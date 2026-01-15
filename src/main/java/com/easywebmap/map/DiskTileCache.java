package com.easywebmap.map;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;

public class DiskTileCache {
    private final Path cacheDirectory;
    private final ConcurrentHashMap<String, Long> tileTimestamps;

    public DiskTileCache(Path dataDirectory) {
        this.cacheDirectory = dataDirectory.resolve("tilecache");
        this.tileTimestamps = new ConcurrentHashMap<>();
        try {
            Files.createDirectories(this.cacheDirectory);
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to create tile cache directory: " + e.getMessage());
        }
    }

    public byte[] get(String worldName, int zoom, int x, int z) {
        Path tilePath = getTilePath(worldName, zoom, x, z);
        if (!Files.exists(tilePath)) {
            return null;
        }
        try {
            return Files.readAllBytes(tilePath);
        } catch (IOException e) {
            return null;
        }
    }

    public void put(String worldName, int zoom, int x, int z, byte[] data) {
        Path tilePath = getTilePath(worldName, zoom, x, z);
        try {
            Files.createDirectories(tilePath.getParent());
            Files.write(tilePath, data);
            String key = createKey(worldName, zoom, x, z);
            this.tileTimestamps.put(key, System.currentTimeMillis());
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to cache tile: " + e.getMessage());
        }
    }

    public long getTileAge(String worldName, int zoom, int x, int z) {
        String key = createKey(worldName, zoom, x, z);
        Long cachedTimestamp = this.tileTimestamps.get(key);

        if (cachedTimestamp != null) {
            return System.currentTimeMillis() - cachedTimestamp;
        }

        // Check file modification time
        Path tilePath = getTilePath(worldName, zoom, x, z);
        if (!Files.exists(tilePath)) {
            return Long.MAX_VALUE;
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(tilePath, BasicFileAttributes.class);
            long fileTime = attrs.lastModifiedTime().toMillis();
            this.tileTimestamps.put(key, fileTime);
            return System.currentTimeMillis() - fileTime;
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    public boolean exists(String worldName, int zoom, int x, int z) {
        return Files.exists(getTilePath(worldName, zoom, x, z));
    }

    public void clear() {
        this.tileTimestamps.clear();
        try {
            if (Files.exists(this.cacheDirectory)) {
                Files.walk(this.cacheDirectory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {}
                    });
                Files.createDirectories(this.cacheDirectory);
            }
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to clear tile cache: " + e.getMessage());
        }
    }

    public void clearWorld(String worldName) {
        Path worldDir = this.cacheDirectory.resolve(worldName);
        if (!Files.exists(worldDir)) {
            return;
        }
        try {
            Files.walk(worldDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            System.err.println("[EasyWebMap] Failed to clear world cache: " + e.getMessage());
        }
        // Clear timestamps for this world
        this.tileTimestamps.entrySet().removeIf(entry -> entry.getKey().startsWith(worldName + "/"));
    }

    private Path getTilePath(String worldName, int zoom, int x, int z) {
        return this.cacheDirectory.resolve(worldName).resolve(String.valueOf(zoom))
                .resolve(x + "_" + z + ".png");
    }

    private String createKey(String worldName, int zoom, int x, int z) {
        return worldName + "/" + zoom + "/" + x + "/" + z;
    }

    public Path getCacheDirectory() {
        return this.cacheDirectory;
    }
}
