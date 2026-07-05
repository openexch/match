// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.state;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Executable proof that the gateway's epoch-based bucket flooring
 * (floor(tsMs / intervalMs) * intervalMs, UTC) coincides with TimescaleDB's
 * time_bucket() boundaries for all six supported intervals.
 *
 * time_bucket aligns to origin 2000-01-03 00:00:00 UTC = epoch 946857600s.
 * Because 946857600 is divisible by every supported interval width, every
 * time_bucket boundary is also an epoch-aligned boundary — the SQL-derived
 * candles and the in-memory candles land in identical buckets, provided the
 * SQL side never uses the timezone-aware time_bucket variant.
 */
public class BucketAlignmentTest {

    private static final long TIME_BUCKET_ORIGIN_SEC = 946_857_600L; // 2000-01-03 UTC
    private static final String[] INTERVALS = {"1m", "5m", "15m", "1h", "4h", "1d"};
    private static final long[] WIDTH_SEC = {60, 300, 900, 3_600, 14_400, 86_400};

    @Test
    public void testTimeBucketOrigin_DivisibleByAllIntervalWidths() {
        for (int i = 0; i < WIDTH_SEC.length; i++) {
            assertEquals("origin must align for " + INTERVALS[i],
                    0, TIME_BUCKET_ORIGIN_SEC % WIDTH_SEC[i]);
        }
    }

    @Test
    public void testJavaFlooring_ProducesEpochAlignedBuckets() {
        // Awkward instants: bucket boundaries +/- 1ms, a DST-transition day in
        // Europe (2026-03-29), a leap-day (2024-02-29), and "now"-ish values.
        List<Long> samples = List.of(
                0L, 1L, 59_999L, 60_000L, 60_001L,
                1_711_843_200_000L,          // 2024-03-31 00:00:00 UTC (EU DST day)
                1_711_843_199_999L,
                1_708_905_600_000L,          // 2024-02-26 00:00 UTC
                1_708_992_000_000L - 1,      // leap-week boundary - 1ms
                1_774_742_400_000L,          // 2026-03-29 (EU DST transition) 00:00 UTC
                1_774_742_400_000L + 7_199_999L,
                1_751_760_000_123L,          // mid-2025, arbitrary ms offset
                86_399_999L, 86_400_000L, 86_400_001L);

        for (int i = 0; i < INTERVALS.length; i++) {
            long widthMs = WIDTH_SEC[i] * 1000;
            for (long tsMs : samples) {
                long bucketMs = (tsMs / widthMs) * widthMs;   // InMemoryCandleProvider.onTrade
                long bucketSec = bucketMs / 1000;
                assertEquals("bucket not epoch-aligned for " + INTERVALS[i] + " ts=" + tsMs,
                        0, bucketSec % WIDTH_SEC[i]);
                assertTrue("bucket must not exceed ts", bucketMs <= tsMs);
                assertTrue("ts must fall inside its bucket", tsMs - bucketMs < widthMs);
                // Same boundary time_bucket computes: floor relative to its origin
                long tsSec = tsMs / 1000;
                long timeBucketSec = TIME_BUCKET_ORIGIN_SEC
                        + ((tsSec - TIME_BUCKET_ORIGIN_SEC) / WIDTH_SEC[i]) * WIDTH_SEC[i];
                if (tsSec >= TIME_BUCKET_ORIGIN_SEC) { // all real market data
                    assertEquals("time_bucket boundary diverges for " + INTERVALS[i] + " ts=" + tsMs,
                            timeBucketSec, bucketSec);
                }
            }
        }
    }

    @Test
    public void testDayBuckets_AreUtcMidnight() {
        long tsMs = 1_751_760_000_123L; // some instant mid-2025
        long widthMs = 86_400_000L;
        long bucketSec = ((tsMs / widthMs) * widthMs) / 1000;
        assertEquals("1d buckets must start at UTC midnight", 0, bucketSec % 86_400);
    }
}
