// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.openexchange.cluster.journal.JournalRetention;
import org.junit.After;
import org.junit.Test;

import static com.match.infrastructure.persistence.MeRetentionStory.GENESIS;
import static com.match.infrastructure.persistence.MeRetentionStory.JOURNAL;
import static com.match.infrastructure.persistence.MeRetentionStory.SEG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The holds of {@link MeJournalRetentionHoldsClusterTest} can fail through the engine: each row here switches one
 * rule of the kit's retention off on a real three-member engine whose journal is more than three segments long, and
 * the members then cut what the rule held, so the assertion "nothing was purged" fails by name. The last rows are a
 * behaviour 1 cluster, which records no genesis: it stays UNBOUND under {@code readers} and never purges.
 */
public class MeJournalRetentionControlsClusterTest {

    @After
    public void seamsOff() {
        JournalRetention.Seams.reset();
    }

    private interface Setup {
        void files(MeRetentionStory s, long head);
    }

    /** A purge is irreversible, so every control has its own cluster. */
    private static void control(final int port, final String name, final Runnable seam, final String named,
                                final Setup setup) throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(port, name, false, "journal.retention.settlement", "readers",
                "journal.retention.poll.ms", "1000").boot()) {
            s.orders(1_500);
            final long head = s.head();
            s.await(() -> s.live().stream().allMatch(m -> s.recorded(m) == head && s.active(m).end() > 3L * SEG),
                    60_000, s::describe);
            setup.files(s, head);
            // with the rule on, the same files hold: two passes, nothing moves
            s.pumpMs(2_500);
            for (final Member m : s.h.members) {
                assertEquals(name + ": member " + m.id + " holds with the rule on", 0, s.active(m).start());
            }
            seam.run();
            MeJournalRetentionClusterTest.nc(name, named, () -> {
                final long until = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < until) {
                    for (final Member m : s.h.members) {
                        if (s.active(m).start() > 0) {
                            throw new AssertionError(named + ": member " + m.id + " start " + s.active(m).start()
                                    + " " + s.describe());
                        }
                    }
                    s.pumpMs(200);
                }
            });
        }
    }

    @Test(timeout = 300_000)
    public void withNoReadersAsNoConstraintAMemberCutsWithNobodyRegistered() throws Exception {
        control(28_100, "mjr-nc-noreaders", () -> JournalRetention.Seams.noReadersIsNoConstraint = true,
                "purged with no registered reader", (s, head) -> { });
    }

    @Test(timeout = 300_000)
    public void withoutTheIdentityCheckAFileOfAnotherGenesisCutsTheJournal() throws Exception {
        control(28_500, "mjr-nc-foreign", () -> JournalRetention.Seams.foreignCheckOff = true,
                "purged on a reader file of another genesis", (s, head) -> {
                    for (final Member m : s.h.members) {
                        assertEquals(0, s.tool(m, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id",
                                String.valueOf(GENESIS + 1), "--journal", JOURNAL, "--reader", "ct-bridge",
                                "--applied-journal-seq", String.valueOf(head), "--source", "stale-console"));
                    }
                });
    }

    @Test(timeout = 300_000)
    public void withoutTheTailBoundACursorInTheWrongUnitCutsTheJournal() throws Exception {
        control(28_900, "mjr-nc-ahead", () -> JournalRetention.Seams.aheadCheckOff = true,
                "purged on a cursor above the member's tail", (s, head) -> s.setAll("ct-bridge", head + 1_000_000));
    }

    @Test(timeout = 300_000)
    public void withTheMaximumInsteadOfTheMinimumADeadReaderIsCutPast() throws Exception {
        control(29_300, "mjr-nc-dead", () -> JournalRetention.Seams.minIsMax = true,
                "purged a record above reader oe-marketdata's cursor", (s, head) -> {
                    // The reader that holds the journal is registered first. A reader with no file is not
                    // protected, and a member's pass may fall between two registrations: with ct-bridge alone
                    // at the head, that pass is right to purge up to the head.
                    s.setAll("oe-marketdata", 0);
                    s.setAll("ct-bridge", head);
                });
    }

    // ------------------------------------------------------------------ behaviour 1

    private static final String UNBOUND = "is not UNBOUND";

    /** Readers registered at a cursor far above anything, more than three segments recorded, several passes. */
    private static void behaviourOne(final int port, final String name, final long publishedGenesis)
            throws Exception {
        try (MeRetentionStory s = new MeRetentionStory(port, name, false, "journal.retention.settlement", "readers",
                "journal.retention.poll.ms", "1000")) {
            s.h.beforeLaunch = (m, service) -> service.genesisIdOverrideForTest = publishedGenesis;
            s.bootBehaviourOne();
            s.ordersBehaviourOne(1_200, 3L * SEG + 1);
            for (final Member m : s.h.members) {
                assertEquals("behaviour 1 records no genesis", 0, m.engine().meIdentity().meEpoch());
                assertEquals("behaviour 1 journals at journalSeq 0", 0, s.recorded(m));
            }
            s.setAll("ct-bridge", 1_000);
            s.setAll("oe-marketdata", 1_000);
            s.await(() -> s.live().stream().allMatch(m -> s.status(m).report != null), 30_000, s::describe);
            s.pumpMs(3_500); // three more passes
            for (final Member m : s.h.members) {
                // whatever the member says its genesis is, nothing of a behaviour 1 journal is ever cut
                assertTrue("member " + m.id + " recorded more than three segments", s.active(m).end() > 3L * SEG);
                assertEquals("member " + m.id + " purged nothing", 0, s.active(m).start());
                assertEquals(0, s.metric(m, "purged_bytes_total"));
            }
            for (final Member m : s.h.members) {
                if (s.metric(m, "state") != JournalRetention.State.UNBOUND.code()) {
                    throw new AssertionError("member " + m.id + " " + UNBOUND + ": state " + s.metric(m, "state") + " "
                            + s.describe());
                }
                assertTrue(m.service.probe().detail(), m.service.probe().detail().contains(
                        "journal retention settlement UNBOUND: this member has no recorded genesis id yet"));
            }
            System.out.println("GREEN behaviour 1: UNBOUND, start 0 with " + s.active(s.f).end() + " bytes recorded");
        }
    }

    @Test(timeout = 300_000)
    public void aBehaviourOneMemberStaysUnboundAndNeverPurges() throws Exception {
        behaviourOne(29_700, "mjr-b1", 0L);
    }

    /**
     * The control of the UNBOUND row: a member that publishes a genesis id at behaviour 1 no longer answers UNBOUND,
     * and the assertion names it. It still purges nothing (asserted before the named failure): the records carry
     * epoch 0, so the recording is another epoch's, and journalSeq 0 qualifies no boundary.
     */
    @Test(timeout = 300_000)
    public void aBehaviourOneMemberThatClaimsAGenesisIsNoLongerUnbound() throws Exception {
        MeJournalRetentionClusterTest.nc("MJR behaviour 1", UNBOUND, () -> behaviourOne(30_100, "mjr-b1-nc", GENESIS));
    }
}
