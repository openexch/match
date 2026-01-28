package com.match.infrastructure.gateway.state;

import java.util.List;

/**
 * Interface for candle (OHLCV) data providers.
 * Designed for swap-ability: InMemoryCandleProvider now, TimescaleDbCandleProvider later.
 */
public interface CandleProvider {

    /**
     * Process a trade execution and update candle state.
     * Called from egress polling thread (single writer).
     *
     * @param marketId    market ID
     * @param price       execution price
     * @param quantity    execution quantity
     * @param timestampMs trade timestamp in epoch milliseconds
     */
    void onTrade(int marketId, double price, double quantity, long timestampMs);

    /**
     * Get historical candles for a market and interval.
     * Returns copies for thread safety — most recent last (ascending time order).
     *
     * @param marketId market ID
     * @param interval interval string (1m, 5m, 15m, 1h, 4h, 1d)
     * @param limit    max number of candles to return
     * @return list of candles in ascending time order
     */
    List<Candle> getCandles(int marketId, String interval, int limit);

    /**
     * Get the current (in-progress) candle for a market and interval.
     * Returns a copy for thread safety, or null if no data exists.
     *
     * @param marketId market ID
     * @param interval interval string (1m, 5m, 15m, 1h, 4h, 1d)
     * @return current candle copy, or null
     */
    Candle getCurrentCandle(int marketId, String interval);
}
