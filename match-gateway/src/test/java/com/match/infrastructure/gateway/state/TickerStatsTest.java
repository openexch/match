package com.match.infrastructure.gateway.state;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for TickerStats: trade accumulation, high/low tracking, JSON output.
 */
public class TickerStatsTest {

    private TickerStats ticker;

    @Before
    public void setUp() {
        ticker = new TickerStats(1, "BTC-USD");
    }

    // ==================== Initial State ====================

    @Test
    public void testInitialState_HasDataFalse() {
        assertFalse(ticker.hasData());
    }

    @Test
    public void testInitialState_LastPriceZero() {
        assertEquals(0.0, ticker.getLastPrice(), 0.0001);
    }

    @Test
    public void testInitialState_ToJson_ReturnsEmptyJson() {
        String json = ticker.toJson();
        assertNotNull(json);
        assertTrue(json.contains("TICKER_STATS"));
        assertTrue(json.contains("\"lastPrice\":0"));
        assertTrue(json.contains("\"marketId\":1"));
        assertTrue(json.contains("\"market\":\"BTC-USD\""));
    }

    // ==================== updateFromTrade: first trade ====================

    @Test
    public void testUpdateFromTrade_FirstTrade_SetsAllPrices() {
        ticker.updateFromTrade(50000.0, 1.0);

        assertTrue(ticker.hasData());
        assertEquals(50000.0, ticker.getLastPrice(), 0.0001);
    }

    @Test
    public void testUpdateFromTrade_FirstTrade_JsonShowsCorrectValues() {
        ticker.updateFromTrade(50000.0, 1.0);

        String json = ticker.toJson();
        assertNotNull(json);
        assertTrue(json.contains("50000.0"));
        // open = last = high = low = 50000.0, so priceChange = 0
        assertTrue(json.contains("\"priceChange\":0.0"));
    }

    // ==================== Multiple trades: high/low tracking ====================

    @Test
    public void testMultipleTrades_HighTracked() {
        ticker.updateFromTrade(100.0, 1.0);
        ticker.updateFromTrade(150.0, 1.0);
        ticker.updateFromTrade(120.0, 1.0);

        // High should be 150
        String json = ticker.toJson();
        assertTrue(json.contains("150.0"));
    }

    @Test
    public void testMultipleTrades_LowTracked() {
        ticker.updateFromTrade(100.0, 1.0);
        ticker.updateFromTrade(50.0, 1.0);
        ticker.updateFromTrade(80.0, 1.0);

        // Low should be 50
        String json = ticker.toJson();
        assertTrue(json.contains("50.0"));
    }

    @Test
    public void testMultipleTrades_LastPriceUpdated() {
        ticker.updateFromTrade(100.0, 1.0);
        ticker.updateFromTrade(200.0, 1.0);
        ticker.updateFromTrade(150.0, 1.0);

        assertEquals(150.0, ticker.getLastPrice(), 0.0001);
    }

    // ==================== Volume accumulation ====================

    @Test
    public void testVolumeAccumulates() {
        // Volume = sum of (price * quantity) for each trade
        ticker.updateFromTrade(100.0, 2.0);  // 200.0
        ticker.updateFromTrade(150.0, 3.0);  // 450.0
        // Total volume = 650.0

        String json = ticker.toJson();
        assertTrue(json.contains("650.0"));
    }

    // ==================== toJson: all fields ====================

    @Test
    public void testToJson_ContainsAllExpectedFields() {
        ticker.updateFromTrade(100.0, 1.0);
        ticker.updateFromTrade(120.0, 2.0);

        String json = ticker.toJson();
        assertTrue(json.contains("\"type\":\"TICKER_STATS\""));
        assertTrue(json.contains("\"marketId\":1"));
        assertTrue(json.contains("\"market\":\"BTC-USD\""));
        assertTrue(json.contains("\"lastPrice\":"));
        assertTrue(json.contains("\"priceChange\":"));
        assertTrue(json.contains("\"priceChangePercent\":"));
        assertTrue(json.contains("\"high24h\":"));
        assertTrue(json.contains("\"low24h\":"));
        assertTrue(json.contains("\"volume24h\":"));
        assertTrue(json.contains("\"timestamp\":"));
    }

    @Test
    public void testToJson_PriceChange_Calculated() {
        ticker.updateFromTrade(100.0, 1.0);  // open = 100
        ticker.updateFromTrade(120.0, 1.0);  // last = 120
        // priceChange = 120 - 100 = 20
        // priceChangePercent = 20 / 100 * 100 = 20.0

        String json = ticker.toJson();
        assertTrue(json.contains("\"priceChange\":20.0"));
        assertTrue(json.contains("\"priceChangePercent\":20.0"));
    }

    @Test
    public void testToJson_PriceDecrease() {
        ticker.updateFromTrade(100.0, 1.0);  // open = 100
        ticker.updateFromTrade(80.0, 1.0);   // last = 80
        // priceChange = -20

        String json = ticker.toJson();
        assertTrue(json.contains("\"priceChange\":-20.0"));
    }

    // ==================== hasData ====================

    @Test
    public void testHasData_FalseInitially_TrueAfterTrade() {
        assertFalse(ticker.hasData());
        ticker.updateFromTrade(100.0, 1.0);
        assertTrue(ticker.hasData());
    }
}
