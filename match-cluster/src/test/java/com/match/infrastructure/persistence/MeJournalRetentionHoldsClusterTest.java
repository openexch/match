// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.match.infrastructure.persistence.MeRetentionStory.Held;
import com.match.infrastructure.persistence.MeRetentionStory.Pusher;
import com.match.infrastructure.persistence.MeRetentionStory.Reader;
import com.openexchange.cluster.OffBoxWatermark;
import com.openexchange.cluster.journal.JournalReaders;
import com.openexchange.cluster.journal.JournalRetention;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.match.infrastructure.persistence.MeRetentionStory.GENESIS;
import static com.match.infrastructure.persistence.MeRetentionStory.JOURNAL;
import static com.match.infrastructure.persistence.MeRetentionStory.SEG;
import static com.match.infrastructure.persistence.MeRetentionStory.expectedStart;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every way a matching engine member holds its settlement journal, and the way out of each: no reader, a required
 * reader without a file, a file of another genesis, a cursor above the member's tail, a reader that died (alarms,
 * an operator's release, a late set), and the off-box bound. A hold never purges and never takes the member out of
 * {@code /ready}; after every recovery the start position advances again.
 */
public class MeJournalRetentionHoldsClusterTest {

    private static final String G = String.valueOf(GENESIS);

    /** The greatest segment base of {@code records} whose first record qualifies, capped at the clamp. */
    private static long baseFor(final List<Held> records, final long end, final java.util.function.Predicate<Held> ok) {
        long best = 0;
        for (final Held r : records) {
            if (r.position() % SEG == 0 && r.position() <= expectedStart(end) && ok.test(r)) {
                best = Math.max(best, r.position());
            }
        }
        return best;
    }

    /** Two more passes, then: no member's journal recording has moved its start and nothing was counted purged. */
    private static void assertNothingPurged(final MeRetentionStory s, final String when) {
        s.pumpMs(2_500);
        for (final Member m : s.h.members) {
            assertTrue(when + ": member " + m.id + " recorded more than three segments",
                    s.active(m).end() > 3L * SEG);
            assertEquals(when + ": member " + m.id + " purged nothing", 0, s.active(m).start());
            assertEquals(when + ": member " + m.id, 0, s.metric(m, "purged_bytes_total"));
        }
    }

    private static void awaitHealth(final MeRetentionStory s, final String text) {
        s.await(() -> s.live().stream().allMatch(m -> m.service.probe().detail().contains(text)), 30_000,
                () -> "'" + text + "' on /health of every member: " + s.h.members[0].service.probe().detail() + " "
                        + s.describe());
    }

    @Test(timeout = 600_000)
    public void everyHoldHoldsSaysSoAndRecovers() throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(26_100, "mjr-holds", false, "journal.retention.settlement",
                "readers", "journal.retention.poll.ms", "1000", "journal.retention.alarm.bytes", "1048576",
                "journal.retention.stale.ms", "2000", "journal.retention.required.readers.settlement",
                "ct-bridge,oe-marketdata").boot()) {
            // more than three segments recorded before the first hold: a member that did not hold would cut here
            s.orders(1_200);
            final long first = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == first && s.active(m).end() > 3L * SEG),
                    60_000, s::describe);
            final boolean[] readyBefore = new boolean[MeClusterHarness.MEMBERS];
            for (final Member m : s.h.members) {
                readyBefore[m.id] = m.service.probe().ready();
                assertTrue("member " + m.id + " is ready", readyBefore[m.id]);
            }

            // 1. no reader at all: held, CRITICAL, on /health, still ready
            awaitHealth(s, "journal-retention: journal retention settlement NO_READERS");
            assertTrue(s.lines.toString(), s.said("CRITICAL: no reader is registered for the settlement journal"));
            assertNothingPurged(s, "no reader registered");

            // 2. one of the two required readers has no file
            s.setAll("oe-marketdata", 0);
            awaitHealth(s, "journal retention settlement MISSING: the required reader ct-bridge has no file");
            assertNothingPurged(s, "a required reader without a file");

            // 3. a file of another genesis (a cursor that survived a re-genesis)
            for (final Member m : s.h.members) {
                assertEquals(0, s.tool(m, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id",
                        String.valueOf(GENESIS + 1), "--journal", JOURNAL, "--reader", "ct-bridge",
                        "--applied-journal-seq", String.valueOf(first), "--source", "stale-console"));
            }
            // the other reader is at the head too: only the identity of ct-bridge's file holds the journal now
            s.setAll("oe-marketdata", first);
            awaitHealth(s, "journal retention settlement FOREIGN");
            assertTrue(s.lines.toString(), s.said("settlement journal reader file FOREIGN"));
            assertNothingPurged(s, "a reader file of another genesis");
            for (final Member m : s.h.members) {
                assertEquals(0, s.tool(m, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id", G,
                        "--journal", JOURNAL, "--reader", "oe-marketdata", "--applied-journal-seq", "0", "--source",
                        "operator", "--allow-lower"));
            }
            // a set for this genesis over the foreign file is refused: remove first
            assertEquals(65, s.tool(s.f, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id", G,
                    "--journal", JOURNAL, "--reader", "ct-bridge", "--applied-journal-seq", "0", "--source", "c"));
            for (final Member m : s.h.members) {
                assertEquals(0, s.tool(m, "remove", "--genesis-id", String.valueOf(GENESIS + 1), "--journal", JOURNAL,
                        "--reader", "ct-bridge", "--reason", "file of the previous genesis"));
            }
            s.setAll("ct-bridge", 0);

            // 4. a live reader and a dead one: held at the dead reader's cursor, with both alarms
            final Reader live = s.new Reader("ct-bridge");
            final Pusher pusher = s.new Pusher(live, null);
            s.orders(4_800);
            final long head = s.head();
            s.pump(List.of(live), List.of(pusher), 1_000, () -> live.applied == head
                    && s.said("bytes of the settlement journal (above JOURNAL_RETENTION_ALARM_BYTES 1048576)")
                    && s.said("a reader file of the settlement journal was written")
                    && s.live().stream().allMatch(m -> s.status(m).report != null
                            && s.status(m).report.evaluation.state == JournalRetention.State.VALID
                            && s.status(m).report.evaluation.safeSeq == 0 && s.recorded(m) == head), 120_000,
                    () -> "the alarms of a held journal: " + s.describe() + s.lines);
            for (final Member m : s.h.members) {
                assertEquals("member " + m.id + " holds everything for the dead reader", 0, s.active(m).start());
                assertTrue(s.retained(m) > 1_048_576);
                assertTrue(m.service.renderMetrics().contains("match_journal_retention_reader_applied_seq{journal="
                        + "\"settlement\",reader=\"oe-marketdata\"} 0"));
                assertEquals("a hold never changes /ready on member " + m.id, readyBefore[m.id],
                        m.service.probe().ready());
            }
            List<Held> records;
            do {
                records = s.read(s.f, s.active(s.f));
            } while (records == null);
            final long end = s.active(s.f).end();

            // 5. a cursor in the wrong unit, far above the tail: AHEAD, nothing purged; corrected with --allow-lower
            s.setAll("oe-marketdata", head + 1_000_000);
            s.pump(List.of(live), List.of(pusher), 1_000, () -> s.live().stream().allMatch(
                    m -> s.metric(m, "state") == JournalRetention.State.AHEAD.code()), 30_000, s::describe);
            s.pumpMs(2_500);
            for (final Member m : s.h.members) {
                assertEquals("member " + m.id + " purges nothing on a cursor above its tail", 0, s.active(m).start());
                assertEquals(0, s.tool(m, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id", G,
                        "--journal", JOURNAL, "--reader", "oe-marketdata", "--applied-journal-seq", "0", "--source",
                        "operator", "--allow-lower"));
            }
            assertTrue(s.lines.toString(), s.said("reader oe-marketdata of the settlement journal went backwards"));

            // 6. the operator releases the dead reader's hold up to journalSeq 3000, member by member
            for (final Member m : s.h.members) {
                assertEquals(0, s.tool(m, "release", "--genesis-id", G, "--journal", JOURNAL, "--reader",
                        "oe-marketdata", "--up-to-journal-seq", "3000", "--reason", "market history rebuilt"));
            }
            final long released = baseFor(records, end, r -> r.seq() <= 3_001);
            assertTrue("a base below journalSeq 3001: " + released, released > 0);
            s.pump(List.of(live), List.of(pusher), 1_000, () -> s.live().stream().allMatch(
                    m -> s.active(m).start() == released && s.metric(m, "safe_seq") == 3_000), 60_000,
                    () -> "the start " + released + " after the release: " + s.describe());
            assertTrue(s.lines.toString(), s.said("CRITICAL: hold of reader oe-marketdata of the settlement journal"
                    + " released by operator up to journalSeq 3000 (applied 0)"));
            for (final Member m : s.h.members) {
                assertTrue(m.service.renderMetrics().contains("match_journal_retention_reader_released_seq{journal="
                        + "\"settlement\",reader=\"oe-marketdata\"} 3000"));
            }

            // 7. the reader comes back and its cursor passes the release: the release is cleared, the start advances
            s.setAll("oe-marketdata", 3_500);
            for (final Member m : s.h.members) {
                final JournalReaders.Record file = JournalReaders.read(s.clusterDir(m), JOURNAL, "oe-marketdata").record;
                assertEquals(JournalReaders.NO_RELEASE, file.releasedUpToJournalSeq);
                assertEquals("", file.releaseReason);
            }
            final long later = baseFor(records, end, r -> r.seq() <= 3_501);
            assertTrue(later > released);
            s.pump(List.of(live), List.of(pusher), 1_000, () -> s.live().stream().allMatch(
                    m -> s.active(m).start() == later && s.metric(m, "safe_seq") == 3_500), 60_000,
                    () -> "the start " + later + " after the late set: " + s.describe());
            System.out.println("GREEN holds: NO_READERS, MISSING, FOREIGN, dead reader at 0, AHEAD, released to 3000"
                    + " (start " + released + "), cleared by a set at 3500 (start " + later + "): " + s.describe());
        }
    }

    // ------------------------------------------------------------------ the off-box bound

    /** The watermark command run as an archiver runs it: a process of its own on the member's cluster directory. */
    private static void offBoxSet(final MeRetentionStory s, final long logPosition) throws Exception {
        for (final Member m : s.h.members) {
            final List<String> cmd = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m", "-cp",
                    System.getProperty("java.class.path"), "com.openexchange.cluster.OffBoxWatermarkTool", "set",
                    "--cluster-dir", s.clusterDir(m).getAbsolutePath(), "--engine", "match", "--cluster-id", "0",
                    "--genesis-id", G, "--log-position-bytes", Long.toString(logPosition), "--source", "test"));
            final Path out = Files.createTempFile("offbox-tool-", ".log");
            try {
                final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
                assertTrue(p.waitFor(60, TimeUnit.SECONDS));
                assertEquals(Files.readString(out), 0, p.exitValue());
            } finally {
                Files.deleteIfExists(out);
            }
        }
    }

    private static MeRetentionStory offBoxStory(final int port, final String name) throws Exception {
        final MeRetentionStory s = new MeRetentionStory(port, name, false, "journal.retention.settlement", "readers",
                "journal.retention.poll.ms", "1000");
        s.h.beforeLaunch = (m, service) -> service.offBoxModeOverride = OffBoxWatermark.Mode.REQUIRED;
        return s.boot();
    }

    /**
     * With {@code RETENTION_OFFBOX=required} a member holds its journal while it has no off-box watermark, and with
     * one it purges no record whose {@code egressSeq} (the log position of the command that produced it) is above
     * it, although the reader has applied everything.
     */
    @Test(timeout = 600_000)
    public void underOffBoxRequiredNoRecordAboveTheWatermarkIsPurged() throws Exception {
        try (MeRetentionStory s = offBoxStory(26_500, "mjr-offbox")) {
            s.orders(2_600);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head), 60_000, s::describe);
            s.setAll("ct-bridge", head);
            awaitHealth(s, "journal retention settlement OFFBOX_HOLD");
            s.pumpMs(2_500);
            for (final Member m : s.h.members) {
                assertEquals("member " + m.id + " holds without a watermark", 0, s.active(m).start());
            }
            List<Held> records;
            do {
                records = s.read(s.f, s.active(s.f));
            } while (records == null);
            final long end = s.active(s.f).end();
            // the off-box copy ends with the command that produced journalSeq 1300
            final long x = records.get(1_299).logPosition();
            assertEquals(1_300, records.get(1_299).seq());
            assertTrue("the record carries a log position: " + x, x > 0);
            final long atX = baseFor(records, end, r -> r.logPosition() <= x);
            assertTrue("a base below the watermark and below the clamp: " + atX, atX > 0 && atX < expectedStart(end));
            offBoxSet(s, x);
            s.await(() -> s.live().stream().allMatch(m -> s.active(m).start() == atX), 60_000,
                    () -> "the start " + atX + " at the off-box bound: " + s.describe());
            s.pumpMs(2_500); // two more passes: nothing above the watermark goes
            for (final Member m : s.h.members) {
                assertEquals("member " + m.id, atX, s.active(m).start());
                assertEquals(JournalRetention.State.VALID.code(), s.metric(m, "state"));
            }
            // the off-box copy reaches the head: only the reader and the two-segment clamp bound the purge
            offBoxSet(s, records.get(records.size() - 1).logPosition());
            s.await(() -> s.live().stream().allMatch(m -> s.active(m).start() == expectedStart(end)), 60_000,
                    () -> "the start after the watermark reached the head: " + s.describe());
            System.out.println("GREEN off-box required: held at 0, then " + atX + " at watermark " + x + ", then "
                    + expectedStart(end));
        }
    }

    /** The control of the row above: with the kit's off-box bound off, a member cuts past its watermark. */
    @Test(timeout = 600_000)
    public void withoutTheOffBoxBoundAMemberCutsPastItsWatermark() throws Exception {
        try (MeRetentionStory s = offBoxStory(26_900, "mjr-offbox-nc")) {
            s.orders(2_600);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head), 60_000, s::describe);
            List<Held> records;
            do {
                records = s.read(s.f, s.active(s.f));
            } while (records == null);
            final long end = s.active(s.f).end();
            final long x = records.get(1_299).logPosition();
            final long atX = baseFor(records, end, r -> r.logPosition() <= x);
            offBoxSet(s, x);
            JournalRetention.Seams.offBoxBoundOff = true;
            try {
                s.setAll("ct-bridge", head);
                MeJournalRetentionClusterTest.nc("MJR off-box", "purged past the off-box position", () -> {
                    final long until = System.currentTimeMillis() + 15_000;
                    while (System.currentTimeMillis() < until) {
                        for (final Member m : s.h.members) {
                            if (s.active(m).start() > atX) {
                                throw new AssertionError("purged past the off-box position: member " + m.id
                                        + " start " + s.active(m).start() + ", the watermark allows " + atX);
                            }
                        }
                        s.pumpMs(200);
                    }
                });
            } finally {
                JournalRetention.Seams.reset();
            }
        }
    }
}
