package com.match.infrastructure.gateway.state;

import java.util.concurrent.locks.StampedLock;

/**
 * Per-market ticker statistics accumulated from trades.
 * Thread-safe: single writer (egress polling thread), multiple readers (WebSocket/HTTP handlers).
 * Uses StampedLock for optimistic reads - same pattern as GatewayOrderBook.
 */
public class TickerStats {
    private volatile double lastPrice = 0.0;
    private volatile double openPrice = 0.0;  // First price seen (for change calculation)
    private volatile double high24h = 0.0;
    private volatile double low24h = Double.MAX_VALUE;
    private volatile double volume24h = 0.0;
    private volatile long lastUpdateMs = 0;
    private volatile boolean hasData = false;
    private volatile String cachedJson;

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
            lastUpdateMs = System.currentTimeMillis();
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
        double priceChange = lastPrice - openPrice;
        double pctChange = openPrice > 0 ? (priceChange / openPrice * 100) : 0;
        return String.format(
            "{\"type\":\"TICKER_STATS\",\"marketId\":%d,\"market\":\"%s\"," +
            "\"lastPrice\":%.8f,\"priceChange\":%.8f,\"priceChangePercent\":%.4f," +
            "\"high24h\":%.8f,\"low24h\":%.8f,\"volume24h\":%.2f,\"timestamp\":%d}",
            marketId, marketName, lastPrice, priceChange, pctChange,
            high24h, low24h == Double.MAX_VALUE ? 0 : low24h, volume24h, lastUpdateMs);
    }

    private String buildEmptyJson() {
        return String.format(
            "{\"type\":\"TICKER_STATS\",\"marketId\":%d,\"market\":\"%s\"," +
            "\"lastPrice\":0,\"priceChange\":0,\"priceChangePercent\":0," +
            "\"high24h\":0,\"low24h\":0,\"volume24h\":0,\"timestamp\":0}",
            marketId, marketName);
    }
}
