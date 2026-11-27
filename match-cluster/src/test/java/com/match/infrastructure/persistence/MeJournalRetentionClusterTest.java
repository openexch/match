// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.match.infrastructure.persistence.MeRetentionStory.Held;
import com.match.infrastructure.persistence.MeRetentionStory.Pusher;
import com.match.infrastructure.persistence.MeRetentionStory.Reader;
import com.match.infrastructure.persistence.MeRetentionStory.Rec;
import com.openexchange.cluster.journal.JournalRetention;
import org.junit.Test;

import java.util.List;
import java.util.TreeMap;

import static com.match.infrastructure.persistence.MeRetentionStory.SEG;
import static com.match.infrastructure.persistence.MeRetentionStory.expectedStart;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Journal retention on a real three-member matching engine, in both driver modes: each member purges its own
 * settlement journal recordings behind a reader that really reads them, from cursor files a pusher delivers one step
 * late. Nothing here calls a pass: the tests write reader files and wait for the journal archives' start positions.
 */
public class MeJournalRetentionClusterTest {

    /** A negative control: {@code body} must fail with an {@link AssertionError} containing {@code named}. */
    static void nc(final String id, final String named, final ThrowingRunnable body) throws Exception {
        try {
            body.run();
        } catch (final AssertionError e) {
            final String msg = String.valueOf(e.getMessage());
            if (!msg.contains(named)) {
                throw new AssertionError("negative control " + id + " failed for the wrong reason: <" + msg
                        + ">, expected <" + named + ">", e);
            }
            System.out.println("NC " + id + " -> " + (msg.length() > 300 ? msg.substring(0, 300) + "..." : msg));
            return;
        }
        throw new AssertionError("negative control " + id + " did not fail: the assertion cannot see its rule");
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Order flow in rounds. After each round, once the reader has applied the head and its cursor is delivered,
     * every member's journal recording starts two whole segments below its head segment, so the start rises round
     * over round and what a member retains is at most three segments.
     */
    private static void theStartRisesOnEveryMember(final int port, final String name, final boolean externalDriver,
                                                   final boolean retentionWired, final int rounds)
            throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(port, name, externalDriver, "journal.retention.settlement",
                "readers", "journal.retention.poll.ms", "1000")) {
            s.h.beforeLaunch = (m, service) -> service.journalRetentionEnabled = retentionWired;
            s.boot();
            final Reader reader = s.new Reader("ct-bridge");
            // registered on every member before the reader first starts
            s.setAll("ct-bridge", 0);
            // member g's file is updated one step later than the others'
            final Pusher pusher = s.new Pusher(reader, s.g);
            final long[] lastStart = new long[MeClusterHarness.MEMBERS];
            for (int round = 0; round < rounds; round++) {
                s.orders(1_000);
                final long head = s.head();
                final int r = round;
                s.pump(List.of(reader), List.of(pusher), 1_000, () -> reader.applied == head
                        && s.live().stream().allMatch(m -> {
                            final Rec rec = s.active(m);
                            return s.recorded(m) == head && rec.start() > lastStart[m.id]
                                    && rec.start() == expectedStart(rec.end());
                        }), 60_000, () -> "round " + r + ": the settlement journal start position did not reach two"
                        + " segments below the head " + head + " on every member (reader at " + reader.applied
                        + "): " + s.describe());
                for (final Member m : s.h.members) {
                    lastStart[m.id] = s.active(m).start();
                    final long retained = s.retained(m);
                    assertTrue("round " + round + " member " + m.id + ": retained " + retained + " bytes with the"
                            + " reader at the head: at most three segments", retained <= 3L * SEG);
                }
            }
            final long head = s.head();
            assertEquals("the reader applied every record, one after the other", head, reader.applied);
            for (final Member m : s.h.members) {
                // what the member says about itself agrees with its journal archive
                s.pump(List.of(), List.of(pusher), 1_000, () -> s.metric(m, "safe_seq") == head, 30_000,
                        () -> "member " + m.id + " safe_seq " + s.metric(m, "safe_seq"));
                final Rec rec = s.active(m);
                assertEquals(JournalRetention.State.VALID.code(), s.metric(m, "state"));
                assertEquals(JournalRetention.Mode.READERS.code(), s.metric(m, "mode"));
                assertEquals(head, s.metric(m, "tail_seq"));
                assertEquals(rec.bytes(), s.metric(m, "retained_bytes"));
                assertEquals(1, s.metric(m, "recordings"));
                assertEquals(0, s.metric(m, "recordings_foreign"));
                assertEquals(0, s.metric(m, "recordings_anomaly"));
                assertEquals(rec.start(), s.metric(m, "purged_bytes_total"));
                assertTrue(m.service.renderMetrics().contains("match_journal_retention_agent_state 1\n"));
                assertTrue(m.service.renderMetrics().contains("match_journal_retention_reader_applied_seq{journal="
                        + "\"settlement\",reader=\"ct-bridge\"} " + head));
                assertFalse("a member that purges says nothing on /health: " + m.service.probe().detail(),
                        m.service.probe().detail().contains("journal-retention"));
                // every record above the purged range is still there, in order, and the cut is on a record
                List<Held> held;
                do {
                    held = s.read(m, rec);
                } while (held == null);
                assertEquals(rec.start(), held.get(0).position());
                assertEquals(head, held.get(held.size() - 1).seq());
                for (int i = 1; i < held.size(); i++) {
                    assertEquals("dense above the cut", held.get(i - 1).seq() + 1, held.get(i).seq());
                    assertEquals(MeRetentionStory.GENESIS, held.get(i).epoch());
                }
                assertTrue("the first record kept is one the reader applied", held.get(0).seq() <= reader.applied);
            }
            // the passes asked no member's archive for a replay that starts inside a frame: an archive raises each
            // such refusal into the member's Aeron error handler (the harness prints what reaches it)
            assertFalse("a refused replay start reached a member's error handler",
                    s.said("does not point to a valid frame"));
            // each member exports Aeron's duty-cycle and snapshot-duration counters, in this driver mode
            for (final Member m : s.h.members) {
                final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (!m.service.renderMetrics().contains("match_service_max_cycle_time_seconds ")
                        && System.nanoTime() < deadline) {
                    Thread.sleep(100);
                }
                final String body = m.service.renderMetrics();
                for (final String series : new String[] {"match_consensus_module_max_cycle_time_seconds ",
                    "match_consensus_module_cycle_time_exceeded_total ", "match_service_max_cycle_time_seconds ",
                    "match_service_cycle_time_exceeded_total ", "match_snapshot_total_max_duration_seconds ",
                    "match_snapshot_service_max_duration_seconds ", "match_driver_conductor_max_cycle_time_seconds ",
                    "match_driver_sender_max_cycle_time_seconds ", "match_driver_receiver_max_cycle_time_seconds ",
                    "match_stall_sampler_age_seconds "}) {
                    assertTrue("member " + m.id + " has no " + series + "series:\n" + body,
                            body.contains("\n" + series));
                }
                assertEquals("member " + m.id + ": the cluster archive and the journal archive", 2,
                        body.lines().filter(l -> l.startsWith("match_archive_max_cycle_time_seconds{archive_id=")
                                && l.contains(",agent=\"conductor\"} ")).count());
            }
            System.out.println("GREEN settlement journal retention (" + (externalDriver ? "external" : "embedded")
                    + " driver): head " + head + ", " + s.describe());
        }
    }

    @Test(timeout = 600_000)
    public void theJournalStartRisesOnEveryMemberBehindARealReaderEmbeddedDriver() throws Exception {
        theStartRisesOnEveryMember(24_100, "mjr-embedded", false, true, 4);
    }

    @Test(timeout = 600_000)
    public void theJournalStartRisesOnEveryMemberBehindARealReaderExternalDriver() throws Exception {
        theStartRisesOnEveryMember(24_500, "mjr-external", true, true, 4);
    }

    /**
     * The bug this replaces, and the control of the test above: a member that does not run the retention keeps every
     * record for ever (the journal recording's start position stays 0), whatever its readers applied.
     */
    @Test(timeout = 600_000)
    public void withoutTheRetentionThreadTheJournalIsNeverCut() throws Exception {
        nc("MJR engine wiring", "the settlement journal start position did not reach two segments below the head",
                () -> theStartRisesOnEveryMember(24_900, "mjr-unwired", false, false, 1));
    }

    /**
     * The oracle sees a purge above a reader's cursor: a pusher that sends more than the reader applied lets the
     * members cut records the reader still needs, and the reader finds its next record on no member.
     */
    @Test(timeout = 600_000)
    public void aPusherThatSendsMoreThanTheReaderAppliedMakesTheReaderGap() throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(25_300, "mjr-skew", false, "journal.retention.settlement",
                "readers", "journal.retention.poll.ms", "1000").boot()) {
            final Reader reader = s.new Reader("ct-bridge");
            s.setAll("ct-bridge", 0);
            s.orders(2_000);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head), 60_000, s::describe);
            // the reader has applied nothing; the pusher says it applied 1500 (below every member's tail)
            final Pusher pusher = s.new Pusher(reader, null);
            pusher.skew = 1_500;
            reader.frozen = true;
            nc("MJR pusher skew", "reader ct-bridge gap: its next journal record 1 is on no member", () -> {
                s.pump(List.of(reader), List.of(pusher), 1_000,
                        () -> s.live().stream().allMatch(m -> s.active(m).start() > 0), 60_000, s::describe);
                reader.frozen = false;
                s.pump(List.of(reader), List.of(), 1_000, () -> reader.applied == head, 30_000, s::describe);
            });
        }
    }

    /**
     * The two-segment clamp through the engine: with the kit's clamp off a member cuts its active recording up to
     * its head segment, and the assertion on what stays below the head names it.
     */
    @Test(timeout = 600_000)
    public void theActiveRecordingKeepsTwoWholeSegmentsBelowItsHead() throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(25_700, "mjr-clamp", false, "journal.retention.settlement",
                "readers", "journal.retention.poll.ms", "1000").boot()) {
            s.orders(2_000);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head), 60_000, s::describe);
            final ThrowingRunnable check = () -> {
                s.await(() -> s.live().stream().allMatch(m -> s.active(m).start() > 0), 60_000, s::describe);
                s.pumpMs(2_500);
                for (final Member m : s.h.members) {
                    final Rec rec = s.active(m);
                    if ((rec.end() & -(long) SEG) - rec.start() < 2L * SEG) {
                        throw new AssertionError("fewer than two segments below the head (member " + m.id + ": "
                                + rec + ")");
                    }
                    final TreeMap<Long, Integer> held = s.held(m);
                    assertTrue(held == null || held.lastKey() == head);
                }
            };
            JournalRetention.Seams.clampOff = true;
            try {
                s.setAll("ct-bridge", head);
                nc("MJR clamp", "fewer than two segments below the head", check);
            } finally {
                JournalRetention.Seams.reset();
            }
        }
    }
}
