// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.cluster.service.ClientSession;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

/**
 * C-3: drainQueue() must bound the work one call does to EGRESS_DRAIN_MAX messages, so a freshly
 * promoted leader inheriting a full warm-standby omsEgressQueue cannot stall behind a single
 * unbounded drain before it processes any new command (the AE 60s failover-blindness mechanism).
 *
 * <p>drainQueue was moved out of the aeronBroadcaster anonymous inner class onto AppClusteredService
 * (behavior identical) precisely so it has a package-private test seam. The drain is exercised with
 * an EMPTY session list: flush() early-returns when there are no sessions, so drainQueue is not
 * reachable through flush(), but drainQueue itself still polls, byte-decrements, and counts the cap
 * with no sessions to offer to, which is exactly the outer loop this test needs to pin. Delivery to
 * a session is a separate concern already covered by EmbeddedClusterTest.
 *
 * <p>The config resolver (env / sysprop / default, and the refuse-at-startup posture for a bad
 * value) is a pure function tested directly.
 */
public class AppClusteredServiceDrainCapTest {

    private static final int MSG_LEN = 4;

    private static AppClusteredService.QueuedMessage msg() {
        return new AppClusteredService.QueuedMessage(new byte[]{1, 2, 3, 4}, MSG_LEN);
    }

    private static Queue<AppClusteredService.QueuedMessage> queueOf(final int n) {
        final Queue<AppClusteredService.QueuedMessage> q = new ArrayBlockingQueue<>(n);
        for (int i = 0; i < n; i++) {
            q.add(msg());
        }
        return q;
    }

    // ---- C-3 cap behavior ----

    @Test
    public void oneDrainCapsWork_remainderStays_nextDrainFinishes_counterClimbsOnce() {
        final AppClusteredService svc = new AppClusteredService();
        final int cap = AppClusteredService.egressDrainMax();
        final int extra = 25;
        final int total = cap + extra;

        final Queue<AppClusteredService.QueuedMessage> queue = queueOf(total);
        // Mirror how the real enqueue tracks bytes, so the drain's addAndGet(-length) is observable.
        final AtomicLong bytes = new AtomicLong((long) total * MSG_LEN);
        final List<ClientSession> noSessions = Collections.emptyList();

        assertEquals("drain-cap counter starts at 0", 0L, svc.drainCappedTotal());

        // First drain: capped. Processes exactly `cap` messages, leaves `extra` queued.
        final boolean delivered = svc.drainQueue(queue, bytes, noSessions);
        assertFalse("no sessions means nothing was delivered this drain", delivered);
        assertEquals("a capped drain processes at most the cap, leaving the remainder queued",
                extra, queue.size());
        assertEquals("bytes are decremented only for the `cap` processed messages",
                (long) extra * MSG_LEN, bytes.get());
        assertEquals("hitting the cap with work still queued increments the counter once",
                1L, svc.drainCappedTotal());

        // Second drain: finishes the remainder (extra < cap), so it does NOT hit the cap.
        final boolean delivered2 = svc.drainQueue(queue, bytes, noSessions);
        assertFalse(delivered2);
        assertEquals("the next drain takes the rest of the backlog", 0, queue.size());
        assertEquals("all bytes drained", 0L, bytes.get());
        assertEquals("an under-cap drain does NOT increment the counter", 1L, svc.drainCappedTotal());
    }

    @Test
    public void drainingExactlyCap_leavesNoBacklog_soNoCapEvent() {
        final AppClusteredService svc = new AppClusteredService();
        final int cap = AppClusteredService.egressDrainMax();

        final Queue<AppClusteredService.QueuedMessage> queue = queueOf(cap);
        final AtomicLong bytes = new AtomicLong((long) cap * MSG_LEN);

        final boolean delivered = svc.drainQueue(queue, bytes, Collections.emptyList());
        assertFalse(delivered);
        assertEquals("a queue of exactly cap drains fully in one call", 0, queue.size());
        // The counter measures DEFERRED backlog, not merely touching the cap boundary: with nothing
        // left behind it must stay 0.
        assertEquals("draining exactly cap leaves no backlog, so no cap event is counted",
                0L, svc.drainCappedTotal());
    }

    @Test
    public void underCapDrain_processesEverything_withNoCapEvent() {
        final AppClusteredService svc = new AppClusteredService();
        final int n = Math.max(1, AppClusteredService.egressDrainMax() / 2);

        final Queue<AppClusteredService.QueuedMessage> queue = queueOf(n);
        final AtomicLong bytes = new AtomicLong((long) n * MSG_LEN);

        svc.drainQueue(queue, bytes, Collections.emptyList());
        assertEquals("an under-cap queue drains fully", 0, queue.size());
        assertEquals("no bytes left", 0L, bytes.get());
        assertEquals("no cap event", 0L, svc.drainCappedTotal());
    }

    // ---- C-3 config resolution (pure) ----

    @Test
    public void resolveDrainMax_defaultsWhenUnset() {
        assertEquals(AppClusteredService.DEFAULT_EGRESS_DRAIN_MAX,
                AppClusteredService.resolveEgressDrainMax(null, null));
    }

    @Test
    public void resolveDrainMax_envWinsOverProp() {
        assertEquals(100, AppClusteredService.resolveEgressDrainMax("100", "200"));
    }

    @Test
    public void resolveDrainMax_propUsedWhenEnvUnset() {
        assertEquals(200, AppClusteredService.resolveEgressDrainMax(null, "200"));
    }

    @Test
    public void resolveDrainMax_refusesUnparseable() {
        try {
            AppClusteredService.resolveEgressDrainMax("not-a-number", null);
            fail("an unparseable cap must be refused at startup, not silently defaulted");
        } catch (IllegalArgumentException expected) {
            // strict-config house style: refuse rather than guess
        }
    }

    @Test
    public void resolveDrainMax_refusesZeroAndNegative() {
        try {
            AppClusteredService.resolveEgressDrainMax("0", null);
            fail("a zero cap must be refused at startup");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            AppClusteredService.resolveEgressDrainMax("-5", null);
            fail("a negative cap must be refused at startup");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
