// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic guardrails for {@link JournalRetention}'s safety contract. The position/watermark math is
 * factored into static methods so it is provable without a live archive:
 * <ul>
 *   <li>candidate = highest checkpoint position strictly below the watermark;</li>
 *   <li>candidate selection is confined to the ACTIVE incarnation (positions reset across incarnations);</li>
 *   <li>the purge target always keeps at least two whole segments below the recording head;</li>
 *   <li>the non-positive-watermark and no-qualifying-checkpoint cases purge nothing.</li>
 * </ul>
 * A real-archive proof that {@code purgeSegments} actually removes files and replay from the new start
 * still works lives in {@code JournalRetentionIntegrationTest}.
 */
public class JournalRetentionTest {

    // Readable, power-of-two segment geometry for the math (production uses 64MB/64MB).
    private static final int TERM = 64;
    private static final int SEG = 64;

    private static List<long[]> cp(final long... egressThenPositionPairs) {
        final List<long[]> out = new ArrayList<>();
        for (int i = 0; i < egressThenPositionPairs.length; i += 2) {
            out.add(new long[] {egressThenPositionPairs[i], egressThenPositionPairs[i + 1]});
        }
        return out;
    }

    // ---- candidate selection: highest position strictly below the watermark ----

    @Test
    public void selectCandidate_picksHighestPositionBelowWatermark() {
        // egressSeq, position pairs (one incarnation, monotonic in both).
        final List<long[]> checkpoints = cp(
                100, 0,
                200, 64,
                300, 128,
                400, 192);
        // watermark 350 -> qualifying egress {100,200,300}, highest position among them = 128.
        assertEquals(128, JournalRetention.selectCandidatePosition(checkpoints, 0, 350));
    }

    @Test
    public void selectCandidate_watermarkIsStrict_excludesEqualEgress() {
        final List<long[]> checkpoints = cp(100, 0, 200, 64, 300, 128);
        // watermark == 200 must NOT include the egress==200 checkpoint (strict <).
        assertEquals(0, JournalRetention.selectCandidatePosition(checkpoints, 0, 200));
    }

    @Test
    public void selectCandidate_noneBelowWatermark_returnsMinusOne() {
        final List<long[]> checkpoints = cp(500, 0, 600, 64);
        assertEquals(-1, JournalRetention.selectCandidatePosition(checkpoints, 0, 100));
    }

    @Test
    public void selectCandidate_honoursFromIndex_ignoresOlderIncarnation() {
        // Two incarnations in one file; positions reset at index 2. The active-suffix start is 2, so a
        // low-egress OLD-incarnation checkpoint at a high position (900,192) must be ignored.
        final List<long[]> checkpoints = cp(
                100, 64,
                900, 192,   // old incarnation, high position but old egress
                1000, 0,    // new incarnation begins (position reset)
                1100, 64,
                1200, 128);
        // From index 2 with watermark 1150 -> qualifying {1000@0, 1100@64}; highest position = 64.
        assertEquals(64, JournalRetention.selectCandidatePosition(checkpoints, 2, 1150));
    }

    // ---- active-incarnation suffix detection (positions reset across incarnations) ----

    @Test
    public void activeSuffixStartIndex_singleIncarnation_isZero() {
        final List<long[]> checkpoints = cp(100, 0, 200, 64, 300, 128);
        assertEquals(0, JournalRetention.activeSuffixStartIndex(checkpoints));
    }

    @Test
    public void activeSuffixStartIndex_findsLastResetBoundary() {
        // Position drops at index 2 (0<128) and again at index 4 (0<64): active run starts at 4.
        final List<long[]> checkpoints = cp(
                100, 0,
                200, 128,
                300, 0,
                400, 64,
                500, 0,
                600, 64);
        assertEquals(4, JournalRetention.activeSuffixStartIndex(checkpoints));
    }

    @Test
    public void activeSuffixStartIndex_emptyAndSingle() {
        assertEquals(0, JournalRetention.activeSuffixStartIndex(new ArrayList<>()));
        assertEquals(0, JournalRetention.activeSuffixStartIndex(cp(42, 0)));
    }

    // ---- purge-target math: keep >= 2 whole segments below the recording head ----

    @Test
    public void computeTarget_purgesLowSegmentsKeepingTwoWhole() {
        // recordingPosition 200 -> active segment base 192; candidate 100 -> segment base 64.
        // keepTwoWholeSegments = 192 - 2*64 = 64. target = min(64, 64) = 64: purge only segment [0,64).
        final long target = JournalRetention.computePurgeTarget(0, 200, 100, TERM, SEG);
        assertEquals(64, target);
        assertTrue("target must advance past the start to purge", target > 0);
    }

    @Test
    public void computeTarget_backsOffToKeepTwoWholeSegments() {
        // A high candidate (190 -> segment base 128) would leave < 2 whole segments below the head;
        // it must be backed off to keepTwoWholeSegments = 64.
        final long target = JournalRetention.computePurgeTarget(0, 200, 190, TERM, SEG);
        assertEquals(64, target);
    }

    @Test
    public void computeTarget_tooFewSegments_isNoOp() {
        // recordingPosition 100 -> active base 64; keepTwo = 64 - 128 = -64. Nothing purgeable.
        final long target = JournalRetention.computePurgeTarget(0, 100, 50, TERM, SEG);
        assertTrue("target <= startPosition signals no-op", target <= 0);
    }

    @Test
    public void computeTarget_candidateBeyondRecordingHead_isNoOp() {
        // Candidate past the recording head cannot belong to the active recording -> refuse.
        final long target = JournalRetention.computePurgeTarget(0, 200, 300, TERM, SEG);
        assertEquals(0, target); // == startPosition -> caller no-ops
    }

    @Test
    public void computeTarget_noCandidate_isNoOp() {
        assertEquals(0, JournalRetention.computePurgeTarget(0, 200, -1, TERM, SEG));
    }

    @Test
    public void computeTarget_respectsNonZeroStartPosition() {
        // A recording already purged to start 64: candidate 100 rounds to base 64 == start -> no-op.
        final long target = JournalRetention.computePurgeTarget(64, 400, 100, TERM, SEG);
        assertTrue("target at/below start is a no-op", target <= 64);
    }

    @Test
    public void computeTarget_realisticSixtyFourMegGeometry() {
        final int seg = 64 * 1024 * 1024;
        final long recordingPosition = 6L * seg + 100; // writing in the 7th segment
        final long candidate = 3L * seg;               // 4th segment base, below the watermark
        // keepTwoWholeSegments = 6*seg - 2*seg = 4*seg; candidate (3*seg) is the tighter bound.
        final long target = JournalRetention.computePurgeTarget(0, recordingPosition, candidate, seg, seg);
        assertEquals(3L * seg, target);
    }

    // ---- contract 1: a non-positive watermark purges nothing (no archive touched) ----

    @Test
    public void purgeBelow_nonPositiveWatermark_isNoOpWithoutArchive() {
        // safeEgressSeq <= 0 returns before touching the archive, so a null archive is safe here.
        assertEquals(0, JournalRetention.purgeBelow(null, Path.of("/nonexistent-journal-dir"), 0));
        assertEquals(0, JournalRetention.purgeBelow(null, Path.of("/nonexistent-journal-dir"), -5));
    }
}
