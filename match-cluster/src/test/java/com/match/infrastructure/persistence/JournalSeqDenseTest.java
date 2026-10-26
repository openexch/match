// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.engine.EngineConfigStateMachine.Decision;
import com.match.application.publisher.OrderStatusType;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableOrderIntent;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.journalSeqOf;
import static com.match.application.engine.BehaviourTwoHarness.meEpochOf;
import static com.match.application.engine.BehaviourTwoHarness.templateId;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 contract sections 4 and 7 (R-B0-1): under behaviour 2 the ME journal is one dense
 * journalSeq sequence from FRESH = 1, in ring order, every frame stamped with meEpoch G; the
 * suppressed terminals and the egress-only statuses consume nothing. Behaviour 1 stays at
 * journalSeq 0 / meEpoch 0, byte-equal to the frames captured at 3b95c56.
 */
public class JournalSeqDenseTest {

    /** The behaviour-1 reference frames, captured at 3b95c56 (contract W6, review NOTE-6). */
    static final String BEHAVIOUR1_RESOURCE = "/s3/behaviour1-journal.bin";

    private static DurableOrderIntent intent(long idLow, int kind, long userId, long omsOrderId, long oldOrderId,
                                             int side, double price, double qty) {
        return new DurableOrderIntent(17, idLow, userId, omsOrderId, oldOrderId, FixedPoint.fromDouble(price),
                FixedPoint.fromDouble(qty), 0, BehaviourTwoHarness.MARKET, kind, 0, side);
    }

    private static List<Integer> templates(List<byte[]> frames) {
        final List<Integer> out = new ArrayList<>();
        for (byte[] f : frames) {
            out.add(templateId(f));
        }
        return out;
    }

    private static List<Long> seqs(List<byte[]> frames) {
        final List<Long> out = new ArrayList<>();
        for (byte[] f : frames) {
            out.add(journalSeqOf(f));
        }
        return out;
    }

    private static List<Long> range(long from, long toInclusive) {
        final List<Long> out = new ArrayList<>();
        for (long s = from; s <= toInclusive; s++) {
            out.add(s);
        }
        return out;
    }

    // ---- behaviour 2 ----

    @Test
    public void oneCommandIsConsecutiveFromFresh() {
        try (Replica r = new Replica()) {
            final List<byte[]> all = new ArrayList<>();
            final List<byte[]> fresh = r.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
            assertEquals(List.of(5), templates(fresh));
            assertEquals("FRESH is journalSeq 1", List.of(1L), seqs(fresh));
            all.addAll(fresh);

            final List<byte[]> makers = new ArrayList<>();
            makers.addAll(r.apply(new Entry(durableCreate(1, 100, 9001, 1, 59_000, 1), 192L, 1_001L)));
            makers.addAll(r.apply(new Entry(durableCreate(2, 101, 9002, 1, 59_001, 1), 320L, 1_002L)));
            assertEquals("two resting makers: one outcome each, NEW consumes none", List.of(3, 3), templates(makers));
            assertEquals(List.of(2L, 3L), seqs(makers));
            all.addAll(makers);

            final List<byte[]> cross = r.apply(new Entry(durableCreate(3, 200, 9003, 0, 59_001, 2), 448L, 1_003L));
            assertEquals("trade, maker FILLED, trade, maker FILLED, taker FILLED, outcome",
                    List.of(1, 2, 1, 2, 2, 3), templates(cross));
            assertEquals(range(4, 9), seqs(cross));
            all.addAll(cross);

            for (byte[] f : all) {
                assertEquals("every frame carries meEpoch G", G, meEpochOf(f));
            }
            assertSeqEpochInvariant(all);
            assertEquals("nextJournalSeq is one past the last frame", 10L, r.h.engine.meIdentity().nextJournalSeq());
            assertTrue("dense == meIdentity.isBound()", r.h.engine.isJournalSeqDense() && r.h.engine.meIdentity().isBound());
        }
    }

    /** A DIRECT book with one order per level, so a replacement can be refused after its cancel. */
    private static EngineConfigState oneOrderPerLevel() {
        return EngineConfigState.of(1, EngineConfigState.IMPL_DIRECT, 0, 100, 1,
                new EngineConfigState.MarketDef[]{new EngineConfigState.MarketDef(BehaviourTwoHarness.MARKET,
                        "BTC-USD", FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(51_000.0),
                        FixedPoint.fromDouble(1.0))},
                2, 50_000, G, 12);
    }

    @Test
    public void suppressedTerminalsConsumeNone() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(Decision.FRESH, h.apply(oneOrderPerLevel(), 64L));
            assertEquals(List.of(1L), seqs(h.drain()));
            final Engine e = h.engine;

            // NEW: the outcome only.
            e.setCurrentLogPosition(192L);
            final var a = e.acceptDurable(intent(1, 0, 100, 9001, 0, 0, 50_500, 1), 1_001L);
            List<byte[]> f = h.drain();
            assertEquals(List.of(3), templates(f));
            assertEquals(List.of(2L), seqs(f));

            // Accepted replace: the old-leg CANCELLED is suppressed, the new leg rests NEW.
            e.setCurrentLogPosition(320L);
            final var replaced = e.acceptDurable(intent(2, 2, 100, 9001, a.orderId(), 0, 50_600, 1), 1_002L);
            assertTrue(replaced.oldCancelled());
            f = h.drain();
            assertEquals("old-leg CANCELLED not journaled", List.of(3), templates(f));
            assertEquals(List.of(3L), seqs(f));

            // Pre-cancel amend rejection (price out of range): suppressed, the outcome only.
            e.setCurrentLogPosition(448L);
            final var rejectedAmend = e.acceptDurable(intent(3, 2, 100, 9001, replaced.orderId(), 0, 60_000, 1), 1_003L);
            assertEquals(OrderStatusType.REJECTED, rejectedAmend.status());
            assertFalse(rejectedAmend.oldCancelled());
            f = h.drain();
            assertEquals("pre-cancel amend rejection not journaled", List.of(3), templates(f));
            assertEquals(List.of(4L), seqs(f));

            // A taker that fills the maker and rests PARTIALLY_FILLED: trade, maker FILLED, outcome.
            e.setCurrentLogPosition(576L);
            final var partial = e.acceptDurable(intent(4, 0, 200, 9002, 0, 1, 50_600, 2), 1_004L);
            assertEquals(OrderStatusType.PARTIALLY_FILLED, partial.status());
            f = h.drain();
            assertEquals("PARTIALLY_FILLED consumes none", List.of(1, 2, 3), templates(f));
            assertEquals(List.of(5L, 6L, 7L), seqs(f));

            // A replacement refused after its cancel (LEVEL_FULL) is a real terminal: it consumes one.
            e.setCurrentLogPosition(704L);
            final var d = e.acceptDurable(intent(5, 0, 100, 9003, 0, 0, 50_100, 1), 1_005L);
            e.setCurrentLogPosition(832L);
            e.acceptDurable(intent(6, 0, 101, 9004, 0, 0, 50_200, 1), 1_006L);
            assertEquals(List.of(8L, 9L), seqs(h.drain()));
            e.setCurrentLogPosition(960L);
            final var refused = e.acceptDurable(intent(7, 2, 100, 9003, d.orderId(), 0, 50_200, 1), 1_007L);
            assertTrue("the old leg was cancelled first", refused.oldCancelled());
            assertEquals(OrderStatusType.REJECTED, refused.status());
            f = h.drain();
            assertEquals("the new-leg REJECTED terminal, then the outcome", List.of(2, 3), templates(f));
            assertEquals(List.of(10L, 11L), seqs(f));
            final TerminalDecoder t = new TerminalDecoder().wrapAndApplyHeader(new UnsafeBuffer(f.get(0)), 0,
                    new MessageHeaderDecoder());
            assertEquals(OrderStatusType.REJECTED, t.status().value());
            assertEquals(12L, e.meIdentity().nextJournalSeq());
        }
    }

    // ---- behaviour 1 ----

    /**
     * The behaviour-1 log: durable creates (rest, cross), an equal retry (re-journaled in
     * behaviour 1), a conflict, a durable replace, a legacy create crossing it, a durable taker
     * filling the maker, legacy cancels (unknown leg ack, resting leg), a durable cancel of a
     * filled leg (UNKNOWN_LEG) and a pre-cancel amend rejection.
     */
    static List<byte[]> behaviourOneOrders() {
        final List<byte[]> f = new ArrayList<>();
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 2));                       // order 1 rests
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));                       // order 2 fills against 1
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));                       // equal retry
        f.add(durableCreate(2, 200, 9002, 1, 59_500, 1));                       // conflict
        f.add(durable(3, 2, 100, 9001, 1, 0, 58_000, 1, 0, 0));                 // replace 1 -> order 3
        f.add(createOrder(300, 9003, 1, 58_000, 0.5));                          // order 4, partial vs 3
        f.add(durableCreate(4, 400, 9004, 1, 58_000, 0.5));                     // order 5 fills 3
        f.add(cancelOrder(100, 99));                                            // unknown: CANCELLED ack
        f.add(durable(5, 1, 200, 9002, 2, 1, 0, 0, 0, 0));                      // cancel of filled 2: UNKNOWN_LEG
        f.add(durableCreate(6, 100, 9006, 0, 57_000, 1));                       // order 6 rests
        f.add(durable(7, 2, 100, 9006, 6, 0, 40_000, 1, 0, 0));                 // amend refused before cancel
        f.add(cancelOrder(100, 6));                                             // CANCELLED
        return f;
    }

    /** Frames of the behaviour-1 log on a legacy engine, then on a FRESH_LEGACY config engine. */
    static List<byte[]> behaviourOneFrames() {
        final List<byte[]> out = new ArrayList<>();
        try (Replica legacy = new Replica(new Engine("array"))) {
            final List<Entry> log = positioned(behaviourOneOrders());
            out.addAll(legacy.apply(log, 0, log.size()));
            assertEquals(0, legacy.demuxer.applyErrorCount());
            assertFalse("behaviour 1: not dense", legacy.h.engine.isJournalSeqDense() || legacy.h.engine.meIdentity().isBound());
        }
        try (Replica freshLegacy = new Replica()) {
            final List<byte[]> f = new ArrayList<>();
            f.add(engineConfig(1, 1, 0, 0, 100_000));
            f.addAll(behaviourOneOrders());
            final List<Entry> log = positioned(f);
            out.addAll(freshLegacy.apply(log, 0, log.size()));
            assertEquals(List.of(Decision.FRESH_LEGACY), freshLegacy.h.decisions);
            assertFalse("FRESH_LEGACY: not dense",
                    freshLegacy.h.engine.isJournalSeqDense() || freshLegacy.h.engine.meIdentity().isBound());
            assertEquals(0, freshLegacy.demuxer.applyErrorCount());
        }
        return out;
    }

    static byte[] encode(List<byte[]> frames) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(f.length).array());
            out.writeBytes(f);
        }
        return out.toByteArray();
    }

    static List<byte[]> decode(byte[] bytes) {
        final List<byte[]> out = new ArrayList<>();
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            final byte[] f = new byte[b.getInt()];
            b.get(f);
            out.add(f);
        }
        return out;
    }

    @Test
    public void behaviourOneStaysAtZero() throws IOException {
        final List<byte[]> frames = behaviourOneFrames();
        final String capture = System.getProperty("s3.capture.behaviour1");
        if (capture != null) {
            Files.createDirectories(Path.of(capture).getParent());
            Files.write(Path.of(capture), encode(frames));
        }
        assertTrue("non-vacuous: trades, terminals and outcomes", frames.size() >= 20);
        assertTrue(templates(frames).contains(1));
        assertTrue(templates(frames).contains(2));
        assertTrue(templates(frames).contains(3));
        for (byte[] f : frames) {
            assertEquals("behaviour 1: journalSeq 0", 0L, journalSeqOf(f));
            assertEquals("behaviour 1: meEpoch 0", 0L, meEpochOf(f));
        }
        final byte[] reference;
        try (InputStream in = JournalSeqDenseTest.class.getResourceAsStream(BEHAVIOUR1_RESOURCE)) {
            assertNotNull("missing committed reference " + BEHAVIOUR1_RESOURCE, in);
            reference = in.readAllBytes();
        }
        assertFramesEqual("behaviour 1 vs the 3b95c56 reference", decode(reference), frames);
    }
}
