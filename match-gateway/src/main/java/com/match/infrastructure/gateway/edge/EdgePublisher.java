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
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

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
    // A small, separate lane for the frames that reset the relay's cache and
    // trim its delta buffer: the periodic EDGE_CACHE bundles and any live full
    // book snapshot. These are the relay's ONLY resync path, so a burst of
    // deltas must never evict them (match#99 item 2). Sized for many bundle
    // cycles (4 frames x markets each) well within the 30s liveness watchdog.
    private static final int PRIORITY_QUEUE_CAPACITY = 256;
    // Frames are gateway-produced compact JSON with "type" first (Gson keeps
    // insertion order), so a prefix test classifies them without parsing.
    private static final String EDGE_CACHE_PREFIX = "{\"type\":\"EDGE_CACHE\"";
    private static final String BOOK_SNAPSHOT_PREFIX = "{\"type\":\"BOOK_SNAPSHOT\"";
    private static final long RECONNECT_MIN_MS = 1_000;
    private static final long RECONNECT_MAX_MS = 30_000;
    private static final long STATS_INTERVAL_MS = 60_000;
    // Liveness watchdog (learned live 2026-07-07: a Worker redeploy severed
    // the socket without a TCP close; the publisher kept queueing into a
    // half-open connection for minutes and the public feed froze). We ping
    // every PING_INTERVAL_S; the edge answers pongs even while the DO
    // hibernates. READ_IDLE_S of silence means the connection is dead:
    // close it, which triggers the normal reconnect path.
    private static final int PING_INTERVAL_S = 10;
    private static final int READ_IDLE_S = 30;

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
    // Flushed before `queue` on every drain; a snapshot always beats pending deltas.
    private final ArrayBlockingQueue<String> priorityQueue = new ArrayBlockingQueue<>(PRIORITY_QUEUE_CAPACITY);
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
        // Fixed-schedule liveness pings (NOT write-idle-driven: a busy
        // publisher never goes write-idle, yet reads nothing on a healthy
        // connection unless something solicits pongs).
        group.scheduleAtFixedRate(() -> {
            Channel ch = channel;
            if (ch != null && handshaken) {
                ch.writeAndFlush(new PingWebSocketFrame());
            }
        }, PING_INTERVAL_S, PING_INTERVAL_S, TimeUnit.SECONDS);
    }

    /**
     * Enqueue one frame for the edge. Lock-free from broadcast threads.
     *
     * Snapshot/bundle frames take a separate priority lane that is flushed
     * first and is never evicted by deltas (match#99 item 2): they are the only
     * thing that resets the relay's snapshotVersion and trims its replay buffer,
     * so a delta burst must not starve them. Everything else (live deltas,
     * ticker, cluster events) keeps the drop-OLDEST-under-pressure semantics so
     * fresh state wins. Both lanes are bounded, so memory stays bounded.
     */
    public void publish(String jsonFrame) {
        if (closed) {
            return;
        }
        ArrayBlockingQueue<String> lane = isPriority(jsonFrame) ? priorityQueue : queue;
        while (!lane.offer(jsonFrame)) {
            // On the priority lane a full queue means bundles are backing up; the
            // oldest snapshot is already superseded by a newer one, so dropping it
            // still leaves the freshest resync frame. Either way, bounded memory.
            lane.poll();
            dropped.incrementAndGet();
        }
        scheduleDrain();
    }

    /** Bundle envelopes and live full book snapshots are the relay's resync path. */
    private static boolean isPriority(String jsonFrame) {
        return jsonFrame.startsWith(EDGE_CACHE_PREFIX) || jsonFrame.startsWith(BOOK_SNAPSHOT_PREFIX);
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
                    p.addLast(new IdleStateHandler(READ_IDLE_S, 0, 0));
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
                return;
            }
            if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
                // Pings go out on a fixed schedule (start()), so a healthy
                // connection reads a pong at least every PING_INTERVAL_S.
                // Silence this long means the pipe is dead.
                System.err.println("[EDGE] No pong/read for " + READ_IDLE_S + "s — connection is dead, reconnecting");
                ctx.close(); // channelInactive triggers the reconnect
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
        // Priority lane first: a queued snapshot/bundle ships ahead of pending
        // deltas so the relay's resync frame is never left behind under load.
        int written = drainLane(ch, priorityQueue) + drainLane(ch, queue);
        if (written > 0) {
            ch.flush();
            published.addAndGet(written);
        }
        if (ch.isWritable() && (!priorityQueue.isEmpty() || !queue.isEmpty())) {
            scheduleDrain();
        }
    }

    private int drainLane(Channel ch, ArrayBlockingQueue<String> lane) {
        int written = 0;
        String frame;
        while (ch.isWritable() && (frame = lane.poll()) != null) {
            ch.write(new TextWebSocketFrame(frame));
            written++;
        }
        return written;
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
            + " priorityQueued=" + priorityQueue.size() + " connected=" + handshaken);
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
