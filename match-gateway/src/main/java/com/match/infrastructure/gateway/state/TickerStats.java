// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.state;

import java.util.concurrent.locks.StampedLock;

/**
 * Per-market ticker statistics accumulated from trades.
 * Thread-safe: single writer (egress polling thread), multiple readers (WebSocket/HTTP handlers).
 * Uses StampedLock for optimistic reads - same pattern as GatewayOrderBook.
 */
public class TickerStats {
    /** A DB baseline older than this is ignored (falls back to since-boot semantics). */
    static final long BASELINE_FRESH_MS = 30_000;

    private volatile double lastPrice = 0.0;
    private volatile double openPrice = 0.0;  // First price seen (for change calculation)
    private volatile double high24h = 0.0;
    private volatile double low24h = Double.MAX_VALUE;
    private volatile double volume24h = 0.0;
    private volatile long lastUpdateMs = 0;
    private volatile boolean hasData = false;
    private volatile String cachedJson;

    // Rolling-24h baseline computed from the persisted 1m candles, plus
    // live-since-baseline accumulators. When the baseline is fresh the ticker
    // reports true rolling-24h figures (baseline merged with live trades);
    // when it goes stale (DB down) the legacy since-boot fields above take over.
    private volatile double baseOpen24h = 0.0;
    private volatile double baseHigh24h = 0.0;
    private volatile double baseLow24h = 0.0;
    private volatile double baseQuoteVol24h = 0.0;
    private volatile long baselineAsOfMs = 0;
    private volatile double liveHigh = 0.0;
    private volatile double liveLow = Double.MAX_VALUE;
    private volatile double liveQuoteVol = 0.0;
    private volatile boolean liveHasData = false;

    private final StampedLock lock = new StampedLock();
    private final int marketId;
    private final String marketName;

    public TickerStats(int marketId, String marketName) {
        this.marketId = marketId;
        this.marketName = marketName;
    }

    /**
     * Update stats from a trade execution.
     * Called from egress polling thread (single writer).
     */
    public void updateFromTrade(double price, double quantity) {
        long stamp = lock.writeLock();
        try {
            if (!hasData) {
                openPrice = price;
                high24h = price;
                low24h = price;
                hasData = true;
            } else {
                high24h = Math.max(high24h, price);
                low24h = Math.min(low24h, price);
            }
            lastPrice = price;
            volume24h += price * quantity;
            if (!liveHasData) {
                liveHigh = price;
                liveLow = price;
                liveHasData = true;
            } else {
                liveHigh = Math.max(liveHigh, price);
                liveLow = Math.min(liveLow, price);
            }
            liveQuoteVol += price * quantity;
            lastUpdateMs = System.currentTimeMillis();
            cachedJson = buildJson();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Apply a rolling-24h baseline computed from the persisted 1m candles.
     * Called from the persistence ticker thread every few seconds. Resets the
     * live-since-baseline accumulators (their window is now inside the
     * baseline). Also seeds lastPrice after a restart so the ticker is
     * populated before the first post-boot trade.
     */
    public void applyDbBaseline(double open24h, double high24hDb, double low24hDb,
                                double quoteVol24h, double lastClose, long asOfMs) {
        long stamp = lock.writeLock();
        try {
            baseOpen24h = open24h;
            baseHigh24h = high24hDb;
            baseLow24h = low24hDb;
            baseQuoteVol24h = quoteVol24h;
            baselineAsOfMs = asOfMs;
            liveHigh = 0.0;
            liveLow = Double.MAX_VALUE;
            liveQuoteVol = 0.0;
            liveHasData = false;
            if (!hasData && lastClose > 0) {
                lastPrice = lastClose;
                openPrice = open24h > 0 ? open24h : lastClose;
                high24h = high24hDb > 0 ? high24hDb : lastClose;
                low24h = low24hDb > 0 ? low24hDb : lastClose;
                lastUpdateMs = asOfMs;
                hasData = true;
            }
            cachedJson = buildJson();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Get current stats as JSON. Uses optimistic read for lock-free fast path.
     */
    public String toJson() {
        long stamp = lock.tryOptimisticRead();
        String json = cachedJson;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                json = cachedJson;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return json != null ? json : buildEmptyJson();
    }

    /**
     * Check if this ticker has received any trade data.
     */
    public boolean hasData() {
        return hasData;
    }

    /**
     * Get last price (for mid-price calculations).
     */
    public double getLastPrice() {
        return lastPrice;
    }

    private String buildJson() {
        boolean fresh = baselineAsOfMs > 0
                && System.currentTimeMillis() - baselineAsOfMs < BASELINE_FRESH_MS;
        double open, high, low, volume;
        if (fresh) {
            // True rolling 24h: DB baseline merged with trades seen since it
            open = baseOpen24h > 0 ? baseOpen24h : openPrice;
            high = Math.max(baseHigh24h, liveHasData ? liveHigh : 0);
            if (high == 0) {
                high = high24h;
            }
            if (liveHasData && baseLow24h > 0) {
                low = Math.min(baseLow24h, liveLow);
            } else if (liveHasData) {
                low = liveLow;
            } else if (baseLow24h > 0) {
                low = baseLow24h;
            } else {
                low = low24h == Double.MAX_VALUE ? 0 : low24h;
            }
            volume = baseQuoteVol24h + liveQuoteVol;
        } else {
            // Legacy since-boot semantics (DB down or persistence disabled)
            open = openPrice;
            high = high24h;
            low = low24h == Double.MAX_VALUE ? 0 : low24h;
            volume = volume24h;
        }
        double priceChange = open > 0 && lastPrice > 0 ? lastPrice - open : 0;
        double pctChange = open > 0 ? (priceChange / open * 100) : 0;
        return String.format(
            "{\"type\":\"TICKER_STATS\",\"marketId\":%d,\"market\":\"%s\"," +
            "\"lastPrice\":%.8f,\"priceChange\":%.8f,\"priceChangePercent\":%.4f," +
            "\"high24h\":%.8f,\"low24h\":%.8f,\"volume24h\":%.2f,\"timestamp\":%d}",
            marketId, marketName, lastPrice, priceChange, pctChange,
            high, low, volume, lastUpdateMs);
    }

    private String buildEmptyJson() {
        return String.format(
            "{\"type\":\"TICKER_STATS\",\"marketId\":%d,\"market\":\"%s\"," +
            "\"lastPrice\":0,\"priceChange\":0,\"priceChangePercent\":0," +
            "\"high24h\":0,\"low24h\":0,\"volume24h\":0,\"timestamp\":0}",
            marketId, marketName);
    }
}
