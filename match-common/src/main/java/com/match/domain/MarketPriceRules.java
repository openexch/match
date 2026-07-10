// SPDX-License-Identifier: Apache-2.0
package com.match.domain;

/**
 * Per-market price band and tick size — the matching ENGINE's hard price rules,
 * shared out of {@code match-cluster} so the engine and every discovery surface
 * (e.g. OMS {@code GET /api/v1/markets}) read one set of numbers and can never drift.
 *
 * <p>These are the exact values enforced by the engine's {@code PriceRules}: a hard,
 * absolute {@code [minPrice, maxPrice]} band (inclusive) plus an on-tick check. An
 * off-tick or out-of-band limit price is rejected engine-side.
 *
 * <p>All three values are 8-decimal fixed-point longs (see {@link FixedPoint},
 * {@link FixedPoint#SCALE} == 8). {@code minPrice} corresponds to the engine's
 * {@code basePrice} (the inclusive lower bound).
 *
 * <p><b>Not</b> the OMS-side price collar (a separate, percentage-based risk band):
 * these are the engine's structural tick + absolute band, keyed the same way
 * {@link MarketInfo} enumerates markets.
 */
public record MarketPriceRules(int marketId, long minPrice, long maxPrice, long tickSize) {

    // Fixed-point (8dp) values mirror match-cluster MarketConfig exactly; the
    // trailing comments give the human-readable price for each.
    public static final MarketPriceRules BTC_USD = new MarketPriceRules(
        MarketInfo.BTC_USD.id(),  5_000_000_000_000L, 15_000_000_000_000L, 100_000_000L); // 50,000 – 150,000, tick 1.00
    public static final MarketPriceRules ETH_USD = new MarketPriceRules(
        MarketInfo.ETH_USD.id(),    100_000_000_000L,  1_000_000_000_000L,  50_000_000L); // 1,000 – 10,000, tick 0.50
    public static final MarketPriceRules SOL_USD = new MarketPriceRules(
        MarketInfo.SOL_USD.id(),      5_000_000_000L,     50_000_000_000L,   5_000_000L); // 50 – 500, tick 0.05
    public static final MarketPriceRules XRP_USD = new MarketPriceRules(
        MarketInfo.XRP_USD.id(),         50_000_000L,      1_000_000_000L,     100_000L); // 0.50 – 10.00, tick 0.001
    public static final MarketPriceRules DOGE_USD = new MarketPriceRules(
        MarketInfo.DOGE_USD.id(),         5_000_000L,        100_000_000L,      10_000L); // 0.05 – 1.00, tick 0.0001

    public static final MarketPriceRules[] ALL = { BTC_USD, ETH_USD, SOL_USD, XRP_USD, DOGE_USD };

    private static final MarketPriceRules[] BY_ID = new MarketPriceRules[6];
    static {
        for (MarketPriceRules r : ALL) {
            BY_ID[r.marketId] = r;
        }
    }

    /** @return the rules for {@code marketId}, or {@code null} if unknown. */
    public static MarketPriceRules forId(int marketId) {
        if (marketId >= 1 && marketId < BY_ID.length && BY_ID[marketId] != null) {
            return BY_ID[marketId];
        }
        return null;
    }
}
