// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.state;

/**
 * Data class for aggregated trade entries stored in the gateway.
 * Trades at the same price with the same taker side within a flush
 * interval are combined.
 */
public class AggregatedTrade {
    public double price;
    public double quantity;
    public int tradeCount;
    /** Taker (aggressor) side: "BUY", "SELL", or null when unknown (pre-v5 upstream). */
    public String side;
    public long timestamp;

    public void reset() {
        price = 0;
        quantity = 0;
        tradeCount = 0;
        side = null;
        timestamp = 0;
    }

    public void copyFrom(AggregatedTrade other) {
        this.price = other.price;
        this.quantity = other.quantity;
        this.tradeCount = other.tradeCount;
        this.side = other.side;
        this.timestamp = other.timestamp;
    }
}
