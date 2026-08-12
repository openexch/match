// SPDX-License-Identifier: Apache-2.0
package com.match.application.publisher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * Integration test for MatchEventPublisher — tests real Disruptor pipeline.
 * Publishes events and verifies handler receives them.
 */
public class MatchEventPublisherTest {

    private MatchEventPublisher publisher;
    private CapturingHandler handler;

    private static final int MARKET_ID = 1;

    @Before
    public void setUp() {
        publisher = new MatchEventPublisher();
        handler = new CapturingHandler(MARKET_ID);
        publisher.initMarket(MARKET_ID, handler);
    }

    @After
    public void tearDown() {
        if (publisher.isRunning()) {
            publisher.shutdown();
        }
    }

    // ==================== Lifecycle ====================

    @Test
    public void isRunningBeforeStart() {
        assertFalse(publisher.isRunning());
    }

    @Test
    public void isRunningAfterStart() {
        publisher.start();
        assertTrue(publisher.isRunning());
    }

    @Test
    public void shutdownSetsNotRunning() {
        publisher.start();
        assertTrue(publisher.isRunning());
        publisher.shutdown();
        assertFalse(publisher.isRunning());
    }

    @Test
    public void doubleStartIsIdempotent() {
        publisher.start();
        publisher.start(); // Should not throw
        assertTrue(publisher.isRunning());
    }

    @Test
    public void shutdownWithoutStartIsNoOp() {
        publisher.shutdown(); // Should not throw
        assertFalse(publisher.isRunning());
    }

    @Test(expected = IllegalStateException.class)
    public void initMarketAfterStartThrows() {
        publisher.start();
        publisher.initMarket(99, new CapturingHandler(99));
    }

    // ==================== Buffer Capacity ====================

    @Test
    public void getRingBufferSize() {
        assertEquals(65536, publisher.getRingBufferSize());
    }

    @Test
    public void getRemainingCapacityAfterStart() {
        publisher.start();
        long capacity = publisher.getRemainingCapacity(MARKET_ID);
        assertTrue("Capacity should be positive", capacity > 0);
    }

    @Test
    public void getRemainingCapacityUnknownMarket() {
        publisher.start();
        assertEquals(-1, publisher.getRemainingCapacity(999));
    }

    // ==================== Snapshot Support ====================

    @Test
    public void tradeIdGeneratorDefaultStartsAt1() {
        assertEquals(1L, publisher.getTradeIdGenerator());
    }

    @Test
    public void setAndGetTradeIdGenerator() {
        publisher.setTradeIdGenerator(1000L);
        assertEquals(1000L, publisher.getTradeIdGenerator());
    }

    // ==================== Trade Execution Publishing ====================

    @Test
    public void publishTradeExecutionDeliveredToHandler() throws Exception {
        publisher.start();

        boolean result = publisher.publishTradeExecution(
            MARKET_ID, 12345L, 10L, 100L, 20L, 200L, 5000L, 250L, true, 0L, 0L, 0L);

        assertTrue("Publish should succeed", result);
        Thread.sleep(200);

        assertFalse("Handler should receive event", handler.events.isEmpty());
        CapturedEvent e = handler.events.get(0);
        assertEquals(PublishEventType.TRADE_EXECUTION, e.eventType);
        assertEquals(MARKET_ID, e.marketId);
        assertEquals(12345L, e.timestamp);
        assertEquals(5000L, e.price);
        assertEquals(250L, e.quantity);
        assertTrue(e.takerIsBuy);
    }

    @Test
    public void publishTradeExecutionToUnknownMarketReturnsFalse() {
        publisher.start();
        boolean result = publisher.publishTradeExecution(
            999, 1000L, 1L, 1L, 2L, 2L, 100L, 10L, true, 0L, 0L, 0L);
        assertFalse("Unknown market should return false", result);
    }

    @Test
    public void tradeIdGeneratorIncrementsOnPublish() throws Exception {
        publisher.start();
        long before = publisher.getTradeIdGenerator();

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L, 0L);
        Thread.sleep(200);

        // After one publish, generator should have incremented
        assertEquals(before + 1, publisher.getTradeIdGenerator());
    }

    // ==================== Order Book Level Update Publishing ====================

    @Test
    public void publishOrderBookLevelUpdateDeliveredToHandler() throws Exception {
        publisher.start();

        boolean result = publisher.publishOrderBookLevelUpdate(
            MARKET_ID, 2000L, 7500L, 120L, 5, true);

        assertTrue(result);
        Thread.sleep(200);

        assertFalse(handler.events.isEmpty());
        CapturedEvent e = handler.events.get(0);
        assertEquals(PublishEventType.ORDER_BOOK_UPDATE, e.eventType);
        assertEquals(MARKET_ID, e.marketId);
        assertEquals(2000L, e.timestamp);
    }

    @Test
    public void publishOrderBookLevelUpdateUnknownMarketReturnsFalse() {
        publisher.start();
        boolean result = publisher.publishOrderBookLevelUpdate(999, 100L, 50L, 10L, 1, true);
        assertFalse(result);
    }

    // ==================== Order Book Snapshot Publishing ====================

    @Test
    public void publishOrderBookSnapshotDeliveredToHandler() throws Exception {
        publisher.start();

        long[] bidPrices = {100L, 99L};
        long[] bidQuantities = {10L, 20L};
        int[] bidOrderCounts = {1, 2};
        long[] askPrices = {101L};
        long[] askQuantities = {15L};
        int[] askOrderCounts = {3};

        boolean result = publisher.publishOrderBookSnapshot(
            MARKET_ID, 3000L,
            bidPrices, bidQuantities, bidOrderCounts, 2,
            askPrices, askQuantities, askOrderCounts, 1);

        assertTrue(result);
        Thread.sleep(200);

        assertFalse(handler.events.isEmpty());
        CapturedEvent e = handler.events.get(0);
        assertEquals(PublishEventType.ORDER_BOOK_UPDATE, e.eventType);
        assertTrue(e.isSnapshot);
    }

    @Test
    public void publishOrderBookSnapshotUnknownMarketReturnsFalse() {
        publisher.start();
        boolean result = publisher.publishOrderBookSnapshot(
            999, 100L, new long[0], new long[0], new int[0], 0,
            new long[0], new long[0], new int[0], 0);
        assertFalse(result);
    }

    // ==================== Order Status Update Publishing ====================

    @Test
    public void publishOrderStatusUpdateDeliveredToHandler() throws Exception {
        publisher.start();

        boolean result = publisher.publishOrderStatusUpdate(
            MARKET_ID, 4000L, 55L, 300L, OrderStatusType.NEW, 500L, 0L, 4000L, true, 0L, 0, 0L);

        assertTrue(result);
        Thread.sleep(200);

        assertFalse(handler.events.isEmpty());
        CapturedEvent e = handler.events.get(0);
        assertEquals(PublishEventType.ORDER_STATUS_UPDATE, e.eventType);
        assertEquals(MARKET_ID, e.marketId);
    }

    @Test
    public void publishOrderStatusUpdateUnknownMarketReturnsFalse() {
        publisher.start();
        boolean result = publisher.publishOrderStatusUpdate(
            999, 100L, 1L, 1L, OrderStatusType.NEW, 100L, 0L, 50L, true, 0L, 0, 0L);
        assertFalse(result);
    }

    // ==================== Multiple Events ====================

    @Test
    public void multipleEventsDeliveredInOrder() throws Exception {
        publisher.start();

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L, 0L);
        publisher.publishOrderBookLevelUpdate(MARKET_ID, 200L, 60L, 20L, 3, false);
        publisher.publishOrderStatusUpdate(MARKET_ID, 300L, 1L, 1L, OrderStatusType.FILLED, 0L, 10L, 50L, true, 0L, 0, 0L);

        Thread.sleep(200);

        assertEquals("Should receive 3 events", 3, handler.events.size());
        assertEquals(PublishEventType.TRADE_EXECUTION, handler.events.get(0).eventType);
        assertEquals(PublishEventType.ORDER_BOOK_UPDATE, handler.events.get(1).eventType);
        assertEquals(PublishEventType.ORDER_STATUS_UPDATE, handler.events.get(2).eventType);
    }

    // ==================== Multi-Market ====================

    @Test
    public void multipleMarketsReceiveTheirOwnEvents() throws Exception {
        CapturingHandler handler2 = new CapturingHandler(2);
        publisher.initMarket(2, handler2);
        publisher.start();

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L, 0L);
        publisher.publishTradeExecution(2, 200L, 3L, 3L, 4L, 4L, 70L, 5L, false, 0L, 0L, 0L);

        Thread.sleep(200);

        assertEquals(1, handler.events.size());
        assertEquals(1, handler2.events.size());
        assertEquals(MARKET_ID, handler.events.get(0).marketId);
        assertEquals(2, handler2.events.get(0).marketId);
    }

    // ==================== C-1: claimSlotOrHalt slot claiming ====================

    /**
     * C-1: every publish method now claims its ring slot through claimSlotOrHalt, which halts the
     * node only when the ring stays full past the spin budget. This drives a burst that stays well
     * within ring capacity (the full-ring branch is never entered), asserting that (a) the helper
     * returns a valid slot for every publish so all events are delivered, and (b) the node is NOT
     * halted and the publisher keeps running.
     *
     * The halt branch, and the "spin briefly then a slot frees" recovery, are both decided inside
     * the RING_FULL_MAX_SPINS (sub-millisecond) window; they cannot be forced deterministically
     * from a test without sub-microsecond cross-thread timing, and the halt outcome would kill the
     * JVM/test run. Those two paths are covered by code review (see claimSlotOrHalt): after the
     * bounded spin, a freed slot falls through to next() (safe under ProducerType.SINGLE), a still
     * full ring logs loudly and calls Runtime.halt(1).
     */
    @Test
    public void claimSlotOrHaltDeliversBurstWithoutHalting() throws Exception {
        MatchEventPublisher p = new MatchEventPublisher();
        CountingHandler counting = new CountingHandler(MARKET_ID);
        p.initMarket(MARKET_ID, counting);
        p.start();
        try {
            // 3 publishes per iteration; 3000 total events sit far below the 65536 ring even if
            // the consumer never drained, so remainingCapacity never reaches 0 and the helper
            // never spins or halts.
            final int burst = 1000;
            for (int i = 0; i < burst; i++) {
                assertTrue("trade publish " + i + " should claim a slot",
                        p.publishTradeExecution(MARKET_ID, i, i, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L, 0L));
                assertTrue("book publish " + i + " should claim a slot",
                        p.publishOrderBookLevelUpdate(MARKET_ID, i, 60L, 20L, 3, false));
                assertTrue("status publish " + i + " should claim a slot",
                        p.publishOrderStatusUpdate(MARKET_ID, i, 1L, 1L, OrderStatusType.NEW, 100L, 0L, 50L, true, 0L, 0, 0L));
            }

            final int expected = burst * 3;
            pollUntil(() -> counting.count.get() == expected, 5000,
                    "all " + expected + " burst events should be delivered through claimSlotOrHalt");
            assertEquals("every claimed slot was delivered", expected, counting.count.get());
            assertTrue("publisher must not have halted under a within-capacity burst", p.isRunning());
        } finally {
            p.shutdown();
        }
    }

    // ==================== C-6: swallowed Disruptor handler exceptions ====================

    /**
     * C-6: a MarketEventHandler that throws must NOT silently vanish. The per-market
     * Disruptor default exception handler swallows the throw (rethrowing would kill the
     * publisher thread), but it must (a) increment disruptorExceptionCount so the loss is
     * observable, and (b) leave the publisher thread alive so later events still flow.
     */
    @Test
    public void swallowedHandlerExceptionIsCountedAndPublisherSurvives() throws Exception {
        MatchEventPublisher p = new MatchEventPublisher();
        ThrowOnceHandler throwing = new ThrowOnceHandler(MARKET_ID);
        p.initMarket(MARKET_ID, throwing);
        p.start();
        try {
            assertEquals("no exceptions before anything is published", 0L, p.disruptorExceptionCount());

            // First event: handler throws. The Disruptor calls handleEventException, which
            // swallows + counts it, then advances past the failed event.
            p.publishTradeExecution(MARKET_ID, 111L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L, 0L);
            pollUntil(() -> p.disruptorExceptionCount() >= 1, 3000,
                    "disruptorExceptionCount should reach >= 1 after the handler throws");
            assertTrue("swallowed exception was counted", p.disruptorExceptionCount() >= 1);

            // Second event: the publisher thread must have survived the throw and keep working.
            p.publishTradeExecution(MARKET_ID, 222L, 3L, 3L, 4L, 4L, 60L, 5L, false, 0L, 0L, 0L);
            pollUntil(() -> !throwing.capturedTimestamps.isEmpty(), 3000,
                    "publisher thread should still deliver events after swallowing an exception");
            assertEquals("the surviving thread delivered the post-exception event",
                    222L, (long) throwing.capturedTimestamps.get(0));

            assertTrue("publisher still running after a swallowed handler exception", p.isRunning());
        } finally {
            p.shutdown();
        }
    }

    /** Poll {@code cond} up to {@code timeoutMs}, failing the test if it never becomes true. */
    private static void pollUntil(BooleanSupplier cond, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out after " + timeoutMs + "ms: " + message);
    }

    // ==================== Test Handler ====================

    private static class CapturedEvent {
        int eventType;
        int marketId;
        long timestamp;
        long price;
        long quantity;
        boolean takerIsBuy;
        boolean isSnapshot;
    }

    private static class CapturingHandler implements MarketEventHandler {
        final List<CapturedEvent> events = new CopyOnWriteArrayList<>();
        private final int marketId;

        CapturingHandler(int marketId) {
            this.marketId = marketId;
        }

        @Override
        public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) {
            CapturedEvent copy = new CapturedEvent();
            copy.eventType = event.getEventType();
            copy.marketId = event.getMarketId();
            copy.timestamp = event.getTimestamp();
            copy.price = event.getPrice();
            copy.quantity = event.getQuantity();
            copy.takerIsBuy = event.isTakerIsBuy();
            copy.isSnapshot = event.isSnapshot();
            events.add(copy);
        }

        @Override
        public int getMarketId() {
            return marketId;
        }
    }

    /**
     * C-1 helper: minimal handler that only counts delivered events (no per-event allocation
     * growth), so a burst test can push thousands of events cheaply.
     */
    private static class CountingHandler implements MarketEventHandler {
        final AtomicInteger count = new AtomicInteger();
        private final int marketId;

        CountingHandler(int marketId) {
            this.marketId = marketId;
        }

        @Override
        public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) {
            count.incrementAndGet();
        }

        @Override
        public int getMarketId() {
            return marketId;
        }
    }

    /**
     * C-6 helper: throws on its FIRST event (to trip the Disruptor exception handler), then
     * captures every event after that (to prove the publisher thread survived the throw).
     */
    private static class ThrowOnceHandler implements MarketEventHandler {
        final CopyOnWriteArrayList<Long> capturedTimestamps = new CopyOnWriteArrayList<>();
        private final int marketId;
        private final AtomicInteger invocations = new AtomicInteger();

        ThrowOnceHandler(int marketId) {
            this.marketId = marketId;
        }

        @Override
        public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) {
            if (invocations.getAndIncrement() == 0) {
                throw new RuntimeException("test-injected handler failure");
            }
            capturedTimestamps.add(event.getTimestamp());
        }

        @Override
        public int getMarketId() {
            return marketId;
        }
    }
}
