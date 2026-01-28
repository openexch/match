package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for TradeRingBuffer: ring buffer semantics, batch adding, JSON output.
 */
public class TradeRingBufferTest {

    private TradeRingBuffer buffer;

    @Before
    public void setUp() {
        buffer = new TradeRingBuffer();
    }

    // ==================== Initial State ====================

    @Test
    public void testInitialState_HasDataFalse() {
        assertFalse(buffer.hasData());
    }

    @Test
    public void testInitialState_CountZero() {
        assertEquals(0, buffer.getCount());
    }

    @Test
    public void testInitialState_GetRecentEmpty() {
        List<AggregatedTrade> recent = buffer.getRecent(10);
        assertTrue(recent.isEmpty());
    }

    // ==================== addBatch ====================

    @Test
    public void testAddBatch_SingleTrade() {
        JsonArray trades = makeTrades(new double[][]{{100.0, 1.0, 1, 1000}});

        buffer.addBatch(1, "BTC-USD", trades);

        assertTrue(buffer.hasData());
        assertEquals(1, buffer.getCount());
    }

    @Test
    public void testAddBatch_MultipleTrades() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 1, 1000},
            {101.0, 2.0, 2, 2000},
            {102.0, 3.0, 3, 3000}
        });

        buffer.addBatch(1, "BTC-USD", trades);

        assertEquals(3, buffer.getCount());
    }

    // ==================== getRecent ====================

    @Test
    public void testGetRecent_ReturnsMostRecentFirst() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 1, 1000},
            {200.0, 2.0, 2, 2000},
            {300.0, 3.0, 3, 3000}
        });
        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent = buffer.getRecent(3);

        assertEquals(3, recent.size());
        // Most recent first
        assertEquals(300.0, recent.get(0).price, 0.0001);
        assertEquals(200.0, recent.get(1).price, 0.0001);
        assertEquals(100.0, recent.get(2).price, 0.0001);
    }

    @Test
    public void testGetRecent_RespectsLimit() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 1, 1000},
            {200.0, 2.0, 2, 2000},
            {300.0, 3.0, 3, 3000}
        });
        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent = buffer.getRecent(2);

        assertEquals(2, recent.size());
        assertEquals(300.0, recent.get(0).price, 0.0001);
        assertEquals(200.0, recent.get(1).price, 0.0001);
    }

    @Test
    public void testGetRecent_LimitLargerThanCount() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 1, 1000}
        });
        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent = buffer.getRecent(100);

        assertEquals(1, recent.size());
    }

    @Test
    public void testGetRecent_ReturnsCopies() {
        JsonArray trades = makeTrades(new double[][]{{100.0, 1.0, 1, 1000}});
        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent1 = buffer.getRecent(1);
        List<AggregatedTrade> recent2 = buffer.getRecent(1);

        assertNotSame(recent1.get(0), recent2.get(0));
        assertEquals(recent1.get(0).price, recent2.get(0).price, 0.0001);
    }

    // ==================== Ring buffer wrapping ====================

    @Test
    public void testRingBuffer_Wrapping_OldestDiscarded() {
        TradeRingBuffer small = new TradeRingBuffer(3);

        // Add 5 trades to a buffer of capacity 3
        for (int i = 1; i <= 5; i++) {
            JsonArray trades = makeTrades(new double[][]{{i * 10.0, 1.0, 1, i * 1000}});
            small.addBatch(1, "BTC-USD", trades);
        }

        assertEquals(3, small.getCount());

        List<AggregatedTrade> recent = small.getRecent(3);
        // Most recent should be 50.0, 40.0, 30.0 (trades 5, 4, 3)
        assertEquals(50.0, recent.get(0).price, 0.0001);
        assertEquals(40.0, recent.get(1).price, 0.0001);
        assertEquals(30.0, recent.get(2).price, 0.0001);
    }

    @Test
    public void testRingBuffer_CountDoesNotExceedCapacity() {
        TradeRingBuffer small = new TradeRingBuffer(5);

        for (int i = 0; i < 20; i++) {
            JsonArray trades = makeTrades(new double[][]{{i, 1.0, 1, i * 1000}});
            small.addBatch(1, "BTC-USD", trades);
        }

        assertEquals(5, small.getCount());
    }

    @Test
    public void testRingBuffer_BatchWrapping() {
        TradeRingBuffer small = new TradeRingBuffer(3);

        // Add a batch of 5 trades at once to capacity 3
        JsonArray trades = makeTrades(new double[][]{
            {10.0, 1.0, 1, 1000},
            {20.0, 1.0, 1, 2000},
            {30.0, 1.0, 1, 3000},
            {40.0, 1.0, 1, 4000},
            {50.0, 1.0, 1, 5000}
        });
        small.addBatch(1, "BTC-USD", trades);

        assertEquals(3, small.getCount());

        List<AggregatedTrade> recent = small.getRecent(3);
        assertEquals(50.0, recent.get(0).price, 0.0001);
        assertEquals(40.0, recent.get(1).price, 0.0001);
        assertEquals(30.0, recent.get(2).price, 0.0001);
    }

    // ==================== Custom capacity ====================

    @Test
    public void testCustomCapacity() {
        TradeRingBuffer custom = new TradeRingBuffer(10);

        for (int i = 0; i < 15; i++) {
            JsonArray trades = makeTrades(new double[][]{{i, 1.0, 1, i * 1000}});
            custom.addBatch(1, "BTC-USD", trades);
        }

        assertEquals(10, custom.getCount());
    }

    // ==================== toJson ====================

    @Test
    public void testToJson_ValidStructure() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 2, 5000},
            {101.0, 3.0, 1, 6000}
        });
        buffer.addBatch(1, "BTC-USD", trades);

        String json = buffer.toJson(10);
        assertNotNull(json);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("TRADES_BATCH", parsed.get("type").getAsString());
        assertEquals(1, parsed.get("marketId").getAsInt());
        assertEquals("BTC-USD", parsed.get("market").getAsString());

        JsonArray arr = parsed.getAsJsonArray("trades");
        assertEquals(2, arr.size());

        // Most recent first
        assertEquals(101.0, arr.get(0).getAsJsonObject().get("price").getAsDouble(), 0.0001);
        assertEquals(100.0, arr.get(1).getAsJsonObject().get("price").getAsDouble(), 0.0001);
    }

    @Test
    public void testToJson_RespectsLimit() {
        JsonArray trades = makeTrades(new double[][]{
            {100.0, 1.0, 1, 1000},
            {200.0, 2.0, 2, 2000},
            {300.0, 3.0, 3, 3000}
        });
        buffer.addBatch(1, "BTC-USD", trades);

        String json = buffer.toJson(2);
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, parsed.getAsJsonArray("trades").size());
    }

    @Test
    public void testToJson_EmptyBuffer() {
        String json = buffer.toJson(10);
        assertNotNull(json);
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(0, parsed.getAsJsonArray("trades").size());
    }

    // ==================== Trade fields ====================

    @Test
    public void testTradeFields_BuyCountSellCount() {
        JsonArray trades = new JsonArray();
        JsonObject trade = new JsonObject();
        trade.addProperty("price", 100.0);
        trade.addProperty("quantity", 2.0);
        trade.addProperty("tradeCount", 3);
        trade.addProperty("buyCount", 2);
        trade.addProperty("sellCount", 1);
        trade.addProperty("timestamp", 5000L);
        trades.add(trade);

        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent = buffer.getRecent(1);
        AggregatedTrade t = recent.get(0);
        assertEquals(100.0, t.price, 0.0001);
        assertEquals(2.0, t.quantity, 0.0001);
        assertEquals(3, t.tradeCount);
        assertEquals(2, t.buyCount);
        assertEquals(1, t.sellCount);
        assertEquals(5000L, t.timestamp);
    }

    @Test
    public void testTradeFields_MissingBuySellCount_DefaultsToZero() {
        JsonArray trades = new JsonArray();
        JsonObject trade = new JsonObject();
        trade.addProperty("price", 50.0);
        trade.addProperty("quantity", 1.0);
        trade.addProperty("tradeCount", 1);
        trade.addProperty("timestamp", 1000L);
        // No buyCount/sellCount
        trades.add(trade);

        buffer.addBatch(1, "BTC-USD", trades);

        List<AggregatedTrade> recent = buffer.getRecent(1);
        assertEquals(0, recent.get(0).buyCount);
        assertEquals(0, recent.get(0).sellCount);
    }

    // ==================== Helper ====================

    private JsonArray makeTrades(double[][] data) {
        JsonArray array = new JsonArray();
        for (double[] trade : data) {
            JsonObject obj = new JsonObject();
            obj.addProperty("price", trade[0]);
            obj.addProperty("quantity", trade[1]);
            obj.addProperty("tradeCount", (int) trade[2]);
            obj.addProperty("timestamp", (long) trade[3]);
            array.add(obj);
        }
        return array;
    }
}
