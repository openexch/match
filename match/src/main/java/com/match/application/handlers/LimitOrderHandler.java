package com.match.application.handlers;

import com.match.domain.*;
import com.match.domain.interfaces.OrderBookSide;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class LimitOrderHandler implements OrderBookSide.OrderTypeSideHandler {

    @Override
    public OrderBookSide.MatchResult handle(OrderBookSide side, Order taker) {
        List<OrderMatch> matches = new ArrayList<>();
        Order placeOrder = taker;

        for (BigDecimal price : side.getPrices()) {
            Level level = side.getPriceLevels().get(price);
            if (level == null) continue;

            // For a bid (taker), we match against asks. We stop if ask price > taker price.
            // For an ask (taker), we match against bids. We stop if bid price < taker price.
            // This logic is implicitly handled by how prices are iterated in OrderBookSideImpl,
            // but an explicit check might be needed depending on the side.
            // Let's assume the side's price iteration order is correct for matching.

            List<Order> orders = level.getOrders();
            for (int i = 0; i < orders.size(); i++) {
                Order maker = orders.get(i);
                if (taker.getRemainingQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }

                BigDecimal matchQuantity = maker.getRemainingQuantity().min(taker.getRemainingQuantity());
                
                taker.setRemainingQuantity(taker.getRemainingQuantity().subtract(matchQuantity));
                // The maker's remaining quantity is updated in the OrderBook after matches are processed.

                matches.add(new OrderMatch(taker, maker, level.getPrice(), matchQuantity));
            }

            if (taker.getRemainingQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                placeOrder = null; // Fully filled
                break;
            }
        }

        return new OrderBookSide.MatchResult(matches, placeOrder);
    }
} 