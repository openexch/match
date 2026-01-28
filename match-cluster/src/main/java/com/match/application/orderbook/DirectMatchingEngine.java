package com.match.application.orderbook;

import com.match.domain.FixedPoint;

/**
 * Ultra-low latency matching engine using direct array access.
 * ZERO allocations in hot path - all results written to pre-allocated arrays.
 *
 * Achieves sub-microsecond matching for typical orders.
 */
public class DirectMatchingEngine {

    // Match result storage - pre-allocated
    private static final int MAX_MATCHES_PER_ORDER = 10_000;
    private static final int MATCH_FIELDS = 4; // makerOrderId, makerUserId, price, quantity

    // Match data: [makerOrderId, makerUserId, price, quantity] per match
    private final long[] matchResults;
    private int matchCount;

    // Taker state
    private long takerRemainingQty;
    private long takerRemainingBudget; // For market buy orders

    // Order books (bid and ask sides)
    private final DirectIndexOrderBook askBook;
    private final DirectIndexOrderBook bidBook;

    /**
     * Create matching engine for a price range.
     *
     * @param basePrice Minimum price (fixed-point)
     * @param maxPrice  Maximum price (fixed-point)
     * @param tickSize  Price increment (fixed-point)
     */
    public DirectMatchingEngine(long basePrice, long maxPrice, long tickSize) {
        this.askBook = new DirectIndexOrderBook(basePrice, maxPrice, tickSize, true);  // ascending
        this.bidBook = new DirectIndexOrderBook(basePrice, maxPrice, tickSize, false); // descending
        this.matchResults = new long[MAX_MATCHES_PER_ORDER * MATCH_FIELDS]; // 4 fields per match
    }

    /**
     * Process a limit order. Returns number of matches.
     * ZERO allocations.
     *
     * @param orderId    Order ID
     * @param userId     User ID
     * @param isBuy      true for buy, false for sell
     * @param price      Limit price (fixed-point)
     * @param quantity   Order quantity (fixed-point)
     * @return Number of matches (access via getMatch* methods)
     */
    public int processLimitOrder(long orderId, long userId, boolean isBuy, long price, long quantity) {
        matchCount = 0;
        takerRemainingQty = quantity;

        DirectIndexOrderBook makerBook = isBuy ? askBook : bidBook;
        DirectIndexOrderBook takerBook = isBuy ? bidBook : askBook;

        // Match against opposing book
        if (!makerBook.isEmpty()) {
            int priceIdx = makerBook.getBestPriceIndex();

            while (priceIdx >= 0 && takerRemainingQty > 0) {
                long levelPrice = makerBook.getBasePrice() +
                    (long) priceIdx * makerBook.getTickSize();

                // Check price compatibility
                if (isBuy && levelPrice > price) break;
                if (!isBuy && levelPrice < price) break;

                // Match at this level
                matchAtLevel(makerBook, priceIdx, levelPrice);

                // Move to next level
                priceIdx = makerBook.nextPriceIndex(priceIdx);
            }
        }

        if (takerRemainingQty > 0) {
            takerBook.addOrder(orderId, userId, price, takerRemainingQty);
        }

        return matchCount;
    }

    /**
     * Process a market order. Returns number of matches.
     * ZERO allocations.
     *
     * @param orderId    Order ID
     * @param userId     User ID
     * @param isBuy      true for buy (uses budget), false for sell (uses quantity)
     * @param quantity   Quantity for sell, ignored for buy
     * @param budget     Budget for buy (fixed-point), ignored for sell
     * @return Number of matches
     */
    public int processMarketOrder(long orderId, long userId, boolean isBuy, long quantity, long budget) {
        matchCount = 0;

        DirectIndexOrderBook makerBook = isBuy ? askBook : bidBook;

        if (makerBook.isEmpty()) {
            return 0;
        }

        if (isBuy) {
            // Market buy - spend budget
            takerRemainingBudget = budget;
            int priceIdx = makerBook.getBestPriceIndex();

            while (priceIdx >= 0 && takerRemainingBudget > 0) {
                long levelPrice = makerBook.getBasePrice() +
                    (long) priceIdx * makerBook.getTickSize();

                matchMarketBuyAtLevel(makerBook, priceIdx, levelPrice);
                priceIdx = makerBook.nextPriceIndex(priceIdx);
            }
        } else {
            // Market sell - sell quantity
            takerRemainingQty = quantity;
            int priceIdx = makerBook.getBestPriceIndex();

            while (priceIdx >= 0 && takerRemainingQty > 0) {
                long levelPrice = makerBook.getBasePrice() +
                    (long) priceIdx * makerBook.getTickSize();

                matchAtLevel(makerBook, priceIdx, levelPrice);
                priceIdx = makerBook.nextPriceIndex(priceIdx);
            }
        }

        return matchCount;
    }

    /**
     * Match taker against all orders at a price level
     */
    private void matchAtLevel(DirectIndexOrderBook book, int priceIdx, long price) {
        while (takerRemainingQty > 0 && book.getOrderCount(priceIdx) > 0) {
            // Stop matching if limit reached to prevent unbounded execution
            if (matchCount >= MAX_MATCHES_PER_ORDER) {
                System.err.println("WARNING: Match limit reached (" + MAX_MATCHES_PER_ORDER
                    + ") for order. Remaining qty: " + takerRemainingQty);
                return;
            }

            long makerOrderId = book.getHeadOrderId(priceIdx);
            if (makerOrderId < 0) break;

            long makerUserId = book.getHeadOrderUserId(priceIdx);
            long makerQty = book.getHeadOrderQuantity(priceIdx);
            long matchQty = Math.min(takerRemainingQty, makerQty);

            if (matchQty <= 0) break;

            // Record match
            int idx = matchCount * MATCH_FIELDS;
            matchResults[idx] = makerOrderId;
            matchResults[idx + 1] = makerUserId;
            matchResults[idx + 2] = price;
            matchResults[idx + 3] = matchQty;
            matchCount++;

            // Update quantities
            takerRemainingQty -= matchQty;
            book.reduceOrderQuantity(makerOrderId, matchQty);
        }
    }

    /**
     * Match market buy at a price level (budget-based)
     */
    private void matchMarketBuyAtLevel(DirectIndexOrderBook book, int priceIdx, long price) {
        while (takerRemainingBudget > 0 && book.getOrderCount(priceIdx) > 0) {
            // Stop matching if limit reached to prevent unbounded execution
            if (matchCount >= MAX_MATCHES_PER_ORDER) {
                System.err.println("WARNING: Match limit reached (" + MAX_MATCHES_PER_ORDER
                    + ") for market buy order. Remaining budget: " + takerRemainingBudget);
                return;
            }

            long makerOrderId = book.getHeadOrderId(priceIdx);
            if (makerOrderId < 0) break;

            long makerUserId = book.getHeadOrderUserId(priceIdx);
            long makerQty = book.getHeadOrderQuantity(priceIdx);

            // Calculate max we can buy with remaining budget
            long maxBuyQty = FixedPoint.divide(takerRemainingBudget, price);
            long matchQty = Math.min(maxBuyQty, makerQty);

            if (matchQty <= 0) break;

            // Calculate cost
            long cost = FixedPoint.multiply(matchQty, price);

            // Record match
            int idx = matchCount * MATCH_FIELDS;
            matchResults[idx] = makerOrderId;
            matchResults[idx + 1] = makerUserId;
            matchResults[idx + 2] = price;
            matchResults[idx + 3] = matchQty;
            matchCount++;

            // Update
            takerRemainingBudget -= cost;
            book.reduceOrderQuantity(makerOrderId, matchQty);
        }
    }

    /**
     * Cancel an order. O(1)
     */
    public boolean cancelOrder(long orderId, boolean isBuy) {
        DirectIndexOrderBook book = isBuy ? bidBook : askBook;
        return book.cancelOrder(orderId);
    }

    /**
     * Add order directly (no matching). O(1)
     */
    public void addOrderNoMatch(long orderId, long userId, boolean isBuy, long price, long quantity) {
        DirectIndexOrderBook book = isBuy ? bidBook : askBook;
        book.addOrder(orderId, userId, price, quantity);
    }

    // ==================== Match Result Access ====================

    public int getMatchCount() {
        return matchCount;
    }

    public long getMatchMakerOrderId(int matchIndex) {
        return matchResults[matchIndex * MATCH_FIELDS];
    }

    public long getMatchMakerUserId(int matchIndex) {
        return matchResults[matchIndex * MATCH_FIELDS + 1];
    }

    public long getMatchPrice(int matchIndex) {
        return matchResults[matchIndex * MATCH_FIELDS + 2];
    }

    public long getMatchQuantity(int matchIndex) {
        return matchResults[matchIndex * MATCH_FIELDS + 3];
    }

    public long getTakerRemainingQuantity() {
        return takerRemainingQty;
    }

    public long getTakerRemainingBudget() {
        return takerRemainingBudget;
    }

    // ==================== Book Access ====================

    public DirectIndexOrderBook getAskBook() {
        return askBook;
    }

    public DirectIndexOrderBook getBidBook() {
        return bidBook;
    }

    public long getBestAsk() {
        return askBook.getBestPrice();
    }

    public long getBestBid() {
        return bidBook.getBestPrice();
    }

    public boolean isAskEmpty() {
        return askBook.isEmpty();
    }

    public boolean isBidEmpty() {
        return bidBook.isEmpty();
    }

    // ==================== Top N Levels for Publishing ====================

    // Pre-allocated arrays for top N levels (avoid allocations)
    private static final int MAX_PUBLISH_LEVELS = 20;
    private final long[] topBidPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] topBidQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[] topBidOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private final long[] topAskPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] topAskQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[] topAskOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private volatile int topBidCount;
    private volatile int topAskCount;

    // Version tracking for collected data - stores version at collection time
    private volatile long collectedBidVersion;
    private volatile long collectedAskVersion;

    // Lock for atomic snapshot collection and reading
    // Ensures collectTopLevels and subsequent getters see consistent state
    private final Object snapshotLock = new Object();

    /**
     * Collect top N price levels from both books.
     * ZERO allocations - uses pre-allocated arrays.
     * Synchronized to ensure atomic collection for cross-thread readers.
     *
     * @param maxLevels Maximum levels to collect (up to 20)
     */
    public void collectTopLevels(int maxLevels) {
        synchronized (snapshotLock) {
            int levels = Math.min(maxLevels, MAX_PUBLISH_LEVELS);

            // Read version FIRST to establish memory barrier (acquires latest values)
            // Store versions for later use - this prevents compiler from optimizing away the reads
            collectedBidVersion = bidBook.getVersion();
            collectedAskVersion = askBook.getVersion();

            // Collect top bids (descending price order)
            topBidCount = 0;
            if (!bidBook.isEmpty()) {
                int priceIdx = bidBook.getBestPriceIndex();
                while (priceIdx >= 0 && topBidCount < levels) {
                    long price = bidBook.getBasePrice() + (long) priceIdx * bidBook.getTickSize();
                    long qty = bidBook.getTotalQuantity(priceIdx);
                    int orderCount = bidBook.getOrderCount(priceIdx);

                    if (qty > 0) {
                        topBidPrices[topBidCount] = price;
                        topBidQuantities[topBidCount] = qty;
                        topBidOrderCounts[topBidCount] = orderCount;
                        topBidCount++;
                    }

                    priceIdx = bidBook.nextPriceIndex(priceIdx);
                }
            }

            // Collect top asks (ascending price order)
            topAskCount = 0;
            if (!askBook.isEmpty()) {
                int priceIdx = askBook.getBestPriceIndex();
                while (priceIdx >= 0 && topAskCount < levels) {
                    long price = askBook.getBasePrice() + (long) priceIdx * askBook.getTickSize();
                    long qty = askBook.getTotalQuantity(priceIdx);
                    int orderCount = askBook.getOrderCount(priceIdx);

                    if (qty > 0) {
                        topAskPrices[topAskCount] = price;
                        topAskQuantities[topAskCount] = qty;
                        topAskOrderCounts[topAskCount] = orderCount;
                        topAskCount++;
                    }

                    priceIdx = askBook.nextPriceIndex(priceIdx);
                }
            }
        }
    }

    // Accessors for collected top levels
    public int getTopBidCount() { return topBidCount; }
    public int getTopAskCount() { return topAskCount; }
    public long[] getTopBidPrices() { return topBidPrices; }
    public long[] getTopBidQuantities() { return topBidQuantities; }
    public int[] getTopBidOrderCounts() { return topBidOrderCounts; }
    public long[] getTopAskPrices() { return topAskPrices; }
    public long[] getTopAskQuantities() { return topAskQuantities; }
    public int[] getTopAskOrderCounts() { return topAskOrderCounts; }
    public long getCollectedBidVersion() { return collectedBidVersion; }
    public long getCollectedAskVersion() { return collectedAskVersion; }
    public long getCollectedVersion() { return Math.max(collectedBidVersion, collectedAskVersion); }

    // ==================== Snapshot Support ====================

    /**
     * Get all bid orders for snapshot
     */
    public long[] getBidOrders() {
        return bidBook.getActiveOrders();
    }

    /**
     * Get all ask orders for snapshot
     */
    public long[] getAskOrders() {
        return askBook.getActiveOrders();
    }

    /**
     * Clear and restore state from snapshot
     */
    public void restoreFromSnapshot(long[] bidOrders, long[] askOrders) {
        bidBook.clear();
        askBook.clear();

        // Restore bid orders (4 fields each: orderId, userId, price, qty)
        for (int i = 0; i < bidOrders.length; i += 4) {
            bidBook.addOrder(bidOrders[i], bidOrders[i + 1], bidOrders[i + 2], bidOrders[i + 3]);
        }

        // Restore ask orders
        for (int i = 0; i < askOrders.length; i += 4) {
            askBook.addOrder(askOrders[i], askOrders[i + 1], askOrders[i + 2], askOrders[i + 3]);
        }
    }

    /**
     * Clear all state
     */
    public void clear() {
        bidBook.clear();
        askBook.clear();
        matchCount = 0;
    }
}
