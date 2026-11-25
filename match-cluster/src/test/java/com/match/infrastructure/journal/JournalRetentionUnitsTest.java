// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import org.agrona.IoUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Journal retention is keyed by {@code (meEpoch, journalSeq)} and a number in any other unit never purges a record a
 * reader has not applied. The journal here is a model with exact arithmetic: record {@code s} of epoch
 * {@link #EPOCH} is {@link #RECORD} bytes and ends at byte {@code s * RECORD} of its recording; segments are
 * {@link #SEG} bytes; the writer checkpoints the first frame of every segment. The files are the real ones
 * ({@link JournalSidecarV2}, {@link JournalHeadFile}); the selection, the head bound and the purge target are the
 * tool's own code. Only the archive call is left out (the integration test has it).
 */
public class JournalRetentionUnitsTest {

    private static final long EPOCH = 7_700_000_001L;
    private static final long PREVIOUS_EPOCH = 7_700_000_000L;
    private static final int SEG = 64 * 1024;
    private static final int RECORD = 128;
    private static final long RECORDING = 12;
    /** egressSeq is a byte position of the ME cluster log: sparse, and far above any journalSeq. */
    private static final long EGRESS_BASE = 50_000_000L;

    private Path dir;
    private ByteArrayOutputStream err;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("journal-retention-units-");
    }

    @After
    public void tearDown() {
        JournalRetention.headBoundOffForTest = false;
        JournalRetention.selectOnEgressSeqForTest = false;
        IoUtil.delete(dir.toFile(), true);
    }

    private static long egressOf(final long journalSeq) {
        return EGRESS_BASE + journalSeq * 4_096;
    }

    /** Writes what the writer writes for records {@code first..last} on {@code recordingId}, positions from 0. */
    private void journal(final long epoch, final long first, final long last, final long recordingId) {
        try (JournalSidecarV2 sidecar = new JournalSidecarV2(dir); JournalHeadFile head = new JournalHeadFile(dir)) {
            long lastBase = -1;
            for (long s = first; s <= last; s++) {
                final long position = (s - first + 1) * RECORD;
                final long base = position - position % SEG;
                if (base != lastBase) {
                    sidecar.append(epoch, s, egressOf(s), recordingId, position);
                    lastBase = base;
                }
            }
            head.write(epoch, last, recordingId);
        }
    }

    /** What a run of the tool would do against an active recording that starts at 0 and holds {@code records}. */
    private long purgeTarget(final long meEpoch, final long applied, final long activeRecordingId, final long records) {
        final JournalRetention.Checked checked = JournalRetention.check(dir, meEpoch, applied);
        final long candidate = JournalRetention.selectCandidatePosition(checked.checkpoints, activeRecordingId,
                meEpoch, applied);
        return Math.max(0, JournalRetention.computePurgeTarget(0, records * RECORD, candidate, SEG, SEG));
    }

    /** The purge target, after checking that no record above {@code trulyApplied} lies below it. */
    private long purgeNeverAbove(final long trulyApplied, final long asked, final long firstSeq, final long records) {
        final long target = purgeTarget(EPOCH, asked, RECORDING, records);
        final long highestPurged = firstSeq - 1 + target / RECORD; // records wholly below the target
        if (target > 0 && highestPurged > trulyApplied) {
            throw new AssertionError("purged records above the applied journalSeq: target " + target
                    + " deletes up to journalSeq " + highestPurged + ", applied " + trulyApplied);
        }
        return target;
    }

    private static String refusal(final Runnable r) {
        try {
            r.run();
        } catch (final JournalRetention.Refused refused) {
            return refused.getMessage();
        }
        throw new AssertionError("expected a refusal");
    }

    private int run(final String... args) {
        err = new ByteArrayOutputStream();
        return JournalRetention.run(args, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
    }

    private int runWith(final String epoch, final String applied) {
        return run("--journal-node-dir", dir.toString(), "--aeron-dir", dir.resolve("no-driver").toString(),
                "--archive-control-port", "18999", "--me-epoch", epoch, "--applied-journal-seq", applied);
    }

    // ---------------------------------------------------------------- selection

    @Test
    public void theRightUnitPurgesBelowTheAppliedCursorAndNoFurther() {
        journal(EPOCH, 1, 5_000, RECORDING);
        final long applied = 2_000;
        final long target = purgeNeverAbove(applied, applied, 1, 5_000);
        // checkpoints at seq 1, 512, 1024, 1536, 2048, ...: the greatest at or below 2000 is 1536, at byte 196608
        assertEquals(3L * SEG, target);
        if (!(target > 0)) {
            throw new AssertionError("bytesReclaimed > 0 expected for an applied cursor three segments in");
        }
        // equal to a checkpoint's journalSeq: that checkpoint qualifies (at or below)
        assertEquals(4L * SEG, purgeNeverAbove(2_048, 2_048, 1, 5_000));
        assertEquals(3L * SEG, purgeNeverAbove(2_047, 2_047, 1, 5_000));
        // close to the head: two whole segments stay
        assertEquals(base(5_000L * RECORD) - 2L * SEG, purgeNeverAbove(5_000, 5_000, 1, 5_000));
    }

    private static long base(final long position) {
        return position - position % SEG;
    }

    @Test
    public void aNumberInAnyOtherUnitIsRefusedOrANoOpNeverAPurge() {
        journal(EPOCH, 1, 5_000, RECORDING);
        final long applied = 2_000;

        // consumePosition: the egressSeq of the last applied record (a byte position of the ME cluster log)
        final long consumePosition = egressOf(applied);
        assertEquals("applied journalSeq " + consumePosition + " is beyond the journal head 5000: wrong unit, or the"
                + " head file is stale; nothing purged",
                refusal(() -> purgeNeverAbove(applied, consumePosition, 1, 5_000)));
        // a byte position of the journal recording itself
        final long bytePosition = applied * RECORD;
        assertTrue(refusal(() -> purgeNeverAbove(applied, bytePosition, 1, 5_000))
                .startsWith("applied journalSeq " + bytePosition + " is beyond the journal head 5000"));
        // a trade id: dense like journalSeq and never above it; too small, so less is purged or nothing
        assertEquals("a small trade id purges nothing", 0, purgeNeverAbove(applied, 300, 1, 5_000));
        assertEquals(base(600L * RECORD), purgeNeverAbove(applied, 600, 1, 5_000));
        // a cursor kept from before an ME re-genesis
        assertEquals("epoch mismatch: asked " + PREVIOUS_EPOCH + ", journal " + EPOCH + "; nothing purged",
                refusal(() -> purgeTarget(PREVIOUS_EPOCH, applied, RECORDING, 5_000)));
        // nothing applied, and no epoch
        assertTrue(refusal(() -> purgeTarget(EPOCH, 0, RECORDING, 5_000)).contains("is below 1"));
        assertTrue(refusal(() -> purgeTarget(0, applied, RECORDING, 5_000)).contains("--me-epoch 0"));
    }

    /** Control (a): without the head bound a byte position purges to two segments below the head. */
    @Test
    public void negativeControlHeadBoundOff() {
        journal(EPOCH, 1, 5_000, RECORDING);
        JournalRetention.headBoundOffForTest = true;
        try {
            purgeNeverAbove(2_000, 2_000L * RECORD, 1, 5_000);
        } catch (final AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith(
                    "purged records above the applied journalSeq: target " + (base(5_000L * RECORD) - 2L * SEG)));
            return;
        }
        fail("negative control did not fail: the assertion cannot see the head bound");
    }

    /** Control (b): selected on egressSeq, the old rule, a journalSeq never purges anything. */
    @Test
    public void negativeControlSelectionOnEgressSeq() {
        journal(EPOCH, 1, 5_000, RECORDING);
        JournalRetention.selectOnEgressSeqForTest = true;
        try {
            final long target = purgeNeverAbove(2_000, 2_000, 1, 5_000);
            if (!(target > 0)) {
                throw new AssertionError("bytesReclaimed > 0 expected for an applied cursor three segments in, got "
                        + target);
            }
        } catch (final AssertionError expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("bytesReclaimed > 0 expected"));
            return;
        }
        fail("negative control did not fail: the assertion cannot see the selection key");
    }

    @Test
    public void onlyTheActiveRecordingsCheckpointsAreSelected() {
        // an earlier incarnation wrote records 1..3000 on recording 12 (stopped now); the node restarted and
        // writes 3001.. on recording 13, whose byte positions start again at 0
        journal(EPOCH, 1, 3_000, RECORDING);
        journal(EPOCH, 3_001, 6_000, RECORDING + 1);
        final JournalRetention.Checked checked = JournalRetention.check(dir, EPOCH, 4_100);
        assertEquals("both recordings are in one sidecar", 2,
                checked.checkpoints.stream().map(JournalSidecarV2.Checkpoint::recordingId).distinct().count());
        final long candidate = JournalRetention.selectCandidatePosition(checked.checkpoints, RECORDING + 1, EPOCH,
                4_100);
        // recording 13 checkpoints: seq 3001 @128, 3512 @65536, 4024 @131072, 4536 @196608: 4024 is the greatest
        assertEquals(2L * SEG, candidate);
        // the stopped recording has checkpoints at higher positions with lower seqs (2560 @327680): none is taken
        assertTrue(checked.checkpoints.stream().anyMatch(c -> c.recordingId() == RECORDING
                && c.journalSeq() <= 4_100 && c.position() > candidate));
        // a cursor inside the stopped recording: nothing of the active one qualifies
        assertEquals(-1, JournalRetention.selectCandidatePosition(checked.checkpoints, RECORDING + 1, EPOCH, 2_900));
        // an active recording with no checkpoint yet (a writer that just restarted)
        assertEquals(-1, JournalRetention.selectCandidatePosition(checked.checkpoints, RECORDING + 2, EPOCH, 4_100));
    }

    @Test
    public void aCheckpointOfTheActiveRecordingInAnotherEpochRefuses() {
        try (JournalSidecarV2 sidecar = new JournalSidecarV2(dir); JournalHeadFile head = new JournalHeadFile(dir)) {
            sidecar.append(0, 0, 100, RECORDING, 128); // written before the epoch was bound
            sidecar.append(EPOCH, 400, 200, RECORDING, 65_600);
            head.write(EPOCH, 900, RECORDING);
        }
        final List<JournalSidecarV2.Checkpoint> checkpoints = JournalRetention.check(dir, EPOCH, 500).checkpoints;
        assertEquals("epoch mismatch: asked " + EPOCH + ", journal 0 (a checkpoint of the active recording "
                + RECORDING + "); nothing purged",
                refusal(() -> JournalRetention.selectCandidatePosition(checkpoints, RECORDING, EPOCH, 500)));
    }

    // ---------------------------------------------------------------- the command line

    @Test
    public void usageErrorsExit64() {
        journal(EPOCH, 1, 5_000, RECORDING);
        // the form this tool had before: four positional arguments, the last one a bare number
        assertEquals(64, run(dir.toString(), "/dev/shm/aeron", "18999", "2000"));
        assertTrue(err.toString(), err.toString().contains(
                "positional arguments are no longer accepted: name the unit (--me-epoch, --applied-journal-seq)"));
        assertTrue(err.toString(), err.toString().contains("Usage: JournalRetention --journal-node-dir"));
        assertEquals("no arguments", 64, run());
        assertEquals("a positional after the flags", 64, run("--journal-node-dir", dir.toString(), "--aeron-dir",
                "x", "--archive-control-port", "18999", "--me-epoch", "5", "--applied-journal-seq", "7", "2000"));
        assertEquals("an unknown flag", 64, run("--journal-node-dir", dir.toString(), "--aeron-dir", "x",
                "--archive-control-port", "18999", "--me-epoch", "5", "--applied-journal-seq", "7",
                "--safe-egress-seq", "9"));
        assertEquals("a missing flag", 64, run("--journal-node-dir", dir.toString(), "--aeron-dir", "x",
                "--archive-control-port", "18999", "--applied-journal-seq", "7"));
        assertTrue(err.toString(), err.toString().contains("missing flag --me-epoch"));
        assertEquals("a repeated flag", 64, run("--journal-node-dir", dir.toString(), "--aeron-dir", "x",
                "--archive-control-port", "18999", "--me-epoch", "5", "--me-epoch", "5", "--applied-journal-seq",
                "7"));
        assertEquals("a flag without its value", 64, run("--journal-node-dir", dir.toString(), "--aeron-dir", "x",
                "--archive-control-port", "18999", "--me-epoch", "5", "--applied-journal-seq"));
    }

    @Test
    public void refusedInputExits65BeforeAnyArchiveIsContacted() throws Exception {
        // nothing written yet (the state right after an upgrade): no head, no sidecar
        assertEquals(65, runWith(Long.toString(EPOCH), "2000"));
        assertTrue(err.toString(), err.toString().contains("journal head file"));

        journal(EPOCH, 1, 5_000, RECORDING);
        assertEquals(65, runWith(Long.toString(EPOCH), "0"));
        assertEquals(65, runWith(Long.toString(EPOCH), "-4"));
        assertEquals(65, runWith("0", "2000"));
        assertEquals(65, runWith(Long.toString(EPOCH), "two-thousand"));
        assertEquals(65, runWith(Long.toString(PREVIOUS_EPOCH), "2000"));
        assertTrue(err.toString(), err.toString().contains("epoch mismatch: asked " + PREVIOUS_EPOCH + ", journal "
                + EPOCH));
        assertEquals(65, runWith(Long.toString(EPOCH), "5001"));
        assertTrue(err.toString(), err.toString().contains("applied journalSeq 5001 is beyond the journal head 5000:"
                + " wrong unit, or the head file is stale; nothing purged"));
        assertEquals("a port out of range", 65, run("--journal-node-dir", dir.toString(), "--aeron-dir", "x",
                "--archive-control-port", "70000", "--me-epoch", Long.toString(EPOCH), "--applied-journal-seq",
                "2000"));

        // the head torn by a crash in the middle of a rewrite
        final Path head = dir.resolve(JournalHeadFile.FILE_NAME);
        final byte[] good = Files.readAllBytes(head);
        final byte[] torn = good.clone();
        torn[8] ^= 0x10;
        Files.write(head, torn);
        assertEquals(65, runWith(Long.toString(EPOCH), "2000"));
        assertTrue(err.toString(), err.toString().contains("is missing or fails its CRC"));
        Files.write(head, good);

        // the sidecar with a bad record before the tail
        final Path sidecar = dir.resolve(JournalSidecarV2.FILE_NAME);
        final byte[] bytes = Files.readAllBytes(sidecar);
        bytes[JournalSidecarV2.RECORD_LENGTH + 32] ^= 0x01;
        Files.write(sidecar, bytes);
        assertEquals(65, runWith(Long.toString(EPOCH), "2000"));
        assertTrue(err.toString(), err.toString().contains("the checkpoint sidecar is unusable"));
        Files.delete(sidecar);
        assertEquals(65, runWith(Long.toString(EPOCH), "2000"));
    }
}
