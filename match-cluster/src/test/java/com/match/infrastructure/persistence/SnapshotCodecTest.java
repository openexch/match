// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Int2ObjectHashMap;
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
