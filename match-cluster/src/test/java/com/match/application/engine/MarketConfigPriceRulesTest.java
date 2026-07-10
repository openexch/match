// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.MarketPriceRules;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pins the engine's per-market price band + tick ({@link MarketConfig}) to the shared
 * {@link MarketPriceRules} holder in match-common, and pins both to concrete fixed-point
 * values. Guards against drift in either direction once OMS serves the same numbers via
 * {@code GET /api/v1/markets} (match#64 pt2).
 */
public class MarketConfigPriceRulesTest {

    @Test
    public void everyMarketConfigMatchesSharedHolder() {
        assertEquals("market count must agree between MarketConfig and MarketPriceRules",
                MarketPriceRules.ALL.length, MarketConfig.ALL_MARKETS.length);

        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            MarketPriceRules rules = MarketPriceRules.forId(config.marketId);
            assertNotNull("no MarketPriceRules for marketId " + config.marketId, rules);
            assertEquals("minPrice/basePrice drift for marketId " + config.marketId,
                    rules.minPrice(), config.basePrice);
            assertEquals("maxPrice drift for marketId " + config.marketId,
                    rules.maxPrice(), config.maxPrice);
            assertEquals("tickSize drift for marketId " + config.marketId,
                    rules.tickSize(), config.tickSize);
        }
    }

    @Test
    public void sharedHolderPinsExactFixedPointValues() {
        // Extremes of the tick range: BTC (1.00) and DOGE (0.0001), 8dp fixed-point.
        assertPinned(MarketPriceRules.BTC_USD, 5_000_000_000_000L, 15_000_000_000_000L, 100_000_000L);
        assertPinned(MarketPriceRules.ETH_USD, 100_000_000_000L, 1_000_000_000_000L, 50_000_000L);
        assertPinned(MarketPriceRules.SOL_USD, 5_000_000_000L, 50_000_000_000L, 5_000_000L);
        assertPinned(MarketPriceRules.XRP_USD, 50_000_000L, 1_000_000_000L, 100_000L);
        assertPinned(MarketPriceRules.DOGE_USD, 5_000_000L, 100_000_000L, 10_000L);
    }

    private static void assertPinned(MarketPriceRules r, long minPrice, long maxPrice, long tickSize) {
        assertEquals("minPrice for marketId " + r.marketId(), minPrice, r.minPrice());
        assertEquals("maxPrice for marketId " + r.marketId(), maxPrice, r.maxPrice());
        assertEquals("tickSize for marketId " + r.marketId(), tickSize, r.tickSize());
    }
}
