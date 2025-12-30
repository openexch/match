package com.match.application.handlers;

import com.match.application.orderbook.AATree;
import com.match.application.orderbook.OrderBookSideImpl;
import com.match.domain.*;
import com.match.domain.enums.OrderSide;
import com.match.domain.interfaces.OrderBookSide;

import java.util.ArrayList;
import java.util.List;

/**
 * Handler for MARKET orders optimized for ultra-low latency.
 * Uses fixed-point arithmetic, pre-allocated data structures,
 * and AA Tree iteration for O(log n) price level traversal.
 */
public class MarketOrderHandler implements OrderBookSide.OrderTypeSideHandler {

    // Thread-local pre-allocated match list
    private static final ThreadLocal<ArrayList<OrderMatch>> MATCH_LIST =
            ThreadLocal.withInitial(() -> new ArrayList<>(64));

    @Override
    public OrderBookSide.MatchResult handle(OrderBookSide side, Order taker) {
        ArrayList<OrderMatch> matches = MATCH_LIST.get();
        matches.clear();

        if (taker.getSide() == OrderSide.BID) {
            handleMarketBuy(side, taker, matches);
        } else {
            handleMarketSell(side, taker, matches);
        }

        // Market orders never rest on the book
        return new OrderBookSide.MatchResult(matches, null);
    }

    /**
     * Market buy: spend totalPrice to buy as much as possible
     */
    private void handleMarketBuy(OrderBookSide side, Order taker, List<OrderMatch> matches) {
        long remainingTotalPrice = taker.getTotalPrice();

        // Use AA Tree iteration for efficient traversal
        if (side instanceof OrderBookSideImpl) {
            AATree priceTree = ((OrderBookSideImpl) side).getPriceTree();
            priceTree.startIteration();

            while (priceTree.hasNext() && remainingTotalPrice > 0L) {
                Level level = priceTree.next();
                if (level == null || level.isEmpty()) continue;

                long price = level.getPrice();
                remainingTotalPrice = matchMarketBuyAtLevel(taker, level, price, remainingTotalPrice, matches);
            }
        } else {
            // Fallback for other implementations
            long[] sortedPrices = side.getSortedPrices();
            int priceCount = side.getPriceLevelCount();

            for (int p = 0; p < priceCount && remainingTotalPrice > 0L; p++) {
                long price = sortedPrices[p];
                Level level = side.getLevel(price);
                if (level == null || level.isEmpty()) continue;

                remainingTotalPrice = matchMarketBuyAtLevel(taker, level, price, remainingTotalPrice, matches);
            }
        }
    }

    /**
     * Match market buy at a specific price level
     */
    private long matchMarketBuyAtLevel(Order taker, Level level, long price,
                                       long remainingTotalPrice, List<OrderMatch> matches) {
        List<Order> orders = level.getOrders();
        int orderCount = orders.size();

        for (int i = 0; i < orderCount && remainingTotalPrice > 0L; i++) {
            Order maker = orders.get(i);

            // Calculate max quantity we can buy with remaining budget
            long maxQuantityToBuy = FixedPoint.divide(remainingTotalPrice, price);

            // Match quantity is min of what we can afford and what maker has
            long matchQuantity = Math.min(maker.getRemainingQuantity(), maxQuantityToBuy);

            if (matchQuantity <= 0L) continue;

            // Calculate cost: matchQuantity * price
            long matchCost = FixedPoint.multiply(matchQuantity, price);
            remainingTotalPrice -= matchCost;

            // For market buy, accumulate bought quantity in remainingQuantity
            taker.setRemainingQuantity(taker.getRemainingQuantity() + matchQuantity);

            matches.add(new OrderMatch(taker, maker, price, matchQuantity));
        }

        return remainingTotalPrice;
    }

    /**
     * Market sell: sell as much quantity as possible
     */
    private void handleMarketSell(OrderBookSide side, Order taker, List<OrderMatch> matches) {
        long remainingQuantity = taker.getRemainingQuantity();

        // Use AA Tree iteration for efficient traversal
        if (side instanceof OrderBookSideImpl) {
            AATree priceTree = ((OrderBookSideImpl) side).getPriceTree();
            priceTree.startIteration();

            while (priceTree.hasNext() && remainingQuantity > 0L) {
                Level level = priceTree.next();
                if (level == null || level.isEmpty()) continue;

                long price = level.getPrice();
                remainingQuantity = matchMarketSellAtLevel(taker, level, price, remainingQuantity, matches);
            }
        } else {
            // Fallback for other implementations
            long[] sortedPrices = side.getSortedPrices();
            int priceCount = side.getPriceLevelCount();

            for (int p = 0; p < priceCount && remainingQuantity > 0L; p++) {
                long price = sortedPrices[p];
                Level level = side.getLevel(price);
                if (level == null || level.isEmpty()) continue;

                remainingQuantity = matchMarketSellAtLevel(taker, level, price, remainingQuantity, matches);
            }
        }

        taker.setRemainingQuantity(remainingQuantity);
    }

    /**
     * Match market sell at a specific price level
     */
    private long matchMarketSellAtLevel(Order taker, Level level, long price,
                                        long remainingQuantity, List<OrderMatch> matches) {
        List<Order> orders = level.getOrders();
        int orderCount = orders.size();

        for (int i = 0; i < orderCount && remainingQuantity > 0L; i++) {
            Order maker = orders.get(i);

            long matchQuantity = Math.min(maker.getRemainingQuantity(), remainingQuantity);
            remainingQuantity -= matchQuantity;

            matches.add(new OrderMatch(taker, maker, price, matchQuantity));
        }

        return remainingQuantity;
    }
}
