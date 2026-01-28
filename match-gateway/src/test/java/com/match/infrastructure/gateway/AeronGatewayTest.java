package com.match.infrastructure.gateway;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Tests for AeronGateway that don't require a running cluster.
 * Tests constructor, accessors, SBE message dispatch, and session events.
 */
public class AeronGatewayTest {

    // ==================== Constructor and Accessors ====================

    @Test
    public void testConstructor_DoesNotConnect() {
        AeronGateway gateway = new AeronGateway();
        assertFalse(gateway.isConnected());
        gateway.close();
    }

    @Test
    public void testSubmitOrder_EnqueuesSuccessfully() {
        AeronGateway gateway = new AeronGateway();
        org.agrona.concurrent.UnsafeBuffer buf = new org.agrona.concurrent.UnsafeBuffer(new byte[64]);
        // submitOrder should enqueue even without cluster connection
        boolean result = gateway.submitOrder(buf, 0, 50);
        assertTrue(result);
        gateway.close();
    }

    @Test
    public void testSubmitOrder_RejectsTooLargeMessage() {
        AeronGateway gateway = new AeronGateway();
        org.agrona.concurrent.UnsafeBuffer buf = new org.agrona.concurrent.UnsafeBuffer(new byte[256]);
        // Messages larger than ORDER_MSG_BUFFER_SIZE (128) should be rejected
        boolean result = gateway.submitOrder(buf, 0, 200);
        assertFalse(result);
        gateway.close();
    }

    @Test
    public void testIsConnected_FalseInitially() {
        AeronGateway gateway = new AeronGateway();
        assertFalse(gateway.isConnected());
        gateway.close();
    }

    // ==================== Offer without connection ====================

    @Test(expected = IllegalStateException.class)
    public void testOffer_ThrowsWhenNotConnected() {
        AeronGateway gateway = new AeronGateway();
        try {
            UnsafeBuffer buf = new UnsafeBuffer(new byte[64]);
            gateway.offer(buf, 0, 64);
        } finally {
            gateway.close();
        }
    }

    // ==================== Close ====================

    @Test
    public void testClose_MultipleTimes_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.close();
        gateway.close(); // Should not crash
    }

    @Test
    public void testClose_BeforeConnect() {
        AeronGateway gateway = new AeronGateway();
        gateway.close(); // Never connected — should be fine
    }

    // ==================== onMessage dispatch ====================

    @Test
    public void testOnMessage_BookSnapshot_DispatchesToListener() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        UnsafeBuffer buffer = encodeBookSnapshot(1, 12345L);
        int length = getEncodedLength(buffer, BookSnapshotEncoder.BLOCK_LENGTH, 0, 0);

        gateway.onMessage(0L, 0L, buffer, 0, length, null);

        assertEquals(1, listener.bookSnapshots.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_TradesBatch_DispatchesToListener() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        UnsafeBuffer buffer = encodeTradesBatch(1, 12345L);
        int length = getTradesEncodedLength(buffer);

        gateway.onMessage(0L, 0L, buffer, 0, length, null);

        assertEquals(1, listener.tradesBatches.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_BookDelta_DispatchesToListener() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        UnsafeBuffer buffer = encodeBookDelta(1, 12345L);
        int length = getDeltaEncodedLength(buffer);

        gateway.onMessage(0L, 0L, buffer, 0, length, null);

        assertEquals(1, listener.bookDeltas.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_OrderStatusBatch_DispatchesToListener() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        UnsafeBuffer buffer = encodeOrderStatusBatch(1, 12345L);
        int length = getOrderStatusEncodedLength(buffer);

        gateway.onMessage(0L, 0L, buffer, 0, length, null);

        assertEquals(1, listener.orderStatusBatches.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_UnknownTemplateId_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        // Encode a header with unknown template ID
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        headerEnc.wrap(buffer, 0)
            .blockLength(10)
            .templateId(9999)
            .schemaId(1)
            .version(2);

        gateway.onMessage(0L, 0L, buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + 10, null);

        assertEquals(0, listener.bookSnapshots.get());
        assertEquals(0, listener.tradesBatches.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_TooShortBuffer_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        UnsafeBuffer buffer = new UnsafeBuffer(new byte[4]); // Too short for SBE header

        gateway.onMessage(0L, 0L, buffer, 0, 4, null);

        assertEquals(0, listener.bookSnapshots.get());
        gateway.close();
    }

    @Test
    public void testOnMessage_NoListener_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        // Don't set listener

        UnsafeBuffer buffer = encodeBookSnapshot(1, 12345L);
        int length = getEncodedLength(buffer, BookSnapshotEncoder.BLOCK_LENGTH, 0, 0);

        gateway.onMessage(0L, 0L, buffer, 0, length, null);
        // No crash = pass
        gateway.close();
    }

    // ==================== onSessionEvent ====================

    @Test
    public void testOnSessionEvent_OK_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.onSessionEvent(0L, 0L, 1L, 0,
            io.aeron.cluster.codecs.EventCode.OK, "Connected");
        gateway.close();
    }

    @Test
    public void testOnSessionEvent_ERROR_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.onSessionEvent(0L, 0L, 1L, 0,
            io.aeron.cluster.codecs.EventCode.ERROR, "Session error");
        gateway.close();
    }

    @Test
    public void testOnSessionEvent_CLOSED_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.onSessionEvent(0L, 0L, 1L, 0,
            io.aeron.cluster.codecs.EventCode.CLOSED, "Session closed");
        gateway.close();
    }

    @Test
    public void testOnSessionEvent_WithClusterStatus() {
        AeronGateway gateway = new AeronGateway();
        com.match.infrastructure.admin.ClusterStatus status = new com.match.infrastructure.admin.ClusterStatus();
        gateway.setClusterStatus(status);

        gateway.onSessionEvent(0L, 0L, 1L, 0,
            io.aeron.cluster.codecs.EventCode.ERROR, "test error");
        assertFalse(status.isGatewayConnected());
        gateway.close();
    }

    // ==================== onNewLeader ====================

    @Test
    public void testOnNewLeader_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.onNewLeader(0L, 2L, 1, "localhost:9000");
        gateway.close();
    }

    @Test
    public void testOnNewLeader_UpdatesClusterStatus() {
        AeronGateway gateway = new AeronGateway();
        com.match.infrastructure.admin.ClusterStatus status = new com.match.infrastructure.admin.ClusterStatus();
        gateway.setClusterStatus(status);

        gateway.onNewLeader(0L, 5L, 2, "localhost:9000");

        assertEquals(2, status.getLeaderId());
        assertEquals(5L, status.getLeadershipTermId());
        gateway.close();
    }

    @Test
    public void testOnNewLeader_NotifiesListener() {
        AeronGateway gateway = new AeronGateway();
        TestListener listener = new TestListener();
        gateway.setEgressListener(listener);

        gateway.onNewLeader(0L, 3L, 1, "localhost:9000");

        assertEquals(1, listener.newLeaders.get());
        assertEquals(1, listener.lastLeaderId);
        assertEquals(3L, listener.lastLeadershipTermId);
        gateway.close();
    }

    @Test
    public void testOnNewLeader_NoListenerOrStatus_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        // Don't set listener or clusterStatus
        gateway.onNewLeader(0L, 1L, 0, "localhost:9000");
        // No crash = pass
        gateway.close();
    }

    // ==================== setEgressListener / setClusterStatus ====================

    @Test
    public void testSetEgressListener_Null_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.setEgressListener(null);
        gateway.close();
    }

    @Test
    public void testSetClusterStatus_Null_NoCrash() {
        AeronGateway gateway = new AeronGateway();
        gateway.setClusterStatus(null);
        gateway.close();
    }

    // ==================== Encoding Helpers ====================

    private UnsafeBuffer encodeBookSnapshot(int marketId, long timestamp) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        BookSnapshotEncoder encoder = new BookSnapshotEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(1);
        encoder.askVersion(1);
        encoder.bidsCount(0);
        encoder.asksCount(0);
        return buffer;
    }

    private UnsafeBuffer encodeTradesBatch(int marketId, long timestamp) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        TradesBatchEncoder encoder = new TradesBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.tradesCount(0);
        return buffer;
    }

    private UnsafeBuffer encodeBookDelta(int marketId, long timestamp) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        BookDeltaEncoder encoder = new BookDeltaEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.bidVersion(1);
        encoder.askVersion(1);
        encoder.changesCount(0);
        return buffer;
    }

    private UnsafeBuffer encodeOrderStatusBatch(int marketId, long timestamp) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        MessageHeaderEncoder headerEnc = new MessageHeaderEncoder();
        OrderStatusBatchEncoder encoder = new OrderStatusBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(timestamp);
        encoder.ordersCount(0);
        return buffer;
    }

    private int getEncodedLength(UnsafeBuffer buffer, int blockLength, int bidsCount, int asksCount) {
        // Header (8) + block (28) + bids group header (4) + bids data + asks group header (4) + asks data
        return MessageHeaderEncoder.ENCODED_LENGTH + BookSnapshotEncoder.BLOCK_LENGTH +
               4 + (bidsCount * 20) + 4 + (asksCount * 20);
    }

    private int getTradesEncodedLength(UnsafeBuffer buffer) {
        return MessageHeaderEncoder.ENCODED_LENGTH + TradesBatchEncoder.BLOCK_LENGTH + 4; // group header
    }

    private int getDeltaEncodedLength(UnsafeBuffer buffer) {
        return MessageHeaderEncoder.ENCODED_LENGTH + BookDeltaEncoder.BLOCK_LENGTH + 4; // group header
    }

    private int getOrderStatusEncodedLength(UnsafeBuffer buffer) {
        return MessageHeaderEncoder.ENCODED_LENGTH + OrderStatusBatchEncoder.BLOCK_LENGTH + 4; // group header
    }

    // ==================== Test Listener ====================

    private static class TestListener implements AeronGateway.EgressMessageListener {
        final AtomicInteger bookSnapshots = new AtomicInteger(0);
        final AtomicInteger bookDeltas = new AtomicInteger(0);
        final AtomicInteger tradesBatches = new AtomicInteger(0);
        final AtomicInteger orderStatusBatches = new AtomicInteger(0);
        final AtomicInteger newLeaders = new AtomicInteger(0);
        volatile int lastLeaderId;
        volatile long lastLeadershipTermId;

        @Override
        public void onBookSnapshot(BookSnapshotDecoder decoder) {
            bookSnapshots.incrementAndGet();
        }

        @Override
        public void onBookDelta(BookDeltaDecoder decoder) {
            bookDeltas.incrementAndGet();
        }

        @Override
        public void onTradesBatch(TradesBatchDecoder decoder) {
            tradesBatches.incrementAndGet();
        }

        @Override
        public void onOrderStatusBatch(OrderStatusBatchDecoder decoder) {
            orderStatusBatches.incrementAndGet();
        }

        @Override
        public void onNewLeader(int leaderMemberId, long leadershipTermId) {
            newLeaders.incrementAndGet();
            lastLeaderId = leaderMemberId;
            lastLeadershipTermId = leadershipTermId;
        }
    }
}
