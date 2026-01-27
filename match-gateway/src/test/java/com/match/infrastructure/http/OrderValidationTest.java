package com.match.infrastructure.http;

import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for Order.validate() — validates gateway-level input validation (C3 fix).
 */
public class OrderValidationTest {

    private static final Gson gson = new Gson();

    private Order makeOrder(String market, String side, String type, double price, double qty, double totalPrice, String userId) {
        Order o = new Order();
        o.market = market;
        o.orderSide = side;
        o.orderType = type;
        o.price = price;
        o.quantity = qty;
        o.totalPrice = totalPrice;
        o.userId = userId;
        return o;
    }

    // ==================== Valid Orders ====================

    @Test
    public void testValidLimitBuy() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 50000.0, 0.5, 0, "user1");
        assertNull("Should be valid", o.validate());
    }

    @Test
    public void testValidLimitSell() {
        Order o = makeOrder("ETH-USD", "SELL", "LIMIT", 3000.0, 10.0, 0, "user2");
        assertNull("Should be valid", o.validate());
    }

    @Test
    public void testValidMarketBuy() {
        Order o = makeOrder("BTC-USD", "BUY", "MARKET", 0, 1.0, 50000.0, "user1");
        assertNull("Should be valid", o.validate());
    }

    @Test
    public void testValidMarketSell() {
        Order o = makeOrder("BTC-USD", "SELL", "MARKET", 0, 1.0, 0, "user1");
        assertNull("Should be valid", o.validate());
    }

    @Test
    public void testValidLimitMaker() {
        Order o = makeOrder("SOL-USD", "BUY", "LIMIT_MAKER", 100.0, 5.0, 0, "user1");
        assertNull("Should be valid", o.validate());
    }

    @Test
    public void testDefaultOrderType() {
        // No orderType → defaults to LIMIT
        Order o = makeOrder("BTC-USD", "BUY", null, 50000.0, 0.5, 0, "user1");
        assertNull("Should be valid with default LIMIT type", o.validate());
    }

    @Test
    public void testSideSynonyms() {
        assertNull(makeOrder("BTC-USD", "BID", "LIMIT", 100, 1, 0, "u1").validate());
        assertNull(makeOrder("BTC-USD", "ASK", "LIMIT", 100, 1, 0, "u1").validate());
        assertNull(makeOrder("BTC-USD", "buy", "LIMIT", 100, 1, 0, "u1").validate());
        assertNull(makeOrder("BTC-USD", "sell", "LIMIT", 100, 1, 0, "u1").validate());
    }

    // ==================== Missing Required Fields ====================

    @Test
    public void testMissingMarket() {
        Order o = makeOrder(null, "BUY", "LIMIT", 100, 1, 0, "user1");
        assertNotNull("Should reject null market", o.validate());
        assertTrue(o.validate().contains("market"));
    }

    @Test
    public void testEmptyMarket() {
        Order o = makeOrder("", "BUY", "LIMIT", 100, 1, 0, "user1");
        assertNotNull("Should reject empty market", o.validate());
    }

    @Test
    public void testUnknownMarket() {
        Order o = makeOrder("FAKE-USD", "BUY", "LIMIT", 100, 1, 0, "user1");
        assertNotNull("Should reject unknown market", o.validate());
        assertTrue(o.validate().contains("unknown market"));
    }

    @Test
    public void testMissingSide() {
        Order o = makeOrder("BTC-USD", null, "LIMIT", 100, 1, 0, "user1");
        assertNotNull("Should reject null side", o.validate());
    }

    @Test
    public void testInvalidSide() {
        Order o = makeOrder("BTC-USD", "INVALID", "LIMIT", 100, 1, 0, "user1");
        assertNotNull("Should reject invalid side", o.validate());
    }

    @Test
    public void testMissingUserId() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, 1, 0, null);
        assertNotNull("Should reject null userId", o.validate());
    }

    @Test
    public void testEmptyUserId() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, 1, 0, "");
        assertNotNull("Should reject empty userId", o.validate());
    }

    // ==================== Invalid Values ====================

    @Test
    public void testZeroQuantity() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, 0, 0, "user1");
        assertNotNull("Should reject zero quantity", o.validate());
        assertTrue(o.validate().contains("quantity"));
    }

    @Test
    public void testNegativeQuantity() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, -5, 0, "user1");
        assertNotNull("Should reject negative quantity", o.validate());
    }

    @Test
    public void testZeroPriceOnLimitOrder() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 0, 1, 0, "user1");
        assertNotNull("Should reject zero price on limit", o.validate());
        assertTrue(o.validate().contains("price"));
    }

    @Test
    public void testNegativePriceOnLimitOrder() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", -100, 1, 0, "user1");
        assertNotNull("Should reject negative price", o.validate());
    }

    @Test
    public void testMarketBuyWithoutBudget() {
        Order o = makeOrder("BTC-USD", "BUY", "MARKET", 0, 1, 0, "user1");
        assertNotNull("Market buy needs totalPrice", o.validate());
        assertTrue(o.validate().contains("totalPrice"));
    }

    @Test
    public void testMarketBuyWithNegativeBudget() {
        Order o = makeOrder("BTC-USD", "BUY", "MARKET", 0, 1, -500, "user1");
        assertNotNull("Should reject negative budget", o.validate());
    }

    @Test
    public void testInvalidOrderType() {
        Order o = makeOrder("BTC-USD", "BUY", "STOP_LOSS", 100, 1, 0, "user1");
        assertNotNull("Should reject unknown type", o.validate());
        assertTrue(o.validate().contains("orderType"));
    }

    // ==================== Overflow Protection ====================

    @Test
    public void testQuantityTooLarge() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, 100_000_000_000.0, 0, "user1");
        assertNotNull("Should reject overflow-causing quantity", o.validate());
        assertTrue(o.validate().contains("too large"));
    }

    @Test
    public void testPriceTooLarge() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100_000_000_000.0, 1, 0, "user1");
        assertNotNull("Should reject overflow-causing price", o.validate());
    }

    @Test
    public void testTotalPriceTooLarge() {
        Order o = makeOrder("BTC-USD", "BUY", "MARKET", 0, 1, 100_000_000_000.0, "user1");
        assertNotNull("Should reject overflow-causing totalPrice", o.validate());
    }

    // ==================== Conversion Methods ====================

    @Test
    public void testMarketIdConversion() {
        Order o = makeOrder("BTC-USD", "BUY", "LIMIT", 100, 1, 0, "user1");
        assertEquals(1, o.getMarketId());

        o.market = "ETH-USD";
        assertEquals(2, o.getMarketId());

        o.market = "UNKNOWN";
        assertEquals(0, o.getMarketId());
    }

    @Test
    public void testUserIdConversion() {
        Order o = new Order();
        o.userId = "12345";
        assertEquals(12345L, o.getUserIdAsLong());

        o.userId = "alice";
        assertTrue("Non-numeric userId should hash to positive long", o.getUserIdAsLong() > 0);

        o.userId = null;
        assertEquals(0L, o.getUserIdAsLong());
    }

    @Test
    public void testJsonDeserialization() {
        String json = "{\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":50000.0,\"quantity\":0.5,\"userId\":\"user1\"}";
        Order o = gson.fromJson(json, Order.class);
        assertNull("Deserialized order should be valid", o.validate());
        assertEquals(1, o.getMarketId());
    }
}
