package com.match.loadtest;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects and reports performance metrics during load testing
 */
public class MetricsCollector {

    // Counters
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder backpressureCount = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();

    // Latency tracking (nanoseconds)
    private final LatencyTracker latencyTracker = new LatencyTracker();

    // Throughput tracking
    private final AtomicLong lastSnapshotTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastSnapshotCount = new AtomicLong(0);

    private final long startTime = System.currentTimeMillis();

    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        latencyTracker.record(latencyNanos);
    }

    public void recordFailure() {
        failureCount.increment();
    }

    public void recordBackpressure() {
        backpressureCount.increment();
    }

    public void recordTimeout() {
        timeoutCount.increment();
    }

    // Getters for UI
    public long getSuccessCount() {
        return successCount.sum();
    }

    public long getFailureCount() {
        return failureCount.sum();
    }

    public long getBackpressureCount() {
        return backpressureCount.sum();
    }

    public long getTimeoutCount() {
        return timeoutCount.sum();
    }

    public long getStartTime() {
        return startTime;
    }

    public LatencyStats getLatencyStats() {
        return latencyTracker.getStats();
    }

    /**
     * Calculate current throughput and return stats for UI update
     */
    public long calculateThroughput(long totalSent) {
        long now = System.currentTimeMillis();
        long lastTime = lastSnapshotTime.getAndSet(now);
        long lastCount = lastSnapshotCount.getAndSet(totalSent);

        long intervalMs = now - lastTime;
        long intervalMessages = totalSent - lastCount;

        return intervalMs > 0 ? (intervalMessages * 1000L / intervalMs) : 0L;
    }

    public void printSnapshot(long totalSent) {
        long now = System.currentTimeMillis();
        long lastTime = lastSnapshotTime.getAndSet(now);
        long lastCount = lastSnapshotCount.getAndSet(totalSent);

        long intervalMs = now - lastTime;
        long intervalMessages = totalSent - lastCount;
        double throughput = intervalMs > 0 ? (intervalMessages * 1000.0 / intervalMs) : 0.0;

        long success = successCount.sum();
        long failures = failureCount.sum();
        long backpressure = backpressureCount.sum();
        long total = success + failures;

        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        LatencyStats stats = latencyTracker.getStats();

        // Display latencies in microseconds
        System.out.printf(
            "│ %,8d msg/s │ %,10d sent │ %,10d success │ %,8d fails │ %,6d BP │ %6.2f%% │ p50: %6.1fμs │ p99: %6.1fμs │%n",
            (int) throughput,
            totalSent,
            success,
            failures,
            backpressure,
            successRate,
            stats.p50 / 1000.0,
            stats.p99 / 1000.0
        );
    }

    public void printFinalReport(long totalSent) {
        long duration = System.currentTimeMillis() - startTime;
        long success = successCount.sum();
        long failures = failureCount.sum();
        long backpressure = backpressureCount.sum();
        long timeouts = timeoutCount.sum();
        long total = success + failures;

        double avgThroughput = duration > 0 ? (totalSent * 1000.0 / duration) : 0.0;
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        LatencyStats stats = latencyTracker.getStats();

        System.out.println();
        System.out.printf("Total Duration:        %,10d ms%n", duration);
        System.out.printf("Messages Sent:         %,10d%n", totalSent);
        System.out.printf("Successful:            %,10d (%.2f%%)%n", success, successRate);
        System.out.printf("Failed:                %,10d%n", failures);
        System.out.printf("Backpressure Events:   %,10d%n", backpressure);
        System.out.printf("Timeouts:              %,10d%n", timeouts);
        System.out.printf("Average Throughput:    %,10.2f msg/s%n", avgThroughput);
        System.out.println();
        System.out.println("Latency Distribution (μs):");
        System.out.printf("  Min:                 %,10.2f μs%n", stats.min / 1000.0);
        System.out.printf("  p50 (median):        %,10.2f μs%n", stats.p50 / 1000.0);
        System.out.printf("  p95:                 %,10.2f μs%n", stats.p95 / 1000.0);
        System.out.printf("  p99:                 %,10.2f μs%n", stats.p99 / 1000.0);
        System.out.printf("  Max:                 %,10.2f μs%n", stats.max / 1000.0);
        System.out.printf("  Avg:                 %,10.2f μs%n", stats.avg / 1000.0);
    }

    /**
     * Lock-free latency tracker with percentile calculations.
     * Single-writer (duty cycle thread), multi-reader safe.
     */
    private static class LatencyTracker {
        private static final int CAPACITY = 1_000_000;
        private final long[] samples = new long[CAPACITY];
        private volatile int writeIndex = 0;  // Only written by duty cycle thread

        public void record(long latencyNanos) {
            // Lock-free: single writer pattern
            int idx = writeIndex;
            samples[idx % CAPACITY] = latencyNanos;
            writeIndex = idx + 1;  // Volatile write ensures visibility
        }

        public LatencyStats getStats() {
            int currentIndex = writeIndex;  // Snapshot the index
            if (currentIndex == 0) {
                return new LatencyStats(0, 0, 0, 0, 0, 0);
            }

            int count = Math.min(currentIndex, CAPACITY);
            long[] copy = new long[count];

            // Copy samples - may have slight inconsistency but acceptable for metrics
            int startIdx = currentIndex > CAPACITY ? currentIndex - CAPACITY : 0;
            for (int i = 0; i < count; i++) {
                copy[i] = samples[(startIdx + i) % CAPACITY];
            }

            Arrays.sort(copy);

            long min = copy[0];
            long max = copy[count - 1];
            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += copy[i];
            }
            long avg = sum / count;

            long p50 = copy[(int) (count * 0.50)];
            long p95 = copy[(int) (count * 0.95)];
            long p99 = copy[Math.min(count - 1, (int) (count * 0.99))];

            return new LatencyStats(min, max, avg, p50, p95, p99);
        }
    }

    /**
     * Latency statistics in nanoseconds
     */
    public static class LatencyStats {
        public final long min;
        public final long max;
        public final long avg;
        public final long p50;
        public final long p95;
        public final long p99;

        public LatencyStats(long min, long max, long avg, long p50, long p95, long p99) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
        }
    }
}
