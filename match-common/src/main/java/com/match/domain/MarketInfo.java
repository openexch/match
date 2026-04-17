package com.match.domain;

/**
 * Canonical market identity — single source of truth for market IDs, symbols, and assets.
 * Used by match engine, OMS, and gateway to avoid duplicating market definitions.
 */
public record MarketInfo(int id, String symbol, String baseAsset, String quoteAsset) {

    public static final MarketInfo BTC_USD  = new MarketInfo(1, "BTC-USD",  "BTC",  "USD");
    public static final MarketInfo ETH_USD  = new MarketInfo(2, "ETH-USD",  "ETH",  "USD");
    public static final MarketInfo SOL_USD  = new MarketInfo(3, "SOL-USD",  "SOL",  "USD");
    public static final MarketInfo XRP_USD  = new MarketInfo(4, "XRP-USD",  "XRP",  "USD");
    public static final MarketInfo DOGE_USD = new MarketInfo(5, "DOGE-USD", "DOGE", "USD");

    public static final MarketInfo[] ALL = { BTC_USD, ETH_USD, SOL_USD, XRP_USD, DOGE_USD };

    private static final MarketInfo[] BY_ID = new MarketInfo[6];
    static {
        for (MarketInfo m : ALL) {
            BY_ID[m.id] = m;
        }
    }

    public static MarketInfo fromId(int id) {
        if (id >= 1 && id < BY_ID.length && BY_ID[id] != null) {
            return BY_ID[id];
        }
        return null;
    }

    public static int marketCount() {
        return ALL.length;
    }
}
