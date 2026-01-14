(function() {
    'use strict';

    // Config - 1 tile = 1 chunk = 32 blocks
    const CHUNK_SIZE = 32;
    const TILE_SIZE = 256;
    const SCALE = TILE_SIZE / CHUNK_SIZE;  // 8 - Leaflet units per block

    // State
    let map = null;
    let tileLayer = null;
    let currentWorld = 'world';
    let websocket = null;
    let playerMarkers = {};
    let playerData = {};  // Store player data for list
    let reconnectTimer = null;
    let playerListCollapsed = false;

    function initMap() {
        // Using CRS.Simple: 1 unit = 1 pixel at zoom 0
        // We want 1 unit = 1 block, so we need to scale tiles
        // Set large bounds to allow zooming out
        const worldBounds = L.latLngBounds(
            L.latLng(-100000, -100000),
            L.latLng(100000, 100000)
        );

        map = L.map('map', {
            crs: L.CRS.Simple,
            minZoom: -6,
            maxZoom: 4,
            zoomSnap: 0.5,
            zoomDelta: 0.5,
            maxBounds: worldBounds,
            maxBoundsViscosity: 1.0
        });

        // Start at origin (zoomed out)
        map.setView([0, 0], -3);

        updateTileLayer();

        map.on('mousemove', function(e) {
            // Convert Leaflet coords to world coords (divide by scale factor)
            const x = Math.round(e.latlng.lng / SCALE);
            const z = Math.round(-e.latlng.lat / SCALE);
            document.getElementById('coords-display').textContent = `X: ${x}, Z: ${z}`;
        });

        map.attributionControl.addAttribution('EasyMap');
    }

    function updateTileLayer() {
        if (tileLayer) {
            map.removeLayer(tileLayer);
        }

        // Custom tile layer - use zoomOffset so tiles are always fetched at native zoom
        tileLayer = L.tileLayer('/api/tiles/' + currentWorld + '/0/{x}/{y}.png', {
            tileSize: TILE_SIZE,
            minNativeZoom: 0,
            maxNativeZoom: 0,
            minZoom: -6,
            maxZoom: 4,
            noWrap: true,
            bounds: [[-100000, -100000], [100000, 100000]],
        });

        tileLayer.addTo(map);
    }

    // Convert world coords to LatLng
    function worldToLatLng(x, z) {
        // X -> lng, Z -> -lat (north is up, Z increases south)
        // Multiply by SCALE since Leaflet uses tile-pixel coords (256px per 32-block chunk)
        return L.latLng(-z * SCALE, x * SCALE);
    }

    async function loadWorlds() {
        try {
            const response = await fetch('/api/worlds');
            const worlds = await response.json();

            const select = document.getElementById('world-select');
            select.innerHTML = '';

            worlds.forEach(world => {
                const option = document.createElement('option');
                option.value = world.name;
                option.textContent = world.name;
                if (world.name === currentWorld) option.selected = true;
                select.appendChild(option);
            });

            if (worlds.length > 0 && !worlds.find(w => w.name === currentWorld)) {
                currentWorld = worlds[0].name;
                updateTileLayer();
            }
        } catch (e) {
            console.error('Failed to load worlds:', e);
        }
    }

    function onWorldChange(e) {
        currentWorld = e.target.value;
        updateTileLayer();
        clearPlayerMarkers();
        updatePlayerList();
    }

    function clearPlayerMarkers() {
        Object.values(playerMarkers).forEach(m => map.removeLayer(m));
        playerMarkers = {};
        playerData = {};
    }

    // Convert radians to degrees
    function radToDeg(rad) {
        return rad * (180 / Math.PI);
    }

    // Create arrow icon with rotation
    function createArrowIcon(yawRadians) {
        // Yaw is in radians, convert to degrees
        // Arrow points up (north) by default, adjust so it points in facing direction
        const yawDeg = radToDeg(yawRadians);
        const rotation = yawDeg + 180;

        return L.divIcon({
            className: 'player-marker',
            html: `<div class="player-arrow" style="transform: rotate(${rotation}deg);"></div>`,
            iconSize: [20, 20],
            iconAnchor: [10, 10]
        });
    }

    // Update arrow rotation directly on DOM element
    function updateArrowRotation(marker, yawRadians) {
        const el = marker.getElement();
        if (el) {
            const arrow = el.querySelector('.player-arrow');
            if (arrow) {
                const yawDeg = radToDeg(yawRadians);
                const rotation = yawDeg + 180;
                arrow.style.transform = `rotate(${rotation}deg)`;
            }
        }
    }

    // WebSocket
    function connectWebSocket() {
        const statusEl = document.getElementById('connection-status');
        statusEl.textContent = 'Connecting...';
        statusEl.className = 'connecting';

        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        websocket = new WebSocket(`${protocol}//${location.host}/ws`);

        websocket.onopen = () => {
            statusEl.textContent = 'Connected';
            statusEl.className = 'connected';
            if (reconnectTimer) {
                clearTimeout(reconnectTimer);
                reconnectTimer = null;
            }
        };

        websocket.onmessage = (e) => {
            try {
                const data = JSON.parse(e.data);
                if (data.type === 'players') updatePlayers(data.worlds);
            } catch (err) {}
        };

        websocket.onclose = () => {
            statusEl.textContent = 'Disconnected';
            statusEl.className = 'disconnected';
            if (!reconnectTimer) {
                reconnectTimer = setTimeout(connectWebSocket, 3000);
            }
        };
    }

    function updatePlayers(worldsData) {
        const players = worldsData[currentWorld] || [];
        const seen = new Set();
        let count = 0;

        // Update player data store
        playerData = {};

        players.forEach(p => {
            seen.add(p.uuid);
            count++;
            const pos = worldToLatLng(p.x, p.z);
            const yaw = p.yaw || 0;
            console.log(`Player ${p.name}: yaw=${yaw}, rotX=${p.rotX}, rotY=${p.rotY}, rotZ=${p.rotZ}`);

            // Store player data
            playerData[p.uuid] = {
                name: p.name,
                uuid: p.uuid,
                x: Math.round(p.x),
                y: Math.round(p.y),
                z: Math.round(p.z),
                yaw: yaw
            };

            if (playerMarkers[p.uuid]) {
                playerMarkers[p.uuid].setLatLng(pos);
                updateArrowRotation(playerMarkers[p.uuid], yaw);
            } else {
                const marker = L.marker(pos, {
                    icon: createArrowIcon(yaw)
                });
                marker.bindTooltip(p.name, {
                    permanent: false,
                    direction: 'top',
                    offset: [0, -12],
                    className: 'player-tooltip'
                });
                marker.addTo(map);
                playerMarkers[p.uuid] = marker;
            }
        });

        Object.keys(playerMarkers).forEach(uuid => {
            if (!seen.has(uuid)) {
                map.removeLayer(playerMarkers[uuid]);
                delete playerMarkers[uuid];
            }
        });

        document.getElementById('player-count-display').textContent = `Players: ${count}`;
        updatePlayerList();
    }

    function updatePlayerList() {
        const listEl = document.getElementById('player-list');
        const players = Object.values(playerData);

        if (players.length === 0) {
            listEl.innerHTML = '<li class="player-list-empty">No players online</li>';
            return;
        }

        // Sort by name
        players.sort((a, b) => a.name.localeCompare(b.name));

        listEl.innerHTML = players.map(p => `
            <li data-uuid="${p.uuid}" onclick="window.focusPlayer('${p.uuid}')">
                <span class="player-icon"></span>
                <span class="player-name">${escapeHtml(p.name)}</span>
                <span class="player-coords">${p.x}, ${p.z}</span>
            </li>
        `).join('');
    }

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // Focus map on player
    window.focusPlayer = function(uuid) {
        const p = playerData[uuid];
        if (p) {
            const pos = worldToLatLng(p.x, p.z);
            map.setView(pos, 0);  // Zoom level 0 for good detail
        }
    };

    function initPlayerListToggle() {
        const toggleBtn = document.getElementById('player-list-toggle');
        const content = document.getElementById('player-list-content');

        toggleBtn.addEventListener('click', () => {
            playerListCollapsed = !playerListCollapsed;
            if (playerListCollapsed) {
                content.classList.add('collapsed');
                toggleBtn.textContent = '+';
            } else {
                content.classList.remove('collapsed');
                toggleBtn.textContent = '-';
            }
        });
    }

    // Init
    document.addEventListener('DOMContentLoaded', () => {
        initMap();
        loadWorlds();
        connectWebSocket();
        initPlayerListToggle();
        document.getElementById('world-select').addEventListener('change', onWorldChange);
        setInterval(loadWorlds, 30000);
    });
})();
