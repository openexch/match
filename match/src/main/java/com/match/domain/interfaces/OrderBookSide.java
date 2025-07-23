package com.match.domain.interfaces;

import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.OrderMatch;
import com.match.domain.enums.OrderType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface OrderBookSide {

    void placeOrder(Order order);
    void cancelOrder(Order order);
    void removeOrder(Order order);
    void createPriceLevel(Order order);
    void removePriceLevel(BigDecimal price);
    BigDecimal getBestPrice();
    Collection<BigDecimal> getPrices();
    Map<BigDecimal, Level> getPriceLevels();
    void registerHandler(OrderType orderType, OrderTypeSideHandler handler);
    boolean canMatch(Order taker);
    OrderTypeSideHandler getHandler(OrderType orderType) throws Exception;

    @FunctionalInterface
    interface OrderTypeSideHandler {
        MatchResult handle(OrderBookSide side, Order order) throws Exception;
    }

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
    }
} 