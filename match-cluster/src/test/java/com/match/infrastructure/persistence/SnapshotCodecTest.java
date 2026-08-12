// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Durability unit tests for {@link SnapshotCodec} — the byte format every restart and failover
 * recovers from. No Aeron: pure serialize/deserialize over in-memory buffers.
 */
public class SnapshotCodecTest {

    private static final long TRADE_ID_IN = 7L;
    private static final long TIMER_ID_IN = 1_000_000_000_000L;

    /** Serialize → deserialize into a fresh engine restores books, generators, and scalars exactly. */
    @Test
    public void roundTripPreservesState() {
        Engine orig = varied();

        ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, buf);

        Engine restored = new Engine();
        SnapshotCodec.Decoded d = SnapshotCodec.deserialize(buf, 0, len, restored);

        assertEquals("orderId generator", orig.getOrderIdGenerator(), restored.getOrderIdGenerator());
        assertEquals("tradeId generator carried", TRADE_ID_IN, d.tradeIdGenerator);
        assertTrue("timer correlation present", d.timerCorrelationIdPresent);
        assertEquals("timer correlation carried", TIMER_ID_IN, d.timerCorrelationId);
        assertEquals("no orders dropped", 0, d.rejectedOrders);

        // Books are byte-identical: getBidOrders/getAskOrders is the canonical book serialization.
        Int2ObjectHashMap<?>.KeyIterator it = orig.getEngines().keySet().iterator();
        while (it.hasNext()) {
            int m = it.nextInt();
            assertArrayEquals("bids for market " + m,
                    orig.getEngine(m).getBidOrders(), restored.getEngine(m).getBidOrders());
            assertArrayEquals("asks for market " + m,
                    orig.getEngine(m).getAskOrders(), restored.getEngine(m).getAskOrders());
        }
    }

    /** The same state serializes to byte-identical output every time (pins map/book iteration order). */
    @Test
    public void serializeIsByteDeterministic() {
        Engine orig = varied();

        ExpandableArrayBuffer b1 = new ExpandableArrayBuffer();
        int l1 = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, b1);
        ExpandableArrayBuffer b2 = new ExpandableArrayBuffer();
        int l2 = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, b2);

        assertEquals("payload length stable", l1, l2);
        assertArrayEquals("snapshot bytes must be deterministic", bytes(b1, l1), bytes(b2, l2));
    }

    /** decode → encode → decode → encode is stable: a restored snapshot re-serializes identically. */
    @Test
    public void restoreIsIdempotent() {
        Engine orig = varied();
        ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, buf);

        Engine e1 = new Engine();
        SnapshotCodec.Decoded d1 = SnapshotCodec.deserialize(buf, 0, len, e1);
        ExpandableArrayBuffer r1 = new ExpandableArrayBuffer();
        int lr1 = SnapshotCodec.serialize(e1, d1.tradeIdGenerator, d1.timerCorrelationId, r1);

        Engine e2 = new Engine();
        SnapshotCodec.Decoded d2 = SnapshotCodec.deserialize(r1, 0, lr1, e2);
        ExpandableArrayBuffer r2 = new ExpandableArrayBuffer();
        int lr2 = SnapshotCodec.serialize(e2, d2.tradeIdGenerator, d2.timerCorrelationId, r2);

        assertArrayEquals("re-serialized snapshot must be stable", bytes(r1, lr1), bytes(r2, lr2));
    }

    /** An order that can't be restored (out-of-range price) must be reported — state loss is never silent. */
    @Test
    public void geometryMismatchIsLoud() {
        ExpandableArrayBuffer bad = new ExpandableArrayBuffer();
        int p = 0;
        bad.putLong(p, 5);  p += 8;   // orderIdGen
        bad.putLong(p, 1);  p += 8;   // tradeIdGen
        bad.putInt(p, 1);   p += 4;   // numMarkets
        bad.putInt(p, Engine.MARKET_BTC_USD); p += 4;
        bad.putInt(p, 1);   p += 4;   // numBidOrders = 1
        bad.putLong(p, 1);  p += 8;   // orderId
        bad.putLong(p, 1);  p += 8;   // userId
        bad.putLong(p, FixedPoint.fromDouble(200_000.0)); p += 8; // price ABOVE the $150k BTC ceiling
        bad.putLong(p, FixedPoint.fromDouble(1.0)); p += 8;       // qty
        bad.putInt(p, 0);   p += 4;   // numAskOrders = 0
        bad.putLong(p, 0);  p += 8;   // timerCorrelationId

        Engine e = new Engine();
        SnapshotCodec.Decoded d = SnapshotCodec.deserialize(bad, 0, p, e);

        assertTrue("out-of-range order must be counted as a dropped order (loud state loss)",
                d.rejectedOrders >= 1);
        assertTrue("the dropped order must NOT be on the book", e.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
    }

    /** A pre-match#25 snapshot without the trailing timer counter decodes without error. */
    @Test
    public void decodesSnapshotWithoutTrailingTimer() {
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int p = 0;
        b.putLong(p, 9);  p += 8;   // orderIdGen
        b.putLong(p, 3);  p += 8;   // tradeIdGen
        b.putInt(p, 0);   p += 4;   // numMarkets = 0 (and NO trailing timer field)

        Engine e = new Engine();
        SnapshotCodec.Decoded d = SnapshotCodec.deserialize(b, 0, p, e);

        assertEquals(9, e.getOrderIdGenerator());
        assertEquals(3, d.tradeIdGenerator);
        assertTrue("timer counter must be absent, not garbage", !d.timerCorrelationIdPresent);
    }

    /** A-1: the orderId -> omsOrderId correlation map round-trips exactly and stays byte-deterministic. */
    @Test
    public void omsOrderIdMapRoundTripsExactly() {
        Engine orig = varied();
        // A-1 added this map to the snapshot. Populate it with entries whose natural hash-map
        // iteration order is NOT ascending, so the round-trip proves both fidelity and the sort.
        long[][] pairs = {{17, 1700}, {3, 300}, {42, 4200}, {8, 800}, {25, 2500}, {1, 100}};
        for (long[] p : pairs) {
            orig.getOrderIdToOmsOrderId().put(p[0], p[1]);
        }

        ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, buf);

        Engine restored = new Engine();
        SnapshotCodec.deserialize(buf, 0, len, restored);

        Long2LongHashMap rm = restored.getOrderIdToOmsOrderId();
        assertEquals("oms map size restored", orig.getOrderIdToOmsOrderId().size(), rm.size());
        for (long[] p : pairs) {
            assertEquals("omsOrderId restored for orderId " + p[0], p[1], rm.get(p[0]));
        }

        // Determinism: the same populated state serializes byte-identically twice.
        ExpandableArrayBuffer buf2 = new ExpandableArrayBuffer();
        int len2 = SnapshotCodec.serialize(orig, TRADE_ID_IN, TIMER_ID_IN, buf2);
        assertEquals("payload length stable with oms map", len, len2);
        assertArrayEquals("snapshot with oms map must be byte-deterministic", bytes(buf, len), bytes(buf2, len2));
    }

    /**
     * A-1 determinism: the oms map is written ASCENDING by orderId, independent of insertion order.
     * {@link Long2LongHashMap} iterates in slot order (hash-derived), not sorted, so without the sort
     * the serialized orderIds would follow that scrambled order and this fails — which is exactly the
     * cross-replica snapshot fork the sort prevents.
     */
    @Test
    public void omsOrderIdMapIsSerializedAscendingByOrderId() {
        Engine e = new Engine();
        long[] insertOrder = {17, 3, 42, 8, 25, 1, 99, 12, 6, 30, 2, 51};
        for (long id : insertOrder) {
            e.getOrderIdToOmsOrderId().put(id, id * 100);
        }

        ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(e, TRADE_ID_IN, TIMER_ID_IN, buf);

        long[] serializedIds = extractOmsMapOrderIds(buf);
        long[] expected = insertOrder.clone();
        Arrays.sort(expected);
        assertArrayEquals("A-1: oms map orderIds must be written ascending (cross-replica determinism)",
                expected, serializedIds);
    }

    /** Walk the snapshot byte format and return the oms-map section's orderIds in wire order. */
    private static long[] extractOmsMapOrderIds(ExpandableArrayBuffer b) {
        int pos = 0;
        pos += 8; // orderIdGenerator
        pos += 8; // tradeIdGenerator
        int numMarkets = b.getInt(pos); pos += 4;
        for (int m = 0; m < numMarkets; m++) {
            pos += 4; // marketId
            int numBid = b.getInt(pos); pos += 4; pos += numBid * 4 * 8;
            int numAsk = b.getInt(pos); pos += 4; pos += numAsk * 4 * 8;
        }
        pos += 8; // timerCorrelationId
        int count = b.getInt(pos); pos += 4;
        long[] ids = new long[count];
        for (int i = 0; i < count; i++) {
            ids[i] = b.getLong(pos); pos += 8; // orderId
            pos += 8;                           // omsOrderId
        }
        return ids;
    }

    // ---- helpers ----

    /** An engine with resting orders across several markets and price levels. */
    private static Engine varied() {
        Engine e = new Engine();
        placeLimit(e, Engine.MARKET_BTC_USD, 100, OrderSide.BID, 60_000.0, 1.0);
        placeLimit(e, Engine.MARKET_BTC_USD, 101, OrderSide.BID, 59_000.0, 2.0);
        placeLimit(e, Engine.MARKET_BTC_USD, 102, OrderSide.ASK, 61_000.0, 1.5);
        placeLimit(e, Engine.MARKET_BTC_USD, 103, OrderSide.ASK, 62_000.0, 0.25);
        placeLimit(e, Engine.MARKET_ETH_USD, 200, OrderSide.ASK, 3_000.0, 2.0);
        placeLimit(e, Engine.MARKET_SOL_USD, 300, OrderSide.BID, 200.0, 10.0);
        return e;
    }

    private static void placeLimit(Engine e, int market, long user, OrderSide side, double px, double qty) {
        CreateOrderCommand c = new CreateOrderCommand();
        c.setUserId(user);
        c.setOrderSide(side);
        c.setOrderType(OrderType.LIMIT);
        c.setPrice(FixedPoint.fromDouble(px));
        c.setQuantity(FixedPoint.fromDouble(qty));
        c.setTotalPrice(0);
        e.acceptOrder(market, Engine.CMD_CREATE, c, 1000L);
    }

    private static byte[] bytes(ExpandableArrayBuffer b, int len) {
        return Arrays.copyOf(b.byteArray(), len);
    }
}
