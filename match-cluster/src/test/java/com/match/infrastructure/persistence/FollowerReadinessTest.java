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

/** Initial FOLLOWER needs real consensus/application evidence even without a role callback. */
public class FollowerReadinessTest {

    @Test
    public void bootTimeFollowerBecomesReadyWithoutOnRoleChange() throws Exception {
        final AppClusteredService service = new AppClusteredService();
        final StubCluster cluster = new StubCluster(Cluster.Role.FOLLOWER);
        inject(service, "cluster", cluster);
        final NodeReadiness readiness = readinessOf(service);
        readiness.started();
        try (ConsensusFixture fixture = new ConsensusFixture()) {
            service.readinessConsensus(fixture.context);
            long now = System.nanoTime();
            service.doBackgroundWork(now);
            assertFalse("role alone must not hide lag", readiness.ready());
            cluster.applied = 100;
            service.doBackgroundWork(now + 20_000_000);
            assertTrue("caught up initial FOLLOWER without callback: " + readiness.describe(), readiness.ready());
            fixture.context.electionStateCounter().set(io.aeron.cluster.ElectionState.CANVASS.code());
            service.doBackgroundWork(now + 40_000_000);
            assertFalse("consensus election wins over stale service role", readiness.ready());
        }
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

    private static final class ConsensusFixture implements AutoCloseable {
        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("follower-readiness-");
        final org.agrona.concurrent.status.CountersManager counters = new org.agrona.concurrent.status.CountersManager(
                new org.agrona.concurrent.UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(8192)),
                new org.agrona.concurrent.UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(2048)));
        final io.aeron.cluster.service.ClusterMarkFile mark = new io.aeron.cluster.service.ClusterMarkFile(
                dir.resolve(io.aeron.cluster.service.ClusterMarkFile.FILENAME).toFile(),
                io.aeron.cluster.codecs.mark.ClusterComponentType.CONSENSUS_MODULE,
                io.aeron.cluster.service.ClusterMarkFile.ERROR_BUFFER_MIN_LENGTH,
                System::currentTimeMillis, 0, 4096);
        final io.aeron.cluster.ConsensusModule.Context context = new io.aeron.cluster.ConsensusModule.Context()
                .clusterMarkFile(mark).clusterClock(new io.aeron.cluster.MillisecondClusterClock())
                .commitPositionCounter(counter(100)).leadershipTermIdCounter(counter(7))
                .electionCounter(counter(2))
                .electionStateCounter(counter(io.aeron.cluster.ElectionState.CLOSED.code()))
                .moduleStateCounter(counter(io.aeron.cluster.ConsensusModule.State.ACTIVE.code()))
                .clusterNodeRoleCounter(counter(Cluster.Role.FOLLOWER.code()));
        ConsensusFixture() throws Exception { mark.updateActivityTimestamp(System.currentTimeMillis()); }
        private io.aeron.Counter counter(long value) {
            int id = counters.allocate("readiness test"); counters.setCounterRegistrationId(id, 100 + id);
            var c = new io.aeron.Counter(counters, id); c.set(value); return c;
        }
        public void close() throws Exception {
            mark.close();
            try (var paths = java.nio.file.Files.walk(dir)) {
                for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { java.nio.file.Files.delete(p); }
            }
        }
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
        private long applied;

        StubCluster(final Role role) {
            this.role = role;
        }

        public Role role() {
            return role;
        }

        public long logPosition() {
            return applied;
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
