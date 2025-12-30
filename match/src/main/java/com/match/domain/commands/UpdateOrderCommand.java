package com.match.domain.commands;

import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;

/**
 * Command for updating an order.
 * Optimized for object pooling with reset() method.
 * Uses fixed-point long for prices and quantities.
 */
public class UpdateOrderCommand {
    private long userId;
    private long orderId;
    private long price;     // Fixed-point price
    private long quantity;  // Fixed-point quantity
    private OrderSide orderSide;
    private OrderType orderType;

    /**
     * Reset for object pool reuse
     */
    public void reset() {
        this.userId = 0L;
        this.orderId = 0L;
        this.price = 0L;
        this.quantity = 0L;
        this.orderSide = null;
        this.orderType = null;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public long getQuantity() {
        return quantity;
    }

    public void setQuantity(long quantity) {
        this.quantity = quantity;
    }

    public OrderSide getOrderSide() {
        return orderSide;
    }

    public void setOrderSide(OrderSide orderSide) {
        this.orderSide = orderSide;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }
}
