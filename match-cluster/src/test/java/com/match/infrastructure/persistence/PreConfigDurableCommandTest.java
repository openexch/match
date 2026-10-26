// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigStateMachine;
import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.MatchEventPublisher;
import com.match.application.publisher.PublishEvent;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.journal.SettlementJournal;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 section 13 addition: a DurableOrderCommand (template 9) logged before any EngineConfig on
 * a config-mode cluster. Wired as production is: the engine has NO event publisher until the
 * FRESH's EnginesCreatedListener brings publishing up. Before the rule the command reached
 * {@code Engine.acceptDurable} and failed on the missing publisher, an apply failure on every
 * replica at the same position (and again on replay). Now it is the fresh-cluster guard's
 * counted drop, like CreateOrder/CancelOrder/UpdateOrder: nothing applied, nothing journaled.
 */
public class PreConfigDurableCommandTest {

    /** A production-shaped config-mode replica (publisher attached only by the FRESH listener). */
    private static final class Node implements AutoCloseable {
        final Engine engine = Engine.deferredUntilConfig();
        final MatchEventPublisher publisher = new MatchEventPublisher();
        final SettlementJournal journal = new SettlementJournal(1 << 20);
        final AtomicInteger failFast = new AtomicInteger();
        final SbeDemuxer demuxer = new SbeDemuxer(engine);
        final EngineConfigStateMachine sm;

        Node() {
            journal.bindIdentity(engine.meIdentity());
            publisher.setSettlementJournal(journal);
            sm = new EngineConfigStateMachine(engine, failFast::incrementAndGet, config -> {
                for (var m : config.markets) {
                    publisher.initMarket(m.marketId, new MarketEventHandler() {
                        public int getMarketId() { return m.marketId; }
                        public void onEvent(PublishEvent e, long seq, boolean end) { }
                    });
                }
                engine.setEventPublisher(publisher);
                publisher.start();
            }, publisher::publishEngineConfig);
            demuxer.setEngineConfigHandler(sm::onEngineConfig);
            demuxer.setPreConfigOrderRejectHandler((m, u, o, b, t) -> { });
        }

        List<byte[]> run(List<Entry> log) {
            final List<byte[]> frames = new ArrayList<>();
            for (Entry e : log) {
                engine.setCurrentLogPosition(e.position);
                demuxer.dispatch(new UnsafeBuffer(e.frame), 0, e.frame.length, e.timestamp);
                journal.ringBuffer().read((t, b, i, n) -> {
                    final byte[] v = new byte[n];
                    b.getBytes(i, v);
                    frames.add(v);
                }, Integer.MAX_VALUE);
            }
            return frames;
        }

        byte[] snapshot() {
            final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(engine, 0L, buf);
            return Arrays.copyOf(buf.byteArray(), len);
        }

        @Override
        public void close() {
            publisher.shutdown();
            engine.close();
        }
    }

    private static List<Entry> log() {
        final List<byte[]> f = new ArrayList<>();
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 1));            // before any EngineConfig
        f.add(engineConfig(1, 2, G, 12, 50_000));                   // FRESH at 2
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 1));            // the same command id, now served
        return positioned(f);
    }

    @Test
    public void templateNineBeforeEngineConfigIsACountedDropOnEveryReplicaAndOnReplay() {
        final List<Entry> log = log();
        try (Node a = new Node(); Node b = new Node(); Node replay = new Node()) {
            final List<byte[]> fa = a.run(log);
            final List<byte[]> fb = b.run(log);
            final List<byte[]> fr = replay.run(log);

            for (Node n : new Node[]{a, b, replay}) {
                assertEquals("never an apply failure", 0, n.demuxer.applyErrorCount());
                assertEquals("not malformed input", 0, n.demuxer.decodeRejectCount());
                assertEquals("counted by the fresh-cluster guard", 1, n.demuxer.preConfigOrderRejectCount());
                assertEquals(0, n.failFast.get());
                assertEquals("only the served command is in the ledger", 1, n.engine.commandLedger().size());
            }
            // Journal: record 5 for the FRESH and record 3 APPLIED for the served command; nothing
            // for the pre-config frame (no epoch existed).
            assertEquals(2, fa.size());
            assertEquals(com.match.infrastructure.mejournal.generated.EngineConfigDecoder.TEMPLATE_ID,
                    com.match.application.engine.BehaviourTwoHarness.templateId(fa.get(0)));
            final var served = com.match.application.engine.BehaviourTwoHarness.record3(fa.get(1));
            assertEquals(DurableCommandOutcome.APPLIED, served.result());
            assertEquals(log.get(2).position, served.appliedPosition());
            assertEquals(G, served.meEpoch());

            for (List<byte[]> other : List.of(fb, fr)) {
                assertEquals(fa.size(), other.size());
                for (int i = 0; i < fa.size(); i++) {
                    assertArrayEquals("journal frame " + i, fa.get(i), other.get(i));
                }
            }
            assertArrayEquals(a.snapshot(), b.snapshot());
            assertArrayEquals(a.snapshot(), replay.snapshot());
        }
    }
}
