// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.auth.ClusterSessionClass;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * RC2 S6 contract 5.4 (FIX-8): the service registers, at onStart, the sessions the container restored from a
 * snapshot (they never see onSessionOpen), so a restored leader's keep-warm and egress reach them; R0 applies to
 * each restored principal.
 *
 * <p>Two unit cases drive the real onStart with {@link MeStubCluster}. The cluster case opens a client session,
 * takes a snapshot, restarts BOTH followers from it one at a time (each learns the session only from the
 * container snapshot), waits until each has closed its startup election, then kills the leader so a restored
 * member leads a normal failover term the client follows. A whole-cluster restart cannot carry a session:
 * Aeron 1.53 closes every open session with TIMEOUT on a leader-startup election
 * ({@code SessionManager.prepareSessionsForNewTerm(isStartup)}), which is also what happens when the leader dies
 * while a restarted member is still inside its startup election (hence {@code awaitJoined}).</p>
 */
public class MeRestoredEgressTest {

    private static final int CLUSTER_HEARTBEAT = 21;

    private static int templateOf(final byte[] frame) {
        return new UnsafeBuffer(frame).getShort(2, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    @Test
    public void restoredLeaderEgressReachesPreSnapshotSessions() {
        final MeStubCluster c = new MeStubCluster();
        final TestAuth.StubSession restored = new TestAuth.StubSession(11, TestAuth.principal(ClusterSessionClass.FORWARDER));
        c.sessions.add(restored); // restored by the container before onStart
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> { throw new AssertionError("failFast"); };
        try {
            s.onStart(c, null);
            s.onRoleChange(c.role);
            s.onNewLeadershipTermEvent(4, c.logPosition, c.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
            s.onTimerEvent(s.lastArmedFlushTimerId(), c.time + 250);
            assertFalse("the restored session received egress", restored.offered.isEmpty());
            assertEquals("the keep-warm reached the session no onSessionOpen announced", CLUSTER_HEARTBEAT,
                    templateOf(restored.offered.get(0)));
        } finally {
            s.onTerminate(c);
        }
    }

    @Test
    public void aPrincipalLessRestoredSessionHaltsTheNode() {
        final MeStubCluster c = new MeStubCluster();
        c.sessions.add(new TestAuth.StubSession(12, new byte[0])); // a pre-S6 container snapshot
        final AppClusteredService s = new AppClusteredService();
        final int[] stops = {0};
        s.failFast = () -> stops[0]++;
        try {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.onStart(c, null));
            assertTrue(e.getMessage(), e.getMessage().contains("clean genesis required"));
            assertEquals(1, stops[0]);
        } finally {
            s.onTerminate(c);
        }
    }

    @Test
    public void restoredLeaderEgressReachesPreSnapshotSessionsInACluster() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(24_500, "restored-egress")) {
            h.startAll();
            final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            final java.util.concurrent.atomic.AtomicInteger heartbeats = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger books = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicInteger newLeaders = new java.util.concurrent.atomic.AtomicInteger();
            final io.aeron.cluster.client.AeronCluster client = h.connect(TestAuth.credentials(ClusterSessionClass.ADMIN),
                    new io.aeron.cluster.client.EgressListener() {
                        public void onMessage(final long sessionId, final long ts, final org.agrona.DirectBuffer buffer,
                                              final int offset, final int length, final io.aeron.logbuffer.Header hdr) {
                            final int t = buffer.getShort(offset + 2, java.nio.ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
                            if (t == CLUSTER_HEARTBEAT) {
                                heartbeats.incrementAndGet();
                            } else if (t == 22 || t == 25) { // BookSnapshot, BookDelta
                                books.incrementAndGet();
                            }
                        }

                        public void onNewLeader(final long sessionId, final long term, final int leaderId,
                                                final String endpoints) {
                            newLeaders.incrementAndGet();
                        }
                    }, 60_000);
            try {
                final long sessionId = client.clusterSessionId();
                assertTrue(MeClusterHarness.offer(client,
                        BehaviourTwoLog.engineConfig(1, 2, com.match.application.engine.BehaviourTwoHarness.G, 12, 1_000),
                        10_000) > 0);
                assertTrue("record 5 on every member", MeClusterHarness.await(client,
                        () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                h.snapshot(leader, client);
                for (MeClusterHarness.Member m : h.members) {
                    if (m != leader) {
                        m.close();
                        m.start();
                        assertTrue("member " + m.id + " joined", h.awaitJoined(m, leader, client, 30_000));
                    }
                }
                final int newLeaders0 = newLeaders.get();
                leader.close();
                final MeClusterHarness.Member restoredLeader = h.awaitLeader(leader, 30_000);
                assertTrue("the restored session follows the new leader (NewLeaderEvent, no ingress)",
                        MeClusterHarness.await(client, () -> newLeaders.get() > newLeaders0, 30_000));
                assertEquals(sessionId, client.clusterSessionId());
                assertEquals(restoredLeader.id, client.leaderMemberId());
                assertTrue("non-vacuous: the new leader learned the session from the container snapshot",
                        restoredLeader.service.restoredSessionCount() >= 1);
                final int hb0 = heartbeats.get();
                final int books0 = books.get();
                // A second client's order produces book data; it connects on another thread while this one keeps
                // the restored session alive with keep-alives.
                final java.util.concurrent.atomic.AtomicReference<Object> second = new java.util.concurrent.atomic.AtomicReference<>();
                final Thread connector = new Thread(() -> {
                    try {
                        second.set(h.connect(ClusterSessionClass.FORWARDER));
                    } catch (Throwable t) {
                        second.set(t);
                    }
                }, "forwarder-connect");
                connector.start();
                assertTrue(MeClusterHarness.await(client, () -> second.get() != null, 60_000));
                connector.join();
                if (second.get() instanceof Throwable t) {
                    throw new AssertionError("second client did not connect", t);
                }
                try (io.aeron.cluster.client.AeronCluster forwarder = (io.aeron.cluster.client.AeronCluster) second.get()) {
                    assertTrue(MeClusterHarness.offer(forwarder,
                            DurableLedgerV2Test.create(901, 0, 100, 0, 59_000, 1), 10_000) > 0);
                    final long t0 = System.currentTimeMillis();
                    final boolean reached = MeClusterHarness.await(client, () -> {
                        forwarder.pollEgress();
                        return heartbeats.get() > hb0 && books.get() > books0;
                    }, 2_500);
                    System.out.println("[RESTORED] leader " + restoredLeader.id + " heartbeats " + hb0 + "->"
                            + heartbeats.get() + " books " + books0 + "->" + books.get() + " in "
                            + (System.currentTimeMillis() - t0) + " ms");
                    assertTrue("the restored leader's keep-warm and a book update reach the pre-snapshot session "
                            + "within 2.5 s (heartbeats " + hb0 + "->" + heartbeats.get() + ", books " + books0
                            + "->" + books.get() + ")", reached);
                }
            } finally {
                org.agrona.CloseHelper.quietClose(client);
            }
        }
    }
}
