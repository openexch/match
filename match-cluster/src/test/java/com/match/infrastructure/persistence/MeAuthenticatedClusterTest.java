// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.security.AuthenticationException;
import io.aeron.security.CredentialsSupplier;
import io.aeron.security.NullCredentialsSupplier;
import org.agrona.CloseHelper;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S6 9.2: three ME members behind the real authenticator and class table. Credential-less, wrong-secret
 * and class-mismatch clients are rejected at connect; FORWARDER template 9 is journaled on every member; ADMIN
 * template 10 is refused on every member (equal journals); a member restarted from a snapshot taken while the
 * FORWARDER session was open judges that session's later frames like the others (the restored principal).
 */
public class MeAuthenticatedClusterTest {

    private static void expectRejected(final MeClusterHarness h, final String what, final CredentialsSupplier creds) {
        try {
            final AeronCluster c = AeronCluster.connect(new AeronCluster.Context()
                    .credentialsSupplier(creds)
                    .egressListener((sessionId, ts, buffer, offset, length, hdr) -> { })
                    .egressChannel("aeron:udp?endpoint=localhost:0")
                    .ingressChannel("aeron:udp?term-length=1m")
                    .aeronDirectoryName(new java.io.File(h.root, "client").getAbsolutePath())
                    .ingressEndpoints(h.liveIngressEndpoints())
                    .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5)));
            CloseHelper.quietClose(c);
            fail(what + ": connected");
        } catch (final AuthenticationException expected) {
            System.out.println("[AUTH] " + what + " rejected: " + expected.getMessage());
        }
    }

    private static long rejectedOnAll(final MeClusterHarness h) {
        long n = 0;
        for (MeClusterHarness.Member m : h.members) {
            n += m.live ? m.authRejected() : 0;
        }
        return n;
    }

    @Test
    public void onlyIssuedCredentialsConnectAndTheClassIsJudgedOnEveryMember() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(25_100, "authn")) {
            h.startAll();
            final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            expectRejected(h, "no credential", new NullCredentialsSupplier());
            expectRejected(h, "wrong secret", TestAuth.wrongSecret());
            expectRejected(h, "class mismatch", TestAuth.classMismatch());
            assertTrue("the leader's authenticator rejected all three", leader.authRejected() >= 3);
            assertTrue(rejectedOnAll(h) >= 3);

            try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
                 AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin, () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));

                // FORWARDER template 9: journaled on every member
                assertTrue(MeClusterHarness.offer(forwarder, create(951, 0, 100, 0, 59_000, 1), 10_000) > 0);
                assertTrue(MeClusterHarness.await(forwarder, () -> h.outcomes.containsKey(MeClusterHarness.key(951, 0))
                        && records3(h, 951, 0) == MeClusterHarness.MEMBERS, 20_000));
                final long leg = h.outcomes.get(MeClusterHarness.key(951, 0))[1];
                assertTrue(MeClusterHarness.offer(forwarder, cancel(951, 1, 100, leg), 10_000) > 0);
                assertTrue(MeClusterHarness.await(forwarder, () -> records3(h, 951, 1) == MeClusterHarness.MEMBERS, 20_000));

                // ADMIN template 10 (an effective release): refused on every member, no record 4 anywhere
                final byte[] release = releaseFrame(1, order(951));
                assertTrue(MeClusterHarness.offer(admin, release, 10_000) > 0);
                assertTrue("refused on every member", MeClusterHarness.await(admin, () -> {
                    for (MeClusterHarness.Member m : h.members) {
                        if (m.unauthorized() < 1) {
                            return false;
                        }
                    }
                    return true;
                }, 20_000));
                assertFalse("no record 4 on any member", h.releases.containsKey(1L));

                // snapshot while the FORWARDER session is open, then restart a follower from it
                h.snapshot(leader, admin);
                final MeClusterHarness.Member follower = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
                follower.close();
                follower.start();
                assertTrue(h.awaitJoined(follower, leader, admin, 30_000));
                final long refusedBefore = follower.unauthorized();
                // the restored FORWARDER session's next frames: a durable create applied, an ADMIN release refused
                assertTrue(MeClusterHarness.offer(forwarder, create(952, 0, 100, 1, 61_000, 1), 10_000) > 0);
                assertTrue("the restarted member journals the restored FORWARDER's frame",
                        MeClusterHarness.await(forwarder, () -> records3(h, 952, 0) == MeClusterHarness.MEMBERS, 20_000));
                assertTrue(MeClusterHarness.offer(admin, releaseFrame(2, order(951)), 10_000) > 0);
                assertTrue("the restarted member refuses ADMIN template 10 like the others",
                        MeClusterHarness.await(admin, () -> follower.unauthorized() > refusedBefore, 20_000));
                // the FORWARDER release frame is the effective one, on every member
                assertTrue(MeClusterHarness.offer(forwarder, releaseFrame(3, order(951)), 10_000) > 0);
                assertTrue(MeClusterHarness.await(forwarder, () -> h.releases.containsKey(3L), 20_000));
                assertTrue(h.awaitQuiescence(forwarder, 20_000));
                assertEquals("equal journals on every member", 0, h.unionMismatches.get());
                assertFalse(h.releases.containsKey(1L) || h.releases.containsKey(2L));
            }
        }
    }

    /** Members whose journal holds a record 3 for (h, l). */
    private static int records3(final MeClusterHarness h, final long idHigh, final long attempt) {
        final long idLow = BehaviourTwoLog.coreIdLow(attempt, attempt == 0 ? CREATE : CANCEL);
        int n = 0;
        for (MeClusterHarness.Member m : h.members) {
            synchronized (m.frames) {
                for (byte[] f : m.frames) {
                    if (MeClusterHarness.templateId(f) == 3) {
                        final var o = MeClusterHarness.outcome(f);
                        if (o.commandIdHigh() == idHigh && o.commandIdLow() == idLow) {
                            n++;
                            break;
                        }
                    }
                }
            }
        }
        return n;
    }

}
