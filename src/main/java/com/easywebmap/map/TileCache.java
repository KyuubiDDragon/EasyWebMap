package com.easywebmap.map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free tile cache using ConcurrentHashMap.
 * Uses a deque to track access order for LRU eviction.
 */
public class TileCache {
    private final ConcurrentHashMap<String, byte[]> cache;
    private final ConcurrentLinkedDeque<String> accessOrder;
    private final int maxSize;
    private final AtomicInteger size;

    public TileCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>(maxSize);
        this.accessOrder = new ConcurrentLinkedDeque<>();
        this.size = new AtomicInteger(0);
    }

    public byte[] get(String key) {
        byte[] value = this.cache.get(key);
        if (value != null) {
            // Move to end for LRU (async, non-blocking)
            this.accessOrder.remove(key);
            this.accessOrder.addLast(key);
        }
        return value;
    }

    public void put(String key, byte[] data) {
        if (this.cache.putIfAbsent(key, data) == null) {
            this.accessOrder.addLast(key);
            int currentSize = this.size.incrementAndGet();
            // Evict if over capacity
            while (currentSize > this.maxSize) {
                String oldest = this.accessOrder.pollFirst();
                if (oldest != null && this.cache.remove(oldest) != null) {
                    currentSize = this.size.decrementAndGet();
                } else {
                    break;
                }
            }
        } else {
            // Update existing
            this.cache.put(key, data);
            this.accessOrder.remove(key);
            this.accessOrder.addLast(key);
        }
    }

    public void clear() {
        this.cache.clear();
        this.accessOrder.clear();
        this.size.set(0);
    }

    public int size() {
        return this.size.get();
    }

    public static String createKey(String worldName, int zoom, int x, int z) {
        return worldName + "/" + zoom + "/" + x + "/" + z;
    }
}
