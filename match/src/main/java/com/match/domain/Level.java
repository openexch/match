package com.match.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Level {

    private final BigDecimal price;
    private final List<Order> orders;
    private final Map<String, Integer> orderIndexMap;
    private BigDecimal totalQuantity;

    public Level(Order order) {
        this.price = order.getPrice();
        this.orders = new ArrayList<>();
        this.orderIndexMap = new HashMap<>();
        this.totalQuantity = BigDecimal.ZERO;
        append(order);
    }

    public void append(Order order) {
        orders.add(order);
        orderIndexMap.put(order.getId(), orders.size() - 1);
        totalQuantity = totalQuantity.add(order.getRemainingQuantity());
    }

    public void delete(String orderId) {
        Integer index = orderIndexMap.get(orderId);
        if (index != null) {
            Order order = orders.get(index);
            totalQuantity = totalQuantity.subtract(order.getRemainingQuantity());
            
            // Remove order efficiently by swapping with last element
            int lastIndex = orders.size() - 1;
            if (index < lastIndex) {
                Order lastOrder = orders.get(lastIndex);
                orders.set(index, lastOrder);
                orderIndexMap.put(lastOrder.getId(), index);
            }
            orders.remove(lastIndex);
            orderIndexMap.remove(orderId);
        }
    }

    public Order getHead() {
        return orders.isEmpty() ? null : orders.get(0);
    }

    public Order getTail() {
        return orders.isEmpty() ? null : orders.get(orders.size() - 1);
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getLength() {
        return orders.size();
    }

    public BigDecimal getTotalQuantity() {
        return totalQuantity;
    }

    public List<Order> getOrders() {
        return orders;
    }
}