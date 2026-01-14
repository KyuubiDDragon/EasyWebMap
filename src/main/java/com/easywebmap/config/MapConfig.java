package com.easywebmap.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Reader;
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
        if (Files.exists(this.configFile)) {
            try (BufferedReader reader = Files.newBufferedReader(this.configFile)) {
                this.data = GSON.fromJson(reader, ConfigData.class);
                if (this.data == null) {
                    this.data = new ConfigData();
                }
            } catch (Exception e) {
                this.data = new ConfigData();
            }
        } else {
            this.data = new ConfigData();
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

    private static class ConfigData {
        int httpPort = 8080;
        int updateIntervalMs = 1000;
        int tileCacheSize = 500;
        List<String> enabledWorlds = new ArrayList<>();
        int tileSize = 256;
        int maxZoom = 4;
    }
}
