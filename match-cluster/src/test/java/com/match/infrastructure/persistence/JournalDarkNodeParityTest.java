// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigStateMachine.Decision;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.generated.ClusterHeartbeatDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.journalSeqOf;
import static com.match.application.engine.BehaviourTwoHarness.ofTemplate;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 contract section 4 (R-B0-2): a journal-on, a journal-off and a ring-less replica of
 * one behaviour-2 log end with the same snapshot bytes (tradeId, identity block with
 * nextJournalSeq), and on the journal-on replica the sequence has no producer-side gap:
 * {@code nextJournalSeq - 1 == max journalSeq == number of frames}.
 */
public class JournalDarkNodeParityTest {

    static List<Entry> log() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfig(1, 2, G, 12, 50_000));                               // FRESH
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 2));                       // order 1 rests
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));                       // trade
        f.add(durable(3, 2, 100, 9001, 1, 0, 58_500, 1, 0, 0));                 // replace 1 -> 3
        f.add(durableCreate(4, 300, 9004, 1, 58_500, 1));                       // trade, maker 3 FILLED
        f.add(durableCreate(5, 400, 9005, 0, 57_000, 1));                       // order 5 rests
        f.add(durable(6, 1, 400, 9005, 5, 0, 0, 0, 0, 0));                      // cancel 5
        f.add(cancelOrder(100, 99));                                            // legacy cancel ack
        f.add(durableCreate(2, 200, 9002, 1, 59_100, 1));                       // CONFLICT
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));                       // suppressed duplicate
        f.add(withHeaderVersion(durableCreate(7, 500, 9007, 1, 58_000, 1), 13)); // above ceiling: INVALID
        f.add(engineConfig(2, 2, G, 12, 50_000));                               // BUMP, ceiling 12, cv + 1
        f.add(durableCreate(8, 600, 9008, 1, 56_000, 1));                       // after the BUMP
        return positioned(f);
    }

    private static long heartbeatEpoch(byte[] image) {
        final S2Services.Service svc = new S2Services.Service(true);
        svc.restore(image);
        assertEquals(0, svc.failFast.get());
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
        svc.service.encodeKeepWarm(buf, 1, 123L);
        return new ClusterHeartbeatDecoder().wrapAndApplyHeader(buf, 0, new MessageHeaderDecoder()).meEpoch();
    }

    @Test
    public void darkAndJournaledReplicasHaveEqualSeqEpochAndTradeIds() {
        final List<Entry> log = log();
        try (Replica on = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica dark = new Replica(Engine.deferredUntilConfig(), Shape.DARK);
             Replica ringless = new Replica(Engine.deferredUntilConfig(), Shape.RINGLESS)) {
            final List<byte[]> frames = on.apply(log, 0, log.size());
            assertTrue("journal off: no frame", dark.apply(log, 0, log.size()).isEmpty());
            final List<byte[]> ringlessFrames = ringless.apply(log, 0, log.size());

            assertEquals(List.of(Decision.FRESH, Decision.BUMP), on.h.decisions);
            assertEquals(on.h.decisions, dark.h.decisions);
            assertEquals(on.h.decisions, ringless.h.decisions);
            assertEquals(0, on.h.failFast.get());

            // Non-vacuous: trades, terminals, a CONFLICT, an INVALID, two record 5, one suppression.
            assertTrue(ofTemplate(frames, 1).size() >= 2);
            assertTrue(ofTemplate(frames, 2).size() >= 3);
            assertEquals(2, ofTemplate(frames, 5).size());
            final List<Integer> results = new ArrayList<>();
            for (byte[] r3 : ofTemplate(frames, 3)) {
                results.add((int) record3(r3).result());
            }
            assertTrue(results.contains(DurableCommandOutcome.CONFLICT));
            assertTrue(results.contains(DurableCommandOutcome.INVALID));

            final long next = on.h.engine.meIdentity().nextJournalSeq();
            assertEquals("nextJournalSeq - 1 == number of frames (no producer-side gap)", frames.size(), next - 1);
            long max = 0;
            for (int i = 0; i < frames.size(); i++) {
                assertEquals("dense from 1 in ring order", i + 1L, journalSeqOf(frames.get(i)));
                max = Math.max(max, journalSeqOf(frames.get(i)));
            }
            assertEquals("nextJournalSeq - 1 == max journalSeq", max, next - 1);
            assertEquals("max journalSeq == number of frames", frames.size(), max);
            assertSeqEpochInvariant(frames);
            assertEquals(1L, on.h.engine.suppressedDuplicateCount());
            assertFramesEqual("ring-less replica journal", frames, ringlessFrames);

            for (Replica other : List.of(dark, ringless)) {
                assertEquals(next, other.h.engine.meIdentity().nextJournalSeq());
                assertEquals(on.h.engine.getTradeIdGenerator(), other.h.engine.getTradeIdGenerator());
                assertEquals(on.h.engine.suppressedDuplicateCount(), other.h.engine.suppressedDuplicateCount());
            }
            final byte[] imageOn = on.snapshot();
            assertArrayEquals("dark replica snapshot bytes incl. the identity block", imageOn, dark.snapshot());
            assertArrayEquals("ring-less replica snapshot bytes incl. the identity block", imageOn, ringless.snapshot());

            final long epoch = heartbeatEpoch(imageOn);
            assertEquals(G, epoch);
            assertEquals(epoch, heartbeatEpoch(dark.snapshot()));
            assertEquals(epoch, heartbeatEpoch(ringless.snapshot()));
        }
    }
}
