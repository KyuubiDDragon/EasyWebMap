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

public class WebServer {
    private final EasyWebMap plugin;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public WebServer(EasyWebMap plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int port = this.plugin.getConfig().getHttpPort();
        this.bossGroup = NettyUtil.getEventLoopGroup(1, "easywebmap-boss");
        this.workerGroup = NettyUtil.getEventLoopGroup(4, "easywebmap-worker");
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(this.bossGroup, this.workerGroup)
                    .channel(NettyUtil.getServerChannel())
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline()
                                    .addLast("codec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(65536))
                                    .addLast("handler", new HttpRequestHandler(plugin));
                        }
                    });
            this.serverChannel = bootstrap.bind(port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[EasyWebMap] Failed to start web server: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (this.serverChannel != null) {
            this.serverChannel.close();
        }
        if (this.workerGroup != null) {
            this.workerGroup.shutdownGracefully();
        }
        if (this.bossGroup != null) {
            this.bossGroup.shutdownGracefully();
        }
    }

    public EasyWebMap getPlugin() {
        return this.plugin;
    }
}
