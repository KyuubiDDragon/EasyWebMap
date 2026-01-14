package com.easywebmap.map;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TileManager {
    private final EasyWebMap plugin;
    private final TileCache cache;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> pendingRequests;

    public TileManager(EasyWebMap plugin) {
        this.plugin = plugin;
        this.cache = new TileCache(plugin.getConfig().getTileCacheSize());
        this.pendingRequests = new ConcurrentHashMap<>();
    }

    public CompletableFuture<byte[]> getTile(String worldName, int zoom, int tileX, int tileZ) {
        String cacheKey = TileCache.createKey(worldName, zoom, tileX, tileZ);
        byte[] cached = this.cache.get(cacheKey);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<byte[]> pending = this.pendingRequests.get(cacheKey);
        if (pending != null) {
            return pending;
        }
        CompletableFuture<byte[]> future = this.generateTile(worldName, tileX, tileZ);
        this.pendingRequests.put(cacheKey, future);
        future.whenComplete((data, ex) -> {
            this.pendingRequests.remove(cacheKey);
            if (data != null && data.length > 0 && ex == null) {
                this.cache.put(cacheKey, data);
            }
        });
        return future;
    }

    private CompletableFuture<byte[]> generateTile(String worldName, int tileX, int tileZ) {
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(PngEncoder.encodeEmpty(this.plugin.getConfig().getTileSize()));
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

    public void clearCache() {
        this.cache.clear();
    }

    public int getCacheSize() {
        return this.cache.size();
    }
}
