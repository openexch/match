package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.locks.StampedLock;

/**
 * Local order book state maintained from BOOK_SNAPSHOT messages.
 * Thread-safe for concurrent reads, single-writer (egress thread).
 * Uses StampedLock for optimistic reads.
 */
public class GatewayOrderBook {
    private static final int MAX_LEVELS = 20;

    // Pre-allocated arrays for zero-allocation updates
    private final double[] bidPrices = new double[MAX_LEVELS];
    private final double[] bidQuantities = new double[MAX_LEVELS];
    private final int[] bidOrderCounts = new int[MAX_LEVELS];
    private final double[] askPrices = new double[MAX_LEVELS];
    private final double[] askQuantities = new double[MAX_LEVELS];
    private final int[] askOrderCounts = new int[MAX_LEVELS];

    private volatile int bidCount;
    private volatile int askCount;
    private volatile long version;
    private volatile long bidVersion;
    private volatile long askVersion;
    private volatile long lastUpdateMs;
    private volatile int marketId;
    private volatile String marketName;

    // Cached JSON for fast responses (regenerated on update)
    private volatile String cachedJson;

    private final StampedLock lock = new StampedLock();

    /**
     * Update order book from parsed BOOK_SNAPSHOT message.
     * Called from egress polling thread (single writer).
     */
    public void update(int marketId, String marketName, JsonArray bids, JsonArray asks,
                       long bidVersion, long askVersion, long version, long timestamp) {
        long stamp = lock.writeLock();
        try {
            this.marketId = marketId;
            this.marketName = marketName;
            this.bidVersion = bidVersion;
            this.askVersion = askVersion;
            this.version = version;
            this.lastUpdateMs = timestamp;

            // Copy bids
            this.bidCount = Math.min(bids.size(), MAX_LEVELS);
            for (int i = 0; i < bidCount; i++) {
                JsonObject level = bids.get(i).getAsJsonObject();
                bidPrices[i] = level.get("price").getAsDouble();
                bidQuantities[i] = level.get("quantity").getAsDouble();
                bidOrderCounts[i] = level.get("orderCount").getAsInt();
            }

            // Copy asks
            this.askCount = Math.min(asks.size(), MAX_LEVELS);
            for (int i = 0; i < askCount; i++) {
                JsonObject level = asks.get(i).getAsJsonObject();
                askPrices[i] = level.get("price").getAsDouble();
                askQuantities[i] = level.get("quantity").getAsDouble();
                askOrderCounts[i] = level.get("orderCount").getAsInt();
            }

            // Regenerate cached JSON
            this.cachedJson = buildJson();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Get cached JSON representation for fast responses.
     * Uses optimistic read for high concurrency.
     */
    public String toJson() {
        // Try optimistic read first
        long stamp = lock.tryOptimisticRead();
        String json = cachedJson;
        if (!lock.validate(stamp)) {
            // Fallback to read lock
            stamp = lock.readLock();
            try {
                json = cachedJson;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return json;
    }

    /**
     * Check if book has data.
     */
    public boolean hasData() {
        return bidCount > 0 || askCount > 0;
    }

    /**
     * Get current version.
     */
    public long getVersion() {
        return version;
    }

    /**
     * Build JSON representation (called under write lock).
     */
    private String buildJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "BOOK_SNAPSHOT");
        json.addProperty("marketId", marketId);
        json.addProperty("market", marketName != null ? marketName : "UNKNOWN");
        json.addProperty("timestamp", lastUpdateMs);
        json.addProperty("bidVersion", bidVersion);
        json.addProperty("askVersion", askVersion);
        json.addProperty("version", version);

        JsonArray bidsArray = new JsonArray();
        for (int i = 0; i < bidCount; i++) {
            JsonObject l = new JsonObject();
            l.addProperty("price", bidPrices[i]);
            l.addProperty("quantity", bidQuantities[i]);
            l.addProperty("orderCount", bidOrderCounts[i]);
            bidsArray.add(l);
        }
        json.add("bids", bidsArray);

        JsonArray asksArray = new JsonArray();
        for (int i = 0; i < askCount; i++) {
            JsonObject l = new JsonObject();
            l.addProperty("price", askPrices[i]);
            l.addProperty("quantity", askQuantities[i]);
            l.addProperty("orderCount", askOrderCounts[i]);
            asksArray.add(l);
        }
        json.add("asks", asksArray);

        return json.toString();
    }

    // Getters for direct access (use with caution - prefer toJson())
    public int getBidCount() { return bidCount; }
    public int getAskCount() { return askCount; }
    public double getBidPrice(int i) { return bidPrices[i]; }
    public double getAskPrice(int i) { return askPrices[i]; }
    public double getBidQuantity(int i) { return bidQuantities[i]; }
    public double getAskQuantity(int i) { return askQuantities[i]; }
}
