package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-size ring buffer for recent trades.
 * Lock-free single-writer, multi-reader using volatile and copy-on-read.
 */
public class TradeRingBuffer {
    private static final int DEFAULT_CAPACITY = 500;

    private final AggregatedTrade[] trades;
    private final int capacity;
    private volatile int head = 0;  // Points to next write position
    private volatile int count = 0; // Number of valid entries

    private volatile int marketId;
    private volatile String marketName;

    public TradeRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public TradeRingBuffer(int capacity) {
        this.capacity = capacity;
        this.trades = new AggregatedTrade[capacity];
        // Pre-allocate all entries
        for (int i = 0; i < capacity; i++) {
            trades[i] = new AggregatedTrade();
        }
    }

    /**
     * Add a batch of trades from TRADES_BATCH message.
     * Called from egress polling thread (single writer).
     */
    public void addBatch(int marketId, String marketName, JsonArray tradesArray) {
        this.marketId = marketId;
        this.marketName = marketName;

        for (int i = 0; i < tradesArray.size(); i++) {
            JsonObject trade = tradesArray.get(i).getAsJsonObject();

            // Get the slot to write to
            AggregatedTrade entry = trades[head];

            // Parse trade data
            entry.price = trade.get("price").getAsDouble();
            entry.quantity = trade.get("quantity").getAsDouble();
            entry.tradeCount = trade.get("tradeCount").getAsInt();
            entry.buyCount = trade.has("buyCount") ? trade.get("buyCount").getAsInt() : 0;
            entry.sellCount = trade.has("sellCount") ? trade.get("sellCount").getAsInt() : 0;
            entry.timestamp = trade.get("timestamp").getAsLong();

            // Advance head (circular)
            head = (head + 1) % capacity;
            if (count < capacity) {
                count++;
            }
        }
    }

    /**
     * Get the most recent N trades.
     * Returns a copy for thread safety.
     */
    public List<AggregatedTrade> getRecent(int n) {
        // Capture volatile reads
        int localHead = head;
        int localCount = count;

        int toRead = Math.min(n, localCount);
        List<AggregatedTrade> result = new ArrayList<>(toRead);

        // Read backwards from head (most recent first)
        for (int i = 0; i < toRead; i++) {
            int idx = (localHead - 1 - i + capacity) % capacity;
            AggregatedTrade copy = new AggregatedTrade();
            copy.copyFrom(trades[idx]);
            result.add(copy);
        }

        return result;
    }

    /**
     * Get JSON representation of recent trades.
     */
    public String toJson(int limit) {
        List<AggregatedTrade> recent = getRecent(limit);

        JsonObject json = new JsonObject();
        json.addProperty("type", "TRADES_HISTORY");
        json.addProperty("marketId", marketId);
        json.addProperty("market", marketName != null ? marketName : "UNKNOWN");
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("count", recent.size());

        JsonArray tradesArray = new JsonArray();
        for (AggregatedTrade t : recent) {
            JsonObject obj = new JsonObject();
            obj.addProperty("price", t.price);
            obj.addProperty("quantity", t.quantity);
            obj.addProperty("tradeCount", t.tradeCount);
            obj.addProperty("buyCount", t.buyCount);
            obj.addProperty("sellCount", t.sellCount);
            obj.addProperty("timestamp", t.timestamp);
            tradesArray.add(obj);
        }
        json.add("trades", tradesArray);

        return json.toString();
    }

    /**
     * Get count of stored trades.
     */
    public int getCount() {
        return count;
    }

    /**
     * Check if buffer has any trades.
     */
    public boolean hasData() {
        return count > 0;
    }
}
