// SPDX-License-Identifier: Apache-2.0
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
    default void onTrade(int marketId, double price, double quantity, long timestampMs) {
        onTrade(marketId, price, quantity, 1, timestampMs);
    }

    /**
     * Process an aggregated trade and update candle state.
     * The egress stream carries price-aggregated entries; tradeCount is the
     * number of underlying executions, so candle tradeCount stays consistent
     * with the persisted trades (which sum trade_count in the database).
     *
     * @param marketId    market ID
     * @param price       execution price
     * @param quantity    aggregated execution quantity
     * @param tradeCount  number of underlying executions (>= 1)
     * @param timestampMs trade timestamp in epoch milliseconds
     */
    void onTrade(int marketId, double price, double quantity, int tradeCount, long timestampMs);

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
