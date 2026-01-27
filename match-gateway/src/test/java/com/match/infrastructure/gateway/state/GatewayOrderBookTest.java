package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for GatewayOrderBook: snapshot updates, delta operations, JSON caching.
 */
public class GatewayOrderBookTest {

    private GatewayOrderBook book;

    @Before
    public void setUp() {
        book = new GatewayOrderBook();
    }

    // ==================== Initial State ====================

    @Test
    public void testInitialState_HasDataFalse() {
        assertFalse(book.hasData());
    }

    @Test
    public void testInitialState_VersionZero() {
        assertEquals(0L, book.getVersion());
    }

    @Test
    public void testInitialState_ToJsonNull() {
        assertNull(book.toJson());
    }

    @Test
    public void testInitialState_CountsZero() {
        assertEquals(0, book.getBidCount());
        assertEquals(0, book.getAskCount());
    }

    // ==================== update() ====================

    @Test
    public void testUpdate_SetsHasDataTrue() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        JsonArray asks = makeLevels(new double[][]{{101.0, 4.0, 2}});

        book.update(1, "BTC-USD", bids, asks, 1L, 1L, 1L, 1000L);

        assertTrue(book.hasData());
    }

    @Test
    public void testUpdate_SetsVersion() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        JsonArray asks = makeLevels(new double[][]{{101.0, 4.0, 2}});

        book.update(1, "BTC-USD", bids, asks, 10L, 11L, 42L, 1000L);

        assertEquals(42L, book.getVersion());
    }

    @Test
    public void testUpdate_SetsBidAndAskCounts() {
        JsonArray bids = makeLevels(new double[][]{
            {100.0, 5.0, 3},
            {99.0, 2.0, 1}
        });
        JsonArray asks = makeLevels(new double[][]{
            {101.0, 4.0, 2},
            {102.0, 6.0, 4},
            {103.0, 1.0, 1}
        });

        book.update(1, "BTC-USD", bids, asks, 1L, 1L, 1L, 1000L);

        assertEquals(2, book.getBidCount());
        assertEquals(3, book.getAskCount());
    }

    @Test
    public void testUpdate_SetsMarketId() {
        book.update(3, "SOL-USD", new JsonArray(), makeLevels(new double[][]{{50.0, 1.0, 1}}), 1L, 1L, 1L, 1000L);
        assertEquals(3, book.getMarketId());
    }

    // ==================== Getters: bid/ask price/quantity ====================

    @Test
    public void testGetters_BidPriceAndQuantity() {
        JsonArray bids = makeLevels(new double[][]{
            {100.5, 5.25, 3},
            {99.0, 2.0, 1}
        });
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        assertEquals(100.5, book.getBidPrice(0), 0.0001);
        assertEquals(5.25, book.getBidQuantity(0), 0.0001);
        assertEquals(99.0, book.getBidPrice(1), 0.0001);
        assertEquals(2.0, book.getBidQuantity(1), 0.0001);
    }

    @Test
    public void testGetters_AskPriceAndQuantity() {
        JsonArray asks = makeLevels(new double[][]{
            {101.0, 4.0, 2},
            {102.5, 6.75, 4}
        });
        book.update(1, "BTC-USD", new JsonArray(), asks, 1L, 1L, 1L, 1000L);

        assertEquals(101.0, book.getAskPrice(0), 0.0001);
        assertEquals(4.0, book.getAskQuantity(0), 0.0001);
        assertEquals(102.5, book.getAskPrice(1), 0.0001);
        assertEquals(6.75, book.getAskQuantity(1), 0.0001);
    }

    // ==================== toJson() ====================

    @Test
    public void testToJson_ContainsCorrectStructure() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        JsonArray asks = makeLevels(new double[][]{{101.0, 4.0, 2}});

        book.update(1, "BTC-USD", bids, asks, 10L, 11L, 42L, 5000L);

        String json = book.toJson();
        assertNotNull(json);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("BOOK_SNAPSHOT", parsed.get("type").getAsString());
        assertEquals(1, parsed.get("marketId").getAsInt());
        assertEquals("BTC-USD", parsed.get("market").getAsString());
        assertEquals(5000L, parsed.get("timestamp").getAsLong());
        assertEquals(10L, parsed.get("bidVersion").getAsLong());
        assertEquals(11L, parsed.get("askVersion").getAsLong());
        assertEquals(42L, parsed.get("version").getAsLong());

        JsonArray bidsArr = parsed.getAsJsonArray("bids");
        assertEquals(1, bidsArr.size());
        assertEquals(100.0, bidsArr.get(0).getAsJsonObject().get("price").getAsDouble(), 0.0001);

        JsonArray asksArr = parsed.getAsJsonArray("asks");
        assertEquals(1, asksArr.size());
        assertEquals(101.0, asksArr.get(0).getAsJsonObject().get("price").getAsDouble(), 0.0001);
    }

    @Test
    public void testToJson_AfterUpdate_NotNull() {
        book.update(1, "BTC-USD", makeLevels(new double[][]{{100.0, 1.0, 1}}), new JsonArray(), 1L, 1L, 1L, 1000L);
        assertNotNull(book.toJson());
    }

    // ==================== applyDelta: NEW_LEVEL ====================

    @Test
    public void testApplyDelta_NewBidLevel_InsertedAtCorrectPosition() {
        // Start with bids at 100 and 98 (descending)
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}, {98.0, 2.0, 1}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        // Insert bid at 99 — should go between 100 and 98
        book.applyDelta("BID", 99.0, 3.0, 2, "NEW_LEVEL");

        assertEquals(3, book.getBidCount());
        assertEquals(100.0, book.getBidPrice(0), 0.0001);
        assertEquals(99.0, book.getBidPrice(1), 0.0001);
        assertEquals(98.0, book.getBidPrice(2), 0.0001);
    }

    @Test
    public void testApplyDelta_NewAskLevel_InsertedAtCorrectPosition() {
        // Start with asks at 101 and 103 (ascending)
        JsonArray asks = makeLevels(new double[][]{{101.0, 5.0, 3}, {103.0, 2.0, 1}});
        book.update(1, "BTC-USD", new JsonArray(), asks, 1L, 1L, 1L, 1000L);

        // Insert ask at 102 — should go between 101 and 103
        book.applyDelta("ASK", 102.0, 3.0, 2, "NEW_LEVEL");

        assertEquals(3, book.getAskCount());
        assertEquals(101.0, book.getAskPrice(0), 0.0001);
        assertEquals(102.0, book.getAskPrice(1), 0.0001);
        assertEquals(103.0, book.getAskPrice(2), 0.0001);
    }

    @Test
    public void testApplyDelta_NewBidLevel_HigherThanAll_InsertsFirst() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        book.applyDelta("BID", 105.0, 2.0, 1, "NEW_LEVEL");

        assertEquals(2, book.getBidCount());
        assertEquals(105.0, book.getBidPrice(0), 0.0001);
        assertEquals(100.0, book.getBidPrice(1), 0.0001);
    }

    @Test
    public void testApplyDelta_NewAskLevel_LowerThanAll_InsertsFirst() {
        JsonArray asks = makeLevels(new double[][]{{105.0, 5.0, 3}});
        book.update(1, "BTC-USD", new JsonArray(), asks, 1L, 1L, 1L, 1000L);

        book.applyDelta("ASK", 100.0, 2.0, 1, "NEW_LEVEL");

        assertEquals(2, book.getAskCount());
        assertEquals(100.0, book.getAskPrice(0), 0.0001);
        assertEquals(105.0, book.getAskPrice(1), 0.0001);
    }

    // ==================== applyDelta: UPDATE_LEVEL ====================

    @Test
    public void testApplyDelta_UpdateLevel_ChangesQuantityAndOrderCount() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        book.applyDelta("BID", 100.0, 10.0, 5, "UPDATE_LEVEL");

        assertEquals(1, book.getBidCount());
        assertEquals(100.0, book.getBidPrice(0), 0.0001);
        assertEquals(10.0, book.getBidQuantity(0), 0.0001);
    }

    @Test
    public void testApplyDelta_UpdateLevel_NonExistentPrice_NoEffect() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        book.applyDelta("BID", 999.0, 10.0, 5, "UPDATE_LEVEL");

        assertEquals(1, book.getBidCount());
        assertEquals(5.0, book.getBidQuantity(0), 0.0001);
    }

    // ==================== applyDelta: DELETE_LEVEL ====================

    @Test
    public void testApplyDelta_DeleteLevel_RemovesAndShifts() {
        JsonArray bids = makeLevels(new double[][]{
            {100.0, 5.0, 3},
            {99.0, 3.0, 2},
            {98.0, 1.0, 1}
        });
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        book.applyDelta("BID", 99.0, 0, 0, "DELETE_LEVEL");

        assertEquals(2, book.getBidCount());
        assertEquals(100.0, book.getBidPrice(0), 0.0001);
        assertEquals(98.0, book.getBidPrice(1), 0.0001);
    }

    @Test
    public void testApplyDelta_DeleteLevel_NonExistentPrice_NoEffect() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        book.applyDelta("BID", 999.0, 0, 0, "DELETE_LEVEL");

        assertEquals(1, book.getBidCount());
    }

    @Test
    public void testApplyDelta_DeleteLevel_Ask() {
        JsonArray asks = makeLevels(new double[][]{
            {101.0, 4.0, 2},
            {102.0, 6.0, 4}
        });
        book.update(1, "BTC-USD", new JsonArray(), asks, 1L, 1L, 1L, 1000L);

        book.applyDelta("ASK", 101.0, 0, 0, "DELETE_LEVEL");

        assertEquals(1, book.getAskCount());
        assertEquals(102.0, book.getAskPrice(0), 0.0001);
    }

    // ==================== updateVersions() ====================

    @Test
    public void testUpdateVersions_UpdatesMetadataAndRegeneratesJson() {
        JsonArray bids = makeLevels(new double[][]{{100.0, 5.0, 3}});
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        String jsonBefore = book.toJson();

        book.updateVersions(1, "BTC-USD", 50L, 51L, 9999L);

        assertEquals(51L, book.getVersion()); // max(50, 51)
        String jsonAfter = book.toJson();
        assertNotNull(jsonAfter);
        assertNotEquals(jsonBefore, jsonAfter);

        JsonObject parsed = JsonParser.parseString(jsonAfter).getAsJsonObject();
        assertEquals(50L, parsed.get("bidVersion").getAsLong());
        assertEquals(51L, parsed.get("askVersion").getAsLong());
        assertEquals(9999L, parsed.get("timestamp").getAsLong());
    }

    // ==================== MAX_LEVELS Boundary ====================

    @Test
    public void testMaxLevels_BidsClampedTo20() {
        double[][] levels = new double[25][];
        for (int i = 0; i < 25; i++) {
            levels[i] = new double[]{100.0 - i, 1.0, 1};
        }
        JsonArray bids = makeLevels(levels);
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        assertEquals(20, book.getBidCount());
    }

    @Test
    public void testMaxLevels_NewLevelIgnoredIfNotInTop20() {
        // Fill with 20 bid levels: 120, 119, ..., 101
        double[][] levels = new double[20][];
        for (int i = 0; i < 20; i++) {
            levels[i] = new double[]{120.0 - i, 1.0, 1};
        }
        JsonArray bids = makeLevels(levels);
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        assertEquals(20, book.getBidCount());
        // Lowest bid is 101.0

        // Try to insert bid at 50.0 — lower than all existing, should be ignored
        book.applyDelta("BID", 50.0, 5.0, 1, "NEW_LEVEL");

        // Count should still be 20
        assertEquals(20, book.getBidCount());
        // Lowest bid should still be 101.0
        assertEquals(101.0, book.getBidPrice(19), 0.0001);
    }

    @Test
    public void testMaxLevels_NewLevelReplacesLowestIfBetter() {
        // Fill with 20 bid levels: 120, 119, ..., 101
        double[][] levels = new double[20][];
        for (int i = 0; i < 20; i++) {
            levels[i] = new double[]{120.0 - i, 1.0, 1};
        }
        JsonArray bids = makeLevels(levels);
        book.update(1, "BTC-USD", bids, new JsonArray(), 1L, 1L, 1L, 1000L);

        // Insert bid at 115.5 — higher than 101 (lowest), should replace
        book.applyDelta("BID", 115.5, 5.0, 1, "NEW_LEVEL");

        assertEquals(20, book.getBidCount());
        // The new level should be in the book
        boolean found = false;
        for (int i = 0; i < book.getBidCount(); i++) {
            if (Math.abs(book.getBidPrice(i) - 115.5) < 0.0001) {
                found = true;
                break;
            }
        }
        assertTrue("New level 115.5 should be in the book", found);
    }

    // ==================== Empty book with no bids/asks ====================

    @Test
    public void testUpdate_EmptyBidsAndAsks_HasDataFalse() {
        book.update(1, "BTC-USD", new JsonArray(), new JsonArray(), 1L, 1L, 1L, 1000L);
        assertFalse(book.hasData());
    }

    // ==================== Helper ====================

    private JsonArray makeLevels(double[][] data) {
        JsonArray array = new JsonArray();
        for (double[] level : data) {
            JsonObject obj = new JsonObject();
            obj.addProperty("price", level[0]);
            obj.addProperty("quantity", level[1]);
            obj.addProperty("orderCount", (int) level[2]);
            array.add(obj);
        }
        return array;
    }
}
