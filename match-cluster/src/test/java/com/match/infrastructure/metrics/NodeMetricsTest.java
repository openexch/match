// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.metrics;

import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.*;

/**
 * The service thread stamps the snapshot position and hash without allocating, and a reader still sees both
 * values of one stamp.
 */
public class NodeMetricsTest {

    private static final int WARMUP = 200_000;
    private static final int MEASURED = 100_000;
    // A per-call allocation of even one small object over MEASURED calls is megabytes; this only absorbs
    // the probe's own bookkeeping.
    private static final long SLACK_BYTES = 16_384;

    @Test
    public void stampingTheSnapshotStateAllocatesNothingOnTheStampingThread() {
        final com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeAllocationCounting(bean);
        final NodeMetrics metrics = new NodeMetrics();
        for (int i = 0; i < WARMUP; i++) {
            metrics.stampSnapshotState(i, i & NodeMetrics.MAX_STATE_HASH);
        }
        final long before = bean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            metrics.stampSnapshotState(i, i & NodeMetrics.MAX_STATE_HASH);
        }
        final long allocated = bean.getCurrentThreadAllocatedBytes() - before;
        assertTrue("stamping " + MEASURED + " snapshot states allocated " + allocated + " byte(s)",
                allocated < SLACK_BYTES);
        final NodeMetrics.SnapshotState last = metrics.snapshotState();
        assertEquals(MEASURED - 1, last.logPosition());
        assertEquals(MEASURED - 1, last.stateHash());
    }

    @Test
    public void aStampIsReadBackAsOnePair() {
        final NodeMetrics metrics = new NodeMetrics();
        assertEquals(NodeMetrics.NEVER, metrics.snapshotState().logPosition());
        assertFalse(metrics.snapshotState().hashKnown());
        metrics.stampSnapshotState(4_096L, 0xFFFF_FFFFL);
        assertEquals(4_096L, metrics.snapshotState().logPosition());
        assertEquals(0xFFFF_FFFFL, metrics.snapshotState().stateHash());
        metrics.stampSnapshotState(8_192L, NodeMetrics.UNKNOWN_HASH);
        assertEquals(8_192L, metrics.snapshotState().logPosition());
        assertFalse(metrics.snapshotState().hashKnown());
    }

    @Test
    public void aRefusedStampLeavesThePreviousPairReadable() {
        final NodeMetrics metrics = new NodeMetrics();
        metrics.stampSnapshotState(4_096L, 7L);
        assertThrows(IllegalArgumentException.class, () -> metrics.stampSnapshotState(8_192L, -2L));
        assertEquals(4_096L, metrics.snapshotState().logPosition());
        assertEquals(7L, metrics.snapshotState().stateHash());
    }

    private static void assumeAllocationCounting(final com.sun.management.ThreadMXBean bean) {
        org.junit.Assume.assumeTrue("per-thread allocation counting is supported",
                bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
    }
}
