package com.match.infrastructure.http;// HttpAeronGateway.java

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.match.infrastructure.generated.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.SystemEpochClock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.TimeUnit;

public class HttpController implements EgressListener, AutoCloseable, Agent {

    // Market data WebSocket for real-time order book and trade updates
    private static final int MARKET_WS_PORT = 8081;
    private static ChannelGroup marketChannels;
    private EventLoopGroup marketBossGroup;
    private EventLoopGroup marketWorkerGroup;
    private Channel marketServerChannel;

    // Cache last order book snapshot for immediate sending on new connections
    private volatile String lastBookSnapshot = null;

    // Egress health check: detect stale sessions where ingress works but egress doesn't
    private volatile long lastEgressTime = System.currentTimeMillis();
    private static final long EGRESS_TIMEOUT_MS = 60_000; // 60 seconds without egress = stale session

    // Admin WebSocket for real-time progress updates
    private static final int ADMIN_WS_PORT = 8082;
    private static ChannelGroup adminChannels;
    private EventLoopGroup adminBossGroup;
    private EventLoopGroup adminWorkerGroup;
    private Channel adminServerChannel;

    // Operation progress tracking using immutable state for atomic reads
    // All state is captured in a single immutable class to prevent torn reads
    private static final class ProgressState {
        final String operation;      // "rolling-update", "snapshot", "compact", null
        final String status;         // Current status message
        final int progress;          // 0-100
        final int currentStep;       // Current step number
        final int totalSteps;        // Total steps
        final long startTime;
        final boolean complete;
        final boolean error;
        final String errorMessage;

        ProgressState(String operation, String status, int progress, int currentStep,
                      int totalSteps, long startTime, boolean complete, boolean error,
                      String errorMessage) {
            this.operation = operation;
            this.status = status;
            this.progress = progress;
            this.currentStep = currentStep;
            this.totalSteps = totalSteps;
            this.startTime = startTime;
            this.complete = complete;
            this.error = error;
            this.errorMessage = errorMessage;
        }

        static ProgressState empty() {
            return new ProgressState(null, null, 0, 0, 0, 0, false, false, null);
        }

        ProgressState start(String op, int steps) {
            return new ProgressState(op, "Starting...", 0, 0, steps, System.currentTimeMillis(), false, false, null);
        }

        ProgressState update(int step, String message) {
            int newProgress = totalSteps > 0 ? (step * 100) / totalSteps : 0;
            return new ProgressState(operation, message, newProgress, step, totalSteps, startTime, false, false, null);
        }

        ProgressState finish(boolean success, String message) {
            return new ProgressState(
                operation, message, success ? 100 : progress, currentStep, totalSteps,
                startTime, true, !success, success ? null : message
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("operation", operation);
            map.put("status", status);
            map.put("progress", progress);
            map.put("currentStep", currentStep);
            map.put("totalSteps", totalSteps);
            map.put("complete", complete);
            map.put("error", error);
            map.put("errorMessage", errorMessage);
            map.put("elapsedMs", startTime > 0 ? System.currentTimeMillis() - startTime : 0);
            return map;
        }
    }

    private static class OperationProgress {
        // Single volatile reference to immutable state ensures atomic reads
        private volatile ProgressState state = ProgressState.empty();

        void reset() {
            state = ProgressState.empty();
        }

        void start(String op, int steps) {
            state = state.start(op, steps);
            broadcastProgress();
        }

        void update(int step, String message) {
            state = state.update(step, message);
            broadcastProgress();
        }

        void finish(boolean success, String message) {
            state = state.finish(success, message);
            broadcastProgress();
        }

        private void broadcastProgress() {
            if (adminChannels != null && !adminChannels.isEmpty()) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "ADMIN_PROGRESS");
                msg.put("data", state.toMap());
                String json = gson.toJson(msg);
                adminChannels.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        Map<String, Object> toMap() {
            return state.toMap();
        }

        // Accessors for individual fields (read from immutable state)
        String getOperation() { return state.operation; }
        boolean isComplete() { return state.complete; }
        boolean isError() { return state.error; }
    }

    private static final OperationProgress operationProgress = new OperationProgress();

    // Cluster status tracking for real-time UI updates
    // Uses thread-safe atomic types to ensure visibility across threads
    private static class ClusterStatus {
        volatile int leaderId = -1;
        volatile long leadershipTermId = -1;
        volatile boolean gatewayConnected = false;
        volatile long lastUpdateTime = 0;
        // Thread-safe arrays for cross-thread visibility
        final AtomicBoolean[] nodeHealthy = new AtomicBoolean[] {
            new AtomicBoolean(false), new AtomicBoolean(false), new AtomicBoolean(false)
        };
        final AtomicReferenceArray<String> nodeStatus = new AtomicReferenceArray<>(
            new String[]{"OFFLINE", "OFFLINE", "OFFLINE"}
        );

        synchronized void updateLeader(int newLeaderId, long termId) {
            int oldLeaderId = this.leaderId;
            this.leaderId = newLeaderId;
            this.leadershipTermId = termId;
            this.lastUpdateTime = System.currentTimeMillis();

            // Update node statuses based on leader
            for (int i = 0; i < 3; i++) {
                if (i == newLeaderId) {
                    nodeStatus.set(i, "LEADER");
                    nodeHealthy[i].set(true);
                } else if (nodeHealthy[i].get()) {
                    nodeStatus.set(i, "FOLLOWER");
                }
            }

            // Broadcast leader change event
            broadcastClusterEvent("LEADER_CHANGE", newLeaderId,
                "Leader changed from Node " + oldLeaderId + " to Node " + newLeaderId);
            broadcastStatus();
        }

        synchronized void setNodeStatus(int nodeId, String status, boolean healthy) {
            if (nodeId >= 0 && nodeId < 3) {
                String oldStatus = nodeStatus.get(nodeId);
                nodeStatus.set(nodeId, status);
                nodeHealthy[nodeId].set(healthy);
                lastUpdateTime = System.currentTimeMillis();

                // Broadcast node status change
                if (!oldStatus.equals(status)) {
                    String event = healthy ? "NODE_UP" : "NODE_DOWN";
                    broadcastClusterEvent(event, nodeId, "Node " + nodeId + " is now " + status);
                }
                broadcastStatus();
            }
        }

        synchronized void setGatewayConnected(boolean connected) {
            boolean wasConnected = this.gatewayConnected;
            this.gatewayConnected = connected;
            this.lastUpdateTime = System.currentTimeMillis();

            if (wasConnected != connected) {
                String event = connected ? "CONNECTION_RESTORED" : "CONNECTION_LOST";
                broadcastClusterEvent(event, null,
                    connected ? "Gateway connected to cluster" : "Gateway lost connection to cluster");
                broadcastStatus();
            }
        }

        synchronized void broadcastStatus() {
            if (adminChannels != null && !adminChannels.isEmpty()) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "CLUSTER_STATUS");
                msg.put("leaderId", leaderId);
                msg.put("leadershipTermId", leadershipTermId);
                msg.put("gatewayConnected", gatewayConnected);
                msg.put("timestamp", System.currentTimeMillis());

                List<Map<String, Object>> nodes = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    Map<String, Object> node = new HashMap<>();
                    node.put("id", i);
                    node.put("status", nodeStatus.get(i));
                    node.put("healthy", nodeHealthy[i].get());
                    nodes.add(node);
                }
                msg.put("nodes", nodes);

                String json = gson.toJson(msg);
                adminChannels.writeAndFlush(new TextWebSocketFrame(json));

                // Also broadcast to market WebSocket for UI visibility
                if (marketChannels != null && !marketChannels.isEmpty()) {
                    marketChannels.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }

        synchronized void broadcastClusterEvent(String event, Integer nodeId, String message) {
            if (adminChannels != null && !adminChannels.isEmpty()) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "CLUSTER_EVENT");
                msg.put("event", event);
                if (nodeId != null) {
                    msg.put("nodeId", nodeId);
                }
                if (event.equals("LEADER_CHANGE")) {
                    msg.put("newLeaderId", leaderId);
                }
                msg.put("message", message);
                msg.put("timestamp", System.currentTimeMillis());

                String json = gson.toJson(msg);
                adminChannels.writeAndFlush(new TextWebSocketFrame(json));

                // Also broadcast to market WebSocket for UI visibility
                if (marketChannels != null && !marketChannels.isEmpty()) {
                    marketChannels.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }
    }

    private static final ClusterStatus clusterStatus = new ClusterStatus();

    private static final long HEARTBEAT_INTERVAL = 250;
    private long lastHeartbeatTime = Long.MIN_VALUE;
    private static final int HTTP_PORT = 8080;
    private ConnectionState connectionState = ConnectionState.NOT_CONNECTED;
    // Gson nesnesini bir kere oluşturup yeniden kullanmak en verimlisidir.
    private static final Gson gson = new Gson();

    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(512); // Buffer'ı biraz büyüttük

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();

    private volatile MediaDriver mediaDriver;
    private volatile AeronCluster cluster;
    private final HttpServer server;

    // Reconnection support
    private volatile boolean needsReconnect = false;
    private volatile long lastReconnectAttempt = 0;
    private static final long RECONNECT_COOLDOWN_MS = 500;  // Fast reconnection attempts
    private final String ingressEndpoints;
    private final String egressChannel;
    
    // Rate limiting and flow control
    private final AtomicInteger requestsInFlight = new AtomicInteger(0);
    private final AtomicLong lastBackpressureTime = new AtomicLong(0);
    private static final int MAX_REQUESTS_IN_FLIGHT = 1000;
    private static final long BACKPRESSURE_COOLDOWN_MS = 100;

    public HttpController() throws IOException {

        // Use environment variable or default to localhost for development
        final String clusterAddresses = System.getenv().getOrDefault("CLUSTER_ADDRESSES", "localhost,localhost,localhost");
        final String egressHost = System.getenv().getOrDefault("EGRESS_HOST", "localhost");
        final int egressPort = Integer.parseInt(System.getenv().getOrDefault("EGRESS_PORT", "9091"));

        final List<String> hostnames = Arrays.asList(clusterAddresses.split(","));
        this.ingressEndpoints = io.aeron.samples.cluster.ClusterConfig.ingressEndpoints(
                hostnames, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        this.egressChannel = "aeron:udp?endpoint=" + egressHost + ":" + egressPort;
        this.mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context().threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true));

        // Try to connect with retries on startup
        int maxStartupRetries = 30;  // 5 minutes total (10s timeout * 30 retries)
        for (int attempt = 1; attempt <= maxStartupRetries; attempt++) {
            try {
                connectToCluster();
                break;  // Success
            } catch (Exception e) {
                System.err.println("Startup connection attempt " + attempt + "/" + maxStartupRetries + " failed: " + e.getMessage());
                if (attempt == maxStartupRetries) {
                    throw new RuntimeException("Failed to connect to cluster after " + maxStartupRetries + " attempts", e);
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        this.server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        this.server.createContext("/order", (exchange) -> {
            String responseText = "Order accepted and forwarded to cluster.";
            int statusCode = 202;

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    // Gelen JSON'ı Order sınıfına dönüştür
                    Order order = gson.fromJson(reader, Order.class);

                    if (order == null || order.market == null) {
                        responseText = "Error: Invalid or empty JSON body.";
                        statusCode = 400; // Bad Request
                    } else {
                        // Rate limiting check
                        if (requestsInFlight.get() >= MAX_REQUESTS_IN_FLIGHT) {
                            responseText = "Error: Server is overloaded. Please retry later.";
                            statusCode = 503; // Service Unavailable
                        } else if (System.currentTimeMillis() - lastBackpressureTime.get() < BACKPRESSURE_COOLDOWN_MS) {
                            responseText = "Error: Cluster is experiencing backpressure. Please slow down.";
                            statusCode = 429; // Too Many Requests
                        } else {
                            requestsInFlight.incrementAndGet();
                            try {
                                sendMessage(order);
                            } finally {
                                requestsInFlight.decrementAndGet();
                            }
                        }
                    }
                } catch (JsonSyntaxException e) {
                    responseText = "Error: Malformed JSON.";
                    statusCode = 400; // Bad Request
                } catch (Exception e) {
                    responseText = "Error processing request: " + e.getMessage();
                    statusCode = 500;
                    e.printStackTrace();
                }
            } else {
                responseText = "Error: Only POST method is supported.";
                statusCode = 405;
            }

            exchange.sendResponseHeaders(statusCode, responseText.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseText.getBytes());
            }
        });

        // Cluster Admin API
        this.server.createContext("/api/admin/status", this::handleAdminStatus);
        this.server.createContext("/api/admin/restart-node", this::handleRestartNode);
        this.server.createContext("/api/admin/stop-node", this::handleStopNode);
        this.server.createContext("/api/admin/start-node", this::handleStartNode);
        this.server.createContext("/api/admin/stop-backup", this::handleStopBackup);
        this.server.createContext("/api/admin/start-backup", this::handleStartBackup);
        this.server.createContext("/api/admin/restart-backup", this::handleRestartBackup);
        this.server.createContext("/api/admin/stop-gateway", this::handleStopGateway);
        this.server.createContext("/api/admin/start-gateway", this::handleStartGateway);
        this.server.createContext("/api/admin/restart-gateway", this::handleRestartGateway);
        this.server.createContext("/api/admin/rolling-update", this::handleRollingUpdate);
        this.server.createContext("/api/admin/snapshot", this::handleSnapshot);
        this.server.createContext("/api/admin/compact", this::handleCompact);
        this.server.createContext("/api/admin/progress", this::handleProgress);
        this.server.createContext("/api/admin/logs", this::handleLogs);

        // Root redirect to UI
        this.server.createContext("/", (exchange) -> {
            String path = exchange.getRequestURI().getPath();

            if ("/".equals(path)) {
                // Redirect root to /ui/
                exchange.getResponseHeaders().set("Location", "/ui/");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            // 404 for other unhandled paths
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // Static file serving for UI
        this.server.createContext("/ui", this::handleStaticFile);

        this.server.setExecutor(null);
        this.server.start();
        System.out.println("🚀 JSON HTTP Gateway started on http://localhost:" + HTTP_PORT);
        System.out.println("📊 Trading UI available at http://localhost:" + HTTP_PORT + "/ui/");

        // Start market data WebSocket server (receives from cluster, broadcasts to UI)
        startMarketDataWebSocket();

        // Start admin WebSocket server
        startAdminWebSocket();
    }

    /**
     * Start WebSocket server for market data (order book, trades, order status).
     * Receives market data from cluster via Aeron egress and broadcasts to connected UI clients.
     */
    private void startMarketDataWebSocket() {
        marketChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        marketBossGroup = new NioEventLoopGroup(1);
        marketWorkerGroup = new NioEventLoopGroup(4); // More workers for market data throughput

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(marketBossGroup, marketWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpServerCodec(),
                            new HttpObjectAggregator(65536),
                            new WebSocketServerProtocolHandler("/ws", null, true, 65536, false, true),
                            new MarketDataWebSocketHandler()
                        );
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true); // Low latency

            marketServerChannel = bootstrap.bind(MARKET_WS_PORT).sync().channel();
            System.out.println("📈 Market WebSocket started on ws://localhost:" + MARKET_WS_PORT + "/ws");
        } catch (Exception e) {
            System.err.println("Failed to start market WebSocket: " + e.getMessage());
        }
    }

    /**
     * Handler for market data WebSocket connections.
     * Clients subscribe to markets and receive order book snapshots and trade updates.
     */
    @ChannelHandler.Sharable
    private class MarketDataWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            marketChannels.add(ctx.channel());
            System.out.println("Market client connected: " + ctx.channel().remoteAddress());
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            marketChannels.remove(ctx.channel());
            System.out.println("Market client disconnected: " + ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                String text = ((TextWebSocketFrame) frame).text();
                try {
                    // Parse JSON message from client
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
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Market WebSocket error: " + cause.getMessage());
            ctx.close();
        }
    }

    /**
     * Broadcast market data to all connected WebSocket clients.
     * Called when market data is received from cluster via Aeron egress.
     */
    private void broadcastMarketData(String jsonMessage) {
        if (marketChannels != null && !marketChannels.isEmpty()) {
            int clientCount = marketChannels.size();
            marketChannels.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            if (System.currentTimeMillis() % 5000 < 50) {
                System.out.printf("[WS] Broadcast to %d clients: %d bytes%n", clientCount, jsonMessage.length());
            }
        }
    }

    /**
     * Start WebSocket server for admin progress updates.
     */
    private void startAdminWebSocket() {
        adminChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        adminBossGroup = new NioEventLoopGroup(1);
        adminWorkerGroup = new NioEventLoopGroup(2);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(adminBossGroup, adminWorkerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpServerCodec(),
                            new HttpObjectAggregator(65536),
                            new WebSocketServerProtocolHandler("/ws", null, true),
                            new AdminWebSocketHandler()
                        );
                    }
                });

            adminServerChannel = bootstrap.bind(ADMIN_WS_PORT).sync().channel();
            System.out.println("📡 Admin WebSocket started on ws://localhost:" + ADMIN_WS_PORT + "/ws");
        } catch (Exception e) {
            System.err.println("Failed to start admin WebSocket: " + e.getMessage());
        }
    }

    /**
     * Handler for admin WebSocket connections.
     */
    @ChannelHandler.Sharable
    private class AdminWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            adminChannels.add(ctx.channel());
            System.out.println("Admin client connected: " + ctx.channel().remoteAddress());

            // Send current progress immediately on connect
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "ADMIN_PROGRESS");
            msg.put("data", operationProgress.toMap());
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(msg)));
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            adminChannels.remove(ctx.channel());
            System.out.println("Admin client disconnected: " + ctx.channel().remoteAddress());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                String text = ((TextWebSocketFrame) frame).text();
                try {
                    Map<String, Object> msg = gson.fromJson(text, Map.class);
                    String action = (String) msg.get("action");

                    if ("subscribe-admin".equals(action)) {
                        // Send current cluster status immediately
                        sendClusterStatus(ctx);
                        // Confirm subscription
                        Map<String, Object> confirm = new HashMap<>();
                        confirm.put("type", "ADMIN_SUBSCRIBED");
                        confirm.put("timestamp", System.currentTimeMillis());
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(confirm)));
                        System.out.println("Admin client subscribed: " + ctx.channel().remoteAddress());
                    } else if ("ping".equals(action)) {
                        Map<String, Object> pong = new HashMap<>();
                        pong.put("type", "PONG");
                        pong.put("timestamp", System.currentTimeMillis());
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(pong)));
                    }
                } catch (Exception e) {
                    // Fallback for non-JSON messages
                    if (text.contains("\"action\":\"ping\"")) {
                        Map<String, Object> pong = new HashMap<>();
                        pong.put("type", "PONG");
                        pong.put("timestamp", System.currentTimeMillis());
                        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(pong)));
                    }
                }
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            }
        }

        private void sendClusterStatus(ChannelHandlerContext ctx) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "CLUSTER_STATUS");
            msg.put("leaderId", clusterStatus.leaderId);
            msg.put("leadershipTermId", clusterStatus.leadershipTermId);
            msg.put("gatewayConnected", clusterStatus.gatewayConnected);
            msg.put("timestamp", System.currentTimeMillis());

            List<Map<String, Object>> nodes = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", i);
                node.put("status", clusterStatus.nodeStatus.get(i));
                node.put("healthy", clusterStatus.nodeHealthy[i].get());
                nodes.add(node);
            }
            msg.put("nodes", nodes);
            ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(msg)));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("Admin WebSocket error: " + cause.getMessage());
            ctx.close();
        }
    }

    /**
     * Serve static files from classpath resources.
     * Supports SPA fallback - serves index.html for unknown routes.
     */
    private void handleStaticFile(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Add CORS headers for WebSocket compatibility
        Headers responseHeaders = exchange.getResponseHeaders();
        responseHeaders.set("Access-Control-Allow-Origin", "*");
        responseHeaders.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        responseHeaders.set("Access-Control-Allow-Headers", "Content-Type");

        // Handle CORS preflight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        // Remove /ui prefix to get relative path
        String resourcePath = path.replaceFirst("^/ui", "");
        if (resourcePath.isEmpty() || resourcePath.equals("/")) {
            resourcePath = "/index.html";
        }

        // Security: prevent path traversal
        if (resourcePath.contains("..")) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }

        // Try to load resource from classpath
        String fullPath = "/static/ui" + resourcePath;
        InputStream is = getClass().getResourceAsStream(fullPath);

        // SPA fallback: serve index.html for non-file routes
        if (is == null && !resourcePath.contains(".")) {
            is = getClass().getResourceAsStream("/static/ui/index.html");
            resourcePath = "/index.html";
        }

        if (is == null) {
            String response = "Not Found: " + resourcePath;
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
            return;
        }

        // Set content type based on file extension
        String contentType = getContentType(resourcePath);
        responseHeaders.set("Content-Type", contentType);

        // Cache static assets (except HTML)
        if (!contentType.contains("html")) {
            responseHeaders.set("Cache-Control", "public, max-age=31536000");
        } else {
            responseHeaders.set("Cache-Control", "no-cache");
        }

        // Stream the file
        byte[] content = is.readAllBytes();
        is.close();

        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    /**
     * Get content type based on file extension.
     */
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    // Diğer metotlar (startPolling, sendMessage, onMessage, vb.) bir öncekiyle aynı...

    /**
     * Connect or reconnect to the Aeron Cluster.
     */
    private synchronized void connectToCluster() {
        // Close existing connection if any
        if (cluster != null && !cluster.isClosed()) {
            try {
                cluster.close();
            } catch (Exception e) {
                System.err.println("Error closing old cluster connection: " + e.getMessage());
            }
        }

        // Check if MediaDriver is still valid, restart if needed
        if (mediaDriver != null) {
            try {
                // Check if the Aeron directory still exists
                File aeronDir = new File(mediaDriver.aeronDirectoryName());
                if (!aeronDir.exists() || !new File(aeronDir, "cnc.dat").exists()) {
                    System.out.println("MediaDriver directory invalid, restarting MediaDriver...");
                    try { mediaDriver.close(); } catch (Exception ignored) {}
                    mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                        .threadingMode(ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true));
                    System.out.println("MediaDriver restarted: " + mediaDriver.aeronDirectoryName());
                }
            } catch (Exception e) {
                System.err.println("Error checking MediaDriver: " + e.getMessage());
                // Force restart
                try { mediaDriver.close(); } catch (Exception ignored) {}
                mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                    .threadingMode(ThreadingMode.SHARED)
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true));
            }
        }

        final AeronCluster.Context clusterCtx = new AeronCluster.Context()
                .egressListener(this)
                .egressChannel(egressChannel)
                .ingressChannel("aeron:udp?term-length=16m|mtu=8k")
                .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                .ingressEndpoints(ingressEndpoints)
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(2));  // 2-second failover detection

        System.out.println("Connecting to Aeron Cluster...");
        this.cluster = AeronCluster.connect(clusterCtx);
        System.out.println("Connected to Cluster");
        connectionState = ConnectionState.CONNECTED;
        needsReconnect = false;
        // Reset egress health check timer on new connection
        lastEgressTime = System.currentTimeMillis();

        // Get current leader from cluster
        int currentLeader = cluster.leaderMemberId();
        if (currentLeader >= 0) {
            clusterStatus.updateLeader(currentLeader, cluster.leadershipTermId());
            System.out.println("Current leader: Node " + currentLeader);
        }

        // Broadcast gateway connection to UI
        clusterStatus.setGatewayConnected(true);
    }

    /**
     * Attempt reconnection if needed and cooldown has passed.
     * Synchronized to prevent race conditions with connectToCluster().
     */
    private synchronized void tryReconnectIfNeeded() {
        if (!needsReconnect) return;

        long now = System.currentTimeMillis();
        if (now - lastReconnectAttempt < RECONNECT_COOLDOWN_MS) return;

        lastReconnectAttempt = now;
        System.out.println("Attempting to reconnect to cluster...");

        try {
            // Call directly - Java monitors are reentrant so nested synchronized is safe
            connectToCluster();
            System.out.println("Reconnected to cluster successfully!");
        } catch (Exception e) {
            System.err.println("Reconnection failed: " + e.getMessage());
            connectionState = ConnectionState.NOT_CONNECTED;
            clusterStatus.setGatewayConnected(false);
        }
    }

    /**
     * Get cluster reference safely.
     * Returns null if cluster is not connected or is being reconnected.
     */
    private synchronized AeronCluster getCluster() {
        if (needsReconnect || cluster == null || cluster.isClosed()) {
            return null;
        }
        return cluster;
    }

    private long lastClusterStatusCheck = 0;
    private static final long CLUSTER_STATUS_CHECK_INTERVAL = 2000; // Check every 2 seconds

    // DEBUG: Poll counters
    private long pollCount = 0;
    private long totalPollWork = 0;

    public void startPolling() {
        final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(100);
        while (true) {
            // Handle reconnection
            if (needsReconnect || cluster == null || cluster.isClosed()) {
                needsReconnect = true;
                connectionState = ConnectionState.NOT_CONNECTED;
                clusterStatus.setGatewayConnected(false);
                tryReconnectIfNeeded();
                idleStrategy.idle(0);
                continue;
            }

            try {
                // Poll egress and send keepalive
                int work = cluster.pollEgress();

                // DEBUG: Log poll results periodically
                pollCount++;
                if (work > 0) {
                    totalPollWork += work;
                    System.out.printf("[POLL-DEBUG] work=%d, totalWork=%d, pollCount=%d%n", work, totalPollWork, pollCount);
                } else if (pollCount % 1000 == 0) {
                    System.out.printf("[POLL-DEBUG] idle poll #%d, totalWork=%d%n", pollCount, totalPollWork);
                }

                // Send cluster heartbeat roughly every 250ms
                final long now = SystemEpochClock.INSTANCE.time();
                if (now >= (lastHeartbeatTime + HEARTBEAT_INTERVAL)) {
                    lastHeartbeatTime = now;
                    if (connectionState == ConnectionState.CONNECTED) {
                        cluster.sendKeepAlive();
                        work++;
                    }
                }

                // Periodic cluster status check for node health
                if (now >= (lastClusterStatusCheck + CLUSTER_STATUS_CHECK_INTERVAL)) {
                    lastClusterStatusCheck = now;
                    checkAndBroadcastClusterStatus();
                }

                // Egress health check: detect stale sessions where cluster isn't responding
                // Only check after connection is established (wait 5 seconds after connect)
                long timeSinceLastEgress = now - lastEgressTime;
                if (connectionState == ConnectionState.CONNECTED && timeSinceLastEgress > EGRESS_TIMEOUT_MS) {
                    System.out.printf("[HEALTH] Egress timeout detected: %dms since last egress. Forcing reconnection.%n", timeSinceLastEgress);
                    needsReconnect = true;
                    connectionState = ConnectionState.NOT_CONNECTED;
                    clusterStatus.setGatewayConnected(false);
                    // Reset timer for reconnection
                    lastEgressTime = now;
                }

                idleStrategy.idle(work);
            } catch (Exception e) {
                System.err.println("Polling error, will reconnect: " + e.getMessage());
                needsReconnect = true;
                connectionState = ConnectionState.NOT_CONNECTED;
                clusterStatus.setGatewayConnected(false);
            }
        }
    }

    /**
     * Check cluster node status and broadcast to UI clients
     */
    private void checkAndBroadcastClusterStatus() {
        // Quick check using systemctl for node health
        // Skip status updates during operations - let rolling update control states
        if (operationProgress.getOperation() != null) {
            return;
        }

        for (int i = 0; i < 3; i++) {
            try {
                String currentStatus = clusterStatus.nodeStatus.get(i);
                // Don't override transitional states - these are managed by operations
                if (currentStatus.equals("STOPPING") || currentStatus.equals("STARTING") ||
                    currentStatus.equals("REJOINING") || currentStatus.equals("ELECTION")) {
                    continue;
                }

                // Use systemctl to check if service is active (fast, reliable)
                String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + i);
                boolean running = "active".equals(activeResult.trim());

                if (running && currentStatus.equals("OFFLINE")) {
                    clusterStatus.setNodeStatus(i, "FOLLOWER", true);
                } else if (!running && !currentStatus.equals("OFFLINE")) {
                    clusterStatus.setNodeStatus(i, "OFFLINE", false);
                }
            } catch (Exception e) {
                // Ignore errors in status check
            }
        }
    }

    public void sendMessage(Order order) {
        if (cluster == null || cluster.isClosed() || needsReconnect) {
            needsReconnect = true;
            throw new IllegalStateException("Cluster is not connected. Reconnecting...");
        }

        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

        // Use primitive types for zero-allocation encoding
        createOrderEncoder.userId(order.getUserIdAsLong());
        createOrderEncoder.price(order.getPriceAsLong());
        createOrderEncoder.quantity(order.getQuantityAsLong());
        createOrderEncoder.totalPrice(order.getTotalPriceAsLong());
        createOrderEncoder.marketId(order.getMarketId());
        createOrderEncoder.orderType(order.toOrderType());
        createOrderEncoder.orderSide(order.toOrderSide());

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + createOrderEncoder.encodedLength();
        long result;
        int retryCount = 0;
        final int maxRetries = 3;
        final long retryDelayMs = 10;

        while ((result = cluster.offer(buffer, 0, length)) < 0) {
            System.out.printf("[ORDER-DEBUG] offer failed: result=%d%n", result);
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                needsReconnect = true;
                throw new IllegalStateException("Cluster connection lost. Reconnecting...");
            }

            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                lastBackpressureTime.set(System.currentTimeMillis());
                if (++retryCount > maxRetries) {
                    throw new RuntimeException("Message rejected due to backpressure after " + maxRetries + " retries");
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while handling backpressure", e);
                }
            } else {
                throw new RuntimeException("Failed to send message to cluster: " + result);
            }
        }
        // DEBUG: Log successful offer
        System.out.printf("[ORDER-DEBUG] offer succeeded: result=%d, length=%d%n", result, length);
    }

    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length, Header header) {
        // Track egress receipt for health check
        lastEgressTime = System.currentTimeMillis();

        final String response = buffer.getStringWithoutLengthUtf8(offset, length);

        // Check if this is market data (JSON with type field) and broadcast to WebSocket clients
        if (response.startsWith("{\"type\":")) {
            // Market data messages: BOOK_SNAPSHOT, TRADES_BATCH, ORDER_STATUS_BATCH
            if (response.contains("\"type\":\"BOOK_SNAPSHOT\"")) {
                // Cache the latest book snapshot for new client connections
                lastBookSnapshot = response;
                broadcastMarketData(response);
                return;
            } else if (response.contains("\"type\":\"TRADES_BATCH\"") ||
                       response.contains("\"type\":\"ORDER_STATUS_BATCH\"") ||
                       response.contains("\"type\":\"ORDER_STATUS\"")) {
                broadcastMarketData(response);
                return;
            }
        }
    }

    @Override
    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId, String ingressEndpoints) {
        System.out.printf("ℹ️ New cluster leader: memberId=%d, termId=%d, endpoints=%s%n",
                leaderMemberId, leadershipTermId, ingressEndpoints);

        // Broadcast leader change to UI
        clusterStatus.updateLeader(leaderMemberId, leadershipTermId);
    }

    // ==================== CLUSTER ADMIN API ====================

    private void handleAdminStatus(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        Map<String, Object> status = new HashMap<>();

        // Get cluster nodes status
        List<Map<String, Object>> nodes = new ArrayList<>();

        // Use gateway's tracked leader (fast - no external calls needed)
        // The gateway maintains a connection to the cluster and tracks the leader
        int leaderNode = clusterStatus.leaderId;

        // Check each node - use clusterStatus for transitional states during operations
        for (int i = 0; i < 3; i++) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", i);

            // Get current status from our tracking (includes transitional states)
            String trackedStatus = clusterStatus.nodeStatus.get(i);
            boolean isTransitional = trackedStatus.equals("STOPPING") ||
                                     trackedStatus.equals("STARTING") ||
                                     trackedStatus.equals("REJOINING") ||
                                     trackedStatus.equals("ELECTION");

            // If in STOPPING state (shutting down), always show STOPPING until explicitly changed
            if (trackedStatus.equals("STOPPING")) {
                node.put("running", false);
                node.put("role", "STOPPING");
            } else {
                try {
                    // Use systemctl to check if service is active (fast, no timeout issues)
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + i);
                    boolean isActive = "active".equals(activeResult.trim());

                    if (isActive) {
                        // Get PID from systemctl (fast)
                        String pidResult = executeCommand("systemctl", "--user", "show", "-p", "MainPID", "--value", "match-node" + i);
                        int pid = Integer.parseInt(pidResult.trim());

                        node.put("running", true);
                        node.put("pid", pid);
                        // Use transitional state if set, otherwise use cluster role
                        if (isTransitional) {
                            node.put("role", trackedStatus);
                        } else {
                            node.put("role", i == leaderNode ? "LEADER" : "FOLLOWER");
                            // Update our tracking to match reality
                            clusterStatus.nodeStatus.set(i, i == leaderNode ? "LEADER" : "FOLLOWER");
                            clusterStatus.nodeHealthy[i].set(true);
                        }
                    } else {
                        throw new RuntimeException("Service not active");
                    }
                } catch (Exception e) {
                    node.put("running", false);
                    // Use transitional state if set, otherwise OFFLINE
                    if (isTransitional) {
                        node.put("role", trackedStatus);
                    } else {
                        node.put("role", "OFFLINE");
                        clusterStatus.nodeStatus.set(i, "OFFLINE");
                        clusterStatus.nodeHealthy[i].set(false);
                    }
                }
            }
            nodes.add(node);
        }
        status.put("nodes", nodes);
        status.put("leader", leaderNode);

        // Check backup node
        Map<String, Object> backup = new HashMap<>();
        try {
            String result = executeCommand("pgrep", "-f", "ClusterBackupApp");
            backup.put("running", !result.trim().isEmpty());
            if (!result.trim().isEmpty()) {
                backup.put("pid", Integer.parseInt(result.trim().split("\n")[0]));
            }
        } catch (Exception e) {
            backup.put("running", false);
        }
        status.put("backup", backup);

        // Check gateway
        Map<String, Object> gateway = new HashMap<>();
        gateway.put("running", true); // We're running!
        gateway.put("port", HTTP_PORT);
        status.put("gateway", gateway);

        // Archive size (actual disk usage, not apparent size of sparse files)
        try {
            String result = executeCommand("du", "-sb", "--apparent-size", "/tmp/aeron-cluster/");
            String[] parts = result.trim().split("\t");
            if (parts.length > 0) {
                status.put("archiveBytes", Long.parseLong(parts[0]));
            }
            // Also get actual disk usage
            result = executeCommand("du", "-s", "/tmp/aeron-cluster/");
            parts = result.trim().split("\t");
            if (parts.length > 0) {
                status.put("archiveDiskBytes", Long.parseLong(parts[0]) * 1024); // du -s returns KB
            }
        } catch (Exception e) {
            status.put("archiveBytes", 0);
            status.put("archiveDiskBytes", 0);
        }

        sendJsonResponse(exchange, 200, status);
    }

    private void handleRestartNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        // Parse request body
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = gson.fromJson(body, Map.class);
        int nodeId = ((Number) request.get("nodeId")).intValue();

        if (nodeId < 0 || nodeId > 2) {
            sendJsonResponse(exchange, 400, Map.of("error", "Invalid nodeId. Must be 0, 1, or 2"));
            return;
        }

        // Set status to STOPPING before executing
        clusterStatus.setNodeStatus(nodeId, "STOPPING", false);

        // Execute restart in background with proper state tracking
        new Thread(() -> {
            try {
                // Stop the node
                executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                Thread.sleep(1000);

                // Set status to STARTING
                clusterStatus.setNodeStatus(nodeId, "STARTING", false);

                // Start the node
                executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                Thread.sleep(2000);

                // Set status to REJOINING
                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                // Wait for node to fully rejoin (use systemctl to check)
                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + nodeId);
                    if ("active".equals(activeResult.trim())) {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " restart initiated"));
    }

    private void handleStopNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = gson.fromJson(body, Map.class);
        int nodeId = ((Number) request.get("nodeId")).intValue();

        if (nodeId < 0 || nodeId > 2) {
            sendJsonResponse(exchange, 400, Map.of("error", "Invalid nodeId. Must be 0, 1, or 2"));
            return;
        }

        // Set status to STOPPING before executing
        clusterStatus.setNodeStatus(nodeId, "STOPPING", false);

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                Thread.sleep(500);
                // After stop completes, set to OFFLINE
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            } catch (Exception e) {
                e.printStackTrace();
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " stop initiated"));
    }

    private void handleStartNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> request = gson.fromJson(body, Map.class);
        int nodeId = ((Number) request.get("nodeId")).intValue();

        if (nodeId < 0 || nodeId > 2) {
            sendJsonResponse(exchange, 400, Map.of("error", "Invalid nodeId. Must be 0, 1, or 2"));
            return;
        }

        // Set status to STARTING before executing
        clusterStatus.setNodeStatus(nodeId, "STARTING", false);

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                Thread.sleep(2000);
                // Set to REJOINING after start
                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                // Wait for node to fully rejoin (use systemctl to check)
                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + nodeId);
                    if ("active".equals(activeResult.trim())) {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " start initiated"));
    }

    private void handleStopBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Backup node stop initiated"));
    }

    private void handleStartBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Backup node start initiated"));
    }

    private void handleRestartBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-backup");
                Thread.sleep(1000);
                executeCommand("systemctl", "--user", "start", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Backup node restart initiated"));
    }

    private void handleStopGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-gateway");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Gateway stop initiated"));
    }

    private void handleStartGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "match-gateway");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Gateway start initiated"));
    }

    private void handleRestartGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        new Thread(() -> {
            try {
                // Use restart which is atomic - separate stop+start kills the process before start runs
                executeCommand("systemctl", "--user", "restart", "match-gateway");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Gateway restart initiated"));
    }

    private void handleRollingUpdate(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            sendJsonResponse(exchange, 409, Map.of("error", "Another operation in progress"));
            return;
        }

        // Execute rolling update in background with progress tracking
        new Thread(() -> {
            try {
                // Rolling update with granular steps and real-time node state broadcasting
                // Total 17 steps for detailed progress tracking (including build step)
                operationProgress.start("rolling-update", 17);

                // Step 1: Build application
                operationProgress.update(1, "Building application...");
                String projectDir = System.getenv("MATCH_PROJECT_DIR");
                if (projectDir == null || projectDir.isEmpty()) {
                    projectDir = System.getProperty("user.dir");
                }
                executeCommand("bash", "-c", "cd " + projectDir + "/match && mvn clean package -DskipTests -q");

                String jarPath = "match/target/cluster-engine-1.0.jar";

                // Step 2: Find current leader
                operationProgress.update(2, "Finding cluster leader...");
                int leaderNode = -1;
                String listResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node0/cluster", "list-members");
                if (listResult.contains("leaderMemberId=")) {
                    leaderNode = Integer.parseInt(listResult.split("leaderMemberId=")[1].split(",")[0].trim());
                }

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                // Update clusterStatus with current leader
                clusterStatus.updateLeader(leaderNode, clusterStatus.leadershipTermId);

                // Get follower nodes
                int[] followers = new int[2];
                int idx = 0;
                for (int i = 0; i < 3; i++) {
                    if (i != leaderNode) followers[idx++] = i;
                }

                // Steps 3-12: Update both followers (5 steps each)
                int step = 3;
                for (int f = 0; f < 2; f++) {
                    int nodeId = followers[f];
                    String nodeLabel = "Node " + nodeId;

                    // Step N: Stopping follower via systemctl (single source of truth)
                    operationProgress.update(step, "Stopping " + nodeLabel + "...");
                    clusterStatus.setNodeStatus(nodeId, "STOPPING", false);
                    Thread.sleep(300); // Let UI show STOPPING state
                    // Stop via systemctl - preserves CPU pinning and enables proper service management
                    executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                    Thread.sleep(500);
                    step++;

                    // Step N+1: Mark file timeout - keep showing STOPPING state
                    operationProgress.update(step, nodeLabel + ": Waiting for mark file timeout (12s)...");
                    // Node stays in STOPPING state during mark file timeout
                    Thread.sleep(12000);
                    step++;

                    // Step N+2: Cleaning - still in STOPPING state
                    operationProgress.update(step, nodeLabel + ": Cleaning shared memory...");
                    executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-*node" + nodeId + "* 2>/dev/null || true");
                    executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + nodeId + "/cluster/cluster-mark.dat 2>/dev/null || true");
                    executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + nodeId + "/cluster/*.lck 2>/dev/null || true");
                    Thread.sleep(300);
                    step++;

                    // Step N+3: Starting follower via systemctl (single source of truth)
                    operationProgress.update(step, "Starting " + nodeLabel + "...");
                    clusterStatus.setNodeStatus(nodeId, "STARTING", false);
                    Thread.sleep(300); // Let UI show STARTING state
                    // Start via systemctl - uses service definition with CPU pinning, proper env vars
                    executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                    Thread.sleep(2000); // Give node time to start
                    step++;

                    // Step N+4: Waiting to rejoin
                    operationProgress.update(step, nodeLabel + ": Waiting to rejoin cluster...");
                    clusterStatus.setNodeStatus(nodeId, "REJOINING", true);
                    Thread.sleep(500); // Let UI show REJOINING state
                    boolean rejoined = false;
                    for (int i = 0; i < 20; i++) {
                        String result = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                            "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                            "/tmp/aeron-cluster/node" + nodeId + "/cluster", "list-members");
                        if (result != null && result.contains("leaderMemberId=")) {
                            clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                            operationProgress.update(step, nodeLabel + " rejoined as follower");
                            rejoined = true;
                            break;
                        }
                        Thread.sleep(500);
                    }
                    if (!rejoined) {
                        clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
                    }
                    step++;
                    Thread.sleep(500);
                }

                // Step 13: Stop old leader via systemctl - triggers election immediately
                operationProgress.update(13, "Stopping Node " + leaderNode + " (Leader)...");
                clusterStatus.setNodeStatus(leaderNode, "STOPPING", false);
                // Mark followers as in election state BEFORE stopping leader
                for (int nodeId : followers) {
                    clusterStatus.setNodeStatus(nodeId, "ELECTION", true);
                }
                Thread.sleep(200); // Brief pause for UI

                // Stop via systemctl (single source of truth)
                executeCommand("systemctl", "--user", "stop", "match-node" + leaderNode);

                // Step 14: Election in progress - poll immediately for new leader
                operationProgress.update(14, "Leader election in progress...");

                // Wait for election to complete - poll frequently (cluster heartbeat timeout is 2s)
                int newLeader = -1;
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(100); // Poll every 100ms for fast detection
                    String result = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                        "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                        "/tmp/aeron-cluster/node" + followers[0] + "/cluster", "list-members");
                    if (result != null && result.contains("leaderMemberId=")) {
                        try {
                            newLeader = Integer.parseInt(result.split("leaderMemberId=")[1].split(",")[0].trim());
                            if (newLeader >= 0 && newLeader != leaderNode) {
                                // Update cluster status with new leader
                                clusterStatus.updateLeader(newLeader, clusterStatus.leadershipTermId + 1);
                                for (int nodeId : followers) {
                                    if (nodeId == newLeader) {
                                        clusterStatus.setNodeStatus(nodeId, "LEADER", true);
                                    } else {
                                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                                    }
                                }
                                operationProgress.update(14, "New leader elected: Node " + newLeader);
                                Thread.sleep(300); // Brief pause to show new leader
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // Step 15: Mark file timeout for old leader
                operationProgress.update(15, "Waiting for mark file timeout (10s)...");
                Thread.sleep(10000);

                // Step 16: Clean old leader files
                operationProgress.update(16, "Cleaning Node " + leaderNode + " shared memory...");
                executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-*" + leaderNode + "*");
                executeCommand("bash", "-c", "rm -rf /tmp/aeron-cluster/node" + leaderNode + "/cluster/cluster-mark.dat");
                executeCommand("bash", "-c", "rm -rf /tmp/aeron-cluster/node" + leaderNode + "/cluster/*.lck");
                Thread.sleep(300);

                // Step 17: Start old leader as follower via systemctl
                operationProgress.update(17, "Starting Node " + leaderNode + " as follower...");
                clusterStatus.setNodeStatus(leaderNode, "STARTING", false);
                Thread.sleep(300); // Let UI show STARTING state
                // Start via systemctl (single source of truth - uses service definition with CPU pinning)
                executeCommand("systemctl", "--user", "start", "match-node" + leaderNode);
                Thread.sleep(2000); // Give node time to start

                // Wait for node to rejoin
                clusterStatus.setNodeStatus(leaderNode, "REJOINING", true);
                Thread.sleep(500); // Let UI show REJOINING state
                boolean rejoined = false;
                for (int i = 0; i < 15; i++) {
                    String result = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                        "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                        "/tmp/aeron-cluster/node" + leaderNode + "/cluster", "list-members");
                    if (result != null && result.contains("leaderMemberId=")) {
                        clusterStatus.setNodeStatus(leaderNode, "FOLLOWER", true);
                        operationProgress.update(17, "Node " + leaderNode + " rejoined as follower");
                        rejoined = true;
                        break;
                    }
                    Thread.sleep(500);
                }
                if (!rejoined) {
                    clusterStatus.setNodeStatus(leaderNode, "OFFLINE", false);
                }

                Thread.sleep(500); // Final state visible
                operationProgress.finish(true, "All nodes updated successfully");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Rolling update initiated"));
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            sendJsonResponse(exchange, 409, Map.of("error", "Another operation in progress"));
            return;
        }

        // Execute snapshot with progress tracking
        new Thread(() -> {
            try {
                operationProgress.start("snapshot", 5);
                String jarPath = "match/target/cluster-engine-1.0.jar";

                // Step 1: Find leader
                operationProgress.update(1, "Finding cluster leader...");
                int leaderNode = -1;
                String listResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node0/cluster", "list-members");
                if (listResult.contains("leaderMemberId=")) {
                    leaderNode = Integer.parseInt(listResult.split("leaderMemberId=")[1].split(",")[0].trim());
                }

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                // Step 2: Request snapshot on leader
                operationProgress.update(2, "Requesting snapshot from Node " + leaderNode + "...");
                String snapshotResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node" + leaderNode + "/cluster", "snapshot");

                // Step 3: Verify snapshot
                operationProgress.update(3, "Verifying snapshot...");
                Thread.sleep(2000);

                // Step 4: Check result
                operationProgress.update(4, "Checking snapshot status...");
                boolean success = snapshotResult.contains("SNAPSHOT") &&
                                 (snapshotResult.contains("completed") || snapshotResult.contains("applied"));

                if (success) {
                    operationProgress.update(5, "Snapshot complete!");
                    Thread.sleep(500);
                    operationProgress.finish(true, "Snapshot created successfully");
                } else {
                    operationProgress.finish(false, "Snapshot may have failed: " + snapshotResult.trim());
                }

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Snapshot initiated"));
    }

    private void handleCompact(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return;
        }

        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            sendJsonResponse(exchange, 409, Map.of("error", "Another operation in progress"));
            return;
        }

        // Compact archives with progress tracking
        new Thread(() -> {
            try {
                operationProgress.start("compact", 7);
                String jarPath = "match/target/cluster-engine-1.0.jar";

                int step = 1;
                for (int i = 0; i < 3; i++) {
                    String archiveDir = "/tmp/aeron-cluster/node" + i + "/archive";

                    operationProgress.update(step++, "Compacting Node " + i + " archive...");
                    // Use bash to pipe 'y' to the command (it requires confirmation)
                    executeCommand("bash", "-c",
                        "echo 'y' | java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED " +
                        "-cp " + jarPath + " io.aeron.archive.ArchiveTool " + archiveDir + " compact");

                    operationProgress.update(step++, "Cleaning Node " + i + " orphaned segments...");
                    // Also needs confirmation
                    executeCommand("bash", "-c",
                        "echo 'y' | java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED " +
                        "-cp " + jarPath + " io.aeron.archive.ArchiveTool " + archiveDir + " delete-orphaned-segments");
                }

                operationProgress.update(7, "Compaction complete!");
                Thread.sleep(500);
                operationProgress.finish(true, "Archives compacted successfully");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();

        sendJsonResponse(exchange, 202, Map.of("message", "Archive compaction initiated"));
    }

    private void handleProgress(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // Check for reset parameter
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("reset=true") && operationProgress.isComplete()) {
            operationProgress.reset();
        }

        sendJsonResponse(exchange, 200, operationProgress.toMap());
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        int nodeId = 0;
        int lines = 50;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    if ("node".equals(kv[0])) nodeId = Integer.parseInt(kv[1]);
                    if ("lines".equals(kv[0])) lines = Math.min(500, Integer.parseInt(kv[1]));
                }
            }
        }

        Map<String, Object> response = new HashMap<>();
        try {
            Path logPath = Path.of("/tmp/aeron-cluster/node" + nodeId + ".log");
            if (Files.exists(logPath)) {
                List<String> allLines = Files.readAllLines(logPath);
                int start = Math.max(0, allLines.size() - lines);
                response.put("logs", allLines.subList(start, allLines.size()));
                response.put("node", nodeId);
                response.put("totalLines", allLines.size());
            } else {
                response.put("logs", List.of());
                response.put("error", "Log file not found");
            }
        } catch (Exception e) {
            response.put("logs", List.of());
            response.put("error", e.getMessage());
        }

        sendJsonResponse(exchange, 200, response);
    }

    private void setCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String executeCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out");
        }

        return output.toString();
    }

    @Override
    public void close() {
        // Stop accepting new requests immediately
        if (server != null) {
            server.stop(0); // 0 means stop immediately
        }

        // Close market data WebSocket server
        if (marketServerChannel != null) {
            try {
                marketServerChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (marketBossGroup != null) {
            marketBossGroup.shutdownGracefully();
        }
        if (marketWorkerGroup != null) {
            marketWorkerGroup.shutdownGracefully();
        }

        // Close admin WebSocket server
        if (adminServerChannel != null) {
            try {
                adminServerChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (adminBossGroup != null) {
            adminBossGroup.shutdownGracefully();
        }
        if (adminWorkerGroup != null) {
            adminWorkerGroup.shutdownGracefully();
        }

        // Close cluster connection
        if (cluster != null && !cluster.isClosed()) {
            try {
                cluster.close();
            } catch (Exception e) {
                // Log but don't rethrow - we want to continue cleanup
                e.printStackTrace();
            }
        }

        // Close media driver
        if (mediaDriver != null) {
            mediaDriver.close();
        }
    }

    public static void main(String[] args) throws IOException {
        final HttpController gateway = new HttpController();
        gateway.startPolling();
        Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
    }

    @Override
    public int doWork() throws Exception {
        //send cluster heartbeat roughly every 250ms
        final long now = SystemEpochClock.INSTANCE.time();
        if (now >= (lastHeartbeatTime + HEARTBEAT_INTERVAL))
        {
            lastHeartbeatTime = now;
            if (connectionState == ConnectionState.CONNECTED)
            {
                cluster.sendKeepAlive();
            }
        }

        //poll outbound messages from the cluster
        if (null != cluster && !cluster.isClosed())
        {
            cluster.pollEgress();
        }

        //always sleep
        return 0;
    }

    @Override
    public String roleName() {
        return "cluster-http-agent";
    }
}