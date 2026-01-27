package com.match.domain;

import org.agrona.collections.Long2LongHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Price level in the order book, optimized for ultra-low latency.
 * Uses fixed-point long for price/quantity and primitive collections.
 */
public class Level {

    private final long price;  // Fixed-point price
    private final List<Order> orders;
    private final Long2LongHashMap orderIndexMap;  // long orderId -> long index
    private long totalQuantity;  // Fixed-point total quantity

    private static final long MISSING_VALUE = -1L;

    public Level(long price) {
        this.price = price;
        this.orders = new ArrayList<>(16);  // Pre-size for common case
        this.orderIndexMap = new Long2LongHashMap(MISSING_VALUE);
        this.totalQuantity = 0L;
    }

    public Level(Order order) {
        this(order.getPrice());
        append(order);
    }

    public void append(Order order) {
        orders.add(order);
        orderIndexMap.put(order.getId(), orders.size() - 1);
        totalQuantity += order.getRemainingQuantity();
    }

    public void delete(long orderId) {
        long indexLong = orderIndexMap.get(orderId);
        if (indexLong != MISSING_VALUE) {
            int index = (int) indexLong;
            Order order = orders.get(index);
            totalQuantity -= order.getRemainingQuantity();

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

    /**
     * Update total quantity when an order's remaining quantity changes
     */
    public void updateQuantity(long delta) {
        totalQuantity += delta;
    }

    public Order getHead() {
        return orders.isEmpty() ? null : orders.get(0);
    }

    public Order getTail() {
        return orders.isEmpty() ? null : orders.get(orders.size() - 1);
    }

    public long getPrice() {
        return price;
    }

    public int getLength() {
        return orders.size();
    }

    public boolean isEmpty() {
        return orders.isEmpty();
    }

    public long getTotalQuantity() {
        return totalQuantity;
    }

    public List<Order> getOrders() {
        return orders;
    }

    public Order getOrder(int index) {
        return orders.get(index);
    }

    public int getOrderCount() {
        return orders.size();
    }
}
