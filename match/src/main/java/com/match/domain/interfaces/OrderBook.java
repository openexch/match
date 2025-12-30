package com.match.domain.interfaces;

import com.match.domain.Order;
import com.match.domain.enums.OrderSide;

/**
 * Order book interface optimized for ultra-low latency.
 * Uses primitive long for order IDs.
 */
public interface OrderBook {
    void createOrder(Order order) throws Exception;
    void cancelOrder(long orderId) throws Exception;
    void updateOrder(long orderId) throws Exception;
    void registerSide(OrderSide side, OrderBookSide sideHandler);
    OrderBookSide getBidSide();
    OrderBookSide getAskSide();
}
