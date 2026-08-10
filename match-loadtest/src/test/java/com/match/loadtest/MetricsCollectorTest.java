package com.match.loadtest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the HdrHistogram-backed latency tracker: the percentiles the benchmark publishes
 * must come out of recorded samples, and reset() must actually clear the run (the ladder
 * discards warmup by calling it).
 */
public class MetricsCollectorTest {

    @Test
    public void recordsPercentilesIncludingTheTail() {
        final MetricsCollector m = new MetricsCollector();
        // 9,900 samples at 1us + 99 at 50us + 1 at 5ms -> a known tail shape
        for (int i = 0; i < 9_900; i++) m.recordSuccess(1_000);
        for (int i = 0; i < 99; i++) m.recordSuccess(50_000);
        m.recordSuccess(5_000_000);

        final MetricsCollector.LatencyStats s = m.getLatencyStats();
        assertEquals("p50 should sit in the 1us bulk", 1_000, s.p50, 50);
        assertTrue("p99 should still be in the bulk", s.p99 <= 2_000);
        assertTrue("p99.9 should surface the 50us band", s.p999 >= 40_000);
        assertTrue("max should be the 5ms outlier", s.max >= 4_900_000);
        assertTrue("p99.99 must not exceed max", s.p9999 <= s.max);
    }

    @Test
    public void resetDiscardsWarmupSamples() {
        final MetricsCollector m = new MetricsCollector();
        for (int i = 0; i < 1_000; i++) m.recordSuccess(9_000_000); // "warmup": 9ms each
        m.reset();
        for (int i = 0; i < 1_000; i++) m.recordSuccess(1_000);     // measured window: 1us

        final MetricsCollector.LatencyStats s = m.getLatencyStats();
        assertTrue("warmup samples must not leak into the measured window", s.max < 2_000);
        assertEquals(0, m.getSuccessCount() - 1_000);
    }

    @Test
    public void takeIntervalReturnsThatIntervalOnlyAndLeavesCumulativeWhole() {
        final MetricsCollector m = new MetricsCollector();
        m.enableIntervalLogging();

        for (int i = 0; i < 1_000; i++) m.recordSuccess(1_000_000); // 1ms
        final MetricsCollector.IntervalStats first = m.takeIngressInterval();
        assertEquals("the take must see exactly the recorded samples", 1_000, first.count);
        assertEquals("interval p50 should sit at the 1ms bulk", 1_000_000, first.p50, 10_000);

        final MetricsCollector.IntervalStats second = m.takeIngressInterval();
        assertEquals("an immediate second take is an empty interval, not a repeat", 0, second.count);
        assertEquals(0, second.p50);
        assertEquals(0, second.max);

        for (int i = 0; i < 500; i++) m.recordSuccess(3_000_000); // 3ms
        assertEquals("the next interval sees only its own samples", 500, m.takeIngressInterval().count);

        final MetricsCollector.LatencyStats s = m.getLatencyStats();
        assertEquals("cumulative p50 still reflects the whole run", 1_000_000, s.p50, 10_000);
        assertTrue("cumulative max must still see the 3ms band", s.max >= 2_900_000);
        assertEquals("cumulative count untouched by interval drains", 1_500, m.getSuccessCount());
    }

    @Test
    public void statsFlipsBetweenTakesStillFeedTheInterval() {
        // UI mode drains the recorder every 100ms via getStats; those flips must not
        // steal samples from the ILOG interval.
        final MetricsCollector m = new MetricsCollector();
        m.enableIntervalLogging();
        for (int i = 0; i < 300; i++) m.recordSuccess(1_000_000);
        m.getLatencyStats(); // UI-cadence flip mid-interval
        for (int i = 0; i < 200; i++) m.recordSuccess(1_000_000);

        assertEquals("samples drained by getStats belong to the interval too",
            500, m.takeIngressInterval().count);
    }

    @Test
    public void committedTrackIsCapturedIndependentlyOfIngress() {
        final MetricsCollector m = new MetricsCollector();
        m.enableIntervalLogging();
        for (int i = 0; i < 10; i++) m.recordAck(2_000_000);

        assertEquals(10, m.takeCommittedInterval().count);
        assertEquals("acks must not leak into the ingress track", 0, m.takeIngressInterval().count);
    }

    @Test
    public void resetClearsPendingIntervalState() {
        final MetricsCollector m = new MetricsCollector();
        m.enableIntervalLogging();
        for (int i = 0; i < 100; i++) m.recordSuccess(9_000_000);
        m.getLatencyStats(); // flip some samples into the interval accumulator before the reset
        m.reset();           // warmup boundary

        assertEquals("warmup samples must not leak into the first measured interval",
            0, m.takeIngressInterval().count);
    }

    @Test
    public void ilogLineKeepsTheSharedShapeIncludingAnEmptyInterval() {
        // The shape is a cross-repo contract: a single awk parses this and the sibling repo's
        // lines. n=0 still prints, with values 0.00, and the timestamp keeps all three
        // millisecond digits (Instant.toString would have dropped trailing zeros).
        final String line = MetricsCollector.formatIntervalLine(
            0L, MetricsCollector.TRACK_INGRESS, new MetricsCollector.IntervalStats(0, 0, 0, 0));

        assertEquals(
            "ILOG epoch_ms=0 ts=1970-01-01T00:00:00.000Z track=ingress n=0 p50_ms=0.00 p99_ms=0.00 max_ms=0.00",
            line);
    }

    @Test
    public void ilogLineFormatsNanosAsMsWithTwoDecimals() {
        // 90,061,123 ms after the epoch = 1 day + 1h 1m 1.123s.
        final String line = MetricsCollector.formatIntervalLine(
            90_061_123L, MetricsCollector.TRACK_COMMITTED,
            new MetricsCollector.IntervalStats(42, 1_500_000, 7_250_000, 12_000_000));

        assertEquals(
            "ILOG epoch_ms=90061123 ts=1970-01-02T01:01:01.123Z track=committed n=42 p50_ms=1.50 p99_ms=7.25 max_ms=12.00",
            line);
    }
}
