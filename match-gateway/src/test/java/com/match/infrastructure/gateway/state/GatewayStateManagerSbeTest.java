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
        assertTrue(json.contains("TRADES_HISTORY"));
        assertTrue(json.contains("\"count\":1"));
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

    // ==================== OrderStatusBatch (no-op) ====================

    @Test
    public void testOnOrderStatusBatch_NoCrash() {
        encodeAndProcessOrderStatusBatch(1, System.currentTimeMillis());
        // No exception = pass
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
        BookSnapshotEncoder encoder = new BookSnapshotEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(bidVersion);
        encoder.askVersion(askVersion);

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
        TradesBatchEncoder encoder = new TradesBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);

        TradesBatchEncoder.TradesEncoder tradesEnc = encoder.tradesCount(trades.length);
        for (long[] trade : trades) {
            tradesEnc.next()
                .price(trade[0])
                .quantity(trade[1])
                .tradeCount((int) trade[2])
                .timestamp(trade[3]);
        }

        TradesBatchDecoder decoder = new TradesBatchDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onTradesBatch(decoder);
    }

    private void encodeAndProcessBookDelta(int marketId, long timestamp, long bidVersion, long askVersion,
                                            Object[][] changes) {
        BookDeltaEncoder encoder = new BookDeltaEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(bidVersion);
        encoder.askVersion(askVersion);

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

    private void encodeAndProcessOrderStatusBatch(int marketId, long timestamp) {
        OrderStatusBatchEncoder encoder = new OrderStatusBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.ordersCount(0); // Empty batch

        OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        manager.onOrderStatusBatch(decoder);
    }
}
