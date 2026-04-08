package com.match.application.publisher;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for PublishEvent — verifies all setters, getters, and clear().
 */
public class PublishEventTest {

    private PublishEvent event;

    @Before
    public void setUp() {
        event = new PublishEvent();
    }

    // ==================== Trade Execution ====================

    @Test
    public void setTradeExecutionSetsAllFields() {
        event.setTradeExecution(1, 1000L, 42L, 10L, 100L, 20L, 200L, 5000L, 250L, true, 0L, 0L);

        assertEquals(PublishEventType.TRADE_EXECUTION, event.getEventType());
        assertEquals(1, event.getMarketId());
        assertEquals(1000L, event.getTimestamp());
        assertEquals(42L, event.getTradeId());
        assertEquals(10L, event.getTakerOrderId());
        assertEquals(100L, event.getTakerUserId());
        assertEquals(20L, event.getMakerOrderId());
        assertEquals(200L, event.getMakerUserId());
        assertEquals(5000L, event.getPrice());
        assertEquals(250L, event.getQuantity());
        assertTrue(event.isTakerIsBuy());
    }

    @Test
    public void setTradeExecutionTakerIsSell() {
        event.setTradeExecution(2, 2000L, 99L, 11L, 101L, 22L, 202L, 3000L, 100L, false, 0L, 0L);

        assertFalse(event.isTakerIsBuy());
        assertEquals(2, event.getMarketId());
        assertEquals(99L, event.getTradeId());
    }

    // ==================== Order Book Level Update ====================

    @Test
    public void setOrderBookLevelUpdateSetsAllFields() {
        event.setOrderBookLevelUpdate(3, 3000L, 7500L, 120L, 5, true);

        assertEquals(PublishEventType.ORDER_BOOK_UPDATE, event.getEventType());
        assertEquals(3, event.getMarketId());
        assertEquals(3000L, event.getTimestamp());
        assertEquals(7500L, event.getUpdatePrice());
        assertEquals(120L, event.getUpdateQuantity());
        assertEquals(5, event.getUpdateOrderCount());
        assertTrue(event.isUpdateIsBid());
        assertFalse(event.isSnapshot());
    }

    @Test
    public void setOrderBookLevelUpdateAskSide() {
        event.setOrderBookLevelUpdate(1, 4000L, 8000L, 50L, 2, false);

        assertFalse(event.isUpdateIsBid());
        assertFalse(event.isSnapshot());
    }

    // ==================== Order Book Snapshot ====================

    @Test
    public void setOrderBookSnapshotSetsAllFields() {
        long[] bidPrices = {100L, 99L, 98L};
        long[] bidQuantities = {10L, 20L, 30L};
        int[] bidOrderCounts = {1, 2, 3};

        long[] askPrices = {101L, 102L};
        long[] askQuantities = {15L, 25L};
        int[] askOrderCounts = {4, 5};

        event.setOrderBookSnapshot(1, 5000L,
            bidPrices, bidQuantities, bidOrderCounts, 3,
            askPrices, askQuantities, askOrderCounts, 2);

        assertEquals(PublishEventType.ORDER_BOOK_UPDATE, event.getEventType());
        assertEquals(1, event.getMarketId());
        assertEquals(5000L, event.getTimestamp());
        assertTrue(event.isSnapshot());

        assertEquals(3, event.getBidLevelCount());
        assertEquals(100L, event.getBidPrice(0));
        assertEquals(20L, event.getBidQuantity(1));
        assertEquals(3, event.getBidOrderCount(2));

        assertEquals(2, event.getAskLevelCount());
        assertEquals(101L, event.getAskPrice(0));
        assertEquals(25L, event.getAskQuantity(1));
        assertEquals(5, event.getAskOrderCount(1));
    }

    @Test
    public void snapshotClampsToTenLevels() {
        long[] prices = new long[15];
        long[] quantities = new long[15];
        int[] orderCounts = new int[15];
        for (int i = 0; i < 15; i++) {
            prices[i] = (i + 1) * 100L;
            quantities[i] = (i + 1) * 10L;
            orderCounts[i] = i + 1;
        }

        event.setOrderBookSnapshot(1, 6000L,
            prices, quantities, orderCounts, 15,
            prices, quantities, orderCounts, 12);

        assertEquals(10, event.getBidLevelCount());
        assertEquals(10, event.getAskLevelCount());
    }

    // ==================== Order Status Update ====================

    @Test
    public void setOrderStatusUpdateSetsAllFields() {
        event.setOrderStatusUpdate(2, 7000L, 55L, 300L, OrderStatusType.NEW, 500L, 0L, 4000L, true, 0L);

        assertEquals(PublishEventType.ORDER_STATUS_UPDATE, event.getEventType());
        assertEquals(2, event.getMarketId());
        assertEquals(7000L, event.getTimestamp());
        assertEquals(55L, event.getOrderId());
        assertEquals(300L, event.getUserId());
        assertEquals(OrderStatusType.NEW, event.getOrderStatus());
        assertEquals(500L, event.getRemainingQty());
        assertEquals(0L, event.getFilledQty());
        assertEquals(4000L, event.getOrderPrice());
        assertTrue(event.isOrderIsBuy());
    }

    @Test
    public void setOrderStatusUpdateSellSide() {
        event.setOrderStatusUpdate(1, 8000L, 66L, 400L, OrderStatusType.FILLED, 0L, 1000L, 5000L, false, 0L);

        assertFalse(event.isOrderIsBuy());
        assertEquals(OrderStatusType.FILLED, event.getOrderStatus());
        assertEquals(0L, event.getRemainingQty());
        assertEquals(1000L, event.getFilledQty());
    }

    // ==================== Clear ====================

    @Test
    public void clearResetsAllFields() {
        // Set trade execution first
        event.setTradeExecution(1, 1000L, 42L, 10L, 100L, 20L, 200L, 5000L, 250L, true, 0L, 0L);
        // Then clear
        event.clear();

        assertEquals(0, event.getEventType());
        assertEquals(0, event.getMarketId());
        assertEquals(0L, event.getTimestamp());
        assertEquals(0L, event.getTradeId());
        assertEquals(0L, event.getTakerOrderId());
        assertEquals(0L, event.getTakerUserId());
        assertEquals(0L, event.getMakerOrderId());
        assertEquals(0L, event.getMakerUserId());
        assertEquals(0L, event.getPrice());
        assertEquals(0L, event.getQuantity());
        assertFalse(event.isTakerIsBuy());
        assertEquals(0, event.getBidLevelCount());
        assertEquals(0, event.getAskLevelCount());
        assertEquals(0L, event.getUpdatePrice());
        assertEquals(0L, event.getUpdateQuantity());
        assertEquals(0, event.getUpdateOrderCount());
        assertFalse(event.isUpdateIsBid());
        assertFalse(event.isSnapshot());
        assertEquals(0L, event.getOrderId());
        assertEquals(0L, event.getUserId());
        assertEquals(0, event.getOrderStatus());
        assertEquals(0L, event.getRemainingQty());
        assertEquals(0L, event.getFilledQty());
        assertEquals(0L, event.getOrderPrice());
        assertFalse(event.isOrderIsBuy());
    }

    @Test
    public void clearAfterOrderBookSnapshot() {
        long[] p = {100L};
        long[] q = {10L};
        int[] c = {1};
        event.setOrderBookSnapshot(1, 100L, p, q, c, 1, p, q, c, 1);

        event.clear();

        assertEquals(0, event.getBidLevelCount());
        assertEquals(0, event.getAskLevelCount());
        assertFalse(event.isSnapshot());
    }

    @Test
    public void clearAfterOrderStatusUpdate() {
        event.setOrderStatusUpdate(1, 100L, 10L, 20L, OrderStatusType.CANCELLED, 0L, 500L, 3000L, true, 0L);

        event.clear();

        assertEquals(0L, event.getOrderId());
        assertEquals(0, event.getOrderStatus());
        assertFalse(event.isOrderIsBuy());
    }

    // ==================== Event Type Constants ====================

    @Test
    public void eventTypeConstants() {
        assertEquals(0, PublishEventType.TRADE_EXECUTION);
        assertEquals(1, PublishEventType.ORDER_BOOK_UPDATE);
        assertEquals(2, PublishEventType.ORDER_STATUS_UPDATE);
    }

    // ==================== Order Status Type Constants ====================

    @Test
    public void orderStatusTypeConstants() {
        assertEquals(0, OrderStatusType.NEW);
        assertEquals(1, OrderStatusType.PARTIALLY_FILLED);
        assertEquals(2, OrderStatusType.FILLED);
        assertEquals(3, OrderStatusType.CANCELLED);
        assertEquals(4, OrderStatusType.REJECTED);
    }
}
