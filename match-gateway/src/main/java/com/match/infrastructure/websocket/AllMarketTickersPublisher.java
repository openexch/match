// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.infrastructure.gateway.state.GatewayStateManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Publishes the all-market TICKERS frame: every market's ticker in one frame, sent to
 * every market-data client whatever market it subscribes to, so the market picker can
 * show live prices for markets the client is not subscribed to.
 *
 * <p>Coalesced and throttled: a dedicated scheduler thread wakes every
 * {@code MARKET_TICKERS_INTERVAL_MS} (default 1000) and builds the frame from the
 * per-market cached ticker strings; it is sent only when the tickers differ from the last
 * frame sent. Any number of trades inside one interval collapse into that one frame.
 *
 * <p>Off the hot path: the Aeron egress thread is not involved at all. It keeps updating
 * {@code TickerStats} exactly as before; this thread only reads the cached strings
 * (volatile reads) and hands the frame to {@link MarketDataWebSocket#broadcastToAll},
 * which tees it to the edge publisher's lock-free queue and enqueues Netty writes.
 */
public final class AllMarketTickersPublisher implements AutoCloseable {

    static final String INTERVAL_ENV = "MARKET_TICKERS_INTERVAL_MS";
    static final long DEFAULT_INTERVAL_MS = 1000;

    private final GatewayStateManager stateManager;
    private final Consumer<String> sink;
    private final LongSupplier clock;
    private final long intervalMs;
    private ScheduledExecutorService scheduler;

    /** Only touched by the scheduler thread (or the test calling {@link #tick}). */
    private String lastSentArray;

    AllMarketTickersPublisher(GatewayStateManager stateManager, Consumer<String> sink,
                              long intervalMs, LongSupplier clock) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException(INTERVAL_ENV + " must be > 0, got " + intervalMs);
        }
        this.stateManager = stateManager;
        this.sink = sink;
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    /** Production wiring: frames go to every connected client and the edge relay. */
    public static AllMarketTickersPublisher create(GatewayStateManager stateManager,
                                                   MarketDataWebSocket webSocket) {
        return new AllMarketTickersPublisher(stateManager, webSocket::broadcastToAll,
            parseInterval(System.getenv(INTERVAL_ENV)), System::currentTimeMillis);
    }

    /** Unset means the default; anything set must be a positive integer (strict: no silent fallback). */
    static long parseInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INTERVAL_MS;
        }
        final long ms;
        try {
            ms = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(INTERVAL_ENV + " must be a positive integer (ms), got '" + raw + "'");
        }
        if (ms <= 0) {
            throw new IllegalArgumentException(INTERVAL_ENV + " must be > 0, got " + ms);
        }
        return ms;
    }

    public long intervalMs() {
        return intervalMs;
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-tickers");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        System.out.println("[WS] All-market TICKERS every " + intervalMs + "ms (when changed)");
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            // Never let one failure cancel the periodic task.
            System.err.println("[WS] TICKERS tick failed: " + t);
        }
    }

    /**
     * One throttle window: send the current TICKERS frame if it differs from the last one
     * sent. Returns the frame sent, or null when nothing changed or no market has a price.
     */
    String tick() {
        String array = stateManager.buildTickersArrayJson();
        if ("[]".equals(array) || array.equals(lastSentArray)) {
            return null;
        }
        String frame = GatewayStateManager.buildTickersFrameJson(array, clock.getAsLong());
        lastSentArray = array;
        sink.accept(frame);
        return frame;
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
