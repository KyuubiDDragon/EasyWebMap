package com.easywebmap.map;

import java.util.LinkedHashMap;
import java.util.Map;

public class TileCache {
    private final LinkedHashMap<String, byte[]> memoryCache;
    private final int maxSize;

    public TileCache(int maxSize) {
        this.maxSize = maxSize;
        this.memoryCache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > TileCache.this.maxSize;
            }
        };
    }

    public synchronized byte[] get(String key) {
        return this.memoryCache.get(key);
    }

    public synchronized void put(String key, byte[] data) {
        this.memoryCache.put(key, data);
    }

    public synchronized void clear() {
        this.memoryCache.clear();
    }

    public synchronized int size() {
        return this.memoryCache.size();
    }

    public static String createKey(String worldName, int zoom, int x, int z) {
        return worldName + "/" + zoom + "/" + x + "/" + z;
    }
}
