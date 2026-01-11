package com.match.infrastructure.websocket;

import com.google.gson.Gson;
import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.gateway.GatewayHttpHandler;
import com.match.infrastructure.gateway.state.GatewayStateManager;
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

import static com.match.infrastructure.InfrastructureConstants.*;

/**
 * WebSocket server for market data (order book, trades, order status).
 * Receives market data from cluster via GatewayStateManager and broadcasts to connected UI clients.
 * Also provides REST API endpoints via GatewayHttpHandler.
 */
public class MarketDataWebSocket implements AutoCloseable {

    private static final Gson gson = new Gson();

    private ChannelGroup channels;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    // Reference to state manager for queries and initial state
    private volatile GatewayStateManager stateManager;

    // Reference to cluster status for forwarding status broadcasts
    private volatile ClusterStatus clusterStatus;

    public void setClusterStatus(ClusterStatus status) {
        this.clusterStatus = status;
        if (status != null) {
            status.setMarketChannels(channels);
        }
    }

    public void setStateManager(GatewayStateManager stateManager) {
        this.stateManager = stateManager;
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

        // Create shareable handlers
        final GatewayHttpHandler httpHandler = stateManager != null
            ? new GatewayHttpHandler(stateManager) : null;
        final MarketDataHandler wsHandler = new MarketDataHandler();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    pipeline.addLast(new HttpServerCodec());
                    pipeline.addLast(new HttpObjectAggregator(65536));
                    // Add REST API handler before WebSocket (handles /api/* and /health)
                    if (httpHandler != null) {
                        pipeline.addLast(httpHandler);
                    }
                    pipeline.addLast(new WebSocketServerProtocolHandler("/ws", null, true, 65536, false, true));
                    pipeline.addLast(wsHandler);
                }
            })
            .childOption(ChannelOption.TCP_NODELAY, true); // Low latency

        serverChannel = bootstrap.bind(MARKET_GATEWAY_PORT).sync().channel();
    }

    /**
     * Broadcast market data to all connected WebSocket clients.
     * Called by GatewayStateManager after processing egress messages.
     */
    public void broadcastMarketData(String jsonMessage) {
        if (channels != null && !channels.isEmpty()) {
            channels.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        }
    }

    // ==================== WebSocket Handler ====================

    @ChannelHandler.Sharable
    private class MarketDataHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            channels.add(ctx.channel());
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            channels.remove(ctx.channel());
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

                        // Send initial state from state manager
                        sendInitialState(ctx);
                    } else if ("refresh".equals(action)) {
                        // Client requesting state refresh (after reconnect)
                        sendInitialState(ctx);
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
                    } else if ("getOrderBook".equals(action)) {
                        // Query: return current order book
                        if (stateManager != null) {
                            String json = stateManager.getOrderBook().toJson();
                            if (json != null) {
                                ctx.writeAndFlush(new TextWebSocketFrame(json));
                            }
                        }
                    } else if ("getTrades".equals(action)) {
                        // Query: return recent trades
                        if (stateManager != null) {
                            int limit = 50;
                            if (msg.containsKey("limit")) {
                                limit = ((Number) msg.get("limit")).intValue();
                            }
                            String json = stateManager.getTrades().toJson(limit);
                            ctx.writeAndFlush(new TextWebSocketFrame(json));
                        }
                    } else if ("getOrders".equals(action)) {
                        // Query: return open orders for user
                        if (stateManager != null && msg.containsKey("userId")) {
                            long userId = ((Number) msg.get("userId")).longValue();
                            String json = stateManager.getOpenOrders().toJson(userId);
                            ctx.writeAndFlush(new TextWebSocketFrame(json));
                        }
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

        private void sendInitialState(ChannelHandlerContext ctx) {
            if (stateManager != null) {
                // Send order book snapshot
                String bookJson = stateManager.getOrderBook().toJson();
                if (bookJson != null) {
                    ctx.write(new TextWebSocketFrame(bookJson));
                }

                // Send recent trades (last 50)
                if (stateManager.getTrades().hasData()) {
                    String tradesJson = stateManager.getTrades().toJson(50);
                    ctx.write(new TextWebSocketFrame(tradesJson));
                }

                ctx.flush();
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("type", "REFRESH_PENDING");
                response.put("message", "State not yet available, waiting for cluster update");
                ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
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
