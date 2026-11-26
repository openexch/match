// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * S4 T4 (match, embedded driver, in-process rig): three real members roll from level N-1 to N by the R-B4 walk.
 * Every member holds a snapshot taken at N-1 before the roll; followers go first, one at a time, each back at
 * {@code /ready} 200 with the expected {@code compat=}; the leader is killed mid-roll and restarted on N; an N member
 * leads an N-1 follower; at the end all members report N, a snapshot is taken, and every member reports the same
 * snapshot state hash and equal journals. Orders are sent between the steps and while a member is down, and every
 * one of them is APPLIED (zero rejected commands); no member's validator refused anything.
 *
 * <p>The per-member level is a test seam: {@code ClusterConfig.memberCompat} on each member's config, the method the
 * production launcher calls with the build's constant.</p>
 */
public class MixedLevelRollClusterTest {

    private static final int PORT_BASE = 26_500;
    private static final MemberCompat OLD = MemberCompat.of(1, 1);
    private static final MemberCompat NEW = MemberCompat.of(1, 2);

    private final MemberCompat[] levels = {OLD, OLD, OLD};
    private final AtomicInteger refusals = new AtomicInteger();
    private final List<Long> sent = new ArrayList<>();
    private long nextId = 10_001;

    private void install(final MeClusterHarness h) {
        h.afterConfig = m -> {
            m.cfg.memberCompat(levels[m.id], (own, other) -> {
                refusals.incrementAndGet();
                System.err.println("[T4] member " + m.id + " REFUSED " + other + " (own " + own + ")");
            });
            // the production launcher's pre-election check, on the member's own seen file
            assertNull("member " + m.id + " passes checkBeforeLaunch at " + levels[m.id],
                    MemberCompat.checkBeforeLaunch(m.cfg.consensusModuleContext().clusterDir(), levels[m.id], "test"));
        };
    }

    private void send(final MeClusterHarness h, final AeronCluster forwarder, final int n) {
        for (int i = 0; i < n; i++) {
            final long id = nextId++;
            sent.add(id);
            assertTrue(MeClusterHarness.offer(forwarder, create(id, 0, 100 + (id % 7), (int) (id % 2),
                    59_000 + (id % 3) * 10, 1 + id % 2), 10_000) > 0);
        }
    }

    private void assertEveryOrderApplied(final MeClusterHarness h, final AeronCluster client) {
        assertTrue("every order has an outcome", MeClusterHarness.await(client,
                () -> sent.stream().allMatch(id -> h.outcomes.containsKey(MeClusterHarness.key(id, 0))), 30_000));
        for (final long id : sent) {
            assertEquals("order " + id + " APPLIED (zero rejected commands)", 0L,
                    h.outcomes.get(MeClusterHarness.key(id, 0))[0]);
        }
    }

    private static String compatOf(final MeClusterHarness.Member m, final AeronCluster client) {
        final String[] body = {null};
        assertTrue("member " + m.id + " /ready 200", MeClusterHarness.await(client, () -> {
            final String[] r = MemberProcess.ready(m.service.nodeEndpointPort());
            if (r != null && "200".equals(r[0])) {
                body[0] = r[1];
                return true;
            }
            return false;
        }, 30_000));
        final int at = body[0].indexOf(" compat=");
        assertTrue("member " + m.id + " /ready carries compat=: " + body[0], at >= 0);
        return body[0].substring(at + " compat=".length(), body[0].indexOf(' ', at + 1));
    }

    private void assertLevels(final MeClusterHarness h, final AeronCluster client) {
        for (final MeClusterHarness.Member m : h.members) {
            assertEquals("member " + m.id + " /ready compat", levels[m.id].toString(), compatOf(m, client));
        }
    }

    private void restartAt(final MeClusterHarness h, final MeClusterHarness.Member m, final MemberCompat level,
                           final AeronCluster forwarder, final AeronCluster admin) throws Exception {
        if (m.live) {
            m.close();
        }
        // load while the member is down: the other two keep quorum
        send(h, forwarder, 4);
        levels[m.id] = level;
        m.start();
        final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
        assertTrue("member " + m.id + " rejoined at " + level, h.awaitJoined(m, leader, admin, 30_000));
    }

    @Test
    public void aClusterRollsFromNMinusOneToNWithMixedLevelsAndALeaderKilledMidRoll() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(PORT_BASE, "mixed-level-roll")) {
            install(h);
            h.startAll();
            MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
                 AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin,
                        () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                send(h, forwarder, 8);
                assertTrue(h.awaitQuiescence(forwarder, 30_000));
                assertLevels(h, admin);

                // R-B4 step 1: every member at N-1 holds a snapshot taken at N-1
                h.snapshot(leader, admin);
                h.awaitSnapshotSeries(admin, -1, 30_000);
                for (final MeClusterHarness.Member m : h.members) {
                    assertTrue("member " + m.id + " holds a snapshot", m.newestSnapshot() > 0);
                }

                // step 2: the first follower to N
                final MeClusterHarness.Member f1 = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
                final MeClusterHarness.Member f2 = h.members[(leader.id + 2) % MeClusterHarness.MEMBERS];
                restartAt(h, f1, NEW, forwarder, admin);
                send(h, forwarder, 4);
                assertTrue(h.awaitQuiescence(forwarder, 30_000));
                assertLevels(h, admin);

                // step 3, leader dies mid-roll: the N-1 leader is killed, restarted on N
                final MeClusterHarness.Member oldLeader = leader;
                oldLeader.close();
                MeClusterHarness.Member next = h.awaitLeader(oldLeader, 30_000);
                send(h, forwarder, 4);
                levels[oldLeader.id] = NEW;
                oldLeader.start();
                next = h.awaitLeader(null, 30_000);
                assertTrue(h.awaitJoined(oldLeader, next, admin, 30_000));
                if (levels[next.id].equals(OLD)) {
                    // the N-1 member won: stop it so an N member leads, and bring it back still on N-1
                    final MeClusterHarness.Member oldOne = next;
                    oldOne.close();
                    next = h.awaitLeader(oldOne, 30_000);
                    send(h, forwarder, 4);
                    oldOne.start();
                    assertTrue(h.awaitJoined(oldOne, h.awaitLeader(null, 30_000), admin, 30_000));
                    next = h.awaitLeader(null, 30_000);
                }
                assertEquals("an N member leads", NEW, levels[next.id]);
                assertEquals("an N-1 follower remains", OLD, levels[f2.id]);
                assertEquals(Cluster.Role.FOLLOWER, f2.role());
                send(h, forwarder, 6);
                assertTrue("N leads N-1: every member journaled the same records",
                        h.awaitQuiescence(forwarder, 30_000));
                assertLevels(h, admin);

                // the last N-1 member (a follower) to N
                restartAt(h, f2, NEW, forwarder, admin);
                send(h, forwarder, 4);
                assertTrue(h.awaitQuiescence(forwarder, 30_000));
                assertLevels(h, admin);
                for (final MemberCompat l : levels) {
                    assertEquals(NEW, l);
                }

                // step 4: all at N, snapshot; one state on every member
                leader = h.awaitLeader(null, 30_000);
                final long before = leader.newestSnapshot();
                h.snapshot(leader, admin);
                final long[][] series = h.awaitSnapshotSeries(admin, before, 30_000);
                for (int i = 1; i < series.length; i++) {
                    assertNotNull(series[i]);
                    assertEquals("member " + i + " snapshot position", series[0][0], series[i][0]);
                    assertEquals("member " + i + " snapshot state hash", series[0][1], series[i][1]);
                }
                assertEveryOrderApplied(h, admin);
                assertEquals("equal journals on every member", 0, h.unionMismatches.get());
                assertEquals("no member refused a version", 0, refusals.get());
                for (final MeClusterHarness.Member m : h.members) {
                    assertEquals("member " + m.id + " never failed fast", 0, m.failFast.get());
                    final MemberCompat.Seen seen = MemberCompat.readSeenOrNull(
                            m.cfg.consensusModuleContext().clusterDir());
                    assertNotNull("member " + m.id + " recorded what it accepted", seen);
                    assertEquals("member " + m.id + " saw N", NEW.level(), seen.levelMax());
                }
            }
        }
    }
}
