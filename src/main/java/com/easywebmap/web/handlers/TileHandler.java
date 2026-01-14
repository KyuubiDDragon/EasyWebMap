package com.easywebmap.web.handlers;

import com.easywebmap.EasyWebMap;
import com.easywebmap.map.TileManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TileHandler {
    private static final Pattern TILE_PATTERN = Pattern.compile("/api/tiles/([^/]+)/(-?\\d+)/(-?\\d+)/(-?\\d+)\\.png");
    private final EasyWebMap plugin;
    private final TileManager tileManager;

    public TileHandler(EasyWebMap plugin) {
        this.plugin = plugin;
        this.tileManager = new TileManager(plugin);
    }

    public void handle(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }
        Matcher matcher = TILE_PATTERN.matcher(req.uri());
        if (!matcher.matches()) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        String worldName = matcher.group(1);
        int zoom = Integer.parseInt(matcher.group(2));
        int x = Integer.parseInt(matcher.group(3));
        int z = Integer.parseInt(matcher.group(4));
        if (!this.plugin.getConfig().isWorldEnabled(worldName)) {
            this.sendError(ctx, HttpResponseStatus.FORBIDDEN);
            return;
        }
        this.tileManager.getTile(worldName, zoom, x, z).thenAccept(data -> {
            if (!ctx.channel().isActive()) {
                return;
            }
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(data)
            );
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "image/png")
                    .set(HttpHeaderNames.CONTENT_LENGTH, data.length)
                    .set(HttpHeaderNames.CACHE_CONTROL, "max-age=30")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        });
    }

    public TileManager getTileManager() {
        return this.tileManager;
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
