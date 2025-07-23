package com.match.application.orderbook;

import com.match.domain.Order;
import com.match.domain.OrderMatch;
import com.match.domain.enums.OrderSide;
import com.match.domain.interfaces.OrderBook;
import com.match.domain.interfaces.OrderBookSide;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import com.match.infrastructure.Logger;

public class OrderBookImpl implements OrderBook {
    private static final Logger logger = Logger.getLogger(OrderBookImpl.class);
    private final Map<OrderSide, OrderBookSide> orderSides;
    private final Map<String, Order> index;

    public OrderBookImpl(String id, String currency, String payment) {
        this.orderSides = new HashMap<>();
        this.index = new HashMap<>();
        logger.info("Order book created for %s%s pair", currency, payment);
    }

    @Override
    public void createOrder(Order order) throws Exception {
        matchOrder(order);
    }

    @Override
    public void cancelOrder(String orderId) throws Exception {
        Order existingOrder = index.get(orderId);
        if (existingOrder != null) {
            OrderBookSide side = orderSides.get(existingOrder.getSide());
            if (side != null) {
                side.removeOrder(existingOrder);
                index.remove(existingOrder.getId());
            }
        }
    }

    @Override
    public void updateOrder(String orderId) throws Exception {
        // Simplified: remove, and then it would be re-added as a creation
        Order orderToUpdate = index.get(orderId);
        if (orderToUpdate != null) {
            OrderBookSide side = orderSides.get(orderToUpdate.getSide());
            if (side != null) {
                side.removeOrder(orderToUpdate);
                index.remove(orderToUpdate.getId());
            }
        }
    }

    private void matchOrder(Order order) throws Exception {
        OrderSide counterSide = (order.getSide() == OrderSide.ASK) ? OrderSide.BID : OrderSide.ASK;
        OrderBookSide makerSide = orderSides.get(counterSide);
        OrderBookSide takerSide = orderSides.get(order.getSide());

        if (makerSide.canMatch(order)) {
            OrderBookSide.OrderTypeSideHandler handler = makerSide.getHandler(order.getType());
            OrderBookSide.MatchResult result = handler.handle(makerSide, order);

            for (OrderMatch match : result.getMatches()) {
                match.getMaker().setRemainingQuantity(match.getMaker().getRemainingQuantity().subtract(match.getQuantity()));
                if (match.getMaker().getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
                    makerSide.removeOrder(match.getMaker());
                    index.remove(match.getMaker().getId());
                }
            }

            if (result.getPlaceOrder() != null) {
                takerSide.placeOrder(result.getPlaceOrder());
                index.put(result.getPlaceOrder().getId(), result.getPlaceOrder());
            }
        } else {
            takerSide.placeOrder(order);
            index.put(order.getId(), order);
        }
    }

    @Override
    public void registerSide(OrderSide side, OrderBookSide sideHandler) {
        this.orderSides.put(side, sideHandler);
    }

    @Override
    public OrderBookSide getBidSide() {
        return orderSides.get(OrderSide.BID);
    }

    @Override
    public OrderBookSide getAskSide() {
        return orderSides.get(OrderSide.ASK);
    }
}