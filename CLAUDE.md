# EasyMap Plugin - Development Context

## Project Overview
A Dynmap-like web map viewer for Hytale servers. Provides browser-based live map viewing with tile-based rendering, real-time player positions via WebSocket, and a LeafletJS frontend.

**Key Feature:** Uses Hytale's built-in `WorldMapManager.getImageAsync()` - the same system that powers the in-game map. This means live terrain updates as blocks are placed/broken.

## How to Explore the Hytale Server API

The Hytale server API is in `Server/HytaleServer.jar`. Since there's no official documentation, use these commands to discover available classes and methods:

### List All Classes in a Package
```bash
cd "/Users/golemgrid/Library/Application Support/Hytale/install/release/package/game/latest/Server"

# Find all event-related classes
jar -tf HytaleServer.jar | grep -i "event"

# Find map-related classes
jar -tf HytaleServer.jar | grep -i "map"

# Find netty-related classes
jar -tf HytaleServer.jar | grep -i "netty"

# Find all classes in a specific package
jar -tf HytaleServer.jar | grep "com/hypixel/hytale/server/core/event/events/"
```

### Inspect a Class (Methods, Fields, Signatures)
```bash
# Basic class inspection
javap -classpath HytaleServer.jar com.hypixel.hytale.server.core.universe.world.WorldMapManager

# With more detail (private members too)
javap -p -classpath HytaleServer.jar com.hypixel.hytale.server.core.universe.world.MapImage
```

### Key Packages to Explore
```
com.hypixel.hytale.server.core.plugin        # JavaPlugin, PluginBase
com.hypixel.hytale.server.core.event.events  # All events
com.hypixel.hytale.server.core.command       # Command system
com.hypixel.hytale.server.core.entity        # Entity/Player classes
com.hypixel.hytale.server.core.universe      # Universe, World, WorldMapManager
com.hypixel.hytale.component                 # ECS system (Store, Ref, Query)
com.hypixel.hytale.event                     # EventRegistry
com.hypixel.hytale.math.vector               # Vector3d, Vector3f, Vector3i
com.hypixel.hytale.netty                     # NettyUtil for HTTP/WebSocket
```

## Hytale Server Plugin Development

### Plugin Structure
```
plugins/EasyMap/
├── src/main/java/com/easymap/
│   ├── EasyMap.java                   # Main plugin (JavaPlugin)
│   ├── config/
│   │   └── MapConfig.java             # JSON config management
│   ├── commands/
│   │   └── EasyMapCommand.java        # /easymap admin commands
│   ├── web/
│   │   ├── WebServer.java             # Netty HTTP server
│   │   ├── HttpRequestHandler.java    # Request router
│   │   ├── WebSocketHandler.java      # Real-time connections
│   │   └── handlers/
│   │       ├── TileHandler.java       # GET /api/tiles/{world}/{z}/{x}/{y}.png
│   │       ├── PlayerHandler.java     # GET /api/players/{world}
│   │       └── StaticHandler.java     # Serve web frontend
│   ├── map/
│   │   ├── TileManager.java           # Tile generation & caching
│   │   ├── TileCache.java             # LRU memory + disk cache
│   │   └── PngEncoder.java            # MapImage -> PNG bytes
│   └── tracker/
│       └── PlayerTracker.java         # Broadcast player positions
├── src/main/resources/
│   ├── manifest.json
│   └── web/
│       ├── index.html
│       ├── css/map.css
│       └── js/map.js                  # LeafletJS integration
└── pom.xml
```

### manifest.json (PascalCase Required!)
```json
{
    "Group": "cryptobench",
    "Name": "EasyMap",
    "Version": "1.0.0",
    "Main": "com.easymap.EasyMap",
    "Description": "Dynmap-like web map viewer for Hytale",
    "Authors": [],
    "Dependencies": {}
}
```

### Main Plugin Class
```java
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class EasyMap extends JavaPlugin {
    public EasyMap(JavaPluginInit init) { super(init); }

    @Override
    public void setup() {
        // Register commands, events, systems
        getCommandRegistry().registerCommand(new EasyMapCommand(this));
    }

    @Override
    public void start() {
        // Start web server and player tracker
    }

    @Override
    public void shutdown() {
        // Stop web server gracefully
    }
}
```

### Available Registries (from PluginBase)
```java
getEventRegistry()           // For IBaseEvent events (PlayerInteractEvent, etc.)
getEntityStoreRegistry()     // For ECS systems and components on entities
getChunkStoreRegistry()      // For ECS systems and components on chunks
getCommandRegistry()         // For commands
getBlockStateRegistry()      // For block states
getEntityRegistry()          // For entity types
getTaskRegistry()            // For scheduled tasks
getDataDirectory()           // Plugin data folder: mods/Group_PluginName/
```

## EasyMap-Specific APIs

### Tile Generation Flow
```java
// TileManager.getTile()
WorldMapManager mgr = world.getWorldMapManager();
CompletableFuture<MapImage> future = mgr.getImageAsync(chunkX, chunkZ);
future.thenApply(img -> PngEncoder.encode(img, tileSize));
```

### MapImage to PNG Conversion
```java
// MapImage contains int[] data - RGBA pixel array
public class PngEncoder {
    public static byte[] encode(MapImage img, int tileSize) {
        BufferedImage buffered = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, tileSize, tileSize, img.getData(), 0, tileSize);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }
}
```

### Player Position Access
```java
// On world thread
world.execute(() -> {
    Store<EntityStore> store = world.getEntityStore();
    TransformComponent t = store.getComponent(playerRef, TransformComponent.getComponentType());
    Vector3d pos = t.getPosition();
});
```

### Netty HTTP Server Setup
```java
ServerBootstrap bootstrap = new ServerBootstrap()
    .group(NettyUtil.getEventLoopGroup(1, "boss"), NettyUtil.getEventLoopGroup(4, "worker"))
    .channel(NettyUtil.getServerChannel())
    .childHandler(new ChannelInitializer<>() {
        void initChannel(Channel ch) {
            ch.pipeline()
                .addLast(new HttpServerCodec())
                .addLast(new HttpObjectAggregator(65536))
                .addLast(new HttpRequestHandler(plugin));
        }
    });
bootstrap.bind(port).sync();
```

### WebSocket Upgrade
```java
// In HttpRequestHandler, detect upgrade request
if (req.headers().get(HttpHeaderNames.UPGRADE) != null) {
    WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
        "ws://" + req.headers().get(HttpHeaderNames.HOST) + "/ws", null, false);
    WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
    handshaker.handshake(ctx.channel(), req);
}
```

## API Endpoints

| Endpoint | Method | Response | Description |
|----------|--------|----------|-------------|
| `/api/tiles/{world}/{z}/{x}/{y}.png` | GET | image/png | Map tile at zoom z, coords x,y |
| `/api/players/{world}` | GET | JSON | Player positions in world |
| `/api/worlds` | GET | JSON | List of enabled worlds |
| `/ws` | WebSocket | JSON frames | Real-time player updates |
| `/*` | GET | HTML/CSS/JS | Static web frontend |

## Commands

```java
public class EasyMapCommand extends AbstractPlayerCommand {
    private final RequiredArg<String> subcommand;

    public EasyMapCommand(EasyMap plugin) {
        super("easymap", "EasyMap admin commands");
        this.subcommand = withRequiredArg("action", "status|reload|clearcache", ArgTypes.STRING);
        requirePermission("easymap.admin");
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store,
                          Ref<EntityStore> playerRef, PlayerRef playerData, World world) {
        String action = subcommand.get(ctx);
        switch (action) {
            case "status" -> // Show connection count
            case "reload" -> // Reload config
            case "clearcache" -> // Clear tile cache
        }
    }
}
```

## Messages (No Minecraft Color Codes!)
```java
import com.hypixel.hytale.server.core.Message;
import java.awt.Color;

// Correct
playerData.sendMessage(Message.raw("Success!").color(new Color(85, 255, 85)));

// WRONG - shows literal "§a"
playerData.sendMessage(Message.raw("§aSuccess!"));

// Common colors
Color GREEN = new Color(85, 255, 85);
Color RED = new Color(255, 85, 85);
Color YELLOW = new Color(255, 255, 85);
Color GOLD = new Color(255, 170, 0);
Color GRAY = new Color(170, 170, 170);
```

## Configuration
```java
public class MapConfig {
    private int httpPort = 8080;
    private int updateIntervalMs = 1000;
    private int tileCacheSize = 500;
    private List<String> enabledWorlds = new ArrayList<>();  // empty = all
    private int tileSize = 256;
    private int maxZoom = 4;
}
```

## Data Storage
```java
Path dataDir = getDataDirectory();  // mods/cryptobench_EasyMap/
Gson gson = new GsonBuilder().setPrettyPrinting().create();  // Gson provided by Hytale
```

## Building & Installation
```bash
cd /Users/golemgrid/Documents/GitHub.nosync/EasyMap
mvn clean package
# Copy target/EasyMap-1.0.0.jar to Server/mods/
```

## Verification
1. Build: `mvn clean package`
2. Install: Copy JAR to `Server/mods/`
3. Start server, check console for "EasyMap web server started on port 8080"
4. Open browser to `http://localhost:8080`
5. Verify:
   - Map tiles load and display terrain
   - Player markers appear and update in real-time
   - `/easymap status` shows connection count
   - `/easymap clearcache` clears tiles

## Key Imports
```java
// Plugin
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

// Events
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.*;

// ECS
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

// Commands
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;

// Entity/Player
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldMapManager;

// Math
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;

// Messages
import com.hypixel.hytale.server.core.Message;

// Netty (for HTTP/WebSocket)
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import com.hypixel.hytale.netty.NettyUtil;
```

## Reference Files

Pattern sources from existing plugins:
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/src/main/java/com/easytpa/EasyTPA.java` - Plugin lifecycle
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/src/main/java/com/easytpa/config/TpaConfig.java` - JSON config
- `/Users/golemgrid/Documents/GitHub.nosync/EasyClaims/src/main/java/com/easyclaims/map/ClaimImageBuilder.java` - Async image generation
- `/Users/golemgrid/Documents/GitHub.nosync/EasyTPA/pom.xml` - Maven build setup
