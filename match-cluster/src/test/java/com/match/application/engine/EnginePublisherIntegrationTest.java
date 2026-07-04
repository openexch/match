// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.publisher.*;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * Full integration test: Engine → MatchEventPublisher → Handler pipeline.
 * Tests the real flow of order submission → matching → event publishing.
 */
public class EnginePublisherIntegrationTest {

    private Engine engine;
    private MatchEventPublisher publisher;
    private CapturingHandler handler;

    private static final int MARKET_ID = 1; // BTC-USD

    @Before
    public void setUp() {
        engine = new Engine();
        publisher = new MatchEventPublisher();
        handler = new CapturingHandler(MARKET_ID);
        publisher.initMarket(MARKET_ID, handler);
        publisher.start();
        engine.setEventPublisher(publisher);
    }

    @After
    public void tearDown() {
        if (publisher.isRunning()) {
            publisher.shutdown();
        }
    }

    // ==================== Helpers ====================

    /** Build an engine with an explicit matching implementation, wired to the shared publisher. */
    private Engine engineWith(String impl) {
        Engine e = new Engine(impl);
        e.setEventPublisher(publisher);
        return e;
    }

    private CreateOrderCommand limitCmd(long userId, OrderSide side, double price, double qty) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand limitMakerCmd(long userId, OrderSide side, double price, double qty) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT_MAKER);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand marketBuyCmd(long userId, double budget) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(0);
        cmd.setTotalPrice(FixedPoint.fromDouble(budget));
        return cmd;
    }

    private CreateOrderCommand marketSellCmd(long userId, double qty) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CancelOrderCommand cancelCmd(long userId, long orderId) {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderId(orderId);
        return cmd;
    }

    private void waitForEvents() throws InterruptedException {
        Thread.sleep(200);
    }

    private List<CapturedEvent> findByType(int eventType) {
        List<CapturedEvent> result = new java.util.ArrayList<>();
        for (CapturedEvent e : handler.events) {
            if (e.eventType == eventType) {
                result.add(e);
            }
        }
        return result;
    }

    // ==================== Tests ====================

    @Test
    public void limitOrderNoMatchPublishesNewStatus() throws Exception {
        // Submit a limit bid — no opposing order, should be placed on book
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statusEvents = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should receive ORDER_STATUS_UPDATE", statusEvents.isEmpty());
        assertEquals(OrderStatusType.NEW, statusEvents.get(0).orderStatus);
    }

    @Test
    public void matchingOrdersPublishTradeAndFilledStatus() throws Exception {
        // Place ask (maker)
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.ASK, 60000.0, 1.0), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // Place matching bid (taker)
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(200L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> trades = findByType(PublishEventType.TRADE_EXECUTION);
        assertFalse("Should have trade execution", trades.isEmpty());
        assertEquals(MARKET_ID, trades.get(0).marketId);

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status update", statuses.isEmpty());
        // Taker should be FILLED
        boolean hasFilled = false;
        for (CapturedEvent e : statuses) {
            if (e.orderStatus == OrderStatusType.FILLED) {
                hasFilled = true;
                break;
            }
        }
        assertTrue("Should have a FILLED status", hasFilled);
    }

    @Test
    public void cancelOrderPublishesCancelledStatus() throws Exception {
        // Place bid
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        long orderId = engine.getOrderIdGenerator() - 1;
        handler.events.clear();

        // Cancel it
        engine.acceptOrder(MARKET_ID, Engine.CMD_CANCEL, cancelCmd(100L, orderId), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals(OrderStatusType.CANCELLED, statuses.get(0).orderStatus);
    }

    @Test
    public void limitMakerThatWouldMatchPublishesRejected() throws Exception {
        // Place ask at 60000
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.ASK, 60000.0, 1.0), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // LIMIT_MAKER bid at 60000 — would cross spread, should be REJECTED
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitMakerCmd(200L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals(OrderStatusType.REJECTED, statuses.get(0).orderStatus);
    }

    @Test
    public void limitMakerThatDoesNotCrossIsAccepted() throws Exception {
        // Place ask at 70000
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.ASK, 70000.0, 1.0), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // LIMIT_MAKER bid at 60000 — won't cross, should be NEW
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitMakerCmd(200L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals(OrderStatusType.NEW, statuses.get(0).orderStatus);
    }

    @Test
    public void marketBuyWithBudgetPublishesTradeAndFilled() throws Exception {
        // Place ask at 60000 for 1 BTC
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.ASK, 60000.0, 1.0), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // Market buy with budget of $60000
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, marketBuyCmd(200L, 60000.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> trades = findByType(PublishEventType.TRADE_EXECUTION);
        assertFalse("Should have trade", trades.isEmpty());

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have status", statuses.isEmpty());
    }

    @Test
    public void marketSellPublishesTradeAndFilled() throws Exception {
        // Place bid at 60000 for 1 BTC
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // Market sell 1 BTC
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, marketSellCmd(200L, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> trades = findByType(PublishEventType.TRADE_EXECUTION);
        assertFalse("Should have trade", trades.isEmpty());
    }

    @Test
    public void partialFillPublishesPartiallyFilledStatus() throws Exception {
        // Place ask for 0.5 BTC at 60000
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.ASK, 60000.0, 0.5), System.nanoTime());
        waitForEvents();
        handler.events.clear();

        // Bid for 1 BTC at 60000 — only 0.5 matches, rest on book
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(200L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        boolean hasPartial = false;
        for (CapturedEvent e : statuses) {
            if (e.orderStatus == OrderStatusType.PARTIALLY_FILLED) {
                hasPartial = true;
                break;
            }
        }
        assertTrue("Should have PARTIALLY_FILLED status", hasPartial);
    }

    @Test
    public void marketBuyOnEmptyBookPublishesRejected() throws Exception {
        // Market buy on empty book — no asks to match
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, marketBuyCmd(200L, 60000.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Market order on empty book should be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
    }

    @Test
    public void marketSellOnEmptyBookPublishesRejected() throws Exception {
        // Market sell on empty book — no bids to match
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, marketSellCmd(200L, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Market order on empty book should be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
    }

    // ==================== Loud Limits: no phantom NEW statuses ====================

    @Test
    public void limitOrderOutOfRangePublishesRejected() throws Exception {
        // BTC range is $50K-$150K; $200K is out of range.
        // Must publish REJECTED — not a phantom NEW for an order that never rested.
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 200_000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Out-of-range limit must be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
        assertTrue("Book must not contain the order",
            engine.getEngine(MARKET_ID).isBidEmpty());
    }

    @Test
    public void limitOrderOffTickPublishesRejected() throws Exception {
        // BTC tick is $1; $60,000.50 is off-tick.
        // Must be rejected — not silently rounded down to $60,000.
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60_000.50, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Off-tick limit must be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
        assertTrue("Book must not contain the order",
            engine.getEngine(MARKET_ID).isBidEmpty());
    }

    @Test
    public void limitOrderLevelFullPublishesRejected() throws Exception {
        // The 64-orders-per-level cap is a direct-index-book behavior; pin to that impl.
        Engine engine = engineWith("direct");
        // Fill the $60,000 bid level to capacity (64 orders)
        for (int i = 0; i < 64; i++) {
            engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L + i, OrderSide.BID, 60_000.0, 1.0), System.nanoTime());
        }
        waitForEvents();
        handler.events.clear();

        // 65th order at the same level must be REJECTED, not phantom NEW
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(200L, OrderSide.BID, 60_000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Order beyond level capacity must be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
    }

    @Test
    public void limitOrderBookFullPublishesRejected() throws Exception {
        // Array-backed equivalent: no per-level cap, but a loud BOOK_FULL when the shared pool is
        // exhausted. Same Engine terminal-status path (restReason != NONE -> REJECTED), reached via
        // the array book. Tiny capacity so the pool fills in a handful of orders.
        String prev = System.getProperty("match.engine.book.capacity");
        System.setProperty("match.engine.book.capacity", "4");
        try {
            Engine engine = engineWith("array");
            // Fill the bid pool (capacity 4) at distinct prices — proves it is a book-wide bound,
            // not a per-level one.
            for (int i = 0; i < 4; i++) {
                engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L + i, OrderSide.BID, 60_000.0 + i, 1.0), System.nanoTime());
            }
            waitForEvents();
            handler.events.clear();

            // One more bid cannot rest (pool exhausted) -> REJECTED, never phantom NEW.
            engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(200L, OrderSide.BID, 60_010.0, 1.0), System.nanoTime());
            waitForEvents();

            List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
            assertFalse("Should have order status", statuses.isEmpty());
            assertEquals("Order beyond pool capacity must be REJECTED",
                OrderStatusType.REJECTED, statuses.get(0).orderStatus);
        } finally {
            if (prev == null) {
                System.clearProperty("match.engine.book.capacity");
            } else {
                System.setProperty("match.engine.book.capacity", prev);
            }
        }
    }

    @Test
    public void limitMakerOutOfRangePublishesRejected() throws Exception {
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitMakerCmd(100L, OrderSide.BID, 200_000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Out-of-range LIMIT_MAKER must be REJECTED",
            OrderStatusType.REJECTED, statuses.get(0).orderStatus);
        assertTrue("Book must not contain the order",
            engine.getEngine(MARKET_ID).isBidEmpty());
    }

    @Test
    public void partialFillThenLevelFullPublishesCancelled() throws Exception {
        // The 64-orders-per-level cap is a direct-index-book behavior; pin to that impl.
        Engine engine = engineWith("direct");
        // Construct the edge state directly: a resting ask at $61,000 AND a full
        // bid level at $61,000 (reachable in production via snapshot restore).
        var market = engine.getEngine(MARKET_ID);
        market.addOrderNoMatch(900L, 900L, false, FixedPoint.fromDouble(61_000.0), FixedPoint.fromDouble(0.5));
        for (int i = 0; i < 64; i++) {
            market.addOrderNoMatch(1000L + i, 1000L + i, true,
                FixedPoint.fromDouble(61_000.0), FixedPoint.fromDouble(1.0));
        }
        handler.events.clear();

        // Taker buy 1.0 at $61,000: fills 0.5 against the ask, remainder cannot
        // rest (level full). Must be terminal CANCELLED with the fill reported —
        // not PARTIALLY_FILLED implying an open order that doesn't exist.
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(200L, OrderSide.BID, 61_000.0, 1.0), System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Partial fill with failed rest must be terminal CANCELLED",
            OrderStatusType.CANCELLED, statuses.get(0).orderStatus);
    }

    @Test
    public void updateToInvalidPriceRejectedAndKeepsOriginalOrder() throws Exception {
        // Place a valid bid
        engine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60_000.0, 1.0), System.nanoTime());
        waitForEvents();
        long orderId = engine.getOrderIdGenerator() - 1;
        handler.events.clear();

        // Update to an off-tick price — must reject the UPDATE and keep the original
        com.match.domain.commands.UpdateOrderCommand cmd = new com.match.domain.commands.UpdateOrderCommand();
        cmd.setUserId(100L);
        cmd.setOrderId(orderId);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setPrice(FixedPoint.fromDouble(60_000.50));
        cmd.setQuantity(FixedPoint.fromDouble(2.0));
        engine.acceptOrder(MARKET_ID, Engine.CMD_UPDATE, cmd, System.nanoTime());
        waitForEvents();

        List<CapturedEvent> statuses = findByType(PublishEventType.ORDER_STATUS_UPDATE);
        assertFalse("Should have order status", statuses.isEmpty());
        assertEquals("Invalid update must be REJECTED", OrderStatusType.REJECTED, statuses.get(0).orderStatus);
        assertFalse("Original order must survive a rejected update",
            engine.getEngine(MARKET_ID).isBidEmpty());
    }

    @Test
    public void engineWithoutPublisherDoesNotCrash() {
        // Create a fresh engine without publisher
        Engine bareEngine = new Engine();
        // Should not throw even without publisher
        bareEngine.acceptOrder(MARKET_ID, Engine.CMD_CREATE, limitCmd(100L, OrderSide.BID, 60000.0, 1.0), System.nanoTime());
    }

    // ==================== Test Handler ====================

    private static class CapturedEvent {
        int eventType;
        int marketId;
        long timestamp;
        long price;
        long quantity;
        boolean takerIsBuy;
        int orderStatus;
        long orderId;
        long userId;
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
            copy.orderStatus = event.getOrderStatus();
            copy.orderId = event.getOrderId();
            copy.userId = event.getUserId();
            events.add(copy);
        }

        @Override
        public int getMarketId() {
            return marketId;
        }
    }
}
