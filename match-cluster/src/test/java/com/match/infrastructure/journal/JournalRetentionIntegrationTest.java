// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.application.engine.MeIdentityTestAccess;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The retention command against a real journal archive: a live recording written by the real writer agent
 * (64 KiB segments), purged by {@code (meEpoch, appliedJournalSeq)}, and replayed afterwards. The property: after a
 * purge every record with {@code journalSeq > applied} is still replayable from the recording's new start.
 *
 * <p>The writer is restarted in the middle. The new incarnation records on a new recording whose positions start
 * again at 0; a run that arrives before that recording has a checkpoint purges nothing, and later runs select only
 * the new recording's checkpoints.</p>
 */
public class JournalRetentionIntegrationTest {

    private static final int TEST_CONTROL_PORT = 18920;
    private static final int TEST_STREAM_ID = 4001;
    private static final String TEST_CHANNEL = "aeron:ipc?term-length=64k";
    private static final int SEGMENT_LENGTH = 64 * 1024;
    private static final long EPOCH = 7_700_000_001L;

    private Path tmpDir;
    private MediaDriver driver;
    private Archive archive;
    private AgentRunner writerRunner;
    private SettlementJournal journal;
    private JournalWriterAgent agent;
    private long written;

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
        journal.bindIdentity(MeIdentityTestAccess.restored(EPOCH, 2, 2));
        startWriter();
    }

    private void startWriter() {
        agent = new JournalWriterAgent(
                journal.ringBuffer(),
                this::clientContext,
                TEST_CHANNEL,
                TEST_STREAM_ID,
                new JournalSidecarV2(tmpDir),
                new JournalHeadFile(tmpDir),
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

    /**
     * Appends trades with journalSeq {@code written + 1 .. upTo} (egressSeq far above, as a log position is) and
     * waits until the current writer has offered them all. On the way the head file is never ahead of what was
     * appended.
     */
    private void writeUpTo(final long upTo) throws InterruptedException {
        final long before = agent.writtenEntries();
        final long first = written + 1;
        for (long s = first; s <= upTo; s++) {
            journal.appendTrade(s, 50_000_000L + s * 4_096, s, 1, 11L, 900_001L, 22L, 900_002L, 1_000L, 1L, true,
                    s, 7_011L, 7_022L);
            written = s;
            if (s % 97 == 0) {
                final JournalHeadFile.Head head = JournalHeadFile.read(tmpDir);
                if (head != null && head.journalSeq() > written) {
                    throw new AssertionError("the head file is ahead of the journal: head journalSeq "
                            + head.journalSeq() + ", appended " + written);
                }
            }
        }
        final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (agent.writtenEntries() < before + (upTo - first + 1)) {
            assertTrue("writer did not drain up to journalSeq " + upTo, System.currentTimeMillis() < deadline);
            Thread.sleep(5);
        }
    }

    private int run(final ByteArrayOutputStream err, final String... args) {
        return JournalRetention.run(args, System.out, new PrintStream(err));
    }

    private int retention(final long epoch, final long applied, final String... more) {
        final List<String> args = new ArrayList<>(List.of("--journal-node-dir", tmpDir.toString(), "--aeron-dir",
                driver.aeronDirectoryName(), "--archive-control-port", Integer.toString(TEST_CONTROL_PORT),
                "--me-epoch", Long.toString(epoch), "--applied-journal-seq", Long.toString(applied)));
        args.addAll(List.of(more));
        return run(new ByteArrayOutputStream(), args.toArray(new String[0]));
    }

    /** {@code {recordingId, startPosition, stopPosition}} of every journal recording, in catalog order. */
    private List<long[]> recordings() {
        final List<long[]> out = new ArrayList<>();
        try (AeronArchive client = AeronArchive.connect(clientContext())) {
            client.listRecordingsForUri(0, 16, "aeron:ipc", TEST_STREAM_ID,
                    (controlSessionId, correlationId, id, startTimestamp, stopTimestamp, startPosition,
                     stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength,
                     sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
                            out.add(new long[] {id, startPosition, stopPosition}));
        }
        return out;
    }

    /** The journalSeqs replayable from {@code recording}'s start position up to {@code lastSeq}, in order. */
    private List<Long> replay(final long[] recording, final long lastSeq) throws InterruptedException {
        final List<Long> seqs = new ArrayList<>();
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final TradeDecoder trade = new TradeDecoder();
        final FragmentHandler handler = (buffer, offset, length, hdr) -> {
            header.wrap(buffer, offset);
            assertEquals(TradeDecoder.TEMPLATE_ID, header.templateId());
            trade.wrap(buffer, offset + header.encodedLength(), header.blockLength(), header.version());
            assertEquals(EPOCH, trade.meEpoch());
            seqs.add(trade.journalSeq());
        };
        try (AeronArchive client = AeronArchive.connect(clientContext());
             Subscription replay = client.replay(recording[0], recording[1], Long.MAX_VALUE,
                     "aeron:ipc?term-length=64k", 9101)) {
            final long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
            while ((seqs.isEmpty() || seqs.get(seqs.size() - 1) < lastSeq) && System.currentTimeMillis() < deadline) {
                if (replay.poll(handler, 64) == 0) {
                    Thread.sleep(1);
                }
            }
        }
        return seqs;
    }

    /** Every record above {@code applied} up to {@code lastSeq} is replayable, contiguous, from the new start. */
    private void assertEverythingAboveIsReplayable(final long[] recording, final long applied, final long lastSeq)
            throws InterruptedException {
        final List<Long> seqs = replay(recording, lastSeq);
        assertTrue("the replay returned records", !seqs.isEmpty());
        assertEquals("the tail record survives", lastSeq, (long) seqs.get(seqs.size() - 1));
        for (int i = 1; i < seqs.size(); i++) {
            assertEquals("contiguous journalSeq in the replay", seqs.get(i - 1) + 1, (long) seqs.get(i));
        }
        if (seqs.get(0) > applied + 1) {
            throw new AssertionError("purged records above the applied journalSeq: the replay starts at journalSeq "
                    + seqs.get(0) + ", applied " + applied);
        }
    }

    @Test
    public void purgesByAppliedJournalSeqAndKeepsEveryRecordAboveItAcrossAWriterRestart() throws Exception {
        // 1. the first incarnation: about five segments
        writeUpTo(2_400);
        final long[] first = recordings().get(0);
        assertEquals("one live recording", AeronArchive.NULL_POSITION, first[2]);
        final JournalHeadFile.Head head = JournalHeadFile.read(tmpDir);
        assertNotNull(head);
        assertEquals(EPOCH, head.meEpoch());
        assertEquals(first[0], head.recordingId());
        assertTrue("the head is not ahead of the journal: " + head, head.journalSeq() <= 2_400);
        final List<JournalSidecarV2.Checkpoint> checkpoints = JournalSidecarV2.readAll(tmpDir).checkpoints;
        assertTrue("one checkpoint per segment: " + checkpoints.size(), checkpoints.size() > 3);
        for (final JournalSidecarV2.Checkpoint c : checkpoints) {
            assertEquals(first[0], c.recordingId());
            assertEquals(EPOCH, c.meEpoch());
            assertEquals("the checkpoint carries the frame's own numbers", 50_000_000L + c.journalSeq() * 4_096,
                    c.egressSeq());
        }

        // the form this tool had before is a usage error and purges nothing
        final ByteArrayOutputStream oldForm = new ByteArrayOutputStream();
        assertEquals("the old positional invocation exits 64", 64, run(oldForm, tmpDir.toString(),
                driver.aeronDirectoryName(), Integer.toString(TEST_CONTROL_PORT), "1500"));
        assertTrue(oldForm.toString(), oldForm.toString().contains("positional arguments are no longer accepted"));
        // wrong units and a wrong epoch are refused, a dry run purges nothing
        assertEquals(65, retention(EPOCH, 50_000_000L + 1_500L * 4_096));
        assertEquals(65, retention(EPOCH - 1, 1_500));
        assertEquals(0, retention(EPOCH, 1_500, "--dry-run"));
        assertEquals("nothing was purged so far", 0, recordings().get(0)[1]);

        // the purge
        assertEquals(0, retention(EPOCH, 1_500));
        final long[] purged = recordings().get(0);
        assertTrue("the start position advanced: " + purged[1], purged[1] > 0);
        assertEquals(0, purged[1] % SEGMENT_LENGTH);
        assertEverythingAboveIsReplayable(purged, 1_500, 2_400);
        // the same run again finds nothing more
        assertEquals(0, retention(EPOCH, 1_500));
        assertEquals(purged[1], recordings().get(0)[1]);

        // 2. the writer restarts: a new recording, positions from 0 again
        CloseHelper.quietClose(writerRunner);
        startWriter();
        // a run before the new recording has a checkpoint: nothing qualifies, nothing is purged, exit 0
        assertEquals(0, retention(EPOCH, 2_300));
        writeUpTo(6_000);
        final List<long[]> both = recordings();
        assertEquals("two recordings: " + both.size(), 2, both.size());
        final long[] stopped = both.get(0);
        final long[] active = both.get(1);
        assertTrue("the first incarnation's recording is stopped", stopped[2] != AeronArchive.NULL_POSITION);
        assertEquals("the new recording starts at 0", 0, active[1]);
        assertEquals(active[0], JournalHeadFile.read(tmpDir).recordingId());

        // a cursor inside the stopped recording qualifies no checkpoint of the active one
        assertEquals(0, retention(EPOCH, 2_390));
        assertEquals(0, recordings().get(1)[1]);

        // a cursor inside the active recording purges it, by its own checkpoints only
        assertEquals(0, retention(EPOCH, 4_800));
        final long[] activePurged = recordings().get(1);
        assertTrue("the active recording's start advanced: " + activePurged[1], activePurged[1] > 0);
        assertEverythingAboveIsReplayable(activePurged, 4_800, 6_000);
        assertEquals("the stopped recording is left as it was", stopped[1], recordings().get(0)[1]);

        // an unreachable archive is a runtime failure, not a refusal
        final ByteArrayOutputStream unreachable = new ByteArrayOutputStream();
        assertEquals(1, run(unreachable, "--journal-node-dir", tmpDir.toString(), "--aeron-dir",
                driver.aeronDirectoryName(), "--archive-control-port", Integer.toString(TEST_CONTROL_PORT + 7),
                "--me-epoch", Long.toString(EPOCH), "--applied-journal-seq", "4800"));
        assertTrue(unreachable.toString(), unreachable.toString().contains("FAILED"));
    }
}
