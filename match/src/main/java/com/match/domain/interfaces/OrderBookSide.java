package com.match.domain.interfaces;

import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.OrderMatch;
import com.match.domain.enums.OrderType;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.List;

/**
 * Interface for one side (bid or ask) of an order book.
 * Optimized for ultra-low latency with primitive types.
 */
public interface OrderBookSide {

    void placeOrder(Order order);
    void cancelOrder(Order order);
    void removeOrder(Order order);
    void createPriceLevel(Order order);
    void removePriceLevel(long price);

    /**
     * Get best price (lowest ask or highest bid)
     * @return best price in fixed-point, or Long.MIN_VALUE if empty
     */
    long getBestPrice();

    /**
     * Check if side has any orders
     */
    boolean isEmpty();

    /**
     * Get sorted price levels for iteration (ascending for asks, descending for bids)
     */
    long[] getSortedPrices();

    /**
     * Get number of price levels
     */
    int getPriceLevelCount();

    /**
     * Get price level map for direct access
     */
    Long2ObjectHashMap<Level> getPriceLevels();

    /**
     * Get level at specific price
     */
    Level getLevel(long price);

    void registerHandler(OrderType orderType, OrderTypeSideHandler handler);
    boolean canMatch(Order taker);
    OrderTypeSideHandler getHandler(OrderType orderType);

    @FunctionalInterface
    interface OrderTypeSideHandler {
        MatchResult handle(OrderBookSide side, Order order);
    }

    /**
     * Result of a matching operation.
     * Uses pre-allocated list to avoid allocations.
     */
    class MatchResult {
        private final List<OrderMatch> matches;
        private final Order placeOrder;

        public MatchResult(List<OrderMatch> matches, Order placeOrder) {
            this.matches = matches;
            this.placeOrder = placeOrder;
        }

        public List<OrderMatch> getMatches() {
            return matches;
        }

        public Order getPlaceOrder() {
            return placeOrder;
        }

        public boolean hasMatches() {
            return matches != null && !matches.isEmpty();
        }

        public boolean shouldPlaceOrder() {
            return placeOrder != null;
        }
    }
}
