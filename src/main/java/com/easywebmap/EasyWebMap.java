package com.easywebmap;

import com.easywebmap.commands.EasyWebMapCommand;
import com.easywebmap.config.MapConfig;
import com.easywebmap.map.TileManager;
import com.easywebmap.tracker.PlayerTracker;
import com.easywebmap.web.WebServer;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

public class EasyWebMap extends JavaPlugin {
    private MapConfig config;
    private TileManager tileManager;
    private WebServer webServer;
    private PlayerTracker playerTracker;

    public EasyWebMap(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        this.config = new MapConfig(this.getDataDirectory());
        this.tileManager = new TileManager(this);
        this.webServer = new WebServer(this);
        this.playerTracker = new PlayerTracker(this);
        this.getCommandRegistry().registerCommand((AbstractCommand) new EasyWebMapCommand(this));
    }

    @Override
    public void start() {
        this.webServer.start();
        this.playerTracker.start();
        System.out.println("[EasyWebMap] Web server started on port " + this.config.getHttpPort());
    }

    @Override
    public void shutdown() {
        if (this.playerTracker != null) {
            this.playerTracker.shutdown();
        }
        if (this.webServer != null) {
            this.webServer.shutdown();
        }
        System.out.println("[EasyWebMap] Shutdown complete");
    }

    public MapConfig getConfig() {
        return this.config;
    }

    public WebServer getWebServer() {
        return this.webServer;
    }

    public PlayerTracker getPlayerTracker() {
        return this.playerTracker;
    }

    public TileManager getTileManager() {
        return this.tileManager;
    }
}
