package com.match.domain;

import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;

/**
 * Order entity optimized for ultra-low latency.
 * Uses fixed-point long arithmetic instead of BigDecimal.
 * Supports object pooling via reset() method.
 */
public class Order {
    // Use long ID for fast lookup (instead of String UUID)
    private long id;
    private long userId;  // Numeric user ID for performance

    // Fixed-point values (8 decimal places)
    private long price;
    private long quantity;
    private long totalPrice;
    private long remainingQuantity;

    private OrderSide side;
    private int marketId;  // Numeric market ID
    private OrderType type;

    // Timestamps as epoch nanos (instead of LocalDateTime)
    private long acceptedAtNanos;
    private long processedAtNanos;

    /**
     * Reset order for object pool reuse
     */
    public void reset() {
        this.id = 0L;
        this.userId = 0L;
        this.price = 0L;
        this.quantity = 0L;
        this.totalPrice = 0L;
        this.remainingQuantity = 0L;
        this.side = null;
        this.marketId = 0;
        this.type = null;
        this.acceptedAtNanos = 0L;
        this.processedAtNanos = 0L;
    }

    // Getters and setters

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
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

    public long getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(long totalPrice) {
        this.totalPrice = totalPrice;
    }

    public OrderSide getSide() {
        return side;
    }

    public void setSide(OrderSide side) {
        this.side = side;
    }

    public long getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(long remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public int getMarketId() {
        return marketId;
    }

    public void setMarketId(int marketId) {
        this.marketId = marketId;
    }

    public OrderType getType() {
        return type;
    }

    public void setType(OrderType type) {
        this.type = type;
    }

    public long getAcceptedAtNanos() {
        return acceptedAtNanos;
    }

    public void setAcceptedAtNanos(long acceptedAtNanos) {
        this.acceptedAtNanos = acceptedAtNanos;
    }

    public long getProcessedAtNanos() {
        return processedAtNanos;
    }

    public void setProcessedAtNanos(long processedAtNanos) {
        this.processedAtNanos = processedAtNanos;
    }

    // Convenience methods for fixed-point conversion

    public double getPriceAsDouble() {
        return FixedPoint.toDouble(price);
    }

    public void setPriceFromDouble(double price) {
        this.price = FixedPoint.fromDouble(price);
    }

    public double getQuantityAsDouble() {
        return FixedPoint.toDouble(quantity);
    }

    public void setQuantityFromDouble(double quantity) {
        this.quantity = FixedPoint.fromDouble(quantity);
    }

    public double getRemainingQuantityAsDouble() {
        return FixedPoint.toDouble(remainingQuantity);
    }

    public boolean isFilled() {
        return remainingQuantity <= 0L;
    }
}
