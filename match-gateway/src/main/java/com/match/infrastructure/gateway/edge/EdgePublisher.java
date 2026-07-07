// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.edge;

import com.match.infrastructure.gateway.state.GatewayStateManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional outbound publisher to the market-relay edge Worker
 * (edge/market-relay): every broadcast frame is sent ONCE over this
 * connection and the edge fans it out to all viewers, so origin upload
 * stays O(1) in viewer count instead of O(n).
 *
 * Enabled by MARKET_EDGE_URL (e.g. wss://market-relay.example.workers.dev/publish)
 * plus MARKET_EDGE_TOKEN or MARKET_EDGE_TOKEN_FILE. Misconfiguration logs and
 * disables the publisher; it never prevents the gateway from serving directly.
 *
 * Two flows feed the edge:
 *  - the live tee: MarketDataWebSocket.broadcastMarketData and the
 *    ClusterStatus broadcasts call {@link #publish};
 *  - EDGE_CACHE bundles every MARKET_EDGE_BUNDLE_MS (default 5s): the cached
 *    book snapshot, ticker, recent trades, and 1m candle history per market,
 *    wrapped in {"type":"EDGE_CACHE","frame":...} so the relay refreshes its
 *    subscribe-time cache without re-broadcasting to viewers. The book bundle
 *    also bounds the relay's delta replay buffer.
 *
 * Delivery is best-effort by design: the queue drops oldest under pressure
 * (the stream is snapshot-recoverable, same rationale as writeOrConflate),
 * and the relay heals any hole via the client refresh path.
 */
public final class EdgePublisher implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 8192;
    private static final long RECONNECT_MIN_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long STATS_INTERVAL_MS = 60_000;

    // Surfaced in logs; same pattern as MarketDataWebSocket's counters.
    public final AtomicLong published = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong reconnects = new AtomicLong();

    private final URI uri;
    private final String token;
    private final int[] marketIds;
    private final long bundleIntervalMs;
    private final GatewayStateManager stateManager;

    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private volatile Channel channel; // non-null AND handshaken when connected
    private volatile boolean handshaken;
    private volatile boolean closed;
    private long reconnectDelayMs = RECONNECT_MIN_MS;

    /** Returns null (publisher disabled) unless MARKET_EDGE_URL is configured. */
    public static EdgePublisher startOrNull(GatewayStateManager stateManager) {
        String url = System.getenv("MARKET_EDGE_URL");
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String token = resolveToken();
            if (token == null || token.isBlank()) {
                System.err.println("[EDGE] MARKET_EDGE_URL is set but no MARKET_EDGE_TOKEN(_FILE); publisher disabled");
                return null;
            }
            EdgePublisher publisher = new EdgePublisher(new URI(url), token, stateManager);
            publisher.start();
            System.out.println("[EDGE] Publishing market data to " + url);
            return publisher;
        } catch (Exception e) {
            System.err.println("[EDGE] Invalid edge publisher config, disabled: " + e.getMessage());
            return null;
        }
    }

    private static String resolveToken() throws Exception {
        String token = System.getenv("MARKET_EDGE_TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }
        String file = System.getenv("MARKET_EDGE_TOKEN_FILE");
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file)).trim();
        }
        return null;
    }

    private EdgePublisher(URI uri, String token, GatewayStateManager stateManager) {
        this.uri = uri;
        this.token = token;
        this.stateManager = stateManager;
        this.marketIds = parseMarkets(System.getenv().getOrDefault("MARKET_EDGE_MARKETS", "1,2,3,4,5"));
        this.bundleIntervalMs = Long.parseLong(System.getenv().getOrDefault("MARKET_EDGE_BUNDLE_MS", "5000"));
    }

    private static int[] parseMarkets(String csv) {
        String[] parts = csv.split(",");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ids[i] = Integer.parseInt(parts[i].trim());
        }
        return ids;
    }

    private void start() {
        connect();
        group.scheduleAtFixedRate(this::enqueueCacheBundles, bundleIntervalMs, bundleIntervalMs, TimeUnit.MILLISECONDS);
        group.scheduleAtFixedRate(this::logStats, STATS_INTERVAL_MS, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Enqueue one frame for the edge. Lock-free from broadcast threads; drops
     * the OLDEST frame under pressure so fresh state wins.
     */
    public void publish(String jsonFrame) {
        if (closed) {
            return;
        }
        while (!queue.offer(jsonFrame)) {
            queue.poll();
            dropped.incrementAndGet();
        }
        scheduleDrain();
    }

    // ── connection management ───────────────────────────────────────────────

    private void connect() {
        if (closed) {
            return;
        }
        final boolean tls = "wss".equalsIgnoreCase(uri.getScheme());
        final int port = uri.getPort() != -1 ? uri.getPort() : (tls ? 443 : 80);
        final SslContext sslContext;
        try {
            sslContext = tls ? SslContextBuilder.forClient().build() : null;
        } catch (Exception e) {
            System.err.println("[EDGE] TLS context failed, publisher disabled: " + e.getMessage());
            return;
        }

        DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.add("Authorization", "Bearer " + token);
        final WebSocketClientProtocolHandler wsHandler = new WebSocketClientProtocolHandler(
            WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(uri)
                .customHeaders(headers)
                .maxFramePayloadLength(1 << 20)
                .build());

        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    if (sslContext != null) {
                        p.addLast(sslContext.newHandler(ch.alloc(), uri.getHost(), port));
                    }
                    p.addLast(new HttpClientCodec());
                    p.addLast(new HttpObjectAggregator(65536));
                    p.addLast(wsHandler);
                    p.addLast(new EdgeHandler());
                }
            });

        bootstrap.connect(uri.getHost(), port).addListener(future -> {
            if (!future.isSuccess()) {
                onDisconnected("connect failed: " + future.cause());
            }
        });
    }

    private void onDisconnected(String reason) {
        handshaken = false;
        channel = null;
        if (closed) {
            return;
        }
        System.err.println("[EDGE] Disconnected (" + reason + "); reconnecting in " + reconnectDelayMs + "ms");
        reconnects.incrementAndGet();
        group.schedule(this::connect, reconnectDelayMs, TimeUnit.MILLISECONDS);
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
    }

    private final class EdgeHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt == ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                channel = ctx.channel();
                handshaken = true;
                reconnectDelayMs = RECONNECT_MIN_MS;
                System.out.println("[EDGE] Connected to " + uri.getHost());
                enqueueCacheBundles(); // seed the relay immediately
                scheduleDrain();
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            // The relay sends nothing the publisher needs; control frames are
            // handled by WebSocketClientProtocolHandler.
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            onDisconnected("channel closed");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            if (ctx.channel().isWritable()) {
                scheduleDrain();
            }
        }
    }

    // ── frame draining (single writer: the channel's event loop) ───────────

    private void scheduleDrain() {
        Channel ch = channel;
        if (ch == null || !handshaken || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        ch.eventLoop().execute(this::drain);
    }

    private void drain() {
        drainScheduled.set(false);
        Channel ch = channel;
        if (ch == null || !handshaken) {
            return;
        }
        int written = 0;
        String frame;
        while (ch.isWritable() && (frame = queue.poll()) != null) {
            ch.write(new TextWebSocketFrame(frame));
            written++;
        }
        if (written > 0) {
            ch.flush();
            published.addAndGet(written);
        }
        if (!queue.isEmpty() && ch.isWritable()) {
            scheduleDrain();
        }
    }

    // ── periodic EDGE_CACHE bundles ─────────────────────────────────────────

    private void enqueueCacheBundles() {
        if (!handshaken || stateManager == null) {
            return;
        }
        for (int marketId : marketIds) {
            enqueueCache(stateManager.getInitialBookSnapshot(marketId));
            enqueueCache(stateManager.getTickerStats(marketId));
            enqueueCache(stateManager.getTrades().toJsonForMarket(50, marketId));
            enqueueCache(stateManager.buildCandleHistoryJson(marketId, "1m", 200));
        }
    }

    private void enqueueCache(String innerFrame) {
        if (innerFrame != null) {
            publish("{\"type\":\"EDGE_CACHE\",\"frame\":" + innerFrame + "}");
        }
    }

    private void logStats() {
        System.out.println("[EDGE] published=" + published.get() + " dropped=" + dropped.get()
            + " reconnects=" + reconnects.get() + " queued=" + queue.size()
            + " connected=" + handshaken);
    }

    @Override
    public void close() {
        closed = true;
        Channel ch = channel;
        if (ch != null) {
            ch.close();
        }
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
}
