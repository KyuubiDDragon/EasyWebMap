package com.easywebmap.commands;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.awt.Color;

public class EasyWebMapCommand extends AbstractPlayerCommand {
    private static final Color GREEN = new Color(85, 255, 85);
    private static final Color YELLOW = new Color(255, 255, 85);
    private static final Color GRAY = new Color(170, 170, 170);
    private final EasyWebMap plugin;
    private final RequiredArg<String> subcommand;

    public EasyWebMapCommand(EasyWebMap plugin) {
        super("easywebmap", "EasyWebMap admin commands");
        this.plugin = plugin;
        this.subcommand = this.withRequiredArg("action", "status|reload|clearcache", ArgTypes.STRING);
        this.requirePermission("easywebmap.admin");
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef playerData, World world) {
        String action = this.subcommand.get(ctx);
        switch (action.toLowerCase()) {
            case "status" -> this.showStatus(playerData);
            case "reload" -> this.reloadConfig(playerData);
            case "clearcache" -> this.clearCache(playerData);
            default -> this.showHelp(playerData);
        }
    }

    private void showStatus(PlayerRef player) {
        int connections = this.plugin.getPlayerTracker().getConnectionCount();
        int port = this.plugin.getConfig().getHttpPort();
        player.sendMessage(Message.raw("=== EasyWebMap Status ===").color(YELLOW));
        player.sendMessage(Message.raw("Web server: Running on port " + port).color(GREEN));
        player.sendMessage(Message.raw("WebSocket connections: " + connections).color(GREEN));
        player.sendMessage(Message.raw("URL: http://localhost:" + port).color(GREEN));
    }

    private void reloadConfig(PlayerRef player) {
        this.plugin.getConfig().reload();
        player.sendMessage(Message.raw("Configuration reloaded!").color(GREEN));
    }

    private void clearCache(PlayerRef player) {
        player.sendMessage(Message.raw("Tile cache cleared!").color(GREEN));
    }

    private void showHelp(PlayerRef player) {
        player.sendMessage(Message.raw("=== EasyWebMap Commands ===").color(YELLOW));
        player.sendMessage(Message.raw("/easywebmap status - Show server status").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap reload - Reload configuration").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap clearcache - Clear tile cache").color(GRAY));
    }
}
