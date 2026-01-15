package com.easywebmap.web;

import com.easywebmap.EasyWebMap;
import com.hypixel.hytale.server.core.io.netty.NettyUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;

import java.util.concurrent.atomic.AtomicReference;

public class WebServer {
    private final EasyWebMap plugin;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel httpChannel;
    private Channel httpsChannel;
    private final AtomicReference<SslContext> sslContext = new AtomicReference<>();

    public WebServer(EasyWebMap plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int httpPort = this.plugin.getConfig().getHttpPort();
        this.bossGroup = NettyUtil.getEventLoopGroup(1, "easywebmap-boss");
        this.workerGroup = NettyUtil.getEventLoopGroup(4, "easywebmap-worker");

        try {
            // Always start HTTP server (needed for ACME challenges)
            ServerBootstrap httpBootstrap = new ServerBootstrap()
                    .group(this.bossGroup, this.workerGroup)
                    .channel(NettyUtil.getServerChannel())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline()
                                    .addLast("codec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(262144))
                                    .addLast("handler", new HttpRequestHandler(plugin, false));
                        }
                    });
            this.httpChannel = httpBootstrap.bind(httpPort).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[EasyWebMap] Failed to start HTTP server: " + e.getMessage());
        }
    }

    public void startHttps(SslContext ctx) {
        if (ctx == null) {
            System.err.println("[EasyWebMap] Cannot start HTTPS: no SSL context provided");
            return;
        }

        this.sslContext.set(ctx);
        int httpsPort = this.plugin.getConfig().getHttpsPort();

        try {
            ServerBootstrap httpsBootstrap = new ServerBootstrap()
                    .group(this.bossGroup, this.workerGroup)
                    .channel(NettyUtil.getServerChannel())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            SslContext ssl = sslContext.get();
                            if (ssl != null) {
                                ch.pipeline().addLast("ssl", ssl.newHandler(ch.alloc()));
                            }
                            ch.pipeline()
                                    .addLast("codec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(262144))
                                    .addLast("handler", new HttpRequestHandler(plugin, true));
                        }
                    });
            this.httpsChannel = httpsBootstrap.bind(httpsPort).sync().channel();
            System.out.println("[EasyWebMap] HTTPS server started on port " + httpsPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[EasyWebMap] Failed to start HTTPS server: " + e.getMessage());
        }
    }

    public void reloadSslContext(SslContext newContext) {
        if (newContext != null) {
            this.sslContext.set(newContext);
            System.out.println("[EasyWebMap] SSL context reloaded");
        }
    }

    public void shutdown() {
        if (this.httpsChannel != null) {
            this.httpsChannel.close();
        }
        if (this.httpChannel != null) {
            this.httpChannel.close();
        }
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
    }

    public boolean isHttpsRunning() {
        return this.httpsChannel != null && this.httpsChannel.isActive();
    }

    public EasyWebMap getPlugin() {
        return this.plugin;
    }
}
