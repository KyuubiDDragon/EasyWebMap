package com.easywebmap.web;

import com.easywebmap.EasyWebMap;
import com.easywebmap.web.handlers.PlayerHandler;
import com.easywebmap.web.handlers.StaticHandler;
import com.easywebmap.web.handlers.TileHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final EasyWebMap plugin;
    private final TileHandler tileHandler;
    private final PlayerHandler playerHandler;
    private final StaticHandler staticHandler;

    public HttpRequestHandler(EasyWebMap plugin) {
        this.plugin = plugin;
        this.tileHandler = new TileHandler(plugin);
        this.playerHandler = new PlayerHandler(plugin);
        this.staticHandler = new StaticHandler();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.decoderResult().isSuccess()) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }
        String uri = req.uri();
        if (uri.equals("/ws") && this.isWebSocketUpgrade(req)) {
            this.handleWebSocketUpgrade(ctx, req);
            return;
        }
        if (uri.startsWith("/api/tiles/")) {
            this.tileHandler.handle(ctx, req);
        } else if (uri.startsWith("/api/players/")) {
            this.playerHandler.handlePlayers(ctx, req);
        } else if (uri.equals("/api/worlds")) {
            this.playerHandler.handleWorlds(ctx, req);
        } else {
            this.staticHandler.handle(ctx, req);
        }
    }

    private boolean isWebSocketUpgrade(FullHttpRequest req) {
        String upgrade = req.headers().get(HttpHeaderNames.UPGRADE);
        return upgrade != null && upgrade.equalsIgnoreCase("websocket");
    }

    private void handleWebSocketUpgrade(ChannelHandlerContext ctx, FullHttpRequest req) {
        String wsUrl = "ws://" + req.headers().get(HttpHeaderNames.HOST) + "/ws";
        WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(wsUrl, null, false);
        WebSocketServerHandshaker handshaker = factory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
            ctx.pipeline().replace(this, "websocket", new WebSocketHandler(this.plugin, handshaker));
            this.plugin.getPlayerTracker().addChannel(ctx.channel());
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
