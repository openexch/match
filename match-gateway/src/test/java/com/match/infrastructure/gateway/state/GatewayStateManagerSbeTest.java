// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.state;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SBE integration tests for GatewayStateManager.
 * Encodes real SBE messages, decodes them, and verifies state updates.
 */
public class GatewayStateManagerSbeTest {

    private GatewayStateManager manager;
    private ExpandableDirectByteBuffer buffer;
    private MessageHeaderEncoder headerEnc;
    private MessageHeaderDecoder headerDec;

    @Before
    public void setUp() {
        manager = new GatewayStateManager();
        buffer = new ExpandableDirectByteBuffer(4096);
        headerEnc = new MessageHeaderEncoder();
        headerDec = new MessageHeaderDecoder();
    }

    // ==================== BookSnapshot ====================

    @Test
    public void testOnBookSnapshot_CreatesOrderBook() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertNotNull(book);
        assertTrue(book.hasData());
    }

    @Test
    public void testOnBookSnapshot_DataAccessible() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}, {fp(99.0), fp(10.0), 5}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertNotNull(book);
        assertEquals(2, book.getBidCount());
        assertEquals(1, book.getAskCount());
    }

    @Test
    public void testOnBookSnapshot_JsonContainsFields() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        String json = manager.getOrderBook(1).toJson();
        assertNotNull(json);
        assertTrue(json.contains("BOOK_SNAPSHOT"));
        assertTrue(json.contains("BTC-USD"));
        assertTrue(json.contains("bids"));
        assertTrue(json.contains("asks"));
    }

    @Test
    public void testOnBookSnapshot_GetInitialBookSnapshot() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        String snapshot = manager.getInitialBookSnapshot(1);
        assertNotNull(snapshot);
        assertTrue(snapshot.contains("BOOK_SNAPSHOT"));
    }

    @Test
    public void testOnBookSnapshot_EmptyBook() {
        encodeAndProcessBookSnapshot(1, 12345L, 0, 0,
            new long[][]{},
            new long[][]{}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertNotNull(book);
        assertFalse(book.hasData());
    }

    @Test
    public void testOnBookSnapshot_MultipleMarkets() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        encodeAndProcessBookSnapshot(2, 12345L, 5, 15,
            new long[][]{{fp(3000.0), fp(10.0), 7}},
            new long[][]{{fp(3001.0), fp(20.0), 4}}
        );

        assertNotNull(manager.getOrderBook(1));
        assertNotNull(manager.getOrderBook(2));
        // Different market, different data
        assertNotEquals(manager.getOrderBook(1).toJson(), manager.getOrderBook(2).toJson());
    }

    @Test
    public void testOnBookSnapshot_MarketNames() {
        encodeAndProcessBookSnapshot(1, 12345L, 1, 1,
            new long[][]{{fp(100.0), fp(1.0), 1}},
            new long[][]{}
        );
        encodeAndProcessBookSnapshot(2, 12345L, 1, 1,
            new long[][]{{fp(100.0), fp(1.0), 1}},
            new long[][]{}
        );
        encodeAndProcessBookSnapshot(3, 12345L, 1, 1,
            new long[][]{{fp(100.0), fp(1.0), 1}},
            new long[][]{}
        );

        assertTrue(manager.getOrderBook(1).toJson().contains("BTC-USD"));
        assertTrue(manager.getOrderBook(2).toJson().contains("ETH-USD"));
        assertTrue(manager.getOrderBook(3).toJson().contains("SOL-USD"));
    }

    // ==================== BookSnapshot staleness guard (match#95) ====================

    @Test
    public void testOnBookSnapshot_StaleAfterNewerSnapshot_BookUnchangedAndCounted() {
        // Establish a baseline at bookVersion=20.
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20, 20L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        assertEquals(0L, manager.getStaleSnapshotsDropped());
        String jsonBefore = manager.getOrderBook(1).toJson();

        // Redelivered old-leader snapshot at a lower bookVersion, carrying different (stale) levels.
        encodeAndProcessBookSnapshot(1, 12346L, 5, 5, 15L,
            new long[][]{{fp(90.0), fp(1.0), 1}},
            new long[][]{{fp(95.0), fp(1.0), 1}}
        );

        assertEquals(jsonBefore, manager.getOrderBook(1).toJson());
        assertEquals(20L, manager.getOrderBook(1).getVersion());
        assertEquals(1L, manager.getStaleSnapshotsDropped());
    }

    @Test
    public void testOnBookSnapshot_EqualVersionAfterNewerSnapshot_TreatedAsStale() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20, 20L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        String jsonBefore = manager.getOrderBook(1).toJson();

        // Duplicate redelivery at the SAME bookVersion — not a genuine advance, must be dropped.
        encodeAndProcessBookSnapshot(1, 12346L, 10, 20, 20L,
            new long[][]{{fp(999.0), fp(1.0), 1}},
            new long[][]{}
        );

        assertEquals(jsonBefore, manager.getOrderBook(1).toJson());
        assertEquals(1L, manager.getStaleSnapshotsDropped());
    }

    @Test
    public void testOnBookSnapshot_StaleAfterNewerDelta_BookUnchangedAndCounted() {
        // Baseline snapshot at bookVersion=10.
        encodeAndProcessBookSnapshot(1, 12345L, 10, 10, 10L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        // A delta advances the book to version 15 (bidVersion/askVersion fallback, matching how the
        // existing encodeAndProcessBookDelta helper exercises the delta path elsewhere in this file).
        encodeAndProcessBookDelta(1, 12346L, 15, 15,
            new Object[][]{
                {fp(100.0), fp(7.0), 4, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}
            }
        );
        assertEquals(15L, manager.getOrderBook(1).getVersion());
        String jsonBefore = manager.getOrderBook(1).toJson();

        // Redelivered old-leader snapshot at a lower bookVersion than the delta already applied.
        encodeAndProcessBookSnapshot(1, 12347L, 3, 3, 12L,
            new long[][]{{fp(50.0), fp(1.0), 1}},
            new long[][]{{fp(60.0), fp(1.0), 1}}
        );

        assertEquals(jsonBefore, manager.getOrderBook(1).toJson());
        assertEquals(15L, manager.getOrderBook(1).getVersion());
        assertEquals(1L, manager.getStaleSnapshotsDropped());
    }

    @Test
    public void testOnBookSnapshot_HigherVersionApplied() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20, 20L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        // A genuine re-snapshot after a gap — HIGHER bookVersion — must be applied.
        encodeAndProcessBookSnapshot(1, 12346L, 30, 30, 30L,
            new long[][]{{fp(200.0), fp(9.0), 6}},
            new long[][]{{fp(201.0), fp(4.0), 3}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals(30L, book.getVersion());
        assertEquals(200.0, book.getBidPrice(0), 0.0001);
        assertEquals(201.0, book.getAskPrice(0), 0.0001);
        assertEquals(0L, manager.getStaleSnapshotsDropped());
    }

    @Test
    public void testOnBookSnapshot_LegacyVersionZero_AppliedUnconditionally() {
        // match#95 regression: a pre-v4 upstream never sends a bookVersion (mixed-version rolling
        // update), so the fallback-computed version (max(bidVersion, askVersion)) can be numerically
        // LOWER on a later legacy snapshot than an earlier one without meaning anything is stale.
        // The guard must not engage when the incoming message carries no real bookVersion — dropping
        // legacy snapshots would freeze the book forever.
        encodeAndProcessBookSnapshot(1, 12345L, 100, 100, 0L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        assertEquals(100L, manager.getOrderBook(1).getVersion());

        // Later legacy snapshot with a lower per-side version (bookVersion still absent/0) —
        // today's apply-unconditionally behavior must be preserved.
        encodeAndProcessBookSnapshot(1, 12346L, 1, 1, 0L,
            new long[][]{{fp(50.0), fp(2.0), 1}},
            new long[][]{{fp(51.0), fp(1.0), 1}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals(1L, book.getVersion());
        assertEquals(50.0, book.getBidPrice(0), 0.0001);
        assertEquals(51.0, book.getAskPrice(0), 0.0001);
        assertEquals(0L, manager.getStaleSnapshotsDropped());
    }

    // ==================== TradesBatch ====================

    @Test
    public void testOnTradesBatch_StoresTrades() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(100.0), fp(5.0), 1, 12345L}}
        );

        assertTrue(manager.getTrades().hasData());
        assertEquals(1, manager.getTrades().getCount());
    }

    @Test
    public void testOnTradesBatch_TradesJsonContainsFields() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(50000.0), fp(0.5), 3, 12345L}}
        );

        String json = manager.getRecentTradesJson(10);
        assertNotNull(json);
        assertTrue(json.contains("TRADES_BATCH"));
        assertTrue(json.contains("\"trades\""));
    }

    @Test
    public void testOnTradesBatch_MultipleTrades() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{
                {fp(100.0), fp(5.0), 1, 12345L},
                {fp(101.0), fp(3.0), 2, 12346L},
                {fp(99.0), fp(7.0), 1, 12347L}
            }
        );

        assertEquals(3, manager.getTrades().getCount());
    }

    @Test
    public void testOnTradesBatch_UpdatesTickerStats() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(50000.0), fp(1.0), 1, 12345L}}
        );

        String ticker = manager.getTickerStats(1);
        assertNotNull(ticker);
        assertTrue(ticker.contains("TICKER_STATS"));
        assertTrue(ticker.contains("\"marketId\":1"));
        assertTrue(ticker.contains("BTC-USD"));
    }

    @Test
    public void testOnTradesBatch_MultipleUpdatesTracker() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(100.0), fp(1.0), 1, 12345L}}
        );
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(105.0), fp(2.0), 1, 12346L}}
        );

        String ticker = manager.getTickerStats(1);
        assertNotNull(ticker);
        // Should have updated high price
        assertTrue(ticker.contains("TICKER_STATS"));
    }

    @Test
    public void testOnTradesBatch_DifferentMarkets() {
        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{{fp(50000.0), fp(1.0), 1, 12345L}}
        );
        encodeAndProcessTradesBatch(2, System.currentTimeMillis(),
            new long[][]{{fp(3000.0), fp(2.0), 1, 12346L}}
        );

        assertNotNull(manager.getTickerStats(1));
        assertNotNull(manager.getTickerStats(2));
    }

    @Test
    public void testOnTradesBatch_TakerSideMappedToJsonSide() {
        CapturingWebSocket ws = new CapturingWebSocket();
        manager.setWebSocket(ws);

        encodeAndProcessTradesBatch(1, System.currentTimeMillis(),
            new long[][]{
                {fp(100.0), fp(5.0), 1, 12345L},
                {fp(101.0), fp(3.0), 2, 12346L}
            },
            new OrderSide[]{OrderSide.BID, OrderSide.ASK}
        );

        // Broadcast JSON carries the taker side per trade
        String batch = ws.messages.stream()
            .filter(m -> m.contains("TRADES_BATCH")).findFirst().orElseThrow();
        assertTrue(batch.contains("\"side\":\"BUY\""));
        assertTrue(batch.contains("\"side\":\"SELL\""));

        // Ring buffer round-trip preserves it too
        String json = manager.getRecentTradesJson(10);
        assertTrue(json.contains("\"side\":\"BUY\""));
        assertTrue(json.contains("\"side\":\"SELL\""));
    }

    @Test
    public void testOnTradesBatch_PreV5Header_SideOmitted() {
        // Mixed-version rolling update: a v4 (pre-takerSide) upstream. Encode a
        // v5 message, then stamp the header's version back to 4 — the decoder
        // must return OrderSide.NULL_VAL for takerSide while price/quantity/
        // timestamp still parse, and the gateway must omit "side".
        encodeAndProcessTradesBatchWithHeaderVersion(1, System.currentTimeMillis(),
            new long[][]{{fp(123.0), fp(2.0), 4, 98765L}},
            new OrderSide[]{OrderSide.BID},
            4
        );

        assertEquals(1, manager.getTrades().getCount());
        String json = manager.getRecentTradesJson(10);
        assertFalse("pre-v5 upstream must not produce a side", json.contains("\"side\""));
        assertTrue(json.contains("123.0"));   // price still parses
        assertTrue(json.contains("98765"));   // timestamp still parses
        assertTrue(json.contains("\"tradeCount\":4"));
    }

    // ==================== BookDelta ====================

    @Test
    public void testOnBookDelta_AppliesDelta() {
        // First, create a book via snapshot
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        // Apply an UPDATE_LEVEL delta on bid side
        encodeAndProcessBookDelta(1, 12346L, 11, 20,
            new Object[][]{
                {fp(100.0), fp(7.0), 4, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}
            }
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertNotNull(book);
        assertTrue(book.hasData());
    }

    @Test
    public void testOnBookDelta_NewLevel() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        // Add a new bid level
        encodeAndProcessBookDelta(1, 12346L, 11, 20,
            new Object[][]{
                {fp(99.0), fp(10.0), 5, OrderSide.BID, BookUpdateType.NEW_LEVEL}
            }
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals(2, book.getBidCount());
    }

    @Test
    public void testOnBookDelta_DeleteLevel() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 20,
            new long[][]{{fp(100.0), fp(5.0), 3}, {fp(99.0), fp(10.0), 5}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );

        // Delete a bid level
        encodeAndProcessBookDelta(1, 12346L, 11, 20,
            new Object[][]{
                {fp(99.0), fp(0), 0, OrderSide.BID, BookUpdateType.DELETE_LEVEL}
            }
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals(1, book.getBidCount());
    }

    @Test
    public void testOnBookDelta_NoExistingBook_CreatesOne() {
        // Delta without prior snapshot should create a new book
        encodeAndProcessBookDelta(1, 12346L, 1, 1,
            new Object[][]{
                {fp(100.0), fp(5.0), 3, OrderSide.BID, BookUpdateType.NEW_LEVEL}
            }
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertNotNull(book);
    }

    // ==================== BookDelta chain-break staleness (match#96) ====================

    @Test
    public void testOnBookDelta_ChainBreak_DeltaDroppedBookFlaggedStale() {
        // Baseline snapshot at bookVersion=10: one bid @100 qty 5.
        encodeAndProcessBookSnapshot(1, 12345L, 10, 10, 10L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        assertEquals(0L, manager.getChainBreaks());
        assertFalse(manager.getOrderBook(1).isStale());
        assertFalse("healthy book JSON must not carry a stale key",
            manager.getOrderBook(1).toJson().contains("\"stale\""));

        // A delta that names fromVersion=99 (!= our 10) — a genuine forward gap. If applied it would
        // set bid @100 to qty 999; it must instead be DROPPED and the book flagged stale.
        encodeAndProcessBookDelta(1, 12346L, 15, 15, 100L, 99L,
            new Object[][]{
                {fp(100.0), fp(999.0), 7, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}
            }
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals("chain break counted", 1L, manager.getChainBreaks());
        assertTrue("book flagged stale", book.isStale());
        assertEquals("version unchanged (delta dropped)", 10L, book.getVersion());
        assertEquals("delta NOT applied — bid qty unchanged", 5.0, book.getBidQuantity(0), 0.0001);
        assertTrue("served JSON advertises stale:true", book.toJson().contains("\"stale\":true"));
    }

    @Test
    public void testOnBookDelta_WhileStale_DeltasDroppedNotReCountedAsBreaks() {
        // Drive the book stale via a chain break.
        encodeAndProcessBookSnapshot(1, 12345L, 10, 10, 10L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        encodeAndProcessBookDelta(1, 12346L, 15, 15, 100L, 99L,
            new Object[][]{{fp(100.0), fp(999.0), 7, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );
        assertTrue(manager.getOrderBook(1).isStale());
        assertEquals(1L, manager.getChainBreaks());

        // Further deltas while stale must be dropped and counted separately — NOT re-counted as
        // chain breaks (chainBreaks tracks distinct break events, not every delta while stale).
        encodeAndProcessBookDelta(1, 12347L, 20, 20, 200L, 150L,
            new Object[][]{{fp(100.0), fp(777.0), 9, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );
        encodeAndProcessBookDelta(1, 12348L, 25, 25, 300L, 250L,
            new Object[][]{{fp(100.0), fp(555.0), 9, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals("deltas-while-stale counted distinctly", 2L, manager.getDeltasDroppedWhileStale());
        assertEquals("chain breaks NOT re-incremented while stale", 1L, manager.getChainBreaks());
        assertTrue("still stale", book.isStale());
        assertEquals("still not applied", 5.0, book.getBidQuantity(0), 0.0001);
    }

    @Test
    public void testOnBookDelta_RecoverySnapshotClearsStale_ThenChainedDeltaApplies() {
        encodeAndProcessBookSnapshot(1, 12345L, 10, 10, 10L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        encodeAndProcessBookDelta(1, 12346L, 15, 15, 100L, 99L,
            new Object[][]{{fp(100.0), fp(999.0), 7, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );
        assertTrue(manager.getOrderBook(1).isStale());

        // Recovery: a fresh snapshot at a HIGHER bookVersion (passes the match#95 monotonic guard)
        // re-anchors the chain and clears the stale flag.
        encodeAndProcessBookSnapshot(1, 12347L, 200, 200, 200L,
            new long[][]{{fp(200.0), fp(9.0), 6}},
            new long[][]{{fp(201.0), fp(4.0), 3}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertFalse("stale flag cleared on recovery", book.isStale());
        assertEquals(200L, book.getVersion());
        assertEquals(200.0, book.getBidPrice(0), 0.0001);
        assertFalse("stale marker gone from JSON after recovery", book.toJson().contains("\"stale\""));
        assertEquals("recovery snapshot not counted as stale", 0L, manager.getStaleSnapshotsDropped());

        // A chained delta on top of the recovered book (fromVersion == 200) applies normally.
        encodeAndProcessBookDelta(1, 12348L, 201, 200, 201L, 200L,
            new Object[][]{{fp(200.0), fp(42.0), 2, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );
        assertEquals("post-recovery delta applied", 42.0, book.getBidQuantity(0), 0.0001);
        assertEquals(201L, book.getVersion());
        assertEquals("no new chain break after recovery", 1L, manager.getChainBreaks());
    }

    @Test
    public void testOnBookDelta_HealthyChainedDelta_NoStaleKeyByteCompat() {
        // Byte-compat regression: a well-chained delta (fromVersion == our version) applies cleanly
        // and the served JSON must carry NO stale key (healthy payloads stay byte-identical).
        encodeAndProcessBookSnapshot(1, 12345L, 10, 10, 10L,
            new long[][]{{fp(100.0), fp(5.0), 3}},
            new long[][]{{fp(101.0), fp(8.0), 2}}
        );
        encodeAndProcessBookDelta(1, 12346L, 11, 10, 11L, 10L,
            new Object[][]{{fp(100.0), fp(7.0), 4, OrderSide.BID, BookUpdateType.UPDATE_LEVEL}}
        );

        GatewayOrderBook book = manager.getOrderBook(1);
        assertEquals("chained delta applied", 7.0, book.getBidQuantity(0), 0.0001);
        assertEquals(11L, book.getVersion());
        assertFalse("no chain break on a healthy chained delta", book.isStale());
        assertEquals(0L, manager.getChainBreaks());
        assertFalse("healthy JSON has no stale key", book.toJson().contains("\"stale\""));
    }

    // ==================== OrderStatusBatch (decode no-op) ====================

    @Test
    public void testOnOrderStatusBatch_NoCrash() {
        encodeAndProcessOrderStatusBatch(1, System.currentTimeMillis(), 0);
        // No exception = pass
    }

    @Test
    public void testOnOrderStatusBatch_DoesNotBroadcast() {
        // Per-user order data flows only via the OMS's user-scoped WebSocket;
        // broadcasting every participant's orders on the public market feed
        // was a privacy leak. A non-empty batch must produce NO broadcast.
        CapturingWebSocket ws = new CapturingWebSocket();
        manager.setWebSocket(ws);

        encodeAndProcessOrderStatusBatch(1, System.currentTimeMillis(), 2);

        assertTrue("ORDER_STATUS_BATCH must not be broadcast to market WS clients",
            ws.messages.isEmpty());
    }

    // ==================== onNewLeader ====================

    @Test
    public void testOnNewLeader_NoCrash() {
        manager.onNewLeader(1, 5L);
        // No exception = pass — just logs
    }

    // ==================== Helper Methods ====================

    private long fp(double value) {
        return FixedPoint.fromDouble(value);
    }

    private void encodeAndProcessBookSnapshot(int marketId, long timestamp, long bidVersion, long askVersion,
                                               long[][] bids, long[][] asks) {
        encodeAndProcessBookSnapshot(marketId, timestamp, bidVersion, askVersion, 0L, bids, asks);
    }

    /**
     * Encode a BookSnapshot with an explicit bookVersion (the v4 monotonic version, match#95's
     * staleness guard). {@code bookVersion == 0} means "not present" (pre-v4 upstream) — the encoder
     * writes it explicitly so a reused buffer never leaks a value from a prior encode in the same test.
     */
    private void encodeAndProcessBookSnapshot(int marketId, long timestamp, long bidVersion, long askVersion,
                                               long bookVersion, long[][] bids, long[][] asks) {
        BookSnapshotEncoder encoder = new BookSnapshotEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(bidVersion);
        encoder.askVersion(askVersion);
        encoder.bookVersion(bookVersion);

        BookSnapshotEncoder.BidsEncoder bidsEnc = encoder.bidsCount(bids.length);
        for (long[] bid : bids) {
            bidsEnc.next().price(bid[0]).quantity(bid[1]).orderCount((int) bid[2]);
        }

        BookSnapshotEncoder.AsksEncoder asksEnc = encoder.asksCount(asks.length);
        for (long[] ask : asks) {
            asksEnc.next().price(ask[0]).quantity(ask[1]).orderCount((int) ask[2]);
        }

        BookSnapshotDecoder decoder = new BookSnapshotDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onBookSnapshot(decoder);
    }

    private void encodeAndProcessTradesBatch(int marketId, long timestamp, long[][] trades) {
        encodeAndProcessTradesBatch(marketId, timestamp, trades, null);
    }

    private void encodeAndProcessTradesBatch(int marketId, long timestamp, long[][] trades,
                                             OrderSide[] takerSides) {
        encodeAndProcessTradesBatchWithHeaderVersion(marketId, timestamp, trades, takerSides,
            TradesBatchEncoder.SCHEMA_VERSION);
    }

    /**
     * Encode a TradesBatch, optionally stamping the message header's version
     * to an older schema version to simulate a pre-v5 upstream, then decode
     * and process. {@code takerSides == null} encodes NULL_VAL (unknown).
     */
    private void encodeAndProcessTradesBatchWithHeaderVersion(int marketId, long timestamp,
                                                              long[][] trades, OrderSide[] takerSides,
                                                              int headerVersion) {
        TradesBatchEncoder encoder = new TradesBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);

        TradesBatchEncoder.TradesEncoder tradesEnc = encoder.tradesCount(trades.length);
        for (int i = 0; i < trades.length; i++) {
            long[] trade = trades[i];
            tradesEnc.next()
                .price(trade[0])
                .quantity(trade[1])
                .tradeCount((int) trade[2])
                .timestamp(trade[3])
                .takerSide(takerSides != null ? takerSides[i] : OrderSide.NULL_VAL);
        }

        if (headerVersion != TradesBatchEncoder.SCHEMA_VERSION) {
            headerEnc.wrap(buffer, 0).version(headerVersion);
        }

        TradesBatchDecoder decoder = new TradesBatchDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onTradesBatch(decoder);
    }

    private void encodeAndProcessBookDelta(int marketId, long timestamp, long bidVersion, long askVersion,
                                            Object[][] changes) {
        // BookDelta shares its field prefix (marketId/timestamp/bidVersion/askVersion/bookVersion)
        // with BookSnapshot, so the shared test buffer can carry a leftover bookVersion from a prior
        // encodeAndProcessBookSnapshot(..., bookVersion, ...) call into this one unless explicitly
        // zeroed here — this exercises the legacy (bookVersion absent) fallback path deliberately.
        encodeAndProcessBookDelta(marketId, timestamp, bidVersion, askVersion, 0L, 0L, changes);
    }

    /**
     * Encode a BookDelta with an explicit v4 chain (bookVersion + fromVersion). fromVersion names
     * the published book version this delta extends; a value != the gateway's current version is a
     * chain break (match#96). bookVersion == 0 (and fromVersion == 0) selects the legacy path.
     */
    private void encodeAndProcessBookDelta(int marketId, long timestamp, long bidVersion, long askVersion,
                                            long bookVersion, long fromVersion, Object[][] changes) {
        BookDeltaEncoder encoder = new BookDeltaEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(bidVersion);
        encoder.askVersion(askVersion);
        encoder.bookVersion(bookVersion);
        encoder.fromVersion(fromVersion);

        BookDeltaEncoder.ChangesEncoder changesEnc = encoder.changesCount(changes.length);
        for (Object[] change : changes) {
            changesEnc.next()
                .price((long) change[0])
                .quantity((long) change[1])
                .orderCount((int) change[2])
                .side((OrderSide) change[3])
                .updateType((BookUpdateType) change[4]);
        }

        BookDeltaDecoder decoder = new BookDeltaDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onBookDelta(decoder);
    }

    private void encodeAndProcessOrderStatusBatch(int marketId, long timestamp, int orderCount) {
        OrderStatusBatchEncoder encoder = new OrderStatusBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        OrderStatusBatchEncoder.OrdersEncoder ordersEnc = encoder.ordersCount(orderCount);
        for (int i = 0; i < orderCount; i++) {
            ordersEnc.next()
                .orderId(i + 1)
                .userId(100 + i)
                .status(OrderStatus.NEW)
                .price(fp(100.0 + i))
                .remainingQty(fp(1.0))
                .filledQty(0)
                .side(OrderSide.BID)
                .timestamp(timestamp)
                .omsOrderId(9000 + i)
                .statusSeq(i + 1);
        }

        OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onOrderStatusBatch(decoder);
    }

    /** Captures broadcasts without starting a server (channels stay null in the base class). */
    private static final class CapturingWebSocket
            extends com.match.infrastructure.websocket.MarketDataWebSocket {
        final java.util.List<String> messages = new java.util.ArrayList<>();

        @Override
        public void broadcastMarketData(String jsonMessage) {
            messages.add(jsonMessage);
        }
    }
}
