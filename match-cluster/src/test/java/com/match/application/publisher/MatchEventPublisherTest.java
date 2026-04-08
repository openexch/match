package com.match.application.publisher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

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
            MARKET_ID, 12345L, 10L, 100L, 20L, 200L, 5000L, 250L, true, 0L, 0L);

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
            999, 1000L, 1L, 1L, 2L, 2L, 100L, 10L, true, 0L, 0L);
        assertFalse("Unknown market should return false", result);
    }

    @Test
    public void tradeIdGeneratorIncrementsOnPublish() throws Exception {
        publisher.start();
        long before = publisher.getTradeIdGenerator();

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L);
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
            MARKET_ID, 4000L, 55L, 300L, OrderStatusType.NEW, 500L, 0L, 4000L, true, 0L);

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
            999, 100L, 1L, 1L, OrderStatusType.NEW, 100L, 0L, 50L, true, 0L);
        assertFalse(result);
    }

    // ==================== Multiple Events ====================

    @Test
    public void multipleEventsDeliveredInOrder() throws Exception {
        publisher.start();

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L);
        publisher.publishOrderBookLevelUpdate(MARKET_ID, 200L, 60L, 20L, 3, false);
        publisher.publishOrderStatusUpdate(MARKET_ID, 300L, 1L, 1L, OrderStatusType.FILLED, 0L, 10L, 50L, true, 0L);

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

        publisher.publishTradeExecution(MARKET_ID, 100L, 1L, 1L, 2L, 2L, 50L, 10L, true, 0L, 0L);
        publisher.publishTradeExecution(2, 200L, 3L, 3L, 4L, 4L, 70L, 5L, false, 0L, 0L);

        Thread.sleep(200);

        assertEquals(1, handler.events.size());
        assertEquals(1, handler2.events.size());
        assertEquals(MARKET_ID, handler.events.get(0).marketId);
        assertEquals(2, handler2.events.get(0).marketId);
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
}
