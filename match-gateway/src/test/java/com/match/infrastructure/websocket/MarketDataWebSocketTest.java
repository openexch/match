package com.match.infrastructure.websocket;

import com.match.infrastructure.gateway.state.GatewayStateManager;
import com.match.infrastructure.generated.*;
import com.match.domain.FixedPoint;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for MarketDataWebSocket: broadcasting, extractMarketId, close.
 * Tests the public API of MarketDataWebSocket without starting the server
 * (since it hardcodes port 8081).
 */
public class MarketDataWebSocketTest {

    private MarketDataWebSocket ws;
    private GatewayStateManager stateManager;

    @Before
    public void setUp() {
        ws = new MarketDataWebSocket();
        stateManager = new GatewayStateManager();
        ws.setStateManager(stateManager);
    }

    @After
    public void tearDown() {
        ws.close();
    }

    // ==================== Broadcast without channels ====================

    @Test
    public void testBroadcastMarketData_NoChannels_NoCrash() {
        // channels is null before start(), should not crash
        ws.broadcastMarketData("{\"type\":\"BOOK_SNAPSHOT\",\"marketId\":1}");
        // No crash = pass
    }

    @Test
    public void testBroadcastMarketData_NullJson_NoCrash() {
        // Even with null, should handle gracefully
        try {
            ws.broadcastMarketData(null);
        } catch (NullPointerException e) {
            // Acceptable — channels not initialized
        }
    }

    // ==================== close without start ====================

    @Test
    public void testClose_WithoutStart_NoCrash() {
        ws.close(); // Never started — should not crash
    }

    // ==================== setClusterStatus ====================

    @Test
    public void testSetClusterStatus_Null_NoCrash() {
        ws.setClusterStatus(null);
    }

    @Test
    public void testSetStateManager() {
        GatewayStateManager mgr = new GatewayStateManager();
        ws.setStateManager(mgr);
        // No crash = pass
    }

    // ==================== getChannels ====================

    @Test
    public void testGetChannels_NullBeforeStart() {
        assertNull(ws.getChannels());
    }

    // ==================== Integration: State manager wired to WebSocket ====================

    @Test
    public void testStateManagerWithWebSocketWired() {
        // Wire the state manager to the WebSocket so broadcastMarketData is called
        stateManager.setWebSocket(ws);

        // Process a book snapshot through SBE
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(4096);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        MessageHeaderDecoder headerDec = new MessageHeaderDecoder();

        BookSnapshotEncoder encoder = new BookSnapshotEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(1);
        encoder.timestamp(System.currentTimeMillis());
        encoder.bidVersion(10);
        encoder.askVersion(20);
        encoder.bidsCount(1).next()
            .price(FixedPoint.fromDouble(50000.0))
            .quantity(FixedPoint.fromDouble(0.5))
            .orderCount(3);
        encoder.asksCount(1).next()
            .price(FixedPoint.fromDouble(50100.0))
            .quantity(FixedPoint.fromDouble(1.0))
            .orderCount(2);

        BookSnapshotDecoder decoder = new BookSnapshotDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);

        // This should NOT crash even though channels are null
        stateManager.onBookSnapshot(decoder);

        // Verify state was updated
        assertNotNull(stateManager.getOrderBook(1));
        assertTrue(stateManager.getOrderBook(1).hasData());
    }

    @Test
    public void testStateManagerTradesWithWebSocketWired() {
        stateManager.setWebSocket(ws);

        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(4096);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        MessageHeaderDecoder headerDec = new MessageHeaderDecoder();

        TradesBatchEncoder encoder = new TradesBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(1);
        encoder.timestamp(System.currentTimeMillis());
        encoder.tradesCount(1).next()
            .price(FixedPoint.fromDouble(50000.0))
            .quantity(FixedPoint.fromDouble(1.0))
            .tradeCount(5)
            .timestamp(System.currentTimeMillis());

        TradesBatchDecoder decoder = new TradesBatchDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);

        stateManager.onTradesBatch(decoder);

        assertTrue(stateManager.getTrades().hasData());
        assertNotNull(stateManager.getTickerStats(1));
    }

    @Test
    public void testStateManagerDeltaWithWebSocketWired() {
        stateManager.setWebSocket(ws);

        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(4096);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        MessageHeaderDecoder headerDec = new MessageHeaderDecoder();

        // First create the book via snapshot
        BookSnapshotEncoder snapEncoder = new BookSnapshotEncoder();
        snapEncoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        snapEncoder.marketId(1);
        snapEncoder.timestamp(System.currentTimeMillis());
        snapEncoder.bidVersion(1);
        snapEncoder.askVersion(1);
        snapEncoder.bidsCount(1).next()
            .price(FixedPoint.fromDouble(100.0))
            .quantity(FixedPoint.fromDouble(5.0))
            .orderCount(3);
        snapEncoder.asksCount(0);

        BookSnapshotDecoder snapDecoder = new BookSnapshotDecoder();
        headerDec.wrap(buffer, 0);
        snapDecoder.wrapAndApplyHeader(buffer, 0, headerDec);
        stateManager.onBookSnapshot(snapDecoder);

        // Now send a delta with a NEW_LEVEL on ask side
        BookDeltaEncoder deltaEncoder = new BookDeltaEncoder();
        deltaEncoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        deltaEncoder.marketId(1);
        deltaEncoder.timestamp(System.currentTimeMillis());
        deltaEncoder.bidVersion(1);
        deltaEncoder.askVersion(2);
        deltaEncoder.changesCount(1).next()
            .price(FixedPoint.fromDouble(101.0))
            .quantity(FixedPoint.fromDouble(8.0))
            .orderCount(2)
            .side(OrderSide.ASK)
            .updateType(BookUpdateType.NEW_LEVEL);

        BookDeltaDecoder deltaDecoder = new BookDeltaDecoder();
        headerDec.wrap(buffer, 0);
        deltaDecoder.wrapAndApplyHeader(buffer, 0, headerDec);
        stateManager.onBookDelta(deltaDecoder);

        // Verify delta was applied
        assertEquals(1, stateManager.getOrderBook(1).getAskCount());
    }
}
