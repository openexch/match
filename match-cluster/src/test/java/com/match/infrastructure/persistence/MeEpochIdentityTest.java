// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.infrastructure.generated.ClusterHeartbeatDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.G2;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract section 5 (R-B0-4, IN-08 producer identity): a wiped ME has no identity until
 * a new FRESH gives it a new epoch; a restored ME keeps its epoch on every path (snapshot
 * restore, full replay from position 0), with the same nextJournalSeq as a never-stopped member.
 * The keep-warm heartbeat carries the recorded epoch.
 */
public class MeEpochIdentityTest {

    private static List<Entry> g1Log() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfig(1, 2, G, 12, 50_000));
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 1));
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));
        f.add(engineConfig(2, 2, G, 12, 50_000));
        return positioned(f);
    }

    private static long heartbeatEpoch(S2Services.Service svc) {
        final UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
        svc.service.encodeKeepWarm(buf, 1, 123L);
        return new ClusterHeartbeatDecoder().wrapAndApplyHeader(buf, 0, new MessageHeaderDecoder()).meEpoch();
    }

    @Test
    public void wipedMeIsANewEpochRestoredMeKeepsIt() {
        final List<Entry> log = g1Log();
        final byte[] image;
        final List<byte[]> originalFrames;
        final long nextSeq;
        try (Replica original = new Replica()) {
            originalFrames = original.apply(log, 0, log.size());
            assertEquals(G, original.h.engine.meIdentity().meEpoch());
            nextSeq = original.h.engine.meIdentity().nextJournalSeq();
            assertEquals("FRESH 1, five records, BUMP 7", 8L, nextSeq);
            image = original.snapshot();
        }

        // Wiped: no snapshot, no log. No identity, meEpoch 0 on every record, until a new FRESH.
        try (Replica wiped = new Replica()) {
            assertFalse(wiped.h.engine.meIdentity().isBound());
            final List<byte[]> preConfig = wiped.apply(new Entry(durableCreate(9, 100, 9009, 0, 59_000, 1), 64L, 1_000L));
            assertTrue("no epoch, nothing journaled before any FRESH", preConfig.isEmpty());
            assertEquals(1, wiped.demuxer.preConfigOrderRejectCount());
            assertFalse(wiped.h.engine.meIdentity().isBound());

            final List<byte[]> fresh = wiped.apply(new Entry(engineConfig(1, 2, G2, 12, 50_000), 192L, 1_001L));
            assertEquals(1, fresh.size());
            assertEquals("a new FRESH gives the new epoch", G2, BehaviourTwoHarness.record5(fresh.get(0)).meEpoch());
            assertEquals("journalSeq restarts at 1", 1L, BehaviourTwoHarness.record5(fresh.get(0)).journalSeq());
            assertEquals(G2, wiped.h.engine.meIdentity().meEpoch());
        }

        // Snapshot restore keeps G1 and the same nextJournalSeq.
        try (Replica restored = Replica.restoredFrom(image)) {
            assertEquals(G, restored.h.engine.meIdentity().meEpoch());
            assertEquals(nextSeq, restored.h.engine.meIdentity().nextJournalSeq());
        }

        // Full replay of the G1 log from position 0 re-applies the same FRESH.
        try (Replica replayed = new Replica()) {
            final List<byte[]> frames = replayed.apply(log, 0, log.size());
            assertEquals(G, replayed.h.engine.meIdentity().meEpoch());
            assertEquals(nextSeq, replayed.h.engine.meIdentity().nextJournalSeq());
            assertEquals(originalFrames.size(), frames.size());
            for (int i = 0; i < frames.size(); i++) {
                assertArrayEquals(originalFrames.get(i), frames.get(i));
            }
        }

        // The heartbeat carries the recorded epoch: 0 on a wiped node, G1 once restored.
        final S2Services.Service svc = new S2Services.Service(true);
        assertEquals(0L, heartbeatEpoch(svc));
        svc.restore(image);
        assertEquals(0, svc.failFast.get());
        assertEquals(G, heartbeatEpoch(svc));
    }
}
