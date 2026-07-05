// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import com.match.infrastructure.gateway.state.Candle;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * DB-first read behavior: memory fallback when the database is not ready or
 * errors, short JSON cache when it is healthy.
 */
public class MarketDataReadServiceTest {

    private MarketDataDb db;
    private MarketDataReadService service;
    private final AtomicInteger readerCalls = new AtomicInteger();
    private volatile boolean readerFails = false;

    private class FakeReader extends TimescaleReader {
        FakeReader() {
            super(db);
        }

        @Override
        public List<Candle> getCandles(int marketId, String interval, int limit) throws SQLException {
            readerCalls.incrementAndGet();
            if (readerFails) {
                throw new SQLException("boom");
            }
            List<Candle> candles = new ArrayList<>();
            Candle c = new Candle();
            c.time = 60;
            c.open = 1;
            c.high = 2;
            c.low = 0.5;
            c.close = 1.5;
            c.volume = 10;
            c.tradeCount = 4;
            c.marketId = marketId;
            candles.add(c);
            return candles;
        }
    }

    @Before
    public void setUp() {
        db = MarketDataDb.create(new MarketDataDbConfig(
                "jdbc:postgresql://127.0.0.1:1/never", "none", "none", true));
        service = new MarketDataReadService(db, new FakeReader());
    }

    @After
    public void tearDown() {
        service.close();
        db.close();
    }

    @Test
    public void testDbNotReady_FallsBackImmediately_WithoutQuerying() {
        String json = service.candleHistoryJson(1, "1m", 200, () -> "FALLBACK").join();
        assertEquals("FALLBACK", json);
        assertEquals(0, readerCalls.get());
        assertEquals(1, db.readFallbacks.get());
    }

    @Test
    public void testUnknownInterval_FallsBack_EvenWhenReady() {
        db.markUp();
        db.markSchemaReadyForTests();
        String json = service.candleHistoryJson(1, "7w", 200, () -> "FALLBACK").join();
        assertEquals("FALLBACK", json);
        assertEquals(0, readerCalls.get());
    }

    @Test
    public void testHealthyDb_ServesDbJson_AndCachesWithinTtl() {
        db.markUp();
        db.markSchemaReadyForTests();

        String json = service.candleHistoryJson(1, "1m", 200, () -> "FALLBACK").join();
        assertTrue(json.contains("\"type\":\"CANDLE_HISTORY\""));
        assertTrue(json.contains("\"market\":\"BTC-USD\""));
        assertTrue(json.contains("\"interval\":\"1m\""));
        assertTrue(json.contains("\"time\":60"));
        assertEquals(1, readerCalls.get());

        // Second identical request inside the TTL: cache hit, no second query
        String cached = service.candleHistoryJson(1, "1m", 200, () -> "FALLBACK").join();
        assertEquals(json, cached);
        assertEquals(1, readerCalls.get());

        // Different key -> its own query
        service.candleHistoryJson(2, "1m", 200, () -> "FALLBACK").join();
        assertEquals(2, readerCalls.get());
    }

    @Test
    public void testQueryFailure_FallsBackAndMarksDown() {
        db.markUp();
        db.markSchemaReadyForTests();
        readerFails = true;

        String json = service.candleHistoryJson(1, "1m", 200, () -> "FALLBACK").join();
        assertEquals("FALLBACK", json);
        assertEquals(1, db.readFallbacks.get());
        assertFalse("query failure must mark the db down", db.isAvailable());
    }
}
