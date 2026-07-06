// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.infrastructure.gateway.state.Candle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Synchronous JDBC reads against the candle aggregates and the trades
 * hypertable. Only ever called from the persistence read/ticker threads —
 * never from the egress thread or a Netty event loop.
 */
public class TimescaleReader {

    /** Whitelist: interval string -> continuous aggregate. Never concatenate user input. */
    private static final Map<String, String> VIEW_BY_INTERVAL = Map.of(
            "1m", "candles_1m",
            "5m", "candles_5m",
            "15m", "candles_15m",
            "1h", "candles_1h",
            "4h", "candles_4h",
            "1d", "candles_1d");

    /**
     * One row of the recent-trades tape (newest first). {@code takerIsBuy}:
     * TRUE = taker bought, FALSE = taker sold, null = row persisted before
     * the cluster carried takerSide (taker_side IS NULL).
     */
    public record TapeRow(int marketId, double price, double quantity, int tradeCount, long tsMs,
                          Boolean takerIsBuy) {}

    private final MarketDataDb db;

    public TimescaleReader(MarketDataDb db) {
        this.db = db;
    }

    public static boolean supportsInterval(String interval) {
        return VIEW_BY_INTERVAL.containsKey(interval);
    }

    /** Most recent {@code limit} candles in ascending time order (the API contract). */
    public List<Candle> getCandles(int marketId, String interval, int limit) throws SQLException {
        String view = VIEW_BY_INTERVAL.get(interval);
        if (view == null) {
            return List.of();
        }
        String sql = "SELECT extract(epoch FROM bucket)::bigint AS time, open, high, low, close,"
                + " volume, trade_count FROM " + view
                + " WHERE market_id = ? ORDER BY bucket DESC LIMIT ?";
        List<Candle> candles = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, marketId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candle c = new Candle();
                    c.time = rs.getLong("time");
                    c.open = rs.getDouble("open");
                    c.high = rs.getDouble("high");
                    c.low = rs.getDouble("low");
                    c.close = rs.getDouble("close");
                    c.volume = rs.getDouble("volume");
                    c.tradeCount = (int) rs.getLong("trade_count");
                    c.marketId = marketId;
                    candles.add(c);
                }
            }
        }
        Collections.reverse(candles);
        return candles;
    }

    /** Most recent {@code limit} trades, newest first. marketId 0 = all markets. */
    public List<TapeRow> getRecentTrades(int limit, int marketId) throws SQLException {
        String sql = "SELECT (extract(epoch FROM time) * 1000)::bigint AS ts, market_id, price,"
                + " quantity, trade_count, taker_side FROM trades"
                + (marketId > 0 ? " WHERE market_id = ?" : "")
                + " ORDER BY time DESC LIMIT ?";
        List<TapeRow> rows = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (marketId > 0) {
                ps.setInt(idx++, marketId);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    boolean b = rs.getBoolean("taker_side");
                    Boolean takerIsBuy = rs.wasNull() ? null : b;
                    rows.add(new TapeRow(rs.getInt("market_id"), rs.getDouble("price"),
                            rs.getDouble("quantity"), rs.getInt("trade_count"), rs.getLong("ts"),
                            takerIsBuy));
                }
            }
        }
        return rows;
    }

    /** Rolling 24h ticker figures from the 1m aggregate (real-time: includes the current minute). */
    public TickerBaseline get24hBaseline(int marketId) throws SQLException {
        String sql = """
                WITH w AS (
                    SELECT max(high) AS high24h, min(low) AS low24h,
                           coalesce(sum(quote_volume), 0) AS qvol
                    FROM candles_1m
                    WHERE market_id = ? AND bucket >= now() - INTERVAL '24 hours'
                ), o AS (
                    SELECT close AS open24h FROM candles_1m
                    WHERE market_id = ? AND bucket <= now() - INTERVAL '24 hours'
                    ORDER BY bucket DESC LIMIT 1
                ), f AS (
                    SELECT open AS first_open FROM candles_1m
                    WHERE market_id = ? AND bucket > now() - INTERVAL '24 hours'
                    ORDER BY bucket ASC LIMIT 1
                ), l AS (
                    SELECT close AS last_close FROM candles_1m
                    WHERE market_id = ? ORDER BY bucket DESC LIMIT 1
                )
                SELECT w.high24h, w.low24h, w.qvol,
                       coalesce(o.open24h, f.first_open) AS open24h, l.last_close
                FROM w LEFT JOIN o ON true LEFT JOIN f ON true LEFT JOIN l ON true
                """;
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                ps.setInt(i, marketId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new TickerBaseline(0, 0, 0, 0, 0, System.currentTimeMillis());
                }
                return new TickerBaseline(
                        rs.getDouble("open24h"),
                        rs.getDouble("high24h"),
                        rs.getDouble("low24h"),
                        rs.getDouble("qvol"),
                        rs.getDouble("last_close"),
                        System.currentTimeMillis());
            }
        }
    }
}
