// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.EngineConfigStateMachine.Decision;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.ConfigDecision;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract section 7: snapshot splice equality A + B == A + SNAPSHOT + B on a behaviour-2
 * log. The restored replica must emit the same journal frames byte for byte (meEpoch G, the
 * second BUMP at its dense journalSeq, the INVALID record), end with the same snapshot bytes, the same
 * recorded config (the four included), the same {@code MeIdentity} and the same decisions.
 */
public class EngineConfigSpliceDeterminismTest {

    /** A: FRESH at 2, orders with trades, a BUMP. B: the continuation. */
    static List<Entry> log() {
        final List<byte[]> f = new ArrayList<>();
        // ---- A ----
        f.add(engineConfig(1, 2, G, 12, 50_000));                          // 0 FRESH
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 2));                    // 1 bid rests
        f.add(durableCreate(2, 200, 9002, 1, 59_000, 1));                    // 2 trade
        f.add(durableCreate(3, 300, 9003, 1, 59_000, 1));                    // 3 trade, bid filled (last trade of A)
        f.add(engineConfig(2, 2, G, 12, 50_000));                          // 4 BUMP
        // ---- B ----
        f.add(durableCreate(4, 100, 9004, 0, 58_000, 1));                    // 5 bid rests
        f.add(engineConfig(2, 2, G, 12, 50_000));                          // 6 DUPLICATE
        f.add(engineConfig(1, 2, G, 12, 50_000));                          // 7 STALE
        f.add(withHeaderVersion(durableCreate(5, 500, 9005, 1, 58_000, 1), 13)); // 8 above ceiling -> INVALID
        f.add(engineConfig(3, 2, G, 12, 50_000));                          // 9 second BUMP
        f.add(durableCreate(6, 400, 9006, 1, 58_000, 1));                    // 10 v12 CREATE, trade
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 2));                    // 11 retry of entry 1 (ledger duplicate)
        return positioned(f);
    }

    private static final int CUT_AFTER_FRESH = 1;
    private static final int CUT_AFTER_LAST_TRADE_OF_A = 4;
    private static final int CUT_AFTER_FIRST_BUMP = 5;
    private static final int CUT_INSIDE_B = 9;

    /** The straight run, per entry. */
    private static final class Straight {
        final List<List<byte[]>> framesPerEntry = new ArrayList<>();
        final List<Integer> decisionsBefore = new ArrayList<>();
        final byte[] finalSnapshot;
        final String recorded;
        final String identity;
        final List<Decision> decisions;

        Straight(List<Entry> log) {
            try (Replica r = new Replica()) {
                for (Entry e : log) {
                    decisionsBefore.add(r.h.decisions.size());
                    framesPerEntry.add(r.apply(e));
                }
                finalSnapshot = r.snapshot();
                recorded = r.h.engine.getEngineConfig().describe();
                identity = r.h.engine.meIdentity().toString();
                decisions = new ArrayList<>(r.h.decisions);
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

    private static void assertFramesEqual(String what, List<byte[]> expected, List<byte[]> actual) {
        assertEquals(what + ": frame count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertArrayEquals(what + ": frame " + i, expected.get(i), actual.get(i));
        }
    }

    /** Run [0, cuts[0]), then for each cut: snapshot, restore into a fresh deferred engine, continue. */
    private static void assertSpliceEqual(List<Entry> log, Straight straight, int... cuts) {
        Replica current = new Replica();
        try {
            current.apply(log, 0, cuts[0]);
            final List<byte[]> frames = new ArrayList<>();
            final List<Decision> decisionsAfterFirstCut = new ArrayList<>();
            for (int c = 0; c < cuts.length; c++) {
                final byte[] image = current.snapshot();
                current.close();
                current = Replica.restoredFrom(image);
                final int to = c + 1 < cuts.length ? cuts[c + 1] : log.size();
                frames.addAll(current.apply(log, cuts[c], to));
                decisionsAfterFirstCut.addAll(current.h.decisions);
            }
            final String at = "cut " + java.util.Arrays.toString(cuts);
            assertFramesEqual(at, straight.framesFrom(cuts[0]), frames);
            assertArrayEquals(at + ": final snapshot bytes", straight.finalSnapshot, current.snapshot());
            assertEquals(at + ": recorded config with the four", straight.recorded,
                    current.h.engine.getEngineConfig().describe());
            assertEquals(at + ": MeIdentity", straight.identity, current.h.engine.meIdentity().toString());
            assertEquals(at + ": decisions after the cut",
                    straight.decisions.subList(straight.decisionsBefore.get(cuts[0]), straight.decisions.size()),
                    decisionsAfterFirstCut);
            assertEquals(0, current.h.failFast.get());
        } finally {
            current.close();
        }
    }

    @Test
    public void theStraightRunIsNotVacuous() {
        final Straight s = new Straight(log());
        assertEquals(java.util.List.of(Decision.FRESH, Decision.BUMP, Decision.DUPLICATE, Decision.STALE,
                Decision.BUMP), s.decisions);
        final List<byte[]> b = s.framesFrom(CUT_AFTER_FIRST_BUMP);
        for (byte[] f : s.framesFrom(0)) {
            assertEquals("meEpoch G on every record", G, BehaviourTwoHarness.meEpochOf(f));
        }
        final List<byte[]> r5 = BehaviourTwoHarness.ofTemplate(b, 5);
        assertEquals(1, r5.size());
        // RC2 S3: dense seqs, so the second BUMP is 1 + every frame journaled before it.
        int framesBeforeSecondBump = 0;
        for (int i = 0; i < 9; i++) {
            framesBeforeSecondBump += s.framesPerEntry.get(i).size();
        }
        assertEquals(1L + framesBeforeSecondBump, BehaviourTwoHarness.record5(r5.get(0)).journalSeq());
        assertEquals(ConfigDecision.BUMP, BehaviourTwoHarness.record5(r5.get(0)).decision());
        int invalid = 0;
        for (byte[] f : BehaviourTwoHarness.ofTemplate(b, 3)) {
            if (BehaviourTwoHarness.record3(f).result() == DurableCommandOutcome.INVALID) {
                invalid++;
            }
        }
        assertEquals("one INVALID record in B", 1, invalid);
        assertTrue("B has a trade", !BehaviourTwoHarness.ofTemplate(b, 1).isEmpty());
        assertTrue("A has trades", BehaviourTwoHarness.ofTemplate(s.framesFrom(0), 1).size() >= 3);
    }

    @Test
    public void cutAfterFresh() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_FRESH);
    }

    @Test
    public void cutAfterTheLastTradeOfA() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_LAST_TRADE_OF_A);
    }

    @Test
    public void cutAfterTheFirstBump() {
        final List<Entry> log = log();
        assertSpliceEqual(log, new Straight(log), CUT_AFTER_FIRST_BUMP);
    }

    @Test
    public void cutTakenTwice() {
        final List<Entry> log = log();
        final Straight straight = new Straight(log);
        assertSpliceEqual(log, straight, CUT_AFTER_FIRST_BUMP, CUT_INSIDE_B);
        assertSpliceEqual(log, straight, CUT_AFTER_FRESH, CUT_AFTER_LAST_TRADE_OF_A);
    }
}
