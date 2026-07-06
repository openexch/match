// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.persistence;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * TradeWriter behavior with a fake sink (no database):
 * queue -> batch fidelity, overflow drops, transient-failure retention,
 * statement-failure batch dropping. The hot-path offer() must never block.
 */
public class TradeWriterTest {

    private MarketDataDb db;
    private TradeWriter writer;

    @Before
    public void setUp() {
        // Pool is never connected in these tests (initializationFailTimeout=-1)
        db = MarketDataDb.create(new MarketDataDbConfig(
                "jdbc:postgresql://127.0.0.1:1/never", "none", "none", true));
    }

    @After
    public void tearDown() {
        if (writer != null) {
            writer.close();
        }
        db.close();
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    @Test
    public void testOfferedRows_ReachSinkWithFieldFidelity() {
        List<TradeWriter.TradeRow> received = new CopyOnWriteArrayList<>();
        writer = new TradeWriter(db, received::addAll, 1024, 512, 10, false);
        writer.start();

        assertTrue(writer.offer(1, 100.5, 2.25, 3, 1_700_000_000_123L, Boolean.TRUE));
        assertTrue(writer.offer(2, 0.0001, 1_000_000, 1, 1_700_000_000_456L, Boolean.FALSE));
        // null-side row: written before the cluster carried takerSide (pre-v5 upstream)
        assertTrue(writer.offer(3, 42.0, 1.5, 2, 1_700_000_000_789L, null));

        await("rows to reach the sink", () -> received.size() == 3);
        TradeWriter.TradeRow first = received.get(0);
        assertEquals(1, first.marketId());
        assertEquals(100.5, first.price(), 0);
        assertEquals(2.25, first.quantity(), 0);
        assertEquals(3, first.tradeCount());
        assertEquals(1_700_000_000_123L, first.tsMs());
        assertEquals(Boolean.TRUE, first.takerIsBuy());
        assertEquals(Boolean.FALSE, received.get(1).takerIsBuy());
        assertNull("unknown taker side must persist as null", received.get(2).takerIsBuy());
        assertEquals(3, db.tradesWritten.get());
        assertTrue(db.batchFlushes.get() >= 1);
        assertTrue(db.isAvailable());
    }

    @Test
    public void testQueueOverflow_DropsAndCounts_NeverBlocks() {
        // Writer thread NOT started: the queue can only fill up
        writer = new TradeWriter(db, batch -> {}, 4, 512, 10, false);

        for (int i = 0; i < 4; i++) {
            assertTrue(writer.offer(1, 100, 1, 1, i, Boolean.TRUE));
        }
        long before = System.nanoTime();
        assertFalse(writer.offer(1, 100, 1, 1, 99, Boolean.TRUE));
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertEquals(1, db.tradesDropped.get());
        assertEquals(4, writer.queueDepth());
        assertTrue("offer must not block (took " + elapsedMs + "ms)", elapsedMs < 100);
    }

    @Test
    public void testTransientFailure_RetainsBatchAndRetries() {
        AtomicInteger attempts = new AtomicInteger();
        List<TradeWriter.TradeRow> received = new CopyOnWriteArrayList<>();
        TradeWriter.BatchSink sink = batch -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new SQLTransientException("connect refused");
            }
            received.addAll(batch);
        };
        writer = new TradeWriter(db, sink, 1024, 512, 10, false);
        writer.start();

        writer.offer(1, 100, 1, 1, 1000, Boolean.TRUE);
        writer.offer(1, 101, 1, 1, 2000, Boolean.FALSE);

        await("batch to survive transient failures", () -> received.size() == 2);
        assertTrue("no rows may be lost on transient failures", attempts.get() >= 3);
        assertEquals(0, db.writeErrors.get());
        assertEquals(2, db.tradesWritten.get());
        assertTrue("recovery must mark the db up", db.isAvailable());
    }

    @Test
    public void testStatementFailure_DropsBatchAfterTwoAttempts_ThenContinues() {
        AtomicInteger attempts = new AtomicInteger();
        List<TradeWriter.TradeRow> received = new ArrayList<>();
        TradeWriter.BatchSink sink = batch -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new SQLException("value out of range");
            }
            synchronized (received) {
                received.addAll(batch);
            }
        };
        writer = new TradeWriter(db, sink, 1024, 512, 10, false);
        writer.start();

        writer.offer(1, 100, 1, 1, 1000, Boolean.TRUE); // poison batch: fails twice, then dropped

        await("poison batch to be dropped", () -> db.writeErrors.get() == 1);

        writer.offer(1, 200, 1, 1, 2000, Boolean.TRUE); // writer must still be alive
        await("subsequent batch to be written", () -> {
            synchronized (received) {
                return received.size() == 1;
            }
        });
        synchronized (received) {
            assertEquals(200, received.get(0).price(), 0);
        }
        assertEquals(1, db.tradesWritten.get());
    }

    @Test
    public void testClose_DrainsPendingRows() {
        List<TradeWriter.TradeRow> received = new CopyOnWriteArrayList<>();
        writer = new TradeWriter(db, received::addAll, 1024, 512, 60_000, false); // long linger
        writer.start();

        writer.offer(1, 100, 1, 1, 1000, Boolean.TRUE);
        writer.close(); // shutdown flushes regardless of linger
        assertEquals(1, received.size());
    }
}
