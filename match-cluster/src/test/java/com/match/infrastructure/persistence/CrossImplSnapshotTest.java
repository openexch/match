package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.domain.FixedPoint;
import org.agrona.ExpandableArrayBuffer;
import org.junit.Test;

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
        int length = SnapshotCodec.serialize(from, 7L, 0L, buffer);

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
