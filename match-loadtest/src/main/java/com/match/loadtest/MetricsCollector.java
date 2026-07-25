// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;
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

    /**
     * Reset all metrics (used after warmup phase)
     */
    public void reset() {
        successCount.reset();
        failureCount.reset();
        backpressureCount.reset();
        timeoutCount.reset();
        latencyTracker.reset();
        lastSnapshotTime.set(System.currentTimeMillis());
        lastSnapshotCount.set(0);
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
        System.out.printf("  p99.9:               %,10.2f μs%n", stats.p999 / 1000.0);
        System.out.printf("  p99.99:              %,10.2f μs%n", stats.p9999 / 1000.0);
        System.out.printf("  Max:                 %,10.2f μs%n", stats.max / 1000.0);
        System.out.printf("  Avg:                 %,10.2f μs%n", stats.avg / 1000.0);

        // Raw distribution for the published data set: -Dloadtest.hgrm=<path>
        final String hgrm = System.getProperty("loadtest.hgrm");
        if (hgrm != null && !hgrm.isBlank()) {
            try (java.io.PrintStream ps = new java.io.PrintStream(hgrm)) {
                latencyTracker.writeHgrm(ps);
                System.out.println("  (hgrm written: " + hgrm + ")");
            } catch (Exception e) {
                System.out.println("  WARN: could not write hgrm " + hgrm + ": " + e);
            }
        }
    }

    /**
     * Latency tracker backed by HdrHistogram.
     *
     * Replaces a fixed ring + Arrays.sort: computing percentiles used to allocate an 8 MB
     * copy and sort 1M longs on EVERY report interval (2s), which churned the heap and the
     * cache and perturbed the tail it was measuring. HdrHistogram records in constant time
     * on the writer and reads percentiles without sorting or allocating.
     *
     * SingleWriterRecorder matches the actual threading: the cluster duty-cycle thread is the
     * only writer; the reporter thread reads. Interval histograms are accumulated into a
     * cumulative histogram so reported percentiles stay whole-run (as before).
     */
    private static class LatencyTracker {
        private final SingleWriterRecorder recorder = new SingleWriterRecorder(3);
        private Histogram cumulative = new Histogram(3);
        private Histogram recycled;

        public void record(long latencyNanos) {
            if (latencyNanos >= 0) {
                recorder.recordValue(latencyNanos);
            }
        }

        public synchronized void reset() {
            recorder.reset();
            cumulative.reset();
            recycled = null;
        }

        /** Cumulative snapshot. Cheap: a phaser flip + histogram add, no sort, no 8MB copy. */
        public synchronized LatencyStats getStats() {
            recycled = recorder.getIntervalHistogram(recycled);
            cumulative.add(recycled);
            if (cumulative.getTotalCount() == 0) {
                return new LatencyStats(0, 0, 0, 0, 0, 0, 0, 0);
            }
            return new LatencyStats(
                    cumulative.getMinValue(),
                    cumulative.getMaxValue(),
                    (long) cumulative.getMean(),
                    cumulative.getValueAtPercentile(50.0),
                    cumulative.getValueAtPercentile(95.0),
                    cumulative.getValueAtPercentile(99.0),
                    cumulative.getValueAtPercentile(99.9),
                    cumulative.getValueAtPercentile(99.99));
        }

        /** Full distribution for the raw-data artifact (benchmark-as-code). */
        public synchronized void writeHgrm(final java.io.PrintStream out) {
            recycled = recorder.getIntervalHistogram(recycled);
            cumulative.add(recycled);
            cumulative.outputPercentileDistribution(out, 1000.0); // microseconds
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
        public final long p999;
        public final long p9999;

        public LatencyStats(long min, long max, long avg, long p50, long p95, long p99,
                            long p999, long p9999) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.p999 = p999;
            this.p9999 = p9999;
        }
    }
}
