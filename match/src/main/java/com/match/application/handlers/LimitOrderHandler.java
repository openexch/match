package com.match.application.handlers;

import com.match.application.orderbook.AATree;
import com.match.application.orderbook.OrderBookSideImpl;
import com.match.domain.*;
import com.match.domain.interfaces.OrderBookSide;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler for LIMIT orders optimized for ultra-low latency.
 * Uses fixed-point arithmetic, pre-allocated data structures,
 * and AA Tree iteration for O(log n) price level traversal.
 */
public class LimitOrderHandler implements OrderBookSide.OrderTypeSideHandler {

    // Thread-local pre-allocated match list to avoid allocations
    private static final ThreadLocal<ArrayList<OrderMatch>> MATCH_LIST =
        ThreadLocal.withInitial(() -> new ArrayList<>(64));

    @Override
    public OrderBookSide.MatchResult handle(OrderBookSide side, Order taker) {
        // Get and clear thread-local list (reuse, don't allocate)
        ArrayList<OrderMatch> matches = MATCH_LIST.get();
        matches.clear();

        Order placeOrder = taker;

        // Use AA Tree iteration for efficient traversal (no array allocation)
        if (side instanceof OrderBookSideImpl) {
            AATree priceTree = ((OrderBookSideImpl) side).getPriceTree();
            priceTree.startIteration();

            while (priceTree.hasNext() && taker.getRemainingQuantity() > 0L) {
                Level level = priceTree.next();
                if (level == null || level.isEmpty()) continue;

                long price = level.getPrice();
                matchAtLevel(taker, level, price, matches);
            }
        } else {
            // Fallback for other implementations
            long[] sortedPrices = side.getSortedPrices();
            int priceCount = side.getPriceLevelCount();

            for (int p = 0; p < priceCount && taker.getRemainingQuantity() > 0L; p++) {
                long price = sortedPrices[p];
                Level level = side.getLevel(price);
                if (level == null || level.isEmpty()) continue;

                matchAtLevel(taker, level, price, matches);
            }
        }

        if (taker.getRemainingQuantity() <= 0L) {
            placeOrder = null;
        }

        return new OrderBookSide.MatchResult(matches, placeOrder);
    }

    /**
     * Match taker against all orders at a price level
     */
    private void matchAtLevel(Order taker, Level level, long price, ArrayList<OrderMatch> matches) {
        List<Order> orders = level.getOrders();
        int orderCount = orders.size();

        for (int i = 0; i < orderCount; i++) {
            // Check if taker is fully filled
            if (taker.getRemainingQuantity() <= 0L) {
                break;
            }

            Order maker = orders.get(i);

            // Calculate match quantity (min of remaining quantities)
            long matchQuantity = Math.min(
                maker.getRemainingQuantity(),
                taker.getRemainingQuantity()
            );

            // Update taker's remaining quantity
            taker.setRemainingQuantity(taker.getRemainingQuantity() - matchQuantity);

            // Record the match
            matches.add(new OrderMatch(taker, maker, price, matchQuantity));
        }
    }
}
