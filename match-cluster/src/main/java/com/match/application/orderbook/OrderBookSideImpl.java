package com.match.application.orderbook;

import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.enums.OrderType;
import com.match.domain.interfaces.OrderBookSide;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.EnumMap;

/**
 * Order book side implementation optimized for ultra-low latency.
 * Uses AA Tree for O(log n) insert/delete with O(1) best price access.
 */
public class OrderBookSideImpl implements OrderBookSide {

    private static final long NO_PRICE = Long.MIN_VALUE;

    // AA Tree for price levels - O(log n) insert/delete, O(1) best price
    private final AATree priceTree;

    // Direction: true = ascending (ask side), false = descending (bid side)
    private final boolean ascending;

    // Handlers for each order type
    private final EnumMap<OrderType, OrderTypeSideHandler> handlers;

    /**
     * @param ascending true for ask side (ascending prices), false for bid side (descending prices)
     */
    public OrderBookSideImpl(boolean ascending) {
        this.ascending = ascending;
        this.priceTree = new AATree(ascending);
        this.handlers = new EnumMap<>(OrderType.class);
    }

    @Override
    public void placeOrder(Order order) {
        long price = order.getPrice();
        Level level = priceTree.get(price);
        if (level != null) {
            level.append(order);
        } else {
            createPriceLevel(order);
        }
    }

    @Override
    public void createPriceLevel(Order order) {
        long price = order.getPrice();
        Level level = new Level(order);
        priceTree.put(price, level);
    }

    @Override
    public void removePriceLevel(long price) {
        priceTree.remove(price);
    }

    @Override
    public void cancelOrder(Order order) {
        removeOrder(order);
    }

    @Override
    public void removeOrder(Order order) {
        long price = order.getPrice();
        Level level = priceTree.get(price);
        if (level != null) {
            level.delete(order.getId());
            if (level.isEmpty()) {
                removePriceLevel(price);
            }
        }
    }

    @Override
    public long getBestPrice() {
        return priceTree.isEmpty() ? NO_PRICE : priceTree.getBestPrice();
    }

    @Override
    public boolean isEmpty() {
        return priceTree.isEmpty();
    }

    @Override
    public long[] getSortedPrices() {
        // Build array from tree for compatibility
        // This is not on the hot path - only used for snapshots/debugging
        int count = priceTree.size();
        long[] prices = new long[count];
        priceTree.startIteration();
        int i = 0;
        while (priceTree.hasNext() && i < count) {
            prices[i++] = priceTree.nextPrice();
            priceTree.next(); // Advance iterator
        }
        return prices;
    }

    @Override
    public int getPriceLevelCount() {
        return priceTree.size();
    }

    @Override
    public Long2ObjectHashMap<Level> getPriceLevels() {
        // Build map from tree for compatibility
        // This is not on the hot path - only used for snapshots/debugging
        Long2ObjectHashMap<Level> map = new Long2ObjectHashMap<>();
        priceTree.startIteration();
        while (priceTree.hasNext()) {
            Level level = priceTree.next();
            if (level != null) {
                map.put(level.getPrice(), level);
            }
        }
        return map;
    }

    @Override
    public Level getLevel(long price) {
        return priceTree.get(price);
    }

    @Override
    public void registerHandler(OrderType orderType, OrderTypeSideHandler handler) {
        handlers.put(orderType, handler);
    }

    @Override
    public OrderTypeSideHandler getHandler(OrderType orderType) {
        OrderTypeSideHandler handler = handlers.get(orderType);
        if (handler == null) {
            throw new IllegalArgumentException("Handler not found for order type: " + orderType);
        }
        return handler;
    }

    @Override
    public boolean canMatch(Order taker) {
        if (priceTree.isEmpty()) {
            return false;
        }

        // Market orders can always match if there are orders on the book
        if (taker.getType() == OrderType.MARKET) {
            return true;
        }

        long bestPrice = getBestPrice();

        // For ask side (ascending), taker price should be >= best ask price
        // For bid side (descending), taker price should be <= best bid price
        if (ascending) {
            // Ask side: taker (bid) matches if taker.price >= best ask price
            return taker.getPrice() >= bestPrice;
        } else {
            // Bid side: taker (ask) matches if taker.price <= best bid price
            return taker.getPrice() <= bestPrice;
        }
    }

    /**
     * Get the AA Tree for direct iteration during matching.
     * Use startIteration(), hasNext(), next() for efficient traversal.
     */
    public AATree getPriceTree() {
        return priceTree;
    }
}
