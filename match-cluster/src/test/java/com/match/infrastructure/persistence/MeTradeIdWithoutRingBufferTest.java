// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.fresh2;
import static com.match.application.engine.BehaviourTwoHarness.ofTemplate;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 contract section 3 (G2, K7) and findings F-1 and F-2: tradeId and journalSeq are
 * allocated by the engine before any ring lookup, so a member without egress rings or without
 * any sink allocates exactly what a production member does; the journal appends do not depend
 * on the egress ring; and a sink-less engine keeps the replicated maker bookkeeping.
 */
public class MeTradeIdWithoutRingBufferTest {

    /** A behaviour-2 log with trades, a replace, cancels and a full fill of a maker. */
    static List<Entry> log() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfig(1, 2, G, 12, 50_000));                       // FRESH
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 3));               // order 1 rests
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));               // order 2: trade
        f.add(durableCreate(3, 201, 9003, 1, 59_000, 1));               // order 3: trade
        f.add(durable(4, 2, 100, 9001, 1, 0, 58_000, 1, 0, 0));         // replace 1 -> order 4
        f.add(durableCreate(5, 202, 9005, 1, 58_000, 2));               // order 5: trade, maker 4 FILLED, rests
        f.add(durable(6, 1, 202, 9005, 5, 1, 0, 0, 0, 0));              // cancel 5
        f.add(cancelOrder(100, 99));                                    // legacy cancel ack
        return positioned(f);
    }

    private static byte[] image(Engine engine) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(engine, 0L, buf);
        return Arrays.copyOf(buf.byteArray(), len);
    }

    private static TreeMap<Long, Long> omsMap(Engine engine) {
        final TreeMap<Long, Long> out = new TreeMap<>();
        final Long2LongHashMap m = engine.getOrderIdToOmsOrderId();
        final Long2LongHashMap.KeyIterator it = m.keySet().iterator();
        while (it.hasNext()) {
            final long k = it.nextValue();
            out.put(k, m.get(k));
        }
        return out;
    }

    @Test
    public void replicaWithoutEgressRingsAllocatesTheSameTradeIdsAndSeqs() {
        final List<Entry> log = log();
        try (Replica a = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica b = new Replica(Engine.deferredUntilConfig(), Shape.RINGLESS)) {
            a.apply(log, 0, log.size());
            b.apply(log, 0, log.size());
            final byte[] ia = a.snapshot();
            final byte[] ib = b.snapshot();

            final Engine da = Engine.deferredUntilConfig();
            final Engine db = Engine.deferredUntilConfig();
            try {
                final SnapshotCodec.Decoded decodedA = SnapshotCodec.deserialize(new UnsafeBuffer(ia), 0, ia.length, da);
                final SnapshotCodec.Decoded decodedB = SnapshotCodec.deserialize(new UnsafeBuffer(ib), 0, ib.length, db);
                assertEquals("A traded three times", 4L, decodedA.tradeIdGenerator);
                assertEquals("payload offset 8 of the ring-less replica", decodedA.tradeIdGenerator,
                        decodedB.tradeIdGenerator);
                assertEquals("identity block", da.meIdentity().toString(), db.meIdentity().toString());
                assertEquals(a.h.engine.getTradeIdGenerator(), b.h.engine.getTradeIdGenerator());
                assertEquals(a.h.engine.meIdentity().nextJournalSeq(), b.h.engine.meIdentity().nextJournalSeq());
                assertArrayEquals("whole snapshot", ia, ib);
            } finally {
                da.close();
                db.close();
            }
        }
    }

    @Test
    public void journalAppendsDoNotDependOnTheEgressRing() {
        final List<Entry> log = log();
        try (Replica a = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica b = new Replica(Engine.deferredUntilConfig(), Shape.RINGLESS)) {
            final List<byte[]> fa = a.apply(log, 0, log.size());
            final List<byte[]> fb = b.apply(log, 0, log.size());
            assertEquals("non-vacuous: three trades", 3, ofTemplate(fa, 1).size());
            assertFramesEqual("trades on the ring-less replica", ofTemplate(fa, 1), ofTemplate(fb, 1));
            assertFramesEqual("terminals on the ring-less replica", ofTemplate(fa, 2), ofTemplate(fb, 2));
            assertFramesEqual("every frame", fa, fb);
        }
    }

    /** F-1 (review FIX-2): legacy lane only, no acceptDurable. */
    @Test
    public void sinklessEngineDropsFilledMakerLikeASinkEngine() {
        final Engine c = new Engine("array");
        try (Replica a = new Replica(new Engine("array"), Shape.JOURNALED)) {
            final SbeDemuxer dc = new SbeDemuxer(c);
            final List<Entry> log = positioned(List.of(
                    createOrder(100, 9001, 0, 59_000, 1),     // maker rests with an omsOrderId
                    createOrder(200, 9002, 1, 59_000, 1)));   // crosses it fully
            for (Entry e : log) {
                a.apply(e);
                c.setCurrentLogPosition(e.position);
                dc.dispatch(new UnsafeBuffer(e.frame), 0, e.frame.length, e.timestamp);
            }
            assertTrue("non-vacuous: A's maker is gone", omsMap(a.h.engine).isEmpty());
            assertEquals("the filled maker is gone on the sink-less engine too", omsMap(a.h.engine), omsMap(c));
            assertArrayEquals("snapshot bytes (omsMap section)", image(a.h.engine), image(c));
        } finally {
            c.close();
        }
    }

    private static DurableOrderIntent intent(long idLow, int kind, long userId, long omsOrderId, long oldOrderId,
                                             int side, double price, double qty) {
        return new DurableOrderIntent(17, idLow, userId, omsOrderId, oldOrderId, FixedPoint.fromDouble(price),
                FixedPoint.fromDouble(qty), 0, BehaviourTwoHarness.MARKET, kind, 0, side);
    }

    @Test
    public void sinklessDurableCancelOfAFilledLegIsUnknownLeg() {
        try (BehaviourTwoHarness a = new BehaviourTwoHarness(Engine.deferredUntilConfig(), Shape.JOURNALED);
             BehaviourTwoHarness c = new BehaviourTwoHarness(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
            final List<DurableCommandOutcome> outA = new ArrayList<>();
            final List<DurableCommandOutcome> outC = new ArrayList<>();
            a.apply(fresh2(), 64L);
            c.apply(fresh2(), 64L);
            final DurableOrderIntent[] commands = {
                    intent(1, 0, 100, 9001, 0, 0, 59_000, 1),   // maker leg 1 rests
                    intent(2, 0, 200, 9002, 0, 1, 59_000, 1),   // fills it
                    intent(3, 1, 100, 9001, 1, 0, 0, 0),        // durable CANCEL of the filled leg
            };
            long position = 192L;
            for (DurableOrderIntent cmd : commands) {
                a.engine.setCurrentLogPosition(position);
                c.engine.setCurrentLogPosition(position);
                outA.add(a.engine.acceptDurable(cmd, 1_000L + position));
                outC.add(c.engine.acceptDurable(cmd, 1_000L + position));
                position += 128;
            }
            assertEquals(DurableCommandOutcome.UNKNOWN_LEG, outA.get(2).result());
            assertEquals("equal record-3 decisions", outA, outC);
            assertEquals(a.engine.meIdentity().nextJournalSeq(), c.engine.meIdentity().nextJournalSeq());
            assertEquals(a.engine.getTradeIdGenerator(), c.engine.getTradeIdGenerator());
            assertArrayEquals(image(a.engine), image(c.engine));
            assertTrue("the sink-less member journals nothing", c.drain().isEmpty());
        }
    }
}
