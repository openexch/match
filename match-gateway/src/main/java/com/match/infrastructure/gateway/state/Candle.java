package com.match.infrastructure.gateway.state;

/**
 * Data class for OHLCV candle entries.
 * Immutable once created — copies are returned to readers for thread safety.
 */
public class Candle {
    public long time;       // bucket start in epoch seconds
    public double open;
    public double high;
    public double low;
    public double close;
    public double volume;
    public int tradeCount;
    public int marketId;

    public void copyFrom(Candle other) {
        this.time = other.time;
        this.open = other.open;
        this.high = other.high;
        this.low = other.low;
        this.close = other.close;
        this.volume = other.volume;
        this.tradeCount = other.tradeCount;
        this.marketId = other.marketId;
    }

    public void reset() {
        time = 0;
        open = 0;
        high = 0;
        low = 0;
        close = 0;
        volume = 0;
        tradeCount = 0;
        marketId = 0;
    }
}
