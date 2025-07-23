package com.match.application.handlers;

import com.match.domain.*;
import com.match.domain.enums.OrderSide;
import com.match.domain.interfaces.OrderBookSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public class MarketOrderHandler implements OrderBookSide.OrderTypeSideHandler {

    @Override
    public OrderBookSide.MatchResult handle(OrderBookSide side, Order taker) {
        List<OrderMatch> matches = new ArrayList<>();

        if (taker.getSide() == OrderSide.BID) { // Market Buy
            handleMarketBuy(side, taker, matches);
        } else { // Market Sell
            handleMarketSell(side, taker, matches);
        }

        // Market orders never rest on the book
        return new OrderBookSide.MatchResult(matches, null);
    }

    private void handleMarketBuy(OrderBookSide side, Order taker, List<OrderMatch> matches) {
        BigDecimal remainingTotalPrice = taker.getTotalPrice();

        for (BigDecimal price : side.getPrices()) {
            if (remainingTotalPrice.compareTo(BigDecimal.ZERO) <= 0) break;

            Level level = side.getPriceLevels().get(price);
            if (level == null) continue;

            List<Order> orders = level.getOrders();
            for (int i = 0; i < orders.size(); i++) {
                Order maker = orders.get(i);
                if (remainingTotalPrice.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal maxQuantityToBuy = remainingTotalPrice.divide(price, 8, RoundingMode.DOWN);
                BigDecimal matchQuantity = maker.getRemainingQuantity().min(maxQuantityToBuy);
                
                if (matchQuantity.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal matchCost = matchQuantity.multiply(price);
                remainingTotalPrice = remainingTotalPrice.subtract(matchCost);
                
                taker.setRemainingQuantity(taker.getRemainingQuantity().add(matchQuantity));

                matches.add(new OrderMatch(taker, maker, price, matchQuantity));
            }
        }
    }

    private void handleMarketSell(OrderBookSide side, Order taker, List<OrderMatch> matches) {
        BigDecimal remainingQuantity = taker.getRemainingQuantity();

        for (BigDecimal price : side.getPrices()) {
            if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) break;

            Level level = side.getPriceLevels().get(price);
            if (level == null) continue;

            List<Order> orders = level.getOrders();
            for (int i = 0; i < orders.size(); i++) {
                Order maker = orders.get(i);
                 if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) break;

                BigDecimal matchQuantity = maker.getRemainingQuantity().min(remainingQuantity);

                remainingQuantity = remainingQuantity.subtract(matchQuantity);
                
                matches.add(new OrderMatch(taker, maker, price, matchQuantity));
            }
        }
        taker.setRemainingQuantity(remainingQuantity);
    }
} 