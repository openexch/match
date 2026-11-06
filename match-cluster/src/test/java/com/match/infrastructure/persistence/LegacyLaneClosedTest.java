// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigStateMachine;
import com.match.determinism.RecordingEventSink;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 contract 5.3 (decision A2, L5 moved from S6): at behaviour 2 the legacy templates
 * CreateOrder 1, CancelOrder 2 and UpdateOrder 3 are refused after the header gates, the
 * above-ceiling check and the pre-config guard: counted, no mutation, no egress, no journal.
 * Behaviour 1 keeps serving them.
 */
public class LegacyLaneClosedTest {

    @Test
    public void legacyTemplatesRefusedInBehaviourTwo() {
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(r.send(create(77, 0, 100, 0, 59_000, 1)));
            final byte[] before = r.r.snapshot();
            final long gen = r.engine().getOrderIdGenerator();
            final byte[][] legacy = {createOrder(100, 9001, 1, 59_000, 1), cancelOrder(100, leg),
                    updateOrder(100, leg, 0, 58_000, 1)};
            for (int i = 0; i < legacy.length; i++) {
                assertTrue("template " + (i + 1) + ": no journal", r.send(legacy[i]).isEmpty());
                assertEquals("template " + (i + 1) + ": counted", i + 1, S4Probe.legacyLaneRejects(r.r.demuxer));
            }
            assertArrayEquals("books, omsMap, ledger and generators unchanged", before, r.r.snapshot());
            assertEquals(gen, r.engine().getOrderIdGenerator());
            assertEquals("the durable leg still rests", 1, resting(r.engine()));
            assertEquals(0, r.r.demuxer.decodeRejectCount());
            assertEquals(0, r.r.demuxer.applyErrorCount());
        }
        // Egress: nothing reaches the sink for a refused legacy frame.
        final Engine engine = Engine.deferredUntilConfig();
        final RecordingEventSink sink = new RecordingEventSink();
        engine.setEventPublisher(sink);
        final EngineConfigStateMachine sm = new EngineConfigStateMachine(engine, () -> { }, null);
        final SbeDemuxer demuxer = new SbeDemuxer(engine);
        demuxer.setEngineConfigHandler(sm::onEngineConfig);
        long position = 64;
        for (byte[] f : List.of(engineConfig(1, 2, G, 12, 1_000), create(78, 0, 100, 0, 59_000, 1))) {
            engine.setCurrentLogPosition(position += 128);
            demuxer.dispatchTrusted(new UnsafeBuffer(f), 0, f.length, position);
        }
        final int events = sink.events().size();
        assertEquals("the durable create published its NEW", 1, events);
        for (byte[] f : List.of(createOrder(100, 9001, 1, 59_000, 1), cancelOrder(100, 1), updateOrder(100, 1, 0, 58_000, 1))) {
            engine.setCurrentLogPosition(position += 128);
            demuxer.dispatchTrusted(new UnsafeBuffer(f), 0, f.length, position);
        }
        assertEquals("no egress for the refused legacy frames", events, sink.events().size());
        assertEquals(3, S4Probe.legacyLaneRejects(demuxer));

        // Behaviour 1 serves them (pin).
        try (Replica one = new Replica()) {
            one.apply(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            one.apply(new Entry(createOrder(100, 9001, 0, 59_000, 1), 192L, 1_001L));
            assertEquals("behaviour 1: the legacy create rests", 1, resting(one.h.engine));
            assertEquals(0, S4Probe.legacyLaneRejects(one.demuxer));
        }
    }
    /**
     * RC2 S6 section 8 (precedence pin, passes on the base by construction): at behaviour 2 templates 1/2/3
     * from GATEWAY, FORWARDER and ADMIN all meet the S4 lane refusal (legacyLaneRejects, never
     * unauthorizedRejects); at behaviour 1 all three classes are served. REPLICA is R1 (MeSessionAuthorityTest).
     */
    @Test
    public void laneRefusalIsClassIndependent() {
        for (int c : new int[] {MeSessionAuthorityTest.GATEWAY, MeSessionAuthorityTest.FORWARDER,
                MeSessionAuthorityTest.ADMIN}) {
            try (Run r = fresh(1_000)) {
                final long leg = appliedOrderId(MeSessionAuthorityTest.sendAs(r, MeSessionAuthorityTest.FORWARDER,
                        create(77, 0, 100, 0, 59_000, 1)));
                final byte[] before = r.r.snapshot();
                for (byte[] f : List.of(createOrder(100, 9001, 1, 59_000, 1), cancelOrder(100, leg),
                        updateOrder(100, leg, 0, 58_000, 1))) {
                    assertTrue("class " + c + ": no journal", MeSessionAuthorityTest.sendAs(r, c, f).isEmpty());
                }
                assertEquals("class " + c, 3, S4Probe.legacyLaneRejects(r.r.demuxer));
                assertEquals("class " + c + ": the lane refusal, not the class rows", 0,
                        r.r.demuxer.unauthorizedRejectCount());
                assertArrayEquals(before, r.r.snapshot());
            }
            try (Replica one = new Replica()) {
                MeSessionAuthorityTest.applyAs(one, MeSessionAuthorityTest.ADMIN,
                        new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
                MeSessionAuthorityTest.applyAs(one, c, new Entry(createOrder(100, 9001, 0, 59_000, 1), 192L, 1_001L));
                assertEquals("behaviour 1, class " + c + ": the legacy create rests", 1, resting(one.h.engine));
                assertEquals(0, S4Probe.legacyLaneRejects(one.demuxer));
                assertEquals(0, one.demuxer.unauthorizedRejectCount());
            }
        }
    }
}
