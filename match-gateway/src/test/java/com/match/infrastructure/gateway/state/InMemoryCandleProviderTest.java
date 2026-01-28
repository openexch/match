package com.match.infrastructure.gateway.state;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for InMemoryCandleProvider: candle aggregation, ring buffer wrapping, multi-interval support.
 */
public class InMemoryCandleProviderTest {

    private InMemoryCandleProvider provider;

    @Before
    public void setUp() {
        provider = new InMemoryCandleProvider();
    }

    // ==================== Initial State ====================

    @Test
    public void testInitialState_NoCandlesReturned() {
        List<Candle> candles = provider.getCandles(1, "1m", 100);
        assertTrue(candles.isEmpty());
    }

    @Test
    public void testInitialState_CurrentCandleNull() {
        assertNull(provider.getCurrentCandle(1, "1m"));
    }

    // ==================== onTrade - single candle ====================

    @Test
    public void testOnTrade_CreatesSingleCandle() {
        provider.onTrade(1, 100.0, 1.5, 60000L); // t=60s -> bucket 60s for 1m

        List<Candle> candles = provider.getCandles(1, "1m", 10);
        assertEquals(1, candles.size());

        Candle c = candles.get(0);
        assertEquals(60, c.time); // 60000ms / 1000 = 60s
        assertEquals(100.0, c.open, 0.0001);
        assertEquals(100.0, c.high, 0.0001);
        assertEquals(100.0, c.low, 0.0001);
        assertEquals(100.0, c.close, 0.0001);
        assertEquals(1.5, c.volume, 0.0001);
        assertEquals(1, c.tradeCount);
        assertEquals(1, c.marketId);
    }

    @Test
    public void testOnTrade_UpdatesExistingCandle() {
        // Both trades in same 1m bucket (60000-119999ms)
        provider.onTrade(1, 100.0, 1.0, 60000L);
        provider.onTrade(1, 105.0, 2.0, 65000L);
        provider.onTrade(1, 95.0, 0.5, 70000L);
        provider.onTrade(1, 102.0, 1.0, 80000L);

        List<Candle> candles = provider.getCandles(1, "1m", 10);
        assertEquals(1, candles.size());

        Candle c = candles.get(0);
        assertEquals(100.0, c.open, 0.0001);
        assertEquals(105.0, c.high, 0.0001);
        assertEquals(95.0, c.low, 0.0001);
        assertEquals(102.0, c.close, 0.0001);
        assertEquals(4.5, c.volume, 0.0001);
        assertEquals(4, c.tradeCount);
    }

    // ==================== Multi-candle ====================

    @Test
    public void testOnTrade_MultipleCandles_DifferentBuckets() {
        // Bucket 1: 60000-119999ms
        provider.onTrade(1, 100.0, 1.0, 60000L);
        // Bucket 2: 120000-179999ms
        provider.onTrade(1, 110.0, 2.0, 120000L);
        // Bucket 3: 180000-239999ms
        provider.onTrade(1, 105.0, 1.5, 180000L);

        List<Candle> candles = provider.getCandles(1, "1m", 10);
        assertEquals(3, candles.size());

        // Ascending time order
        assertEquals(60, candles.get(0).time);
        assertEquals(120, candles.get(1).time);
        assertEquals(180, candles.get(2).time);
    }

    @Test
    public void testGetCandles_RespectsLimit() {
        for (int i = 0; i < 10; i++) {
            provider.onTrade(1, 100.0 + i, 1.0, (i + 1) * 60000L);
        }

        List<Candle> candles = provider.getCandles(1, "1m", 3);
        assertEquals(3, candles.size());
        // Should be the 3 most recent, ascending
        assertEquals(480, candles.get(0).time); // 8th minute
        assertEquals(540, candles.get(1).time); // 9th minute
        assertEquals(600, candles.get(2).time); // 10th minute
    }

    // ==================== getCurrentCandle ====================

    @Test
    public void testGetCurrentCandle_ReturnsMostRecent() {
        provider.onTrade(1, 100.0, 1.0, 60000L);
        provider.onTrade(1, 110.0, 2.0, 120000L);

        Candle current = provider.getCurrentCandle(1, "1m");
        assertNotNull(current);
        assertEquals(120, current.time);
        assertEquals(110.0, current.open, 0.0001);
    }

    @Test
    public void testGetCurrentCandle_ReturnsCopy() {
        provider.onTrade(1, 100.0, 1.0, 60000L);

        Candle c1 = provider.getCurrentCandle(1, "1m");
        Candle c2 = provider.getCurrentCandle(1, "1m");
        assertNotSame(c1, c2);
        assertEquals(c1.open, c2.open, 0.0001);
    }

    // ==================== Multi-interval ====================

    @Test
    public void testMultiInterval_1mAnd5m() {
        // Two trades in same 5m bucket but different 1m buckets
        provider.onTrade(1, 100.0, 1.0, 60000L);   // 1m bucket: 60s, 5m bucket: 0s
        provider.onTrade(1, 105.0, 2.0, 120000L);  // 1m bucket: 120s, 5m bucket: 0s

        // 1m: should have 2 candles
        List<Candle> candles1m = provider.getCandles(1, "1m", 10);
        assertEquals(2, candles1m.size());

        // 5m: should have 1 candle with both trades
        List<Candle> candles5m = provider.getCandles(1, "5m", 10);
        assertEquals(1, candles5m.size());
        assertEquals(100.0, candles5m.get(0).open, 0.0001);
        assertEquals(105.0, candles5m.get(0).close, 0.0001);
        assertEquals(3.0, candles5m.get(0).volume, 0.0001);
    }

    @Test
    public void testAllIntervals_Created() {
        // A single trade should create candles in all 6 intervals
        provider.onTrade(1, 100.0, 1.0, 3600000L); // 1h mark

        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        for (String interval : intervals) {
            List<Candle> candles = provider.getCandles(1, interval, 10);
            assertEquals("Should have 1 candle for interval " + interval, 1, candles.size());
        }
    }

    // ==================== Multi-market ====================

    @Test
    public void testMultiMarket_Isolation() {
        provider.onTrade(1, 100.0, 1.0, 60000L);
        provider.onTrade(2, 200.0, 2.0, 60000L);

        List<Candle> market1 = provider.getCandles(1, "1m", 10);
        List<Candle> market2 = provider.getCandles(2, "1m", 10);

        assertEquals(1, market1.size());
        assertEquals(100.0, market1.get(0).open, 0.0001);
        assertEquals(1, market1.get(0).marketId);

        assertEquals(1, market2.size());
        assertEquals(200.0, market2.get(0).open, 0.0001);
        assertEquals(2, market2.get(0).marketId);
    }

    // ==================== Ring buffer wrapping ====================

    @Test
    public void testRingBuffer_Wrapping() {
        InMemoryCandleProvider smallProvider = new InMemoryCandleProvider(3);

        // Add 5 candles (each in a different 1m bucket)
        for (int i = 1; i <= 5; i++) {
            smallProvider.onTrade(1, i * 10.0, 1.0, i * 60000L);
        }

        List<Candle> candles = smallProvider.getCandles(1, "1m", 10);
        assertEquals(3, candles.size());

        // Should have the 3 most recent
        assertEquals(180, candles.get(0).time); // 3rd minute
        assertEquals(240, candles.get(1).time); // 4th minute
        assertEquals(300, candles.get(2).time); // 5th minute
    }

    // ==================== Edge cases ====================

    @Test
    public void testInvalidMarketId_Zero() {
        provider.onTrade(0, 100.0, 1.0, 60000L);
        assertTrue(provider.getCandles(0, "1m", 10).isEmpty());
    }

    @Test
    public void testInvalidMarketId_Negative() {
        provider.onTrade(-1, 100.0, 1.0, 60000L);
        assertTrue(provider.getCandles(-1, "1m", 10).isEmpty());
    }

    @Test
    public void testInvalidInterval() {
        provider.onTrade(1, 100.0, 1.0, 60000L);
        assertTrue(provider.getCandles(1, "2m", 10).isEmpty());
        assertNull(provider.getCurrentCandle(1, "2m"));
    }

    @Test
    public void testIntervalIndex_AllSupported() {
        assertEquals(0, InMemoryCandleProvider.intervalIndex("1m"));
        assertEquals(1, InMemoryCandleProvider.intervalIndex("5m"));
        assertEquals(2, InMemoryCandleProvider.intervalIndex("15m"));
        assertEquals(3, InMemoryCandleProvider.intervalIndex("1h"));
        assertEquals(4, InMemoryCandleProvider.intervalIndex("4h"));
        assertEquals(5, InMemoryCandleProvider.intervalIndex("1d"));
        assertEquals(-1, InMemoryCandleProvider.intervalIndex("unknown"));
    }

    // ==================== Bucket alignment ====================

    @Test
    public void testBucketAlignment_1m() {
        // Trade at 90 seconds (90000ms) should bucket to 60s
        provider.onTrade(1, 100.0, 1.0, 90000L);
        List<Candle> candles = provider.getCandles(1, "1m", 10);
        assertEquals(60, candles.get(0).time);
    }

    @Test
    public void testBucketAlignment_1h() {
        // Trade at 5400000ms (1.5 hours) should bucket to 3600s (1 hour)
        provider.onTrade(1, 100.0, 1.0, 5400000L);
        List<Candle> candles = provider.getCandles(1, "1h", 10);
        assertEquals(3600, candles.get(0).time);
    }

    @Test
    public void testBucketAlignment_1d() {
        // Trade at 1.5 days should bucket to day 1 start
        long oneAndHalfDays = (long) (1.5 * 86400000L);
        provider.onTrade(1, 100.0, 1.0, oneAndHalfDays);
        List<Candle> candles = provider.getCandles(1, "1d", 10);
        assertEquals(86400, candles.get(0).time); // 1 day in seconds
    }

    // ==================== getCandles returns copies ====================

    @Test
    public void testGetCandles_ReturnsCopies() {
        provider.onTrade(1, 100.0, 1.0, 60000L);

        List<Candle> list1 = provider.getCandles(1, "1m", 10);
        List<Candle> list2 = provider.getCandles(1, "1m", 10);

        assertNotSame(list1.get(0), list2.get(0));
        assertEquals(list1.get(0).open, list2.get(0).open, 0.0001);
    }
}
