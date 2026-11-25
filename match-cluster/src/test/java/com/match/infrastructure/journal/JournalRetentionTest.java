// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The purge-target math of {@link JournalRetention}: the target is a segment base, at least two whole segments stay
 * below the recording head, and a candidate that cannot belong to the recording purges nothing. The selection by
 * {@code (meEpoch, journalSeq)} and the refusals are in {@code JournalRetentionUnitsTest}; the real-archive proof is
 * {@code JournalRetentionIntegrationTest}.
 */
public class JournalRetentionTest {

    // Readable, power-of-two segment geometry for the math (production uses 64MB/64MB).
    private static final int TERM = 64;
    private static final int SEG = 64;

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
}
