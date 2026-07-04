// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Property-style tests over a large, SEEDED pseudo-random order stream. Complements the
 * hand-authored determinism corpus by exercising the matching engine at scale while asserting
 * structural invariants and determinism.
 *
 * <p>Same seed → same stream → same result: the determinism check replays one stream through two
 * independent engines and requires byte-identical trade tapes and final books.</p>
 */
public class MatchingInvariantsTest {

    private static final long BASE_PRICE = FixedPoint.fromDouble(10.0);
    private static final long MAX_PRICE = FixedPoint.fromDouble(1000.0);
    private static final long TICK_SIZE = FixedPoint.fromDouble(0.01);

    /** No crossed book ever, and strict quantity conservation across the whole run. */
    @Test
    public void invariantsHoldOverSeededStream() {
        DirectMatchingEngine e = new DirectMatchingEngine(BASE_PRICE, MAX_PRICE, TICK_SIZE);
        List<long[]> stream = genStream(42L, 600);

        long restedTotal = 0;   // Σ of remainders that successfully rested on the book
        long tradedTotal = 0;   // Σ of all matched quantity
        long orderId = 1;

        for (long[] o : stream) {
            boolean isBuy = o[0] == 1;
            int matchCount = e.processLimitOrder(orderId, orderId, isBuy, o[1], o[2]);
            orderId++;

            for (int i = 0; i < matchCount; i++) {
                tradedTotal += e.getMatchQuantity(i);
            }
            if (e.getLastRestRejectReason() == OrderRejectReason.NONE) {
                restedTotal += e.getTakerRemainingQuantity();
            }

            // Invariant 1: the book is never crossed.
            if (!e.isBidEmpty() && !e.isAskEmpty()) {
                assertTrue("crossed book: bestBid=" + e.getBestBid() + " bestAsk=" + e.getBestAsk(),
                        e.getBestBid() < e.getBestAsk());
            }
        }

        // Invariant 2: everything that ever rested is either still resting or has been traded away.
        long bookQty = restingQty(e.getBidOrders()) + restingQty(e.getAskOrders());
        assertEquals("conservation: Σrested must equal bookQty + Σtraded",
                restedTotal, bookQty + tradedTotal);
    }

    /** Determinism: one seeded stream through two fresh engines yields identical trades + book. */
    @Test
    public void deterministicAcrossTwoEngines() {
        List<long[]> stream = genStream(1234L, 500);
        assertEquals("same input must produce identical trades and final book",
                replayAndCapture(stream), replayAndCapture(stream));
    }

    // ---- helpers ----

    private static String replayAndCapture(List<long[]> stream) {
        DirectMatchingEngine e = new DirectMatchingEngine(BASE_PRICE, MAX_PRICE, TICK_SIZE);
        StringBuilder sb = new StringBuilder();
        long orderId = 1;
        for (long[] o : stream) {
            int matchCount = e.processLimitOrder(orderId, orderId, o[0] == 1, o[1], o[2]);
            for (int i = 0; i < matchCount; i++) {
                sb.append(e.getMatchMakerOrderId(i)).append(',')
                        .append(e.getMatchPrice(i)).append(',')
                        .append(e.getMatchQuantity(i)).append(';');
            }
            sb.append('|');
            orderId++;
        }
        sb.append("BIDS:").append(Arrays.toString(e.getBidOrders()));
        sb.append("ASKS:").append(Arrays.toString(e.getAskOrders()));
        return sb.toString();
    }

    /** Generate {isBuy, price, qty} triples clustered around $100 ±$10, on-tick, qty 0.1..3.0. */
    private static List<long[]> genStream(long seed, int n) {
        Random rnd = new Random(seed);
        long mid = FixedPoint.fromDouble(100.0);
        List<long[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long isBuy = rnd.nextBoolean() ? 1 : 0;
            int ticksFromMid = rnd.nextInt(2001) - 1000;          // ±1000 ticks = ±$10
            long price = mid + TICK_SIZE * ticksFromMid;
            long qty = FixedPoint.fromDouble(0.1) * (1 + rnd.nextInt(30)); // 0.1 .. 3.0
            out.add(new long[]{isBuy, price, qty});
        }
        return out;
    }

    private static long restingQty(long[] orders) {
        long sum = 0;
        for (int i = 0; i < orders.length; i += 4) {
            sum += orders[i + 3]; // layout: [orderId, userId, price, qty]
        }
        return sum;
    }
}
