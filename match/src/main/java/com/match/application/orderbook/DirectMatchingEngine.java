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
    private static final int MAX_MATCHES_PER_ORDER = 100;

    // Match data: [makerOrderId, price, quantity] per match
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
        this.matchResults = new long[MAX_MATCHES_PER_ORDER * 3]; // 3 fields per match
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

        // Place remaining quantity on book
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
            long makerOrderId = book.getHeadOrderId(priceIdx);
            if (makerOrderId < 0) break;

            long makerQty = book.getHeadOrderQuantity(priceIdx);
            long matchQty = Math.min(takerRemainingQty, makerQty);

            if (matchQty <= 0) break;

            // Record match
            if (matchCount < MAX_MATCHES_PER_ORDER) {
                int idx = matchCount * 3;
                matchResults[idx] = makerOrderId;
                matchResults[idx + 1] = price;
                matchResults[idx + 2] = matchQty;
                matchCount++;
            }

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
            long makerOrderId = book.getHeadOrderId(priceIdx);
            if (makerOrderId < 0) break;

            long makerQty = book.getHeadOrderQuantity(priceIdx);

            // Calculate max we can buy with remaining budget
            long maxBuyQty = FixedPoint.divide(takerRemainingBudget, price);
            long matchQty = Math.min(maxBuyQty, makerQty);

            if (matchQty <= 0) break;

            // Calculate cost
            long cost = FixedPoint.multiply(matchQty, price);

            // Record match
            if (matchCount < MAX_MATCHES_PER_ORDER) {
                int idx = matchCount * 3;
                matchResults[idx] = makerOrderId;
                matchResults[idx + 1] = price;
                matchResults[idx + 2] = matchQty;
                matchCount++;
            }

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
        return matchResults[matchIndex * 3];
    }

    public long getMatchPrice(int matchIndex) {
        return matchResults[matchIndex * 3 + 1];
    }

    public long getMatchQuantity(int matchIndex) {
        return matchResults[matchIndex * 3 + 2];
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
