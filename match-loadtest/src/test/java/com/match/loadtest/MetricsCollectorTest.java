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
}
