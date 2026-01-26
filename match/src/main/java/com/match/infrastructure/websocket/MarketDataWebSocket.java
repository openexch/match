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
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.match.infrastructure.InfrastructureConstants.*;

/**
 * WebSocket server for market data (order book, trades, order status).
 * Receives market data from cluster via GatewayStateManager and broadcasts to connected UI clients.
 * Also provides REST API endpoints via GatewayHttpHandler.
 */
public class MarketDataWebSocket implements AutoCloseable {

    private static final Gson gson = new Gson();
    private static final Map<Integer, String> MARKET_NAMES = Map.of(
        1, "BTC-USD",
        2, "ETH-USD",
        3, "SOL-USD"
    );

    // Attribute to store subscribed marketId per channel
    private static final AttributeKey<Integer> SUBSCRIBED_MARKET =
        AttributeKey.valueOf("subscribedMarket");

    private ChannelGroup channels;
    // Per-market channel groups for efficient broadcasting
    private final ConcurrentHashMap<Integer, ChannelGroup> marketChannels = new ConcurrentHashMap<>();
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
     * Broadcast market data to clients subscribed to the relevant market.
     * Called by GatewayStateManager after processing egress messages.
     * Parses marketId from JSON to filter recipients.
     */
    public void broadcastMarketData(String jsonMessage) {
        if (channels == null || channels.isEmpty()) {
            return;
        }

        // Extract marketId from message for filtering
        int marketId = extractMarketId(jsonMessage);

        if (marketId > 0) {
            // Send only to clients subscribed to this market
            ChannelGroup group = marketChannels.get(marketId);
            if (group != null && !group.isEmpty()) {
                group.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            }
        } else {
            // Broadcast to all (e.g., cluster status messages)
            channels.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        }
    }

    /**
     * Extract marketId from JSON message for routing.
     * Returns 0 if not found (message goes to all clients).
     */
    private int extractMarketId(String json) {
        try {
            // Quick parse - look for "marketId": pattern
            int idx = json.indexOf("\"marketId\":");
            if (idx == -1) {
                idx = json.indexOf("\"marketId\" :");
            }
            if (idx >= 0) {
                int start = idx + (json.charAt(idx + 10) == ' ' ? 12 : 11);
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) {
                    end++;
                }
                if (end > start) {
                    return Integer.parseInt(json.substring(start, end));
                }
            }
        } catch (Exception e) {
            // Ignore parse errors - will broadcast to all
        }
        return 0;
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
            Channel channel = ctx.channel();
            channels.remove(channel);
            // Remove from market-specific group
            Integer oldMarketId = channel.attr(SUBSCRIBED_MARKET).get();
            if (oldMarketId != null) {
                ChannelGroup oldGroup = marketChannels.get(oldMarketId);
                if (oldGroup != null) {
                    oldGroup.remove(channel);
                }
            }
        }

        private void subscribeToMarket(Channel channel, int marketId) {
            // Remove from previous market group if switching
            Integer oldMarketId = channel.attr(SUBSCRIBED_MARKET).get();
            if (oldMarketId != null && oldMarketId != marketId) {
                ChannelGroup oldGroup = marketChannels.get(oldMarketId);
                if (oldGroup != null) {
                    oldGroup.remove(channel);
                }
            }

            // Add to new market group
            channel.attr(SUBSCRIBED_MARKET).set(marketId);
            ChannelGroup group = marketChannels.computeIfAbsent(marketId,
                k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));
            group.add(channel);
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
                        int marketId = msg.containsKey("marketId")
                            ? ((Number) msg.get("marketId")).intValue() : 1;

                        // Track subscription for filtered broadcasting
                        subscribeToMarket(ctx.channel(), marketId);

                        Map<String, Object> response = new HashMap<>();
                        response.put("type", "SUBSCRIPTION_CONFIRMED");
                        response.put("marketId", marketId);
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));

                        // Send ticker stats for this market
                        sendTickerStats(ctx, marketId);

                        // Send initial state from state manager (only if matches requested market)
                        sendInitialState(ctx, marketId);
                    } else if ("refresh".equals(action)) {
                        // Client requesting state refresh (after reconnect)
                        int refreshMarketId = msg.containsKey("marketId")
                            ? ((Number) msg.get("marketId")).intValue() : 0;
                        sendInitialState(ctx, refreshMarketId);
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
                        // Query: return order book for specified market
                        if (stateManager != null) {
                            int queryMarketId = msg.containsKey("marketId")
                                ? ((Number) msg.get("marketId")).intValue() : 1;
                            var book = stateManager.getOrderBook(queryMarketId);
                            if (book != null && book.hasData()) {
                                ctx.writeAndFlush(new TextWebSocketFrame(book.toJson()));
                            } else {
                                ctx.writeAndFlush(new TextWebSocketFrame(buildEmptyBookSnapshot(queryMarketId)));
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

        private void sendTickerStats(ChannelHandlerContext ctx, int marketId) {
            if (stateManager != null) {
                String tickerJson = stateManager.getTickerStats(marketId);
                if (tickerJson != null) {
                    ctx.writeAndFlush(new TextWebSocketFrame(tickerJson));
                }
            }
        }

        private void sendInitialState(ChannelHandlerContext ctx, int requestedMarketId) {
            if (stateManager != null && requestedMarketId > 0) {
                // Get order book for the requested market
                var book = stateManager.getOrderBook(requestedMarketId);
                System.out.println("[WS] sendInitialState: requestedMarketId=" + requestedMarketId +
                    ", book=" + (book != null ? "exists, hasData=" + book.hasData() : "null"));

                if (book != null && book.hasData()) {
                    // Send cached book for this market
                    String bookJson = book.toJson();
                    if (bookJson != null) {
                        System.out.println("[WS] Sending cached book for market " + requestedMarketId);
                        ctx.write(new TextWebSocketFrame(bookJson));
                    }
                } else {
                    // No data for this market yet - send empty book
                    System.out.println("[WS] Sending EMPTY book for market " + requestedMarketId);
                    ctx.write(new TextWebSocketFrame(buildEmptyBookSnapshot(requestedMarketId)));
                }

                // Send recent trades (last 20)
                if (stateManager.getTrades().hasData()) {
                    String tradesJson = stateManager.getTrades().toJson(20);
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

        private String buildEmptyBookSnapshot(int marketId) {
            Map<String, Object> book = new HashMap<>();
            book.put("type", "BOOK_SNAPSHOT");
            book.put("marketId", marketId);
            book.put("market", MARKET_NAMES.getOrDefault(marketId, "UNKNOWN"));
            book.put("timestamp", System.currentTimeMillis());
            book.put("bidVersion", 0);
            book.put("askVersion", 0);
            book.put("version", 0);
            book.put("bids", new ArrayList<>());
            book.put("asks", new ArrayList<>());
            return gson.toJson(book);
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
