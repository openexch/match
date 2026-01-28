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
    private static final int DEFAULT_CAPACITY = 100;

    private final AggregatedTrade[] trades;
    private final int capacity;
    private volatile int head = 0;  // Points to next write position
    private volatile int count = 0; // Number of valid entries

    // Per-trade market tracking (each slot knows its market)
    private final int[] tradeMarketIds;
    private final String[] tradeMarketNames;

    public TradeRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public TradeRingBuffer(int capacity) {
        this.capacity = capacity;
        this.trades = new AggregatedTrade[capacity];
        this.tradeMarketIds = new int[capacity];
        this.tradeMarketNames = new String[capacity];
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
        for (int i = 0; i < tradesArray.size(); i++) {
            JsonObject trade = tradesArray.get(i).getAsJsonObject();

            // Get the slot to write to
            AggregatedTrade entry = trades[head];
            tradeMarketIds[head] = marketId;
            tradeMarketNames[head] = marketName;

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
     * Get the most recent N trades (all markets).
     * Returns a copy for thread safety.
     */
    public List<AggregatedTrade> getRecent(int n) {
        return getRecentForMarket(n, 0); // 0 = all markets
    }

    /**
     * Get the most recent N trades for a specific market.
     * @param n max number of trades to return
     * @param filterMarketId market ID to filter by, or 0 for all markets
     * Returns a copy for thread safety.
     */
    public List<AggregatedTrade> getRecentForMarket(int n, int filterMarketId) {
        // Capture volatile reads
        int localHead = head;
        int localCount = count;

        List<AggregatedTrade> result = new ArrayList<>(Math.min(n, localCount));

        // Read backwards from head (most recent first)
        for (int i = 0; i < localCount && result.size() < n; i++) {
            int idx = (localHead - 1 - i + capacity) % capacity;
            if (filterMarketId == 0 || tradeMarketIds[idx] == filterMarketId) {
                AggregatedTrade copy = new AggregatedTrade();
                copy.copyFrom(trades[idx]);
                result.add(copy);
            }
        }

        return result;
    }

    /**
     * Get JSON representation of recent trades for a specific market.
     * Uses TRADES_BATCH type so the UI handles it uniformly.
     */
    public String toJson(int limit) {
        return toJsonForMarket(limit, 0);
    }

    /**
     * Get JSON representation of recent trades for a specific market.
     * @param limit max number of trades
     * @param marketId market to filter by, or 0 for all
     */
    public String toJsonForMarket(int limit, int marketId) {
        List<AggregatedTrade> recent = getRecentForMarket(limit, marketId);

        // Resolve market info
        int resolvedMarketId = marketId;
        String resolvedName = "UNKNOWN";
        int localHead = head;
        int localCount = count;
        for (int i = 0; i < localCount; i++) {
            int idx = (localHead - 1 - i + capacity) % capacity;
            if (marketId == 0 || tradeMarketIds[idx] == marketId) {
                if (tradeMarketNames[idx] != null) {
                    resolvedName = tradeMarketNames[idx];
                    resolvedMarketId = tradeMarketIds[idx];
                    break;
                }
            }
        }

        JsonObject json = new JsonObject();
        // Use TRADES_BATCH so the UI handles it the same as live trades
        json.addProperty("type", "TRADES_BATCH");
        json.addProperty("marketId", resolvedMarketId);
        json.addProperty("market", resolvedName);
        json.addProperty("timestamp", System.currentTimeMillis());

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
