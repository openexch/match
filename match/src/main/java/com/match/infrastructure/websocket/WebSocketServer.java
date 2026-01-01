package com.match.infrastructure.websocket;

import com.match.infrastructure.Logger;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * Netty-based WebSocket server for external client connections.
 * Supports subscription-based event broadcasting.
 */
public class WebSocketServer implements AutoCloseable {

    private static final Logger logger = Logger.getLogger(WebSocketServer.class);
    private static final int DEFAULT_PORT = 8081;

    private final int port;
    private final SubscriptionManager subscriptionManager;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;

    public WebSocketServer(SubscriptionManager subscriptionManager) {
        this(DEFAULT_PORT, subscriptionManager);
    }

    public WebSocketServer(int port, SubscriptionManager subscriptionManager) {
        this.port = port;
        this.subscriptionManager = subscriptionManager;

        // Use NIO event loop groups
        // For Linux, could use EpollEventLoopGroup for better performance
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup(4); // 4 I/O threads
    }

    /**
     * Start the WebSocket server.
     */
    public void start() throws InterruptedException {
        WebSocketFrameHandler frameHandler = new WebSocketFrameHandler(subscriptionManager);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new HttpServerCodec());
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    // Idle detection: 60s read timeout (detect dead connections)
                    pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                    pipeline.addLast(new WebSocketServerProtocolHandler(
                        "/ws",      // WebSocket path
                        null,       // Subprotocols
                        true,       // Allow extensions
                        65536,      // Max frame size
                        false,      // Allow mask mismatch
                        true        // Check starting deadline
                    ));
                    pipeline.addLast(frameHandler);
                }
            })
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true); // Disable Nagle for low latency

        serverChannel = bootstrap.bind(port).sync().channel();
        logger.info("WebSocket server started on port " + port);
    }

    /**
     * Get the subscription manager.
     */
    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }

    /**
     * Get the server port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        logger.info("WebSocket server stopped");
    }
}
