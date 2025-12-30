package com.match.loadtest;

import com.match.domain.FixedPoint;

/**
 * Order request for the lock-free queue.
 * Uses primitive types for zero-allocation encoding.
 */
public final class OrderRequest {

    public final long userId;
    public final int marketId;
    public final String orderType;
    public final String orderSide;
    public final long price;        // Fixed-point (8 decimals)
    public final long quantity;     // Fixed-point (8 decimals)
    public final long totalPrice;   // Fixed-point (8 decimals)
    public final long enqueueTimeNs;

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
