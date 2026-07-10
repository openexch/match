// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

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
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Real-archive proof that {@link JournalRetention#purgeBelow} actually reclaims segment files on a LIVE
 * recording and that replay from the new start position still works and loses nothing at or above the
 * watermark. Mirrors {@link JournalWriterIntegrationTest}'s embedded setup (random tmp media driver +
 * temp archive dir + high test-only control port), scaled up to write &gt; 2 small (64k) segments so
 * there is something to purge.
 *
 * <p>The money-safety property under test: after purging below {@code safeEgressSeq}, EVERY journal
 * entry whose egressSeq is at/above the watermark is still replayable (none were deleted), while
 * low-egressSeq segments below it were physically removed.
 */
public class JournalRetentionIntegrationTest {

    private static final int TEST_CONTROL_PORT = 18920;
    private static final int TEST_STREAM_ID = 4001;
    private static final String TEST_CHANNEL = "aeron:ipc?term-length=64k";
    private static final int SEGMENT_LENGTH = 64 * 1024; // 64k: many small segments from a modest write

    private static final int TRADES = 3200;   // ~6 segments at ~128 bytes/fragment
    private static final long WATERMARK = 1500; // purge egressSeq < 1500; keep 1500..TRADES

    private Path tmpDir;
    private MediaDriver driver;
    private Archive archive;
    private AgentRunner writerRunner;
    private SettlementJournal journal;
    private JournalWriterAgent agent;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("journal-retention-test");
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
                .segmentFileLength(SEGMENT_LENGTH));

        journal = new SettlementJournal(1 << 18);
        agent = new JournalWriterAgent(
                journal.ringBuffer(),
                this::clientContext,
                TEST_CHANNEL,
                TEST_STREAM_ID,
                new JournalCheckpointFile(tmpDir),
                SEGMENT_LENGTH);
        writerRunner = new AgentRunner(new BusySpinIdleStrategy(),
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
    public void purgesLowSegmentsAndKeepsEverythingAtOrAboveWatermark() throws Exception {
        // Write enough trades (egressSeq == tradeId == i) to span several 64k segments.
        for (int i = 1; i <= TRADES; i++) {
            journal.appendTrade(i, i, 1, 11L, 900_001L, 22L, 900_002L, 1_000L, 1L, true, i, 7_011L, 7_022L);
        }
        final long drainDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (agent.writtenEntries() < TRADES) {
            assertTrue("writer did not drain " + TRADES + " entries in time",
                    System.currentTimeMillis() < drainDeadline);
            Thread.sleep(5);
        }

        final File archiveDir = new File(tmpDir.toFile(), "archive");
        final int segmentsBefore = countSegmentFiles(archiveDir);
        assertTrue("test must produce > 2 segments to be meaningful (got " + segmentsBefore + ")",
                segmentsBefore > 2);

        // Purge below the watermark through a fresh client — the same access pattern the admin CLI uses.
        final long bytesReclaimed;
        try (AeronArchive purgeArchive = AeronArchive.connect(clientContext())) {
            bytesReclaimed = JournalRetention.purgeBelow(purgeArchive, tmpDir, WATERMARK);
        }
        assertTrue("expected some bytes reclaimed, got " + bytesReclaimed, bytesReclaimed > 0);

        final int segmentsAfter = countSegmentFiles(archiveDir);
        assertTrue("segment files must decrease (" + segmentsBefore + " -> " + segmentsAfter + ")",
                segmentsAfter < segmentsBefore);

        // Replay from the recording's NEW start position and prove nothing >= WATERMARK was lost.
        try (AeronArchive replayArchive = AeronArchive.connect(clientContext())) {
            final long[] recId = {Aeron.NULL_VALUE};
            final long[] newStart = {0};
            replayArchive.listRecordingsForUri(0, 16, "aeron:ipc", TEST_STREAM_ID,
                    (controlSessionId, correlationId, id, startTimestamp, stopTimestamp, startPosition,
                     stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength,
                     sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) -> {
                        recId[0] = id;
                        newStart[0] = startPosition;
                    });
            assertTrue("recording must still exist", recId[0] != Aeron.NULL_VALUE);
            assertTrue("start position must have advanced past 0 after purge", newStart[0] > 0);

            final int[] countAtOrAboveWatermark = {0};
            final long[] maxEgress = {-1};
            final MessageHeaderDecoder header = new MessageHeaderDecoder();
            final JournalTradeDecoder trade = new JournalTradeDecoder();
            final FragmentHandler handler = (buffer, offset, length, hdr) -> {
                final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                buffer.getBytes(offset, copy, 0, length);
                header.wrap(copy, 0);
                if (header.templateId() == JournalTradeDecoder.TEMPLATE_ID) {
                    trade.wrap(copy, header.encodedLength(), header.blockLength(), header.version());
                    final long egress = trade.egressSeq();
                    if (egress >= WATERMARK) {
                        countAtOrAboveWatermark[0]++;
                    }
                    if (egress > maxEgress[0]) {
                        maxEgress[0] = egress;
                    }
                }
            };

            try (Subscription replay = replayArchive.replay(
                    recId[0], newStart[0], Long.MAX_VALUE, "aeron:ipc?term-length=64k", 9101)) {
                final long replayDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
                // Replay is ordered from newStart; the tail entry is egressSeq == TRADES.
                while (maxEgress[0] < TRADES && System.currentTimeMillis() < replayDeadline) {
                    if (replay.poll(handler, 64) == 0) {
                        Thread.sleep(1);
                    }
                }
            }

            assertEquals("the tail entry (egressSeq == TRADES) must survive the purge",
                    TRADES, maxEgress[0]);
            // Every entry with egressSeq in [WATERMARK, TRADES] must still be replayable — none purged.
            assertEquals("no entry at/above the watermark may be purged",
                    (int) (TRADES - WATERMARK + 1), countAtOrAboveWatermark[0]);
        }
    }

    private static int countSegmentFiles(final File archiveDir) {
        final File[] files = archiveDir.listFiles((dir, name) -> name.endsWith(".rec"));
        return files == null ? 0 : files.length;
    }
}
