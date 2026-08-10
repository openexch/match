// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects and reports performance metrics during load testing
 */
public class MetricsCollector {

    /** Below this share of sent orders the committed percentiles are a biased sample, not a result. */
    private static final double ACK_COVERAGE_MIN_PCT = 99.0;

    /**
     * Track names on the ILOG lines, shared with the hgrm artifacts
     * (base-ingress.hgrm / base-committed.hgrm) so forensics tooling sees one vocabulary.
     */
    static final String TRACK_INGRESS = "ingress";
    static final String TRACK_COMMITTED = "committed";

    /**
     * ILOG timestamp: UTC, always exactly three millisecond digits. NOT Instant.toString(),
     * which drops trailing zero millis and would change the line shape once a second, breaking
     * the single awk that parses this format across repos.
     */
    private static final DateTimeFormatter ILOG_TS =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    // Counters
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final LongAdder backpressureCount = new LongAdder();
    private final LongAdder timeoutCount = new LongAdder();

    // Latency tracking (nanoseconds)
    private final LatencyTracker latencyTracker = new LatencyTracker();
    /**
     * Committed round trip: CreateOrder offered -> first status back. Kept in a SEPARATE tracker from
     * {@link #latencyTracker}, which measures ingress publication (encode + hand-off to the local
     * driver, never leaving the machine). Publishing one under the other's name is the mistake this
     * split exists to make impossible.
     */
    private final LatencyTracker ackLatency = new LatencyTracker();
    private final LongAdder acksReceived = new LongAdder();

    // Throughput tracking
    private final AtomicLong lastSnapshotTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastSnapshotCount = new AtomicLong(0);

    private final long startTime = System.currentTimeMillis();

    /**
     * A CreateOrder was acknowledged: the matching engine replicated it, committed it, applied it and
     * sent a status back. This is the round trip. {@link #recordSuccess} measures something else
     * entirely — see this class's ingress/ack distinction — and the two must never be quoted as if
     * they were the same number.
     */
    public void recordAck(long latencyNanos) {
        ackLatency.record(latencyNanos);
        acksReceived.increment();
    }

    public void recordSuccess(long latencyNanos) {
        successCount.increment();
        latencyTracker.record(latencyNanos);
    }

    public long getAcksReceived() {
        return acksReceived.sum();
    }

    public LatencyStats getAckLatencyStats() {
        return ackLatency.getStats();
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
        ackLatency.reset();
        acksReceived.reset();
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
     * Turn on per-second interval capture on both latency tracks (--interval-log).
     * Off by default: without it the trackers keep their exact pre-existing behaviour.
     */
    public void enableIntervalLogging() {
        latencyTracker.enableIntervalCapture();
        ackLatency.enableIntervalCapture();
    }

    // Package-private per-track drains so tests can assert interval semantics directly.
    IntervalStats takeIngressInterval() {
        return latencyTracker.takeInterval();
    }

    IntervalStats takeCommittedInterval() {
        return ackLatency.takeInterval();
    }

    /**
     * One ILOG line per latency track for the second that just ended. Interval values only
     * (not cumulative); printed even when n=0 so the shape is constant for the awk that
     * aligns these against iostat/GC/bridge time series. Runs on the reporter thread —
     * never on the duty thread.
     */
    public void printIntervalLog() {
        final long epochMs = System.currentTimeMillis();
        final IntervalStats ingress = takeIngressInterval();
        final IntervalStats committed = takeCommittedInterval();
        System.out.println(formatIntervalLine(epochMs, TRACK_INGRESS, ingress));
        System.out.println(formatIntervalLine(epochMs, TRACK_COMMITTED, committed));
    }

    /**
     * Pure formatter for one ILOG line (no trailing newline). The format is shared with a
     * sibling repo — a single awk must parse both — so it is asserted verbatim in tests.
     * Locale.ROOT: a comma decimal separator from the platform locale would break the parser.
     * {@code epochMs} is the interval END; the caller stamps one value across all tracks of a tick.
     */
    static String formatIntervalLine(final long epochMs, final String track, final IntervalStats s) {
        return String.format(Locale.ROOT,
            "ILOG epoch_ms=%d ts=%s track=%s n=%d p50_ms=%.2f p99_ms=%.2f max_ms=%.2f",
            epochMs,
            ILOG_TS.format(Instant.ofEpochMilli(epochMs)),
            track,
            s.count,
            s.p50 / 1e6,
            s.p99 / 1e6,
            s.max / 1e6);
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
        System.out.println("Latency Distribution (μs) — INGRESS PUBLICATION:");
        System.out.println("  encode + hand-off to the local driver. Never leaves this machine:");
        System.out.println("  no network, no consensus, no matching, no reply.");
        System.out.printf("  Min:                 %,10.2f μs%n", stats.min / 1000.0);
        System.out.printf("  p50 (median):        %,10.2f μs%n", stats.p50 / 1000.0);
        System.out.printf("  p95:                 %,10.2f μs%n", stats.p95 / 1000.0);
        System.out.printf("  p99:                 %,10.2f μs%n", stats.p99 / 1000.0);
        System.out.printf("  p99.9:               %,10.2f μs%n", stats.p999 / 1000.0);
        System.out.printf("  p99.99:              %,10.2f μs%n", stats.p9999 / 1000.0);
        System.out.printf("  Max:                 %,10.2f μs%n", stats.max / 1000.0);
        System.out.printf("  Avg:                 %,10.2f μs%n", stats.avg / 1000.0);

        final LatencyStats ack = ackLatency.getStats();
        final long acks = acksReceived.sum();
        System.out.println();
        if (acks > 0) {
            System.out.println("Latency Distribution (μs) — COMMITTED ROUND TRIP:");
            System.out.println("  CreateOrder offered -> first status back: replicated, committed,");
            System.out.println("  applied, answered. THIS is the number comparable to another system's.");
            System.out.printf("  Acknowledged:        %,10d orders%n", acks);
            printAckCoverage(acks, totalSent);
            System.out.printf("  Min:                 %,10.2f μs%n", ack.min / 1000.0);
            System.out.printf("  p50:                 %,10.2f μs%n", ack.p50 / 1000.0);
            System.out.printf("  p95:                 %,10.2f μs%n", ack.p95 / 1000.0);
            System.out.printf("  p99:                 %,10.2f μs%n", ack.p99 / 1000.0);
            System.out.printf("  p99.9:               %,10.2f μs%n", ack.p999 / 1000.0);
            System.out.printf("  p99.99:              %,10.2f μs%n", ack.p9999 / 1000.0);
            System.out.printf("  Max:                 %,10.2f μs%n", ack.max / 1000.0);
            System.out.printf("  Avg:                 %,10.2f μs%n", ack.avg / 1000.0);
        } else {
            System.out.println("Latency Distribution (μs) — COMMITTED ROUND TRIP:");
            System.out.println("  NO ACKS MATCHED — the round-trip number is absent, not zero.");
            System.out.println("  Do not quote the ingress figure above in its place.");
        }

        writeHgrmFiles(acks > 0);
    }

    /**
     * What fraction of the sent orders the round-trip percentiles were actually computed from.
     *
     * <p>A present COMMITTED block is not the same as a measured one. If most statuses never came
     * back, the percentiles describe the minority that did — and the orders whose status is missing
     * are not a random minority, they are the ones the engine had trouble with. So the coverage is
     * printed next to the number, and below the threshold it is labelled as unpublishable rather
     * than left for the reader to divide two figures and notice. A few orders still in flight when
     * the window closes is normal; a coverage of 3% is a different measurement entirely.</p>
     */
    private static void printAckCoverage(final long acks, final long totalSent) {
        if (totalSent <= 0) {
            return;
        }
        final double pct = acks * 100.0 / totalSent;
        System.out.printf("  Ack coverage:        %,10.2f %% of sent orders%n", pct);
        if (pct < ACK_COVERAGE_MIN_PCT) {
            System.out.printf(
                "  ACK COVERAGE LOW (< %.0f%%) — SAMPLE IS BIASED, DO NOT PUBLISH THESE PERCENTILES%n",
                ACK_COVERAGE_MIN_PCT
            );
        }
    }

    /**
     * Raw distributions for the published data set: -Dloadtest.hgrm=&lt;path&gt;.
     *
     * Both metrics get their own file. Writing only the ingress histogram (as this did until
     * 2026-07-27) left the headline number — the committed round trip — with no raw data, which
     * the benchmark methodology requires shipping alongside every published percentile. A trailing
     * ".hgrm" in the property is stripped so the two files sit next to each other:
     *   -Dloadtest.hgrm=/tmp/run7.hgrm -> /tmp/run7-ingress.hgrm + /tmp/run7-committed.hgrm
     *
     * When no ack matched, the committed file is NOT written. An empty histogram on disk reads as
     * "measured, and it was zero"; a missing file reads as what it is.
     */
    private void writeHgrmFiles(final boolean haveAcks) {
        final String prop = System.getProperty("loadtest.hgrm");
        if (prop == null || prop.isBlank()) {
            return;
        }
        final String base = prop.endsWith(".hgrm") ? prop.substring(0, prop.length() - ".hgrm".length()) : prop;

        writeHgrm(base + "-" + TRACK_INGRESS + ".hgrm", latencyTracker);
        if (haveAcks) {
            writeHgrm(base + "-" + TRACK_COMMITTED + ".hgrm", ackLatency);
        } else {
            System.out.println("  (committed hgrm NOT written: no acks matched)");
        }
    }

    private static void writeHgrm(final String path, final LatencyTracker tracker) {
        try (java.io.PrintStream ps = new java.io.PrintStream(path)) {
            tracker.writeHgrm(ps);
            System.out.println("  (hgrm written: " + path + ")");
        } catch (Exception e) {
            System.out.println("  WARN: could not write hgrm " + path + ": " + e);
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
        /**
         * Per-interval accumulator for --interval-log; null means capture is off (the default,
         * with zero behaviour change). Fed on EVERY flip, no matter who triggered it: under the
         * UI's 100ms getStats cadence the recorder is drained many times per ILOG tick, and any
         * flip that bypassed this histogram would silently drop those samples from the interval.
         */
        private Histogram intervalAccum;

        public void record(long latencyNanos) {
            if (latencyNanos >= 0) {
                recorder.recordValue(latencyNanos);
            }
        }

        public synchronized void reset() {
            recorder.reset();
            cumulative.reset();
            recycled = null;
            if (intervalAccum != null) {
                intervalAccum.reset(); // reset, not null out: capture stays enabled across warmup
            }
        }

        /** Lazily switch on interval capture (idempotent). */
        public synchronized void enableIntervalCapture() {
            if (intervalAccum == null) {
                intervalAccum = new Histogram(3);
            }
        }

        /** The single drain point: recorder -> cumulative (and the interval accumulator when on). */
        private void flip() {
            recycled = recorder.getIntervalHistogram(recycled);
            cumulative.add(recycled);
            if (intervalAccum != null) {
                intervalAccum.add(recycled);
            }
        }

        /**
         * Snapshot and clear the current interval. Values are nanoseconds for the samples
         * recorded since the previous take (or enable/reset) only. One small allocation per
         * call on the reporter thread; the duty-thread record() path is untouched.
         */
        public synchronized IntervalStats takeInterval() {
            flip();
            if (intervalAccum == null) {
                return new IntervalStats(0, 0, 0, 0);
            }
            final IntervalStats stats = new IntervalStats(
                    intervalAccum.getTotalCount(),
                    intervalAccum.getValueAtPercentile(50.0),
                    intervalAccum.getValueAtPercentile(99.0),
                    intervalAccum.getMaxValue());
            intervalAccum.reset();
            return stats;
        }

        /** Cumulative snapshot. Cheap: a phaser flip + histogram add, no sort, no 8MB copy. */
        public synchronized LatencyStats getStats() {
            flip();
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
            flip();
            cumulative.outputPercentileDistribution(out, 1000.0); // microseconds
        }
    }

    /**
     * One interval's latency snapshot in nanoseconds: that second's samples only, not
     * cumulative. All-zero when the interval recorded nothing (n=0 still prints).
     */
    public static class IntervalStats {
        public final long count;
        public final long p50;
        public final long p99;
        public final long max;

        public IntervalStats(long count, long p50, long p99, long max) {
            this.count = count;
            this.p50 = p50;
            this.p99 = p99;
            this.max = max;
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
