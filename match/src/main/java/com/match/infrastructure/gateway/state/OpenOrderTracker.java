package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tracks open orders per user from ORDER_STATUS messages.
 * Maintains order lifecycle and removes terminal orders (FILLED, CANCELLED, REJECTED).
 * Thread-safe for concurrent reads and single writer.
 */
public class OpenOrderTracker {
    private static final int POOL_SIZE = 1000;

    // userId -> (orderId -> OpenOrder)
    private final Long2ObjectHashMap<Long2ObjectHashMap<OpenOrder>> ordersByUser = new Long2ObjectHashMap<>();

    // Object pool for OpenOrder instances
    private final Deque<OpenOrder> orderPool = new ArrayDeque<>(POOL_SIZE);

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public OpenOrderTracker() {
        // Pre-allocate pool
        for (int i = 0; i < POOL_SIZE; i++) {
            orderPool.push(new OpenOrder());
        }
    }

    /**
     * Process a batch of order status updates.
     * Called from egress polling thread (single writer).
     */
    public void onOrderStatusBatch(JsonArray orders) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < orders.size(); i++) {
                JsonObject order = orders.get(i).getAsJsonObject();
                processOrderStatus(order);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Process a single order status update.
     * Called from egress polling thread (single writer).
     */
    public void onOrderStatus(JsonObject order) {
        lock.writeLock().lock();
        try {
            processOrderStatus(order);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Process order status (called under write lock).
     */
    private void processOrderStatus(JsonObject order) {
        long orderId = order.get("orderId").getAsLong();
        long userId = order.get("userId").getAsLong();
        String status = order.get("status").getAsString();

        // Check if terminal state
        if (isTerminal(status)) {
            removeOrder(userId, orderId);
            return;
        }

        // Get or create user's order map
        Long2ObjectHashMap<OpenOrder> userOrders = ordersByUser.get(userId);
        if (userOrders == null) {
            userOrders = new Long2ObjectHashMap<>();
            ordersByUser.put(userId, userOrders);
        }

        // Get or create order entry
        OpenOrder entry = userOrders.get(orderId);
        if (entry == null) {
            entry = orderPool.poll();
            if (entry == null) {
                entry = new OpenOrder();
            }
            entry.reset();
            userOrders.put(orderId, entry);
        }

        // Update order fields
        entry.orderId = orderId;
        entry.userId = userId;
        entry.price = order.get("price").getAsDouble();
        entry.remainingQuantity = order.get("remainingQuantity").getAsDouble();
        entry.filledQuantity = order.get("filledQuantity").getAsDouble();
        entry.side = order.get("side").getAsString();
        entry.status = status;
        entry.timestamp = order.get("timestamp").getAsLong();
    }

    /**
     * Remove order (called under write lock).
     */
    private void removeOrder(long userId, long orderId) {
        Long2ObjectHashMap<OpenOrder> userOrders = ordersByUser.get(userId);
        if (userOrders != null) {
            OpenOrder removed = userOrders.remove(orderId);
            if (removed != null) {
                removed.reset();
                orderPool.push(removed);
            }
            // Clean up empty user map
            if (userOrders.isEmpty()) {
                ordersByUser.remove(userId);
            }
        }
    }

    /**
     * Get all open orders for a user.
     * Returns a copy for thread safety.
     */
    public List<OpenOrder> getOrdersForUser(long userId) {
        lock.readLock().lock();
        try {
            Long2ObjectHashMap<OpenOrder> userOrders = ordersByUser.get(userId);
            if (userOrders == null || userOrders.isEmpty()) {
                return new ArrayList<>();
            }

            List<OpenOrder> result = new ArrayList<>(userOrders.size());
            Long2ObjectHashMap<OpenOrder>.ValueIterator iter = userOrders.values().iterator();
            while (iter.hasNext()) {
                OpenOrder copy = new OpenOrder();
                copy.copyFrom(iter.next());
                result.add(copy);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get JSON representation of open orders for a user.
     */
    public String toJson(long userId) {
        List<OpenOrder> orders = getOrdersForUser(userId);

        JsonObject json = new JsonObject();
        json.addProperty("type", "OPEN_ORDERS");
        json.addProperty("userId", userId);
        json.addProperty("timestamp", System.currentTimeMillis());
        json.addProperty("count", orders.size());

        JsonArray ordersArray = new JsonArray();
        for (OpenOrder o : orders) {
            JsonObject obj = new JsonObject();
            obj.addProperty("orderId", o.orderId);
            obj.addProperty("price", o.price);
            obj.addProperty("remainingQuantity", o.remainingQuantity);
            obj.addProperty("filledQuantity", o.filledQuantity);
            obj.addProperty("side", o.side);
            obj.addProperty("status", o.status);
            obj.addProperty("timestamp", o.timestamp);
            ordersArray.add(obj);
        }
        json.add("orders", ordersArray);

        return json.toString();
    }

    /**
     * Get total open order count across all users.
     */
    public int getOpenOrderCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            Long2ObjectHashMap<Long2ObjectHashMap<OpenOrder>>.ValueIterator iter = ordersByUser.values().iterator();
            while (iter.hasNext()) {
                count += iter.next().size();
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean isTerminal(String status) {
        return "FILLED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status);
    }
}
