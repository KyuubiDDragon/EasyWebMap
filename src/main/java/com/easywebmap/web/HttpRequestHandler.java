package com.easywebmap.web;

import com.easywebmap.EasyWebMap;
import com.easywebmap.web.handlers.BatchTileHandler;
import com.easywebmap.web.handlers.PlayerHandler;
import com.easywebmap.web.handlers.StaticHandler;
import com.easywebmap.web.handlers.TileHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String ACME_CHALLENGE_PATH = "/.well-known/acme-challenge/";

    private final EasyWebMap plugin;
    private final TileHandler tileHandler;
    private final BatchTileHandler batchTileHandler;
    private final PlayerHandler playerHandler;
    private final StaticHandler staticHandler;
    private final boolean isSecure;

    public HttpRequestHandler(EasyWebMap plugin) {
        this(plugin, false);
    }

    public HttpRequestHandler(EasyWebMap plugin, boolean isSecure) {
        this.plugin = plugin;
        this.isSecure = isSecure;
        this.tileHandler = new TileHandler(plugin);
        this.batchTileHandler = new BatchTileHandler(plugin, plugin.getTileManager());
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

        // Handle ACME HTTP-01 challenge for Let's Encrypt
        if (uri.startsWith(ACME_CHALLENGE_PATH)) {
            this.handleAcmeChallenge(ctx, uri);
            return;
        }
        if (uri.equals("/ws") && this.isWebSocketUpgrade(req)) {
            this.handleWebSocketUpgrade(ctx, req);
            return;
        }
        if (uri.equals("/api/tiles/batch")) {
            if (req.method() == HttpMethod.OPTIONS) {
                this.handleCorsPrelight(ctx);
            } else {
                this.batchTileHandler.handle(ctx, req);
            }
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
        String protocol = this.isSecure ? "wss" : "ws";
        String wsUrl = protocol + "://" + req.headers().get(HttpHeaderNames.HOST) + "/ws";
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

    private void handleAcmeChallenge(ChannelHandlerContext ctx, String uri) {
        String token = uri.substring(ACME_CHALLENGE_PATH.length());
        String response = this.plugin.getAcmeManager() != null
                ? this.plugin.getAcmeManager().getChallengeResponse(token)
                : null;

        if (response != null) {
            io.netty.buffer.ByteBuf content = ctx.alloc().buffer();
            content.writeBytes(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
            httpResponse.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                    .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
        } else {
            this.sendError(ctx, HttpResponseStatus.NOT_FOUND);
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void handleCorsPrelight(ChannelHandlerContext ctx) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers()
            .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "POST, OPTIONS")
            .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")
            .set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
