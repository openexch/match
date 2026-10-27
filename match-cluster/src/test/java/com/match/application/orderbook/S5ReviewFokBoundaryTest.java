// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Test;

import java.util.function.Consumer;

import static org.junit.Assert.*;

/**
 * RC2 S5 independent review: deterministic boundary books for the FOK pre-walk (contract 3.3,
 * 3.5, decision D-d), on both implementations. Each case builds the same book twice from one
 * script (tombstones included, never a restore), asks the pre-walk on A (which must change
 * nothing), then on the twin B runs the pre-walk and the sweep and requires
 * {@code pre == (remaining 0 and the cap flag clear)}, plus the expected answer.
 */
public class S5ReviewFokBoundaryTest {

    private static final long BASE = fp(50_000), TICK = fp(1), P = fp(50_005);

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }

    private static MatchingEngine direct(int cap, int mopl) {
        return new DirectMatchingEngine(BASE, BASE + 20 * TICK, TICK, cap, mopl);
    }

    private static MatchingEngine array(int cap) {
        return new ArrayMatchingEngine(BASE, BASE + 20 * TICK, TICK, 256, cap);
    }

    private static void ask(MatchingEngine me, long id, long userId, long price, double qty) {
        assertEquals(0, me.processLimitOrder(id, userId, false, price, fp(qty)));
        assertEquals("rested", OrderRejectReason.NONE, me.getLastRestRejectReason());
    }

    /** Pre-walk on A (read-only), then pre-walk + sweep on the twin B; returns the pre-walk answer. */
    private static boolean check(String where, MatchingEngine a, MatchingEngine b, long price, double qty,
                                 boolean expected) {
        final long[] asks = a.getAskOrders();
        final long av = a.getAskVersion();
        final boolean pre = a.canFillCompletely(true, price, fp(qty));
        assertEquals(where + ": pre-walk answer", expected, pre);
        assertArrayEquals(where + ": pre-walk read-only (orders)", asks, a.getAskOrders());
        assertEquals(where + ": pre-walk read-only (version)", av, a.getAskVersion());
        assertEquals(where + ": twin pre-walk", pre, b.canFillCompletely(true, price, fp(qty)));
        b.processLimitOrderNoRest(999_999, 7, true, price, fp(qty));
        assertEquals(where + ": pre-walk <=> sweep fills with the cap flag clear", pre,
                b.getTakerRemainingQuantity() == 0 && !b.wasMatchLimitReached());
        assertEquals(where + ": the taker never rests", -1L, b.restingRemaining(999_999, true));
        return pre;
    }

    private static void both(String name, int cap, int mopl, Consumer<MatchingEngine> script, long price, double qty,
                             boolean expected) {
        final MatchingEngine da = direct(cap, mopl), db = direct(cap, mopl);
        script.accept(da);
        script.accept(db);
        final DirectIndexOrderBook book = ((DirectMatchingEngine) da).getAskBook();
        final int idx = book.priceIndexOf(P);
        final int free = book.freeSlotCount(idx), head = book.levelHeadSlot(idx);
        check(name + " (direct)", da, db, price, qty, expected);
        assertEquals(name + ": direct free-slot stack unchanged", free, book.freeSlotCount(idx));
        assertEquals(name + ": direct head slot unchanged", head, book.levelHeadSlot(idx));
        final MatchingEngine aa = array(cap), ab = array(cap);
        script.accept(aa);
        script.accept(ab);
        check(name + " (array)", aa, ab, price, qty, expected);
    }

    /** maxOrdersPerLevel - 1 head tombstones before the only live order (the sweep's own head guard bound). */
    @Test
    public void maxHeadTombstonesBeforeTheLastLiveOrder() {
        final Consumer<MatchingEngine> s = me -> {
            for (int j = 0; j < 4; j++) {
                ask(me, 1 + j, 100 + j, P, 1);
            }
            for (int j = 0; j < 3; j++) {
                assertTrue(me.cancelOrder(1 + j, false));
            }
        };
        both("M-1 head tombstones, need 1", 8, 4, s, P, 1, true);
        both("M-1 head tombstones, need 1.5", 8, 4, s, P, 1.5, false);
    }

    /** A tombstoned tail then an append behind it: the chain is live, tombstone, live. */
    @Test
    public void appendBehindATombstonedTail() {
        final Consumer<MatchingEngine> s = me -> {
            ask(me, 1, 100, P, 1);
            ask(me, 2, 101, P, 1);
            assertTrue(me.cancelOrder(2, false));
            ask(me, 3, 102, P, 2);
        };
        both("live, tombstone, live: need 3", 8, 4, s, P, 3, true);
        both("live, tombstone, live: need 3 + 1 unit", 8, 4, s, P + 0, 3.00000001, false);
        both("live, tombstone, live: cap 1, need 2", 1, 4, s, P, 2, false);
    }

    /** Middle tombstones on a level whose free stack is empty, then a compacting add. */
    @Test
    public void compactedLevelKeepsFifoAndCount() {
        final Consumer<MatchingEngine> s = me -> {
            for (int j = 0; j < 4; j++) {
                ask(me, 1 + j, 100 + j, P, 1 + j);   // 1, 2, 3, 4
            }
            assertTrue(me.cancelOrder(2, false));
            assertTrue(me.cancelOrder(3, false));
            ask(me, 5, 104, P, 5);                    // direct: compactLevel, chain 1, 4, 5
        };
        both("compacted: need 10, cap 3", 3, 4, s, P, 10, true);
        both("compacted: need 10, cap 2", 2, 4, s, P, 10, false);
        both("compacted: need 5, cap 2", 2, 4, s, P, 5, true);
    }

    /** The need met at exactly the cap-th maker across levels; one more unit needs maker cap + 1. */
    @Test
    public void exactCapAcrossLevels() {
        final Consumer<MatchingEngine> s = me -> {
            ask(me, 1, 100, P, 1);
            ask(me, 2, 101, P + TICK, 1);
            ask(me, 3, 102, P + 2 * TICK, 5);
        };
        both("cap 3 at the 3rd level", 3, 4, s, P + 2 * TICK, 7, true);
        both("cap 2: the 3rd maker is past the cap", 2, 4, s, P + 2 * TICK, 2.5, false);
        both("cap 2: exactly two makers", 2, 4, s, P + 2 * TICK, 2, true);
        both("price excludes the 3rd level", 3, 4, s, P + TICK, 2.5, false);
    }

    /** A partially filled head maker counts with its remaining only. */
    @Test
    public void partiallyFilledHeadMaker() {
        final Consumer<MatchingEngine> s = me -> {
            ask(me, 1, 100, P, 3);
            me.processLimitOrderNoRest(50, 200, true, P, fp(2));
            assertEquals(0, me.getTakerRemainingQuantity());
        };
        both("remaining 1: need 1", 8, 4, s, P, 1, true);
        both("remaining 1: need 2", 8, 4, s, P, 2, false);
    }

    /** No self-trade prevention: the taker's own makers count in the pre-walk and in the sweep. */
    @Test
    public void ownMakersCount() {
        final Consumer<MatchingEngine> s = me -> {
            ask(me, 1, 7, P, 1);    // userId 7 = the taker's userId in check()
            ask(me, 2, 7, P, 1);
        };
        both("own makers", 8, 4, s, P, 2, true);
    }
}
