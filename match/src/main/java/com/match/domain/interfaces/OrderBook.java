package com.match.domain.interfaces;

import com.match.domain.Order;
import com.match.domain.enums.OrderSide;

public interface OrderBook {
    void createOrder(Order order) throws Exception;
    void cancelOrder(String orderId) throws Exception;
    void updateOrder(String orderId) throws Exception;
    void registerSide(OrderSide side, OrderBookSide sideHandler);
    OrderBookSide getBidSide();
    OrderBookSide getAskSide();
}