// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that generates orders and enqueues them to the lock-free queue.
 * The actual cluster communication is handled by the single duty cycle thread.
 *
 * <p><b>Coordinated omission.</b> This thread owns the fixed-rate timeline that the round-trip
 * measurement is anchored to, so two rules hold here and nowhere else:</p>
 * <ol>
 *   <li>The schedule is never rewound to "now" because the loop fell behind. Doing so hands the
 *       system a free pass for exactly the intervals in which it was slow, and the tail percentiles
 *       come out flattering. The one legitimate rebase is the warmup boundary, where JIT-era debt
 *       must not be charged to the measured window.</li>
 *   <li>A slot is never dropped because the queue was full. A dropped slot is the same omission by
 *       another route: the requests that would have been slowest simply vanish from the sample. The
 *       loop waits for room instead, and the wait shows up in the next orders' latency.</li>
 * </ol>
 */
public class OrderPublisher implements Runnable {

    private final int workerId;
    private final ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue;
    private final LoadConfig config;
    private final MetricsCollector metrics;
    private final AtomicLong globalMessageCount;
    private final AtomicBoolean running;
    /** Flipped by the duty thread when the warmup ends; the schedule rebases once on that edge. */
    private final AtomicBoolean measuring;
    private final boolean verbose;

    // User ID for this worker (numeric for zero-allocation encoding)
    private final long userId;

    // Market ID constant (BTC-USD = 1)
    private static final int MARKET_BTC_USD = 1;

    // Current mid price for BTC-USD (will vary slightly)
    private double currentMidPrice;

    // Local message count
    private long localMessageCount = 0;

    public OrderPublisher(
        int workerId,
        ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue,
        LoadConfig config,
        MetricsCollector metrics,
        AtomicLong globalMessageCount,
        AtomicBoolean running,
        AtomicBoolean measuring
    ) {
        this(workerId, orderQueue, config, metrics, globalMessageCount, running, measuring, true);
    }

    public OrderPublisher(
        int workerId,
        ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue,
        LoadConfig config,
        MetricsCollector metrics,
        AtomicLong globalMessageCount,
        AtomicBoolean running,
        AtomicBoolean measuring,
        boolean verbose
    ) {
        this.workerId = workerId;
        this.orderQueue = orderQueue;
        this.config = config;
        this.metrics = metrics;
        this.globalMessageCount = globalMessageCount;
        this.running = running;
        this.measuring = measuring;
        this.verbose = verbose;
        this.userId = workerId;

        // Initialize mid price with slight variation per worker ($110k midpoint of $90k-$130k range)
        this.currentMidPrice = 110000.0 + (workerId * 10.0);
    }

    @Override
    public void run() {
        final long targetMessagesPerSecond = config.getTargetOrdersPerSecond() / config.getWorkerThreads();
        final long nanosPerMessage = 1_000_000_000L / targetMessagesPerSecond;
        final ThreadLocalRandom rand = ThreadLocalRandom.current();

        long nextSendTime = System.nanoTime();
        // With no warmup the run is already the measured window, so there is no boundary to rebase on.
        boolean rebased = measuring.get();

        if (verbose) {
            System.out.printf("[Worker-%d] Started, target rate: %d msg/s%n", workerId, targetMessagesPerSecond);
        }

        while (running.get()) {
            if (!rebased && measuring.get()) {
                // The single permitted rewind: warmup debt is not the measured system's latency.
                nextSendTime = System.nanoTime();
                rebased = true;
            }

            final long now = System.nanoTime();

            if (now >= nextSendTime) {
                // t0 of this order is its SLOT, not this instant. If the loop is late, the lateness
                // is already part of the latency and must be carried, not discarded.
                final long slotNs = nextSendTime;

                // Generate order based on scenario
                OrderScenario.OrderParams params = config.getScenario().generateOrder(
                    config.getMarket(),
                    currentMidPrice
                );

                OrderRequest request = new OrderRequest(
                    userId,
                    MARKET_BTC_USD,
                    params.orderType,
                    params.orderSide,
                    params.price,
                    params.quantity,
                    params.getTotalPrice(),
                    now,      // ingress metric: when it really entered the queue
                    slotNs    // round-trip metric: when it was due
                );

                if (!enqueueOrWait(request)) {
                    break;  // shutting down; the remaining slots are not samples we can honour
                }
                localMessageCount++;

                // Slightly adjust mid price to simulate market movement. ThreadLocalRandom, not
                // Math.random(): the latter shares one Random across every worker, and its CAS
                // contention is generator noise landing in the engine's measurement.
                currentMidPrice += (rand.nextDouble() - 0.5) * 2.0;

                // Advance the schedule by exactly one slot. NEVER to `now`.
                nextSendTime += nanosPerMessage;

            } else {
                // Ultra-low latency: busy spin with CPU hint
                // Thread.onSpinWait() is a CPU hint that improves spin-wait loop performance
                // Avoids context switch overhead of Thread.yield() or Thread.sleep()
                Thread.onSpinWait();
            }
        }

        if (verbose) {
            System.out.printf("[Worker-%d] Stopped, enqueued %,d messages%n", workerId, localMessageCount);
        }
    }

    /**
     * Hand the order to the duty thread, waiting for room rather than discarding it.
     *
     * <p>The old behaviour counted a full queue as backpressure and threw the order away. That is
     * coordinated omission wearing a counter: the queue is full precisely when the system is
     * struggling, so the discarded orders are the ones that would have carried the worst latencies.
     * Waiting instead pushes the whole schedule back, and the delay resurfaces as a larger
     * now-minus-slot on the orders that follow.</p>
     *
     * <p>Backpressure is counted once per blocked order, not once per spin.</p>
     *
     * @return false if the test stopped while waiting, in which case nothing was enqueued.
     */
    private boolean enqueueOrWait(final OrderRequest request) {
        if (orderQueue.offer(request)) {
            return true;
        }
        metrics.recordBackpressure();
        while (!orderQueue.offer(request)) {
            if (!running.get()) {
                return false;
            }
            Thread.onSpinWait();
        }
        return true;
    }

    public void stop() {
        running.set(false);
    }
}
