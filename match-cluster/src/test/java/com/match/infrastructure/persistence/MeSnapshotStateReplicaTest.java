// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import org.junit.Test;

import java.util.Arrays;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.cancel;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static org.junit.Assert.*;

/**
 * Three real members report one snapshot state: after a snapshot every member renders the same
 * {@code match_snapshot_last_log_position} and {@code match_snapshot_state_hash}; a member restarted from that
 * snapshot reports the same pair before any new snapshot (and no snapshot age, since it took none); the next
 * snapshot, taken by the restarted member too, again agrees on all three.
 */
public class MeSnapshotStateReplicaTest {

    private static final int PORT_BASE = 26_200;

    private static void send(final MeClusterHarness h, final AeronCluster forwarder, final long first, final int n) {
        for (int i = 0; i < n; i++) {
            final long id = first + i;
            final int side = i % 2;
            // Alternate sides around one price so some orders rest and some trade.
            assertTrue(MeClusterHarness.offer(forwarder, create(id, 0, 100 + i, side, 59_000 + (i % 3) * 10, 1 + i % 2),
                    10_000) > 0);
        }
        assertTrue("every member journaled the same records", h.awaitQuiescence(forwarder, 30_000));
    }

    /** Resting orders of the test market; the book lists them as flat [orderId, userId, price, qty] tuples. */
    private static final int TUPLE = 4;

    private static int resting(final MeClusterHarness.Member m) {
        final com.match.application.engine.Engine e = m.engine();
        return (e.getEngine(BehaviourTwoHarness.MARKET).getBidOrders().length
                + e.getEngine(BehaviourTwoHarness.MARKET).getAskOrders().length) / TUPLE;
    }

    /**
     * Cancels every other resting order so the array book holds holes when it is snapshotted. The legs, their
     * oms order ids and their owners are read from the leader's engine after quiescence.
     */
    private static int cancelEveryOtherResting(final MeClusterHarness h, final MeClusterHarness.Member leader,
                                               final AeronCluster forwarder) {
        final com.match.application.engine.Engine e = leader.engine();
        int cancelled = 0;
        int index = 0;
        for (long[] side : new long[][]{e.getEngine(BehaviourTwoHarness.MARKET).getBidOrders(),
                e.getEngine(BehaviourTwoHarness.MARKET).getAskOrders()}) {
            for (int t = 0; t < side.length; t += TUPLE, index++) {
                if (index % 2 != 0) {
                    continue;
                }
                final long leg = side[t];
                final long user = side[t + 1];
                assertTrue(MeClusterHarness.offer(forwarder, cancel(e.getOmsOrderIdFor(leg), 1, user, leg), 10_000) > 0);
                cancelled++;
            }
        }
        assertTrue("every member journaled the cancels", h.awaitQuiescence(forwarder, 30_000));
        return cancelled;
    }

    private static String describe(final long[][] series) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < series.length; i++) {
            sb.append(" member ").append(i).append('=').append(Arrays.toString(series[i]));
        }
        return sb.toString();
    }

    private static void assertAllAgree(final String what, final long[][] series) {
        final long[] first = series[0];
        assertNotNull(what + ": member 0 rendered nothing", first);
        assertTrue(what + ": a known hash on member 0:" + describe(series), first[1] >= 0);
        for (int i = 1; i < series.length; i++) {
            assertNotNull(what + ": member " + i + " rendered nothing", series[i]);
            assertArrayEquals(what + ": member " + i + " reports member 0's {position, hash}:" + describe(series),
                    first, series[i]);
        }
    }

    @Test
    public void everyMemberReportsOneSnapshotStateAndARestartedMemberKeepsIt() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(PORT_BASE, "snapshot-state")) {
            h.startAll();
            final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
                 AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin,
                        () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));

                for (MeClusterHarness.Member m : h.members) {
                    final long[] none = m.snapshotSeries();
                    assertEquals("member " + m.id + " has never snapshotted", -1L, none[0]);
                    assertEquals("member " + m.id + " omits the hash before any snapshot", -1L, none[1]);
                }

                // Enough orders that several rest, then cancel some of them: the snapshot (and the book the
                // restarted member restores) carries holes, not only a densely packed book.
                send(h, forwarder, 1_001, 16);
                final int restingBefore = resting(leader);
                final int cancelled = cancelEveryOtherResting(h, leader, forwarder);
                assertTrue("non-vacuous: at least two orders rested, so one cancel leaves a hole", restingBefore >= 2);
                for (MeClusterHarness.Member m : h.members) {
                    assertEquals("member " + m.id + " applied the cancels", restingBefore - cancelled, resting(m));
                }
                h.snapshot(leader, admin);
                final long[][] first = h.awaitSnapshotSeries(admin, -1, 30_000);
                assertAllAgree("first snapshot", first);
                final long position1 = first[0][0];
                final long hash1 = first[0][1];
                assertTrue("a real log position", position1 > 0);
                for (MeClusterHarness.Member m : h.members) {
                    assertEquals("the rendered pair is the member's stamp", position1,
                            m.service.snapshotState().logPosition());
                    assertEquals(hash1, m.service.snapshotState().stateHash());
                }

                final MeClusterHarness.Member follower = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
                follower.close();
                follower.start();
                assertTrue("member " + follower.id + " joined", h.awaitJoined(follower, leader, admin, 30_000));
                assertArrayEquals("the restarted member reports the loaded snapshot's pair before any new snapshot",
                        new long[]{position1, hash1}, follower.snapshotSeries());
                assertTrue("a loaded snapshot is not one this member took: no snapshot age",
                        follower.metrics().contains("match_snapshot_age_seconds -1\n"));

                send(h, forwarder, 2_001, 8);
                h.snapshot(leader, admin);
                final long[][] second = h.awaitSnapshotSeries(admin, position1, 30_000);
                assertAllAgree("second snapshot, restarted member included", second);
                assertTrue("the log moved on", second[0][0] > position1);
                assertNotEquals("the state moved on", hash1, second[0][1]);
                assertEquals("equal journals on every member", 0, h.unionMismatches.get());
            }
        }
    }
}
