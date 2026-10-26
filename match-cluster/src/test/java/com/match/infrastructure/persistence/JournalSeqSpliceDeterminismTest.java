// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.journalSeqOf;
import static com.match.application.engine.BehaviourTwoHarness.ofTemplate;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 contract section 8 (R-B0-3): A + B == A + SNAPSHOT + B for the journal frames (byte for
 * byte, journalSeq included), the snapshot bytes, the tradeId generator and nextJournalSeq. The
 * restored replica's first frame of B carries exactly the restored nextJournalSeq; a full replay
 * from position 0 gives the same frames.
 */
public class JournalSeqSpliceDeterminismTest {

    static List<Entry> log() {
        final List<byte[]> f = new ArrayList<>();
        // ---- A ----
        f.add(engineConfig(1, 2, G, 12, 50_000));                       // 0 FRESH (seq 1)
        f.add(durableCreate(1, 100, 9001, 1, 59_000, 1));               // 1 ask rests (order 1)
        f.add(durableCreate(2, 101, 9002, 1, 59_001, 1));               // 2 ask rests (order 2)
        f.add(durableCreate(3, 200, 9003, 0, 59_001, 2));               // 3 multi-trade command, ends A
        // ---- B ----
        f.add(durableCreate(3, 200, 9003, 0, 59_001, 2));               // 4 equal duplicate: suppressed
        f.add(durableCreate(3, 200, 9003, 0, 59_002, 2));               // 5 CONFLICT
        f.add(durableCreate(4, 300, 9004, 0, 58_000, 1));               // 6 bid rests (order 4)
        f.add(durable(5, 2, 300, 9004, 4, 0, 58_500, 1, 0, 0));         // 7 replace 4 -> order 5
        f.add(createOrder(400, 9010, 1, 58_500, 1));                    // 8 legacy taker: trade, maker 5 FILLED
        f.add(cancelOrder(100, 99));                                    // 9 legacy cancel ack
        f.add(durableCreate(1, 100, 9001, 1, 59_000, 1));               // 10 retry of entry 1: suppressed
        f.add(engineConfig(2, 2, G, 12, 50_000));                       // 11 BUMP
        f.add(durableCreate(6, 500, 9006, 0, 57_000, 1));               // 12 bid rests
        return positioned(f);
    }

    static final int CUT_AFTER_FRESH = 1;
    static final int CUT_AFTER_A = 4;
    static final int CUT_AFTER_SUPPRESSED_DUPLICATE = 5;
    static final int CUT_AFTER_CONFLICT = 6;
    static final int CUT_INSIDE_B = 9;

    private static final class Straight {
        final List<List<byte[]>> framesPerEntry = new ArrayList<>();
        final byte[] finalSnapshot;
        final long tradeIdGenerator;
        final long nextJournalSeq;

        Straight(List<Entry> log) {
            try (Replica r = new Replica()) {
                for (Entry e : log) {
                    framesPerEntry.add(r.apply(e));
                }
                finalSnapshot = r.snapshot();
                tradeIdGenerator = r.h.engine.getTradeIdGenerator();
                nextJournalSeq = r.h.engine.meIdentity().nextJournalSeq();
                assertEquals(0, r.h.failFast.get());
            }
        }

        List<byte[]> framesFrom(int from) {
            final List<byte[]> out = new ArrayList<>();
            for (int i = from; i < framesPerEntry.size(); i++) {
                out.addAll(framesPerEntry.get(i));
            }
            return out;
        }
    }

    /** nextJournalSeq as the identity block of {@code image} carries it. */
    private static long nextJournalSeqIn(byte[] image) {
        final Engine e = Engine.deferredUntilConfig();
        try {
            SnapshotCodec.deserialize(new UnsafeBuffer(image), 0, image.length, e);
            return e.meIdentity().nextJournalSeq();
        } finally {
            e.close();
        }
    }

    private static void assertSpliceEqual(List<Entry> log, Straight straight, int... cuts) {
        final String at = "cut " + Arrays.toString(cuts);
        Replica current = new Replica();
        try {
            current.apply(log, 0, cuts[0]);
            final List<byte[]> frames = new ArrayList<>();
            for (int c = 0; c < cuts.length; c++) {
                final byte[] image = current.snapshot();
                current.close();
                current = Replica.restoredFrom(image);
                final long restoredNext = nextJournalSeqIn(image);
                assertEquals(at + ": restore binds the block's nextJournalSeq", restoredNext,
                        current.h.engine.meIdentity().nextJournalSeq());
                assertTrue(at + ": dense == meIdentity.isBound() after the restore",
                        current.h.engine.isJournalSeqDense() && current.h.engine.meIdentity().isBound());
                final int to = c + 1 < cuts.length ? cuts[c + 1] : log.size();
                final List<byte[]> b = current.apply(log, cuts[c], to);
                if (!b.isEmpty()) {
                    assertEquals(at + ": the first frame after the restore carries the restored nextJournalSeq",
                            restoredNext, journalSeqOf(b.get(0)));
                }
                frames.addAll(b);
            }
            assertFramesEqual(at, straight.framesFrom(cuts[0]), frames);
            final byte[] finalImage = current.snapshot();
            assertArrayEquals(at + ": final snapshot bytes", straight.finalSnapshot, finalImage);
            assertEquals(at + ": tradeId generator", straight.tradeIdGenerator, current.h.engine.getTradeIdGenerator());
            assertEquals(at + ": nextJournalSeq in the identity block", straight.nextJournalSeq,
                    nextJournalSeqIn(finalImage));
            assertEquals(0, current.h.failFast.get());
        } finally {
            current.close();
        }
    }

    @Test
    public void theStraightRunIsDenseAndNotVacuous() {
        final Straight s = new Straight(log());
        final List<byte[]> all = s.framesFrom(0);
        for (int i = 0; i < all.size(); i++) {
            assertEquals("dense from FRESH = 1", i + 1L, journalSeqOf(all.get(i)));
            assertEquals(G, BehaviourTwoHarness.meEpochOf(all.get(i)));
        }
        assertEquals(all.size() + 1L, s.nextJournalSeq);
        assertEquals("the multi-trade command", 2, ofTemplate(s.framesPerEntry.get(3), 1).size());
        assertTrue("entry 4 is suppressed", s.framesPerEntry.get(4).isEmpty());
        assertTrue("entry 10 is suppressed", s.framesPerEntry.get(10).isEmpty());
        assertEquals("entry 5 is one CONFLICT record", 1, s.framesPerEntry.get(5).size());
        assertEquals(2, ofTemplate(all, 5).size());
    }

    @Test
    public void cutAfterFresh() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_FRESH);
    }

    @Test
    public void cutAfterTheMultiTradeCommandThatEndsA() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_A);
    }

    @Test
    public void cutAfterASuppressedDuplicate() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_SUPPRESSED_DUPLICATE);
    }

    @Test
    public void cutAfterAConflict() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_CONFLICT);
    }

    @Test
    public void cutTakenTwice() {
        final List<Entry> log = log();
        final Straight straight = new Straight(log);
        assertSpliceEqual(log, straight, CUT_AFTER_A, CUT_INSIDE_B);
        assertSpliceEqual(log, straight, CUT_AFTER_FRESH, CUT_AFTER_CONFLICT);
    }

    @Test
    public void fullReplayFromPositionZeroGivesTheSameFrames() {
        final List<Entry> log = log();
        final Straight straight = new Straight(log);
        try (Replica replayed = new Replica()) {
            final List<byte[]> frames = replayed.apply(log, 0, log.size());
            assertFramesEqual("full replay", straight.framesFrom(0), frames);
            assertArrayEquals(straight.finalSnapshot, replayed.snapshot());
        }
    }
}
