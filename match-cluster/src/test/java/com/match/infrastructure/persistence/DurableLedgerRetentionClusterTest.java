// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.persistence.MeClusterHarness.Member;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.CloseHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.MeClusterHarness.key;
import static org.junit.Assert.*;

/**
 * RC2 S4 contract 9.1 (H3, topology J-ME3) on a real three-member ME cluster in one JVM
 * ({@link MeClusterHarness}). The reference client acts as the core: core-shaped ids
 * ({@code idHigh = omsOrderId = n}, {@code idLow = attempt << 2 | kind}); it releases an order only
 * after the members' journals carry the outcome of every one of its commands, and it re-sends a
 * command only while no journal carries its record 3.
 */
public class DurableLedgerRetentionClusterTest {

    private static final int C = 1_000;
    private static final int BATCH = 32;
    private static final long EVIDENCE_TIMEOUT_MS = 10_000;

    private static int portBase(int slot) {
        return 21_000 + 1_000 * slot + 100 * ThreadLocalRandom.current().nextInt(0, 5);
    }

    /** The client side of one command: its frame, its id and when it was first sent. */
    private static final class Command {
        final byte[] frame;
        final String key;
        long firstSentAt;

        Command(byte[] frame, long h, long l) {
            this.frame = frame;
            this.key = key(h, l);
        }
    }

    private static final class Client {
        final MeClusterHarness h;
        AeronCluster session;
        long maxWaitMs;

        Client(MeClusterHarness h) throws InterruptedException {
            this.h = h;
            this.session = h.connect();
        }

        void send(Command c) {
            if (c.firstSentAt == 0) {
                c.firstSentAt = System.currentTimeMillis();
            }
            assertTrue("offer accepted", MeClusterHarness.offer(session, c.frame, 10_000) > 0);
        }

        void send(byte[] frame) {
            assertTrue("offer accepted", MeClusterHarness.offer(session, frame, 10_000) > 0);
        }

        /** Wait for every command's record 3 in the union of the journals; returns the outcomes. */
        List<long[]> evidence(List<Command> commands) {
            final boolean ok = MeClusterHarness.await(session, () -> {
                for (Command c : commands) {
                    if (!h.outcomes.containsKey(c.key)) {
                        return false;
                    }
                }
                return true;
            }, EVIDENCE_TIMEOUT_MS);
            final List<long[]> out = new ArrayList<>();
            for (Command c : commands) {
                final long[] o = h.outcomes.get(c.key);
                assertNotNull("no evidence for command " + c.key + " within " + EVIDENCE_TIMEOUT_MS + " ms", ok ? o : null);
                maxWaitMs = Math.max(maxWaitMs, System.currentTimeMillis() - c.firstSentAt);
                out.add(o);
            }
            return out;
        }

        /** Follow the new leader: a fresh session through the live members. */
        void reconnect() throws InterruptedException {
            CloseHelper.quietClose(session);
            session = h.connect();
        }

        void close() {
            CloseHelper.quietClose(session);
        }
    }

    private static void fresh(MeClusterHarness h, Client client) throws InterruptedException {
        // RC2 S6 (R2): EngineConfig is ADMIN-only; the durable lane stays on the FORWARDER client.
        try (AeronCluster admin = h.connect(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN)) {
            assertTrue("offer accepted", MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, C), 10_000) > 0);
            assertTrue("record 5 on every member", MeClusterHarness.await(admin,
                    () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
        }
    }

    @Test
    public void lifetimePastCapacityAcrossLeaderChange() throws Exception {
        final int orders = Integer.getInteger("me3.orders", 20_000);
        final long started = System.currentTimeMillis();
        try (MeClusterHarness h = new MeClusterHarness(portBase(0), "lifetime")) {
            h.startAll();
            Member leader = h.awaitLeader(null, 30_000);
            final Client client = new Client(h);
            try {
                fresh(h, client);
                final Map<Long, Long> legs = new HashMap<>();
                byte[] openRelease = null;
                long openReleaseSeq = 0;
                Member closed = null;
                boolean snapshotted = false;
                for (long base = 1; base <= orders; base += BATCH) {
                    final int n = (int) Math.min(BATCH, orders - base + 1);
                    final List<Command> creates = new ArrayList<>();
                    for (long o = base; o < base + n; o++) {
                        creates.add(new Command(create(o, 0, 100, 0, 59_000 + (o % 100), 1), o, 0));
                    }
                    for (Command c : creates) {
                        client.send(c);
                    }
                    if (closed == null && base + n > orders / 2) {
                        // The leader is closed with this batch in flight: the client follows the new
                        // leader and re-sends, with the same ids, every command without evidence
                        // and the open release frame.
                        closed = leader;
                        System.out.println("[ME3] closing leader member " + closed.id + " at order " + base);
                        closed.close();
                        leader = h.awaitLeader(closed, 30_000);
                        client.reconnect();
                        for (Command c : creates) {
                            if (!h.outcomes.containsKey(c.key)) {
                                client.send(c);
                            }
                        }
                        if (openRelease != null && !h.releases.containsKey(openReleaseSeq)) {
                            client.send(openRelease);
                        }
                    }
                    final List<long[]> created = client.evidence(creates);
                    final List<Command> cancels = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        final long o = base + i;
                        final long[] r = created.get(i);
                        assertEquals("CREATE " + o + " applied", DurableCommandOutcome.APPLIED, r[0]);
                        legs.put(o, r[1]);
                        cancels.add(new Command(cancel(o, 1, 100, r[1]), o, (1 << 2) | 1));
                    }
                    for (Command c : cancels) {
                        client.send(c);
                    }
                    for (long[] r : client.evidence(cancels)) {
                        assertEquals("CANCEL applied", DurableCommandOutcome.APPLIED, r[0]);
                    }
                    final long[][] entries = new long[n][];
                    for (int i = 0; i < n; i++) {
                        entries[i] = order(base + i);
                    }
                    openReleaseSeq = base;
                    openRelease = releaseFrame(base, entries);
                    client.send(openRelease);
                    if (h.maxLedgerSize.get() > 128) {
                        fail("sampled inflight <= 128 on every member, was " + h.maxLedgerSize.get() + " at order " + base);
                    }
                    if (!snapshotted && base + n > orders / 4) {
                        h.snapshot(leader, client.session);
                        snapshotted = true;
                        System.out.println("[ME3] snapshot taken on member " + leader.id + " at order " + (base + n));
                    }
                    if (closed != null && !closed.live && base + n > orders * 3 / 5) {
                        System.out.println("[ME3] restarting member " + closed.id + " from its snapshot plus log");
                        closed.start();
                    }
                }
                final long lastRelease = openReleaseSeq;
                assertTrue("the last release frame applied", MeClusterHarness.await(client.session,
                        () -> h.releases.containsKey(lastRelease), 10_000));
                assertNotNull("the leader was closed", closed);
                assertTrue("the closed member was restarted", closed.live);
                assertTrue("quiescence", h.awaitQuiescence(client.session, 60_000));

                assertEquals("CAPACITY never journaled", 0, h.capacityOutcomes.get());
                assertTrue("sampled inflight <= 128 throughout, was " + h.maxLedgerSize.get(), h.maxLedgerSize.get() <= 128);
                assertTrue("no command waited more than 10 s for evidence, max " + client.maxWaitMs, client.maxWaitMs <= 10_000);
                assertEquals("journals agree on every journalSeq", 0, h.unionMismatches.get());
                // Exactly one ME leg per CREATE id, across the union (deduplicated by journalSeq).
                final Map<Long, Integer> appliedCreates = new HashMap<>();
                long expectedSeq = 1;
                for (Map.Entry<Long, byte[]> e : h.union.entrySet()) {
                    assertEquals("the union is dense", expectedSeq++, (long) e.getKey());
                    if (MeClusterHarness.templateId(e.getValue()) == 3) {
                        final var o = MeClusterHarness.outcome(e.getValue());
                        if (o.commandKind() == 0 && o.result() == DurableCommandOutcome.APPLIED) {
                            appliedCreates.merge(o.commandIdHigh(), 1, Integer::sum);
                        }
                    }
                }
                assertEquals("every CREATE id has one APPLIED record 3", orders, appliedCreates.size());
                for (Map.Entry<Long, Integer> e : appliedCreates.entrySet()) {
                    assertEquals("CREATE " + e.getKey() + ": exactly one leg", 1, (int) e.getValue());
                }
                final byte[] block = h.members[0].ledgerBlock();
                for (Member m : h.members) {
                    assertTrue("member " + m.id + " live", m.live);
                    assertEquals("member " + m.id + ": no fail-fast", 0, m.failFast.get());
                    assertEquals("member " + m.id + ": CAPACITY counter", 0, S4Probe.capacity(m.engine()));
                    assertArrayEquals("member " + m.id + ": ledger v2 block byte-equal", block, m.ledgerBlock());
                    assertEquals("member " + m.id + ": empty inflight", 0, S4Probe.ledgerSize(m.engine()));
                    assertArrayEquals("member " + m.id + ": one tombstone range", new long[]{1, orders},
                            S4Probe.ranges(m.engine()));
                    assertEquals(0, S4Probe.floorCount(m.engine()));
                }
                System.out.println("[ME3] " + orders + " orders (" + (orders / C) + " x C) in "
                        + (System.currentTimeMillis() - started) + " ms; max evidence wait " + client.maxWaitMs
                        + " ms; max sampled inflight " + h.maxLedgerSize.get() + "; union " + h.union.size() + " records");
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void releaseFromNonCommittedEntryHasNoEffect() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(portBase(6), "uncommitted")) {
            h.startAll();
            final Member oldLeader = h.awaitLeader(null, 30_000);
            Client client = new Client(h);
            long oldTerm;
            try {
                fresh(h, client);
                for (long o = 1; o <= 10; o++) {
                    final Command c = new Command(create(o, 0, 100, 0, 59_000, 1), o, 0);
                    client.send(c);
                    final long leg = client.evidence(List.of(c)).get(0)[1];
                    final Command x = new Command(cancel(o, 1, 100, leg), o, 5);
                    client.send(x);
                    client.evidence(List.of(x));
                }
                for (Member m : h.members) {
                    assertTrue("member " + m.id + ": inflight 20", MeClusterHarness.await(client.session,
                            () -> S4Probe.ledgerSize(m.engine()) == 20, 10_000));
                }
                final List<Member> followers = new ArrayList<>();
                for (Member m : h.members) {
                    if (m != oldLeader) {
                        followers.add(m);
                    }
                }
                // The followers lose the log stream (not the consensus channel): the leader keeps its
                // role and appends the next entry alone; nobody can commit it.
                for (Member f : followers) {
                    f.logPartitioned = true;
                }
                final long[][] entries = new long[10][];
                for (int i = 0; i < 10; i++) {
                    entries[i] = upTo(i + 1, 5);
                }
                final byte[] frame = releaseFrame(77, entries);
                final long position = MeClusterHarness.offer(client.session, frame, 5_000);
                assertTrue("the leader accepted the offer: " + position, position > 0);
                final long[] before = oldLeader.livePositions();
                assertTrue("the old leader's log recording holds the entry, uncommitted",
                        MeClusterHarness.await(client.session, () -> oldLeader.livePositions()[0] > before[0], 2_000));
                final long[] after = oldLeader.livePositions();
                System.out.println("[ME3] old leader {recorded, commit} before " + java.util.Arrays.toString(before)
                        + " after " + java.util.Arrays.toString(after) + " role " + oldLeader.role());
                assertEquals("nothing committed past the partition", before[1], after[1]);
                for (Member f : followers) {
                    assertEquals("member " + f.id + ": inflight still 20 before the leader goes", 20,
                            S4Probe.ledgerSize(f.engine()));
                }
                client.close();
                oldLeader.close();
                for (Member f : followers) {
                    f.close();
                }
                final long leaderStop = oldLeader.logStopPosition();
                oldTerm = oldLeader.lastTerm()[0];
                for (Member f : followers) {
                    assertTrue("the entry exists in the old leader's log only: leader " + leaderStop + " > follower "
                            + f.id + " " + f.logStopPosition(), leaderStop > f.logStopPosition());
                }

                for (Member f : followers) {
                    f.start();
                }
                final Member newLeader = h.awaitLeader(oldLeader, 30_000);
                client = new Client(h);
                assertTrue(h.awaitQuiescence(client.session, 30_000));
                for (Member f : followers) {
                    assertEquals("member " + f.id + ": inflight 20", 20, S4Probe.ledgerSize(f.engine()));
                    assertEquals("member " + f.id + ": no record 4", 0, f.records4.get());
                }
                oldLeader.start();
                assertTrue(MeClusterHarness.await(client.session, () -> oldLeader.service != null
                        && S4Probe.hasLedgerV2(oldLeader.engine()) && S4Probe.ledgerSize(oldLeader.engine()) == 20
                        && oldLeader.lastJournalSeq.get() == newLeader.lastJournalSeq.get(), 30_000));
                assertTrue(h.awaitQuiescence(client.session, 30_000));
                assertEquals("the old leader never applied the uncommitted release", 0, oldLeader.records4.get());
                assertEquals("old leader: inflight 20", 20, S4Probe.ledgerSize(oldLeader.engine()));
                final byte[] block = newLeader.ledgerBlock();
                for (Member m : h.members) {
                    assertArrayEquals("member " + m.id + ": byte-equal ledger v2 block", block, m.ledgerBlock());
                }

                // Re-sent through the new leader, the frame applies once.
                client.send(frame);
                for (Member m : h.members) {
                    assertTrue("member " + m.id + ": inflight 0", MeClusterHarness.await(client.session,
                            () -> S4Probe.ledgerSize(m.engine()) == 0, 10_000));
                }
                assertTrue(h.awaitQuiescence(client.session, 30_000));
                int applied = 0;
                for (byte[] f : h.union.values()) {
                    if (MeClusterHarness.templateId(f) == 4 && MeClusterHarness.release(f).releaseSeq() == 77) {
                        applied++;
                    }
                }
                assertEquals("applied once (one record 4 in the union)", 1, applied);
                for (Member m : h.members) {
                    assertEquals("member " + m.id + ": no fail-fast", 0, m.failFast.get());
                }
                // The rejoined old leader's log was truncated to the new leader's history: its last
                // term is the new leader's, based below the position it had appended to alone.
                client.close();
                for (Member m : h.members) {
                    m.close();
                }
                final long[] oldLast = oldLeader.lastTerm();
                final long[] newLast = newLeader.lastTerm();
                assertTrue("a later term than the one the old leader led", oldLast[0] > oldTerm);
                assertArrayEquals("the old leader follows the new leader's term (id, base position)", newLast, oldLast);
                assertTrue("the term base " + oldLast[1] + " lies below the old leader's uncommitted end " + leaderStop,
                        oldLast[1] < leaderStop);
            } finally {
                client.close();
            }
        }
    }
}
