package com.easywebmap;

import com.easywebmap.commands.EasyWebMapCommand;
import com.easywebmap.config.MapConfig;
import com.easywebmap.map.TileManager;
import com.easywebmap.ssl.AcmeManager;
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
    private AcmeManager acmeManager;

    public EasyWebMap(JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        this.config = new MapConfig(this.getDataDirectory());
        this.tileManager = new TileManager(this);
        this.webServer = new WebServer(this);
        this.playerTracker = new PlayerTracker(this);

        if (this.config.isHttpsEnabled()) {
            this.acmeManager = new AcmeManager(this);
        }

        this.getCommandRegistry().registerCommand((AbstractCommand) new EasyWebMapCommand(this));
    }

    @Override
    public void start() {
        this.webServer.start();
        this.playerTracker.start();
        System.out.println("[EasyWebMap] HTTP server started on port " + this.config.getHttpPort());

        if (this.config.isHttpsEnabled() && this.acmeManager != null) {
            this.acmeManager.initialize().thenAccept(success -> {
                if (success) {
                    this.webServer.startHttps(this.acmeManager.getSslContext());
                    this.acmeManager.startRenewalScheduler();
                    System.out.println("[EasyWebMap] HTTPS enabled on port " + this.config.getHttpsPort());
                } else {
                    System.err.println("[EasyWebMap] HTTPS initialization failed, running HTTP only");
                }
            });
        }
    }

    @Override
    public void shutdown() {
        if (this.acmeManager != null) {
            this.acmeManager.shutdown();
        }
        if (this.playerTracker != null) {
            this.playerTracker.shutdown();
        }
        if (this.webServer != null) {
            this.webServer.shutdown();
        }
        if (this.tileManager != null) {
            this.tileManager.shutdown();
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

    public AcmeManager getAcmeManager() {
        return this.acmeManager;
    }

    public java.nio.file.Path getDataDirectory() {
        return super.getDataDirectory();
    }
}
