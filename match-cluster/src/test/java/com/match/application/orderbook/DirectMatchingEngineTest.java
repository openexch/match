package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for DirectMatchingEngine — validates matching correctness and C3 safety.
 */
public class DirectMatchingEngineTest {

    private DirectMatchingEngine engine;

    private static final long BASE_PRICE = FixedPoint.fromDouble(10.0);
    private static final long MAX_PRICE = FixedPoint.fromDouble(1000.0);
    private static final long TICK_SIZE = FixedPoint.fromDouble(0.01);

    @Before
    public void setUp() {
        engine = new DirectMatchingEngine(BASE_PRICE, MAX_PRICE, TICK_SIZE);
    }

    // ==================== Limit Order Matching ====================

    @Test
    public void testLimitBuyNoMatch_AddedToBook() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        int matches = engine.processLimitOrder(1L, 100L, true, price, qty);

        assertEquals(0, matches);
        assertFalse("Bid book should have the order", engine.isBidEmpty());
        assertTrue("Ask book should be empty", engine.isAskEmpty());
    }

    @Test
    public void testLimitSellNoMatch_AddedToBook() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        int matches = engine.processLimitOrder(1L, 100L, false, price, qty);

        assertEquals(0, matches);
        assertTrue("Bid book should be empty", engine.isBidEmpty());
        assertFalse("Ask book should have the order", engine.isAskEmpty());
    }

    @Test
    public void testLimitOrderFullMatch() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        // Place ask
        engine.processLimitOrder(1L, 100L, false, price, qty);

        // Place matching bid
        int matches = engine.processLimitOrder(2L, 101L, true, price, qty);

        assertEquals(1, matches);
        assertEquals(1L, engine.getMatchMakerOrderId(0));
        assertEquals(100L, engine.getMatchMakerUserId(0));
        assertEquals(price, engine.getMatchPrice(0));
        assertEquals(qty, engine.getMatchQuantity(0));
        assertEquals(0L, engine.getTakerRemainingQuantity());
    }

    @Test
    public void testLimitOrderPartialMatch() {
        long price = FixedPoint.fromDouble(100.0);
        long makerQty = FixedPoint.fromDouble(5.0);
        long takerQty = FixedPoint.fromDouble(10.0);

        // Place smaller ask
        engine.processLimitOrder(1L, 100L, false, price, makerQty);

        // Place larger bid
        int matches = engine.processLimitOrder(2L, 101L, true, price, takerQty);

        assertEquals(1, matches);
        assertEquals(makerQty, engine.getMatchQuantity(0));
        // Remaining 5.0 should be on the bid book
        assertEquals(FixedPoint.fromDouble(5.0), engine.getTakerRemainingQuantity());
        assertFalse(engine.isBidEmpty());
    }

    @Test
    public void testLimitOrderPriceIncompatible_NoMatch() {
        // Ask at 200, bid at 100 — should not match
        engine.processLimitOrder(1L, 100L, false, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(10.0));
        int matches = engine.processLimitOrder(2L, 101L, true, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));

        assertEquals(0, matches);
        assertFalse(engine.isAskEmpty());
        assertFalse(engine.isBidEmpty());
    }

    @Test
    public void testMultipleLevelMatch() {
        // Place asks at different prices
        engine.processLimitOrder(1L, 100L, false, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(5.0));
        engine.processLimitOrder(2L, 101L, false, FixedPoint.fromDouble(101.0), FixedPoint.fromDouble(5.0));

        // Place bid that sweeps both levels
        int matches = engine.processLimitOrder(3L, 102L, true, FixedPoint.fromDouble(101.0), FixedPoint.fromDouble(10.0));

        assertEquals(2, matches);
        // First match at 100.0 (better price for buyer)
        assertEquals(FixedPoint.fromDouble(100.0), engine.getMatchPrice(0));
        assertEquals(FixedPoint.fromDouble(101.0), engine.getMatchPrice(1));
    }

    // ==================== Market Order Matching ====================

    @Test
    public void testMarketSellOrder() {
        // Place bid
        engine.processLimitOrder(1L, 100L, true, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));

        // Market sell
        int matches = engine.processMarketOrder(2L, 101L, false, FixedPoint.fromDouble(5.0), 0);

        assertEquals(1, matches);
        assertEquals(FixedPoint.fromDouble(5.0), engine.getMatchQuantity(0));
    }

    @Test
    public void testMarketBuyOrder_BudgetBased() {
        // Place ask at $100
        engine.processLimitOrder(1L, 100L, false, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));

        // Market buy with $500 budget → should buy 5.0 units
        long budget = FixedPoint.fromDouble(500.0);
        int matches = engine.processMarketOrder(2L, 101L, true, 0, budget);

        assertEquals(1, matches);
        assertEquals(5.0, FixedPoint.toDouble(engine.getMatchQuantity(0)), 0.01);
    }

    @Test
    public void testMarketOrderOnEmptyBook_NoMatch() {
        int matches = engine.processMarketOrder(1L, 100L, false, FixedPoint.fromDouble(10.0), 0);
        assertEquals(0, matches);
    }

    // ==================== Cancel ====================

    @Test
    public void testCancelOrder() {
        engine.processLimitOrder(1L, 100L, true, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        assertTrue(engine.cancelOrder(1L, true));
        assertTrue(engine.isBidEmpty());
    }

    @Test
    public void testCancelNonExistentOrder() {
        assertFalse(engine.cancelOrder(999L, true));
    }

    // ==================== Top Levels Collection ====================

    @Test
    public void testCollectTopLevels() {
        engine.processLimitOrder(1L, 100L, true, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        engine.processLimitOrder(2L, 101L, true, FixedPoint.fromDouble(99.0), FixedPoint.fromDouble(5.0));
        engine.processLimitOrder(3L, 102L, false, FixedPoint.fromDouble(101.0), FixedPoint.fromDouble(8.0));

        engine.collectTopLevels(10);

        assertEquals(2, engine.getTopBidCount());
        assertEquals(1, engine.getTopAskCount());
        // Best bid should be 100.0
        assertEquals(FixedPoint.fromDouble(100.0), engine.getTopBidPrices()[0]);
    }

    // ==================== Snapshot ====================

    @Test
    public void testClearAndRestore() {
        engine.processLimitOrder(1L, 100L, true, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        engine.processLimitOrder(2L, 101L, false, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(5.0));

        long[] bids = engine.getBidOrders();
        long[] asks = engine.getAskOrders();

        engine.clear();
        assertTrue(engine.isBidEmpty());
        assertTrue(engine.isAskEmpty());

        engine.restoreFromSnapshot(bids, asks);
        assertFalse(engine.isBidEmpty());
        assertFalse(engine.isAskEmpty());
    }

    // ==================== Match Limit Overflow ====================

    @Test
    public void testShouldMatchMoreThan100Orders() {
        long unitQty = FixedPoint.fromDouble(1.0);

        // DirectIndexOrderBook has MAX_ORDERS_PER_LEVEL=64, so spread 200 orders
        // across 4 price levels (50 per level) to stay within per-level limits
        long[] prices = {
            FixedPoint.fromDouble(100.0),
            FixedPoint.fromDouble(100.01),
            FixedPoint.fromDouble(100.02),
            FixedPoint.fromDouble(100.03)
        };
        int ordersPerLevel = 50;
        int totalOrders = prices.length * ordersPerLevel; // 200

        int orderId = 1;
        for (long price : prices) {
            for (int j = 0; j < ordersPerLevel; j++) {
                engine.addOrderNoMatch(orderId, orderId, false, price, unitQty);
                orderId++;
            }
        }

        // Submit one big buy that sweeps all 200 asks (price high enough to match all)
        long sweepPrice = FixedPoint.fromDouble(100.03);
        long totalQty = FixedPoint.fromDouble(200.0);
        int matches = engine.processLimitOrder(999L, 999L, true, sweepPrice, totalQty);

        assertEquals("Should match all 200 orders", totalOrders, matches);
        assertEquals("Taker should be fully filled", 0L, engine.getTakerRemainingQuantity());
        assertTrue("All asks should be consumed", engine.isAskEmpty());

        // Verify all match results are recorded
        for (int i = 0; i < totalOrders; i++) {
            assertTrue("Match " + i + " should have valid maker order ID",
                engine.getMatchMakerOrderId(i) > 0);
            assertEquals("Match " + i + " should be qty 1",
                unitQty, engine.getMatchQuantity(i));
        }
    }
}
