package com.match.infrastructure.persistence;

import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * 2. Purge recordings of superseded snapshots, keeping the most recent
 *    {@code snapshotsToKeep} (never fewer than 2: recovery falls back to the
 *    previous snapshot if the latest is invalidated).
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
     * Purge log segments below the latest valid snapshot and prune superseded
     * snapshot recordings.
     *
     * @param clusterDir      the node's cluster directory (contains recording.log)
     * @param archive         connected client to this node's archive
     * @param snapshotsToKeep how many most-recent snapshots to retain (min 2 enforced)
     * @return what was reclaimed
     */
    public static Result purgeBelowLatestSnapshot(
            final File clusterDir, final AeronArchive archive, final int snapshotsToKeep) {

        final int keep = Math.max(2, snapshotsToKeep);
        if (keep != snapshotsToKeep) {
            System.out.println("[HOUSEKEEPING] snapshotsToKeep=" + snapshotsToKeep
                    + " raised to 2: recovery needs a fallback if the latest snapshot is invalidated");
        }

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

        // 1. Purge whole log segments below the latest snapshot position
        final long newStartPosition = AeronArchive.segmentFileBasePosition(
                startPosition, latestSnapshotPosition, (int) descriptor[2], (int) descriptor[1]);

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

        // 2. Prune superseded snapshot recordings, keeping the most recent `keep` groups.
        // Entries of purged recordings remain in recording.log (it belongs to the
        // consensus module), so a recording may already be gone on a re-run —
        // recognise that as done, not as an error, to keep housekeeping idempotent.
        int snapshotRecordingsPurged = 0;
        int errors = 0;
        if (groupPositions.size() > keep) {
            final Set<Long> keepPositions = new HashSet<>(groupPositions.subList(0, keep));
            for (final RecordingLog.Entry entry : snapshotEntries) {
                if (keepPositions.contains(entry.logPosition)) {
                    continue;
                }
                final int exists = archive.listRecording(entry.recordingId,
                        (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                         startPos, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                         mtuLength, sessionId, streamId, strippedChannel, originalChannel,
                         sourceIdentity) -> { });
                if (exists == 0) {
                    System.out.println("[HOUSEKEEPING] Snapshot recording " + entry.recordingId
                            + " already purged (stale recording.log entry) — skipping");
                    continue;
                }
                try {
                    archive.purgeRecording(entry.recordingId);
                    snapshotRecordingsPurged++;
                    System.out.println("[HOUSEKEEPING] Purged superseded snapshot recording "
                            + entry.recordingId + " (serviceId=" + entry.serviceId
                            + ", logPosition=" + entry.logPosition + ")");
                } catch (final Exception e) {
                    errors++;
                    System.err.println("[HOUSEKEEPING] ERROR: failed to purge snapshot recording "
                            + entry.recordingId + ": " + e.getMessage());
                }
            }
        } else {
            System.out.println("[HOUSEKEEPING] " + groupPositions.size()
                    + " snapshot(s) present, keeping " + keep + " — no snapshot pruning needed.");
        }

        return new Result(logRecordingId, startPosition, newStartPosition,
                segmentsPurged, snapshotRecordingsPurged, errors);
    }

    /**
     * CLI entry point, intended to be invoked per node by the admin gateway
     * after a successful snapshot.
     *
     * Usage: ArchiveHousekeeping &lt;clusterDir&gt; &lt;aeronDir&gt; [snapshotsToKeep]
     */
    public static void main(final String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ArchiveHousekeeping <clusterDir> <aeronDir> [snapshotsToKeep]");
            System.exit(2);
        }
        final File clusterDir = new File(args[0]);
        final String aeronDir = args[1];
        final int keep = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        try (AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .controlRequestChannel("aeron:ipc?term-length=16m")
                        .controlResponseChannel("aeron:ipc?term-length=16m")
                        .aeronDirectoryName(aeronDir))) {

            final Result result = purgeBelowLatestSnapshot(clusterDir, archive, keep);
            System.out.println("[HOUSEKEEPING] Done: " + result);
        } catch (final Exception e) {
            System.err.println("[HOUSEKEEPING] FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
