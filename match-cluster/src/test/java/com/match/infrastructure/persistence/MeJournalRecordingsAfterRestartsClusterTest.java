// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.match.infrastructure.persistence.MeRetentionStory.Pusher;
import com.match.infrastructure.persistence.MeRetentionStory.Reader;
import org.junit.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every process run of a member opens a new settlement journal recording and leaves the old one stopped; the new
 * run journals again from its snapshot (or from the start of the log), so the recordings overlap in
 * {@code journalSeq}. Restarts of one member under order flow, in both driver modes: the stopped recordings go once
 * the new one has passed them and the reader has applied them, the member never ends with more than two recordings
 * with bytes, its journal tail never falls, and its start position advances again after every restart.
 */
public class MeJournalRecordingsAfterRestartsClusterTest {

    private static void restarts(final int port, final String name, final boolean externalDriver) throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(port, name, externalDriver, "journal.retention.settlement",
                "readers", "journal.retention.poll.ms", "1000").boot()) {
            final Reader reader = s.new Reader("ct-bridge");
            s.setAll("ct-bridge", 0);
            final Pusher pusher = s.new Pusher(reader, null);
            final Member f = s.f;
            s.orders(1_000);
            final long[] tailFloor = {0};
            for (int restart = 1; restart <= 3; restart++) {
                final int n = restart;
                if (restart == 2) {
                    // from here a restart resumes from a snapshot: its recording starts above journalSeq 1
                    s.h.snapshot(s.h.awaitLeaderQuietly(), s.client);
                }
                f.close();
                s.orders(500); // the flow goes on while the member is down
                f.start();
                s.await(() -> MeRetentionStory.head(f) == s.head(), 120_000,
                        () -> "restart " + n + ": member " + f.id + " caught up: " + s.describe());
                s.orders(500);
                final long head = s.head();
                // the member's reader file survived the restart; the pusher goes on delivering to it
                s.pump(List.of(reader), List.of(pusher), 1_000, () -> {
                    final var status = f.service.journalRetention() == null ? null : s.status(f).report;
                    if (status != null && status.tailSeq >= 0) {
                        if (status.tailSeq < tailFloor[0]) {
                            throw new AssertionError("journal tail fell from " + tailFloor[0] + " to "
                                    + status.tailSeq + " on member " + f.id);
                        }
                        tailFloor[0] = status.tailSeq;
                    }
                    return reader.applied == head && s.recordingsWithBytes(f) <= 2 && s.recorded(f) == head
                            && s.active(f).start() == MeRetentionStory.expectedStart(s.active(f).end())
                            && status != null && status.tailSeq == head && status.recordings == 1;
                }, 120_000, () -> "restart " + n + ": member " + f.id + " keeps " + s.recordingsWithBytes(f)
                        + " journal recordings with bytes, or its start does not advance (head " + head
                        + ", reader at " + reader.applied + "): " + s.describe());
                assertTrue("restart " + n + ": the new recording is cut", s.active(f).start() > 0);
            }
            assertEquals("four process runs", 4, f.starts);
            assertTrue("the old recordings went whole: " + s.lines, s.said(": stopped, last journalSeq "));
            final long head = s.head();
            assertEquals(head, reader.applied);
            TreeMap<Long, Integer> held;
            do {
                held = s.held(f);
            } while (held == null);
            assertEquals("the member's journal still ends at the head", head, (long) held.lastKey());
            System.out.println("GREEN three restarts (" + (externalDriver ? "external" : "embedded") + " driver): "
                    + s.describe());
        }
    }

    /**
     * Tail cover through the engine. A member restarts with its journal writer held: the new run records nothing, so
     * the stopped recording of the run before is the only one that carries the member's journal tail. Every reader
     * has applied all of it; it is cut to its last segment and never deleted, pass after pass.
     */
    private static void theTailCarrierStays(final int port, final String name) throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(port, name, false, "journal.retention.settlement", "readers",
                "journal.retention.poll.ms", "1000").boot()) {
            final Member f = s.f;
            s.orders(1_500);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head), 60_000, s::describe);
            f.close();
            com.match.infrastructure.journal.JournalDrainGate.install(() -> false);
            try {
                f.start(); // this run's writer never drains: no new recording
            } finally {
                com.match.infrastructure.journal.JournalDrainGate.install(null);
            }
            s.await(() -> MeRetentionStory.head(f) == head, 120_000, () -> "member " + f.id + " caught up: "
                    + s.describe());
            s.setAll("ct-bridge", head);
            final long until = System.currentTimeMillis() + 8_000;
            int passes = 0;
            long lastPass = -1;
            while (System.currentTimeMillis() < until) {
                final var status = f.service.journalRetention() == null ? null : s.status(f);
                if (status != null && status.report != null && status.lastPassMs != lastPass) {
                    lastPass = status.lastPassMs;
                    passes++;
                    if (status.report.tailSeq != head) {
                        throw new AssertionError("journal tail fell from " + head + " to " + status.report.tailSeq
                                + " on member " + f.id + ": " + s.describe());
                    }
                }
                s.pumpMs(200);
            }
            assertTrue("passes seen: " + passes, passes >= 3);
            assertEquals("the stopped recording is all the member has", 1, s.recordingsWithBytes(f));
            TreeMap<Long, Integer> held;
            do {
                held = s.held(f);
            } while (held == null);
            assertEquals("the member's last record is still readable", head, (long) held.lastKey());
            final long start = s.recordings(f).stream().filter(r -> r.bytes() > 0).findFirst().orElseThrow().start();
            assertTrue("it was cut to its last segment: " + s.describe(), start > 0);
            System.out.println("GREEN tail carrier kept: " + s.describe());
        }
    }

    @Test(timeout = 300_000)
    public void theStoppedRecordingThatCarriesTheTailIsCutButNeverDeleted() throws Exception {
        theTailCarrierStays(30_500, "mjr-tailcover");
    }

    /** The control: with the kit's tail cover off the carrier is deleted whole and the member's tail falls. */
    @Test(timeout = 300_000)
    public void withoutTailCoverTheMembersJournalTailFalls() throws Exception {
        com.openexchange.cluster.journal.JournalRetention.Seams.tailCoverOff = true;
        try {
            MeJournalRetentionClusterTest.nc("MJR tail cover", "journal tail fell from",
                    () -> theTailCarrierStays(30_900, "mjr-tailcover-nc"));
        } finally {
            com.openexchange.cluster.journal.JournalRetention.Seams.reset();
        }
    }

    @Test(timeout = 600_000)
    public void restartsLeaveAtMostTwoJournalRecordingsEmbeddedDriver() throws Exception {
        restarts(27_300, "mjr-restarts", false);
    }

    @Test(timeout = 600_000)
    public void restartsLeaveAtMostTwoJournalRecordingsExternalDriver() throws Exception {
        restarts(27_700, "mjr-restarts-ext", true);
    }
}
