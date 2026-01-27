package com.match.application.orderbook;

import com.match.application.handlers.LimitOrderHandler;
import com.match.application.handlers.MarketOrderHandler;
import com.match.domain.FixedPoint;
import com.match.domain.Order;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for OrderBookImpl — full order book with matching.
 */
public class OrderBookImplTest {

    private OrderBookImpl orderBook;

    @Before
    public void setUp() {
        orderBook = new OrderBookImpl("BTCUSD", "BTC", "USD");

        // Register sides
        OrderBookSideImpl askSide = new OrderBookSideImpl(true);
        OrderBookSideImpl bidSide = new OrderBookSideImpl(false);

        // Register handlers on BOTH sides (maker sides need the handlers)
        LimitOrderHandler limitHandler = new LimitOrderHandler();
        MarketOrderHandler marketHandler = new MarketOrderHandler();

        askSide.registerHandler(OrderType.LIMIT, limitHandler);
        askSide.registerHandler(OrderType.MARKET, marketHandler);
        bidSide.registerHandler(OrderType.LIMIT, limitHandler);
        bidSide.registerHandler(OrderType.MARKET, marketHandler);

        orderBook.registerSide(OrderSide.ASK, askSide);
        orderBook.registerSide(OrderSide.BID, bidSide);
    }

    // ==================== Helper ====================

    private Order createOrder(long id, long userId, OrderSide side, OrderType type,
                              double price, double quantity) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setSide(side);
        order.setType(type);
        order.setPrice(FixedPoint.fromDouble(price));
        order.setQuantity(FixedPoint.fromDouble(quantity));
        order.setRemainingQuantity(FixedPoint.fromDouble(quantity));
        return order;
    }

    // ==================== createOrder (no match → placed on book) ====================

    @Test
    public void testCreateOrder_NoMatch_PlacedOnBook() throws Exception {
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 200.0, 10.0);
        orderBook.createOrder(ask);

        assertNotNull(orderBook.getOrder(1));
        assertEquals(1, orderBook.getOrderCount());
        assertFalse(orderBook.getAskSide().isEmpty());
        assertTrue(orderBook.getBidSide().isEmpty());
    }

    @Test
    public void testCreateOrder_BidNoMatch_PlacedOnBook() throws Exception {
        Order bid = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(bid);

        assertNotNull(orderBook.getOrder(1));
        assertEquals(1, orderBook.getOrderCount());
        assertTrue(orderBook.getAskSide().isEmpty());
        assertFalse(orderBook.getBidSide().isEmpty());
    }

    @Test
    public void testCreateOrder_NoMatchIncompatiblePrices_BothOnBook() throws Exception {
        // Ask at 200, bid at 100 — no match
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 200.0, 10.0);
        Order bid = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);
        orderBook.createOrder(bid);

        assertEquals(2, orderBook.getOrderCount());
        assertNotNull(orderBook.getOrder(1));
        assertNotNull(orderBook.getOrder(2));
    }

    // ==================== createOrder (match → fills and removes) ====================

    @Test
    public void testCreateOrder_FullMatch_BothRemoved() throws Exception {
        // Ask at 100
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);
        assertEquals(1, orderBook.getOrderCount());

        // Bid at 100 — exact match
        Order bid = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(bid);

        // Maker (ask) should be removed from book; taker fully filled, not placed
        assertNull(orderBook.getOrder(1));
        assertNull(orderBook.getOrder(2)); // taker fully filled, not placed
        assertEquals(0, orderBook.getOrderCount());
    }

    @Test
    public void testCreateOrder_PartialMatch_TakerRemainsOnBook() throws Exception {
        // Ask at 100, qty 5
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        orderBook.createOrder(ask);

        // Bid at 100, qty 10 — partial match (5 filled, 5 remaining)
        Order bid = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(bid);

        // Maker fully filled and removed
        assertNull(orderBook.getOrder(1));
        // Taker partially filled, remainder placed on book
        assertNotNull(orderBook.getOrder(2));
        assertEquals(1, orderBook.getOrderCount());
        assertEquals(FixedPoint.fromDouble(5.0), bid.getRemainingQuantity());
    }

    @Test
    public void testCreateOrder_PartialMatch_MakerRemainsOnBook() throws Exception {
        // Ask at 100, qty 10
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);

        // Bid at 100, qty 5 — partial match (5 filled, 0 remaining for taker)
        Order bid = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0);
        orderBook.createOrder(bid);

        // Maker partially filled, still on book
        assertNotNull(orderBook.getOrder(1));
        assertEquals(FixedPoint.fromDouble(5.0), ask.getRemainingQuantity());
        // Taker fully filled, not on book
        assertNull(orderBook.getOrder(2));
        assertEquals(1, orderBook.getOrderCount());
    }

    @Test
    public void testCreateOrder_MultiLevelSweep() throws Exception {
        // Multiple asks at different prices
        orderBook.createOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));
        orderBook.createOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 101.0, 5.0));
        orderBook.createOrder(createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 102.0, 5.0));
        assertEquals(3, orderBook.getOrderCount());

        // Large bid that sweeps multiple levels
        Order bid = createOrder(4, 200, OrderSide.BID, OrderType.LIMIT, 102.0, 15.0);
        orderBook.createOrder(bid);

        // All asks matched, taker fully filled
        assertEquals(0, orderBook.getOrderCount());
    }

    // ==================== cancelOrder ====================

    @Test
    public void testCancelOrder_Existing_RemovesFromBook() throws Exception {
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);
        assertEquals(1, orderBook.getOrderCount());

        orderBook.cancelOrder(1);

        assertNull(orderBook.getOrder(1));
        assertEquals(0, orderBook.getOrderCount());
        assertTrue(orderBook.getAskSide().isEmpty());
    }

    @Test
    public void testCancelOrder_NonExisting_NoEffect() throws Exception {
        // Should not throw
        orderBook.cancelOrder(999);
        assertEquals(0, orderBook.getOrderCount());
    }

    // ==================== updateOrder ====================

    @Test
    public void testUpdateOrder_RemovesOldOrder() throws Exception {
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);
        assertEquals(1, orderBook.getOrderCount());

        orderBook.updateOrder(1);

        assertNull(orderBook.getOrder(1));
        assertEquals(0, orderBook.getOrderCount());
    }

    @Test
    public void testUpdateOrder_NonExisting_NoEffect() throws Exception {
        orderBook.updateOrder(999);
        assertEquals(0, orderBook.getOrderCount());
    }

    // ==================== getOrder/getOrderCount ====================

    @Test
    public void testGetOrder_ReturnsOrderById() throws Exception {
        Order ask = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(ask);
        assertSame(ask, orderBook.getOrder(1));
    }

    @Test
    public void testGetOrder_NonExistent_ReturnsNull() {
        assertNull(orderBook.getOrder(999));
    }

    @Test
    public void testGetOrderCount_Empty() {
        assertEquals(0, orderBook.getOrderCount());
    }

    @Test
    public void testGetOrderCount_MultipleOrders() throws Exception {
        orderBook.createOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 200.0, 5.0));
        orderBook.createOrder(createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 3.0));
        orderBook.createOrder(createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 300.0, 7.0));
        assertEquals(3, orderBook.getOrderCount());
    }

    // ==================== getSides ====================

    @Test
    public void testGetBidSide_ReturnsBidSide() {
        assertNotNull(orderBook.getBidSide());
    }

    @Test
    public void testGetAskSide_ReturnsAskSide() {
        assertNotNull(orderBook.getAskSide());
    }

    // ==================== Full Matching Flow ====================

    @Test
    public void testFullFlow_MultipleBidsAndAsks() throws Exception {
        // Place several asks
        orderBook.createOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0));
        orderBook.createOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 101.0, 10.0));
        orderBook.createOrder(createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 102.0, 10.0));

        // Place several bids (no match — prices too low)
        orderBook.createOrder(createOrder(4, 200, OrderSide.BID, OrderType.LIMIT, 98.0, 10.0));
        orderBook.createOrder(createOrder(5, 201, OrderSide.BID, OrderType.LIMIT, 99.0, 10.0));

        assertEquals(5, orderBook.getOrderCount());

        // Now place a bid that matches best ask
        Order bid = createOrder(6, 300, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        orderBook.createOrder(bid);

        // Order 1 (ask at 100) should be fully matched and removed
        assertNull(orderBook.getOrder(1));
        // Taker fully filled, not placed
        assertNull(orderBook.getOrder(6));
        assertEquals(4, orderBook.getOrderCount());

        // Cancel a remaining order
        orderBook.cancelOrder(4);
        assertEquals(3, orderBook.getOrderCount());
    }

    @Test
    public void testSellSweepsBids() throws Exception {
        // Place bids
        orderBook.createOrder(createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0));
        orderBook.createOrder(createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 99.0, 5.0));

        // Sell at 99 — should match both bids (highest bid first = 100, then 99)
        Order sell = createOrder(3, 200, OrderSide.ASK, OrderType.LIMIT, 99.0, 10.0);
        orderBook.createOrder(sell);

        // Both bids matched
        assertNull(orderBook.getOrder(1));
        assertNull(orderBook.getOrder(2));
        assertNull(orderBook.getOrder(3));
        assertEquals(0, orderBook.getOrderCount());
    }
}
