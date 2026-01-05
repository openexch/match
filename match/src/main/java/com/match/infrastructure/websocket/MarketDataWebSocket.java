package com.match.infrastructure.websocket;

import com.google.gson.Gson;
import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.gateway.AeronGateway;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelMatchers;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket server for market data (order book, trades, order status).
 * Receives market data from cluster via AeronGateway egress and broadcasts to connected UI clients.
 */
public class MarketDataWebSocket implements AeronGateway.EgressMessageListener, AutoCloseable {

    private static final int PORT = 8081;
    private static final Gson gson = new Gson();

    private ChannelGroup channels;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // Cache last order book snapshot for immediate sending on new connections
    private volatile String lastBookSnapshot = null;

    // Reference to cluster status for forwarding status broadcasts
    private volatile ClusterStatus clusterStatus;

    public void setClusterStatus(ClusterStatus status) {
        this.clusterStatus = status;
        if (status != null) {
            status.setMarketChannels(channels);
        }
    }

    public ChannelGroup getChannels() {
        return channels;
    }

    public void start() throws InterruptedException {
        channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        // Preload ChannelMatchers to avoid classloading issues during broadcast
        ChannelMatchers.all();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(4); // More workers for market data throughput

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpServerCodec(),
                        new HttpObjectAggregator(65536),
                        new WebSocketServerProtocolHandler("/ws", null, true, 65536, false, true),
                        new MarketDataHandler()
                    );
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true); // Low latency

        serverChannel = bootstrap.bind(PORT).sync().channel();
        System.out.println("Market WebSocket started on ws://localhost:" + PORT + "/ws");
    }

    /**
     * Broadcast market data to all connected WebSocket clients.
     */
    public void broadcastMarketData(String jsonMessage) {
        if (channels != null && !channels.isEmpty()) {
            channels.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        }
    }

    // ==================== Egress Message Listener ====================

    @Override
    public void onMessage(String json) {
        // DEBUG: Log all incoming messages
        if (json.contains("TRADES") || json.contains("BOOK")) {
            int channelCount = channels != null ? channels.size() : 0;
            System.out.printf("[WS-EGRESS] type=%s, clients=%d, len=%d%n",
                json.contains("TRADES") ? "TRADES_BATCH" : "BOOK_SNAPSHOT",
                channelCount, json.length());
        }

        // Market data messages: BOOK_SNAPSHOT, TRADES_BATCH, ORDER_STATUS_BATCH
        if (json.contains("\"type\":\"BOOK_SNAPSHOT\"")) {
            // Cache the latest book snapshot for new client connections
            lastBookSnapshot = json;
            broadcastMarketData(json);
        } else if (json.contains("\"type\":\"TRADES_BATCH\"") ||
                   json.contains("\"type\":\"ORDER_STATUS_BATCH\"") ||
                   json.contains("\"type\":\"ORDER_STATUS\"")) {
            broadcastMarketData(json);
        }
    }

    @Override
    public void onNewLeader(int leaderMemberId, long leadershipTermId) {
        // Leader change is handled by ClusterStatus
    }

    // ==================== WebSocket Handler ====================

    @ChannelHandler.Sharable
    private class MarketDataHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            channels.add(ctx.channel());
            System.out.println("Market client connected: " + ctx.channel().remoteAddress());
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            channels.remove(ctx.channel());
            System.out.println("Market client disconnected: " + ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                String text = ((TextWebSocketFrame) frame).text();
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = gson.fromJson(text, Map.class);
                    String action = (String) msg.get("action");

                    if ("subscribe".equals(action)) {
                        // Client wants to subscribe to a market
                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "SUBSCRIPTION_CONFIRMED");
                        response.put("marketId", msg.get("marketId"));
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));

                        // Send cached order book snapshot immediately for fast initial state
                        if (lastBookSnapshot != null) {
                            ctx.writeAndFlush(new TextWebSocketFrame(lastBookSnapshot));
                        }
                    } else if ("refresh".equals(action)) {
                        // Client requesting state refresh (after reconnect)
                        if (lastBookSnapshot != null) {
                            ctx.writeAndFlush(new TextWebSocketFrame(lastBookSnapshot));
                        } else {
                            Map<String, Object> response = new HashMap<>();
                            response.put("type", "REFRESH_PENDING");
                            response.put("message", "Order book not yet available, waiting for cluster update");
                            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
                        }
                    } else if ("ping".equals(action)) {
                        Map<String, Object> pong = new HashMap<>();
                        pong.put("type", "PONG");
                        pong.put("timestamp", System.currentTimeMillis());
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(pong)));
                    } else if ("unsubscribe".equals(action)) {
                        // Just acknowledge - no action needed since we broadcast to all
                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "UNSUBSCRIPTION_CONFIRMED");
                        response.put("marketId", msg.get("marketId"));
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
                    }
                } catch (Exception e) {
                    Map<String, Object> error = new HashMap<>();
                    error.put("type", "ERROR");
                    error.put("message", "Invalid message: " + e.getMessage());
                    ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(error)));
                }
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Market WebSocket error: " + cause.getMessage());
            ctx.close();
        }
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }
}
