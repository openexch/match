// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.NodeReadiness;
import io.aeron.Aeron;
import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * cluster-kit#15 regression: Aeron fires {@code onRoleChange} only on role
 * TRANSITIONS, so a node that boots straight into FOLLOWER and stays there (a
 * rolling restart into a live cluster) never got the callback. NodeReadiness
 * then never observed a role: /ready answered "catching up" forever and
 * SnapshotLogPruner never pruned on that node (observed live 2026-08-12).
 *
 * <p>The fix polls {@code cluster.role()} in {@code doBackgroundWork} and feeds
 * ONLY readiness on change. This test drives the tick path with a stub Cluster
 * and never invokes {@code onRoleChange} — exactly the boot-and-stay-FOLLOWER
 * shape — and asserts the node still becomes ready.</p>
 */
public class FollowerReadinessTest {

    @Test
    public void bootTimeFollowerBecomesReadyWithoutOnRoleChange() throws Exception {
        final AppClusteredService service = new AppClusteredService();
        inject(service, "cluster", new StubCluster(Cluster.Role.FOLLOWER));
        final NodeReadiness readiness = readinessOf(service);
        readiness.started(); // what onStart signals; onRoleChange is never fired

        assertFalse("started but no role observed yet must NOT be ready ("
                + readiness.describe() + ")", readiness.ready());

        service.doBackgroundWork(System.nanoTime());

        assertTrue("one duty cycle must observe FOLLOWER via cluster.role() and become ready, "
                + "with onRoleChange never fired (cluster-kit#15); was: " + readiness.describe(),
                readiness.ready());
    }

    @Test
    public void candidateObservationMustNotReportReady() throws Exception {
        final AppClusteredService service = new AppClusteredService();
        inject(service, "cluster", new StubCluster(Cluster.Role.CANDIDATE));
        final NodeReadiness readiness = readinessOf(service);
        readiness.started();

        service.doBackgroundWork(System.nanoTime());

        assertFalse("an election in flight must never report ready; was: "
                + readiness.describe(), readiness.ready());
    }

    // ==================== helpers ====================

    private static NodeReadiness readinessOf(final AppClusteredService service) throws Exception {
        final Field f = AppClusteredService.class.getDeclaredField("readiness");
        f.setAccessible(true);
        return (NodeReadiness) f.get(service);
    }

    private static void inject(final AppClusteredService service, final String field,
                               final Object value) throws Exception {
        final Field f = AppClusteredService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    /**
     * Everything a boot-time member's duty cycle touches: {@code role()} and
     * {@code logPosition()}. The rest is unreachable from doBackgroundWork and
     * throws so any new dependency fails loudly here instead of passing vacuously.
     */
    private static final class StubCluster implements Cluster {
        private final Role role;

        StubCluster(final Role role) {
            this.role = role;
        }

        public Role role() {
            return role;
        }

        public long logPosition() {
            return 0;
        }

        public int memberId() {
            return 1;
        }

        public Aeron aeron() {
            throw new UnsupportedOperationException();
        }

        public ClusteredServiceContainer.Context context() {
            throw new UnsupportedOperationException();
        }

        public ClientSession getClientSession(final long clusterSessionId) {
            throw new UnsupportedOperationException();
        }

        public Collection<ClientSession> clientSessions() {
            throw new UnsupportedOperationException();
        }

        public void forEachClientSession(final Consumer<? super ClientSession> action) {
            throw new UnsupportedOperationException();
        }

        public boolean closeClientSession(final long clusterSessionId) {
            throw new UnsupportedOperationException();
        }

        public long time() {
            throw new UnsupportedOperationException();
        }

        public TimeUnit timeUnit() {
            throw new UnsupportedOperationException();
        }

        public boolean scheduleTimer(final long correlationId, final long deadline) {
            throw new UnsupportedOperationException();
        }

        public boolean cancelTimer(final long correlationId) {
            throw new UnsupportedOperationException();
        }

        public long offer(final DirectBuffer buffer, final int offset, final int length) {
            throw new UnsupportedOperationException();
        }

        public long offer(final DirectBufferVector[] vectors) {
            throw new UnsupportedOperationException();
        }

        public long tryClaim(final int length, final BufferClaim bufferClaim) {
            throw new UnsupportedOperationException();
        }

        public IdleStrategy idleStrategy() {
            throw new UnsupportedOperationException();
        }
    }
}
