package com.match.infrastructure.gateway.state;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for data classes: AggregatedTrade and OpenOrder.
 */
public class DataClassTest {

    // ==================== AggregatedTrade: Field Access ====================

    @Test
    public void testAggregatedTrade_FieldAccess() {
        AggregatedTrade trade = new AggregatedTrade();
        trade.price = 100.5;
        trade.quantity = 2.0;
        trade.tradeCount = 3;
        trade.buyCount = 2;
        trade.sellCount = 1;
        trade.timestamp = 123456789L;

        assertEquals(100.5, trade.price, 0.0001);
        assertEquals(2.0, trade.quantity, 0.0001);
        assertEquals(3, trade.tradeCount);
        assertEquals(2, trade.buyCount);
        assertEquals(1, trade.sellCount);
        assertEquals(123456789L, trade.timestamp);
    }

    // ==================== AggregatedTrade: reset() ====================

    @Test
    public void testAggregatedTrade_Reset_ClearsAll() {
        AggregatedTrade trade = new AggregatedTrade();
        trade.price = 100.0;
        trade.quantity = 5.0;
        trade.tradeCount = 10;
        trade.buyCount = 6;
        trade.sellCount = 4;
        trade.timestamp = 999L;

        trade.reset();

        assertEquals(0.0, trade.price, 0.0);
        assertEquals(0.0, trade.quantity, 0.0);
        assertEquals(0, trade.tradeCount);
        assertEquals(0, trade.buyCount);
        assertEquals(0, trade.sellCount);
        assertEquals(0L, trade.timestamp);
    }

    // ==================== AggregatedTrade: copyFrom() ====================

    @Test
    public void testAggregatedTrade_CopyFrom() {
        AggregatedTrade source = new AggregatedTrade();
        source.price = 200.0;
        source.quantity = 3.0;
        source.tradeCount = 5;
        source.buyCount = 3;
        source.sellCount = 2;
        source.timestamp = 555L;

        AggregatedTrade dest = new AggregatedTrade();
        dest.copyFrom(source);

        assertEquals(200.0, dest.price, 0.0001);
        assertEquals(3.0, dest.quantity, 0.0001);
        assertEquals(5, dest.tradeCount);
        assertEquals(3, dest.buyCount);
        assertEquals(2, dest.sellCount);
        assertEquals(555L, dest.timestamp);
    }

    @Test
    public void testAggregatedTrade_CopyFrom_IndependentOfSource() {
        AggregatedTrade source = new AggregatedTrade();
        source.price = 100.0;
        source.quantity = 1.0;
        source.tradeCount = 1;
        source.buyCount = 1;
        source.sellCount = 0;
        source.timestamp = 100L;

        AggregatedTrade dest = new AggregatedTrade();
        dest.copyFrom(source);

        // Modify source after copy
        source.price = 999.0;
        source.timestamp = 999L;

        // dest should not be affected
        assertEquals(100.0, dest.price, 0.0001);
        assertEquals(100L, dest.timestamp);
    }

    // ==================== OpenOrder: Field Access ====================

    @Test
    public void testOpenOrder_FieldAccess() {
        OpenOrder order = new OpenOrder();
        order.orderId = 42L;
        order.userId = 100L;
        order.price = 50000.0;
        order.remainingQuantity = 0.5;
        order.filledQuantity = 0.3;
        order.side = "BID";
        order.status = "PARTIALLY_FILLED";
        order.timestamp = 999999L;

        assertEquals(42L, order.orderId);
        assertEquals(100L, order.userId);
        assertEquals(50000.0, order.price, 0.0001);
        assertEquals(0.5, order.remainingQuantity, 0.0001);
        assertEquals(0.3, order.filledQuantity, 0.0001);
        assertEquals("BID", order.side);
        assertEquals("PARTIALLY_FILLED", order.status);
        assertEquals(999999L, order.timestamp);
    }

    // ==================== OpenOrder: reset() ====================

    @Test
    public void testOpenOrder_Reset_ClearsAll() {
        OpenOrder order = new OpenOrder();
        order.orderId = 42L;
        order.userId = 100L;
        order.price = 50000.0;
        order.remainingQuantity = 0.5;
        order.filledQuantity = 0.3;
        order.side = "BID";
        order.status = "NEW";
        order.timestamp = 999999L;

        order.reset();

        assertEquals(0L, order.orderId);
        assertEquals(0L, order.userId);
        assertEquals(0.0, order.price, 0.0);
        assertEquals(0.0, order.remainingQuantity, 0.0);
        assertEquals(0.0, order.filledQuantity, 0.0);
        assertNull(order.side);
        assertNull(order.status);
        assertEquals(0L, order.timestamp);
    }

    // ==================== OpenOrder: copyFrom() ====================

    @Test
    public void testOpenOrder_CopyFrom() {
        OpenOrder source = new OpenOrder();
        source.orderId = 10L;
        source.userId = 20L;
        source.price = 30000.0;
        source.remainingQuantity = 1.5;
        source.filledQuantity = 0.5;
        source.side = "ASK";
        source.status = "NEW";
        source.timestamp = 777L;

        OpenOrder dest = new OpenOrder();
        dest.copyFrom(source);

        assertEquals(10L, dest.orderId);
        assertEquals(20L, dest.userId);
        assertEquals(30000.0, dest.price, 0.0001);
        assertEquals(1.5, dest.remainingQuantity, 0.0001);
        assertEquals(0.5, dest.filledQuantity, 0.0001);
        assertEquals("ASK", dest.side);
        assertEquals("NEW", dest.status);
        assertEquals(777L, dest.timestamp);
    }

    @Test
    public void testOpenOrder_CopyFrom_IndependentOfSource() {
        OpenOrder source = new OpenOrder();
        source.orderId = 10L;
        source.price = 100.0;
        source.status = "NEW";

        OpenOrder dest = new OpenOrder();
        dest.copyFrom(source);

        source.orderId = 999L;
        source.price = 999.0;
        source.status = "FILLED";

        assertEquals(10L, dest.orderId);
        assertEquals(100.0, dest.price, 0.0001);
        assertEquals("NEW", dest.status);
    }

    // ==================== OpenOrder: isTerminal() ====================

    @Test
    public void testOpenOrder_IsTerminal_Filled() {
        OpenOrder order = new OpenOrder();
        order.status = "FILLED";
        assertTrue(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_Cancelled() {
        OpenOrder order = new OpenOrder();
        order.status = "CANCELLED";
        assertTrue(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_Rejected() {
        OpenOrder order = new OpenOrder();
        order.status = "REJECTED";
        assertTrue(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_New_ReturnsFalse() {
        OpenOrder order = new OpenOrder();
        order.status = "NEW";
        assertFalse(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_PartiallyFilled_ReturnsFalse() {
        OpenOrder order = new OpenOrder();
        order.status = "PARTIALLY_FILLED";
        assertFalse(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_NullStatus_ReturnsFalse() {
        OpenOrder order = new OpenOrder();
        order.status = null;
        assertFalse(order.isTerminal());
    }

    @Test
    public void testOpenOrder_IsTerminal_EmptyStatus_ReturnsFalse() {
        OpenOrder order = new OpenOrder();
        order.status = "";
        assertFalse(order.isTerminal());
    }
}
