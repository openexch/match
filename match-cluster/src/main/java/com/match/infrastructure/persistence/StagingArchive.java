// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.ArchivingMediaDriver;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.codecs.RecordingSignal;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.YieldingIdleStrategy;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * A quiescent copy of a live node's archive, on real disk, built by Aeron
 * replication rather than by copying files.
 *
 * <p>Why replication and not {@code cp}: an Aeron snapshot is a set of recordings
 * inside a LIVE archive, and the cluster log is a segment file still being
 * appended. Reading those files out from under a running archive is a race
 * against the recorder. Replication asks the source archive to replay a bounded
 * range into this one, so what lands here is a consistent copy the source agreed
 * to hand over.
 *
 * <p><b>The staging archive is deliberately persistent.</b> Aeron's
 * {@link io.aeron.archive.client.ReplicationParams} has a stop position but no
 * START position: a replication begins either at the source recording's start or
 * at the destination recording's stop position. Chaining bundles (each covering
 * {@code previousPosition..snapshotPosition}) therefore requires a destination
 * recording that persists between runs and gets extended. A fresh staging
 * archive each time could only ever re-copy the whole log.
 *
 * <p>That makes staging retention a real obligation rather than an afterthought:
 * once a range is durable in S3, the segments below it are reclaimable here with
 * the same {@code purgeSegments} call the live archives use. Without that this
 * directory grows for as long as the cluster runs. See {@link #purgeBelow}.
 *
 * <p>It lives on disk on purpose. The whole point of the exercise is to stop the
 * ledger's durability from depending on tmpfs, so staging it back into tmpfs
 * would reproduce the failure it exists to prevent.
 */
public final class StagingArchive implements AutoCloseable {

    /** Segment size matches the live AE archive, so purge arithmetic agrees. */
    private static final int SEGMENT_FILE_LENGTH = 64 * 1024 * 1024;

    private static final long REPLICATION_TIMEOUT_NS = TimeUnit.MINUTES.toNanos(10);

    private final ArchivingMediaDriver mediaDriver;
    private final AeronArchive archive;
    private final File archiveDir;

    /**
     * The replication currently being awaited.
     *
     * <p>Signals are routed here through the archive Context rather than a
     * standalone adapter for a load-bearing reason: EVERY archive call that waits
     * for a response drains the same control subscription, so a {@code
     * listRecording} issued mid-replication would silently consume the terminal
     * signal an adapter was waiting for. The context consumer is the only place
     * that sees all of them.
     */
    private final ReplicationOutcome outcome;

    private StagingArchive(final ArchivingMediaDriver mediaDriver, final AeronArchive archive,
                           final File archiveDir, final ReplicationOutcome outcome) {
        this.mediaDriver = mediaDriver;
        this.archive = archive;
        this.archiveDir = archiveDir;
        this.outcome = outcome;
    }

    /**
     * Launch an isolated archive under {@code stagingRoot}, with its own embedded
     * media driver and Aeron directory.
     *
     * <p>Isolation is the point: this process must never attach to a live node's
     * media driver. A capture run that wedged a node's driver would take down the
     * very ledger it is protecting, so it gets its own everything, and SHARED
     * threading because throughput here is irrelevant next to not competing for
     * the cores the engines busy-spin on.
     *
     * @param stagingRoot directory holding {@code aeron/} and {@code archive/}
     */
    public static StagingArchive launch(final File stagingRoot) {
        final File aeronDir = new File(stagingRoot, "aeron");
        final File archiveDir = new File(stagingRoot, "archive");
        //noinspection ResultOfMethodCallIgnored
        archiveDir.mkdirs();

        // EPHEMERAL port. Nothing dials this archive: the client attaches over
        // the IPC local channel below, and replication is initiated BY us TO the
        // source. A fixed port would only create a collision to reason about
        // between two clusters' captures, and "they never run at the same time"
        // is an assumption that ages badly.
        final String control = "aeron:udp?endpoint=localhost:0";

        final MediaDriver.Context driverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDir.getAbsolutePath())
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(new YieldingIdleStrategy())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .spiesSimulateConnection(true);

        final Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDir.getAbsolutePath())
                .archiveDir(archiveDir)
                .controlChannel(control)
                .localControlChannel("aeron:ipc?term-length=16m")
                // Where the SOURCE archive sends the replayed data. Ephemeral
                // port: nothing dials us, we dial out and tell them where to reply.
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .segmentFileLength(SEGMENT_FILE_LENGTH)
                .deleteArchiveOnStart(false);

        final ReplicationOutcome outcome = new ReplicationOutcome();

        ArchivingMediaDriver mediaDriver = null;
        AeronArchive archive = null;
        try {
            mediaDriver = ArchivingMediaDriver.launch(driverContext, archiveContext);
            archive = AeronArchive.connect(new AeronArchive.Context()
                    .controlRequestChannel(archiveContext.localControlChannel())
                    .controlResponseChannel(archiveContext.localControlChannel())
                    .aeronDirectoryName(aeronDir.getAbsolutePath())
                    // Registered before connect so no signal can be dispatched
                    // before there is somebody to receive it.
                    .recordingSignalConsumer((controlSessionId, correlationId, recordingId,
                                              subscriptionId, position, signal) ->
                            outcome.onSignal(correlationId, recordingId, position, signal)));
            return new StagingArchive(mediaDriver, archive, archiveDir, outcome);
        } catch (final RuntimeException e) {
            CloseHelper.quietClose(archive);
            CloseHelper.quietClose(mediaDriver);
            throw e;
        }
    }

    public AeronArchive archive() {
        return archive;
    }

    public File archiveDir() {
        return archiveDir;
    }

    /**
     * Pull a range of a source recording into this archive and block until the
     * source says it is done.
     *
     * @param srcControlChannel the live archive's UDP control channel
     * @param srcRecordingId    recording to copy
     * @param dstRecordingId    existing local recording to EXTEND, or
     *                          {@link io.aeron.Aeron#NULL_VALUE} to create one.
     *                          Extending is how bundles chain: the copy resumes
     *                          exactly where the previous bundle ended.
     * @param stopPosition      position to stop at, or
     *                          {@link AeronArchive#NULL_POSITION} for the whole
     *                          recording (correct for a completed snapshot, wrong
     *                          for a live log, which would never finish)
     * @return the local recording id holding the copy
     */
    public long replicate(final String srcControlChannel, final long srcRecordingId,
                          final long dstRecordingId, final long stopPosition) {
        final io.aeron.archive.client.ReplicationParams params =
                new io.aeron.archive.client.ReplicationParams()
                        .dstRecordingId(dstRecordingId)
                        .stopPosition(stopPosition);

        // The archive stamps every replication signal with the replication id it
        // returns here, so this is the only reliable way to tell THIS replication's
        // completion from the previous one's. Matching on the destination recording
        // id instead would conflate two copies into the same archive.
        final long replicationId = archive.replicate(srcRecordingId,
                AeronArchive.Configuration.CONTROL_STREAM_ID_DEFAULT, srcControlChannel, params);
        outcome.await(replicationId, dstRecordingId);

        final long deadline = System.nanoTime() + REPLICATION_TIMEOUT_NS;
        final YieldingIdleStrategy idle = new YieldingIdleStrategy();
        while (!outcome.isComplete()) {
            if (archive.pollForRecordingSignals() == 0) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("[BUNDLE] replication of recording "
                            + srcRecordingId + " did not finish within "
                            + TimeUnit.NANOSECONDS.toMinutes(REPLICATION_TIMEOUT_NS) + " minutes"
                            + " (last position " + outcome.position() + ")");
                }
                idle.idle();
            } else {
                idle.reset();
            }
        }

        return outcome.recordingId();
    }

    /**
     * Reclaim staging segments below {@code position}. Call this only for ranges
     * already durable and checksummed in S3 — this is the staging mirror of the
     * live archive's housekeeping, and without it the staging directory grows for
     * the life of the cluster.
     *
     * @return count of segment files purged
     */
    public long purgeBelow(final long recordingId, final long position) {
        final long[] descriptor = new long[3]; // startPosition, segmentFileLength, termBufferLength
        final int found = archive.listRecording(recordingId,
                (controlSessionId, correlationId, id, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) -> {
                    descriptor[0] = startPosition;
                    descriptor[1] = segmentFileLength;
                    descriptor[2] = termBufferLength;
                });
        if (found == 0) {
            return 0;
        }

        final long newStart = AeronArchive.segmentFileBasePosition(
                descriptor[0], position, (int) descriptor[2], (int) descriptor[1]);
        if (newStart <= descriptor[0]) {
            return 0;
        }
        return archive.purgeSegments(recordingId, newStart);
    }

    /** Stop position of a local recording, or -1 if it does not exist. */
    public long stopPosition(final long recordingId) {
        final long[] stop = {-1};
        archive.listRecording(recordingId,
                (controlSessionId, correlationId, id, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
                        stop[0] = stopPosition);
        return stop[0];
    }

    @Override
    public void close() {
        CloseHelper.quietClose(archive);
        CloseHelper.quietClose(mediaDriver);
    }

    /**
     * Tracks one replication to completion.
     *
     * <p>Completion is a SIGNAL, not a position comparison. A replication that is
     * merged, stopped or deleted has finished in ways a position check would miss,
     * and treating "position reached" as done would let a bundle be written from a
     * copy the source had abandoned.
     */
    private static final class ReplicationOutcome {
        private long replicationId = io.aeron.Aeron.NULL_VALUE;
        private long recordingId = io.aeron.Aeron.NULL_VALUE;
        private long position = -1;
        private boolean complete;

        /** Arm for one replication; every earlier one's signals become noise. */
        void await(final long replicationId, final long dstRecordingId) {
            this.replicationId = replicationId;
            this.recordingId = dstRecordingId;
            this.position = -1;
            this.complete = false;
        }

        void onSignal(final long signalCorrelationId, final long signalRecordingId,
                      final long signalPosition, final RecordingSignal signal) {
            if (signalCorrelationId != replicationId) {
                return; // a different replication's signal
            }
            if (signalRecordingId != io.aeron.Aeron.NULL_VALUE) {
                recordingId = signalRecordingId;
            }
            // REPLICATE_END carries NULL_POSITION, so the last real position must
            // survive it - it is what the caller checks the copy reached.
            if (signalPosition >= 0) {
                position = signalPosition;
            }
            if (signal == RecordingSignal.DELETE) {
                throw new IllegalStateException(
                        "[BUNDLE] source deleted recording " + signalRecordingId
                                + " during replication");
            }
            // REPLICATE_END is the terminal signal, emitted when the session closes.
            // STOP and SYNC are milestones a bounded copy passes through on the way,
            // and treating either as completion would return a partial recording.
            if (signal == RecordingSignal.REPLICATE_END) {
                complete = true;
            }
        }

        boolean isComplete() {
            return complete;
        }

        long recordingId() {
            return recordingId;
        }

        long position() {
            return position;
        }
    }
}
