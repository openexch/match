package com.match.loadtest;

import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that generates orders and enqueues them to the lock-free queue.
 * The actual cluster communication is handled by the single duty cycle thread.
 */
public class OrderPublisher implements Runnable {

    private final int workerId;
    private final ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue;
    private final LoadConfig config;
    private final MetricsCollector metrics;
    private final AtomicLong globalMessageCount;
    private final AtomicBoolean running;
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
        AtomicBoolean running
    ) {
        this(workerId, orderQueue, config, metrics, globalMessageCount, running, true);
    }

    public OrderPublisher(
        int workerId,
        ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue,
        LoadConfig config,
        MetricsCollector metrics,
        AtomicLong globalMessageCount,
        AtomicBoolean running,
        boolean verbose
    ) {
        this.workerId = workerId;
        this.orderQueue = orderQueue;
        this.config = config;
        this.metrics = metrics;
        this.globalMessageCount = globalMessageCount;
        this.running = running;
        this.verbose = verbose;
        this.userId = workerId;

        // Initialize mid price with slight variation per worker ($110k midpoint of $90k-$130k range)
        this.currentMidPrice = 110000.0 + (workerId * 10.0);
    }

    @Override
    public void run() {
        final long targetMessagesPerSecond = config.getTargetOrdersPerSecond() / config.getWorkerThreads();
        final long nanosPerMessage = 1_000_000_000L / targetMessagesPerSecond;

        long nextSendTime = System.nanoTime();

        if (verbose) {
            System.out.printf("[Worker-%d] Started, target rate: %d msg/s%n", workerId, targetMessagesPerSecond);
        }

        while (running.get()) {
            long now = System.nanoTime();

            if (now >= nextSendTime) {
                // Generate order based on scenario
                OrderScenario.OrderParams params = config.getScenario().generateOrder(
                    config.getMarket(),
                    currentMidPrice
                );

                // Create order request with enqueue timestamp
                OrderRequest request = new OrderRequest(
                    userId,
                    MARKET_BTC_USD,
                    params.orderType,
                    params.orderSide,
                    params.price,
                    params.quantity,
                    params.getTotalPrice(),
                    System.nanoTime()  // Capture time for latency measurement
                );

                // Try to enqueue - this is lock-free and very fast
                if (orderQueue.offer(request)) {
                    localMessageCount++;

                    // Slightly adjust mid price to simulate market movement
                    currentMidPrice += (Math.random() - 0.5) * 2.0;
                } else {
                    // Queue is full - record backpressure
                    metrics.recordBackpressure();
                }

                // Calculate next send time
                nextSendTime += nanosPerMessage;

                // If we've fallen behind, reset to current time
                if (nextSendTime < now) {
                    nextSendTime = now;
                }

            } else {
                // Small sleep to avoid busy waiting
                long sleepNanos = Math.min(nextSendTime - now, 1_000_000L); // Max 1ms
                if (sleepNanos > 100_000L) { // Only sleep if > 100us
                    try {
                        Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    // Busy wait for very short intervals
                    Thread.yield();
                }
            }
        }

        if (verbose) {
            System.out.printf("[Worker-%d] Stopped, enqueued %,d messages%n", workerId, localMessageCount);
        }
    }

    public void stop() {
        running.set(false);
    }
}
