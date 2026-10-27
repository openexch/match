// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.junit.Test;

import java.util.List;
import java.util.TreeMap;

import static com.match.infrastructure.persistence.BehaviourTwoLog.assertFramesEqual;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.S5Frames.*;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Cross-implementation snapshot compatibility. The on-disk snapshot format is a storage-independent
 * flat list of resting orders ({@code [orderId, userId, price, qty]} 4-tuples), so a snapshot taken
 * by one matching implementation must restore losslessly into the other.
 *
 * <p>This is what makes the array-backed engine safe to roll out on a live direct-index cluster (and
 * to roll back): an existing node's snapshot loads into a node running the other implementation, and
 * the restored resting books are identical (both serialize in ascending price, FIFO within a level).
 */
public class CrossImplSnapshotTest {

    private static final int[] MARKETS = {
            Engine.MARKET_BTC_USD, Engine.MARKET_ETH_USD, Engine.MARKET_SOL_USD,
            Engine.MARKET_XRP_USD, Engine.MARKET_DOGE_USD
    };

    @Test
    public void directSnapshotRestoresIntoArrayBook() {
        assertRoundTrip("direct", "array");
    }

    @Test
    public void arraySnapshotRestoresIntoDirectBook() {
        assertRoundTrip("array", "direct");
    }

    private void assertRoundTrip(String fromImpl, String toImpl) {
        Engine from = new Engine(fromImpl);
        seedRestingBooks(from);

        ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
        from.setTradeIdGenerator(7L); int length = SnapshotCodec.serialize(from, 0L, buffer);

        Engine to = new Engine(toImpl);
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(buffer, 0, length, to);

        assertEquals("no resting order should be dropped across implementations", 0, decoded.rejectedOrders);

        for (int market : MARKETS) {
            MatchingEngine a = from.getEngine(market);
            MatchingEngine b = to.getEngine(market);
            assertArrayEquals("bid book mismatch (" + fromImpl + "->" + toImpl + ") market=" + market,
                    a.getBidOrders(), b.getBidOrders());
            assertArrayEquals("ask book mismatch (" + fromImpl + "->" + toImpl + ") market=" + market,
                    a.getAskOrders(), b.getAskOrders());
        }
        assertEquals(from.getOrderIdGenerator(), to.getOrderIdGenerator());
    }

    // ==================== RC2 S5: behaviour-2 TIF and amend history ====================

    private static final int A = DurableCommandOutcome.APPLIED, R = DurableCommandOutcome.REJECTED;

    /** One step on both replicas: equal journals, the expected outcome; returns the outcome's orderId. */
    private static long step(Run arr, Run dir, byte[] frame, int result, int status, int reason) {
        final List<byte[]> fa = arr.send(frame);
        final List<byte[]> fd = dir.send(frame);
        assertFramesEqual("array vs direct journal", fa, fd);
        final CommandOutcomeDecoder o = only3(fa);
        assertEquals("result", result, o.result());
        assertEquals("status", status, o.status());
        assertEquals("reason", reason, o.reason());
        return o.orderId();
    }

    private static long rest(Run arr, Run dir, long h, int side, double price, double qty) {
        return step(arr, dir, limit(h, 0, 100 + h, side, price, qty, GTC), A, NEW, 0);
    }

    private static TreeMap<Long, Long> omsMap(Engine e) {
        final TreeMap<Long, Long> out = new TreeMap<>();
        final Long2LongHashMap m = e.getOrderIdToOmsOrderId();
        final Long2LongHashMap.KeyIterator it = m.keySet().iterator();
        while (it.hasNext()) {
            final long k = it.nextValue();
            out.put(k, m.get(k));
        }
        return out;
    }

    /**
     * RC2 S5 section 9: one behaviour-2 log (FRESH with the same cap 4; direct with 4 orders per
     * level) through the array and the direct implementation: every step's outcome, byte-equal
     * journals, equal books, omsMap and ledger v2 block, and each image round-trips byte-equal on
     * its own implementation.
     */
    @Test
    public void behaviourTwoTifAndAmendHistoryIsImplementationIndependent() {
        try (Run arr = freshWith(S5Frames.config(false, 4, 0));
             Run dir = freshWith(S5Frames.config(true, 4, 4))) {
            rest(arr, dir, 1, ASK, 60_000, 1);
            rest(arr, dir, 2, ASK, 60_001, 1);
            step(arr, dir, limit(3, 0, 200, BID, 60_001, 3, IOC), A, CANCELLED, IOC_REMAINDER);       // IOC partial
            step(arr, dir, limit(4, 0, 200, BID, 60_001, 1, IOC), A, CANCELLED, IOC_REMAINDER);       // IOC no match
            rest(arr, dir, 5, ASK, 60_000, 1);
            rest(arr, dir, 6, ASK, 60_001, 1);
            step(arr, dir, limit(7, 0, 200, BID, 60_001, 3, FOK), A, CANCELLED, FOK_UNFILLABLE);      // FOK by quantity
            rest(arr, dir, 8, ASK, 60_002, 1);
            rest(arr, dir, 9, ASK, 60_002, 1);
            rest(arr, dir, 10, ASK, 60_002, 1);
            step(arr, dir, limit(11, 0, 200, BID, 60_002, 5, FOK), A, CANCELLED, FOK_UNFILLABLE);     // FOK by cap
            rest(arr, dir, 12, ASK, 60_002, 1);                                                      // full direct level
            step(arr, dir, limit(13, 0, 200, BID, 60_001, 2, IOC), A, FILLED, 0);
            step(arr, dir, limit(14, 0, 200, BID, 60_002, 4, FOK), A, FILLED, 0);                    // FOK, full level
            rest(arr, dir, 15, ASK, 60_010, 1);
            rest(arr, dir, 16, ASK, 60_011, 1);
            rest(arr, dir, 17, ASK, 60_012, 1);
            step(arr, dir, market(18, 0, 200, BID, 2, 1_000_000, GTC), A, FILLED, 0);                // bounded buy
            step(arr, dir, market(19, 0, 200, BID, 1, 30_000, GTC), A, CANCELLED, BUDGET_EXHAUSTED); // budget first
            final long l1 = rest(arr, dir, 20, BID, 59_000, 10);
            step(arr, dir, market(21, 0, 300, ASK, 3, 0, GTC), A, FILLED, 0);
            final long l2 = step(arr, dir, amendRaw(20, 1, 120, l1, BID, 59_001, 8, LIMIT, GTC, fp(10)), A, NEW, 0);
            assertEquals("reduced leg", fp(5), remainingOf(book(arr), l2));
            step(arr, dir, market(22, 0, 300, ASK, 3, 0, GTC), A, FILLED, 0);
            assertEquals("no-remainder amend names the old leg", l2,
                    step(arr, dir, amendRaw(20, 2, 120, l2, BID, 59_001, 1, LIMIT, GTC, fp(5)), R, REJECTED,
                            AMEND_NO_REMAINDER));
            final long l3 = rest(arr, dir, 23, BID, 58_000, 10);
            step(arr, dir, amendRaw(23, 1, 123, l3, BID, 58_001, 8, LIMIT, GTC, fp(4)), R, REJECTED,
                    AMEND_REMAINING_MISMATCH);

            final MatchingEngine ma = book(arr), md = book(dir);
            assertArrayEquals("bids", ma.getBidOrders(), md.getBidOrders());
            assertArrayEquals("asks", ma.getAskOrders(), md.getAskOrders());
            assertEquals("omsMap", omsMap(arr.engine()), omsMap(dir.engine()));
            assertArrayEquals("ledger v2 block", S4Probe.ledgerBlock(arr.engine()), S4Probe.ledgerBlock(dir.engine()));
            assertEquals(arr.engine().getOrderIdGenerator(), dir.engine().getOrderIdGenerator());
            assertEquals(arr.engine().getTradeIdGenerator(), dir.engine().getTradeIdGenerator());
            for (Run r : new Run[]{arr, dir}) {
                final byte[] image = r.r.snapshot();
                try (BehaviourTwoLog.Replica restored = BehaviourTwoLog.Replica.restoredFrom(image)) {
                    assertArrayEquals("image round-trips on its own impl", image, restored.snapshot());
                }
            }
        }
    }

    /** Seed non-crossing resting orders, including multiple orders per level (FIFO) across markets. */
    private void seedRestingBooks(Engine e) {
        MatchingEngine btc = e.getEngine(Engine.MARKET_BTC_USD); // base 50000, tick 1.0
        btc.addOrderNoMatch(1, 100, true, fp(60_000.0), fp(1.0));
        btc.addOrderNoMatch(2, 101, true, fp(60_000.0), fp(2.0)); // same level as #1, FIFO after it
        btc.addOrderNoMatch(3, 102, true, fp(59_999.0), fp(0.5));
        btc.addOrderNoMatch(4, 103, false, fp(61_000.0), fp(1.5));
        btc.addOrderNoMatch(5, 104, false, fp(61_001.0), fp(0.7));

        MatchingEngine eth = e.getEngine(Engine.MARKET_ETH_USD); // base 1000, tick 0.5
        eth.addOrderNoMatch(6, 200, true, fp(2_000.0), fp(3.0));
        eth.addOrderNoMatch(7, 201, true, fp(2_000.0), fp(1.0));
        eth.addOrderNoMatch(8, 202, false, fp(2_500.5), fp(4.0));

        MatchingEngine doge = e.getEngine(Engine.MARKET_DOGE_USD); // base 0.05, tick 0.0001
        doge.addOrderNoMatch(9, 300, true, fp(0.1000), fp(100.0));
        doge.addOrderNoMatch(10, 301, false, fp(0.2000), fp(50.0));
    }

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }
}
