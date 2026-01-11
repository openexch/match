package com.match.infrastructure.gateway.state;

/**
 * Data class for open order entries stored in the gateway.
 * Tracks order lifecycle from NEW -> PARTIALLY_FILLED -> FILLED/CANCELLED.
 */
public class OpenOrder {
    public long orderId;
    public long userId;
    public double price;
    public double remainingQuantity;
    public double filledQuantity;
    public String side;  // "BID" or "ASK"
    public String status;  // "NEW", "PARTIALLY_FILLED"
    public long timestamp;

    public void reset() {
        orderId = 0;
        userId = 0;
        price = 0;
        remainingQuantity = 0;
        filledQuantity = 0;
        side = null;
        status = null;
        timestamp = 0;
    }

    public void copyFrom(OpenOrder other) {
        this.orderId = other.orderId;
        this.userId = other.userId;
        this.price = other.price;
        this.remainingQuantity = other.remainingQuantity;
        this.filledQuantity = other.filledQuantity;
        this.side = other.side;
        this.status = other.status;
        this.timestamp = other.timestamp;
    }

    public boolean isTerminal() {
        return "FILLED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status);
    }
}
