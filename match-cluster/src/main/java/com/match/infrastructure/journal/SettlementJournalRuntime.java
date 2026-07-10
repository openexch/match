// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import com.match.infrastructure.Logger;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import org.agrona.CloseHelper;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;

import java.io.File;
import java.nio.file.Path;

/**
 * Owns the settlement journal's moving parts on one node: the SPSC ring
 * ({@link SettlementJournal}, written by the cluster service thread), the writer agent
 * ({@link JournalWriterAgent}, its own AgentRunner thread), and the SECOND, disk-backed
 * Archive that records the journal publication.
 *
 * Two-step lifecycle because the journal Archive is a media-driver client:
 * {@link #createIfEnabled} parses env and builds the driver-independent parts (so the
 * ring can be armed on the event publisher before launch), then {@link #start} launches
 * the Archive + writer thread once the node's media driver is available.
 *
 * Env:
 *   SETTLEMENT_JOURNAL_ENABLED  - "true" to enable (default false = dark, zero behavior change)
 *   SETTLEMENT_JOURNAL_DIR      - REQUIRED when enabled. Must be a DISK path (never tmpfs):
 *                                 this journal is the money-settlement record and the
 *                                 Assets-Engine rebuild input; /dev/shm dies with power.
 *   SETTLEMENT_JOURNAL_RING_BYTES - ring capacity, power of two (default 64MB)
 */
public final class SettlementJournalRuntime implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SettlementJournalRuntime.class);

    private static final int DEFAULT_RING_BYTES = 64 * 1024 * 1024;
    private static final int SEGMENT_FILE_LENGTH = 64 * 1024 * 1024;

    private final int nodeId;
    private final int controlPort;
    private final Path journalDir;
    private final SettlementJournal journal;
    private final JournalCheckpointFile checkpoints;

    private Archive journalArchive;
    private AgentRunner writerRunner;

    private SettlementJournalRuntime(final int nodeId, final int portBase, final Path journalDir, final int ringBytes) {
        this.nodeId = nodeId;
        this.controlPort = portBase + nodeId * 100 + InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET;
        this.journalDir = journalDir;
        this.journal = new SettlementJournal(ringBytes);
        this.checkpoints = new JournalCheckpointFile(journalDir);
    }

    /** @return the runtime, or null when SETTLEMENT_JOURNAL_ENABLED is not "true" (journal dark). */
    public static SettlementJournalRuntime createIfEnabled(final int nodeId, final int portBase) {
        if (!"true".equalsIgnoreCase(System.getenv("SETTLEMENT_JOURNAL_ENABLED"))) {
            return null;
        }
        final String dir = System.getenv("SETTLEMENT_JOURNAL_DIR");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("SETTLEMENT_JOURNAL_ENABLED=true requires SETTLEMENT_JOURNAL_DIR "
                    + "(a DISK path — never tmpfs: this is the money-settlement journal)");
        }
        final String ringEnv = System.getenv("SETTLEMENT_JOURNAL_RING_BYTES");
        final int ringBytes = ringEnv == null || ringEnv.isBlank() ? DEFAULT_RING_BYTES : Integer.parseInt(ringEnv);
        final Path journalDir = Path.of(dir).resolve("node" + nodeId);
        log.info("Settlement journal ENABLED: dir=" + journalDir + " ring=" + ringBytes
                + " controlPort=" + (portBase + nodeId * 100 + InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET));
        return new SettlementJournalRuntime(nodeId, portBase, journalDir, ringBytes);
    }

    /** The ring facade to arm on the event publisher (safe before {@link #start}: the ring buffers). */
    public SettlementJournal journal() {
        return journal;
    }

    /**
     * Launch the journal Archive (a client of the node's media driver) and the writer thread.
     * Call once the media driver at {@code aeronDirectoryName} is up. Idempotence not needed:
     * bootstrap calls this exactly once per process.
     */
    public SettlementJournalRuntime start(final String aeronDirectoryName, final ErrorHandler errorHandler) {
        final String controlChannel = "aeron:udp?endpoint=localhost:" + controlPort;

        // Second Archive on the SAME media driver as the consensus archive: distinct control
        // port AND distinct control/local-control stream ids (the local-control IPC channel is
        // shared driver-wide — identical stream ids would collide with the consensus archive).
        // fileSyncLevel(1): fsync on commit — this archive is the money journal; its write rate
        // (trades + terminals, ~100 bytes each) is trivial next to the consensus log, so SHARED
        // threading + backoff idle keeps it off the busy cores.
        final Archive.Context ctx = new Archive.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .archiveDir(new File(journalDir.toFile(), "archive"))
                .controlChannel(controlChannel)
                .controlStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_STREAM_ID)
                .localControlChannel("aeron:ipc?term-length=1m")
                .localControlStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_LOCAL_CONTROL_STREAM_ID)
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .idleStrategySupplier(BackoffIdleStrategy::new)
                .fileSyncLevel(1)
                .catalogFileSyncLevel(1)
                .segmentFileLength(SEGMENT_FILE_LENGTH)
                .errorHandler(errorHandler);

        journalArchive = Archive.launch(ctx);

        final JournalWriterAgent agent = new JournalWriterAgent(
                journal.ringBuffer(),
                () -> new AeronArchive.Context()
                        .aeronDirectoryName(aeronDirectoryName)
                        .controlRequestChannel(controlChannel)
                        .controlRequestStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_STREAM_ID)
                        .controlResponseChannel("aeron:udp?endpoint=localhost:0"),
                InfrastructureConstants.SETTLEMENT_JOURNAL_CHANNEL,
                InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID,
                checkpoints,
                SEGMENT_FILE_LENGTH);

        writerRunner = new AgentRunner(new BackoffIdleStrategy(), errorHandler, null, agent);
        AgentRunner.startOnThread(writerRunner);
        log.info("Settlement journal writer started (node " + nodeId + ")");
        return this;
    }

    @Override
    public void close() {
        // Runner first (stops the agent -> closes publication/archive-client/checkpoints),
        // then the Archive itself.
        CloseHelper.quietClose(writerRunner);
        CloseHelper.quietClose(journalArchive);
    }
}
