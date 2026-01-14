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
| `/easywebmap status` | Show connection count and server info |
| `/easywebmap reload` | Reload the config file |
| `/easywebmap clearcache` | Clear cached map tiles |

All commands require the `easywebmap.admin` permission.

---

## Configuration

Config file: `mods/cryptobench_EasyWebMap/config.json`

```json
{
  "httpPort": 8080,
  "updateIntervalMs": 1000,
  "tileCacheSize": 500,
  "enabledWorlds": [],
  "tileSize": 256,
  "maxZoom": 4
}
```

| Setting | Default | What it does |
|---------|---------|--------------|
| `httpPort` | 8080 | Web server port |
| `updateIntervalMs` | 1000 | Player update frequency (ms) |
| `tileCacheSize` | 500 | Max tiles to cache in memory |
| `enabledWorlds` | [] | World whitelist (empty = all) |

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
