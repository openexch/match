// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.MarketInfo;
import com.match.domain.MarketPriceRules;

/**
 * Market configuration for the matching engine.
 * Extends {@link MarketInfo} with engine-specific price ranges and tick sizes.
 *
 * <p>The three price numbers (band + tick) are sourced from the shared
 * {@link MarketPriceRules} in {@code match-common} so the engine and every
 * discovery surface (e.g. OMS {@code GET /api/v1/markets}) read one set of
 * numbers and can never drift.
 *
 * Price ranges are based on 52-week historical data with ~30-50% buffer on each side.
 */
public final class MarketConfig {

    public final int marketId;
    public final String symbol;
    public final long basePrice;   // Fixed-point (8 decimals); == MarketPriceRules.minPrice
    public final long maxPrice;    // Fixed-point (8 decimals)
    public final long tickSize;    // Fixed-point (8 decimals)

    public MarketConfig(MarketInfo info, MarketPriceRules rules) {
        this.marketId = info.id();
        this.symbol = info.symbol();
        this.basePrice = rules.minPrice();
        this.maxPrice = rules.maxPrice();
        this.tickSize = rules.tickSize();
    }

    /**
     * Calculate number of price levels for this market.
     */
    public int getPriceLevels() {
        return (int) ((maxPrice - basePrice) / tickSize) + 1;
    }

    // Market configurations based on 52-week price data with buffer
    // Memory-optimized tick sizes to fit in 2GB heap

    // BTC: 52-week range $74,437 - $126,198 -> $50,000 - $150,000
    public static final MarketConfig BTC_USD = new MarketConfig(
        MarketInfo.BTC_USD, MarketPriceRules.BTC_USD);      // 100K levels (~410MB)

    // ETH: 52-week range $1,387 - $4,954 -> $1,000 - $10,000
    public static final MarketConfig ETH_USD = new MarketConfig(
        MarketInfo.ETH_USD, MarketPriceRules.ETH_USD);      // 18K levels (~74MB)

    // SOL: 52-week range $96.59 - $294.33 -> $50 - $500
    public static final MarketConfig SOL_USD = new MarketConfig(
        MarketInfo.SOL_USD, MarketPriceRules.SOL_USD);      // 9K levels (~37MB)

    // XRP: 52-week range $1.53 - $3.65 -> $0.50 - $10.00
    public static final MarketConfig XRP_USD = new MarketConfig(
        MarketInfo.XRP_USD, MarketPriceRules.XRP_USD);      // 9.5K levels (~39MB)

    // DOGE: 52-week range $0.1148 - $0.4335 -> $0.05 - $1.00
    public static final MarketConfig DOGE_USD = new MarketConfig(
        MarketInfo.DOGE_USD, MarketPriceRules.DOGE_USD);    // 9.5K levels (~39MB)

    public static final MarketConfig[] ALL_MARKETS = {
        BTC_USD, ETH_USD, SOL_USD, XRP_USD, DOGE_USD
    };

    /**
     * Get market config by ID.
     */
    public static MarketConfig getById(int marketId) {
        for (MarketConfig config : ALL_MARKETS) {
            if (config.marketId == marketId) {
                return config;
            }
        }
        return null;
    }

    /**
     * Get market config by symbol.
     */
    public static MarketConfig getBySymbol(String symbol) {
        for (MarketConfig config : ALL_MARKETS) {
            if (config.symbol.equals(symbol)) {
                return config;
            }
        }
        return null;
    }
}
