// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import io.aeron.ExclusivePublication;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the C-2 fix: when the recorded publication goes CLOSED under the writer, the entry
 * in flight must NOT be dropped. The agent closes and nulls the publication (so the next
 * doWork reconnects a fresh recorded publication), counts a reconnect, and ABORTs the ring
 * read so the entry stays buffered and is written after reconnect. Never drop.
 *
 * The agent is driven synchronously on the test thread (no AgentRunner) so the CLOSED path is
 * deterministic: a real ExclusivePublication returns Publication.CLOSED once closed, and there
 * is no concurrent offer racing the close. Publication.offer is final, so a real (closed)
 * publication is the only faithful CLOSED source; there is no mock framework on the classpath.
 */
public class JournalWriterReconnectTest {

    private static final int TEST_CONTROL_PORT = 18911;
    private static final int ARCHIVE_CONTROL_STREAM_ID = 4020;
    private static final int ARCHIVE_LOCAL_CONTROL_STREAM_ID = 4021;
    private static final int TEST_STREAM_ID = 4002;
    private static final String TEST_CHANNEL = "aeron:ipc?term-length=64k";
    private static final long SEGMENT_LENGTH = 1024 * 1024;

    private Path tmpDir;
    private MediaDriver driver;
    private Archive archive;
    private SettlementJournal journal;
    private JournalWriterAgent agent;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("journal-writer-reconnect-test");
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
                .fileSyncLevel(0)   // test speed; production uses 1
                .segmentFileLength((int) SEGMENT_LENGTH));

        journal = new SettlementJournal(1 << 16);
        // Deliberately NO AgentRunner: this test drives doWork() itself for determinism.
        agent = new JournalWriterAgent(
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

    /** Drive doWork() on the test thread until the predicate holds or the deadline passes. */
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
    public void closedPublicationReconnectsAndNeverDropsTheEntry() throws Exception {
        // 1) First entry drains through a connected recorded publication (the COMMIT path).
        journal.appendTerminal(500L, 11L, 900_001L, 1, 2, 42L, 7_011L);
        final long connectDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        driveUntil(() -> agent.writtenEntries() >= 1, connectDeadline);
        assertEquals(1, agent.writtenEntries());
        assertEquals("no reconnects on the happy path", 0, agent.reconnects());

        // 2) Force the live publication CLOSED out from under the agent. Publication.offer is
        //    final and there is no mock framework, so a real closed publication is the only
        //    faithful way to produce Publication.CLOSED.
        final Field publicationField = JournalWriterAgent.class.getDeclaredField("publication");
        publicationField.setAccessible(true);
        final ExclusivePublication live = (ExclusivePublication) publicationField.get(agent);
        assertNotNull("publication must be connected before we close it", live);
        live.close();

        // 3) Next entry: the handler hits CLOSED, so it must null the publication (for reconnect),
        //    count exactly one reconnect, and ABORT, leaving the entry buffered and unwritten.
        journal.appendTerminal(510L, 22L, 900_002L, 1, 2, 43L, 7_022L);
        agent.doWork();
        assertNull("publication nulled so the next doWork reconnects", publicationField.get(agent));
        assertEquals("exactly one reconnect counted", 1, agent.reconnects());
        assertEquals("aborted entry not yet written", 1, agent.writtenEntries());
        assertTrue("aborted entry still buffered in the ring (not consumed)",
                journal.ringBuffer().size() > 0);

        // Skip the 1s connect backoff so the reconnect is prompt and the test stays fast.
        final Field lastConnectAttemptMs = JournalWriterAgent.class.getDeclaredField("lastConnectAttemptMs");
        lastConnectAttemptMs.setAccessible(true);
        lastConnectAttemptMs.setLong(agent, 0L);

        // 4) Keep driving: reconnect + retry writes the preserved entry. Never dropped.
        final long recoverDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        driveUntil(() -> agent.writtenEntries() >= 2, recoverDeadline);
        assertEquals("preserved entry recorded after reconnect", 2, agent.writtenEntries());
        assertEquals("ring fully drained after recovery", 0, journal.ringBuffer().size());
        assertEquals("still exactly one reconnect (no spurious reconnects)", 1, agent.reconnects());
    }
}
