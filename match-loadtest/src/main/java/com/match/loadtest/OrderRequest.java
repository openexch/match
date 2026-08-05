// SPDX-License-Identifier: Apache-2.0
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
    /** When this order actually entered the queue. Only the ingress metric may use it. */
    public final long enqueueTimeNs;
    /**
     * The slot on the fixed-rate timeline this order was DUE to be sent, which is the t0 of the
     * committed round trip. It is deliberately not the moment the order was generated or offered:
     * once the system falls behind, those two move with the delay and hide it. Anchoring t0 to the
     * schedule leaves the delay where it belongs — in the tail percentiles.
     */
    public final long scheduledNs;

    public OrderRequest(
        long userId,
        int marketId,
        String orderType,
        String orderSide,
        double price,
        double quantity,
        double totalPrice,
        long enqueueTimeNs,
        long scheduledNs
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
        this.scheduledNs = scheduledNs;
    }
}
