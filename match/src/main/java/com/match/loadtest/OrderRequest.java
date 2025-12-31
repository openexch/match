package com.match.loadtest;

import com.match.domain.FixedPoint;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Order request for the lock-free queue.
 * Uses primitive types for zero-allocation encoding.
 */
public final class OrderRequest {

    // Global correlation ID generator
    private static final AtomicLong CORRELATION_ID_GENERATOR = new AtomicLong(0);

    public final long correlationId;  // Unique ID for round-trip latency tracking
    public final long userId;
    public final int marketId;
    public final String orderType;
    public final String orderSide;
    public final long price;        // Fixed-point (8 decimals)
    public final long quantity;     // Fixed-point (8 decimals)
    public final long totalPrice;   // Fixed-point (8 decimals)
    public final long enqueueTimeNs;
    public volatile long sendTimeNs;  // Time when actually sent to cluster

    public OrderRequest(
        long userId,
        int marketId,
        String orderType,
        String orderSide,
        double price,
        double quantity,
        double totalPrice,
        long enqueueTimeNs
    ) {
        this.correlationId = CORRELATION_ID_GENERATOR.incrementAndGet();
        this.userId = userId;
        this.marketId = marketId;
        this.orderType = orderType;
        this.orderSide = orderSide;
        // Convert to fixed-point at construction time (once)
        this.price = FixedPoint.fromDouble(price);
        this.quantity = FixedPoint.fromDouble(quantity);
        this.totalPrice = FixedPoint.fromDouble(totalPrice);
        this.enqueueTimeNs = enqueueTimeNs;
    }
}
