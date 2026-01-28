package com.match.infrastructure.gateway.state;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory candle aggregation using ring buffers.
 * Lock-free single-writer, multi-reader using volatile + copy-on-read (same pattern as TradeRingBuffer).
 *
 * Supports intervals: 1m, 5m, 15m, 1h, 4h, 1d
 * Per-market, per-interval ring buffers holding last 500 candles.
 * 5 markets × 6 intervals × 500 candles = 15K candles (~1.2MB)
 */
public class InMemoryCandleProvider implements CandleProvider {
    private static final int DEFAULT_CAPACITY = 500;
    private static final int MAX_MARKETS = 6; // market IDs 1-5, index 0 unused

    // Supported intervals and their durations in milliseconds
    static final String[] INTERVALS = {"1m", "5m", "15m", "1h", "4h", "1d"};
    private static final long[] INTERVAL_MS = {
        60_000L,        // 1m
        300_000L,       // 5m
        900_000L,       // 15m
        3_600_000L,     // 1h
        14_400_000L,    // 4h
        86_400_000L     // 1d
    };

    // Ring buffer storage: [marketId][intervalIdx] -> CandleRing
    private final CandleRing[][] rings;
    private final int capacity;

    public InMemoryCandleProvider() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryCandleProvider(int capacity) {
        this.capacity = capacity;
        this.rings = new CandleRing[MAX_MARKETS][INTERVALS.length];
        for (int m = 0; m < MAX_MARKETS; m++) {
            for (int i = 0; i < INTERVALS.length; i++) {
                rings[m][i] = new CandleRing(capacity);
            }
        }
    }

    @Override
    public void onTrade(int marketId, double price, double quantity, long timestampMs) {
        if (marketId <= 0 || marketId >= MAX_MARKETS) return;

        for (int i = 0; i < INTERVALS.length; i++) {
            long intervalMs = INTERVAL_MS[i];
            long bucketMs = (timestampMs / intervalMs) * intervalMs;
            long bucketSec = bucketMs / 1000;

            rings[marketId][i].updateOrCreate(marketId, bucketSec, price, quantity);
        }
    }

    @Override
    public List<Candle> getCandles(int marketId, String interval, int limit) {
        int intervalIdx = intervalIndex(interval);
        if (intervalIdx < 0 || marketId <= 0 || marketId >= MAX_MARKETS) {
            return new ArrayList<>();
        }
        return rings[marketId][intervalIdx].getRecent(limit);
    }

    @Override
    public Candle getCurrentCandle(int marketId, String interval) {
        int intervalIdx = intervalIndex(interval);
        if (intervalIdx < 0 || marketId <= 0 || marketId >= MAX_MARKETS) {
            return null;
        }
        return rings[marketId][intervalIdx].getCurrent();
    }

    /**
     * Resolve interval string to array index.
     * Returns -1 for unknown intervals.
     */
    static int intervalIndex(String interval) {
        switch (interval) {
            case "1m":  return 0;
            case "5m":  return 1;
            case "15m": return 2;
            case "1h":  return 3;
            case "4h":  return 4;
            case "1d":  return 5;
            default:    return -1;
        }
    }

    /**
     * Fixed-size ring buffer for candles of a single market+interval.
     * Single writer (egress thread), multiple readers (WebSocket/HTTP threads).
     * Uses volatile head/count for visibility and copy-on-read for safety.
     */
    static class CandleRing {
        private final Candle[] candles;
        private final int capacity;
        private volatile int head = 0;   // Next write position
        private volatile int count = 0;  // Number of valid entries

        CandleRing(int capacity) {
            this.capacity = capacity;
            this.candles = new Candle[capacity];
            for (int i = 0; i < capacity; i++) {
                candles[i] = new Candle();
            }
        }

        /**
         * Update existing candle at bucket time, or create a new one.
         * Called from egress thread only (single writer).
         */
        void updateOrCreate(int marketId, long bucketSec, double price, double quantity) {
            // Check if the most recent candle matches this bucket
            if (count > 0) {
                int lastIdx = (head - 1 + capacity) % capacity;
                Candle last = candles[lastIdx];
                if (last.time == bucketSec) {
                    // Update existing candle
                    last.high = Math.max(last.high, price);
                    last.low = Math.min(last.low, price);
                    last.close = price;
                    last.volume += quantity;
                    last.tradeCount++;
                    return;
                }
            }

            // Create new candle at head position
            Candle c = candles[head];
            c.time = bucketSec;
            c.open = price;
            c.high = price;
            c.low = price;
            c.close = price;
            c.volume = quantity;
            c.tradeCount = 1;
            c.marketId = marketId;

            head = (head + 1) % capacity;
            if (count < capacity) {
                count++;
            }
        }

        /**
         * Get the most recent N candles in ascending time order (oldest first).
         * Returns copies for thread safety.
         */
        List<Candle> getRecent(int n) {
            int localHead = head;
            int localCount = count;
            int toReturn = Math.min(n, localCount);

            List<Candle> result = new ArrayList<>(toReturn);

            // Read the last toReturn candles, starting from oldest
            for (int i = toReturn - 1; i >= 0; i--) {
                int idx = (localHead - 1 - i + capacity) % capacity;
                Candle copy = new Candle();
                copy.copyFrom(candles[idx]);
                result.add(copy);
            }

            return result;
        }

        /**
         * Get the current (most recent) candle.
         * Returns a copy for thread safety, or null if empty.
         */
        Candle getCurrent() {
            int localCount = count;
            if (localCount == 0) return null;

            int localHead = head;
            int lastIdx = (localHead - 1 + capacity) % capacity;
            Candle copy = new Candle();
            copy.copyFrom(candles[lastIdx]);
            return copy;
        }

        /**
         * Get count of stored candles.
         */
        int getCount() {
            return count;
        }
    }
}
