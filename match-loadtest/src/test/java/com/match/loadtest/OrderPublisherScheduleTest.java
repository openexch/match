// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the coordinated-omission properties of the generator's fixed-rate timeline.
 *
 * <p>These are measurement-correctness rules, and their failure mode is silent: the run still
 * completes, still prints percentiles, and the percentiles are simply too good. Both tests stall
 * the consumer on purpose, because a generator that is keeping up cannot tell the two behaviours
 * apart — the bug only shows once the system is slower than the offered rate.</p>
 */
public class OrderPublisherScheduleTest {

    private static final int RATE = 1_000;               // one worker -> one slot per millisecond
    private static final long SLOT_NS = 1_000_000L;
    private static final int QUEUE_CAPACITY = 8;         // small, so a brief stall blocks the producer
    private static final int STALL_MS = 40;              // ~40 slots fall due while 8 fit in the queue

    private static LoadConfig config() {
        return LoadConfig.builder()
            .targetOrdersPerSecond(RATE)
            .workerThreads(1)
            .scenario(OrderScenario.BALANCED)
            .build();
    }

    /**
     * A stalled consumer must not erase the slots it stalled. The old loop rewound the schedule to
     * "now" and dropped anything that did not fit the queue, so the delay it caused disappeared from
     * the sample it was supposed to appear in.
     */
    @Test
    public void slotsStayOnTheFixedTimelineWhenTheConsumerStalls() throws Exception {
        final ManyToOneConcurrentArrayQueue<OrderRequest> queue =
            new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean measuring = new AtomicBoolean(true);   // no warmup: no rebase expected
        final Thread producer = start(queue, running, measuring);

        try {
            Thread.sleep(STALL_MS);                                // nobody drains: the producer blocks
            final List<OrderRequest> drained = drain(queue, 40);

            assertEquals("no slot may be skipped: consecutive orders are consecutive slots",
                0, offTimelineGaps(drained).size());

            long worstLateness = 0;
            for (final OrderRequest r : drained) {
                worstLateness = Math.max(worstLateness, r.enqueueTimeNs - r.scheduledNs);
            }
            assertTrue(
                "the stall must surface as lateness against the schedule, worst was " + worstLateness + "ns",
                worstLateness > 10 * SLOT_NS);
        } finally {
            stop(running, producer);
        }
    }

    /**
     * The warmup boundary is the one place a rewind is correct: JIT-era debt is not the measured
     * system's latency. It must happen exactly once, and it must actually discard the debt.
     */
    @Test
    public void scheduleRebasesExactlyOnceAtTheWarmupBoundary() throws Exception {
        final ManyToOneConcurrentArrayQueue<OrderRequest> queue =
            new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicBoolean measuring = new AtomicBoolean(false);  // still warming up
        final Thread producer = start(queue, running, measuring);

        try {
            Thread.sleep(STALL_MS);       // build warmup debt the producer must not carry across
            measuring.set(true);
            final List<OrderRequest> drained = drain(queue, 40);

            final List<Long> gaps = offTimelineGaps(drained);
            assertEquals("the schedule may jump once, at the warmup boundary: " + gaps, 1, gaps.size());
            assertTrue("the boundary jump must discard the warmup debt, it was " + gaps.get(0) + "ns",
                gaps.get(0) > 10 * SLOT_NS);
        } finally {
            stop(running, producer);
        }
    }

    /** Deltas between consecutive slots that are not exactly one slot apart. */
    private static List<Long> offTimelineGaps(final List<OrderRequest> drained) {
        final List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < drained.size(); i++) {
            final long delta = drained.get(i).scheduledNs - drained.get(i - 1).scheduledNs;
            if (delta != SLOT_NS) {
                gaps.add(delta);
            }
        }
        return gaps;
    }

    private static Thread start(
        final ManyToOneConcurrentArrayQueue<OrderRequest> queue,
        final AtomicBoolean running,
        final AtomicBoolean measuring
    ) {
        final OrderPublisher publisher = new OrderPublisher(
            0, queue, config(), new MetricsCollector(), new AtomicLong(), running, measuring, false);
        final Thread t = new Thread(publisher, "test-order-publisher");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static List<OrderRequest> drain(
        final ManyToOneConcurrentArrayQueue<OrderRequest> queue,
        final int wanted
    ) {
        final List<OrderRequest> drained = new ArrayList<>(wanted);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (drained.size() < wanted && System.nanoTime() < deadline) {
            final OrderRequest r = queue.poll();
            if (r != null) {
                drained.add(r);
            } else {
                Thread.onSpinWait();
            }
        }
        assertTrue("producer did not catch up after the stall: only " + drained.size() + " of " + wanted,
            drained.size() == wanted);
        return drained;
    }

    private static void stop(final AtomicBoolean running, final Thread producer) throws InterruptedException {
        running.set(false);
        producer.join(TimeUnit.SECONDS.toMillis(5));
    }
}
