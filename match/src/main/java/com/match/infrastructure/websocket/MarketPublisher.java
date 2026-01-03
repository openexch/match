package com.match.infrastructure.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.OrderStatusType;
import com.match.application.publisher.PublishEvent;
import com.match.application.publisher.PublishEventType;
import com.match.domain.FixedPoint;
import com.match.infrastructure.Logger;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-market publisher that handles Disruptor events and broadcasts to WebSocket clients.
 * Runs on dedicated thread per market (created by Disruptor).
 *
 * Uses 50ms buffering to batch trades and coalesce order book updates.
 * Serializes events to JSON for WebSocket clients.
 */
public class MarketPublisher implements MarketEventHandler {

    private static final Logger logger = Logger.getLogger(MarketPublisher.class);
    private static final long FLUSH_INTERVAL_MS = 50;
    private static final int MAX_BUFFERED_TRADES = 100;
    private static final int MAX_BOOK_LEVELS = 20;

    private final int marketId;
    private final String marketName;
    private final SubscriptionManager subscriptionManager;

    // Optional broadcaster for cluster mode (Aeron egress instead of WebSocket)
    private volatile MarketDataBroadcaster broadcaster;

    // Reference to matching engine for order book snapshots (set after construction)
    private volatile com.match.application.orderbook.DirectMatchingEngine matchingEngine;

    // Scheduler for 50ms periodic flush
    private ScheduledExecutorService scheduler;

    // Trade buffer - aggregate by price within flush interval
    private final Long2ObjectHashMap<AggregatedTrade> tradesByPrice = new Long2ObjectHashMap<>();
    private final Deque<AggregatedTrade> aggregatedTradePool = new ArrayDeque<>(64);

    // Order book buffers - coalesce by price level
    private final TreeMap<Long, BufferedLevel> bidBuffer = new TreeMap<>((a, b) -> Long.compare(b, a)); // Descending
    private final TreeMap<Long, BufferedLevel> askBuffer = new TreeMap<>(); // Ascending
    private final Deque<BufferedLevel> levelPool = new ArrayDeque<>(MAX_BOOK_LEVELS * 2);

    // Flag to track if we have pending book updates
    private boolean hasBookUpdates = false;
    private long lastBookTimestamp = 0;

    // Pre-allocated StringBuilder for JSON building (reused per event)
    private final StringBuilder jsonBuilder = new StringBuilder(512);

    // ORDER_STATUS buffer for batching
    private final java.util.List<OrderStatusEntry> orderStatusBuffer = new java.util.ArrayList<>(100);
    private static class OrderStatusEntry {
        int marketId;
        String market;
        long orderId;
        long userId;  // userId is long, not String
        int status;
        long price;
        long remainingQty;
        long filledQty;
        boolean isBuy;
        long timestamp;
    }

    // Change detection - avoid sending duplicate snapshots
    private long lastBestBid = -1;
    private long lastBestAsk = -1;
    private long lastBidQty = -1;
    private long lastAskQty = -1;
    private int lastBidCount = -1;
    private int lastAskCount = -1;

    // Diagnostic counters
    private long flushCount = 0;
    private long flushErrorCount = 0;

    public MarketPublisher(int marketId, String marketName, SubscriptionManager subscriptionManager) {
        this.marketId = marketId;
        this.marketName = marketName;
        this.subscriptionManager = subscriptionManager;

        // Pre-allocate aggregated trade pool
        for (int i = 0; i < 64; i++) {
            aggregatedTradePool.push(new AggregatedTrade());
        }
        for (int i = 0; i < MAX_BOOK_LEVELS * 2; i++) {
            levelPool.push(new BufferedLevel());
        }
    }

    // Aggregated trade by price - combines all trades at same price in flush interval
    private static class AggregatedTrade {
        long price;
        long totalQuantity;
        int tradeCount;
        long lastTimestamp;
        int buyCount;  // Count of buy-initiated trades
        int sellCount; // Count of sell-initiated trades

        void reset() {
            price = 0;
            totalQuantity = 0;
            tradeCount = 0;
            lastTimestamp = 0;
            buyCount = 0;
            sellCount = 0;
        }

        void add(long quantity, boolean takerIsBuy, long timestamp) {
            totalQuantity += quantity;
            tradeCount++;
            lastTimestamp = timestamp;
            if (takerIsBuy) buyCount++; else sellCount++;
        }
    }

    // Track order book version range for trades correlation
    private long bookVersionMin = Long.MAX_VALUE;
    private long bookVersionMax = Long.MIN_VALUE;

    // Pooled book level object to avoid allocations
    private static class BufferedLevel {
        long price;
        long quantity;
        int orderCount;

        void set(long price, long quantity, int orderCount) {
            this.price = price;
            this.quantity = quantity;
            this.orderCount = orderCount;
        }
    }

    @Override
    public int getMarketId() {
        return marketId;
    }

    /**
     * Set the matching engine reference for order book snapshots.
     * Called during startup wiring.
     */
    public void setMatchingEngine(com.match.application.orderbook.DirectMatchingEngine engine) {
        this.matchingEngine = engine;
    }

    /**
     * Set broadcaster for cluster mode.
     * When set, market data is sent via broadcaster (Aeron egress) instead of WebSocket.
     * This allows the cluster to broadcast to gateways which then relay to WebSocket clients.
     */
    public void setBroadcaster(MarketDataBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void onStart() {
        // Prevent duplicate starts (can happen if Disruptor calls lifecycle methods)
        if (scheduler != null) {
            logger.warn("MarketPublisher.onStart() called again - ignoring duplicate");
            return;
        }

        // Start 50ms periodic flush scheduler
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-flush-" + marketId);
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
            this::flushBuffers,
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void onShutdown() {
        // Final flush before shutdown
        flushBuffers();

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) throws Exception {
        int eventType = event.getEventType();
        if (eventType == PublishEventType.TRADE_EXECUTION) {
            // Buffer trades - order book is fetched directly from engine at 50ms intervals
            bufferTrade(event);
        } else if (eventType == PublishEventType.ORDER_STATUS_UPDATE) {
            // Buffer order status for batched sending (reduces message count)
            bufferOrderStatus(event);
        }
        // ORDER_BOOK_UPDATE not used - snapshots collected directly from engine
    }

    /**
     * Buffer trade by aggregating at price level.
     * Multiple trades at the same price are combined into one aggregated record.
     * Also captures the current order book version for correlation.
     */
    private synchronized void bufferTrade(PublishEvent event) {
        long price = event.getPrice();
        AggregatedTrade agg = tradesByPrice.get(price);

        if (agg == null) {
            agg = aggregatedTradePool.poll();
            if (agg == null) {
                agg = new AggregatedTrade();
            }
            agg.reset();
            agg.price = price;
            tradesByPrice.put(price, agg);
        }

        agg.add(event.getQuantity(), event.isTakerIsBuy(), event.getTimestamp());

        // Capture book version for correlation (read from both books)
        if (matchingEngine != null) {
            long bidVersion = matchingEngine.getBidBook().getVersion();
            long askVersion = matchingEngine.getAskBook().getVersion();
            long maxVersion = Math.max(bidVersion, askVersion);
            if (maxVersion < bookVersionMin) bookVersionMin = maxVersion;
            if (maxVersion > bookVersionMax) bookVersionMax = maxVersion;
        }
    }

    private synchronized void bufferBookUpdate(PublishEvent event) {
        if (event.isSnapshot()) {
            // Full snapshot - replace all levels
            returnLevelsToPool(bidBuffer);
            returnLevelsToPool(askBuffer);
            bidBuffer.clear();
            askBuffer.clear();

            // Copy bid levels
            for (int i = 0; i < event.getBidLevelCount(); i++) {
                BufferedLevel level = levelPool.poll();
                if (level == null) level = new BufferedLevel();
                level.set(event.getBidPrice(i), event.getBidQuantity(i), event.getBidOrderCount(i));
                bidBuffer.put(level.price, level);
            }

            // Copy ask levels
            for (int i = 0; i < event.getAskLevelCount(); i++) {
                BufferedLevel level = levelPool.poll();
                if (level == null) level = new BufferedLevel();
                level.set(event.getAskPrice(i), event.getAskQuantity(i), event.getAskOrderCount(i));
                askBuffer.put(level.price, level);
            }
        } else {
            // Incremental update - coalesce by price
            TreeMap<Long, BufferedLevel> buffer = event.isUpdateIsBid() ? bidBuffer : askBuffer;
            long price = event.getUpdatePrice();

            BufferedLevel existing = buffer.get(price);
            if (existing != null) {
                // Update existing level
                existing.set(price, event.getUpdateQuantity(), event.getUpdateOrderCount());
            } else {
                // New level
                BufferedLevel level = levelPool.poll();
                if (level == null) level = new BufferedLevel();
                level.set(price, event.getUpdateQuantity(), event.getUpdateOrderCount());
                buffer.put(price, level);
            }

            // Remove levels with zero quantity
            if (event.getUpdateQuantity() == 0) {
                BufferedLevel removed = buffer.remove(price);
                if (removed != null) {
                    levelPool.push(removed);
                }
            }
        }

        hasBookUpdates = true;
        lastBookTimestamp = event.getTimestamp();
    }

    private void returnLevelsToPool(TreeMap<Long, BufferedLevel> buffer) {
        for (BufferedLevel level : buffer.values()) {
            levelPool.push(level);
        }
    }

    private synchronized void flushBuffers() {
        flushCount++;

        try {
            // Check if we have any subscribers (either via broadcaster or WebSocket)
            boolean hasSubscribers;
            if (broadcaster != null) {
                hasSubscribers = broadcaster.hasSubscribers();
            } else {
                ChannelGroup subs = subscriptionManager.getSubscribers(marketId);
                hasSubscribers = subs != null && !subs.isEmpty();
            }

            if (!hasSubscribers) {
                // No subscribers, clear buffers but don't serialize
                clearBuffersWithoutSending();
                return;
            }

            // Flush aggregated trades
            if (!tradesByPrice.isEmpty()) {
                String tradesJson = serializeAggregatedTrades();
                broadcastMessage(tradesJson);
                clearTradesBuffer();
            }

            // Flush buffered order status updates as batch
            if (!orderStatusBuffer.isEmpty()) {
                String statusJson = serializeOrderStatusBatch();
                broadcastMessage(statusJson);
                orderStatusBuffer.clear();
            }

            // Get fresh order book snapshot from matching engine (runs on this thread, not matching engine thread)
            if (matchingEngine != null) {
                String bookJson = serializeOrderBookFromEngine();
                if (bookJson != null) {
                    broadcastMessage(bookJson);
                }
            }
        } catch (Exception e) {
            // Log with FULL stack trace - this is critical for debugging
            flushErrorCount++;
            logger.error("FLUSH ERROR for market " + marketId + " (error #" + flushErrorCount + "): " + e.getMessage());
            e.printStackTrace();
            // Clear buffers to prevent memory leak
            clearBuffersWithoutSending();
        }
    }

    private void clearBuffersWithoutSending() {
        clearTradesBuffer();
        orderStatusBuffer.clear();
    }

    /**
     * Broadcast message using either broadcaster (cluster mode) or WebSocket (gateway mode).
     */
    private void broadcastMessage(String jsonMessage) {
        if (broadcaster != null) {
            // Cluster mode: send via Aeron egress broadcast
            broadcaster.broadcast(jsonMessage);
        } else {
            // Gateway mode: send directly to WebSocket subscribers
            ChannelGroup subscribers = subscriptionManager.getSubscribers(marketId);
            if (subscribers != null && !subscribers.isEmpty()) {
                subscribers.writeAndFlush(new TextWebSocketFrame(jsonMessage));
            }
        }
    }

    private void clearTradesBuffer() {
        // Return aggregated trades to pool
        Long2ObjectHashMap<AggregatedTrade>.ValueIterator iter = tradesByPrice.values().iterator();
        while (iter.hasNext()) {
            AggregatedTrade agg = iter.next();
            aggregatedTradePool.push(agg);
        }
        tradesByPrice.clear();
    }

    /**
     * Buffer order status for batched sending.
     * Reduces message count by bundling multiple status updates together.
     */
    private synchronized void bufferOrderStatus(PublishEvent event) {
        OrderStatusEntry entry = new OrderStatusEntry();
        entry.marketId = event.getMarketId();
        entry.market = marketName;
        entry.orderId = event.getOrderId();
        entry.userId = event.getUserId();
        entry.status = event.getOrderStatus();
        entry.price = event.getOrderPrice();
        entry.remainingQty = event.getRemainingQty();
        entry.filledQty = event.getFilledQty();
        entry.isBuy = event.isOrderIsBuy();
        entry.timestamp = event.getTimestamp();
        orderStatusBuffer.add(entry);
    }

    /**
     * Serialize aggregated trades - each entry represents all trades at a price level
     * within the flush interval. Much more compact than individual trades.
     * Includes order book version range for correlation with book snapshots.
     */
    private String serializeAggregatedTrades() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "TRADES_BATCH");
        json.addProperty("marketId", marketId);
        json.addProperty("market", marketName);
        json.addProperty("timestamp", System.currentTimeMillis());

        // Include book version range for UI correlation
        if (bookVersionMin != Long.MAX_VALUE && bookVersionMax != Long.MIN_VALUE) {
            json.addProperty("bookVersionMin", bookVersionMin);
            json.addProperty("bookVersionMax", bookVersionMax);
        }

        JsonArray trades = new JsonArray();
        Long2ObjectHashMap<AggregatedTrade>.ValueIterator iter = tradesByPrice.values().iterator();
        while (iter.hasNext()) {
            AggregatedTrade agg = iter.next();
            JsonObject t = new JsonObject();
            t.addProperty("price", FixedPoint.toDouble(agg.price));
            t.addProperty("quantity", FixedPoint.toDouble(agg.totalQuantity));
            t.addProperty("tradeCount", agg.tradeCount);
            t.addProperty("buyCount", agg.buyCount);
            t.addProperty("sellCount", agg.sellCount);
            t.addProperty("timestamp", agg.lastTimestamp);
            trades.add(t);
        }
        json.add("trades", trades);

        // Reset version range for next batch
        bookVersionMin = Long.MAX_VALUE;
        bookVersionMax = Long.MIN_VALUE;

        return json.toString();
    }

    /**
     * Get order book snapshot directly from matching engine.
     * Called on flush thread every 50ms - does NOT block matching engine.
     * Uses change detection to avoid sending duplicate snapshots.
     */
    private String serializeOrderBookFromEngine() {
        // Collect top 20 levels (this is a read-only operation on engine arrays)
        matchingEngine.collectTopLevels(MAX_BOOK_LEVELS);

        int bidCount = matchingEngine.getTopBidCount();
        int askCount = matchingEngine.getTopAskCount();

        // Skip when book is empty
        if (bidCount == 0 && askCount == 0) {
            return null;
        }

        long[] bidPrices = matchingEngine.getTopBidPrices();
        long[] bidQuantities = matchingEngine.getTopBidQuantities();
        int[] bidOrderCounts = matchingEngine.getTopBidOrderCounts();
        long[] askPrices = matchingEngine.getTopAskPrices();
        long[] askQuantities = matchingEngine.getTopAskQuantities();
        int[] askOrderCounts = matchingEngine.getTopAskOrderCounts();

        // Get versions that were captured at collection time (after memory barrier)
        long collectedBidVersion = matchingEngine.getCollectedBidVersion();
        long collectedAskVersion = matchingEngine.getCollectedAskVersion();

        // Change detection - check best bid/ask price, quantity, and count
        long currentBestBid = bidCount > 0 ? bidPrices[0] : -1;
        long currentBestAsk = askCount > 0 ? askPrices[0] : -1;
        long currentBidQty = bidCount > 0 ? bidQuantities[0] : 0;
        long currentAskQty = askCount > 0 ? askQuantities[0] : 0;
        int currentBidCount = bidCount > 0 ? bidOrderCounts[0] : 0;
        int currentAskCount = askCount > 0 ? askOrderCounts[0] : 0;

        boolean changed = (currentBestBid != lastBestBid) ||
                          (currentBestAsk != lastBestAsk) ||
                          (currentBidQty != lastBidQty) ||
                          (currentAskQty != lastAskQty) ||
                          (currentBidCount != lastBidCount) ||
                          (currentAskCount != lastAskCount);

        if (!changed) {
            return null;
        }

        // Update last values for next comparison
        lastBestBid = currentBestBid;
        lastBestAsk = currentBestAsk;
        lastBidQty = currentBidQty;
        lastAskQty = currentAskQty;
        lastBidCount = currentBidCount;
        lastAskCount = currentAskCount;

        JsonObject json = new JsonObject();
        json.addProperty("type", "BOOK_SNAPSHOT");
        json.addProperty("marketId", marketId);
        json.addProperty("market", marketName);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("bidVersion", collectedBidVersion);
        json.addProperty("askVersion", collectedAskVersion);
        json.addProperty("version", Math.max(collectedBidVersion, collectedAskVersion));

        // Add bid levels
        JsonArray bids = new JsonArray();
        for (int i = 0; i < bidCount; i++) {
            JsonObject l = new JsonObject();
            l.addProperty("price", FixedPoint.toDouble(bidPrices[i]));
            l.addProperty("quantity", FixedPoint.toDouble(bidQuantities[i]));
            l.addProperty("orderCount", bidOrderCounts[i]);
            bids.add(l);
        }
        json.add("bids", bids);

        // Add ask levels
        JsonArray asks = new JsonArray();
        for (int i = 0; i < askCount; i++) {
            JsonObject l = new JsonObject();
            l.addProperty("price", FixedPoint.toDouble(askPrices[i]));
            l.addProperty("quantity", FixedPoint.toDouble(askQuantities[i]));
            l.addProperty("orderCount", askOrderCounts[i]);
            asks.add(l);
        }
        json.add("asks", asks);

        return json.toString();
    }

    /**
     * Serialize buffered order status entries as a batch.
     * Reduces message count from N individual messages to 1 batch message.
     */
    private String serializeOrderStatusBatch() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "ORDER_STATUS_BATCH");
        json.addProperty("marketId", marketId);
        json.addProperty("market", marketName);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("count", orderStatusBuffer.size());

        JsonArray orders = new JsonArray();
        for (OrderStatusEntry entry : orderStatusBuffer) {
            JsonObject o = new JsonObject();
            o.addProperty("orderId", entry.orderId);
            o.addProperty("userId", entry.userId);
            o.addProperty("status", getStatusName(entry.status));
            o.addProperty("price", FixedPoint.toDouble(entry.price));
            o.addProperty("remainingQuantity", FixedPoint.toDouble(entry.remainingQty));
            o.addProperty("filledQuantity", FixedPoint.toDouble(entry.filledQty));
            o.addProperty("side", entry.isBuy ? "BID" : "ASK");
            o.addProperty("timestamp", entry.timestamp);
            orders.add(o);
        }
        json.add("orders", orders);

        return json.toString();
    }

    private String getStatusName(int status) {
        switch (status) {
            case OrderStatusType.NEW:
                return "NEW";
            case OrderStatusType.PARTIALLY_FILLED:
                return "PARTIALLY_FILLED";
            case OrderStatusType.FILLED:
                return "FILLED";
            case OrderStatusType.CANCELLED:
                return "CANCELLED";
            case OrderStatusType.REJECTED:
                return "REJECTED";
            default:
                return "UNKNOWN";
        }
    }
}
