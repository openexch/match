// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import org.junit.Test;

import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract section 6 (W10): frames above the RECORDED wire ceiling. Decided from the
 * header and the replicated ceiling only, so every replica journals the same record at the same
 * position whatever its jar.
 */
public class AboveCeilingFrameTest {

    private static final long POS = 4_160L;
    private static final long TS = 77_000L;

    private static Replica freshAtTwo() {
        final Replica r = new Replica();
        r.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
        return r;
    }

    @Test
    public void templateNineAboveCeilingIsJournaledInvalidOnEveryReplica() {
        final byte[] v13 = withHeaderVersion(durable(7, 0, 100, 9001, 0, 0, 59_000, 1, 2, 0), 13);
        try (Replica a = freshAtTwo(); Replica b = freshAtTwo()) {
            final List<byte[]> fa = a.apply(new Entry(v13, POS, TS));
            final List<byte[]> fb = b.apply(new Entry(v13, POS, TS));
            assertEquals("one record", 1, fa.size());
            assertArrayEquals("byte-equal on both replicas", fa.get(0), fb.get(0));

            final var o = BehaviourTwoHarness.record3(fa.get(0));
            assertEquals(DurableCommandOutcome.INVALID, o.result());
            assertEquals("appliedPosition = log position", POS, o.appliedPosition());
            assertEquals("egressSeq = log position", POS, o.egressSeq());
            assertEquals("timestamp = dispatch timestamp", TS, o.timestamp());
            assertEquals(0L, o.orderId());
            assertEquals(-1, o.status());
            assertEquals(0, o.reason());
            assertEquals(0, o.oldCancelled());
            assertEquals("ledger size unchanged", 0, o.ledgerSize());
            assertEquals(2L, o.behaviorVersion());
            assertEquals(G, o.meEpoch());
            // The 14 intent fields are echoed.
            // RC2 S4: BehaviourTwoLog.durable mints core-shaped ids (idHigh = omsOrderId, attempt << 2 | kind).
            assertEquals(9001L, o.commandIdHigh());
            assertEquals(7L << 2, o.commandIdLow());
            assertEquals(100L, o.userId());
            assertEquals(9001L, o.omsOrderId());
            assertEquals(0L, o.oldOrderId());
            assertEquals(FixedPoint.fromDouble(59_000), o.price());
            assertEquals(FixedPoint.fromDouble(1), o.quantity());
            assertEquals(0L, o.budget());
            assertEquals(BehaviourTwoHarness.MARKET, o.marketId());
            assertEquals(0, o.commandKind());
            assertEquals(0, o.orderType());
            assertEquals(0, o.orderSide());
            assertEquals(2, o.timeInForce());
            assertEquals(0L, o.expectedOldRemaining());

            for (Replica r : new Replica[]{a, b}) {
                assertEquals(1, r.demuxer.aboveCeilingInvalidCount());
                assertEquals(0, r.demuxer.schemaRejectCount());
                assertEquals(0, r.demuxer.decodeRejectCount());
                assertEquals("never recorded in the ledger", 0, r.h.engine.ledgerV2().size());
                assertTrue("no apply", r.h.engine.getEngine(BehaviourTwoHarness.MARKET).isBidEmpty());
                assertEquals(0, r.h.failFast.get());
            }

            // The next v12 frame applies.
            final List<byte[]> next = a.apply(new Entry(durableCreate(8, 100, 9002, 0, 59_000, 1), POS + 128, TS + 1));
            assertEquals(DurableCommandOutcome.APPLIED, BehaviourTwoHarness.record3(next.get(0)).result());
            assertEquals(1, a.h.engine.ledgerV2().size());
            assertFalse(a.h.engine.getEngine(BehaviourTwoHarness.MARKET).isBidEmpty());
        }
    }

    @Test
    public void otherTemplatesAboveCeilingAreCountedDrops() {
        try (Replica r = freshAtTwo()) {
            r.apply(new Entry(withHeaderVersion(cancelOrder(100, 1), 13), POS, TS));
            r.apply(new Entry(withHeaderVersion(engineConfig(2, 2, G, 12, 50_000), 13), POS + 128, TS));
            assertEquals(2, r.demuxer.aboveCeilingDropCount());
            assertEquals(0, r.demuxer.schemaRejectCount());
            assertEquals("the above-ceiling EngineConfig never reached the table", 1L,
                    r.h.engine.getEngineConfig().configVersion);
            assertEquals(1, r.h.decisions.size());
            assertTrue("no journal", r.h.drain().isEmpty());

            // Invalid intent above the ceiling -> decode reject, no journal (F1 included).
            r.apply(new Entry(withHeaderVersion(durable(9, 0, 100, 9001, 0, 0, 59_000, 1, 0, 5), 13), POS + 256, TS));
            assertEquals("F1 above the ceiling", 1, r.demuxer.decodeRejectCount());
            r.apply(new Entry(withHeaderVersion(durable(10, 0, 100, 9001, 0, 0, 59_000, 1, 3, 0), 13), POS + 384, TS));
            assertEquals("tif 3 above the ceiling", 2, r.demuxer.decodeRejectCount());
            assertEquals(0, r.demuxer.aboveCeilingInvalidCount());
            assertTrue(r.h.drain().isEmpty());
            assertEquals(0, r.h.engine.ledgerV2().size());
            assertEquals(0, r.demuxer.applyErrorCount());
        }
    }

    @Test
    public void behaviourOneKeepsTheSchemaGate() {
        try (Replica r = new Replica()) {
            r.apply(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            assertEquals(1L, r.h.engine.getEngineConfig().behaviorVersion);
            r.apply(new Entry(withHeaderVersion(durable(7, 0, 100, 9001, 0, 0, 59_000, 1, 0, 0), 13), POS, TS));
            assertEquals(1, r.demuxer.schemaRejectCount());
            assertEquals(0, r.demuxer.aboveCeilingInvalidCount());
            assertEquals(0, r.demuxer.aboveCeilingDropCount());
            assertTrue(r.h.drain().isEmpty());
        }
    }
    /**
     * Review FIX-1: a later jar (supported version 13) at recorded ceiling 12 journals a v13
     * template 9 as INVALID, byte-equal to a v12 member at the same position. The rule reads the
     * header and the recorded ceiling, never the jar's own version.
     */
    @Test
    public void aLaterJarAtTheRecordedCeilingJournalsInvalidLikeTheV12Members() {
        final byte[] v13 = withHeaderVersion(durable(7, 0, 100, 9001, 0, 0, 59_000, 1, 0, 0), 13);
        try (Replica v12Jar = freshAtTwo();
             Replica v13Jar = new Replica(com.match.application.engine.Engine.deferredUntilConfig(), 13)) {
            v13Jar.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
            final List<byte[]> f12 = v12Jar.apply(new Entry(v13, POS, TS));
            final List<byte[]> f13 = v13Jar.apply(new Entry(v13, POS, TS));
            assertEquals(1, f13.size());
            assertEquals(DurableCommandOutcome.INVALID, BehaviourTwoHarness.record3(f13.get(0)).result());
            assertArrayEquals("same record on both jars", f12.get(0), f13.get(0));
            assertEquals(1, v13Jar.demuxer.aboveCeilingInvalidCount());
            assertEquals(0, v13Jar.h.engine.ledgerV2().size());
            assertEquals(0, v13Jar.h.failFast.get());

            // Other templates above the ceiling: the same counted drop on the later jar.
            v13Jar.apply(new Entry(withHeaderVersion(cancelOrder(100, 1), 13), POS + 128, TS));
            assertEquals(1, v13Jar.demuxer.aboveCeilingDropCount());
            assertEquals(0, v13Jar.demuxer.schemaRejectCount());
        }
        // Control: with ceiling 13 recorded the later jar applies the v13 frame (the seam is
        // not a drop of everything above 12).
        try (Replica v13Jar = new Replica(com.match.application.engine.Engine.deferredUntilConfig(), 13)) {
            v13Jar.apply(new Entry(engineConfig(1, 2, G, 13, 50_000), 64L, 1_000L));
            final List<byte[]> f = v13Jar.apply(new Entry(v13, POS, TS));
            assertEquals(DurableCommandOutcome.APPLIED, BehaviourTwoHarness.record3(f.get(0)).result());
            assertEquals(1, v13Jar.h.engine.ledgerV2().size());
        }
    }
}
