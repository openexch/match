// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RC2 S1 review (independent verifier, check 5): the retention watermark is the egressSeq the
 * writer reads out of each frame at {@link JournalWriterAgent#EGRESS_SEQ_OFFSET}. Schema 4 moved
 * that field from frame offset 8 to 24; a stale offset would checkpoint {@code journalSeq} (0 in
 * S1) and {@code JournalRetention} would then purge every segment. This pins the checkpointed
 * value on the first entry of a fresh writer AND on the first entry after a writer RESTART (a
 * new agent, a reopened checkpoint file, a new recorded publication), which no other test covers.
 *
 * The agent is driven on the test thread, as {@link JournalWriterReconnectTest} does.
 */
public class JournalWriterRestartCheckpointTest {

    private static final int TEST_CONTROL_PORT = 18912;
    private static final int ARCHIVE_CONTROL_STREAM_ID = 4030;
    private static final int ARCHIVE_LOCAL_CONTROL_STREAM_ID = 4031;
    private static final int TEST_STREAM_ID = 4003;
    private static final String TEST_CHANNEL = "aeron:ipc?term-length=64k";
    private static final long SEGMENT_LENGTH = 1024 * 1024;

    private Path tmpDir;
    private MediaDriver driver;
    private Archive archive;
    private SettlementJournal journal;
    private JournalWriterAgent agent;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("journal-writer-restart-test");
        driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
        archive = Archive.launch(new Archive.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .archiveDir(new File(tmpDir.toFile(), "archive"))
                .controlChannel("aeron:udp?endpoint=localhost:" + TEST_CONTROL_PORT)
                .controlStreamId(ARCHIVE_CONTROL_STREAM_ID)
                .localControlStreamId(ARCHIVE_LOCAL_CONTROL_STREAM_ID)
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .fileSyncLevel(0)
                .segmentFileLength((int) SEGMENT_LENGTH));
        journal = new SettlementJournal(1 << 16);
        agent = newAgent();
    }

    private JournalWriterAgent newAgent() {
        return new JournalWriterAgent(
                journal.ringBuffer(),
                this::clientContext,
                TEST_CHANNEL,
                TEST_STREAM_ID,
                new JournalCheckpointFile(tmpDir),
                SEGMENT_LENGTH);
    }

    private AeronArchive.Context clientContext() {
        return new AeronArchive.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .controlRequestChannel("aeron:udp?endpoint=localhost:" + TEST_CONTROL_PORT)
                .controlRequestStreamId(ARCHIVE_CONTROL_STREAM_ID)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0");
    }

    @After
    public void tearDown() {
        agent.onClose();
        CloseHelper.quietCloseAll(archive, driver);
        IoUtil.delete(tmpDir.toFile(), true);
    }

    private void driveUntil(final BooleanSupplier done, final long deadlineMs) throws InterruptedException {
        while (!done.getAsBoolean()) {
            assertTrue("timed out driving the writer agent", System.currentTimeMillis() < deadlineMs);
            agent.doWork();
            if (!done.getAsBoolean()) {
                Thread.sleep(1);
            }
        }
    }

    @Test
    public void checkpointCarriesTheFrameEgressSeqBeforeAndAfterAWriterRestart() throws Exception {
        // 1) Fresh writer: the first entry (a record 2 Terminal, egressSeq 500) is checkpointed.
        journal.appendTerminal(500L, 11L, 900_001L, 1, 2, 42L, 7_011L, 0);
        driveUntil(() -> agent.writtenEntries() >= 1,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10));
        List<long[]> checkpoints = JournalCheckpointFile.readAll(tmpDir);
        assertEquals("one checkpoint after the first entry", 1, checkpoints.size());
        assertEquals("checkpoint egressSeq is the frame's egressSeq @24, not journalSeq @8",
                500L, checkpoints.get(0)[0]);
        assertTrue("checkpoint position is after the frame", checkpoints.get(0)[1] > 0);

        // 2) Restart: close the agent (publication, archive client, checkpoint file), build a new one
        //    on the same ring and journal dir. Its lastCheckpointedSegmentBase starts at -1, so the
        //    first entry it writes (a record 1 Trade, egressSeq 777) must checkpoint again.
        agent.onClose();
        agent = newAgent();
        journal.appendTrade(777L, 1L, 1, 11L, 900_001L, 22L, 900_002L, 1_000L, 2_000L, true, 43L, 7_011L, 7_022L);
        driveUntil(() -> agent.writtenEntries() >= 1,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10));
        checkpoints = JournalCheckpointFile.readAll(tmpDir);
        assertEquals("the restarted writer appended a second checkpoint", 2, checkpoints.size());
        assertEquals("post-restart checkpoint egressSeq is the Trade's egressSeq (same head offset on record 1)",
                777L, checkpoints.get(1)[0]);
        assertTrue(checkpoints.get(1)[1] > 0);
    }
}
