package com.easywebmap.map;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TileManager {
    private final EasyWebMap plugin;
    private final TileCache memoryCache;
    private final DiskTileCache diskCache;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests;
    private final ConcurrentHashMap<String, CachedChunkIndexes> chunkIndexCache;

    public TileManager(EasyWebMap plugin) {
        this.plugin = plugin;
        this.memoryCache = new TileCache(plugin.getConfig().getTileCacheSize());
        this.diskCache = new DiskTileCache(plugin.getDataDirectory());
        this.pendingRequests = new ConcurrentHashMap<>();
        this.chunkIndexCache = new ConcurrentHashMap<>();
    }

    public CompletableFuture<byte[]> getTile(String worldName, int zoom, int tileX, int tileZ) {
        String cacheKey = TileCache.createKey(worldName, zoom, tileX, tileZ);

        // 1. Check memory cache first (fastest)
        byte[] memoryCached = this.memoryCache.get(cacheKey);
        if (memoryCached != null) {
            return CompletableFuture.completedFuture(memoryCached);
        }

        // 2. Check if already generating
        CompletableFuture<byte[]> pending = this.pendingRequests.get(cacheKey);
        if (pending != null) {
            return pending;
        }

        // 3. Check disk cache if enabled
        if (this.plugin.getConfig().isUseDiskCache()) {
            byte[] diskCached = this.diskCache.get(worldName, zoom, tileX, tileZ);
            if (diskCached != null) {
                long tileAge = this.diskCache.getTileAge(worldName, zoom, tileX, tileZ);
                long refreshInterval = this.plugin.getConfig().getTileRefreshIntervalMs();

                // If tile is fresh enough, serve from disk
                if (tileAge < refreshInterval) {
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }

                // Tile is old - check if players are nearby
                World world = Universe.get().getWorld(worldName);
                if (world == null || !this.arePlayersNearby(world, tileX, tileZ)) {
                    // No players nearby - terrain can't have changed, serve cached
                    this.memoryCache.put(cacheKey, diskCached);
                    return CompletableFuture.completedFuture(diskCached);
                }

                // Players nearby and tile is old - regenerate
            }
        }

        // 4. Generate new tile
        CompletableFuture<byte[]> future = this.generateTile(worldName, zoom, tileX, tileZ);
        this.pendingRequests.put(cacheKey, future);
        future.whenComplete((data, ex) -> {
            this.pendingRequests.remove(cacheKey);
            if (data != null && data.length > 0 && ex == null) {
                this.memoryCache.put(cacheKey, data);
                if (this.plugin.getConfig().isUseDiskCache()) {
                    this.diskCache.put(worldName, zoom, tileX, tileZ, data);
                }
            }
        });
        return future;
    }

    private boolean arePlayersNearby(World world, int tileX, int tileZ) {
        int radius = this.plugin.getConfig().getTileRefreshRadius();

        for (PlayerRef playerRef : world.getPlayerRefs()) {
            try {
                Transform transform = playerRef.getTransform();
                if (transform == null) continue;

                Vector3d pos = transform.getPosition();
                int playerChunkX = ChunkUtil.chunkCoordinate((int) pos.x);
                int playerChunkZ = ChunkUtil.chunkCoordinate((int) pos.z);

                int dx = Math.abs(playerChunkX - tileX);
                int dz = Math.abs(playerChunkZ - tileZ);

                if (dx <= radius && dz <= radius) {
                    return true;
                }
            } catch (Exception e) {
                // Skip this player
            }
        }
        return false;
    }

    private CompletableFuture<byte[]> generateTile(String worldName, int zoom, int tileX, int tileZ) {
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
        }

        // Check if we should only render explored chunks
        if (this.plugin.getConfig().isRenderExploredChunksOnly()) {
            if (!this.isChunkExplored(world, tileX, tileZ)) {
                return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
            }
        }

        WorldMapManager mapManager = world.getWorldMapManager();
        return mapManager.getImageAsync(tileX, tileZ)
                .thenApply(mapImage -> {
                    if (mapImage == null) {
                        return PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize());
                    }
                    return PngEncoder.encode(mapImage, this.plugin.getConfig().getTileSize());
                })
                .exceptionally(ex -> {
                    System.err.println("[EasyWebMap] Failed to generate tile: " + ex.getMessage());
                    return PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize());
                });
    }

    private boolean isChunkExplored(World world, int chunkX, int chunkZ) {
        try {
            LongSet indexes = this.getCachedChunkIndexes(world);
            if (indexes == null) {
                return true; // Fail open if we can't get indexes
            }
            long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);
            return indexes.contains(chunkIndex);
        } catch (Exception e) {
            // If we can't check, allow rendering (fail open for usability)
            return true;
        }
    }

    private LongSet getCachedChunkIndexes(World world) {
        String worldName = world.getName();
        CachedChunkIndexes cached = this.chunkIndexCache.get(worldName);
        long now = System.currentTimeMillis();

        if (cached != null && (now - cached.timestamp) < this.plugin.getConfig().getChunkIndexCacheMs()) {
            return cached.indexes;
        }

        // Refresh the cache
        try {
            ChunkStore chunkStore = world.getChunkStore();
            IChunkLoader loader = chunkStore.getLoader();
            if (loader == null) {
                return null;
            }
            LongSet indexes = loader.getIndexes();
            this.chunkIndexCache.put(worldName, new CachedChunkIndexes(indexes, now));
            return indexes;
        } catch (Exception e) {
            return cached != null ? cached.indexes : null;
        }
    }

    public CompletableFuture<Integer> pregenerateTiles(String worldName, int centerX, int centerZ, int radius) {
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(0);
        }

        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    // Skip if already cached on disk
                    if (this.diskCache.exists(worldName, 0, x, z)) {
                        continue;
                    }

                    // Skip unexplored chunks
                    if (this.plugin.getConfig().isRenderExploredChunksOnly()) {
                        if (!this.isChunkExplored(world, x, z)) {
                            continue;
                        }
                    }

                    try {
                        // Generate and wait
                        byte[] tile = this.generateTile(worldName, 0, x, z).join();
                        if (tile != null && tile.length > 100) {
                            this.diskCache.put(worldName, 0, x, z, tile);
                            count++;
                        }
                        // Small delay to not overload
                        Thread.sleep(50);
                    } catch (Exception e) {
                        // Continue with next tile
                    }
                }
            }
            return count;
        });
    }

    public void clearCache() {
        this.memoryCache.clear();
        this.diskCache.clear();
        this.chunkIndexCache.clear();
    }

    public void clearMemoryCache() {
        this.memoryCache.clear();
    }

    public int getMemoryCacheSize() {
        return this.memoryCache.size();
    }

    public DiskTileCache getDiskCache() {
        return this.diskCache;
    }

    private static class CachedChunkIndexes {
        final LongSet indexes;
        final long timestamp;

        CachedChunkIndexes(LongSet indexes, long timestamp) {
            this.indexes = indexes;
            this.timestamp = timestamp;
        }
    }
}
