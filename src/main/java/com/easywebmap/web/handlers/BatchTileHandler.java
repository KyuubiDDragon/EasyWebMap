package com.easywebmap.web.handlers;

import com.easywebmap.EasyWebMap;
import com.easywebmap.map.TileManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class BatchTileHandler {
    private static final Gson GSON = new Gson();
    private static final int MAX_BATCH_SIZE = 200;

    private final EasyWebMap plugin;
    private final TileManager tileManager;

    public BatchTileHandler(EasyWebMap plugin, TileManager tileManager) {
        this.plugin = plugin;
        this.tileManager = tileManager;
    }

    public void handle(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        String body = req.content().toString(StandardCharsets.UTF_8);
        JsonObject requestJson;
        try {
            requestJson = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        if (!requestJson.has("world") || !requestJson.has("tiles")) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST);
            return;
        }

        String worldName = requestJson.get("world").getAsString();
        if (!this.plugin.getConfig().isWorldEnabled(worldName)) {
            this.sendError(ctx, HttpResponseStatus.FORBIDDEN);
            return;
        }

        JsonArray tilesArray = requestJson.getAsJsonArray("tiles");
        if (tilesArray.size() > MAX_BATCH_SIZE) {
            this.sendError(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
            return;
        }

        List<TileCoord> coords = new ArrayList<>();
        for (int i = 0; i < tilesArray.size(); i++) {
            JsonObject tile = tilesArray.get(i).getAsJsonObject();
            int z = tile.get("z").getAsInt();
            int x = tile.get("x").getAsInt();
            int y = tile.get("y").getAsInt();
            coords.add(new TileCoord(z, x, y));
        }

        boolean keepAlive = HttpUtil.isKeepAlive(req);
        Map<String, CompletableFuture<byte[]>> futures = new LinkedHashMap<>();
        for (TileCoord coord : coords) {
            String key = coord.z + "/" + coord.x + "/" + coord.y;
            CompletableFuture<byte[]> future = this.tileManager.getTile(worldName, coord.z, coord.x, coord.y);
            futures.put(key, future);
        }

        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenAccept(v -> {
                if (!ctx.channel().isActive()) {
                    return;
                }

                JsonObject response = new JsonObject();
                JsonObject tilesObj = new JsonObject();

                for (Map.Entry<String, CompletableFuture<byte[]>> entry : futures.entrySet()) {
                    byte[] data = entry.getValue().join();
                    JsonObject tileObj = new JsonObject();

                    if (this.isEmptyTile(data)) {
                        tileObj.addProperty("empty", true);
                    } else {
                        tileObj.addProperty("data", Base64.getEncoder().encodeToString(data));
                    }
                    tilesObj.add(entry.getKey(), tileObj);
                }

                response.add("tiles", tilesObj);
                response.addProperty("timestamp", System.currentTimeMillis());

                String json = GSON.toJson(response);
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

                DefaultFullHttpResponse httpResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(bytes)
                );
                httpResponse.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                    .set(HttpHeaderNames.CONTENT_LENGTH, bytes.length)
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "POST, OPTIONS")
                    .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");

                if (keepAlive) {
                    httpResponse.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
                    ctx.writeAndFlush(httpResponse);
                } else {
                    ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
                }
            });
    }

    private boolean isEmptyTile(byte[] data) {
        return data == null || data.length < 500;
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers()
                .set(HttpHeaderNames.CONTENT_LENGTH, 0)
                .set(HttpHeaderNames.CONNECTION, "close");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static class TileCoord {
        final int z;
        final int x;
        final int y;

        TileCoord(int z, int x, int y) {
            this.z = z;
            this.x = x;
            this.y = y;
        }
    }
}
