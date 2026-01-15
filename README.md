# EasyWebMap

> **Built for the European Hytale survival server at `play.hyfyve.net`**

A live web map for your Hytale server. View your world in a browser, track players in real-time, and easily integrate with your community website.

---

## Quick Start

1. Download the latest `EasyWebMap.jar` from [Releases](../../releases)
2. Put it in your server's `mods` folder
3. Restart your server
4. Open `http://localhost:8080` in your browser

---

## What You Can Do

### Live World Map
- See your entire world rendered in a web browser
- Terrain updates automatically as players build or destroy blocks
- Zoom in/out and pan around freely
- Uses Hytale's native map rendering (same as the in-game map)

### Real-Time Player Tracking
- See all online players on the map with arrow markers
- Arrows rotate to show which direction players are facing
- Click any player in the sidebar to jump to their location
- Player positions update every second via WebSocket

### Website Integration
Embed the map directly on your community website using an iframe:
```html
<iframe src="http://your-server-ip:8080" width="100%" height="600"></iframe>
```

Or link to it from your server's website, Discord, or forums.

### REST API
Build custom tools using the built-in API:

| Endpoint | Returns |
|----------|---------|
| `GET /api/worlds` | List of available worlds |
| `GET /api/players/{world}` | All players in a world (name, position, direction) |
| `GET /api/tiles/{world}/{z}/{x}/{y}.png` | Map tile image |
| `WS /ws` | Real-time player position updates |

Example: Fetch player positions
```javascript
const response = await fetch('http://your-server:8080/api/players/world');
const players = await response.json();
// [{ name: "Steve", x: 100, y: 64, z: -200, yaw: 1.57 }, ...]
```

Example: WebSocket for live updates
```javascript
const ws = new WebSocket('ws://your-server:8080/ws');
ws.onmessage = (e) => {
  const data = JSON.parse(e.data);
  console.log(data.worlds); // All player positions by world
};
```

### Multi-World Support
- Switch between worlds using the dropdown
- Configure which worlds are visible
- Each world has its own tile cache

---

## Commands

| Command | What it does |
|---------|--------------|
| `/easywebmap status` | Show connection count, cache info, and server status |
| `/easywebmap reload` | Reload the config file |
| `/easywebmap clearcache` | Clear all caches (memory + disk) |
| `/easywebmap pregenerate <radius>` | Pre-generate tiles around your position |

All commands require the `easywebmap.admin` permission.

---

## Configuration

Config file: `mods/cryptobench_EasyWebMap/config.json`

```json
{
  "httpPort": 8080,
  "updateIntervalMs": 1000,
  "tileCacheSize": 20000,
  "enabledWorlds": [],
  "tileSize": 256,
  "maxZoom": 4,
  "renderExploredChunksOnly": true,
  "chunkIndexCacheMs": 30000,
  "useDiskCache": true,
  "tileRefreshRadius": 5,
  "tileRefreshIntervalMs": 60000
}
```

| Setting | Default | What it does |
|---------|---------|--------------|
| `httpPort` | 8080 | Web server port |
| `updateIntervalMs` | 1000 | Player update frequency (ms) |
| `tileCacheSize` | 20000 | Max tiles to cache in memory (~200MB at 10KB/tile) |
| `enabledWorlds` | [] | World whitelist (empty = all) |
| `renderExploredChunksOnly` | true | Only render chunks that players have explored (prevents lag/abuse) |
| `chunkIndexCacheMs` | 30000 | How long to cache the explored chunks index (ms) |
| `useDiskCache` | true | Save tiles to disk for persistence across restarts |
| `tileRefreshRadius` | 5 | Player must be within N chunks for tile to refresh |
| `tileRefreshIntervalMs` | 60000 | Minimum time between tile refreshes (ms) |

### Chunk Index Cache (`chunkIndexCacheMs`)

When `renderExploredChunksOnly` is enabled, the plugin needs to check which chunks have been explored. This requires reading an index from disk. To avoid reading disk on every tile request, the index is cached.

**Trade-off:**
- **Lower value** (e.g., 5000ms): New exploration shows on map faster, but more disk reads
- **Higher value** (e.g., 60000ms): Fewer disk reads, but newly explored areas take longer to appear

**What this means in practice:**

| Cache Time | Disk Reads | Map Freshness |
|------------|------------|---------------|
| 5000 (5s) | ~12/min per world | New chunks visible within 5 seconds |
| 30000 (30s) | ~2/min per world | New chunks visible within 30 seconds |
| 60000 (1min) | ~1/min per world | New chunks visible within 1 minute |

**Example scenario:** A player explores a new area. With `chunkIndexCacheMs: 30000`, the new chunks won't appear on the web map until the cache expires (up to 30 seconds). The tile will show as empty until then.

**Note:** This only affects *newly* explored chunks. Already-explored chunks are always visible. The `/easywebmap clearcache` command clears this cache immediately if needed

### Disk Cache & Smart Refresh

The plugin uses a smart caching system to minimize server load:

1. **Disk Cache**: Tiles are saved as PNG files to `mods/cryptobench_EasyWebMap/tilecache/`. These persist across server restarts, so the first visitor after a restart doesn't trigger mass tile generation.

2. **Smart Refresh**: Tiles only regenerate when:
   - The tile is older than `tileRefreshIntervalMs` (default: 60 seconds), AND
   - A player is within `tileRefreshRadius` chunks (default: 5 chunks)

**Why this matters:**
- If no players are nearby, terrain can't have changed, so the cached tile is always valid
- This means 99% of tile requests serve instantly from cache with zero server load
- Only actively played areas regenerate, and only once per minute at most

**Flow:**
```
Request for tile → Memory cache? → Serve instantly
                          ↓ no
                   Disk cache? → Fresh enough? → Serve from disk
                          ↓ no         ↓ old
                   Generate new   Players nearby? → No: Serve stale (terrain unchanged)
                                         ↓ yes
                                  Regenerate tile
```

### Pre-generation

Use `/easywebmap pregenerate <radius>` to warm the cache:
- Generates tiles in a square around your position
- Skips already-cached tiles and unexplored chunks
- Runs in background with 50ms delay between tiles to avoid lag
- Example: `/easywebmap pregenerate 50` generates up to 10,201 tiles
- No max limit - use what you need (large values will take time)

---

## Common Use Cases

**Public server map** - Let players see where everyone is exploring

**Website widget** - Embed on your server's homepage to show live activity

**Stream overlay** - Display the map on your Twitch/YouTube stream

**Discord bot** - Use the API to post player locations or screenshots

**Admin tool** - Monitor player activity across your server

---

## FAQ

**Q: How do I access the map from another computer?**
Use your server's IP instead of `localhost`: `http://192.168.1.100:8080`

**Q: How do I embed it on my website?**
Use an iframe: `<iframe src="http://your-server:8080" width="800" height="600"></iframe>`

**Q: Can I hide certain worlds?**
Yes, add specific world names to `enabledWorlds` in config. Empty array shows all.

**Q: How do I put it behind a reverse proxy?**
Point nginx/Apache to port 8080. WebSocket path is `/ws`.

**Q: Why do some areas show as empty on the map?**
By default, only explored chunks are rendered (`renderExploredChunksOnly: true`). This prevents server lag and abuse from users scrolling to unexplored areas. Set it to `false` in config if you want to render all chunks (not recommended for public servers).

**Q: Can users abuse the map to lag my server?**
Not with default settings. The `renderExploredChunksOnly` option (enabled by default) prevents rendering unexplored chunks, so scrolling around won't trigger chunk generation.

---

## Building from Source

```bash
mvn clean package
cp target/EasyWebMap-1.0.0.jar /path/to/Server/mods/
```

Requires Java 25+ and Maven 3.8+.

---

## License

MIT - Do whatever you want with it!
