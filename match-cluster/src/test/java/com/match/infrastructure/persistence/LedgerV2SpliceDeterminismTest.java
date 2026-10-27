// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.domain.commands.DurableCommandOutcome;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.application.engine.BehaviourTwoHarness.record4;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 contract 7.4 with the S2 section 7 rule: A + B == A + SNAPSHOT + B for journal frames
 * (journalSeq included), snapshot bytes and decisions, on a behaviour-2 log at C = 8 with UP_TO,
 * ORDER, LEG_LIVE, an all-NOOP frame, a RELEASED duplicate and CAPACITY. Cuts after FRESH, after
 * the first release, inside the LEG_LIVE window; every cut is also taken twice in a row.
 */
public class LedgerV2SpliceDeterminismTest {

    /** Orders 1 and 2 get orderIds 1 and 2 (the engine's first two ids). */
    static List<byte[]> log() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfig(1, 2, G, 12, 8));                 // 0 FRESH, C = 8
        f.add(create(1, 0, 100, 0, 59_000, 1));               // 1 leg 1 rests
        f.add(create(2, 0, 100, 0, 59_001, 1));               // 2 leg 2 rests
        f.add(cancel(2, 1, 100, 2));                          // 3 cancel leg 2
        f.add(releaseFrame(1, order(1)));                     // 4 LEG_LIVE (leg 1 rests)
        f.add(releaseFrame(2, order(2)));                     // 5 first APPLIED release, removed 2
        f.add(create(2, 0, 100, 0, 59_001, 1));               // 6 RELEASED duplicate
        f.add(releaseFrame(3, order(2)));                     // 7 all-NOOP frame
        f.add(releaseFrame(4, upTo(1, 0)));                   // 8 UP_TO: the CREATE id of a resting leg
        f.add(cancel(1, 1, 100, 1));                          // 9 owner cancel (F3)
        f.add(releaseFrame(5, order(1)));                     // 10 ORDER(1) APPLIED
        for (long h = 3; h <= 11; h++) {
            f.add(create(h, 0, 100, 0, 58_000 + h, 1));       // 11..18 recorded, 19 CAPACITY
        }
        f.add(releaseFrame(6, upTo(3, 0)));                   // 20 room
        f.add(create(11, 0, 100, 0, 58_011, 1));              // 21 APPLIED now
        f.add(create(11, 0, 100, 0, 58_011, 1));              // 22 suppressed duplicate
        return f;
    }

    private static List<byte[]> run(Replica r, List<Entry> log, int from, int to) {
        return r.apply(log, from, to);
    }

    @Test
    public void spliceAtEveryNamedCutEqualsTheUnsplitRun() {
        final List<Entry> log = positioned(log());
        final List<byte[]> whole;
        final byte[] finalImage;
        try (Replica a = new Replica()) {
            whole = run(a, log, 0, log.size());
            finalImage = a.snapshot();
            assertEquals(0, a.demuxer.applyErrorCount());
        }
        // Non-vacuous: the log produced what it is meant to cover.
        final List<Integer> releaseResults = new ArrayList<>();
        for (byte[] f : BehaviourTwoHarness.ofTemplate(whole, 4)) {
            final var g = record4(f).releases();
            while (g.hasNext()) {
                releaseResults.add((int) g.next().resultRaw());
            }
        }
        assertTrue("record 4 frames", releaseResults.size() >= 4);
        assertTrue("LEG_LIVE", releaseResults.contains(2));
        final List<Integer> results = new ArrayList<>();
        for (byte[] f : BehaviourTwoHarness.ofTemplate(whole, 3)) {
            results.add(record3(f).result());
        }
        assertTrue("RELEASED", results.contains(DurableCommandOutcome.RELEASED));
        assertTrue("CAPACITY", results.contains(DurableCommandOutcome.CAPACITY));
        BehaviourTwoLog.assertSeqEpochInvariant(whole);
        for (int i = 0; i < whole.size(); i++) {
            assertEquals("dense", i + 1L, BehaviourTwoHarness.journalSeqOf(whole.get(i)));
        }

        for (int cut : new int[]{1, 5, 6}) {
            for (boolean twice : new boolean[]{false, true}) {
                final List<byte[]> spliced = new ArrayList<>();
                try (Replica a = new Replica()) {
                    spliced.addAll(run(a, log, 0, cut));
                    byte[] image = a.snapshot();
                    Replica b = Replica.restoredFrom(image);
                    if (twice) {
                        final byte[] again = b.snapshot();
                        assertArrayEquals("cut " + cut + ": the restored image re-serializes identically", image, again);
                        b.close();
                        b = Replica.restoredFrom(again);
                    }
                    try {
                        spliced.addAll(run(b, log, cut, log.size()));
                        assertArrayEquals("cut " + cut + (twice ? " (twice)" : "") + ": final snapshot", finalImage, b.snapshot());
                    } finally {
                        b.close();
                    }
                }
                assertFramesEqual("cut " + cut + (twice ? " (twice)" : ""), whole, spliced);
            }
        }
    }
}
