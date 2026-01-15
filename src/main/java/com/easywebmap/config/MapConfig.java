package com.easywebmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path configFile;
    private ConfigData data;

    public MapConfig(Path dataDirectory) {
        this.configFile = dataDirectory.resolve("config.json");
        this.load();
    }

    private void load() {
        ConfigData defaults = new ConfigData();
        boolean needsSave = false;

        if (Files.exists(this.configFile)) {
            try {
                String json = Files.readString(this.configFile);
                JsonObject jsonObj = JsonParser.parseString(json).getAsJsonObject();

                this.data = GSON.fromJson(jsonObj, ConfigData.class);
                if (this.data == null) {
                    this.data = defaults;
                    needsSave = true;
                } else {
                    // Apply defaults for missing fields
                    if (this.data.enabledWorlds == null) {
                        this.data.enabledWorlds = defaults.enabledWorlds;
                        needsSave = true;
                    }
                    // Check if new fields are missing and apply defaults
                    if (!jsonObj.has("renderExploredChunksOnly")) {
                        this.data.renderExploredChunksOnly = defaults.renderExploredChunksOnly;
                        needsSave = true;
                    }
                    if (!jsonObj.has("chunkIndexCacheMs")) {
                        this.data.chunkIndexCacheMs = defaults.chunkIndexCacheMs;
                        needsSave = true;
                    }
                    if (!jsonObj.has("tileRefreshRadius")) {
                        this.data.tileRefreshRadius = defaults.tileRefreshRadius;
                        needsSave = true;
                    }
                    if (!jsonObj.has("tileRefreshIntervalMs")) {
                        this.data.tileRefreshIntervalMs = defaults.tileRefreshIntervalMs;
                        needsSave = true;
                    }
                    if (!jsonObj.has("useDiskCache")) {
                        this.data.useDiskCache = defaults.useDiskCache;
                        needsSave = true;
                    }
                }
            } catch (Exception e) {
                this.data = defaults;
                needsSave = true;
            }
        } else {
            this.data = defaults;
            needsSave = true;
        }

        if (needsSave) {
            this.save();
        }
    }

    public void save() {
        try {
            Files.createDirectories(this.configFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(this.configFile)) {
                GSON.toJson(this.data, writer);
            }
        } catch (Exception e) {
            System.err.println("[EasyWebMap] Failed to save config: " + e.getMessage());
        }
    }

    public void reload() {
        this.load();
    }

    public int getHttpPort() {
        return this.data.httpPort;
    }

    public int getUpdateIntervalMs() {
        return this.data.updateIntervalMs;
    }

    public int getTileCacheSize() {
        return this.data.tileCacheSize;
    }

    public List<String> getEnabledWorlds() {
        return this.data.enabledWorlds;
    }

    public int getTileSize() {
        return this.data.tileSize;
    }

    public int getMaxZoom() {
        return this.data.maxZoom;
    }

    public boolean isWorldEnabled(String worldName) {
        return this.data.enabledWorlds.isEmpty() || this.data.enabledWorlds.contains(worldName);
    }

    public boolean isRenderExploredChunksOnly() {
        return this.data.renderExploredChunksOnly;
    }

    public long getChunkIndexCacheMs() {
        return this.data.chunkIndexCacheMs;
    }

    public int getTileRefreshRadius() {
        return this.data.tileRefreshRadius;
    }

    public long getTileRefreshIntervalMs() {
        return this.data.tileRefreshIntervalMs;
    }

    public boolean isUseDiskCache() {
        return this.data.useDiskCache;
    }

    private static class ConfigData {
        int httpPort = 8080;
        int updateIntervalMs = 1000;
        int tileCacheSize = 20000;
        List<String> enabledWorlds = new ArrayList<>();
        int tileSize = 256;
        int maxZoom = 4;
        boolean renderExploredChunksOnly = true;
        long chunkIndexCacheMs = 30000;
        int tileRefreshRadius = 5;
        long tileRefreshIntervalMs = 60000;
        boolean useDiskCache = true;
    }
}
