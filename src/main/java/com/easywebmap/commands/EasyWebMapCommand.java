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
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import java.awt.Color;

public class EasyWebMapCommand extends AbstractPlayerCommand {
    private static final Color GREEN = new Color(85, 255, 85);
    private static final Color YELLOW = new Color(255, 255, 85);
    private static final Color RED = new Color(255, 85, 85);
    private static final Color GRAY = new Color(170, 170, 170);
    private final EasyWebMap plugin;
    private final RequiredArg<String> subcommand;

    public EasyWebMapCommand(EasyWebMap plugin) {
        super("easywebmap", "EasyWebMap admin commands");
        this.plugin = plugin;
        this.subcommand = this.withRequiredArg("action", "status|reload|clearcache|pregenerate", ArgTypes.STRING);
        this.requirePermission("easywebmap.admin");
    }

    @Override
    protected void execute(CommandContext ctx, Store<EntityStore> store, Ref<EntityStore> playerRef, PlayerRef playerData, World world) {
        String action = this.subcommand.get(ctx);
        String[] parts = action.split(" ");
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "status" -> this.showStatus(playerData);
            case "reload" -> this.reloadConfig(playerData);
            case "clearcache" -> this.clearCache(playerData);
            case "pregenerate" -> this.pregenerate(playerData, world, parts);
            default -> this.showHelp(playerData);
        }
    }

    private void showStatus(PlayerRef player) {
        int connections = this.plugin.getPlayerTracker().getConnectionCount();
        int port = this.plugin.getConfig().getHttpPort();
        int memoryCacheSize = this.plugin.getTileManager().getMemoryCacheSize();
        boolean diskCacheEnabled = this.plugin.getConfig().isUseDiskCache();

        player.sendMessage(Message.raw("=== EasyWebMap Status ===").color(YELLOW));
        player.sendMessage(Message.raw("Web server: Running on port " + port).color(GREEN));
        player.sendMessage(Message.raw("WebSocket connections: " + connections).color(GREEN));
        player.sendMessage(Message.raw("Memory cache: " + memoryCacheSize + " tiles").color(GREEN));
        player.sendMessage(Message.raw("Disk cache: " + (diskCacheEnabled ? "Enabled" : "Disabled")).color(GREEN));
        player.sendMessage(Message.raw("URL: http://localhost:" + port).color(GREEN));
    }

    private void reloadConfig(PlayerRef player) {
        this.plugin.getConfig().reload();
        player.sendMessage(Message.raw("Configuration reloaded!").color(GREEN));
    }

    private void clearCache(PlayerRef player) {
        this.plugin.getTileManager().clearCache();
        player.sendMessage(Message.raw("All caches cleared (memory + disk)!").color(GREEN));
    }

    private void pregenerate(PlayerRef player, World world, String[] parts) {
        int radius = 10; // default
        if (parts.length > 1) {
            try {
                radius = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Message.raw("Invalid radius. Usage: /easywebmap pregenerate <radius>").color(RED));
                return;
            }
        }

        if (radius < 1) {
            player.sendMessage(Message.raw("Radius must be at least 1.").color(RED));
            return;
        }

        // Get player position as center
        Transform transform = player.getTransform();
        if (transform == null) {
            player.sendMessage(Message.raw("Could not get your position.").color(RED));
            return;
        }
        Vector3d pos = transform.getPosition();
        int centerX = (int) pos.x >> 4; // Convert to chunk coordinates
        int centerZ = (int) pos.z >> 4;

        player.sendMessage(Message.raw("Starting pre-generation of " + ((radius * 2 + 1) * (radius * 2 + 1)) + " tiles...").color(YELLOW));
        player.sendMessage(Message.raw("This runs in the background. Check status with /easywebmap status").color(GRAY));

        int finalRadius = radius;
        this.plugin.getTileManager().pregenerateTiles(world.getName(), centerX, centerZ, radius)
            .thenAccept(count -> {
                player.sendMessage(Message.raw("Pre-generation complete! Generated " + count + " new tiles.").color(GREEN));
            });
    }

    private void showHelp(PlayerRef player) {
        player.sendMessage(Message.raw("=== EasyWebMap Commands ===").color(YELLOW));
        player.sendMessage(Message.raw("/easywebmap status - Show server status").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap reload - Reload configuration").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap clearcache - Clear all tile caches").color(GRAY));
        player.sendMessage(Message.raw("/easywebmap pregenerate <radius> - Pre-generate tiles around you").color(GRAY));
    }
}
