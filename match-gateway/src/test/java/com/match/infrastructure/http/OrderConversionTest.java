package com.match.infrastructure.http;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for Order (HTTP) conversion methods — complements OrderValidationTest.
 * Covers: getUserIdAsLong, getMarketId, price/qty conversion, toOrderType, toOrderSide, toString.
 */
public class OrderConversionTest {

    // ==================== getUserIdAsLong ====================

    @Test
    public void testGetUserIdAsLong_NumericString() {
        Order order = new Order();
        order.userId = "12345";
        assertEquals(12345L, order.getUserIdAsLong());
    }

    @Test
    public void testGetUserIdAsLong_LargeNumericString() {
        Order order = new Order();
        order.userId = "9999999999999";
        assertEquals(9999999999999L, order.getUserIdAsLong());
    }

    @Test
    public void testGetUserIdAsLong_NonNumericString_ReturnsHash() {
        Order order = new Order();
        order.userId = "alice";
        long result = order.getUserIdAsLong();
        assertTrue("Non-numeric userId should produce positive hash", result > 0);
    }

    @Test
    public void testGetUserIdAsLong_NonNumeric_ConsistentHash() {
        Order o1 = new Order();
        o1.userId = "bob";
        Order o2 = new Order();
        o2.userId = "bob";
        assertEquals(o1.getUserIdAsLong(), o2.getUserIdAsLong());
    }

    @Test
    public void testGetUserIdAsLong_DifferentStrings_DifferentHash() {
        Order o1 = new Order();
        o1.userId = "alice";
        Order o2 = new Order();
        o2.userId = "bob";
        assertNotEquals(o1.getUserIdAsLong(), o2.getUserIdAsLong());
    }

    @Test
    public void testGetUserIdAsLong_Null_ReturnsZero() {
        Order order = new Order();
        order.userId = null;
        assertEquals(0L, order.getUserIdAsLong());
    }

    @Test
    public void testGetUserIdAsLong_Empty_ReturnsZero() {
        Order order = new Order();
        order.userId = "";
        assertEquals(0L, order.getUserIdAsLong());
    }

    // ==================== getMarketId ====================

    @Test
    public void testGetMarketId_BtcUsd() {
        Order order = new Order();
        order.market = "BTC-USD";
        assertEquals(1, order.getMarketId());
    }

    @Test
    public void testGetMarketId_EthUsd() {
        Order order = new Order();
        order.market = "ETH-USD";
        assertEquals(2, order.getMarketId());
    }

    @Test
    public void testGetMarketId_SolUsd() {
        Order order = new Order();
        order.market = "SOL-USD";
        assertEquals(3, order.getMarketId());
    }

    @Test
    public void testGetMarketId_XrpUsd() {
        Order order = new Order();
        order.market = "XRP-USD";
        assertEquals(4, order.getMarketId());
    }

    @Test
    public void testGetMarketId_DogeUsd() {
        Order order = new Order();
        order.market = "DOGE-USD";
        assertEquals(5, order.getMarketId());
    }

    @Test
    public void testGetMarketId_UnknownMarket_ReturnsZero() {
        Order order = new Order();
        order.market = "UNKNOWN-PAIR";
        assertEquals(0, order.getMarketId());
    }

    @Test
    public void testGetMarketId_NullMarket_ThrowsOrReturnsZero() {
        Order order = new Order();
        order.market = null;
        // Map.of() doesn't allow null keys, so getOrDefault(null, 0) throws NPE
        try {
            int result = order.getMarketId();
            // If it doesn't throw, it should return 0
            assertEquals(0, result);
        } catch (NullPointerException e) {
            // Expected: Map.of() doesn't support null keys
        }
    }

    // ==================== getPriceAsLong ====================

    @Test
    public void testGetPriceAsLong_Conversion() {
        Order order = new Order();
        order.price = 50000.12345678;
        long expected = FixedPoint.fromDouble(50000.12345678);
        assertEquals(expected, order.getPriceAsLong());
    }

    @Test
    public void testGetPriceAsLong_Zero() {
        Order order = new Order();
        order.price = 0.0;
        assertEquals(0L, order.getPriceAsLong());
    }

    // ==================== getQuantityAsLong ====================

    @Test
    public void testGetQuantityAsLong_Conversion() {
        Order order = new Order();
        order.quantity = 1.5;
        long expected = FixedPoint.fromDouble(1.5);
        assertEquals(expected, order.getQuantityAsLong());
    }

    // ==================== getTotalPriceAsLong ====================

    @Test
    public void testGetTotalPriceAsLong_Conversion() {
        Order order = new Order();
        order.totalPrice = 75000.0;
        long expected = FixedPoint.fromDouble(75000.0);
        assertEquals(expected, order.getTotalPriceAsLong());
    }

    // ==================== toOrderType ====================

    @Test
    public void testToOrderType_Limit() {
        Order order = new Order();
        order.orderType = "LIMIT";
        assertEquals(OrderType.LIMIT, order.toOrderType());
    }

    @Test
    public void testToOrderType_Market() {
        Order order = new Order();
        order.orderType = "MARKET";
        assertEquals(OrderType.MARKET, order.toOrderType());
    }

    @Test
    public void testToOrderType_LimitMaker() {
        Order order = new Order();
        order.orderType = "LIMIT_MAKER";
        assertEquals(OrderType.LIMIT_MAKER, order.toOrderType());
    }

    @Test
    public void testToOrderType_Null_DefaultsToLimit() {
        Order order = new Order();
        order.orderType = null;
        assertEquals(OrderType.LIMIT, order.toOrderType());
    }

    @Test
    public void testToOrderType_Unknown_DefaultsToLimit() {
        Order order = new Order();
        order.orderType = "STOP_LOSS";
        assertEquals(OrderType.LIMIT, order.toOrderType());
    }

    @Test
    public void testToOrderType_EmptyString_DefaultsToLimit() {
        Order order = new Order();
        order.orderType = "";
        assertEquals(OrderType.LIMIT, order.toOrderType());
    }

    // ==================== toOrderSide ====================

    @Test
    public void testToOrderSide_Buy_ReturnsBid() {
        Order order = new Order();
        order.orderSide = "BUY";
        assertEquals(OrderSide.BID, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_Sell_ReturnsAsk() {
        Order order = new Order();
        order.orderSide = "SELL";
        assertEquals(OrderSide.ASK, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_Bid_ReturnsBid() {
        Order order = new Order();
        order.orderSide = "BID";
        assertEquals(OrderSide.BID, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_Ask_ReturnsAsk() {
        Order order = new Order();
        order.orderSide = "ASK";
        assertEquals(OrderSide.ASK, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_LowerCase_Buy() {
        Order order = new Order();
        order.orderSide = "buy";
        assertEquals(OrderSide.BID, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_LowerCase_Sell() {
        Order order = new Order();
        order.orderSide = "sell";
        assertEquals(OrderSide.ASK, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_Null_DefaultsToBid() {
        Order order = new Order();
        order.orderSide = null;
        assertEquals(OrderSide.BID, order.toOrderSide());
    }

    @Test
    public void testToOrderSide_Unknown_DefaultsToBid() {
        Order order = new Order();
        order.orderSide = "UNKNOWN";
        assertEquals(OrderSide.BID, order.toOrderSide());
    }

    // ==================== toString ====================

    @Test
    public void testToString_ContainsAllFields() {
        Order order = new Order();
        order.userId = "user1";
        order.market = "BTC-USD";
        order.orderType = "LIMIT";
        order.orderSide = "BUY";
        order.price = 50000.0;
        order.quantity = 1.5;
        order.totalPrice = 75000.0;
        order.timestamp = 123456789L;

        String str = order.toString();

        assertTrue(str.contains("user1"));
        assertTrue(str.contains("BTC-USD"));
        assertTrue(str.contains("LIMIT"));
        assertTrue(str.contains("BUY"));
        assertTrue(str.contains("50000.0"));
        assertTrue(str.contains("1.5"));
        assertTrue(str.contains("75000.0"));
        assertTrue(str.contains("123456789"));
        assertTrue(str.startsWith("Order{"));
        assertTrue(str.endsWith("}"));
    }

    @Test
    public void testToString_NullFields() {
        Order order = new Order();
        // All fields null/zero by default
        String str = order.toString();
        assertNotNull(str);
        assertTrue(str.contains("Order{"));
    }
}
