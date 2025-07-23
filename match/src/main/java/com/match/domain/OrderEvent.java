package com.match.domain;

import com.match.domain.enums.OrderEventType;

public class OrderEvent {
    private final Order order;
    private final OrderEventType type;

    public OrderEvent(Order order, OrderEventType type) {
        this.order = order;
        this.type = type;
    }

    public Order getOrder() {
        return order;
    }

    public OrderEventType getType() {
        return type;
    }
} 