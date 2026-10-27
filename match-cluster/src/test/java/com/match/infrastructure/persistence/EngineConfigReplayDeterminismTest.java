// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigStateMachine;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Slice C replay determinism: two independent service-state instances (deferred engine +
 * SbeDemuxer + EngineConfigStateMachine — the full replicated command path, minus Aeron
 * transport) process the SAME logged byte stream (EngineConfig, then orders, then a duplicate
 * config, then a differing config) and must serialize BYTE-IDENTICAL snapshots. This is the
 * cross-replica guarantee: an EngineConfig in the log forks nothing.
 */
public class EngineConfigReplayDeterminismTest {

    /** One replica's replicated state + ingress path. */
    private static final class Replica {
        final Engine engine = Engine.deferredUntilConfig();
        final SbeDemuxer demuxer = new SbeDemuxer(engine);
        final EngineConfigStateMachine sm = new EngineConfigStateMachine(engine,
                () -> fail("failFast must not fire in this scenario"), null);

        Replica() {
            demuxer.setEngineConfigHandler(sm::onEngineConfig);
            // The reject handler is egress-only (no replicated state); a no-op stands in for it.
            demuxer.setPreConfigOrderRejectHandler((m, u, o, b, t) -> { });
        }

        void apply(List<byte[]> frames) {
            long timestamp = 1_000L;
            for (byte[] frame : frames) {
                demuxer.dispatchTrusted(new UnsafeBuffer(frame), 0, frame.length, timestamp++);
            }
        }

        byte[] snapshot() {
            ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            engine.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(engine, 0L, buf);
            return Arrays.copyOf(buf.byteArray(), len);
        }
    }

    private static byte[] engineConfigFrame(long configVersion, long bookCapacity) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        EngineConfigEncoder encoder = new EngineConfigEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .configVersion(configVersion)
                .impl(EngineImpl.ARRAY)
                .bookCapacity(bookCapacity)
                .maxMatchesPerOrder(100)
                .maxOrdersPerLevel(0)
                // v12: an unset behaviorVersion is 0, which RC2 S2 refuses (S-b).
                .behaviorVersion(1);
        EngineConfigEncoder.MarketsEncoder group = encoder.marketsCount(2);
        group.next().marketId(1)
                .symbol("BTC-USD")
                .minPrice(FixedPoint.fromDouble(50_000.0))
                .maxPrice(FixedPoint.fromDouble(150_000.0))
                .tickSize(FixedPoint.fromDouble(1.0));
        group.next().marketId(3)
                .symbol("SOL-USD")
                .minPrice(FixedPoint.fromDouble(50.0))
                .maxPrice(FixedPoint.fromDouble(500.0))
                .tickSize(FixedPoint.fromDouble(0.05));
        return Arrays.copyOf(buffer.byteArray(),
                MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private static byte[] createOrderFrame(long userId, int marketId, boolean isBuy,
                                           long omsOrderId, double price, double qty) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.price(FixedPoint.fromDouble(price));
        encoder.quantity(FixedPoint.fromDouble(qty));
        encoder.marketId(marketId);
        encoder.orderType(OrderType.LIMIT);
        encoder.orderSide(isBuy ? OrderSide.BID : OrderSide.ASK);
        encoder.omsOrderId(omsOrderId);
        return Arrays.copyOf(buffer.byteArray(),
                MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    @Test
    public void twoReplicasProcessingTheSameLogSnapshotByteIdentically() {
        List<byte[]> log = new ArrayList<>();
        log.add(createOrderFrame(1L, 1, true, 9001L, 60_000.0, 1.0)); // pre-config -> guard-rejected
        log.add(engineConfigFrame(1L, 4096));                        // fresh accept
        log.add(createOrderFrame(2L, 1, true, 9002L, 60_000.0, 1.0)); // rests
        log.add(createOrderFrame(3L, 1, false, 9003L, 60_000.0, 0.4)); // matches partially
        log.add(createOrderFrame(4L, 3, false, 9004L, 200.0, 10.0));  // second market rests
        log.add(engineConfigFrame(1L, 4096));                        // duplicate -> no-op ACK
        log.add(engineConfigFrame(2L, 8192));                        // different -> reject
        log.add(createOrderFrame(5L, 1, true, 9005L, 59_999.0, 2.0)); // more traffic after the reject

        Replica a = new Replica();
        Replica b = new Replica();
        a.apply(log);
        b.apply(log);

        // The decision table landed identically...
        assertEquals(1, a.sm.acceptedCount());
        assertEquals(1, a.sm.duplicateCount());
        assertEquals(1, a.sm.rejectCount());
        assertEquals(1, a.demuxer.preConfigOrderRejectCount());
        assertEquals(a.sm.acceptedCount(), b.sm.acceptedCount());
        assertEquals(a.sm.duplicateCount(), b.sm.duplicateCount());
        assertEquals(a.sm.rejectCount(), b.sm.rejectCount());
        assertEquals(a.demuxer.preConfigOrderRejectCount(), b.demuxer.preConfigOrderRejectCount());

        // ...the books actually carry state (this test is not vacuous)...
        assertTrue(a.engine.hasEngines());
        assertFalse(a.engine.getEngine(1).isBidEmpty());
        assertFalse(a.engine.getEngine(3).isAskEmpty());

        // ...and the snapshots — books + generators + oms map + engineConfig block — are
        // byte-identical across the two replicas.
        assertArrayEquals("replicas processing the same log must snapshot byte-identically",
                a.snapshot(), b.snapshot());
    }
    /**
     * RC2 S2: two replicas on a behaviour-2 log (FRESH at 2, trades, BUMP, DUPLICATE, STALE, an
     * above-ceiling frame, a second BUMP) emit byte-equal journal frames and snapshot
     * byte-identically, the identity block included.
     */
    @Test
    public void twoReplicasBehaviourTwo() {
        final List<BehaviourTwoLog.Entry> log = EngineConfigSpliceDeterminismTest.log();
        try (BehaviourTwoLog.Replica a = new BehaviourTwoLog.Replica();
             BehaviourTwoLog.Replica b = new BehaviourTwoLog.Replica()) {
            final List<byte[]> framesA = a.apply(log, 0, log.size());
            final List<byte[]> framesB = b.apply(log, 0, log.size());
            assertFalse(framesA.isEmpty());
            assertEquals(framesA.size(), framesB.size());
            for (int i = 0; i < framesA.size(); i++) {
                assertArrayEquals("journal frame " + i, framesA.get(i), framesB.get(i));
            }
            assertEquals(a.h.decisions, b.h.decisions);
            final byte[] snapA = a.snapshot();
            assertArrayEquals("behaviour-2 snapshots byte-identical", snapA, b.snapshot());
            // The image carries the identity block: enveloped, and restoring it binds the epoch.
            try (BehaviourTwoLog.Replica r = BehaviourTwoLog.Replica.restoredFrom(snapA)) {
                assertTrue(r.h.engine.meIdentity().isBound());
                assertEquals(com.match.application.engine.BehaviourTwoHarness.G, r.h.engine.meIdentity().meEpoch());
                assertEquals(a.h.engine.meIdentity().nextJournalSeq(), r.h.engine.meIdentity().nextJournalSeq());
            }
            assertEquals(0, a.h.failFast.get());
        }
    }
}
