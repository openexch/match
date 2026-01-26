package com.match.application.engine;

import com.match.domain.FixedPoint;

/**
 * Market configuration for the matching engine.
 * Defines price ranges and tick sizes for each supported market.
 *
 * Price ranges are based on 52-week historical data with ~30-50% buffer on each side.
 */
public final class MarketConfig {

    public final int marketId;
    public final String symbol;
    public final long basePrice;   // Fixed-point (8 decimals)
    public final long maxPrice;    // Fixed-point (8 decimals)
    public final long tickSize;    // Fixed-point (8 decimals)

    public MarketConfig(int marketId, String symbol, double basePrice, double maxPrice, double tickSize) {
        this.marketId = marketId;
        this.symbol = symbol;
        this.basePrice = FixedPoint.fromDouble(basePrice);
        this.maxPrice = FixedPoint.fromDouble(maxPrice);
        this.tickSize = FixedPoint.fromDouble(tickSize);
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
        1, "BTC-USD", 50_000.0, 150_000.0, 1.0);      // 100K levels (~410MB)

    // ETH: 52-week range $1,387 - $4,954 -> $1,000 - $10,000
    public static final MarketConfig ETH_USD = new MarketConfig(
        2, "ETH-USD", 1_000.0, 10_000.0, 0.50);       // 18K levels (~74MB)

    // SOL: 52-week range $96.59 - $294.33 -> $50 - $500
    public static final MarketConfig SOL_USD = new MarketConfig(
        3, "SOL-USD", 50.0, 500.0, 0.05);             // 9K levels (~37MB)

    // XRP: 52-week range $1.53 - $3.65 -> $0.50 - $10.00
    public static final MarketConfig XRP_USD = new MarketConfig(
        4, "XRP-USD", 0.50, 10.0, 0.001);             // 9.5K levels (~39MB)

    // DOGE: 52-week range $0.1148 - $0.4335 -> $0.05 - $1.00
    public static final MarketConfig DOGE_USD = new MarketConfig(
        5, "DOGE-USD", 0.05, 1.0, 0.0001);            // 9.5K levels (~39MB)

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
