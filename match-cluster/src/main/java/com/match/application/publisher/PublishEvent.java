package com.match.application.publisher;

/**
 * Pre-allocated event for LMAX Disruptor ring buffer.
 * ZERO allocations during publishing - all fields are primitive or pre-allocated arrays.
 *
 * Memory layout optimized for cache-line efficiency.
 * Single event class handles all event types to avoid polymorphism overhead.
 */
public class PublishEvent {

    // Event type (see PublishEventType constants)
    private int eventType;

    // Market identification
    private int marketId;

    // Timestamp (cluster timestamp in nanoseconds)
    private long timestamp;

    // === Trade Execution Fields ===
    private long tradeId;
    private long takerOrderId;
    private long takerUserId;
    private long makerOrderId;
    private long makerUserId;
    private long price;           // Fixed-point (8 decimals)
    private long quantity;        // Fixed-point (8 decimals)
    private boolean takerIsBuy;
    private long takerOmsOrderId; // OMS correlation ID for taker
    private long makerOmsOrderId; // OMS correlation ID for maker

    // === Order Book Update Fields ===
    // Top 10 levels each side (pre-allocated arrays)
    private final long[] bidPrices = new long[10];
    private final long[] bidQuantities = new long[10];
    private final int[] bidOrderCounts = new int[10];
    private int bidLevelCount;

    private final long[] askPrices = new long[10];
    private final long[] askQuantities = new long[10];
    private final int[] askOrderCounts = new int[10];
    private int askLevelCount;

    // Single level update (for incremental updates)
    private long updatePrice;
    private long updateQuantity;
    private int updateOrderCount;
    private boolean updateIsBid;
    private boolean isSnapshot; // true = full snapshot, false = incremental

    // === Order Status Update Fields ===
    private long orderId;
    private long userId;
    private int orderStatus;      // See OrderStatusType constants
    private long remainingQty;    // Fixed-point
    private long filledQty;       // Fixed-point
    private long orderPrice;      // Original order price
    private boolean orderIsBuy;
    private long omsOrderId;      // OMS correlation ID

    // Padding to avoid false sharing (occupy full cache line)
    @SuppressWarnings("unused")
    private long p1, p2, p3, p4;

    /**
     * Reset all fields for reuse.
     * Called automatically by Disruptor before reusing event slot.
     */
    public void clear() {
        eventType = 0;
        marketId = 0;
        timestamp = 0;
        tradeId = 0;
        takerOrderId = 0;
        takerUserId = 0;
        makerOrderId = 0;
        makerUserId = 0;
        price = 0;
        quantity = 0;
        takerIsBuy = false;
        takerOmsOrderId = 0;
        makerOmsOrderId = 0;
        bidLevelCount = 0;
        askLevelCount = 0;
        updatePrice = 0;
        updateQuantity = 0;
        updateOrderCount = 0;
        updateIsBid = false;
        isSnapshot = false;
        orderId = 0;
        userId = 0;
        orderStatus = 0;
        remainingQty = 0;
        filledQty = 0;
        orderPrice = 0;
        orderIsBuy = false;
        omsOrderId = 0;
    }

    // === Setters for Trade Execution ===

    /**
     * Set trade execution event data.
     * ZERO allocations.
     */
    public void setTradeExecution(
            int marketId,
            long timestamp,
            long tradeId,
            long takerOrderId,
            long takerUserId,
            long makerOrderId,
            long makerUserId,
            long price,
            long quantity,
            boolean takerIsBuy,
            long takerOmsOrderId,
            long makerOmsOrderId) {
        this.eventType = PublishEventType.TRADE_EXECUTION;
        this.marketId = marketId;
        this.timestamp = timestamp;
        this.tradeId = tradeId;
        this.takerOrderId = takerOrderId;
        this.takerUserId = takerUserId;
        this.makerOrderId = makerOrderId;
        this.makerUserId = makerUserId;
        this.price = price;
        this.quantity = quantity;
        this.takerIsBuy = takerIsBuy;
        this.takerOmsOrderId = takerOmsOrderId;
        this.makerOmsOrderId = makerOmsOrderId;
    }

    // === Setters for Order Book Update (Incremental) ===

    /**
     * Set incremental order book level update.
     * ZERO allocations.
     */
    public void setOrderBookLevelUpdate(
            int marketId,
            long timestamp,
            long price,
            long quantity,
            int orderCount,
            boolean isBid) {
        this.eventType = PublishEventType.ORDER_BOOK_UPDATE;
        this.marketId = marketId;
        this.timestamp = timestamp;
        this.updatePrice = price;
        this.updateQuantity = quantity;
        this.updateOrderCount = orderCount;
        this.updateIsBid = isBid;
        this.isSnapshot = false;
    }

    /**
     * Set order book snapshot (top N levels).
     * Arrays are copied in-place, ZERO allocations.
     */
    public void setOrderBookSnapshot(
            int marketId,
            long timestamp,
            long[] bidPrices, long[] bidQuantities, int[] bidOrderCounts, int bidCount,
            long[] askPrices, long[] askQuantities, int[] askOrderCounts, int askCount) {
        this.eventType = PublishEventType.ORDER_BOOK_UPDATE;
        this.marketId = marketId;
        this.timestamp = timestamp;
        this.isSnapshot = true;

        // Copy bid levels
        this.bidLevelCount = Math.min(bidCount, 10);
        System.arraycopy(bidPrices, 0, this.bidPrices, 0, this.bidLevelCount);
        System.arraycopy(bidQuantities, 0, this.bidQuantities, 0, this.bidLevelCount);
        System.arraycopy(bidOrderCounts, 0, this.bidOrderCounts, 0, this.bidLevelCount);

        // Copy ask levels
        this.askLevelCount = Math.min(askCount, 10);
        System.arraycopy(askPrices, 0, this.askPrices, 0, this.askLevelCount);
        System.arraycopy(askQuantities, 0, this.askQuantities, 0, this.askLevelCount);
        System.arraycopy(askOrderCounts, 0, this.askOrderCounts, 0, this.askLevelCount);
    }

    // === Setters for Order Status Update ===

    /**
     * Set order status update event data.
     * ZERO allocations.
     */
    public void setOrderStatusUpdate(
            int marketId,
            long timestamp,
            long orderId,
            long userId,
            int orderStatus,
            long remainingQty,
            long filledQty,
            long orderPrice,
            boolean orderIsBuy,
            long omsOrderId) {
        this.eventType = PublishEventType.ORDER_STATUS_UPDATE;
        this.marketId = marketId;
        this.timestamp = timestamp;
        this.orderId = orderId;
        this.userId = userId;
        this.orderStatus = orderStatus;
        this.remainingQty = remainingQty;
        this.filledQty = filledQty;
        this.orderPrice = orderPrice;
        this.orderIsBuy = orderIsBuy;
        this.omsOrderId = omsOrderId;
    }

    // === Getters (all inline for JIT optimization) ===

    public int getEventType() { return eventType; }
    public int getMarketId() { return marketId; }
    public long getTimestamp() { return timestamp; }

    // Trade execution getters
    public long getTradeId() { return tradeId; }
    public long getTakerOrderId() { return takerOrderId; }
    public long getTakerUserId() { return takerUserId; }
    public long getMakerOrderId() { return makerOrderId; }
    public long getMakerUserId() { return makerUserId; }
    public long getPrice() { return price; }
    public long getQuantity() { return quantity; }
    public boolean isTakerIsBuy() { return takerIsBuy; }
    public long getTakerOmsOrderId() { return takerOmsOrderId; }
    public long getMakerOmsOrderId() { return makerOmsOrderId; }

    // Order book update getters
    public boolean isSnapshot() { return isSnapshot; }
    public long getUpdatePrice() { return updatePrice; }
    public long getUpdateQuantity() { return updateQuantity; }
    public int getUpdateOrderCount() { return updateOrderCount; }
    public boolean isUpdateIsBid() { return updateIsBid; }

    public int getBidLevelCount() { return bidLevelCount; }
    public long getBidPrice(int level) { return bidPrices[level]; }
    public long getBidQuantity(int level) { return bidQuantities[level]; }
    public int getBidOrderCount(int level) { return bidOrderCounts[level]; }

    public int getAskLevelCount() { return askLevelCount; }
    public long getAskPrice(int level) { return askPrices[level]; }
    public long getAskQuantity(int level) { return askQuantities[level]; }
    public int getAskOrderCount(int level) { return askOrderCounts[level]; }

    // Order status getters
    public long getOrderId() { return orderId; }
    public long getUserId() { return userId; }
    public int getOrderStatus() { return orderStatus; }
    public long getRemainingQty() { return remainingQty; }
    public long getFilledQty() { return filledQty; }
    public long getOrderPrice() { return orderPrice; }
    public boolean isOrderIsBuy() { return orderIsBuy; }
    public long getOmsOrderId() { return omsOrderId; }
}
