// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reclaims archive disk space after cluster snapshots.
 *
 * Aeron snapshots do NOT truncate the cluster log — they only ADD recordings
 * (consensus module state + service state) plus a recording.log entry. Disk
 * reclamation is an explicit archive operation, which this class performs:
 *
 * 1. Purge whole log segment files below the latest valid snapshot position
 *    (recovery = latest snapshot + log replay from its position, so the log
 *    below that point is never needed by this node again).
 * Snapshot recordings themselves are deliberately NOT purged: Aeron recovery
 * selects the snapshot to replay by the recording.log {@code isValid} flag without
 * checking the archive, so purging a recording while its recording.log entry remains
 * valid breaks recover-from-snapshot ("unknown recording id"). Snapshot recordings are
 * tiny (serialized state); the reclaimable disk is the log segments. A post-run check
 * verifies every referenced snapshot recording still resolves in the archive.
 *
 * Must run against each node's own archive. Operational caveats (document,
 * don't discover): a member that was offline during the snapshot cannot
 * log-catch-up across purged segments and must be reseeded from a snapshot;
 * the cluster-backup node must have retrieved the snapshot before its source
 * member purges, or it likewise needs reseeding.
 *
 * Loud-limits principle: every action and every skipped action is reported.
 */
public final class ArchiveHousekeeping {

    /** Outcome of a housekeeping run, for reporting and assertions. */
    public static final class Result {
        public final long logRecordingId;
        public final long previousStartPosition;
        public final long newStartPosition;
        public final long segmentsPurged;
        public final long logBytesReclaimed;
        public final int snapshotRecordingsPurged;
        public final int errors;

        Result(long logRecordingId, long previousStartPosition, long newStartPosition,
               long segmentsPurged, int snapshotRecordingsPurged, int errors) {
            this.logRecordingId = logRecordingId;
            this.previousStartPosition = previousStartPosition;
            this.newStartPosition = newStartPosition;
            this.segmentsPurged = segmentsPurged;
            this.logBytesReclaimed = newStartPosition - previousStartPosition;
            this.snapshotRecordingsPurged = snapshotRecordingsPurged;
            this.errors = errors;
        }

        @Override
        public String toString() {
            return "Result{logRecordingId=" + logRecordingId
                    + ", previousStartPosition=" + previousStartPosition
                    + ", newStartPosition=" + newStartPosition
                    + ", segmentsPurged=" + segmentsPurged
                    + ", logBytesReclaimed=" + logBytesReclaimed
                    + ", snapshotRecordingsPurged=" + snapshotRecordingsPurged
                    + ", errors=" + errors + "}";
        }
    }

    private ArchiveHousekeeping() {}

    /**
     * Purge whole log segments below the latest valid snapshot. Snapshot recordings are
     * never purged (doing so breaks recover-from-snapshot — see class javadoc); this run
     * also verifies that every snapshot recording referenced by recording.log still resolves.
     *
     * @param clusterDir      the node's cluster directory (contains recording.log)
     * @param archive         connected client to this node's archive
     * @param snapshotsToKeep retained for call-site/CLI compatibility; no longer used
     * @return what was reclaimed (snapshotRecordingsPurged is always 0)
     */
    public static Result purgeBelowLatestSnapshot(
            final File clusterDir, final AeronArchive archive, final int snapshotsToKeep) {
        return purgeBelow(clusterDir, archive, Long.MAX_VALUE);
    }

    /** Named so a leftover positional argument can never be read as a position. */
    private static final String WATERMARK_FLAG = "--watermark=";

    /**
     * Extract {@code --watermark=N}, or {@link Long#MAX_VALUE} when absent.
     *
     * <p>Positional arguments are ignored outright, never guessed at: the third
     * slot historically carries {@code snapshotsToKeep} (a bare "2"), which
     * parses perfectly well as a log position. Read as one it would mean "purge
     * nothing", the log would grow unbounded and the disk would fill.
     */
    static long watermarkFrom(final String[] args) {
        for (int i = 2; i < args.length; i++) {
            if (!args[i].startsWith(WATERMARK_FLAG)) {
                continue;
            }
            final String value = args[i].substring(WATERMARK_FLAG.length());
            try {
                final long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    System.err.println("[HOUSEKEEPING] negative watermark " + parsed
                            + " ignored; purging to the latest snapshot instead");
                    return Long.MAX_VALUE;
                }
                return parsed;
            } catch (final NumberFormatException e) {
                System.err.println("[HOUSEKEEPING] FAILED: unparseable "
                        + WATERMARK_FLAG + "value: " + value);
                System.exit(2);
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * Purge whole log segments below {@code watermark}, never above the latest
     * valid snapshot.
     *
     * <p>The watermark is the caller's answer to "what has every consumer already
     * passed?" - the slowest live member, the backup, and the position durably
     * stored in S3. The snapshot bound stays because recovery on THIS node needs
     * everything above it; the watermark bounds it further for everyone else.
     *
     * @param watermark lowest position any consumer still needs
     */
    public static Result purgeBelow(final File clusterDir, final AeronArchive archive,
                                    final long watermark) {

        final List<RecordingLog.Entry> snapshotEntries = new ArrayList<>();
        final long logRecordingId;
        try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
            for (final RecordingLog.Entry entry : recordingLog.entries()) {
                if (entry.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && entry.isValid) {
                    snapshotEntries.add(entry);
                }
            }
            logRecordingId = recordingLog.findLastTermRecordingId();
        }

        if (logRecordingId == -1) {
            throw new IllegalStateException(
                    "[HOUSEKEEPING] No cluster log recording found in " + clusterDir);
        }

        final long[] descriptor = new long[3]; // startPosition, segmentFileLength, termBufferLength
        final int found = archive.listRecording(logRecordingId,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) -> {
                    descriptor[0] = startPosition;
                    descriptor[1] = segmentFileLength;
                    descriptor[2] = termBufferLength;
                });
        if (found == 0) {
            throw new IllegalStateException(
                    "[HOUSEKEEPING] Log recording " + logRecordingId + " not found in archive");
        }
        final long startPosition = descriptor[0];

        if (snapshotEntries.isEmpty()) {
            System.out.println("[HOUSEKEEPING] No valid snapshot — nothing reclaimable. "
                    + "Take a snapshot first; the log below it can then be purged.");
            return new Result(logRecordingId, startPosition, startPosition, 0, 0, 0);
        }

        // Snapshot groups: entries sharing a logPosition (consensus module + each service)
        final List<Long> groupPositions = snapshotEntries.stream()
                .map(e -> e.logPosition)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
        final long latestSnapshotPosition = groupPositions.get(0);

        // The purge point is the LOWER of what this node needs (its newest
        // snapshot) and what every other consumer has already passed. Ignoring
        // the second is how housekeeping strands a member that was behind
        // (match#35) and how it can delete log that S3 has not stored yet.
        final long purgePosition = Math.min(latestSnapshotPosition, watermark);
        if (purgePosition < latestSnapshotPosition) {
            System.out.println("[HOUSEKEEPING] Watermark holds the purge at " + purgePosition
                    + " (snapshot is at " + latestSnapshotPosition + "): a consumer has not"
                    + " passed it yet. Reclaiming less on purpose.");
        }

        // 1. Purge whole log segments below the latest snapshot position
        final long newStartPosition = AeronArchive.segmentFileBasePosition(
                startPosition, purgePosition, (int) descriptor[2], (int) descriptor[1]);

        long segmentsPurged = 0;
        if (newStartPosition > startPosition) {
            segmentsPurged = archive.purgeSegments(logRecordingId, newStartPosition);
            System.out.println("[HOUSEKEEPING] Purged " + segmentsPurged + " log segment(s): "
                    + "recordingId=" + logRecordingId
                    + " startPosition " + startPosition + " -> " + newStartPosition
                    + " (" + (newStartPosition - startPosition) + " bytes reclaimed, "
                    + "snapshot at " + latestSnapshotPosition + ")");
        } else {
            System.out.println("[HOUSEKEEPING] No whole log segment below snapshot position "
                    + latestSnapshotPosition + " (startPosition=" + startPosition
                    + ", segmentFileLength=" + descriptor[1] + ") — nothing purged. "
                    + "Smaller segmentFileLength reclaims sooner.");
        }

        // 2. Snapshot recordings are deliberately NOT purged. Aeron recovery selects the snapshot to
        // replay purely by the recording.log `isValid` flag and does NOT check the archive
        // (RecordingLog.getLatestSnapshot / ConsensusModuleAgent.loadSnapshot -> archive.startReplay has
        // no existence check and no fallback). Purging a snapshot recording here orphaned its still-valid
        // recording.log entry, so recover-from-snapshot crashed with "unknown recording id" and the node
        // came up unable to serve clients. There is NO supported way to invalidate a recording.log entry
        // (invalidateEntry/removeEntry are package-private) and recording.log is owned by the live,
        // unlocked ConsensusModule — we must not mutate it from this separate process. Snapshot recordings
        // are tiny (serialized cluster state, not the log); the reclaimable disk is the log segments above.

        // 3. Safety net: every valid snapshot recording the recording.log still references MUST resolve in
        // the archive, or recover-from-snapshot will crash. Verify and report loudly — this is the exact
        // invariant whose violation == the recovery bug, and it catches an already-corrupted node.
        int errors = 0;
        for (final RecordingLog.Entry entry : snapshotEntries) {
            final int exists = archive.listRecording(entry.recordingId,
                    (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                     startPos, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                     mtuLength, sessionId, streamId, strippedChannel, originalChannel,
                     sourceIdentity) -> { });
            if (exists == 0) {
                errors++;
                System.err.println("[HOUSEKEEPING] CRITICAL: recording.log references snapshot recordingId "
                        + entry.recordingId + " (serviceId=" + entry.serviceId
                        + ", logPosition=" + entry.logPosition + ") that is NOT in the archive — "
                        + "recover-from-snapshot will fail on this node; it must be reseeded.");
            }
        }

        // snapshotRecordingsPurged is always 0 now (we no longer purge snapshot recordings).
        return new Result(logRecordingId, startPosition, newStartPosition,
                segmentsPurged, 0, errors);
    }

    /**
     * CLI entry point, intended to be invoked per node by the admin gateway
     * after a successful snapshot.
     *
     * Usage: ArchiveHousekeeping &lt;clusterDir&gt; &lt;aeronDir&gt; [snapshotsToKeep]
     */
    public static void main(final String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ArchiveHousekeeping <clusterDir> <aeronDir> [ignored] [--watermark=N]");
            System.exit(2);
        }
        final File clusterDir = new File(args[0]);
        final String aeronDir = args[1];
        final long watermark = watermarkFrom(args);

        try (AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .controlRequestChannel("aeron:ipc?term-length=16m")
                        .controlResponseChannel("aeron:ipc?term-length=16m")
                        .aeronDirectoryName(aeronDir))) {

            final Result result = purgeBelow(clusterDir, archive, watermark);
            System.out.println("[HOUSEKEEPING] Done: " + result);
        } catch (final Exception e) {
            System.err.println("[HOUSEKEEPING] FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
