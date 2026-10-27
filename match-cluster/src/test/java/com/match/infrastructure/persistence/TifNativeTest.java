// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.application.orderbook.S5Probe;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.S5Frames.*;
import static org.junit.Assert.*;

/**
 * RC2 S5 contract sections 3 and 4 (W1, W2): native IOC and FOK on behaviour-2 LIMIT creates and
 * the bounded market buy, on the array and the direct book. Every command is a v12 frame through
 * the real {@link SbeDemuxer}; assertions read the journal, the books and the engine state.
 */
@RunWith(Parameterized.class)
public class TifNativeTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> impls() {
        return Arrays.asList(new Object[]{"array"}, new Object[]{"direct"});
    }

    private final boolean direct;

    public TifNativeTest(String impl) {
        this.direct = "direct".equals(impl);
    }

    private Run fresh(int cap) {
        return freshWith(S5Frames.config(direct, cap, 8));
    }

    private static long rest(Run r, long h, long user, int side, double price, double qty) {
        return appliedOrderId(r.send(limit(h, 0, user, side, price, qty, GTC)));
    }

    // ==================== IOC ====================

    @Test
    public void iocPartialCancelsRemainderWithoutResting() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            rest(r, 2, 101, ASK, 60_001, 1);
            final MatchingEngine me = book(r);
            final long bidVersion = me.getBidVersion();
            final List<byte[]> f = r.send(limit(3, 0, 200, BID, 60_001, 3, IOC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("outcome status CANCELLED", CANCELLED, o.status());
            assertEquals("outcome reason IOC_REMAINDER", IOC_REMAINDER, o.reason());
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            final long taker = o.orderId();
            assertEquals("two trades", 2, of(f, 1).size());
            assertEquals("filled 2", fp(2), tradedQuantity(f));
            final TerminalDecoder t = terminalOf(f, taker);
            assertEquals(CANCELLED, t.statusRaw());
            assertEquals(IOC_REMAINDER, t.rejectReason());
            assertEquals("no resting bid", 0, me.getBidOrders().length);
            assertEquals("the taker side was never touched", bidVersion, me.getBidVersion());
            assertFalse("no omsMap key for the taker", r.engine().getOrderIdToOmsOrderId().containsKey(taker));
            assertEquals(1L, S5Probe.ioc(r.engine()));
            assertEquals(1L, S5Probe.iocRemainder(r.engine()));
        }
    }

    @Test
    public void iocNoMatchIsCancelledNotRejected() {
        try (Run r = fresh(100)) {
            final List<byte[]> f = r.send(limit(3, 0, 200, BID, 60_001, 3, IOC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            assertEquals("CANCELLED, never REJECTED", CANCELLED, o.status());
            assertEquals(IOC_REMAINDER, o.reason());
            assertEquals("no trade", 0, of(f, 1).size());
            final TerminalDecoder t = terminalOf(f, o.orderId());
            assertEquals(CANCELLED, t.statusRaw());
            assertEquals(IOC_REMAINDER, t.rejectReason());
            assertEquals("nothing rests", 0, book(r).getBidOrders().length);
        }
    }

    @Test
    public void iocFullFillIsFilled() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 2);
            final List<byte[]> f = r.send(limit(3, 0, 200, BID, 60_000, 2, IOC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(FILLED, o.status());
            assertEquals(NONE, o.reason());
            assertEquals(FILLED, terminalOf(f, o.orderId()).statusRaw());
            assertEquals(0, book(r).getBidOrders().length);
            assertEquals(0, book(r).getAskOrders().length);
        }
    }

    // ==================== FOK ====================

    @Test
    public void fokInfeasibleByQuantityLeavesBookUntouched() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            rest(r, 2, 101, ASK, 60_001, 1);
            final MatchingEngine me = book(r);
            final long[] bids = me.getBidOrders(), asks = me.getAskOrders();
            final long bv = me.getBidVersion(), av = me.getAskVersion();
            final List<byte[]> f = r.send(limit(3, 0, 200, BID, 60_001, 3, FOK));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("no trade", 0, of(f, 1).size());
            assertEquals(CANCELLED, o.status());
            assertEquals(FOK_UNFILLABLE, o.reason());
            assertEquals(List.of(2, 3), templates(f));
            assertEquals(FOK_UNFILLABLE, terminalOf(f, o.orderId()).rejectReason());
            assertArrayEquals("bid book unchanged", bids, me.getBidOrders());
            assertArrayEquals("ask book unchanged", asks, me.getAskOrders());
            assertEquals("bid version unchanged", bv, me.getBidVersion());
            assertEquals("ask version unchanged", av, me.getAskVersion());
            assertFalse(r.engine().getOrderIdToOmsOrderId().containsKey(o.orderId()));
            assertEquals(1L, S5Probe.fokUnfillable(r.engine()));
        }
    }

    @Test
    public void fokInfeasibleByMatchCapLeavesNoPartial() {
        try (Run r = fresh(3)) {
            for (int j = 0; j < 4; j++) {
                rest(r, 1 + j, 100 + j, ASK, 60_000, 1);
            }
            final long[] asks = book(r).getAskOrders();
            final List<byte[]> f = r.send(limit(9, 0, 200, BID, 60_000, 4, FOK));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("no partial: no trade", 0, of(f, 1).size());
            assertEquals(CANCELLED, o.status());
            assertEquals("infeasible within the cap", FOK_UNFILLABLE, o.reason());
            assertArrayEquals(asks, book(r).getAskOrders());
        }
    }

    @Test
    public void fokFeasibleFillsExactly() {
        // Exactly cap makers, depth beyond the limit price.
        try (Run r = fresh(3)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            rest(r, 2, 101, ASK, 60_001, 1);
            rest(r, 3, 102, ASK, 60_002, 1);
            rest(r, 4, 103, ASK, 60_005, 5);
            final List<byte[]> f = r.send(limit(9, 0, 200, BID, 60_002, 3, FOK));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(FILLED, o.status());
            assertEquals(3, of(f, 1).size());
            assertEquals(fp(3), tradedQuantity(f));
            assertEquals(FILLED, terminalOf(f, o.orderId()).statusRaw());
            assertEquals("only the out-of-limit level remains", 4, book(r).getAskOrders().length);
        }
        // Liquidity beyond the limit price does not count (RED: the base trades what crosses and rests).
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            rest(r, 2, 101, ASK, 60_010, 5);
            final long[] asks = book(r).getAskOrders();
            final List<byte[]> f = r.send(limit(9, 0, 200, BID, 60_005, 2, FOK));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(CANCELLED, o.status());
            assertEquals(FOK_UNFILLABLE, o.reason());
            assertEquals(0, of(f, 1).size());
            assertArrayEquals(asks, book(r).getAskOrders());
            assertEquals(0, book(r).getBidOrders().length);
        }
    }

    /** FIX-2: a full direct level is walked to its last order; the array book gives the same journal. */
    @Test
    public void fokNeedsLastOrderOfFullDirectLevel() {
        final List<byte[]> fok;
        final List<byte[]> arrayFok;
        try (Run d = freshWith(S5Frames.config(true, 10, 4));
             Run a = freshWith(S5Frames.config(false, 10, 0))) {
            for (int j = 0; j < 4; j++) {
                rest(d, 1 + j, 100 + j, ASK, 60_000, 1);
                rest(a, 1 + j, 100 + j, ASK, 60_000, 1);
            }
            assertEquals("the direct level is full: no slot left", 0,
                    S5Probe.S5 ? S5Probe.directFreeSlots(book(d), true, fp(60_000)) : 0);
            assertTrue("pre-walk: the 4th order of a full level counts",
                    S5Probe.canFillCompletely(book(d), true, fp(60_000), fp(4)));
            assertFalse("pre-walk: 5 is not there", S5Probe.canFillCompletely(book(d), true, fp(60_000), fp(5)));
            fok = d.send(limit(9, 0, 200, BID, 60_000, 4, FOK));
            arrayFok = a.send(limit(9, 0, 200, BID, 60_000, 4, FOK));
        }
        final CommandOutcomeDecoder o = outcome(fok);
        assertEquals(FILLED, o.status());
        assertEquals("four trades", 4, of(fok, 1).size());
        assertFramesEqual("direct full level == array", arrayFok, fok);
    }

    /** FIX-5: head tombstones on direct; the pre-walk frees nothing and moves no head. */
    @Test
    public void fokPreWalkIsReadOnlyOnDirectTombstones() {
        if (!direct) {
            return;
        }
        final List<byte[]> followA = new java.util.ArrayList<>();
        final List<byte[]> followB = new java.util.ArrayList<>();
        try (Run a = freshWith(S5Frames.config(true, 100, 8));
             Run b = freshWith(S5Frames.config(true, 100, 8))) {
            for (Run r : new Run[]{a, b}) {
                final long o1 = rest(r, 1, 100, ASK, 60_000, 1);
                final long o2 = rest(r, 2, 101, ASK, 60_000, 1);
                rest(r, 3, 102, ASK, 60_000, 1);
                rest(r, 4, 103, ASK, 60_000, 1);
                appliedOrderId(r.send(cancel(1, 1, 100, o1)));
                appliedOrderId(r.send(cancel(2, 1, 101, o2)));
            }
            final MatchingEngine me = book(a);
            final int free = S5Probe.directFreeSlots(me, true, fp(60_000));
            final int head = S5Probe.directHeadSlot(me, true, fp(60_000));
            if (S5Probe.S5) { // the precondition reads S5 test access (the base copy returns sentinels)
                assertArrayEquals("two head tombstones", new int[]{2, 0}, S5Probe.directTombstones(me, true, fp(60_000)));
            }
            final long[] asks = me.getAskOrders();
            final long av = me.getAskVersion();
            // A: the infeasible FOK. B (the twin that never saw it): an IOC at a non-crossing price,
            // the same journal shape (one orderId, terminal CANCELLED + record 3).
            final List<byte[]> fa = a.send(limit(9, 0, 200, BID, 60_000, 5, FOK));
            b.send(limit(9, 0, 200, BID, 59_000, 5, IOC));
            final CommandOutcomeDecoder o = outcome(fa);
            assertEquals(0, of(fa, 1).size());
            assertEquals(CANCELLED, o.status());
            assertEquals(FOK_UNFILLABLE, o.reason());
            assertEquals("free-slot stack unchanged", free, S5Probe.directFreeSlots(me, true, fp(60_000)));
            assertEquals("head slot unchanged", head, S5Probe.directHeadSlot(me, true, fp(60_000)));
            assertArrayEquals("head tombstones still there", new int[]{2, 0},
                    S5Probe.directTombstones(me, true, fp(60_000)));
            assertArrayEquals(asks, me.getAskOrders());
            assertEquals(av, me.getAskVersion());
            // Follow-ups: refill the level to full (compaction), then sweep it.
            for (Run r : new Run[]{a, b}) {
                final List<byte[]> out = r == a ? followA : followB;
                for (int j = 0; j < 6; j++) {
                    out.addAll(r.send(limit(20 + j, 0, 300 + j, ASK, 60_000, 1, GTC)));
                }
                out.addAll(r.send(limit(30, 0, 400, BID, 60_000, 9, GTC)));
                out.addAll(r.send(limit(31, 0, 401, BID, 60_000, 1, FOK)));
            }
        }
        assertFramesEqual("follow-ups after the FOK == the twin", followB, followA);
    }

    // ==================== market buy bound ====================

    @Test
    public void marketBuyBoundedByQuantity() {
        try (Run r = fresh(100)) {
            for (int j = 0; j < 4; j++) {
                rest(r, 1 + j, 100 + j, ASK, 60_000 + j, 1);
            }
            // budget twice what 2 cost, ask depth 4 >= 2 x 2
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 2, 2 * (60_000 + 60_001), GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("bought exactly the quantity", fp(2), tradedQuantity(f));
            assertEquals(FILLED, o.status());
            assertEquals(FILLED, terminalOf(f, o.orderId()).statusRaw());
            assertEquals(2, book(r).getAskOrders().length / 4);
        }
    }

    @Test
    public void marketBuyBoundFilledAtExactlyCapIsFilled() {
        try (Run r = fresh(3)) {
            for (int j = 0; j < 5; j++) {
                rest(r, 1 + j, 100 + j, ASK, 60_000 + j, 1);
            }
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 3, 1_000_000, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("three trades", 3, of(f, 1).size());
            assertEquals(FILLED, o.status());
            assertEquals("cap flag clear: reason NONE", NONE, o.reason());
            assertEquals(NONE, terminalOf(f, o.orderId()).rejectReason());
        }
    }

    @Test
    public void marketBuyBudgetFirstIsCancelled() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 2);
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 2, 60_000, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("budget for half", fp(1), tradedQuantity(f));
            assertEquals(CANCELLED, o.status());
            assertEquals(BUDGET_EXHAUSTED, o.reason());
            assertEquals(BUDGET_EXHAUSTED, terminalOf(f, o.orderId()).rejectReason());
            assertEquals(1L, S5Probe.marketBudgetExhausted(r.engine()));
        }
    }

    @Test
    public void marketBookExhaustedIsCancelled() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 3, 1_000_000, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(fp(1), tradedQuantity(f));
            assertEquals("thin book buy: CANCELLED", CANCELLED, o.status());
            assertEquals(IOC_REMAINDER, o.reason());
        }
        try (Run r = fresh(100)) {
            rest(r, 1, 100, BID, 60_000, 1);
            final List<byte[]> f = r.send(market(9, 0, 200, ASK, 3, 0, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(fp(1), tradedQuantity(f));
            assertEquals("thin book sell: CANCELLED", CANCELLED, o.status());
            assertEquals(IOC_REMAINDER, o.reason());
        }
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            // 0.0001 cannot pay one unit (1e-8) at 60000.
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 1, 0.0001, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(0, of(f, 1).size());
            assertEquals(DurableCommandOutcome.REJECTED, o.result());
            assertEquals(REJECTED, o.status());
            assertEquals("asks exist: the budget, not the book", BUDGET_EXHAUSTED, o.reason());
            assertEquals(BUDGET_EXHAUSTED, terminalOf(f, o.orderId()).rejectReason());
        }
    }

    @Test
    public void marketZeroQuantityRejected() {
        try (Run r = fresh(100)) {
            rest(r, 1, 100, ASK, 60_000, 1);
            final List<byte[]> f = r.send(market(9, 0, 200, BID, 0, 1_000_000, GTC));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(0, of(f, 1).size());
            assertEquals(REJECTED, o.status());
            assertEquals(INVALID_QUANTITY, o.reason());
            assertEquals(4, book(r).getAskOrders().length);
        }
    }

    // ==================== admission ====================

    @Test
    public void unsupportedTifCombinationsAreInvalid() {
        try (Run r = fresh(100)) {
            final long leg = rest(r, 1, 100, BID, 59_000, 1);
            final long[] bids = book(r).getBidOrders();
            final byte[][] frames = {
                    limitMaker(2, 0, 100, BID, 58_000, 1, IOC),
                    limitMaker(3, 0, 100, BID, 58_000, 1, FOK),
                    market(4, 0, 100, BID, 1, 1_000_000, FOK),
                    cancelTif(1, 1, 100, leg, IOC),
                    amendRaw(1, 2, 100, leg, BID, 58_500, 1, LIMIT, FOK, fp(1)),
            };
            for (int i = 0; i < frames.length; i++) {
                final int ledger = S4Probe.ledgerSize(r.engine());
                final long before = S5Probe.invalidTif(r.engine());
                final CommandOutcomeDecoder o = outcome(r.send(frames[i]));
                assertEquals("frame " + i + ": INVALID", DurableCommandOutcome.INVALID, o.result());
                assertEquals("frame " + i + ": reason 0", 0, o.reason());
                assertEquals("frame " + i + ": not recorded", ledger, o.ledgerSize());
                assertEquals("frame " + i + ": ledger unchanged", ledger, S4Probe.ledgerSize(r.engine()));
                assertEquals("frame " + i + ": counter", before + 1, S5Probe.invalidTif(r.engine()));
            }
            assertArrayEquals("the leg is intact", bids, book(r).getBidOrders());
            // IOC on MARKET is a market order.
            rest(r, 5, 300, ASK, 60_000, 1);
            final CommandOutcomeDecoder m = outcome(r.send(market(6, 0, 200, BID, 1, 1_000_000, IOC)));
            assertEquals(DurableCommandOutcome.APPLIED, m.result());
            assertEquals(FILLED, m.status());
        }
    }

    /** Pin: behaviour 1 ignores tif and the bound; acceptDurable on a dense engine keeps the absolute amend. */
    @Test
    public void behaviourOneIgnoresTifAndBound() {
        if (direct) {
            return;
        }
        try (Run r = freshWith(engineConfig(1, 1, 0, 0, 100_000))) {
            final MatchingEngine me = book(r);
            r.send(limit(1, 0, 100, ASK, 60_000, 1, GTC));
            final CommandOutcomeDecoder ioc = outcome(r.send(limit(2, 0, 200, BID, 60_000, 3, IOC)));
            assertEquals("IOC rests its remainder", PARTIALLY_FILLED, ioc.status());
            assertEquals(fp(2), remainingOf(me, ioc.orderId()));
            r.send(limit(3, 0, 101, ASK, 61_000, 1, GTC));
            r.send(limit(4, 0, 102, ASK, 61_001, 1, GTC));
            final List<byte[]> fokF = r.send(limit(5, 0, 201, BID, 61_001, 3, FOK));
            assertEquals("FOK trades what crosses", 2, of(fokF, 1).size());
            assertEquals("FOK rests the rest", PARTIALLY_FILLED, outcome(fokF).status());
            r.send(limit(6, 0, 103, ASK, 62_000, 1, GTC));
            r.send(limit(7, 0, 104, ASK, 62_001, 1, GTC));
            // quantity 1 but a budget for 2: behaviour 1 buys by budget, FILLED on a thin book.
            final List<byte[]> mk = r.send(market(8, 0, 202, BID, 1, 200_000, GTC));
            assertEquals("budget only", fp(2), tradedQuantity(mk));
            assertEquals("thin book: FILLED", FILLED, outcome(mk).status());
        }
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(BehaviourTwoHarness.fresh2(), 64L);
            final Engine e = h.engine;
            e.setCurrentLogPosition(192L);
            final long leg = e.acceptDurable(new DurableOrderIntent(1, 0, 100, 1, 0, fp(60_000), fp(10), 0,
                    BehaviourTwoHarness.MARKET, 0, 0, 1), 1_001L).orderId();
            e.setCurrentLogPosition(320L);
            e.acceptDurable(new DurableOrderIntent(2, 0, 200, 2, 0, fp(60_000), fp(3), 0,
                    BehaviourTwoHarness.MARKET, 0, 0, 0), 1_002L);
            e.setCurrentLogPosition(448L);
            final var amended = e.acceptDurable(new DurableOrderIntent(1, (1 << 2) | 2, 100, 1, leg, fp(60_000), fp(8),
                    0, BehaviourTwoHarness.MARKET, 2, 0, 1, 0, fp(10)), 1_003L);
            assertEquals(DurableCommandOutcome.APPLIED, amended.result());
            assertEquals("acceptDurable: the absolute amend (8, not 5)", fp(8),
                    remainingOf(e.getEngine(BehaviourTwoHarness.MARKET), amended.orderId()));
        }
    }

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }
}
