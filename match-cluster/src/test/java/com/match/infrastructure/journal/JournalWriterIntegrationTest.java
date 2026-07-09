// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.journal.generated.JournalTerminalDecoder;
import com.match.infrastructure.journal.generated.JournalTradeDecoder;
import com.match.infrastructure.journal.generated.MessageHeaderDecoder;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proof of the journal's write half: service-thread appends -> ring ->
 * {@link JournalWriterAgent} -> recorded publication on a real (test-local) Archive ->
 * positioned REPLAY reads back exactly what was appended, in order. The replay path here
 * is the same one the Assets-Engine settlement bridge will use.
 *
 * Fully isolated from the live stack: embedded media driver in a random tmp dir, archive
 * in a JUnit temp dir, high test-only control port.
 */
public class JournalWriterIntegrationTest {

    private static final int TEST_CONTROL_PORT = 18910;
    private static final int TEST_STREAM_ID = 4001;
    private static final String TEST_CHANNEL = "aeron:ipc?term-length=64k";
    private static final long SEGMENT_LENGTH = 1024 * 1024;

    private Path tmpDir;
    private MediaDriver driver;
    private Archive archive;
    private AgentRunner writerRunner;
    private SettlementJournal journal;
    private JournalWriterAgent agent;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("journal-writer-test");
        driver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true));
        archive = Archive.launch(new Archive.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .archiveDir(new File(tmpDir.toFile(), "archive"))
                .controlChannel("aeron:udp?endpoint=localhost:" + TEST_CONTROL_PORT)
                .controlStreamId(4010)
                .localControlStreamId(4011)
                .replicationChannel("aeron:udp?endpoint=localhost:0")
                .recordingEventsEnabled(false)
                .threadingMode(ArchiveThreadingMode.SHARED)
                .fileSyncLevel(0)   // test speed; production uses 1
                .segmentFileLength((int) SEGMENT_LENGTH));

        journal = new SettlementJournal(1 << 16);
        agent = new JournalWriterAgent(
                journal.ringBuffer(),
                this::clientContext,
                TEST_CHANNEL,
                TEST_STREAM_ID,
                new JournalCheckpointFile(tmpDir),
                SEGMENT_LENGTH);
        writerRunner = new AgentRunner(new SleepingMillisIdleStrategy(1),
                Throwable::printStackTrace, null, agent);
        AgentRunner.startOnThread(writerRunner);
    }

    private AeronArchive.Context clientContext() {
        return new AeronArchive.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .controlRequestChannel("aeron:udp?endpoint=localhost:" + TEST_CONTROL_PORT)
                .controlRequestStreamId(4010)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0");
    }

    @After
    public void tearDown() {
        CloseHelper.quietCloseAll(writerRunner, archive, driver);
        IoUtil.delete(tmpDir.toFile(), true);
    }

    @Test
    public void appendedEntriesAreRecordedAndReplayableInOrder() throws Exception {
        journal.appendTrade(500L, 1L, 1, 11L, 900_001L, 22L, 900_002L, 1_000L, 2_000L, true, 42L);
        journal.appendTrade(510L, 2L, 1, 33L, 900_003L, 22L, 900_002L, 1_100L, 900L, false, 43L);
        journal.appendTerminal(510L, 22L, 900_002L, 1, 2, 44L);

        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (agent.writtenEntries() < 3) {
            assertTrue("writer agent did not drain 3 entries in time", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }

        // Replay the recording from position 0 through a fresh archive client — the same
        // access pattern the settlement bridge uses.
        final List<long[]> seen = new ArrayList<>(); // {templateId, egressSeq, entryId}
        try (AeronArchive replayArchive = AeronArchive.connect(clientContext())) {
            final long[] recordingId = {Aeron.NULL_VALUE};
            replayArchive.listRecordingsForUri(0, 16, "aeron:ipc", TEST_STREAM_ID,
                    (controlSessionId, correlationId, recId, startTimestamp, stopTimestamp, startPosition,
                     stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength,
                     sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
                            recordingId[0] = recId);
            assertTrue("journal recording must exist", recordingId[0] != Aeron.NULL_VALUE);

            final MessageHeaderDecoder header = new MessageHeaderDecoder();
            final JournalTradeDecoder trade = new JournalTradeDecoder();
            final JournalTerminalDecoder terminal = new JournalTerminalDecoder();
            final FragmentHandler handler = (buffer, offset, length, hdr) -> {
                final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                buffer.getBytes(offset, copy, 0, length);
                header.wrap(copy, 0);
                if (header.templateId() == JournalTradeDecoder.TEMPLATE_ID) {
                    trade.wrap(copy, header.encodedLength(), header.blockLength(), header.version());
                    seen.add(new long[] {header.templateId(), trade.egressSeq(), trade.tradeId()});
                } else {
                    terminal.wrap(copy, header.encodedLength(), header.blockLength(), header.version());
                    seen.add(new long[] {header.templateId(), terminal.egressSeq(), terminal.orderId()});
                }
            };

            try (Subscription replay = replayArchive.replay(
                    recordingId[0], 0, Long.MAX_VALUE, "aeron:ipc?term-length=64k", 9001)) {
                final long replayDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
                while (seen.size() < 3 && System.currentTimeMillis() < replayDeadline) {
                    if (replay.poll(handler, 16) == 0) {
                        Thread.sleep(1);
                    }
                }
            }
        }

        assertEquals(3, seen.size());
        assertEquals(JournalTradeDecoder.TEMPLATE_ID, (int) seen.get(0)[0]);
        assertEquals(500L, seen.get(0)[1]);
        assertEquals(1L, seen.get(0)[2]);
        assertEquals(JournalTradeDecoder.TEMPLATE_ID, (int) seen.get(1)[0]);
        assertEquals(510L, seen.get(1)[1]);
        assertEquals(2L, seen.get(1)[2]);
        assertEquals(JournalTerminalDecoder.TEMPLATE_ID, (int) seen.get(2)[0]);
        assertEquals(510L, seen.get(2)[1]);
        assertEquals(22L, seen.get(2)[2]);
    }
}
