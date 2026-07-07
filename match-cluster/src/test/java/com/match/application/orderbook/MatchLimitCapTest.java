// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.application.engine.Engine;
import com.match.application.engine.MarketConfig;
import com.match.application.publisher.OrderStatusType;
import com.match.determinism.EngineEvent;
import com.match.determinism.RecordingEventSink;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * match#93 — the per-order match cap ({@code MAX_MATCHES_PER_ORDER}) must TERMINATE the taker at the
 * cap, not silently rest its leftover as a normal book add.
 *
 * <p>Before the fix, a LIMIT order whose sweep was truncated by the cap rested its remainder on the
 * book even though crossing liquidity existed at levels never reached — producing a <b>crossed book</b>
 * (a bid resting above unreached asks). A MARKET order capped mid-sweep fell into the {@code else}
 * branch and was mis-reported {@code FILLED}, hiding a partial fill from OMS.
 *
 * <p>The cap stays a hardcoded deterministic constant in production (10_000); these tests inject a
 * tiny cap (4) through the package-private constructor so the termination path is reachable with a
 * handful of orders. Every scenario is run against BOTH engine implementations (array-backed default
 * + preallocated direct fallback), and an A/B equivalence check proves they decide identically.
 */
public class MatchLimitCapTest {

    // ---- tiny geometry for impl-level tests (base 100 .. max 200, $1 tick): 101 levels, cheap ----
    private static final long IMPL_BASE = FixedPoint.fromDouble(100.0);
    private static final long IMPL_MAX = FixedPoint.fromDouble(200.0);
    private static final long IMPL_TICK = FixedPoint.fromDouble(1.0);

    private static final long ONE = FixedPoint.fromDouble(1.0);
    private static final long FOUR = FixedPoint.fromDouble(4.0);

    private static MatchingEngine newImpl(String kind, int cap) {
        return "array".equals(kind)
            ? new ArrayMatchingEngine(IMPL_BASE, IMPL_MAX, IMPL_TICK, 1024, cap)
            : new DirectMatchingEngine(IMPL_BASE, IMPL_MAX, IMPL_TICK, cap);
    }

    /** Crossed-book invariant: when both sides are non-empty, the best bid must sit strictly below
     *  the best ask. This is the exact condition match#93 used to violate. */
    private static void assertNotCrossed(MatchingEngine e) {
        if (!e.isBidEmpty() && !e.isAskEmpty()) {
            assertTrue("crossed book: bestBid=" + e.getBestBid() + " must be < bestAsk=" + e.getBestAsk(),
                e.getBestBid() < e.getBestAsk());
        }
    }

    // ==================== Impl-level: capped LIMIT terminates, remainder not rested ====================

    private void cappedLimit_impl(String kind) {
        MatchingEngine e = newImpl(kind, 4);
        long p1 = FixedPoint.fromDouble(150.0);
        long p2 = FixedPoint.fromDouble(151.0);
        // 8 resting asks qty=1 across two levels (crossing liquidity for a buy above both).
        for (int i = 1; i <= 4; i++) {
            assertEquals(OrderRejectReason.NONE, e.addOrderNoMatch(i, 700, false, p1, ONE));
        }
        for (int i = 5; i <= 8; i++) {
            assertEquals(OrderRejectReason.NONE, e.addOrderNoMatch(i, 700, false, p2, ONE));
        }

        int m = e.processLimitOrder(999, 42, true, FixedPoint.fromDouble(160.0), FixedPoint.fromDouble(8.0));

        assertEquals("cap bounds trades to exactly the cap", 4, m);
        assertTrue("cap must flag the truncation", e.wasMatchLimitReached());
        assertEquals("remainder surfaces MATCH_LIMIT, it is NOT rested",
            OrderRejectReason.MATCH_LIMIT, e.getLastRestRejectReason());
        assertEquals("true taker remainder preserved", FOUR, e.getTakerRemainingQuantity());
        assertTrue("crossed remainder must NOT rest on the bid book", e.isBidEmpty());
        assertFalse("unreached ask liquidity remains", e.isAskEmpty());
        assertEquals("best ask is the unreached second level", p2, e.getBestAsk());
        assertNotCrossed(e);
    }

    @Test public void array_cappedLimit_terminatesAtCap()  { cappedLimit_impl("array"); }
    @Test public void direct_cappedLimit_terminatesAtCap() { cappedLimit_impl("direct"); }

    // ==================== Impl-level: capped MARKET buy / sell ====================

    private void cappedMarketBuy_impl(String kind) {
        MatchingEngine e = newImpl(kind, 4);
        long p1 = FixedPoint.fromDouble(150.0);
        long p2 = FixedPoint.fromDouble(151.0);
        for (int i = 1; i <= 4; i++) e.addOrderNoMatch(i, 700, false, p1, ONE);
        for (int i = 5; i <= 8; i++) e.addOrderNoMatch(i, 700, false, p2, ONE);

        // Budget for far more than 8 units — so a stop can only be the cap, never the money.
        int m = e.processMarketOrder(999, 42, true, 0, FixedPoint.fromDouble(5000.0));

        assertEquals(4, m);
        assertTrue("cap must flag the truncation", e.wasMatchLimitReached());
        assertEquals("true filled qty", FOUR, sumMatchQty(e, m));
        assertTrue("budget was not exhausted — proves the cap terminated it, not the money",
            e.getTakerRemainingBudget() > 0);
        assertNotCrossed(e);
    }

    private void cappedMarketSell_impl(String kind) {
        MatchingEngine e = newImpl(kind, 4);
        long p1 = FixedPoint.fromDouble(150.0);
        long p0 = FixedPoint.fromDouble(149.0);
        for (int i = 1; i <= 4; i++) e.addOrderNoMatch(i, 700, true, p1, ONE); // bids @150 (best)
        for (int i = 5; i <= 8; i++) e.addOrderNoMatch(i, 700, true, p0, ONE); // bids @149

        int m = e.processMarketOrder(999, 42, false, FixedPoint.fromDouble(8.0), 0);

        assertEquals(4, m);
        assertTrue("cap must flag the truncation", e.wasMatchLimitReached());
        assertEquals("true taker remainder preserved", FOUR, e.getTakerRemainingQuantity());
        assertEquals("true filled qty", FOUR, sumMatchQty(e, m));
        assertNotCrossed(e);
    }

    @Test public void array_cappedMarketBuy_terminatesAtCap()   { cappedMarketBuy_impl("array"); }
    @Test public void direct_cappedMarketBuy_terminatesAtCap()  { cappedMarketBuy_impl("direct"); }
    @Test public void array_cappedMarketSell_terminatesAtCap()  { cappedMarketSell_impl("array"); }
    @Test public void direct_cappedMarketSell_terminatesAtCap() { cappedMarketSell_impl("direct"); }

    // ==================== Impl-level: exact-boundary must NOT set the flag ====================

    private void exactCap_impl(String kind) {
        MatchingEngine e = newImpl(kind, 4);
        long p = FixedPoint.fromDouble(150.0);
        for (int i = 1; i <= 4; i++) e.addOrderNoMatch(i, 700, false, p, ONE); // exactly 4 asks

        // Buy exactly 4 @150 — fully fills in exactly the cap's matches; nothing left to do.
        int m = e.processLimitOrder(999, 42, true, p, FOUR);

        assertEquals(4, m);
        assertFalse("filling in EXACTLY the cap's matches must NOT set the flag (stays FILLED)",
            e.wasMatchLimitReached());
        assertEquals(0L, e.getTakerRemainingQuantity());
        assertEquals(OrderRejectReason.NONE, e.getLastRestRejectReason());
        assertTrue("both sides consumed", e.isAskEmpty() && e.isBidEmpty());
    }

    @Test public void array_exactCapMatches_fillsWithoutFlag()  { exactCap_impl("array"); }
    @Test public void direct_exactCapMatches_fillsWithoutFlag() { exactCap_impl("direct"); }

    // ==================== Impl-level: A/B equivalence at the small cap ====================

    /** The array and direct engines must decide the capped sweep byte-for-byte identically:
     *  same trades, same cap flag, same rest/no-rest decision, same surviving book. */
    @Test
    public void ab_cappedSweep_arrayEqualsDirect() {
        assertEquals(captureCappedSweep("direct"), captureCappedSweep("array"));
    }

    private static String captureCappedSweep(String kind) {
        MatchingEngine e = newImpl(kind, 4);
        long p1 = FixedPoint.fromDouble(150.0);
        long p2 = FixedPoint.fromDouble(151.0);
        for (int i = 1; i <= 4; i++) e.addOrderNoMatch(i, 700, false, p1, ONE);
        for (int i = 5; i <= 8; i++) e.addOrderNoMatch(i, 700, false, p2, ONE);

        int m = e.processLimitOrder(999, 42, true, FixedPoint.fromDouble(160.0), FixedPoint.fromDouble(8.0));

        StringBuilder sb = new StringBuilder();
        sb.append("matches=").append(m).append('\n');
        for (int i = 0; i < m; i++) {
            sb.append("trade maker=").append(e.getMatchMakerOrderId(i))
              .append(" px=").append(e.getMatchPrice(i))
              .append(" qty=").append(e.getMatchQuantity(i)).append('\n');
        }
        sb.append("capped=").append(e.wasMatchLimitReached()).append('\n');
        sb.append("restReason=").append(e.getLastRestRejectReason()).append('\n');
        sb.append("takerRemaining=").append(e.getTakerRemainingQuantity()).append('\n');
        sb.append("bidEmpty=").append(e.isBidEmpty()).append('\n');
        sb.append("bestAsk=").append(e.getBestAsk()).append('\n');
        return sb.toString();
    }

    // ==================== Engine-level: terminal status published correctly ====================
    //
    // The Engine status branch is impl-agnostic (it drives the engine through the MatchingEngine
    // interface), so we inject a small-cap engine of each kind into a live Engine's market slot via
    // getEngines(). ETH's geometry (tick $0.50) keeps the direct book light while staying on-tick.

    private static final int ETH = Engine.MARKET_ETH_USD;

    private static MatchingEngine ethSmallCap(String kind, int cap) {
        MarketConfig c = MarketConfig.ETH_USD;
        return "array".equals(kind)
            ? new ArrayMatchingEngine(c.basePrice, c.maxPrice, c.tickSize, 1 << 14, cap)
            : new DirectMatchingEngine(c.basePrice, c.maxPrice, c.tickSize, cap);
    }

    private static Engine engineWithSmallCapEth(String kind, RecordingEventSink sink) {
        Engine engine = new Engine("array"); // cheap array books for every market...
        engine.getEngines().put(ETH, ethSmallCap(kind, 4)); // ...then swap in the cap=4 test engine
        engine.setEventPublisher(sink);
        return engine;
    }

    // -- capped LIMIT → terminal CANCELLED with the TRUE filled qty, nothing rests, book not crossed --

    private void engineCappedLimit(String kind) {
        RecordingEventSink sink = new RecordingEventSink();
        Engine engine = engineWithSmallCapEth(kind, sink);

        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.ASK, 2000.0, 1.0), 1000L);
        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.ASK, 2000.5, 1.0), 1000L);

        long takerId = engine.getOrderIdGenerator();
        engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(999, OrderSide.BID, 2001.0, 8.0), 1000L);

        assertEquals("cap bounds the trade tape to exactly the cap", 4, countTrades(sink));
        EngineEvent.Status last = lastStatus(sink);
        assertNotNull(last);
        assertEquals("capped LIMIT taker must be terminal CANCELLED (never resting/partial)",
            OrderStatusType.CANCELLED, last.orderStatus());
        assertEquals("terminal status references the taker", takerId, last.orderId());
        assertEquals("CANCELLED carries the TRUE filled quantity", FOUR, last.filledQty());
        assertEquals("no phantom resting remainder", 0L, last.remainingQty());

        MatchingEngine eth = engine.getEngine(ETH);
        assertTrue("crossed remainder must NOT rest on the bid book", eth.isBidEmpty());
        assertEquals("unreached ask level still tops the book",
            FixedPoint.fromDouble(2000.5), eth.getBestAsk());
        assertNotCrossed(eth);
    }

    @Test public void array_engineCappedLimit_publishesCancelled()  { engineCappedLimit("array"); }
    @Test public void direct_engineCappedLimit_publishesCancelled() { engineCappedLimit("direct"); }

    // -- capped MARKET buy / sell → CANCELLED with the TRUE filled qty (NOT FILLED) --

    private void engineCappedMarketBuy(String kind) {
        RecordingEventSink sink = new RecordingEventSink();
        Engine engine = engineWithSmallCapEth(kind, sink);

        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.ASK, 2000.0, 1.0), 1000L);
        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.ASK, 2000.5, 1.0), 1000L);

        long takerId = engine.getOrderIdGenerator();
        engine.acceptOrder(ETH, Engine.CMD_CREATE, marketBuy(999, 100000.0), 1000L); // budget >> needed

        assertEquals(4, countTrades(sink));
        EngineEvent.Status last = lastStatus(sink);
        assertNotNull(last);
        assertEquals("a MARKET order capped mid-sweep must be CANCELLED, not mis-reported FILLED",
            OrderStatusType.CANCELLED, last.orderStatus());
        assertEquals(takerId, last.orderId());
        assertEquals("CANCELLED carries the TRUE filled quantity", FOUR, last.filledQty());
    }

    private void engineCappedMarketSell(String kind) {
        RecordingEventSink sink = new RecordingEventSink();
        Engine engine = engineWithSmallCapEth(kind, sink);

        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.BID, 2000.0, 1.0), 1000L);
        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.BID, 1999.5, 1.0), 1000L);

        long takerId = engine.getOrderIdGenerator();
        engine.acceptOrder(ETH, Engine.CMD_CREATE, marketSell(999, 8.0), 1000L);

        assertEquals(4, countTrades(sink));
        EngineEvent.Status last = lastStatus(sink);
        assertNotNull(last);
        assertEquals("a MARKET order capped mid-sweep must be CANCELLED, not mis-reported FILLED",
            OrderStatusType.CANCELLED, last.orderStatus());
        assertEquals(takerId, last.orderId());
        assertEquals("CANCELLED carries the TRUE filled quantity", FOUR, last.filledQty());
    }

    @Test public void array_engineCappedMarketBuy_publishesCancelled()   { engineCappedMarketBuy("array"); }
    @Test public void direct_engineCappedMarketBuy_publishesCancelled()  { engineCappedMarketBuy("direct"); }
    @Test public void array_engineCappedMarketSell_publishesCancelled()  { engineCappedMarketSell("array"); }
    @Test public void direct_engineCappedMarketSell_publishesCancelled() { engineCappedMarketSell("direct"); }

    // -- boundary: an order that fully fills in EXACTLY the cap's matches stays FILLED (no over-fire) --

    private void engineExactCapFills(String kind) {
        RecordingEventSink sink = new RecordingEventSink();
        Engine engine = engineWithSmallCapEth(kind, sink);

        for (int i = 0; i < 4; i++) engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(500, OrderSide.ASK, 2000.0, 1.0), 1000L);

        long takerId = engine.getOrderIdGenerator();
        engine.acceptOrder(ETH, Engine.CMD_CREATE, limit(999, OrderSide.BID, 2000.0, 4.0), 1000L);

        assertEquals(4, countTrades(sink));
        EngineEvent.Status last = lastStatus(sink);
        assertNotNull(last);
        assertEquals("exact-cap full fill must stay FILLED (the fix must not over-fire)",
            OrderStatusType.FILLED, last.orderStatus());
        assertEquals(takerId, last.orderId());
        assertEquals(FOUR, last.filledQty());
        assertFalse("flag must not be set when the taker fully filled at the cap",
            engine.getEngine(ETH).wasMatchLimitReached());
        assertTrue("both sides consumed",
            engine.getEngine(ETH).isAskEmpty() && engine.getEngine(ETH).isBidEmpty());
    }

    @Test public void array_engineExactCapMatches_fills()  { engineExactCapFills("array"); }
    @Test public void direct_engineExactCapMatches_fills() { engineExactCapFills("direct"); }

    // ==================== helpers ====================

    private static long sumMatchQty(MatchingEngine e, int matchCount) {
        long total = 0;
        for (int i = 0; i < matchCount; i++) total += e.getMatchQuantity(i);
        return total;
    }

    private static int countTrades(RecordingEventSink sink) {
        int n = 0;
        for (EngineEvent ev : sink.events()) {
            if (ev instanceof EngineEvent.Trade) n++;
        }
        return n;
    }

    private static EngineEvent.Status lastStatus(RecordingEventSink sink) {
        EngineEvent.Status last = null;
        for (EngineEvent ev : sink.events()) {
            if (ev instanceof EngineEvent.Status s) last = s;
        }
        return last;
    }

    private static CreateOrderCommand limit(long userId, OrderSide side, double price, double qty) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0);
        return cmd;
    }

    private static CreateOrderCommand marketBuy(long userId, double totalPrice) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.BID);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(0);
        cmd.setTotalPrice(FixedPoint.fromDouble(totalPrice));
        return cmd;
    }

    private static CreateOrderCommand marketSell(long userId, double qty) {
        CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOrderSide(OrderSide.ASK);
        cmd.setOrderType(OrderType.MARKET);
        cmd.setPrice(0);
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0);
        return cmd;
    }
}
