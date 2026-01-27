package com.match.domain.commands;

/**
 * Command for canceling an order.
 * Optimized for object pooling with reset() method.
 */
public class CancelOrderCommand {
    private long userId;
    private long orderId;

    /**
     * Reset for object pool reuse
     */
    public void reset() {
        this.userId = 0L;
        this.orderId = 0L;
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
}
