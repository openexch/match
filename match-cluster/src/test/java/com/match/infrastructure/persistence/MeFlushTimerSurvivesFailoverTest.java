// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import org.agrona.DirectBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * RC2 S6 contract 5.1-5.3 (F-3, critic K3): the ME flush timer id is per leadership term, armed on every member
 * at the term event with a cluster-time deadline and re-armed by the leader only. Unit cases drive one service
 * through {@link MeStubCluster}; {@link #expiredTimerCountModelPerTermIdSurvives} drives three services through
 * three mirrored consensus modules ({@link ExpiredTimerCountModel}); the cluster case runs restart, failover and
 * idle failover on three real members.
 */
public class MeFlushTimerSurvivesFailoverTest {

    static final long BASE = AppClusteredService.FLUSH_TIMER_BASE;

    static long id(final long term) {
        return BASE + term * 1_000_000L;
    }

    private static void term(final AppClusteredService s, final MeStubCluster c, final long term) {
        s.onNewLeadershipTermEvent(term, c.logPosition, c.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
    }

    private static AppClusteredService service(final MeStubCluster c) {
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> { throw new AssertionError("failFast"); };
        return MeStubCluster.attach(s, c);
    }

    @Test
    public void flushTimerIdIsPerLeadershipTerm() {
        final MeStubCluster c = new MeStubCluster();
        final AppClusteredService s = service(c);
        term(s, c, 3);
        assertEquals(id(3), s.lastArmedFlushTimerId());
        assertEquals(3, s.flushTimerTerm());
        term(s, c, 4);
        final int mark = c.schedules.size();
        s.onTimerEvent(id(3), c.time); // the term-3 timer fires under term 4
        assertEquals("re-armed with term 4's id", List.of(id(4)), c.idsSince(mark));
        assertEquals(id(4), s.lastArmedFlushTimerId());
    }

    @Test
    public void newTermArmsFlushWithoutIngress() {
        final MeStubCluster c = new MeStubCluster();
        c.time = 5_000_000L;
        final AppClusteredService s = service(c);
        assertEquals("nothing armed before the term event", 0, c.schedules.size());
        term(s, c, 9);
        assertEquals(1, c.schedules.size());
        assertEquals(id(9), c.schedules.get(0)[0]);
        assertEquals("deadline in cluster time: time + 250 ms", 5_000_000L + 250, c.schedules.get(0)[1]);
    }

    @Test
    public void followerDoesNotReArm() {
        final MeStubCluster c = new MeStubCluster();
        c.role = Cluster.Role.FOLLOWER;
        final AppClusteredService s = service(c);
        term(s, c, 2);
        assertEquals("every member arms at the term event", List.of(id(2)), c.idsSince(0));
        s.onTimerEvent(id(2), c.time + 250);
        assertEquals("a follower re-arms nothing", 1, c.schedules.size());
        assertEquals(0, s.flushTimerFireCount());
    }

    @Test
    public void staleTermTimerFiresOnceAndReArmsCurrent() {
        final MeStubCluster c = new MeStubCluster();
        final AppClusteredService s = service(c);
        term(s, c, 5);
        final int mark = c.schedules.size();
        final long fires = s.flushTimerFireCount();
        s.onTimerEvent(BASE + 2 * 1_000_000L, c.time); // pending from term 2 (a CM snapshot or a deposed leader)
        assertEquals("the stale timer flushed", fires + 1, s.flushTimerFireCount());
        assertEquals("and moved the chain to the current id", List.of(id(5)), c.idsSince(mark));
    }

    // ---- the consensus-module count rule (RC1 S5 rule 16) ----

    /**
     * Proof of contract 1.2 item 4 (b) on the model alone: with ONE fixed id and leader-only re-arming, a
     * follower's module counts every tick it consumes, so the promoted member's first schedule is swallowed and
     * the chain is dead. This is why per-term ids are the precondition of A10.
     */
    @Test
    public void expiredTimerCountModelFixedIdLeaderOnlyRearmIsSwallowedAfterFollowerTicks() {
        final long fixed = BASE;
        final ExpiredTimerCountModel leader = new ExpiredTimerCountModel();
        final ExpiredTimerCountModel follower = new ExpiredTimerCountModel();
        leader.onScheduleTimer(fixed, 250);
        follower.onScheduleTimer(fixed, 250); // armed once on every member
        long now = 0;
        for (int tick = 0; tick < 20; tick++) {
            now += 250;
            for (long fired : leader.poll(now)) {
                follower.onReplayTimerEvent(fired);
                leader.onScheduleTimer(fired, now + 250); // only the leader re-arms
            }
        }
        assertEquals("the follower counted every tick but the first", 19, follower.expiredCount(fixed));
        follower.onScheduleTimer(fixed, now + 250); // promoted: its re-arm of the fixed id
        assertEquals(1, follower.swallowed);
        assertFalse("swallowed: nothing pending, the chain is dead", follower.isPending(fixed));
        assertTrue(follower.poll(now + 10_000).isEmpty());
    }

    /** Three services, three mirrored consensus modules, one log: term 1 led by m0, then idle failover to m1. */
    private static final class Sim {
        final MeStubCluster[] c = new MeStubCluster[3];
        final AppClusteredService[] s = new AppClusteredService[3];
        final List<long[]> log = new ArrayList<>(); // {0, term} or {1, timerId}
        final int[] cursor = new int[3];
        int leader;
        long now = 1_000_000L;

        Sim() {
            for (int i = 0; i < 3; i++) {
                c[i] = new MeStubCluster();
                c[i].role = Cluster.Role.FOLLOWER;
                c[i].time = now;
                c[i].consensusModule = new ExpiredTimerCountModel();
                s[i] = service(c[i]);
            }
        }

        void elect(final int newLeader, final long term) {
            leader = newLeader;
            for (int i = 0; i < 3; i++) {
                final Cluster.Role role = i == newLeader ? Cluster.Role.LEADER : Cluster.Role.FOLLOWER;
                if (c[i].role != role) {
                    c[i].role = role;
                    s[i].onRoleChange(role); // the role is set before the term's first entry is polled
                }
            }
            log.add(new long[] {0, term});
            deliver();
        }

        void deliver() {
            for (int i = 0; i < 3; i++) {
                c[i].time = now;
                while (cursor[i] < log.size()) {
                    final long[] e = log.get(cursor[i]++);
                    if (e[0] == 0) {
                        s[i].onNewLeadershipTermEvent(e[1], 0, now, 0, leader, 7, TimeUnit.MILLISECONDS, 0);
                    } else {
                        if (i != leader) {
                            c[i].consensusModule.onReplayTimerEvent(e[1]);
                        }
                        s[i].onTimerEvent(e[1], now);
                    }
                }
            }
        }

        /** One 250 ms tick: the leader's module fires what is due and appends it; returns the fired ids. */
        List<Long> tick() {
            now += 250;
            final List<Long> fired = c[leader].consensusModule.poll(now);
            for (long id : fired) {
                log.add(new long[] {1, id});
            }
            deliver();
            return fired;
        }
    }

    @Test
    public void expiredTimerCountModelPerTermIdSurvives() {
        final Sim sim = new Sim();
        sim.elect(0, 1);
        final List<Long> term1 = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            term1.addAll(sim.tick());
        }
        assertEquals("the term-1 chain ticks every 250 ms", 20, term1.size());
        assertTrue("every term-1 fire carries term 1's id", term1.stream().allMatch(i -> i == id(1)));
        assertEquals("followers' modules counted the ticks they consumed", 19,
                sim.c[1].consensusModule.expiredCount(id(1)));

        sim.elect(1, 2); // idle failover: no ingress, no session callbacks
        final List<Long> term2 = new ArrayList<>();
        for (int t = 0; t < 10; t++) {
            term2.addAll(sim.tick());
        }
        assertEquals("the new leader's chain is alive without ingress", 10, term2.size());
        assertTrue(term2.stream().allMatch(i -> i == id(2)));
        assertEquals(0, sim.c[1].consensusModule.swallowed);
        assertEquals("one chain: exactly one pending flush timer on the leader", 1,
                sim.c[1].consensusModule.pendingCount());
        assertEquals(id(2), sim.s[1].lastArmedFlushTimerId());
        assertEquals(2, sim.s[1].flushTimerTerm());
    }

    // ---- cluster: restart, failover and idle failover on three real members ----

    private static int portBase(final int offset) {
        return 23_000 + offset * 300;
    }

    /** Wait until the member's commit position equals the leader's and stays for 500 ms. */
    private static void awaitCatchUp(final MeClusterHarness.Member x, final MeClusterHarness.Member leader,
                                     final AeronCluster client) {
        final long deadline = System.currentTimeMillis() + 30_000;
        long since = -1;
        while (System.currentTimeMillis() < deadline) {
            final long lx = x.livePositions()[1];
            final long ll = leader.livePositions()[1];
            if (lx == ll && lx > 0 && x.electionClosed()) {
                if (since < 0) {
                    since = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - since >= 500) {
                    return;
                }
            } else {
                since = -1;
            }
            MeClusterHarness.await(client, () -> false, 50);
        }
        fail("member " + x.id + " did not catch up with leader " + leader.id);
    }

    /**
     * Contract 9.2 {@code newLeaderArmsTermIdWithoutIngress}: each round stops member X for at least 10 s,
     * restarts it, waits for its catch-up, kills the current leader (retrying until X wins, at most 5
     * attempts), then, with no ingress, checks within 2.5 s that X armed {@code base + T * 1e6} for its term T,
     * that its fires grow and that the client receives ClusterHeartbeat. No snapshot is taken during the rounds.
     * Three rounds (restart, failover and idle failover), then a snapshot and a whole-cluster restore from it.
     * After each election the leader runs exactly one chain (about four fires a second) and followers fire nothing.
     */
    @Test
    public void newLeaderArmsTermIdWithoutIngress() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(portBase(0), "flushterm")) {
            h.startAll();
            MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            final AtomicInteger heartbeats = new AtomicInteger();
            final io.aeron.cluster.client.EgressListener listener = (sessionId, ts, buffer, offset, length, hdr) -> {
                if (templateOf(buffer, offset) == com.match.infrastructure.generated.ClusterHeartbeatDecoder.TEMPLATE_ID) {
                    heartbeats.incrementAndGet();
                }
            };
            AeronCluster client = h.connect(TestAuth.credentials(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN),
                    listener, 60_000);
            try {
                for (int round = 0; round < 3; round++) {
                    final MeClusterHarness.Member x = h.members[(leader.id + 1 + round) % MeClusterHarness.MEMBERS];
                    boolean won = false;
                    for (int attempt = 0; attempt < 5 && !won; attempt++) {
                        if (x != leader) {
                            System.out.println("[FLUSH] round " + round + " attempt " + attempt + ": stopping member " + x.id);
                            x.close();
                            MeClusterHarness.await(client, () -> false, 10_500);
                            x.start();
                            awaitCatchUp(x, leader, client);
                        }
                        System.out.println("[FLUSH] killing leader " + leader.id);
                        final MeClusterHarness.Member killed = leader;
                        killed.close();
                        CloseHelperQuiet.close(client);
                        leader = h.awaitLeader(killed, 30_000);
                        client = h.connect(TestAuth.credentials(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN),
                                listener, 60_000);
                        if (leader == x) {
                            won = true;
                            final long term = currentTerm(client, x);
                            final long fires0 = x.service.flushTimerFireCount();
                            final int hb0 = heartbeats.get();
                            final MeClusterHarness.Member xx = x;
                            final boolean armed = MeClusterHarness.await(client,
                                    () -> xx.service.lastArmedFlushTimerId() == BASE + term * 1_000_000L
                                            && xx.service.flushTimerTerm() == term
                                            && xx.service.flushTimerFireCount() > fires0
                                            && heartbeats.get() > hb0,
                                    2_500); // from the client's reconnect, no ingress sent
                            System.out.println("[FLUSH] round " + round + ": member " + x.id + " leads term " + term
                                    + " armed=" + x.service.lastArmedFlushTimerId() + " flushTerm=" + x.service.flushTimerTerm()
                                    + " fires " + fires0 + "->" + x.service.flushTimerFireCount()
                                    + " heartbeats " + hb0 + "->" + heartbeats.get());
                            assertEquals("round " + round + ": the armed id is base + T * 1e6",
                                    BASE + term * 1_000_000L, x.service.lastArmedFlushTimerId());
                            assertEquals("round " + round + ": match_flush_timer_term", term, x.service.flushTimerTerm());
                            assertTrue("round " + round + ": fires grow and keep-warm reaches the client", armed);
                            assertSingleChain(h, "round " + round, x, client);
                        }
                        killed.start();
                        awaitCatchUp(killed, leader, client);
                    }
                    assertTrue("round " + round + ": member " + x.id + " never won within 5 attempts", won);
                }

                // Snapshot restore: the whole cluster restarts from a snapshot. Aeron closes every session on a
                // leader-startup election, so a fresh client connects; the leader's chain must be the startup
                // term's id, armed once, without ingress.
                h.snapshot(leader, client);
                CloseHelperQuiet.close(client);
                for (MeClusterHarness.Member m : h.members) {
                    m.close();
                }
                h.startAll();
                leader = h.awaitLeader(null, 30_000);
                client = h.connect(TestAuth.credentials(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN),
                        listener, 60_000);
                final MeClusterHarness.Member restored = leader;
                final long term = currentTerm(client, restored);
                final long fires0 = restored.service.flushTimerFireCount();
                final int hb0 = heartbeats.get();
                final boolean armed = MeClusterHarness.await(client,
                        () -> restored.service.lastArmedFlushTimerId() == BASE + term * 1_000_000L
                                && restored.service.flushTimerTerm() == term
                                && restored.service.flushTimerFireCount() > fires0
                                && heartbeats.get() > hb0, 2_500);
                System.out.println("[FLUSH] snapshot restore: member " + restored.id + " leads term " + term
                        + " armed=" + restored.service.lastArmedFlushTimerId() + " fires " + fires0 + "->"
                        + restored.service.flushTimerFireCount() + " heartbeats " + hb0 + "->" + heartbeats.get());
                assertEquals("snapshot restore: the armed id is base + T * 1e6", BASE + term * 1_000_000L,
                        restored.service.lastArmedFlushTimerId());
                assertTrue("snapshot restore: fires grow and keep-warm reaches the client", armed);
                assertSingleChain(h, "snapshot restore", restored, client);
            } finally {
                CloseHelperQuiet.close(client);
            }
        }
    }

    /**
     * Exactly one flush chain on the leader: one 250 ms timer fires about 12 times in 3 s; a second chain (a stale
     * id that kept re-arming itself, or a fixed id beside a term id) would double that. Followers never fire.
     */
    private static void assertSingleChain(final MeClusterHarness h, final String what,
                                          final MeClusterHarness.Member leader, final AeronCluster client) {
        final long[] before = new long[MeClusterHarness.MEMBERS];
        for (MeClusterHarness.Member m : h.members) {
            before[m.id] = m.live ? m.service.flushTimerFireCount() : 0;
        }
        final long t0 = System.currentTimeMillis();
        MeClusterHarness.await(client, () -> false, 3_000);
        final long elapsed = System.currentTimeMillis() - t0;
        final long fires = leader.service.flushTimerFireCount() - before[leader.id];
        final long expected = elapsed / 250;
        System.out.println("[FLUSH] " + what + ": " + fires + " fires in " + elapsed + " ms (one chain: ~" + expected + ")");
        assertTrue(what + ": one chain, " + fires + " fires in " + elapsed + " ms",
                fires >= expected / 2 && fires <= expected + 2);
        for (MeClusterHarness.Member m : h.members) {
            if (m.live && m != leader) {
                assertEquals(what + ": follower " + m.id + " fires nothing", before[m.id], m.service.flushTimerFireCount());
            }
        }
    }

    /** The leadership term as the client learned it from the cluster (independent of the service). */
    private static long currentTerm(final AeronCluster client, final MeClusterHarness.Member x) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            client.pollEgress();
            if (client.leaderMemberId() == x.id && client.leadershipTermId() >= 0) {
                return client.leadershipTermId();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the client never saw member " + x.id + " lead");
    }

    private static int templateOf(final DirectBuffer buffer, final int offset) {
        return buffer.getShort(offset + 2, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    private static final class CloseHelperQuiet {
        static void close(final AutoCloseable c) {
            org.agrona.CloseHelper.quietClose(c);
        }
    }
}
