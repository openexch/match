// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.MatchingEngine;
import com.match.application.publisher.OrderStatusType;
import com.match.determinism.EngineEvent;
import com.match.determinism.RecordingEventSink;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for Engine — main matching engine and MarketConfig.
 */
public class EngineTest {

    private Engine engine;

    @Before
    public void setUp() {
        engine = new Engine();
    }

    // ==================== Helpers ====================

    private CreateOrderCommand createLimitCmd(long userId, OrderSide side, double price, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand createMarketBuyCmd(long userId, double totalPrice) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(0);
        cmd.setTotalPrice(FixedPoint.fromDouble(totalPrice));
        return cmd;
    }

    private CreateOrderCommand createMarketSellCmd(long userId, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CreateOrderCommand createLimitMakerCmd(long userId, OrderSide side, double price, double quantity) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT_MAKER);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private CancelOrderCommand createCancelCmd(long userId, long orderId) {
        CancelOrderCommand cmd = new CancelOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderId(orderId);
        return cmd;
    }

    private UpdateOrderCommand createUpdateCmd(long userId, long orderId, OrderSide side,
            double price, double quantity) {
        UpdateOrderCommand cmd = new UpdateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderId(orderId);
        cmd.setOrderSide(side);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(quantity));
        return cmd;
    }

    /** UPDATE command carrying an explicit LIMIT_MAKER order type (post-only amend, match#92). */
    private UpdateOrderCommand updateLimitMakerCmd(long userId, long orderId, OrderSide side,
            double price, double quantity) {
        UpdateOrderCommand cmd = createUpdateCmd(userId, orderId, side, price, quantity);
        cmd.setOrderType(OrderType.LIMIT_MAKER);
        return cmd;
    }

    /** First trade event captured by the sink, or null if none executed. */
    private EngineEvent.Trade firstTrade(RecordingEventSink sink) {
        for (EngineEvent e : sink.events()) {
            if (e instanceof EngineEvent.Trade t) {
                return t;
            }
        }
        return null;
    }

    /** Last order-status event captured by the sink, or null if none were published. */
    private EngineEvent.Status lastStatus(RecordingEventSink sink) {
        EngineEvent.Status last = null;
        for (EngineEvent e : sink.events()) {
            if (e instanceof EngineEvent.Status s) {
                last = s;
            }
        }
        return last;
    }

    private boolean hasTrade(RecordingEventSink sink) {
        for (EngineEvent e : sink.events()) {
            if (e instanceof EngineEvent.Trade) {
                return true;
            }
        }
        return false;
    }

    // ==================== Initialization ====================

    @Test
    public void testEngineInitialization_AllMarketsCreated() {
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            assertNotNull("Engine missing for market " + config.symbol,
                engine.getEngine(config.marketId));
        }
    }

    @Test
    public void testEngineInitialization_AllFiveMarkets() {
        assertNotNull(engine.getEngine(Engine.MARKET_BTC_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_ETH_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_SOL_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_XRP_USD));
        assertNotNull(engine.getEngine(Engine.MARKET_DOGE_USD));
    }

    @Test
    public void testGetEngine_UnknownMarket_ReturnsNull() {
        assertNull(engine.getEngine(999));
    }

    @Test
    public void testGetEngines_ReturnsAllEngines() {
        Int2ObjectHashMap<MatchingEngine> engines = engine.getEngines();
        assertNotNull(engines);
        assertEquals(MarketConfig.ALL_MARKETS.length, engines.size());
    }

    // ==================== Limit Order — No Match ====================

    @Test
    public void testAcceptOrder_LimitBuyNoMatch_AddedToBook() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());
        assertTrue(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_LimitSellNoMatch_AddedToBook() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isBidEmpty());
        assertFalse(btcEngine.isAskEmpty());
    }

    // ==================== Limit Order — Full Match ====================

    @Test
    public void testAcceptOrder_LimitFullMatch() {
        // Place ask
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Place matching bid
        CreateOrderCommand bidCmd = createLimitCmd(101, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Both sides should be empty after full match
        assertTrue(btcEngine.isAskEmpty());
        assertTrue(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitPartialMatch() {
        // Place ask with qty 5
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Place bid with qty 3 — partial match
        CreateOrderCommand bidCmd = createLimitCmd(101, OrderSide.BID, 100000.0, 3.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Ask should still have remaining, bid consumed
        assertFalse(btcEngine.isAskEmpty());
        assertTrue(btcEngine.isBidEmpty());
    }

    // ==================== Market Order ====================

    @Test
    public void testAcceptOrder_MarketBuy() {
        // Place ask at 100000
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 2.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Market buy with budget for 1 unit (100000)
        CreateOrderCommand marketCmd = createMarketBuyCmd(101, 100000.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Ask should still have 1 remaining
        assertFalse(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_MarketSell() {
        // Place bid at 100000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Market sell 3 units
        CreateOrderCommand marketCmd = createMarketSellCmd(101, 3.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Bid should still have 2 remaining
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_MarketOrderOnEmptyBook() {
        // Market sell on empty book — should not throw
        CreateOrderCommand marketCmd = createMarketSellCmd(101, 5.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, marketCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isBidEmpty());
        assertTrue(btcEngine.isAskEmpty());
    }

    // ==================== Limit Maker ====================

    @Test
    public void testAcceptOrder_LimitMaker_PlacedWhenNoCross() {
        // Empty book — limit maker should be placed
        CreateOrderCommand cmd = createLimitMakerCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMaker_RejectedWhenWouldCross() {
        // Place ask at 100000
        CreateOrderCommand askCmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, askCmd, System.nanoTime());

        // Limit maker buy at 100000 — would cross → rejected
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        // Bid should still be empty (order rejected)
        assertTrue(btcEngine.isBidEmpty());
        // Ask should still have the original order
        assertFalse(btcEngine.isAskEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMakerSell_PlacedWhenNoCross() {
        // Place bid at 99000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 99000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Limit maker sell at 100000 — would not cross
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isAskEmpty());
        assertFalse(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_LimitMakerSell_RejectedWhenWouldCross() {
        // Place bid at 100000
        CreateOrderCommand bidCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, bidCmd, System.nanoTime());

        // Limit maker sell at 100000 — would cross
        CreateOrderCommand makerCmd = createLimitMakerCmd(101, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, makerCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertTrue(btcEngine.isAskEmpty()); // rejected
        assertFalse(btcEngine.isBidEmpty()); // original bid still there
    }

    // ==================== Quantity Validation (match#91) ====================

    @Test
    public void testLimitZeroQuantity_Rejected_StatusPublished_NothingRests() {
        // Silent-egress gap: a qty=0 LIMIT never matched and never rested, so NO status ever fired.
        // It must now publish REJECTED so the OMS learns the order's fate.
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        long before = engine.getInvalidQuantityRejectCount();

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.BID, 60000.0, 0.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull("qty=0 LIMIT must publish a status (no silent loss)", s);
        assertEquals(OrderStatusType.REJECTED, s.orderStatus());
        assertTrue("book must not contain the order", engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertEquals(before + 1, engine.getInvalidQuantityRejectCount());
    }

    @Test
    public void testLimitNegativeQuantity_Rejected_StatusPublished_NothingRests() {
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        long before = engine.getInvalidQuantityRejectCount();

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.ASK, 60000.0, -1.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull("qty<0 LIMIT must publish a status (no silent loss)", s);
        assertEquals(OrderStatusType.REJECTED, s.orderStatus());
        assertTrue("book must not contain the order", engine.getEngine(Engine.MARKET_BTC_USD).isAskEmpty());
        assertEquals(before + 1, engine.getInvalidQuantityRejectCount());
    }

    @Test
    public void testLimitMakerZeroQuantity_Rejected_NothingRests() {
        // The poison-pill case: a qty=0 LIMIT_MAKER would rest at the head of its level and brick it.
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        long before = engine.getInvalidQuantityRejectCount();

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitMakerCmd(100, OrderSide.BID, 60000.0, 0.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull("qty=0 LIMIT_MAKER must publish a status", s);
        assertEquals(OrderStatusType.REJECTED, s.orderStatus());
        assertTrue("nothing may rest", engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertEquals(before + 1, engine.getInvalidQuantityRejectCount());
    }

    @Test
    public void testMarketBuyZeroBudget_RejectedWithInvalidQuantity_LiquidityUntouched() {
        // Rest an ask so there IS liquidity; a zero-budget market buy must still reject at admission
        // (INVALID_QUANTITY) and skip the sweep — not silently look like a no-liquidity reject.
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.ASK, 60000.0, 1.0), 1000L);
        long before = engine.getInvalidQuantityRejectCount();

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createMarketBuyCmd(200, 0.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals(OrderStatusType.REJECTED, s.orderStatus());
        assertEquals("INVALID_QUANTITY admission reject must fire (not the no-liquidity path)",
            before + 1, engine.getInvalidQuantityRejectCount());
        // The resting ask is untouched (the sweep was skipped).
        assertFalse(engine.getEngine(Engine.MARKET_BTC_USD).isAskEmpty());
    }

    @Test
    public void testMarketSellZeroQuantity_RejectedWithInvalidQuantity_LiquidityUntouched() {
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.BID, 60000.0, 1.0), 1000L);
        long before = engine.getInvalidQuantityRejectCount();

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createMarketSellCmd(200, 0.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals(OrderStatusType.REJECTED, s.orderStatus());
        assertEquals("INVALID_QUANTITY admission reject must fire (not the no-liquidity path)",
            before + 1, engine.getInvalidQuantityRejectCount());
        assertFalse(engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
    }

    @Test
    public void testUpdateToZeroQuantity_Rejected_OldOrderSurvivesAndStillMatches() {
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);

        // Rest a live bid for 2.0 @ 60000.
        long startId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.BID, 60000.0, 2.0), 1000L);
        assertFalse(engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        long before = engine.getInvalidQuantityRejectCount();

        // UPDATE that order to qty=0 — must REJECT the amend and leave the OLD order resting.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_UPDATE,
            createUpdateCmd(100, startId, OrderSide.BID, 60000.0, 0.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals("UPDATE to qty=0 is a REJECT, never an implicit cancel",
            OrderStatusType.REJECTED, s.orderStatus());
        assertEquals(before + 1, engine.getInvalidQuantityRejectCount());
        assertFalse("old order must survive a rejected amend",
            engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());

        // Prove the surviving order still matches: a market sell of 2.0 fully consumes it.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createMarketSellCmd(200, 2.0), 1000L);
        assertTrue("surviving order should have matched and emptied the book",
            engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertTrue("a trade must have executed against the surviving order", hasTrade(sink));
    }

    // ==================== Limit Maker Amend — Post-Only (match#92) ====================

    @Test
    public void testUpdateLimitMakerAmend_WouldCross_Rejected_OldOrderSurvivesAndMatches() {
        // The core bug: an amended LIMIT_MAKER that now crosses used to execute as a taker,
        // silently stripping post-only. It must instead REJECT and leave the old order resting.
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);

        // Resting SELL ask @60000; post-only BUY @59000 rests (does not cross).
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.ASK, 60000.0, 5.0), 1000L);
        long makerId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitMakerCmd(200, OrderSide.BID, 59000.0, 5.0), 1000L);
        assertFalse("post-only bid should rest", engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());

        // Amend the post-only bid UP to 61000 — now it WOULD cross the 60000 ask.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_UPDATE,
            updateLimitMakerCmd(200, makerId, OrderSide.BID, 61000.0, 5.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals("crossing LIMIT_MAKER amend must REJECT (post-only preserved)",
            OrderStatusType.REJECTED, s.orderStatus());
        assertEquals("reject must reference the OLD resting order", makerId, s.orderId());
        assertFalse("old post-only bid must remain resting after a rejected amend",
            engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertFalse("the rejected amend must not have traded", hasTrade(sink));

        // The surviving bid is still tradable at its ORIGINAL price: a market sell fills @59000.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createMarketSellCmd(300, 5.0), 1000L);
        EngineEvent.Trade t = firstTrade(sink);
        assertNotNull("surviving post-only bid must still match", t);
        assertEquals("must fill at the resting bid's original price",
            FixedPoint.fromDouble(59000.0), t.price());
        assertEquals("the surviving order is the maker", makerId, t.makerOrderId());
    }

    @Test
    public void testUpdateLimitMakerAmend_NonCrossing_CancelledThenNew_NoTrade() {
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);

        // Ask @60000; post-only BUY @59000 rests.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.ASK, 60000.0, 1.0), 1000L);
        long makerId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitMakerCmd(200, OrderSide.BID, 59000.0, 1.0), 1000L);
        long newId = engine.getOrderIdGenerator(); // the replacement will take this id

        // Amend UP to 59500 — still below the 60000 ask, so it does NOT cross: CANCELLED(old)+NEW(new).
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_UPDATE,
            updateLimitMakerCmd(200, makerId, OrderSide.BID, 59500.0, 1.0), 1000L);

        assertFalse("a non-crossing post-only amend must not trade", hasTrade(sink));

        boolean cancelledOld = false;
        for (EngineEvent e : sink.events()) {
            if (e instanceof EngineEvent.Status st && st.orderId() == makerId
                    && st.orderStatus() == OrderStatusType.CANCELLED) {
                cancelledOld = true;
            }
        }
        assertTrue("old order must be CANCELLED by the amend", cancelledOld);

        EngineEvent.Status last = lastStatus(sink);
        assertNotNull(last);
        assertEquals("replacement must be published NEW", OrderStatusType.NEW, last.orderStatus());
        assertEquals("replacement uses the next engine orderId", newId, last.orderId());
        assertEquals("replacement rests at the amended price",
            FixedPoint.fromDouble(59500.0), last.orderPrice());
        assertEquals("replacement rests the full quantity",
            FixedPoint.fromDouble(1.0), last.remainingQty());
        assertEquals("NEW carries zero filled", 0L, last.filledQty());

        // Book now has exactly the replacement resting at the amended price.
        assertFalse(engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertEquals(FixedPoint.fromDouble(59500.0),
            engine.getEngine(Engine.MARKET_BTC_USD).getBestBid());
    }

    @Test
    public void testUpdateLimitMakerAmend_CannotRest_Rejected() {
        // The replacement cannot rest → loud REJECTED (old order already gone), never a phantom NEW.
        // Forced via the direct engine's hard 64-orders/level cap: the array engine can't fail a
        // same-side amend (cancel frees a slot the re-add reclaims), so this terminal branch is
        // exercised on the direct impl.
        Engine directEngine = new Engine("direct");
        RecordingEventSink sink = new RecordingEventSink();
        directEngine.setEventPublisher(sink);

        // Fill the 59000 bid level to its 64-order cap (post-only, empty ask book → none cross).
        for (int i = 0; i < 64; i++) {
            directEngine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
                createLimitMakerCmd(1000 + i, OrderSide.BID, 59000.0, 1.0), 1000L);
        }
        // One more post-only bid at a DIFFERENT level (58000), which we then try to move onto 59000.
        long movingId = directEngine.getOrderIdGenerator();
        directEngine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitMakerCmd(200, OrderSide.BID, 58000.0, 1.0), 1000L);

        // Amend it to 59000 (non-crossing: no asks). The cancel frees its 58000 slot, but the 59000
        // level is already full → addOrderNoMatch returns LEVEL_FULL → loud REJECTED (old gone).
        directEngine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_UPDATE,
            updateLimitMakerCmd(200, movingId, OrderSide.BID, 59000.0, 1.0), 1000L);

        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals("a replacement that cannot rest must be REJECTED, not a phantom NEW",
            OrderStatusType.REJECTED, s.orderStatus());
        assertFalse("the failed amend must not have traded", hasTrade(sink));
        // The full 59000 level still tops the book (the moved order's 58000 level is now empty).
        assertEquals(FixedPoint.fromDouble(59000.0),
            directEngine.getEngine(Engine.MARKET_BTC_USD).getBestBid());
    }

    @Test
    public void testUpdatePlainLimitAmend_StillMatches_Unchanged() {
        // Regression guard: a plain LIMIT amend (absent/LIMIT order type) must keep its
        // cancel-and-replace-that-MATCHES behavior — match#92 must affect LIMIT_MAKER only.
        RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);

        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(100, OrderSide.ASK, 60000.0, 1.0), 1000L);
        long bidId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE,
            createLimitCmd(200, OrderSide.BID, 59000.0, 1.0), 1000L);

        // Plain LIMIT amend (createUpdateCmd leaves the type absent → LIMIT path) UP to 60000: crosses.
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_UPDATE,
            createUpdateCmd(200, bidId, OrderSide.BID, 60000.0, 1.0), 1000L);

        assertTrue("plain LIMIT amend that crosses must still execute", hasTrade(sink));
        EngineEvent.Status s = lastStatus(sink);
        assertNotNull(s);
        assertEquals("crossing plain LIMIT amend fills", OrderStatusType.FILLED, s.orderStatus());
        assertTrue("both sides consumed by the fill",
            engine.getEngine(Engine.MARKET_BTC_USD).isAskEmpty()
                && engine.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
    }

    // ==================== Cancel Order ====================

    @Test
    public void testAcceptOrder_Cancel_Successful() {
        // Place a limit buy — will get orderId 1
        CreateOrderCommand createCmd = createLimitCmd(100, OrderSide.BID, 100000.0, 1.0);
        long startId = engine.getOrderIdGenerator();
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, createCmd, System.nanoTime());

        MatchingEngine btcEngine = engine.getEngine(Engine.MARKET_BTC_USD);
        assertFalse(btcEngine.isBidEmpty());

        // Cancel using the generated order ID
        CancelOrderCommand cancelCmd = createCancelCmd(100, startId);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CANCEL, cancelCmd, System.nanoTime());

        assertTrue(btcEngine.isBidEmpty());
    }

    @Test
    public void testAcceptOrder_Cancel_NonExistent() {
        // Cancel a non-existent order — should not throw
        CancelOrderCommand cancelCmd = createCancelCmd(100, 9999);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CANCEL, cancelCmd, System.nanoTime());
        // No exception = pass
    }

    // ==================== Unknown Market ====================

    @Test
    public void testAcceptOrder_UnknownMarket_SilentlyIgnored() {
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.BID, 100.0, 1.0);
        // Should not throw
        engine.acceptOrder(999, Engine.CMD_CREATE, cmd, System.nanoTime());
    }

    // ==================== Order ID Generation ====================

    @Test
    public void testOrderIdGenerator_Increments() {
        long id1 = engine.getOrderIdGenerator();
        CreateOrderCommand cmd1 = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd1, System.nanoTime());
        long id2 = engine.getOrderIdGenerator();
        assertEquals(id1 + 1, id2);

        CreateOrderCommand cmd2 = createLimitCmd(101, OrderSide.ASK, 100001.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd2, System.nanoTime());
        long id3 = engine.getOrderIdGenerator();
        assertEquals(id2 + 1, id3);
    }

    @Test
    public void testOrderIdGenerator_SetAndGet() {
        engine.setOrderIdGenerator(1000);
        assertEquals(1000, engine.getOrderIdGenerator());
    }

    @Test
    public void testOrderIdGenerator_SnapshotRestore() {
        // Simulate some orders
        CreateOrderCommand cmd = createLimitCmd(100, OrderSide.ASK, 100000.0, 1.0);
        engine.acceptOrder(Engine.MARKET_BTC_USD, Engine.CMD_CREATE, cmd, System.nanoTime());

        long savedId = engine.getOrderIdGenerator();

        // Restore
        Engine newEngine = new Engine();
        newEngine.setOrderIdGenerator(savedId);
        assertEquals(savedId, newEngine.getOrderIdGenerator());
    }

    // ==================== Event Publisher ====================

    @Test
    public void testEventPublisher_NullByDefault() {
        assertNull(engine.getEventPublisher());
    }

    // ==================== MarketConfig ====================

    @Test
    public void testMarketConfig_GetById() {
        MarketConfig btc = MarketConfig.getById(1);
        assertNotNull(btc);
        assertEquals("BTC-USD", btc.symbol);
        assertEquals(1, btc.marketId);
    }

    @Test
    public void testMarketConfig_GetById_AllMarkets() {
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            MarketConfig found = MarketConfig.getById(config.marketId);
            assertNotNull(found);
            assertEquals(config.symbol, found.symbol);
        }
    }

    @Test
    public void testMarketConfig_GetById_Unknown_ReturnsNull() {
        assertNull(MarketConfig.getById(999));
    }

    @Test
    public void testMarketConfig_GetBySymbol() {
        MarketConfig eth = MarketConfig.getBySymbol("ETH-USD");
        assertNotNull(eth);
        assertEquals(2, eth.marketId);
    }

    @Test
    public void testMarketConfig_GetBySymbol_AllMarkets() {
        String[] symbols = { "BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD" };
        for (String symbol : symbols) {
            assertNotNull("Missing config for " + symbol, MarketConfig.getBySymbol(symbol));
        }
    }

    @Test
    public void testMarketConfig_GetBySymbol_Unknown_ReturnsNull() {
        assertNull(MarketConfig.getBySymbol("UNKNOWN-USD"));
    }

    @Test
    public void testMarketConfig_GetPriceLevels() {
        MarketConfig btc = MarketConfig.BTC_USD;
        int levels = btc.getPriceLevels();
        assertTrue("BTC should have significant price levels", levels > 1000);
    }

    @Test
    public void testMarketConfig_PriceFields() {
        MarketConfig btc = MarketConfig.BTC_USD;
        assertEquals(FixedPoint.fromDouble(50_000.0), btc.basePrice);
        assertEquals(FixedPoint.fromDouble(150_000.0), btc.maxPrice);
        assertEquals(FixedPoint.fromDouble(1.0), btc.tickSize);
    }

    @Test
    public void testMarketConfig_AllMarketsArray() {
        assertEquals(5, MarketConfig.ALL_MARKETS.length);
    }

    // ==================== Close ====================

    @Test
    public void testClose_NoException() {
        engine.close(); // should not throw
    }
}
