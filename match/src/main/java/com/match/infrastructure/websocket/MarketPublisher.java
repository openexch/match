package com.match.infrastructure.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    // Change detection - avoid sending duplicate snapshots
    private long lastBestBid = -1;
    private long lastBestAsk = -1;
    private long lastBidQty = -1;
    private long lastAskQty = -1;
    private int lastBidCount = -1;
    private int lastAskCount = -1;

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

        logger.info("MarketPublisher started for market " + marketId + " (" + marketName + ") with " + FLUSH_INTERVAL_MS + "ms buffering");
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

        logger.info("MarketPublisher stopped for market " + marketId);
    }

    @Override
    public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) throws Exception {
        int eventType = event.getEventType();
        if (eventType == PublishEventType.TRADE_EXECUTION) {
            // Buffer trades - order book is fetched directly from engine at 50ms intervals
            bufferTrade(event);
        } else if (eventType == PublishEventType.ORDER_STATUS_UPDATE) {
            // Send order status updates immediately (not buffered)
            sendOrderStatus(event);
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
        try {
            ChannelGroup subscribers = subscriptionManager.getSubscribers(marketId);
            if (subscribers == null || subscribers.isEmpty()) {
                // No subscribers, clear buffers but don't serialize
                clearBuffersWithoutSending();
                return;
            }

            // Flush aggregated trades
            if (!tradesByPrice.isEmpty()) {
                String tradesJson = serializeAggregatedTrades();
                subscribers.writeAndFlush(new TextWebSocketFrame(tradesJson));
                clearTradesBuffer();
            }

            // Get fresh order book snapshot from matching engine (runs on this thread, not matching engine thread)
            if (matchingEngine != null) {
                String bookJson = serializeOrderBookFromEngine();
                if (bookJson != null) {
                    // Log message size periodically
                    if (System.currentTimeMillis() % 5000 < 100) {
                        logger.info("Sending book snapshot: " + bookJson.length() + " bytes to " + subscribers.size() + " subscribers");
                    }
                    subscribers.writeAndFlush(new TextWebSocketFrame(bookJson));
                }
            }
        } catch (Exception e) {
            // Log but don't rethrow - this would kill the scheduler
            logger.warn("Error flushing buffers for market " + marketId + ": " + e.getMessage());
            e.printStackTrace();
            // Clear buffers to prevent memory leak
            clearBuffersWithoutSending();
        }
    }

    private void clearBuffersWithoutSending() {
        clearTradesBuffer();
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

    private void sendOrderStatus(PublishEvent event) {
        ChannelGroup subscribers = subscriptionManager.getSubscribers(marketId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        String json = serializeOrderStatus(event);
        subscribers.writeAndFlush(new TextWebSocketFrame(json));
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

        // Log when book is empty (for debugging)
        if (bidCount == 0 && askCount == 0) {
            // Log periodically when book is empty
            if (System.currentTimeMillis() % 5000 < 100) {
                logger.info("Order book is empty - no levels to send");
            }
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

        // Log book state periodically for debugging - include actual price data
        if (System.currentTimeMillis() % 2000 < 100) {
            long bestBid = bidCount > 0 ? bidPrices[0] : 0;
            long bestAsk = askCount > 0 ? askPrices[0] : 0;
            logger.info("Book: " + bidCount + " bids, " + askCount + " asks, bestBid=" +
                FixedPoint.toDouble(bestBid) + ", bestAsk=" + FixedPoint.toDouble(bestAsk) +
                ", bidVer=" + collectedBidVersion + ", askVer=" + collectedAskVersion);
        }

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

    private String serializeOrderStatus(PublishEvent event) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "ORDER_STATUS");
        json.addProperty("marketId", event.getMarketId());
        json.addProperty("market", marketName);
        json.addProperty("orderId", event.getOrderId());
        json.addProperty("userId", event.getUserId());
        json.addProperty("status", getStatusName(event.getOrderStatus()));
        json.addProperty("price", FixedPoint.toDouble(event.getOrderPrice()));
        json.addProperty("remainingQuantity", FixedPoint.toDouble(event.getRemainingQty()));
        json.addProperty("filledQuantity", FixedPoint.toDouble(event.getFilledQty()));
        json.addProperty("side", event.isOrderIsBuy() ? "BID" : "ASK");
        json.addProperty("timestamp", event.getTimestamp());
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
