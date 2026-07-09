// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import io.aeron.archive.client.AeronArchive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Watermark-gated disk reclamation for the settlement journal's per-node archive.
 *
 * <p>The journal is a per-node SECOND archive (see {@link SettlementJournalRuntime}) recording a
 * publication on {@link InfrastructureConstants#SETTLEMENT_JOURNAL_CHANNEL} stream
 * {@link InfrastructureConstants#SETTLEMENT_JOURNAL_STREAM_ID}. It is the money-settlement record and
 * the Assets-Engine rebuild input, so purging it is dangerous: deleting a byte whose {@code egressSeq}
 * a rebuild still needs is an UNRECOVERABLE lost settlement. This tool therefore purges ONLY below a
 * caller-supplied {@code safeEgressSeq} watermark — the egressSeq up to which settlement has been
 * durably applied downstream (the Assets Engine), so nothing at or above it may be reclaimed.
 *
 * <p>It mirrors the live-safe reclamation pattern of
 * {@link com.match.infrastructure.persistence.ArchiveHousekeeping}: a connected {@link AeronArchive}
 * client, {@code listRecordingsForUri} + {@code purgeSegments} against the LIVE archive (no downtime),
 * invoked per node by the admin gateway. The difference is the key: housekeeping purges below a
 * snapshot POSITION; this purges below an egressSeq WATERMARK, translated to a byte position through
 * the {@link JournalCheckpointFile} sidecar.
 *
 * <h2>Safety contract (protects the money-rebuild story)</h2>
 * <ol>
 *   <li>{@code safeEgressSeq <= 0} purges NOTHING (nothing is provably safe below a non-positive
 *       watermark). Loud log, no-op.</li>
 *   <li>Candidate purge position = the highest checkpoint position whose {@code egressSeq <
 *       safeEgressSeq}. No qualifying checkpoint is a no-op.</li>
 *   <li>For the ACTIVE recording (stopPosition == NULL): the purge target is
 *       {@code segmentFileBasePosition(startPosition, candidate, ...)}, backed off so that at least
 *       TWO whole segments remain below the recording's current {@code recordingPosition}. If the
 *       target is at/below the current startPosition it is a no-op.</li>
 *   <li>STOPPED recordings (older node incarnations) are NEVER purged in v1 — they are logged with
 *       their sizes and left intact. See {@code purgeBelow} for the full rationale.</li>
 *   <li>Every action and every no-op logs what and why, with the values.</li>
 * </ol>
 *
 * <h2>Why candidate selection is restricted to the ACTIVE incarnation (conservative-not-clever)</h2>
 * The checkpoint sidecar is append-only across the whole life of a node's journal directory, but a
 * fresh process incarnation starts a NEW recording whose byte positions RESET to zero. The sidecar
 * carries no recordingId, so a raw "highest position with egressSeq &lt; watermark" taken over the
 * WHOLE file can pick a position that belonged to an OLDER incarnation and then map it into the
 * ACTIVE recording's (different) byte space — which could purge active-recording bytes whose egressSeq
 * is at/above the watermark. That is exactly the money-loss we must never risk. Because {@code
 * egressSeq} is globally monotonic while positions reset downward at each incarnation boundary, the
 * active incarnation's checkpoints are the maximal trailing run that is monotonically increasing in
 * position; candidate selection is confined to that run, and the chosen candidate is additionally
 * required to be {@code <= recordingPosition} of the active recording. This is strictly MORE
 * conservative than the raw rule (it never purges more, sometimes less) and is provably safe: every
 * purged byte has {@code egressSeq < safeEgressSeq}.
 */
public final class JournalRetention {

    private static final String LOG = "[JOURNAL-RETENTION] ";

    private JournalRetention() {}

    /** One recording descriptor row we care about (subset of the 16-field Aeron descriptor). */
    private static final class Rec {
        final long recordingId;
        final long startPosition;
        final long stopPosition;
        final int termBufferLength;
        final int segmentFileLength;

        Rec(long recordingId, long startPosition, long stopPosition,
            int termBufferLength, int segmentFileLength) {
            this.recordingId = recordingId;
            this.startPosition = startPosition;
            this.stopPosition = stopPosition;
            this.termBufferLength = termBufferLength;
            this.segmentFileLength = segmentFileLength;
        }

        boolean isActive() {
            return stopPosition == AeronArchive.NULL_POSITION;
        }
    }

    /**
     * Purge journal segments strictly below {@code safeEgressSeq} on the node whose journal directory
     * is {@code journalNodeDir} (contains {@link JournalCheckpointFile#FILE_NAME} directly and an
     * {@code archive/} subdir). Live-safe: {@code archive} is a connected client of the node's running
     * journal archive.
     *
     * @return bytes reclaimed from the active recording (0 on any no-op).
     */
    public static long purgeBelow(final AeronArchive archive, final Path journalNodeDir, final long safeEgressSeq) {
        // Contract 1: a non-positive watermark means nothing is provably durable downstream yet.
        if (safeEgressSeq <= 0) {
            System.out.println(LOG + "safeEgressSeq=" + safeEgressSeq + " <= 0 — refusing to purge "
                    + "anything (nothing is provably safe below a non-positive watermark). No-op.");
            return 0;
        }

        // Enumerate every journal recording (active + all stopped incarnations) for the journal stream.
        final List<Rec> recordings = new ArrayList<>();
        archive.listRecordingsForUri(0, Integer.MAX_VALUE, "aeron:ipc",
                InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
                        recordings.add(new Rec(recordingId, startPosition, stopPosition,
                                termBufferLength, segmentFileLength)));

        if (recordings.isEmpty()) {
            System.out.println(LOG + "no journal recording found for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID + " in " + journalNodeDir
                    + " — journal never wrote, or wrong archive. No-op.");
            return 0;
        }

        Rec active = null;
        int activeCount = 0;
        for (final Rec r : recordings) {
            if (r.isActive()) {
                active = r;
                activeCount++;
            } else {
                // Contract 4: STOPPED recordings are older node incarnations. We do NOT purge them in
                // v1. The sidecar has no recordingId and positions reset across incarnations, so a
                // stopped incarnation's egressSeq cannot be mapped to this watermark without guessing.
                // The lead prefers conservative-not-clever: report them (id + size) and leave intact.
                System.out.println(LOG + "STOPPED recording " + r.recordingId + " retained (v1 "
                        + "conservative decision): startPosition=" + r.startPosition
                        + " stopPosition=" + r.stopPosition + " bytes=" + (r.stopPosition - r.startPosition)
                        + " — checkpoint positions reset across incarnations and the sidecar carries no "
                        + "recordingId, so this incarnation's egressSeq cannot be safely mapped to the "
                        + "watermark. NOT purged.");
            }
        }

        if (activeCount == 0) {
            System.out.println(LOG + "no ACTIVE recording for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID
                    + " (only stopped incarnations) — nothing to purge on the active archive. No-op.");
            return 0;
        }
        if (activeCount > 1) {
            System.out.println(LOG + "ANOMALY: " + activeCount + " ACTIVE recordings for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID
                    + " — refusing to purge (ambiguous which is live). No-op.");
            return 0;
        }

        // Contract 2: select the candidate from the ACTIVE incarnation's checkpoint suffix only.
        final List<long[]> checkpoints = JournalCheckpointFile.readAll(journalNodeDir);
        if (checkpoints.isEmpty()) {
            System.out.println(LOG + "no checkpoints recorded yet in " + journalNodeDir
                    + " — cannot map a watermark to a byte position. No-op.");
            return 0;
        }
        final int suffixStart = activeSuffixStartIndex(checkpoints);
        final long candidate = selectCandidatePosition(checkpoints, suffixStart, safeEgressSeq);
        if (candidate < 0) {
            System.out.println(LOG + "no checkpoint with egressSeq < " + safeEgressSeq
                    + " in the active incarnation (" + (checkpoints.size() - suffixStart)
                    + " checkpoint(s) examined) — nothing below the watermark. No-op.");
            return 0;
        }

        final long recordingPosition = archive.getRecordingPosition(active.recordingId);
        if (recordingPosition == AeronArchive.NULL_POSITION) {
            System.out.println(LOG + "active recording " + active.recordingId + " position unavailable "
                    + "(recording just stopped?) — refusing to purge. No-op.");
            return 0;
        }

        // Contract 3: translate candidate -> segment base, back off to keep >= 2 whole segments below
        // the current recording position.
        final long target = computePurgeTarget(active.startPosition, recordingPosition, candidate,
                active.termBufferLength, active.segmentFileLength);
        if (target <= active.startPosition) {
            System.out.println(LOG + "computed purge target " + target + " <= startPosition "
                    + active.startPosition + " (candidate=" + candidate + ", recordingPosition="
                    + recordingPosition + ", segmentFileLength=" + active.segmentFileLength
                    + ", keeping >= 2 whole segments) — nothing to purge. No-op.");
            return 0;
        }

        final long segmentsPurged = archive.purgeSegments(active.recordingId, target);
        final long bytesReclaimed = target - active.startPosition;
        System.out.println(LOG + "purged " + segmentsPurged + " journal segment(s) on recording "
                + active.recordingId + ": startPosition " + active.startPosition + " -> " + target
                + " (" + bytesReclaimed + " bytes reclaimed; safeEgressSeq=" + safeEgressSeq
                + ", candidate checkpoint position=" + candidate + ", recordingPosition="
                + recordingPosition + ", kept >= 2 whole segments below recordingPosition).");
        return bytesReclaimed;
    }

    /**
     * Index into {@code checkpoints} at which the ACTIVE (latest) incarnation's monotonic-in-position
     * run begins. Positions strictly increase within one incarnation and reset LOWER when a new
     * recording starts, so a checkpoint whose position did not strictly increase over its predecessor
     * marks an incarnation boundary. The last such boundary opens the active run.
     */
    static int activeSuffixStartIndex(final List<long[]> checkpoints) {
        int start = 0;
        for (int i = 1; i < checkpoints.size(); i++) {
            if (checkpoints.get(i)[1] <= checkpoints.get(i - 1)[1]) {
                start = i;
            }
        }
        return start;
    }

    /**
     * The highest checkpoint POSITION whose {@code egressSeq < safeEgressSeq}, scanning
     * {@code checkpoints[fromIndex .. end]} (the active incarnation's run). Returns -1 when no
     * checkpoint in range qualifies.
     */
    static long selectCandidatePosition(final List<long[]> checkpoints, final int fromIndex, final long safeEgressSeq) {
        long best = -1;
        for (int i = Math.max(0, fromIndex); i < checkpoints.size(); i++) {
            final long egressSeq = checkpoints.get(i)[0];
            final long position = checkpoints.get(i)[1];
            if (egressSeq < safeEgressSeq && position > best) {
                best = position;
            }
        }
        return best;
    }

    /**
     * The clamped purge target (a segment base) for the active recording. Returns a value
     * {@code <= startPosition} (signalling "no-op") when nothing can be safely purged.
     *
     * <p>Guards, in order:
     * <ul>
     *   <li>no candidate ({@code candidatePosition < 0}) — no-op;</li>
     *   <li>candidate beyond the recording head ({@code candidatePosition > recordingPosition}) — the
     *       checkpoint suffix cannot belong to this active recording (e.g. the current incarnation has
     *       written nothing yet), so refuse — no-op;</li>
     *   <li>otherwise: {@code min(segmentBaseOf(candidate), segmentBaseOf(recordingPosition) - 2
     *       segments)} — round the candidate down to a segment base, then back off so at least two
     *       whole segments remain below the recording head.</li>
     * </ul>
     */
    static long computePurgeTarget(final long startPosition, final long recordingPosition,
                                   final long candidatePosition, final int termBufferLength,
                                   final int segmentFileLength) {
        if (candidatePosition < 0) {
            return startPosition;
        }
        if (candidatePosition > recordingPosition) {
            return startPosition;
        }
        final long fromCandidate = AeronArchive.segmentFileBasePosition(
                startPosition, candidatePosition, termBufferLength, segmentFileLength);
        final long activeSegmentBase = AeronArchive.segmentFileBasePosition(
                startPosition, recordingPosition, termBufferLength, segmentFileLength);
        final long keepTwoWholeSegments = activeSegmentBase - 2L * segmentFileLength;
        return Math.min(fromCandidate, keepTwoWholeSegments);
    }

    /**
     * CLI entry point, invoked per node by the admin gateway.
     *
     * <p>Usage: {@code JournalRetention <journalNodeDir> <aeronDir> <archiveControlPort> <safeEgressSeq>}
     * where {@code journalNodeDir} is {@code <SETTLEMENT_JOURNAL_DIR>/node<N>}, {@code aeronDir} is
     * node N's media-driver aeron.dir, and {@code archiveControlPort} is
     * {@code portBase + N*100 + }{@link InfrastructureConstants#JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET}.
     */
    public static void main(final String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: JournalRetention <journalNodeDir> <aeronDir> "
                    + "<archiveControlPort> <safeEgressSeq>");
            System.exit(2);
        }
        final Path journalNodeDir = Path.of(args[0]);
        final String aeronDir = args[1];
        final int controlPort = Integer.parseInt(args[2]);
        final long safeEgressSeq = Long.parseLong(args[3]);
        final String controlChannel = "aeron:udp?endpoint=localhost:" + controlPort;

        try (AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .aeronDirectoryName(aeronDir)
                        .controlRequestChannel(controlChannel)
                        .controlRequestStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_STREAM_ID)
                        .controlResponseChannel("aeron:udp?endpoint=localhost:0"))) {

            final long bytes = purgeBelow(archive, journalNodeDir, safeEgressSeq);
            System.out.println(LOG + "Done: bytesReclaimed=" + bytes + " (journalNodeDir="
                    + journalNodeDir + ", safeEgressSeq=" + safeEgressSeq + ")");
        } catch (final Exception e) {
            System.err.println(LOG + "FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
