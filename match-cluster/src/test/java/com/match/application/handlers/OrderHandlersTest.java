package com.match.application.handlers;

import com.match.application.orderbook.OrderBookSideImpl;
import com.match.domain.*;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.domain.interfaces.OrderBookSide;
import org.agrona.collections.Long2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for order type handlers: LimitOrderHandler, MarketOrderHandler, LimitMakerOrderHandler.
 */
public class OrderHandlersTest {

    private LimitOrderHandler limitHandler;
    private MarketOrderHandler marketHandler;
    private LimitMakerOrderHandler limitMakerHandler;

    @Before
    public void setUp() {
        limitHandler = new LimitOrderHandler();
        marketHandler = new MarketOrderHandler();
        limitMakerHandler = new LimitMakerOrderHandler();
    }

    // ==================== Helpers ====================

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

    private Order createMarketBuy(long id, long userId, double totalPrice) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setSide(OrderSide.BID);
        order.setType(OrderType.MARKET);
        order.setPrice(0);
        order.setQuantity(0);
        order.setRemainingQuantity(0);
        order.setTotalPrice(FixedPoint.fromDouble(totalPrice));
        return order;
    }

    private Order createMarketSell(long id, long userId, double quantity) {
        Order order = new Order();
        order.setId(id);
        order.setUserId(userId);
        order.setSide(OrderSide.ASK);
        order.setType(OrderType.MARKET);
        order.setPrice(0);
        order.setQuantity(FixedPoint.fromDouble(quantity));
        order.setRemainingQuantity(FixedPoint.fromDouble(quantity));
        return order;
    }

    /**
     * Creates an ask-side book (ascending) with orders placed.
     */
    private OrderBookSideImpl createAskSideWith(Order... orders) {
        OrderBookSideImpl side = new OrderBookSideImpl(true);
        for (Order order : orders) {
            side.placeOrder(order);
        }
        return side;
    }

    /**
     * Creates a bid-side book (descending) with orders placed.
     */
    private OrderBookSideImpl createBidSideWith(Order... orders) {
        OrderBookSideImpl side = new OrderBookSideImpl(false);
        for (Order order : orders) {
            side.placeOrder(order);
        }
        return side;
    }

    // ==================== LimitOrderHandler ====================

    @Test
    public void testLimit_FullFill() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertFalse(result.shouldPlaceOrder()); // fully filled

        OrderMatch match = result.getMatches().get(0);
        assertEquals(FixedPoint.fromDouble(100.0), match.getPrice());
        assertEquals(FixedPoint.fromDouble(10.0), match.getQuantity());
        assertEquals(0, taker.getRemainingQuantity());
    }

    @Test
    public void testLimit_PartialFill() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertTrue(result.shouldPlaceOrder()); // partially filled, remainder to book

        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(0).getQuantity());
        assertEquals(FixedPoint.fromDouble(5.0), taker.getRemainingQuantity());
    }

    @Test
    public void testLimit_MultiLevelSweep() {
        Order maker1 = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        Order maker2 = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 101.0, 5.0);
        Order maker3 = createOrder(3, 102, OrderSide.ASK, OrderType.LIMIT, 102.0, 5.0);
        OrderBookSideImpl side = createAskSideWith(maker1, maker2, maker3);

        Order taker = createOrder(4, 200, OrderSide.BID, OrderType.LIMIT, 102.0, 12.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertEquals(3, result.getMatches().size());
        // First match at 100 (best ask)
        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(0).getQuantity());
        // Second at 101
        assertEquals(FixedPoint.fromDouble(101.0), result.getMatches().get(1).getPrice());
        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(1).getQuantity());
        // Third at 102 — partial
        assertEquals(FixedPoint.fromDouble(102.0), result.getMatches().get(2).getPrice());
        assertEquals(FixedPoint.fromDouble(2.0), result.getMatches().get(2).getQuantity());

        assertFalse(result.shouldPlaceOrder()); // fully filled
        assertEquals(0, taker.getRemainingQuantity());
    }

    @Test
    public void testLimit_NoMatch_EmptyBook() {
        OrderBookSideImpl side = new OrderBookSideImpl(true); // empty

        Order taker = createOrder(1, 200, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertTrue(result.shouldPlaceOrder());
        assertEquals(FixedPoint.fromDouble(10.0), taker.getRemainingQuantity());
    }

    @Test
    public void testLimit_BidSide_SellMatchesBids() {
        // Bid side (descending): maker bids
        Order maker1 = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 5.0);
        Order maker2 = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 99.0, 5.0);
        OrderBookSideImpl side = createBidSideWith(maker1, maker2);

        // Taker sell at 99
        Order taker = createOrder(3, 200, OrderSide.ASK, OrderType.LIMIT, 99.0, 8.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertEquals(2, result.getMatches().size());
        // Best bid (100) matched first
        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(0).getQuantity());
        // Then 99
        assertEquals(FixedPoint.fromDouble(99.0), result.getMatches().get(1).getPrice());
        assertEquals(FixedPoint.fromDouble(3.0), result.getMatches().get(1).getQuantity());

        assertFalse(result.shouldPlaceOrder()); // fully filled
    }

    // ==================== MarketOrderHandler ====================

    @Test
    public void testMarketBuy_SingleLevel() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        // Market buy with budget 500 → buy 5.0 at 100.0
        Order taker = createMarketBuy(2, 200, 500.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertFalse(result.shouldPlaceOrder()); // market orders never rest on book

        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(5.0, FixedPoint.toDouble(result.getMatches().get(0).getQuantity()), 0.01);
    }

    @Test
    public void testMarketBuy_MultiLevel() {
        Order maker1 = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 5.0);
        Order maker2 = createOrder(2, 101, OrderSide.ASK, OrderType.LIMIT, 200.0, 5.0);
        OrderBookSideImpl side = createAskSideWith(maker1, maker2);

        // Market buy with budget 1500 → buy 5.0 @ 100 (cost 500), then 5.0 @ 200 (cost 1000)
        Order taker = createMarketBuy(3, 200, 1500.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertEquals(2, result.getMatches().size());
        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(5.0, FixedPoint.toDouble(result.getMatches().get(0).getQuantity()), 0.01);
        assertEquals(FixedPoint.fromDouble(200.0), result.getMatches().get(1).getPrice());
        assertEquals(5.0, FixedPoint.toDouble(result.getMatches().get(1).getQuantity()), 0.01);
    }

    @Test
    public void testMarketBuy_PartialBudgetExhaustion() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        // Budget 300 at price 100 → can only buy 3.0
        Order taker = createMarketBuy(2, 200, 300.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertEquals(1, result.getMatches().size());
        assertEquals(3.0, FixedPoint.toDouble(result.getMatches().get(0).getQuantity()), 0.01);
    }

    @Test
    public void testMarketBuy_EmptyBook() {
        OrderBookSideImpl side = new OrderBookSideImpl(true); // empty

        Order taker = createMarketBuy(1, 200, 1000.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertFalse(result.shouldPlaceOrder()); // market orders never placed
    }

    @Test
    public void testMarketSell_SingleLevel() {
        Order maker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createBidSideWith(maker);

        Order taker = createMarketSell(2, 200, 5.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertFalse(result.shouldPlaceOrder());

        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(0).getQuantity());
    }

    @Test
    public void testMarketSell_MultiLevel() {
        Order maker1 = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 3.0);
        Order maker2 = createOrder(2, 101, OrderSide.BID, OrderType.LIMIT, 99.0, 5.0);
        OrderBookSideImpl side = createBidSideWith(maker1, maker2);

        // Sell 7.0: 3.0 @ 100, 4.0 @ 99
        Order taker = createMarketSell(3, 200, 7.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertEquals(2, result.getMatches().size());
        // Best bid (100) first
        assertEquals(FixedPoint.fromDouble(100.0), result.getMatches().get(0).getPrice());
        assertEquals(FixedPoint.fromDouble(3.0), result.getMatches().get(0).getQuantity());
        // Then 99
        assertEquals(FixedPoint.fromDouble(99.0), result.getMatches().get(1).getPrice());
        assertEquals(FixedPoint.fromDouble(4.0), result.getMatches().get(1).getQuantity());
    }

    @Test
    public void testMarketSell_EmptyBook() {
        OrderBookSideImpl side = new OrderBookSideImpl(false); // empty bid side

        Order taker = createMarketSell(1, 200, 10.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertFalse(result.shouldPlaceOrder());
    }

    @Test
    public void testMarketSell_PartialFill() {
        // Only 3 available, selling 10
        Order maker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 3.0);
        OrderBookSideImpl side = createBidSideWith(maker);

        Order taker = createMarketSell(2, 200, 10.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertEquals(1, result.getMatches().size());
        assertEquals(FixedPoint.fromDouble(3.0), result.getMatches().get(0).getQuantity());
        // 7.0 remaining on taker but market orders don't rest on book
        assertEquals(FixedPoint.fromDouble(7.0), taker.getRemainingQuantity());
    }

    // ==================== LimitMakerOrderHandler ====================

    @Test
    public void testLimitMaker_WouldMatch_Rejected() {
        // Ask side has order at 100
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        // Limit maker buy at 100 — would cross spread → rejected
        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT_MAKER, 100.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertFalse(result.shouldPlaceOrder()); // rejected — no matches, no placement
    }

    @Test
    public void testLimitMaker_WouldMatch_HigherPrice_Rejected() {
        // Ask side has order at 100
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        // Limit maker buy at 110 — would cross spread → rejected
        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT_MAKER, 110.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertFalse(result.shouldPlaceOrder());
    }

    @Test
    public void testLimitMaker_NoMatch_Placed() {
        // Ask side has order at 100
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createAskSideWith(maker);

        // Limit maker buy at 99 — would NOT cross spread → placed
        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT_MAKER, 99.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertTrue(result.shouldPlaceOrder());
        assertSame(taker, result.getPlaceOrder());
    }

    @Test
    public void testLimitMaker_EmptyBook_Placed() {
        OrderBookSideImpl side = new OrderBookSideImpl(true); // empty

        Order taker = createOrder(1, 200, OrderSide.BID, OrderType.LIMIT_MAKER, 100.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertTrue(result.shouldPlaceOrder());
    }

    @Test
    public void testLimitMaker_BidSide_SellWouldNotMatch_Placed() {
        // Bid side has order at 100
        Order maker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createBidSideWith(maker);

        // Limit maker sell at 101 — would NOT cross spread
        Order taker = createOrder(2, 200, OrderSide.ASK, OrderType.LIMIT_MAKER, 101.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertTrue(result.shouldPlaceOrder());
    }

    @Test
    public void testLimitMaker_BidSide_SellWouldMatch_Rejected() {
        // Bid side has order at 100
        Order maker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl side = createBidSideWith(maker);

        // Limit maker sell at 100 — would cross spread → rejected
        Order taker = createOrder(2, 200, OrderSide.ASK, OrderType.LIMIT_MAKER, 100.0, 5.0);
        OrderBookSide.MatchResult result = limitMakerHandler.handle(side, taker);

        assertFalse(result.hasMatches());
        assertFalse(result.shouldPlaceOrder());
    }

    // ==================== Fallback path (non-OrderBookSideImpl) ====================

    /**
     * Wrapper around OrderBookSideImpl that is NOT an instance of OrderBookSideImpl.
     * This forces handlers to use the fallback code path (getSortedPrices/getPriceLevelCount).
     */
    private static class DelegatingOrderBookSide implements OrderBookSide {
        private final OrderBookSideImpl delegate;

        DelegatingOrderBookSide(OrderBookSideImpl delegate) {
            this.delegate = delegate;
        }

        @Override public void placeOrder(Order order) { delegate.placeOrder(order); }
        @Override public void cancelOrder(Order order) { delegate.cancelOrder(order); }
        @Override public void removeOrder(Order order) { delegate.removeOrder(order); }
        @Override public void createPriceLevel(Order order) { delegate.createPriceLevel(order); }
        @Override public void removePriceLevel(long price) { delegate.removePriceLevel(price); }
        @Override public long getBestPrice() { return delegate.getBestPrice(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public long[] getSortedPrices() { return delegate.getSortedPrices(); }
        @Override public int getPriceLevelCount() { return delegate.getPriceLevelCount(); }
        @Override public Long2ObjectHashMap<Level> getPriceLevels() { return delegate.getPriceLevels(); }
        @Override public Level getLevel(long price) { return delegate.getLevel(price); }
        @Override public void registerHandler(OrderType type, OrderTypeSideHandler handler) { delegate.registerHandler(type, handler); }
        @Override public boolean canMatch(Order taker) { return delegate.canMatch(taker); }
        @Override public OrderTypeSideHandler getHandler(OrderType type) { return delegate.getHandler(type); }
    }

    @Test
    public void testLimit_FallbackPath_FullFill() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl realSide = createAskSideWith(maker);
        DelegatingOrderBookSide side = new DelegatingOrderBookSide(realSide);

        Order taker = createOrder(2, 200, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSide.MatchResult result = limitHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertFalse(result.shouldPlaceOrder());
        assertEquals(0, taker.getRemainingQuantity());
    }

    @Test
    public void testMarketBuy_FallbackPath() {
        Order maker = createOrder(1, 100, OrderSide.ASK, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl realSide = createAskSideWith(maker);
        DelegatingOrderBookSide side = new DelegatingOrderBookSide(realSide);

        Order taker = createMarketBuy(2, 200, 500.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertEquals(5.0, FixedPoint.toDouble(result.getMatches().get(0).getQuantity()), 0.01);
    }

    @Test
    public void testMarketSell_FallbackPath() {
        Order maker = createOrder(1, 100, OrderSide.BID, OrderType.LIMIT, 100.0, 10.0);
        OrderBookSideImpl realSide = createBidSideWith(maker);
        DelegatingOrderBookSide side = new DelegatingOrderBookSide(realSide);

        Order taker = createMarketSell(2, 200, 5.0);
        OrderBookSide.MatchResult result = marketHandler.handle(side, taker);

        assertTrue(result.hasMatches());
        assertEquals(1, result.getMatches().size());
        assertEquals(FixedPoint.fromDouble(5.0), result.getMatches().get(0).getQuantity());
    }
}
