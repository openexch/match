package com.match.domain;

/**
 * Represents a match between a taker and maker order.
 * Optimized for ultra-low latency with fixed-point arithmetic.
 * Supports object pooling via reset() method.
 */
public class OrderMatch {
    private Order taker;
    private Order maker;
    private long price;     // Fixed-point price
    private long quantity;  // Fixed-point quantity

    public OrderMatch() {
        // Default constructor for object pooling
    }

    public OrderMatch(Order taker, Order maker, long price, long quantity) {
        this.taker = taker;
        this.maker = maker;
        this.price = price;
        this.quantity = quantity;
    }

    /**
     * Reset for object pool reuse
     */
    public void reset() {
        this.taker = null;
        this.maker = null;
        this.price = 0L;
        this.quantity = 0L;
    }

    /**
     * Set all fields at once (for pooled objects)
     */
    public void set(Order taker, Order maker, long price, long quantity) {
        this.taker = taker;
        this.maker = maker;
        this.price = price;
        this.quantity = quantity;
    }

    public Order getTaker() {
        return taker;
    }

    public void setTaker(Order taker) {
        this.taker = taker;
    }

    public Order getMaker() {
        return maker;
    }

    public void setMaker(Order maker) {
        this.maker = maker;
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

    // Convenience methods

    public double getPriceAsDouble() {
        return FixedPoint.toDouble(price);
    }

    public double getQuantityAsDouble() {
        return FixedPoint.toDouble(quantity);
    }

    /**
     * Calculate trade value (price * quantity)
     */
    public long getValue() {
        return FixedPoint.multiply(price, quantity);
    }
}
