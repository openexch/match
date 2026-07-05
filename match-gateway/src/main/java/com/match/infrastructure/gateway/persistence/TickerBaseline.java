// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

/**
 * Rolling 24h ticker figures computed from the 1m candle aggregate.
 * open24h is the close of the last bucket at/before the window start
 * (or the first open inside the window for markets younger than 24h).
 * Fields are 0 when the database has no data for the market.
 */
public record TickerBaseline(double open24h, double high24h, double low24h,
                             double quoteVolume24h, double lastClose, long asOfMs) {

    public boolean hasData() {
        return lastClose > 0;
    }
}
