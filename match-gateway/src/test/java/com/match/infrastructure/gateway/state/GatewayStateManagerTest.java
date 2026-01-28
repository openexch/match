package com.match.infrastructure.gateway.state;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for GatewayStateManager accessor/helper methods.
 * Does NOT test listener methods (those need real SBE decoders and buffers).
 */
public class GatewayStateManagerTest {

    private GatewayStateManager manager;

    @Before
    public void setUp() {
        manager = new GatewayStateManager();
    }

    // ==================== Order Book Access ====================

    @Test
    public void getOrderBookReturnsNullInitially() {
        assertNull(manager.getOrderBook(1));
        assertNull(manager.getOrderBook(2));
        assertNull(manager.getOrderBook(99));
    }

    // ==================== Trades ====================

    @Test
    public void getTradesReturnsEmptyBuffer() {
        TradeRingBuffer trades = manager.getTrades();
        assertNotNull(trades);
        assertEquals(0, trades.getCount());
        assertFalse(trades.hasData());
    }

    // ==================== Initial Snapshot ====================

    @Test
    public void getInitialBookSnapshotReturnsNullWhenNoData() {
        assertNull(manager.getInitialBookSnapshot(1));
        assertNull(manager.getInitialBookSnapshot(999));
    }

    // ==================== Recent Trades JSON ====================

    @Test
    public void getRecentTradesJsonReturnsValidJson() {
        String json = manager.getRecentTradesJson(10);
        assertNotNull(json);
        // Should be parseable JSON containing required fields
        assertTrue("Should contain type field", json.contains("\"type\""));
        assertTrue("Should contain TRADES_BATCH", json.contains("TRADES_BATCH"));
        assertTrue("Should contain trades array", json.contains("\"trades\""));
    }

    @Test
    public void getRecentTradesJsonWithZeroLimit() {
        String json = manager.getRecentTradesJson(0);
        assertNotNull(json);
        assertTrue(json.contains("\"trades\""));
    }

    // ==================== Ticker Stats ====================

    @Test
    public void getTickerStatsReturnsNullWhenNoData() {
        assertNull(manager.getTickerStats(1));
        assertNull(manager.getTickerStats(2));
        assertNull(manager.getTickerStats(999));
    }

    // ==================== WebSocket ====================

    @Test
    public void setWebSocketNullDoesNotCrash() {
        manager.setWebSocket(null);
        // Should not throw
    }

    @Test
    public void setWebSocketNullThenAccessorsStillWork() {
        manager.setWebSocket(null);
        assertNull(manager.getOrderBook(1));
        assertNotNull(manager.getTrades());
        assertNotNull(manager.getRecentTradesJson(5));
    }
}
