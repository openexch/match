// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.application.orderbook.OrderRejectReason;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.OrderStatusType;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.OrderStatus;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;
import com.match.infrastructure.generated.OrderStatusBatchEncoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Wire test for the SBE v6 reject-reason tail on {@code OrderStatusBatch} (match#75).
 *
 * <p>Proves the reason a MarketPublisher buffers survives the real SBE encode/decode, and that the
 * tail extension is mixed-version safe both ways: an old (v5-acting) reader on a v6 stream skips the
 * tail cleanly and still reads every field it knows, and a new (v6) reader on a genuine v5 stream
 * reads the field as the {@code nullValue} 255 = "unknown". Mirrors the takerSide (v5) round-trip
 * pattern in {@link MarketPublisherTradeBatchTest}.</p>
 */
public class MarketPublisherOrderStatusReasonTest {

    /** Feed three order statuses (two rejects with reasons + one plain NEW) through one flush window. */
    private static byte[] encodeThreeStatuses(int rejectReason1, int rejectReason2) {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        OrderStatusBatchCapturingBroadcaster bc = new OrderStatusBatchCapturingBroadcaster();
        pub.setBroadcaster(bc);

        feed(pub, statusEvent(11L, OrderStatusType.REJECTED, rejectReason1));
        feed(pub, statusEvent(22L, OrderStatusType.REJECTED, rejectReason2));
        feed(pub, statusEvent(33L, OrderStatusType.NEW, OrderRejectReason.NONE));

        pub.onShutdown(); // single final flush

        assertEquals("one OrderStatusBatch expected", 1, bc.batches.size());
        return bc.batches.get(0);
    }

    @Test
    public void v6WriterV6ReaderReasonSurvives() {
        byte[] bytes = encodeThreeStatuses(OrderRejectReason.PRICE_OFF_TICK, OrderRejectReason.WOULD_CROSS);
        List<DecodedStatus> out = decode(bytes);

        assertEquals(3, out.size());
        assertEquals(OrderStatus.REJECTED, out.get(0).status);
        assertEquals(OrderRejectReason.PRICE_OFF_TICK, out.get(0).rejectReason);
        assertEquals(OrderStatus.REJECTED, out.get(1).status);
        assertEquals(OrderRejectReason.WOULD_CROSS, out.get(1).rejectReason);
        assertEquals(OrderStatus.NEW, out.get(2).status);
        assertEquals("non-reject carries NONE (0), never the null value",
                OrderRejectReason.NONE, out.get(2).rejectReason);
    }

    @Test
    public void v6WriterV5ActingReaderSkipsTailCleanly() {
        // An OMS still on schema v5 believes the stream is v5. It must read every v5 field of every
        // entry correctly (striding by the on-wire group blockLength, which includes the tail byte)
        // and simply not see the reason. Simulate by rewriting only the message header version to 5.
        byte[] bytes = encodeThreeStatuses(OrderRejectReason.PRICE_OFF_TICK, OrderRejectReason.WOULD_CROSS);
        UnsafeBuffer buf = new UnsafeBuffer(bytes);
        new MessageHeaderEncoder().wrap(buf, 0).version(5);

        List<DecodedStatus> out = decode(bytes);
        assertEquals("all three entries still decode when striding a v6 group as a v5 reader", 3, out.size());
        // statusSeq is the last v5 field; if striding were wrong these would be garbage.
        assertEquals(1L, out.get(0).statusSeq);
        assertEquals(2L, out.get(1).statusSeq);
        assertEquals(3L, out.get(2).statusSeq);
        assertEquals(11L, out.get(0).orderId);
        assertEquals(22L, out.get(1).orderId);
        assertEquals(33L, out.get(2).orderId);
        // A v6-capable decoder at acting version 5 yields the null value for the tail field.
        for (DecodedStatus d : out) {
            assertEquals("acting version 5 must yield the null value (255) for rejectReason",
                    255, d.rejectReason);
        }
    }

    @Test
    public void v5WriterV6ReaderReadsNullValue() {
        // A genuine v5 writer would emit a group whose on-wire blockLength excludes the reason byte,
        // with header version 5. Simulate faithfully for a single entry: encode v6, then shrink the
        // group blockLength by one and set the header version to 5. A v6 reader must then read the
        // reason as the nullValue 255 ("unknown / upstream too old to say") while every other field
        // parses.
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        OrderStatusBatchCapturingBroadcaster bc = new OrderStatusBatchCapturingBroadcaster();
        pub.setBroadcaster(bc);
        feed(pub, statusEvent(77L, OrderStatusType.REJECTED, OrderRejectReason.PRICE_OFF_TICK));
        pub.onShutdown();
        assertEquals(1, bc.batches.size());

        UnsafeBuffer buf = new UnsafeBuffer(bc.batches.get(0));
        // Group dimension (groupSizeEncoding) sits right after the message block.
        final int groupDimOffset = MessageHeaderEncoder.ENCODED_LENGTH + OrderStatusBatchEncoder.BLOCK_LENGTH;
        int v6BlockLength = buf.getShort(groupDimOffset, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
        // Shrink to the v5 group block length (drop the 1-byte reason tail) and mark the stream v5.
        buf.putShort(groupDimOffset, (short) (v6BlockLength - 1), ByteOrder.LITTLE_ENDIAN);
        new MessageHeaderEncoder().wrap(buf, 0).version(5);

        List<DecodedStatus> out = decode(bc.batches.get(0));
        assertEquals(1, out.size());
        assertEquals("v5 stream: every real field still parses", 77L, out.get(0).orderId);
        assertEquals(OrderStatus.REJECTED, out.get(0).status);
        assertEquals(1L, out.get(0).statusSeq);
        assertEquals("v6 reader on a v5 stream sees nullValue 255 (unknown), never a fabricated code",
                255, out.get(0).rejectReason);
    }

    // ==================== helpers ====================

    /** Deliver one event to the publisher's buffering path; onEvent declares checked Exception. */
    private static void feed(MarketPublisher pub, PublishEvent e) {
        try {
            pub.onEvent(e, 0L, true);
        } catch (Exception ex) {
            throw new AssertionError("onEvent threw", ex);
        }
    }

    private static PublishEvent statusEvent(long orderId, int status, int rejectReason) {
        PublishEvent e = new PublishEvent();
        e.setOrderStatusUpdate(1, 1000L, orderId, 200L, status,
                FixedPoint.fromDouble(1.0), 0L, FixedPoint.fromDouble(60_000.0), true, 0L, rejectReason);
        return e;
    }

    private static final class DecodedStatus {
        long orderId;
        OrderStatus status;
        long statusSeq;
        int rejectReason;
    }

    private static List<DecodedStatus> decode(byte[] bytes) {
        UnsafeBuffer buf = new UnsafeBuffer(bytes);
        MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(OrderStatusBatchDecoder.TEMPLATE_ID, header.templateId());
        OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder()
                .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
        List<DecodedStatus> out = new ArrayList<>();
        for (OrderStatusBatchDecoder.OrdersDecoder o : decoder.orders()) {
            DecodedStatus d = new DecodedStatus();
            d.orderId = o.orderId();
            d.status = o.status();
            d.statusSeq = o.statusSeq();
            d.rejectReason = o.rejectReason() & 0xFF;
            out.add(d);
        }
        return out;
    }

    /** Captures the reliable (OMS-bound) OrderStatusBatch broadcasts as byte copies. */
    private static final class OrderStatusBatchCapturingBroadcaster implements MarketDataBroadcaster {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final List<byte[]> batches = new ArrayList<>();

        @Override
        public boolean hasSubscribers() {
            return true;
        }

        @Override
        public void broadcast(DirectBuffer buffer, int offset, int length) {
            // Non-reliable market-data path (book snapshot / aggregated trades) — not under test.
        }

        @Override
        public void broadcastReliable(DirectBuffer buffer, int offset, int length) {
            header.wrap(buffer, offset);
            if (header.templateId() == OrderStatusBatchDecoder.TEMPLATE_ID) {
                byte[] copy = new byte[length]; // publisher reuses the encode buffer
                buffer.getBytes(offset, copy);
                batches.add(copy);
            }
        }
    }

    // Sanity: at least one broadcast happened (guards a silently-empty flush from passing vacuously).
    @Test
    public void flushProducesExactlyOneBatch() {
        byte[] bytes = encodeThreeStatuses(OrderRejectReason.NO_LIQUIDITY, OrderRejectReason.ORDER_NOT_FOUND);
        assertTrue("batch should be non-empty", bytes.length > MessageHeaderDecoder.ENCODED_LENGTH);
    }
}
