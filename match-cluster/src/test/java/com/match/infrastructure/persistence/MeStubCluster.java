// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.Aeron;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NoOpIdleStrategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * RC2 S6: a minimal {@link Cluster} for driving one {@link AppClusteredService} in process (as the AE's
 * TermTimerIdAfterApplierTest): settable role and cluster time, recorded timer schedules (optionally fed into
 * an {@link ExpiredTimerCountModel}), and a settable set of restored client sessions.
 */
final class MeStubCluster implements Cluster {
    Role role = Role.LEADER;
    long time = 1_000_000L;
    long logPosition = 4_096;
    final List<long[]> schedules = new ArrayList<>(); // {correlationId, deadline}
    final List<ClientSession> sessions = new ArrayList<>();
    ExpiredTimerCountModel consensusModule;

    public Role role() { return role; }
    public long logPosition() { return logPosition; }
    public int memberId() { return 0; }
    public Aeron aeron() { throw new UnsupportedOperationException(); }
    public ClusteredServiceContainer.Context context() { throw new UnsupportedOperationException(); }

    public ClientSession getClientSession(final long id) {
        for (ClientSession s : sessions) {
            if (s.id() == id) {
                return s;
            }
        }
        return null;
    }

    public Collection<ClientSession> clientSessions() { return sessions; }

    public void forEachClientSession(final Consumer<? super ClientSession> action) {
        sessions.forEach(action);
    }

    public boolean closeClientSession(final long id) { throw new UnsupportedOperationException(); }
    public long time() { return time; }
    public TimeUnit timeUnit() { return TimeUnit.MILLISECONDS; }

    public boolean scheduleTimer(final long correlationId, final long deadline) {
        schedules.add(new long[] {correlationId, deadline});
        if (consensusModule != null) {
            consensusModule.onScheduleTimer(correlationId, deadline);
        }
        return true;
    }

    public boolean cancelTimer(final long correlationId) { throw new UnsupportedOperationException(); }
    public IdleStrategy idleStrategy() { return NoOpIdleStrategy.INSTANCE; }
    public long offer(final org.agrona.DirectBuffer b, final int o, final int l) { throw new UnsupportedOperationException(); }
    public long offer(final io.aeron.DirectBufferVector[] v) { throw new UnsupportedOperationException(); }
    public long tryClaim(final int l, final io.aeron.logbuffer.BufferClaim c) { throw new UnsupportedOperationException(); }

    List<Long> idsSince(final int from) {
        final List<Long> ids = new ArrayList<>();
        for (int i = from; i < schedules.size(); i++) {
            ids.add(schedules.get(i)[0]);
        }
        return ids;
    }

    /** Wire {@code cluster} into a service without onStart (no metrics server, no durability). */
    static AppClusteredService attach(final AppClusteredService service, final MeStubCluster cluster) {
        try {
            final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("cluster");
            f.setAccessible(true);
            f.set(service, cluster);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        service.onRoleChange(cluster.role);
        return service;
    }
}
