package com.match.infrastructure.gateway.state;

/**
 * Data class for aggregated trade entries stored in the gateway.
 * Trades at the same price within a flush interval are combined.
 */
public class AggregatedTrade {
    public double price;
    public double quantity;
    public int tradeCount;
    public int buyCount;
    public int sellCount;
    public long timestamp;

    public void reset() {
        price = 0;
        quantity = 0;
        tradeCount = 0;
        buyCount = 0;
        sellCount = 0;
        timestamp = 0;
    }

    public void copyFrom(AggregatedTrade other) {
        this.price = other.price;
        this.quantity = other.quantity;
        this.tradeCount = other.tradeCount;
        this.buyCount = other.buyCount;
        this.sellCount = other.sellCount;
        this.timestamp = other.timestamp;
    }
}
