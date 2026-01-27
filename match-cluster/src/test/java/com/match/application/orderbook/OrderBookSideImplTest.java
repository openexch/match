package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.domain.interfaces.OrderBookSide;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for OrderBookSideImpl — order book side with AA Tree.
 */
public class OrderBookSideImplTest {

    private OrderBookSideImpl askSide;  // ascending
    private OrderBookSideImpl bidSide;  // descending

    private static final long NO_PRICE = Long.MIN_VALUE;

    @Before
    public void setUp() {
        askSide = new OrderBookSideImpl(true);   // ask = ascending
        bidSide = new OrderBookSideImpl(false);  // bid = descending
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

    // ==================== placeOrder ====================

    @Test
    public void testPlaceOrder_NewLevel_CreatesLevel() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        askSide.placeOrder(order);

        assertFalse(askSide.isEmpty());
        assertEquals(1, askSide.getPriceLevelCount());
        Level level = askSide.getLevel(FixedPoint.fromDouble(100.0));
        assertNotNull(level);
        assertEquals(1, level.getLength());
    }

    @Test
    public void testPlaceOrder_ExistingLevel_AppendsOrder() {
        Order order1 = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        Order order2 = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 3.0);

        askSide.placeOrder(order1);
        askSide.placeOrder(order2);

        assertEquals(1, askSide.getPriceLevelCount()); // still one level
        Level level = askSide.getLevel(FixedPoint.fromDouble(100.0));
        assertEquals(2, level.getLength());
    }

    @Test
    public void testPlaceOrder_MultipleLevels() {
        Order order1 = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        Order order2 = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 200.0, 3.0);

        askSide.placeOrder(order1);
        askSide.placeOrder(order2);

        assertEquals(2, askSide.getPriceLevelCount());
    }

    // ==================== removeOrder ====================

    @Test
    public void testRemoveOrder_RemovesFromLevel() {
        Order order1 = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        Order order2 = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 3.0);

        askSide.placeOrder(order1);
        askSide.placeOrder(order2);

        askSide.removeOrder(order1);

        Level level = askSide.getLevel(FixedPoint.fromDouble(100.0));
        assertNotNull(level);
        assertEquals(1, level.getLength());
    }

    @Test
    public void testRemoveOrder_RemovesEmptyLevel() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        askSide.placeOrder(order);

        askSide.removeOrder(order);

        assertTrue(askSide.isEmpty());
        assertNull(askSide.getLevel(FixedPoint.fromDouble(100.0)));
    }

    @Test
    public void testRemoveOrder_NonExistentPrice_NoEffect() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        // order not placed, removeOrder should be safe
        askSide.removeOrder(order);
        assertTrue(askSide.isEmpty());
    }

    // ==================== cancelOrder ====================

    @Test
    public void testCancelOrder_RemovesOrder() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        askSide.placeOrder(order);

        askSide.cancelOrder(order);

        assertTrue(askSide.isEmpty());
    }

    // ==================== getBestPrice ====================

    @Test
    public void testGetBestPrice_EmptyAskSide_ReturnsNoPrice() {
        assertEquals(NO_PRICE, askSide.getBestPrice());
    }

    @Test
    public void testGetBestPrice_EmptyBidSide_ReturnsNoPrice() {
        assertEquals(NO_PRICE, bidSide.getBestPrice());
    }

    @Test
    public void testGetBestPrice_AskSide_ReturnsLowestPrice() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 200.0, 5.0));
        askSide.placeOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 3.0));
        askSide.placeOrder(createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 150.0, 4.0));

        assertEquals(FixedPoint.fromDouble(100.0), askSide.getBestPrice());
    }

    @Test
    public void testGetBestPrice_BidSide_ReturnsHighestPrice() {
        bidSide.placeOrder(createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 200.0, 5.0));
        bidSide.placeOrder(createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 3.0));
        bidSide.placeOrder(createOrder(3, 102, OrderSide.BID, OrderType.LIMIT, 150.0, 4.0));

        assertEquals(FixedPoint.fromDouble(200.0), bidSide.getBestPrice());
    }

    // ==================== isEmpty ====================

    @Test
    public void testIsEmpty_NewSide_ReturnsTrue() {
        assertTrue(askSide.isEmpty());
    }

    @Test
    public void testIsEmpty_AfterAddAndRemove() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        askSide.placeOrder(order);
        assertFalse(askSide.isEmpty());

        askSide.removeOrder(order);
        assertTrue(askSide.isEmpty());
    }

    // ==================== getSortedPrices ====================

    @Test
    public void testGetSortedPrices_EmptySide() {
        long[] prices = askSide.getSortedPrices();
        assertEquals(0, prices.length);
    }

    @Test
    public void testGetSortedPrices_AskSide_Ascending() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 300.0, 5.0));
        askSide.placeOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 3.0));
        askSide.placeOrder(createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 200.0, 4.0));

        long[] prices = askSide.getSortedPrices();
        assertEquals(3, prices.length);
        assertEquals(FixedPoint.fromDouble(100.0), prices[0]);
        assertEquals(FixedPoint.fromDouble(200.0), prices[1]);
        assertEquals(FixedPoint.fromDouble(300.0), prices[2]);
    }

    @Test
    public void testGetSortedPrices_BidSide_Descending() {
        bidSide.placeOrder(createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 300.0, 5.0));
        bidSide.placeOrder(createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 3.0));
        bidSide.placeOrder(createOrder(3, 102, OrderSide.BID, OrderType.LIMIT, 200.0, 4.0));

        long[] prices = bidSide.getSortedPrices();
        assertEquals(3, prices.length);
        assertEquals(FixedPoint.fromDouble(300.0), prices[0]);
        assertEquals(FixedPoint.fromDouble(200.0), prices[1]);
        assertEquals(FixedPoint.fromDouble(100.0), prices[2]);
    }

    // ==================== getPriceLevelCount ====================

    @Test
    public void testGetPriceLevelCount_EmptySide() {
        assertEquals(0, askSide.getPriceLevelCount());
    }

    @Test
    public void testGetPriceLevelCount_MultipleOrders_SameLevel() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));
        askSide.placeOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 3.0));
        assertEquals(1, askSide.getPriceLevelCount());
    }

    @Test
    public void testGetPriceLevelCount_MultipleOrders_DifferentLevels() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));
        askSide.placeOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 200.0, 3.0));
        assertEquals(2, askSide.getPriceLevelCount());
    }

    // ==================== getPriceLevels ====================

    @Test
    public void testGetPriceLevels_ReturnsMap() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));
        askSide.placeOrder(createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 200.0, 3.0));

        Long2ObjectHashMap<Level> levels = askSide.getPriceLevels();
        assertEquals(2, levels.size());
        assertNotNull(levels.get(FixedPoint.fromDouble(100.0)));
        assertNotNull(levels.get(FixedPoint.fromDouble(200.0)));
    }

    // ==================== getLevel ====================

    @Test
    public void testGetLevel_ExistingPrice() {
        Order order = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        askSide.placeOrder(order);
        Level level = askSide.getLevel(FixedPoint.fromDouble(100.0));
        assertNotNull(level);
        assertEquals(FixedPoint.fromDouble(100.0), level.getPrice());
    }

    @Test
    public void testGetLevel_NonExistentPrice_ReturnsNull() {
        assertNull(askSide.getLevel(FixedPoint.fromDouble(999.0)));
    }

    // ==================== canMatch ====================

    @Test
    public void testCanMatch_EmptyBook_ReturnsFalse() {
        Order taker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0);
        assertFalse(askSide.canMatch(taker));
    }

    @Test
    public void testCanMatch_MarketOrder_NonEmptyBook_ReturnsTrue() {
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));

        Order marketTaker = createOrder(2, 101, OrderSide.BID, OrderType.MARKET, 0.0, 5.0);
        assertTrue(askSide.canMatch(marketTaker));
    }

    @Test
    public void testCanMatch_LimitOrder_AskSide_TakerPriceHighEnough() {
        // Ask side has orders at 100.0
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));

        // Taker bid at 100.0 — matches because taker.price >= best ask
        Order taker = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0);
        assertTrue(askSide.canMatch(taker));
    }

    @Test
    public void testCanMatch_LimitOrder_AskSide_TakerPriceTooLow() {
        // Ask side has orders at 100.0
        askSide.placeOrder(createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0));

        // Taker bid at 99.0 — no match because taker.price < best ask
        Order taker = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 99.0, 5.0);
        assertFalse(askSide.canMatch(taker));
    }

    @Test
    public void testCanMatch_LimitOrder_BidSide_TakerPriceLowEnough() {
        // Bid side has orders at 100.0
        bidSide.placeOrder(createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0));

        // Taker ask at 100.0 — matches because taker.price <= best bid
        Order taker = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        assertTrue(bidSide.canMatch(taker));
    }

    @Test
    public void testCanMatch_LimitOrder_BidSide_TakerPriceTooHigh() {
        // Bid side has orders at 100.0
        bidSide.placeOrder(createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0));

        // Taker ask at 101.0 — no match because taker.price > best bid
        Order taker = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 101.0, 5.0);
        assertFalse(bidSide.canMatch(taker));
    }

    @Test
    public void testCanMatch_MarketOrder_EmptyBook_ReturnsFalse() {
        Order marketTaker = createOrder(1, 100, OrderSide.BID, OrderType.MARKET, 0.0, 5.0);
        assertFalse(askSide.canMatch(marketTaker));
    }

    // ==================== registerHandler/getHandler ====================

    @Test
    public void testRegisterHandler_GetHandler() {
        OrderBookSide.OrderTypeSideHandler handler = (side, order) -> null;
        askSide.registerHandler(OrderType.LIMIT, handler);
        assertSame(handler, askSide.getHandler(OrderType.LIMIT));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetHandler_Missing_ThrowsException() {
        askSide.getHandler(OrderType.LIMIT);
    }

    @Test
    public void testRegisterMultipleHandlers() {
        OrderBookSide.OrderTypeSideHandler limitHandler = (side, order) -> null;
        OrderBookSide.OrderTypeSideHandler marketHandler = (side, order) -> null;

        askSide.registerHandler(OrderType.LIMIT, limitHandler);
        askSide.registerHandler(OrderType.MARKET, marketHandler);

        assertSame(limitHandler, askSide.getHandler(OrderType.LIMIT));
        assertSame(marketHandler, askSide.getHandler(OrderType.MARKET));
    }
}
