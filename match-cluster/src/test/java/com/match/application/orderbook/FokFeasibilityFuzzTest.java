// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * RC2 S5 FIX-4, the guard of decision D-d (a FOK judged fillable that does not fill halts the
 * node): on seeded books, on the array and the direct implementation, the pre-walk
 * {@code canFillCompletely} agrees with the sweep {@code processLimitOrderNoRest} in both
 * directions, and with an independent model computed from the book export.
 *
 * <p>The sweep runs on a TWIN built by applying the same seeded log to a second engine (identical
 * layout, tombstones included), never on a snapshot-restored copy: a direct restore re-adds every
 * order compactly and would hide exactly the tombstone layouts the proof must cover. The twin also
 * runs the pre-walk before its sweep (the production order), which must answer the same and leave
 * the book as it was.
 *
 * <p>Seeds: {@code -Dfok.fuzz.seeds} (10 000 by default, 1 000 000 nightly); match cap 1..8,
 * direct {@code maxOrdersPerLevel} 4..8. The coverage counters at the end prove the seeds reached
 * head and middle tombstones, crossing levels at exactly {@code maxOrdersPerLevel} live orders, the
 * need met at exactly the cap-th maker and by the last order of a level, and a non-crossing level
 * after crossing ones.
 */
public class FokFeasibilityFuzzTest {

    private static final long BASE = fp(50_000), TICK = fp(1);
    private static final int LEVELS = 10;

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }

    private static final class Coverage {
        long seeds, feasible, infeasible, headTomb, middleTomb, fullLevel, atCap, lastOfLevel, nonCrossingAfter,
                capBlocked;
    }

    private interface Factory {
        MatchingEngine make();
    }

    /** One op of the seeded log: an add (a matching limit order) or a cancel. */
    private record Op(boolean cancel, long id, boolean isBuy, long price, long qty) { }

    @Test
    public void preWalkEqualsSweep() {
        final long seeds = Long.getLong("fok.fuzz.seeds", 10_000L);
        final Coverage c = new Coverage();
        for (long seed = 1; seed <= seeds; seed++) {
            final Random rnd = new Random(seed);
            final int cap = 1 + rnd.nextInt(8);
            final int mopl = 4 + rnd.nextInt(5);
            final List<Op> log = log(rnd, mopl);
            final boolean isBuy = rnd.nextBoolean();
            final int priceLevel = rnd.nextInt(LEVELS);
            final int qtyMode = rnd.nextInt(6);
            final int pick = rnd.nextInt(1 << 20);
            run("direct", seed, c, () -> new DirectMatchingEngine(BASE, BASE + (LEVELS - 1) * TICK, TICK, cap, mopl),
                    log, isBuy, priceLevel, qtyMode, pick, cap, true, mopl);
            run("array", seed, c, () -> new ArrayMatchingEngine(BASE, BASE + (LEVELS - 1) * TICK, TICK, 128, cap),
                    log, isBuy, priceLevel, qtyMode, pick, cap, false, mopl);
            c.seeds++;
        }
        System.out.println("[S5] FokFeasibilityFuzzTest: " + c.seeds + " seeds x 2 impls; feasible " + c.feasible
                + ", infeasible " + c.infeasible + " (cap-blocked " + c.capBlocked + "); head tombstones "
                + c.headTomb + ", middle tombstones " + c.middleTomb + ", full direct levels " + c.fullLevel
                + ", need met at the cap-th maker " + c.atCap + ", by the last order of a level " + c.lastOfLevel
                + ", non-crossing level after crossing ones " + c.nonCrossingAfter);
        assertTrue("both outcomes", c.feasible > seeds / 10 && c.infeasible > seeds / 10);
        assertTrue("cap-blocked infeasible", c.capBlocked > 0);
        assertTrue("head tombstones", c.headTomb > 0);
        assertTrue("middle tombstones", c.middleTomb > 0);
        assertTrue("levels at exactly maxOrdersPerLevel live orders", c.fullLevel > 0);
        assertTrue("need met at exactly the cap-th maker", c.atCap > 0);
        assertTrue("need met by the last order of a level", c.lastOfLevel > 0);
        assertTrue("a non-crossing level after crossing ones", c.nonCrossingAfter > 0);
    }

    /** A seeded log: resting adds on both halves, crossing takers (partial fills), cancels, level stuffing. */
    private static List<Op> log(Random rnd, int mopl) {
        final List<Op> ops = new ArrayList<>();
        final List<Long> ids = new ArrayList<>();
        long id = 1;
        final int n = 10 + rnd.nextInt(50);
        for (int i = 0; i < n; i++) {
            final int k = rnd.nextInt(10);
            if (k < 2 && !ids.isEmpty()) {
                ops.add(new Op(true, ids.get(rnd.nextInt(ids.size())), rnd.nextBoolean(), 0, 0));
            } else if (k < 4) {
                // Stuff one level up to (and past) maxOrdersPerLevel.
                final boolean ask = rnd.nextBoolean();
                final int lvl = ask ? 5 + rnd.nextInt(5) : rnd.nextInt(5);
                final int m = mopl - 1 + rnd.nextInt(3);
                for (int j = 0; j < m; j++) {
                    ids.add(id);
                    ops.add(new Op(false, id++, !ask, BASE + lvl * TICK, fp(1 + rnd.nextInt(3))));
                }
            } else if (k < 6) {
                // A crossing taker: partial fills, consumed makers (direct head tombstones).
                final boolean buy = rnd.nextBoolean();
                ids.add(id);
                ops.add(new Op(false, id++, buy, BASE + (buy ? 5 + rnd.nextInt(5) : rnd.nextInt(5)) * TICK,
                        fp(0.5 + rnd.nextInt(4))));
            } else {
                final boolean ask = rnd.nextBoolean();
                ids.add(id);
                ops.add(new Op(false, id++, !ask, BASE + (ask ? 5 + rnd.nextInt(5) : rnd.nextInt(5)) * TICK,
                        fp(1 + rnd.nextInt(3))));
            }
        }
        return ops;
    }

    private static MatchingEngine replay(Factory f, List<Op> log) {
        final MatchingEngine me = f.make();
        for (Op op : log) {
            if (op.cancel) {
                if (!me.cancelOrder(op.id, true)) {
                    me.cancelOrder(op.id, false);
                }
            } else {
                me.processLimitOrder(op.id, 1_000 + op.id, op.isBuy, op.price, op.qty);
            }
        }
        return me;
    }

    /** The crossing makers in sweep order from the export: levels best-first, FIFO within a level. */
    private static List<long[]> levels(MatchingEngine me, boolean takerBuys) {
        final long[] side = takerBuys ? me.getAskOrders() : me.getBidOrders();
        final TreeMap<Long, List<Long>> byPrice = new TreeMap<>();
        for (int i = 0; i < side.length; i += 4) {
            byPrice.computeIfAbsent(side[i + 2], p -> new ArrayList<>()).add(side[i + 3]);
        }
        final List<long[]> out = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> e : (takerBuys ? byPrice : byPrice.descendingMap()).entrySet()) {
            final long[] lv = new long[e.getValue().size() + 1];
            lv[0] = e.getKey();
            for (int i = 0; i < e.getValue().size(); i++) {
                lv[i + 1] = e.getValue().get(i);
            }
            out.add(lv);
        }
        return out;
    }

    private static void run(String impl, long seed, Coverage c, Factory f, List<Op> log, boolean isBuy, int priceLevel,
                            int qtyMode, int pick, int cap, boolean direct, int mopl) {
        final String where = impl + " seed " + seed;
        final MatchingEngine a = replay(f, log);
        final MatchingEngine b = replay(f, log);
        final long price = BASE + priceLevel * TICK;

        // The model: S_k over the crossing makers in sweep order.
        final List<long[]> lv = levels(a, isBuy);
        final List<Long> seq = new ArrayList<>();
        final List<Boolean> lastOfLevel = new ArrayList<>();
        boolean crossing = false, nonCrossingAfter = false;
        for (long[] l : lv) {
            final boolean crosses = isBuy ? l[0] <= price : l[0] >= price;
            if (!crosses) {
                nonCrossingAfter = crossing;
                break;
            }
            crossing = true;
            for (int i = 1; i < l.length; i++) {
                seq.add(l[i]);
                lastOfLevel.add(i == l.length - 1);
            }
            if (direct && l.length - 1 == mopl) {
                c.fullLevel++;
            }
            if (direct) {
                final int[] t = S5Probe.directTombstones(a, isBuy, l[0]);
                c.headTomb += t[0] > 0 ? 1 : 0;
                c.middleTomb += t[1] > 0 ? 1 : 0;
            }
        }
        final long[] prefix = new long[seq.size() + 1];
        for (int i = 0; i < seq.size(); i++) {
            prefix[i + 1] = prefix[i] + seq.get(i);
        }
        final long qty;
        final int n = seq.size();
        switch (qtyMode) {
            case 0: qty = n == 0 ? fp(1) : prefix[1 + pick % Math.min(n, cap + 1)]; break;     // S_k, k <= cap + 1
            case 1: qty = n >= cap ? prefix[cap] : fp(1 + pick % 5); break;                     // S_cap exactly
            case 2: qty = n == 0 ? fp(2) : prefix[1 + pick % n] + 1; break;                     // one unit past S_k
            case 3: {                                                                           // a level's last order
                int k = 0;
                for (int i = 0; i < n && k == 0; i++) {
                    if (lastOfLevel.get(i) && pick % 2 == 0) k = i + 1;
                }
                qty = k == 0 ? fp(1 + pick % 7) : prefix[k];
                break;
            }
            default: qty = fp(0.5 * (1 + pick % 16));
        }
        int firstK = -1;
        for (int k = 1; k <= n; k++) {
            if (prefix[k] >= qty) {
                firstK = k;
                break;
            }
        }
        final boolean model = firstK >= 1 && firstK <= cap;

        // A: the pre-walk on the reference engine, which must change nothing.
        final long[] bids = a.getBidOrders(), asks = a.getAskOrders();
        final long bv = a.getBidVersion(), av = a.getAskVersion();
        final boolean pre = S5Probe.canFillCompletely(a, isBuy, price, qty);
        assertArrayEquals(where + ": pre-walk is read-only (bids)", bids, a.getBidOrders());
        assertArrayEquals(where + ": pre-walk is read-only (asks)", asks, a.getAskOrders());
        assertEquals(where + ": pre-walk is read-only (versions)", bv + av, a.getBidVersion() + a.getAskVersion());
        assertEquals(where + ": pre-walk == model (qty " + qty + ", price " + price + ", buy " + isBuy + ", cap " + cap
                + ", firstK " + firstK + ")", model, pre);

        // B: the twin (same log, tombstones included): pre-walk, then the sweep.
        assertEquals(where + ": twin pre-walk", pre, S5Probe.canFillCompletely(b, isBuy, price, qty));
        final int matches = S5Probe.processLimitOrderNoRest(b, 999_999, 1, isBuy, price, qty);
        final boolean filled = b.getTakerRemainingQuantity() == 0 && !b.wasMatchLimitReached();
        assertEquals(where + ": pre-walk true <=> the sweep fills with the cap flag clear (the fence never fires)",
                pre, filled);
        if (pre) {
            assertEquals(where + ": matches == firstK", firstK, matches);
            c.feasible++;
            if (firstK == cap) c.atCap++;
            if (lastOfLevel.get(firstK - 1)) c.lastOfLevel++;
        } else {
            c.infeasible++;
            if (firstK > cap) c.capBlocked++;
        }
        if (nonCrossingAfter) c.nonCrossingAfter++;
    }

    /** Interleaved: FOK probes mutate the seeded book as production does; a feasible one always fills. */
    @Test
    public void interleavedFoksNeverDiverge() {
        for (long seed = 1; seed <= 300; seed++) {
            final Random rnd = new Random(seed);
            final int cap = 1 + rnd.nextInt(8);
            final int mopl = 4 + rnd.nextInt(5);
            final List<Op> log = log(rnd, mopl);
            for (boolean direct : new boolean[]{true, false}) {
                final MatchingEngine me = direct
                        ? new DirectMatchingEngine(BASE, BASE + (LEVELS - 1) * TICK, TICK, cap, mopl)
                        : new ArrayMatchingEngine(BASE, BASE + (LEVELS - 1) * TICK, TICK, 128, cap);
                long id = 1;
                for (Op op : log) {
                    if (op.cancel) {
                        if (!me.cancelOrder(op.id, true)) me.cancelOrder(op.id, false);
                    } else {
                        me.processLimitOrder(op.id, 1, op.isBuy, op.price, op.qty);
                    }
                    id = Math.max(id, op.id + 1);
                    // Interleave FOK probes into the log itself (each mutates the book like production).
                    if (rnd.nextInt(4) == 0) {
                        final boolean buy = rnd.nextBoolean();
                        final long price = BASE + rnd.nextInt(LEVELS) * TICK;
                        final long qty = fp(0.5 * (1 + rnd.nextInt(12)));
                        if (S5Probe.canFillCompletely(me, buy, price, qty)) {
                            S5Probe.processLimitOrderNoRest(me, 500_000 + id, 1, buy, price, qty);
                            assertTrue((direct ? "direct" : "array") + " seed " + seed + ": fence would fire",
                                    me.getTakerRemainingQuantity() == 0 && !me.wasMatchLimitReached());
                        }
                    }
                }
            }
        }
    }
}
