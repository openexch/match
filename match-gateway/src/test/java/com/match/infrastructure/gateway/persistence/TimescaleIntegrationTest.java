// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.infrastructure.gateway.state.Candle;
import com.match.infrastructure.gateway.state.InMemoryCandleProvider;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration test against a real TimescaleDB. Skipped unless
 * MARKET_PG_TEST_URL is set (CI runs a timescale/timescaledb service; on a
 * dev box: MARKET_PG_TEST_URL='jdbc:postgresql://localhost:5432/marketdata?user=market&password=...').
 *
 * The core assertion: an identical trade stream fed to the database (candles
 * derived by continuous aggregates) and to InMemoryCandleProvider (candles
 * derived in Java) produces IDENTICAL candles for all six intervals. That
 * equivalence is what makes the DB-first read path a drop-in replacement for
 * the ring buffers.
 */
public class TimescaleIntegrationTest {

    private record TestTrade(int marketId, double price, double quantity, int tradeCount, long tsMs,
                             Boolean takerIsBuy) {}

    private MarketDataDb db;
    private TimescaleReader reader;
    private TradeWriter writer;

    @Before
    public void setUp() {
        String url = System.getenv("MARKET_PG_TEST_URL");
        Assume.assumeTrue("set MARKET_PG_TEST_URL to run TimescaleDB integration tests", url != null);
        db = MarketDataDb.create(new MarketDataDbConfig(url,
                System.getenv().getOrDefault("MARKET_PG_TEST_USER", null),
                System.getenv().getOrDefault("MARKET_PG_TEST_PASSWORD", null), true));
        reader = new TimescaleReader(db);
        assertTrue("schema bootstrap must succeed", db.ensureSchema());
        assertTrue("schema bootstrap must be idempotent", db.ensureSchema());
        cleanSlate();
    }

    @After
    public void tearDown() {
        if (writer != null) {
            writer.close();
        }
        if (db != null) {
            db.close();
        }
    }

    /** Deterministic reruns: wipe raw trades and re-materialize every cagg over the full range. */
    private void cleanSlate() {
        execute("DELETE FROM trades");
        refreshAllCaggs();
    }

    private void refreshAllCaggs() {
        // Bottom-up: a hierarchical cagg (e.g. candles_1d) rolls up the level below it, so lower
        // intervals must be materialized first.
        for (String view : new String[]{"candles_1m", "candles_5m", "candles_15m",
                "candles_1h", "candles_4h", "candles_1d"}) {
            refreshCaggWithRetry(view);
        }
    }

    /**
     * {@code refresh_continuous_aggregate} intermittently fails in CI (match#138) — a transient
     * lock / "refresh already in progress" / not-yet-committed lower-level materialization right
     * after schema creation. It is safely idempotent, so retry with a short backoff before failing.
     */
    private void refreshCaggWithRetry(String view) {
        String sql = "CALL refresh_continuous_aggregate('" + view + "', NULL, NULL)";
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                execute(sql);
                return;
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(300L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted refreshing " + view, ie);
                }
            }
        }
        throw new RuntimeException("refresh_continuous_aggregate('" + view + "') failed after 5 attempts", last);
    }

    private void execute(String sql) {
        try (Connection conn = db.getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(sql + " failed", e);
        }
    }

    /**
     * Deterministic trade set spanning 1m/5m/15m/1h/4h/1d boundaries,
     * anchored at UTC midnight two days ago (inside retention, outside the
     * 24h ticker window). Strictly increasing timestamps per market so
     * first()/last() tie-breaking cannot diverge from arrival order.
     */
    private static List<TestTrade> boundaryTrades() {
        long base = ((System.currentTimeMillis() - 2 * 86_400_000L) / 86_400_000L) * 86_400_000L;
        long[] offsets = {
                0, 1, 30_000, 59_999,                    // first 1m bucket incl. boundary-1ms
                60_000, 61_000,                          // next 1m bucket
                299_999, 300_000,                        // 5m boundary
                899_999, 900_000,                        // 15m boundary
                3_599_999, 3_600_000,                    // 1h boundary
                14_399_999, 14_400_000,                  // 4h boundary
                86_399_999, 86_400_000, 86_400_123       // 1d boundary
        };
        List<TestTrade> trades = new ArrayList<>();
        double price = 100.0;
        for (int i = 0; i < offsets.length; i++) {
            price += (i % 3 == 0) ? 7.5 : -3.25;         // ups and downs for high/low coverage
            // Mix of buy-taker / sell-taker / unknown (pre-v5) sides
            Boolean side = (i % 3 == 0) ? Boolean.TRUE : (i % 3 == 1) ? Boolean.FALSE : null;
            trades.add(new TestTrade(1, price, 0.5 + (i % 4) * 0.25, 1 + (i % 3), base + offsets[i], side));
        }
        // Second market: sparse subset, must not bleed into market 1's candles
        trades.add(new TestTrade(2, 55.5, 2.0, 2, base + 45_000, Boolean.TRUE));
        trades.add(new TestTrade(2, 54.0, 1.0, 1, base + 61_500, null));
        return trades;
    }

    @Test
    public void testDbCandles_EqualInMemoryCandles_AllIntervals() throws Exception {
        List<TestTrade> trades = boundaryTrades();
        long windowStartSec = trades.get(0).tsMs() / 1000;

        // Feed the database through the real writer (exercises the batch insert path)
        writer = new TradeWriter(db);
        writer.start();
        InMemoryCandleProvider memory = new InMemoryCandleProvider();
        for (TestTrade t : trades) {
            assertTrue(writer.offer(t.marketId(), t.price(), t.quantity(), t.tradeCount(), t.tsMs(),
                    t.takerIsBuy()));
            memory.onTrade(t.marketId(), t.price(), t.quantity(), t.tradeCount(), t.tsMs());
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (db.tradesWritten.get() < trades.size()) {
            assertTrue("writer must persist all trades", System.currentTimeMillis() < deadline);
            Thread.sleep(20);
        }
        refreshAllCaggs(); // our trades are in the past — materialize them deterministically

        for (int marketId : new int[]{1, 2}) {
            for (String interval : InMemoryCandleProvider.INTERVALS) {
                List<Candle> expected = memory.getCandles(marketId, interval, 500);
                List<Candle> actual = reader.getCandles(marketId, interval, 500).stream()
                        .filter(c -> c.time >= windowStartSec)
                        .toList();
                String ctx = "market=" + marketId + " interval=" + interval;
                assertEquals(ctx + " candle count", expected.size(), actual.size());
                for (int i = 0; i < expected.size(); i++) {
                    Candle e = expected.get(i);
                    Candle a = actual.get(i);
                    String c = ctx + " candle[" + i + "] ";
                    assertEquals(c + "time", e.time, a.time);
                    assertEquals(c + "open", e.open, a.open, 1e-9);
                    assertEquals(c + "high", e.high, a.high, 1e-9);
                    assertEquals(c + "low", e.low, a.low, 1e-9);
                    assertEquals(c + "close", e.close, a.close, 1e-9);
                    assertEquals(c + "volume", e.volume, a.volume, 1e-9);
                    assertEquals(c + "tradeCount", e.tradeCount, a.tradeCount);
                }
            }
        }
    }

    @Test
    public void testRecentTrades_NewestFirst_AndMarketFiltered() throws Exception {
        writer = new TradeWriter(db);
        writer.start();
        long now = System.currentTimeMillis();
        writer.offer(3, 10.0, 1.0, 1, now - 3_000, Boolean.TRUE);
        writer.offer(3, 11.0, 2.0, 1, now - 2_000, Boolean.FALSE);
        writer.offer(4, 99.0, 1.0, 1, now - 1_000, null); // pre-takerSide row
        long deadline = System.currentTimeMillis() + 10_000;
        while (db.tradesWritten.get() < 3) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.sleep(20);
        }

        List<TimescaleReader.TapeRow> all = reader.getRecentTrades(10, 0);
        assertEquals(3, all.size());
        assertEquals(99.0, all.get(0).price(), 0); // newest first

        // taker_side round-trip: TRUE / FALSE / SQL NULL -> Boolean null
        assertNull("null taker side must read back as null", all.get(0).takerIsBuy());
        assertEquals(Boolean.FALSE, all.get(1).takerIsBuy());
        assertEquals(Boolean.TRUE, all.get(2).takerIsBuy());

        List<TimescaleReader.TapeRow> m3 = reader.getRecentTrades(10, 3);
        assertEquals(2, m3.size());
        assertEquals(11.0, m3.get(0).price(), 0);
        assertEquals(10.0, m3.get(1).price(), 0);
    }

    @Test
    public void test24hBaseline_RollingWindowFigures() throws Exception {
        writer = new TradeWriter(db);
        writer.start();
        long now = System.currentTimeMillis();
        // Old trade (outside 24h window) sets open24h; recent trades set high/low/volume
        writer.offer(5, 200.0, 1.0, 1, now - 25 * 3_600_000L, Boolean.TRUE);
        writer.offer(5, 210.0, 2.0, 1, now - 120_000, Boolean.TRUE);
        writer.offer(5, 190.0, 1.0, 1, now - 60_000, Boolean.FALSE);
        long deadline = System.currentTimeMillis() + 10_000;
        while (db.tradesWritten.get() < 3) {
            assertTrue(System.currentTimeMillis() < deadline);
            Thread.sleep(20);
        }
        refreshAllCaggs();

        TickerBaseline baseline = reader.get24hBaseline(5);
        assertTrue(baseline.hasData());
        assertEquals(200.0, baseline.open24h(), 1e-9);   // close of the pre-window bucket
        assertEquals(210.0, baseline.high24h(), 1e-9);
        assertEquals(190.0, baseline.low24h(), 1e-9);
        assertEquals(210.0 * 2 + 190.0, baseline.quoteVolume24h(), 1e-9);
        assertEquals(190.0, baseline.lastClose(), 1e-9);
    }

    @Test
    public void testPolicies_AreRegistered() throws Exception {
        int caggPolicies = 0;
        int retentionPolicies = 0;
        try (Connection conn = db.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT proc_name, count(*) AS n FROM timescaledb_information.jobs "
                             + "WHERE proc_name IN ('policy_refresh_continuous_aggregate', 'policy_retention') "
                             + "GROUP BY proc_name")) {
            while (rs.next()) {
                if ("policy_refresh_continuous_aggregate".equals(rs.getString("proc_name"))) {
                    caggPolicies = rs.getInt("n");
                } else {
                    retentionPolicies = rs.getInt("n");
                }
            }
        }
        assertEquals("one refresh policy per interval", 6, caggPolicies);
        assertEquals("retention policy on trades", 1, retentionPolicies);
    }
}
