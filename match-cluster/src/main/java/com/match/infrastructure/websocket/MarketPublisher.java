package com.match.infrastructure.websocket;

import com.match.application.orderbook.DirectMatchingEngine;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.OrderStatusType;
import com.match.application.publisher.PublishEvent;
import com.match.application.publisher.PublishEventType;
import com.match.infrastructure.Logger;
import com.match.infrastructure.generated.*;
import com.match.infrastructure.generated.BookDeltaEncoder;

import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-market publisher that handles Disruptor events and broadcasts via Aeron egress.
 * Runs on dedicated thread per market (created by Disruptor).
 *
 * Uses 50ms buffering to batch trades and coalesce order book updates.
 * Encodes events to SBE binary format for zero-allocation egress.
 */
public class MarketPublisher implements MarketEventHandler {

    private static final Logger logger = Logger.getLogger(MarketPublisher.class);
    private static final long FLUSH_INTERVAL_MS = 20;
    private static final int MAX_BUFFERED_TRADES = 100;
    private static final int MAX_BOOK_LEVELS = 20;

    // SBE encoding buffer (256KB for large batches under load)
    private static final int ENCODE_BUFFER_SIZE = 256 * 1024;
    // Max order status entries per batch to prevent buffer overflow (~60 bytes each)
    private static final int MAX_ORDER_STATUS_PER_BATCH = 2000;
    private final ExpandableDirectByteBuffer encodeBuffer = new ExpandableDirectByteBuffer(ENCODE_BUFFER_SIZE);

    // Pre-allocated SBE encoders (reused, zero allocation)
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final BookSnapshotEncoder bookSnapshotEncoder = new BookSnapshotEncoder();
    private final BookDeltaEncoder bookDeltaEncoder = new BookDeltaEncoder();
    private final TradesBatchEncoder tradesBatchEncoder = new TradesBatchEncoder();
    private final OrderStatusBatchEncoder orderStatusBatchEncoder = new OrderStatusBatchEncoder();

    private final int marketId;
    private final String marketName;
    private final SubscriptionManager subscriptionManager;

    // Broadcaster for cluster mode (Aeron egress)
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

    // Change detection - use engine version numbers to detect any book change
    private long lastBidVersion = -1;
    private long lastAskVersion = -1;

    // Delta tracking - last sent book state for computing incremental changes
    private final long[] lastBidPrices = new long[MAX_BOOK_LEVELS];
    private final long[] lastBidQuantities = new long[MAX_BOOK_LEVELS];
    private final int[] lastBidOrderCounts = new int[MAX_BOOK_LEVELS];
    private final long[] lastAskPrices = new long[MAX_BOOK_LEVELS];
    private final long[] lastAskQuantities = new long[MAX_BOOK_LEVELS];
    private final int[] lastAskOrderCounts = new int[MAX_BOOK_LEVELS];
    private int lastBidCount = 0;
    private int lastAskCount = 0;
    private boolean sentInitialSnapshot = false;

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
            // Capture local references to volatile fields for null-safety
            final MarketDataBroadcaster localBroadcaster = broadcaster;
            final DirectMatchingEngine localEngine = matchingEngine;

            // Must have broadcaster configured (gateway caches state regardless of WebSocket clients)
            if (localBroadcaster == null) {
                clearBuffersWithoutSending();
                return;
            }

            // Flush aggregated trades (SBE encoded)
            if (!tradesByPrice.isEmpty()) {
                int length = encodeTradesBatch();
                if (length > 0) {
                    localBroadcaster.broadcast(encodeBuffer, 0, length);
                }
                clearTradesBuffer();
            }

            // Flush buffered order status updates as batch (SBE encoded)
            // Send in chunks if buffer is large to prevent overflow
            while (!orderStatusBuffer.isEmpty()) {
                int batchSize = Math.min(orderStatusBuffer.size(), MAX_ORDER_STATUS_PER_BATCH);
                int length = encodeOrderStatusBatch(batchSize);
                if (length > 0) {
                    localBroadcaster.broadcast(encodeBuffer, 0, length);
                }
                // Remove encoded entries
                if (batchSize >= orderStatusBuffer.size()) {
                    orderStatusBuffer.clear();
                } else {
                    orderStatusBuffer.subList(0, batchSize).clear();
                }
            }

            // Get fresh order book snapshot from matching engine (SBE encoded)
            if (localEngine != null) {
                int length = encodeBookSnapshot(localEngine);
                if (length > 0) {
                    localBroadcaster.broadcast(encodeBuffer, 0, length);
                }
            }
        } catch (Exception e) {
            flushErrorCount++;
            logger.error("FLUSH ERROR for market " + marketId + " (error #" + flushErrorCount + "): " + e.getMessage());
            e.printStackTrace();
            clearBuffersWithoutSending();
        }
    }

    private void clearBuffersWithoutSending() {
        clearTradesBuffer();
        orderStatusBuffer.clear();
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
     * Encode aggregated trades to SBE binary format.
     * Returns the total encoded length, or 0 if nothing to encode.
     */
    private int encodeTradesBatch() {
        int tradeCount = tradesByPrice.size();
        if (tradeCount == 0) {
            return 0;
        }

        // Encode header
        headerEncoder.wrap(encodeBuffer, 0)
            .blockLength(TradesBatchEncoder.BLOCK_LENGTH)
            .templateId(TradesBatchEncoder.TEMPLATE_ID)
            .schemaId(TradesBatchEncoder.SCHEMA_ID)
            .version(TradesBatchEncoder.SCHEMA_VERSION);

        // Encode message body
        tradesBatchEncoder.wrap(encodeBuffer, MessageHeaderEncoder.ENCODED_LENGTH)
            .marketId(marketId)
            .timestamp(System.currentTimeMillis());

        // Encode trades group
        TradesBatchEncoder.TradesEncoder tradesGroup = tradesBatchEncoder.tradesCount(tradeCount);
        Long2ObjectHashMap<AggregatedTrade>.ValueIterator iter = tradesByPrice.values().iterator();
        while (iter.hasNext()) {
            AggregatedTrade agg = iter.next();
            tradesGroup.next()
                .price(agg.price)
                .quantity(agg.totalQuantity)
                .tradeCount(agg.tradeCount)
                .timestamp(agg.lastTimestamp);
        }

        // Reset version range for next batch
        bookVersionMin = Long.MAX_VALUE;
        bookVersionMax = Long.MIN_VALUE;

        return MessageHeaderEncoder.ENCODED_LENGTH + tradesBatchEncoder.encodedLength();
    }

    /**
     * Encode order book update to SBE binary format.
     * Sends full snapshot on first call, then delta updates.
     * Called on flush thread every 50ms - does NOT block matching engine.
     * @param engine The captured matching engine reference (null-safe)
     * @return Encoded length, or 0 if no change
     */
    private int encodeBookSnapshot(DirectMatchingEngine engine) {
        // Collect top 20 levels (this is a read-only operation on engine arrays)
        engine.collectTopLevels(MAX_BOOK_LEVELS);

        int bidCount = engine.getTopBidCount();
        int askCount = engine.getTopAskCount();

        // Skip when book is empty
        if (bidCount == 0 && askCount == 0) {
            return 0;
        }

        long[] bidPrices = engine.getTopBidPrices();
        long[] bidQuantities = engine.getTopBidQuantities();
        int[] bidOrderCounts = engine.getTopBidOrderCounts();
        long[] askPrices = engine.getTopAskPrices();
        long[] askQuantities = engine.getTopAskQuantities();
        int[] askOrderCounts = engine.getTopAskOrderCounts();

        // Get versions that were captured at collection time (after memory barrier)
        long collectedBidVersion = engine.getCollectedBidVersion();
        long collectedAskVersion = engine.getCollectedAskVersion();

        boolean changed = (collectedBidVersion != lastBidVersion) ||
                          (collectedAskVersion != lastAskVersion);

        if (!changed) {
            return 0;
        }

        // Update last versions for next comparison
        lastBidVersion = collectedBidVersion;
        lastAskVersion = collectedAskVersion;

        int encodedLength;

        // First message must be full snapshot, then send deltas
        if (!sentInitialSnapshot) {
            encodedLength = encodeFullSnapshot(bidPrices, bidQuantities, bidOrderCounts, bidCount,
                                               askPrices, askQuantities, askOrderCounts, askCount,
                                               collectedBidVersion, collectedAskVersion);
            sentInitialSnapshot = true;
        } else {
            encodedLength = encodeBookDelta(bidPrices, bidQuantities, bidOrderCounts, bidCount,
                                            askPrices, askQuantities, askOrderCounts, askCount,
                                            collectedBidVersion, collectedAskVersion);
        }

        // Store current state as last sent
        System.arraycopy(bidPrices, 0, lastBidPrices, 0, bidCount);
        System.arraycopy(bidQuantities, 0, lastBidQuantities, 0, bidCount);
        System.arraycopy(bidOrderCounts, 0, lastBidOrderCounts, 0, bidCount);
        lastBidCount = bidCount;

        System.arraycopy(askPrices, 0, lastAskPrices, 0, askCount);
        System.arraycopy(askQuantities, 0, lastAskQuantities, 0, askCount);
        System.arraycopy(askOrderCounts, 0, lastAskOrderCounts, 0, askCount);
        lastAskCount = askCount;

        return encodedLength;
    }

    /**
     * Encode full order book snapshot.
     */
    private int encodeFullSnapshot(long[] bidPrices, long[] bidQuantities, int[] bidOrderCounts, int bidCount,
                                   long[] askPrices, long[] askQuantities, int[] askOrderCounts, int askCount,
                                   long bidVersion, long askVersion) {
        // Encode header
        headerEncoder.wrap(encodeBuffer, 0)
            .blockLength(BookSnapshotEncoder.BLOCK_LENGTH)
            .templateId(BookSnapshotEncoder.TEMPLATE_ID)
            .schemaId(BookSnapshotEncoder.SCHEMA_ID)
            .version(BookSnapshotEncoder.SCHEMA_VERSION);

        // Encode message body
        bookSnapshotEncoder.wrap(encodeBuffer, MessageHeaderEncoder.ENCODED_LENGTH)
            .marketId(marketId)
            .timestamp(System.currentTimeMillis())
            .bidVersion(bidVersion)
            .askVersion(askVersion);

        // Encode bids group
        BookSnapshotEncoder.BidsEncoder bidsGroup = bookSnapshotEncoder.bidsCount(bidCount);
        for (int i = 0; i < bidCount; i++) {
            bidsGroup.next()
                .price(bidPrices[i])
                .quantity(bidQuantities[i])
                .orderCount(bidOrderCounts[i]);
        }

        // Encode asks group
        BookSnapshotEncoder.AsksEncoder asksGroup = bookSnapshotEncoder.asksCount(askCount);
        for (int i = 0; i < askCount; i++) {
            asksGroup.next()
                .price(askPrices[i])
                .quantity(askQuantities[i])
                .orderCount(askOrderCounts[i]);
        }

        return MessageHeaderEncoder.ENCODED_LENGTH + bookSnapshotEncoder.encodedLength();
    }

    // Reusable list for delta changes (avoid allocations)
    private final java.util.List<LevelChange> deltaChanges = new java.util.ArrayList<>(MAX_BOOK_LEVELS * 2);

    // Simple struct for level changes
    private static class LevelChange {
        long price;
        long quantity;
        int orderCount;
        boolean isBid;
        int updateType; // 0=NEW, 1=UPDATE, 2=DELETE

        void set(long price, long quantity, int orderCount, boolean isBid, int updateType) {
            this.price = price;
            this.quantity = quantity;
            this.orderCount = orderCount;
            this.isBid = isBid;
            this.updateType = updateType;
        }
    }

    // Pre-allocated change objects
    private final LevelChange[] changePool = new LevelChange[MAX_BOOK_LEVELS * 4];
    {
        for (int i = 0; i < changePool.length; i++) {
            changePool[i] = new LevelChange();
        }
    }

    /**
     * Encode incremental book delta - only levels that changed.
     * Compares current state with last sent state and encodes differences.
     */
    private int encodeBookDelta(long[] bidPrices, long[] bidQuantities, int[] bidOrderCounts, int bidCount,
                                long[] askPrices, long[] askQuantities, int[] askOrderCounts, int askCount,
                                long bidVersion, long askVersion) {
        deltaChanges.clear();
        int poolIdx = 0;

        // Compute bid changes
        poolIdx = computeLevelChanges(
            lastBidPrices, lastBidQuantities, lastBidOrderCounts, lastBidCount,
            bidPrices, bidQuantities, bidOrderCounts, bidCount, true, poolIdx);

        // Compute ask changes
        poolIdx = computeLevelChanges(
            lastAskPrices, lastAskQuantities, lastAskOrderCounts, lastAskCount,
            askPrices, askQuantities, askOrderCounts, askCount, false, poolIdx);

        if (deltaChanges.isEmpty()) {
            return 0;
        }

        // Encode header
        headerEncoder.wrap(encodeBuffer, 0)
            .blockLength(BookDeltaEncoder.BLOCK_LENGTH)
            .templateId(BookDeltaEncoder.TEMPLATE_ID)
            .schemaId(BookDeltaEncoder.SCHEMA_ID)
            .version(BookDeltaEncoder.SCHEMA_VERSION);

        // Encode message body
        bookDeltaEncoder.wrap(encodeBuffer, MessageHeaderEncoder.ENCODED_LENGTH)
            .marketId(marketId)
            .timestamp(System.currentTimeMillis())
            .bidVersion(bidVersion)
            .askVersion(askVersion);

        // Encode changes group
        BookDeltaEncoder.ChangesEncoder changesGroup = bookDeltaEncoder.changesCount(deltaChanges.size());

        for (LevelChange change : deltaChanges) {
            changesGroup.next()
                .price(change.price)
                .quantity(change.quantity)
                .orderCount(change.orderCount)
                .side(change.isBid ? OrderSide.BID : OrderSide.ASK)
                .updateType(mapUpdateType(change.updateType));
        }

        return MessageHeaderEncoder.ENCODED_LENGTH + bookDeltaEncoder.encodedLength();
    }

    /**
     * Compute level changes between old and new book state.
     * Adds changes to deltaChanges list using pooled objects.
     * @return next available pool index
     */
    private int computeLevelChanges(long[] oldPrices, long[] oldQtys, int[] oldCounts, int oldLen,
                                    long[] newPrices, long[] newQtys, int[] newCounts, int newLen,
                                    boolean isBid, int poolIdx) {
        // Build price set for old levels
        java.util.Set<Long> oldPriceSet = new java.util.HashSet<>();
        for (int i = 0; i < oldLen; i++) {
            oldPriceSet.add(oldPrices[i]);
        }

        // Check new levels for additions and updates
        for (int i = 0; i < newLen; i++) {
            long price = newPrices[i];
            long qty = newQtys[i];
            int count = newCounts[i];

            // Find matching old level
            int oldIdx = -1;
            for (int j = 0; j < oldLen; j++) {
                if (oldPrices[j] == price) {
                    oldIdx = j;
                    break;
                }
            }

            if (oldIdx == -1) {
                // New level
                LevelChange change = changePool[poolIdx++];
                change.set(price, qty, count, isBid, 0); // NEW
                deltaChanges.add(change);
                oldPriceSet.remove(price);
            } else if (oldQtys[oldIdx] != qty || oldCounts[oldIdx] != count) {
                // Updated level
                LevelChange change = changePool[poolIdx++];
                change.set(price, qty, count, isBid, 1); // UPDATE
                deltaChanges.add(change);
                oldPriceSet.remove(price);
            } else {
                // Unchanged
                oldPriceSet.remove(price);
            }
        }

        // Remaining old prices are deletions
        for (Long price : oldPriceSet) {
            LevelChange change = changePool[poolIdx++];
            change.set(price, 0, 0, isBid, 2); // DELETE
            deltaChanges.add(change);
        }

        return poolIdx;
    }

    private BookUpdateType mapUpdateType(int type) {
        switch (type) {
            case 0: return BookUpdateType.NEW_LEVEL;
            case 1: return BookUpdateType.UPDATE_LEVEL;
            case 2: return BookUpdateType.DELETE_LEVEL;
            default: return BookUpdateType.UPDATE_LEVEL;
        }
    }

    /**
     * Encode buffered order status entries as a batch to SBE binary format.
     * @param batchSize Number of entries to encode from the front of the buffer
     * @return Encoded length, or 0 if nothing to encode
     */
    private int encodeOrderStatusBatch(int batchSize) {
        if (batchSize == 0 || orderStatusBuffer.isEmpty()) {
            return 0;
        }

        // Encode header
        headerEncoder.wrap(encodeBuffer, 0)
            .blockLength(OrderStatusBatchEncoder.BLOCK_LENGTH)
            .templateId(OrderStatusBatchEncoder.TEMPLATE_ID)
            .schemaId(OrderStatusBatchEncoder.SCHEMA_ID)
            .version(OrderStatusBatchEncoder.SCHEMA_VERSION);

        // Encode message body
        orderStatusBatchEncoder.wrap(encodeBuffer, MessageHeaderEncoder.ENCODED_LENGTH)
            .marketId(marketId)
            .timestamp(System.currentTimeMillis());

        // Encode orders group (only up to batchSize)
        OrderStatusBatchEncoder.OrdersEncoder ordersGroup = orderStatusBatchEncoder.ordersCount(batchSize);
        for (int i = 0; i < batchSize; i++) {
            OrderStatusEntry entry = orderStatusBuffer.get(i);
            ordersGroup.next()
                .orderId(entry.orderId)
                .userId(entry.userId)
                .status(mapOrderStatus(entry.status))
                .price(entry.price)
                .remainingQty(entry.remainingQty)
                .filledQty(entry.filledQty)
                .side(entry.isBuy ? OrderSide.BID : OrderSide.ASK)
                .timestamp(entry.timestamp);
        }

        return MessageHeaderEncoder.ENCODED_LENGTH + orderStatusBatchEncoder.encodedLength();
    }

    /**
     * Map internal order status int to SBE OrderStatus enum.
     */
    private OrderStatus mapOrderStatus(int status) {
        switch (status) {
            case OrderStatusType.NEW:
                return OrderStatus.NEW;
            case OrderStatusType.PARTIALLY_FILLED:
                return OrderStatus.PARTIALLY_FILLED;
            case OrderStatusType.FILLED:
                return OrderStatus.FILLED;
            case OrderStatusType.CANCELLED:
                return OrderStatus.CANCELLED;
            case OrderStatusType.REJECTED:
                return OrderStatus.REJECTED;
            default:
                return OrderStatus.NEW;
        }
    }
}
