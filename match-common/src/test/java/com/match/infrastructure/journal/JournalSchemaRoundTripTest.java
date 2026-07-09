// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.journal.generated.BooleanType;
import com.match.infrastructure.journal.generated.JournalTerminalDecoder;
import com.match.infrastructure.journal.generated.JournalTerminalEncoder;
import com.match.infrastructure.journal.generated.JournalTradeDecoder;
import com.match.infrastructure.journal.generated.JournalTradeEncoder;
import com.match.infrastructure.journal.generated.MessageHeaderDecoder;
import com.match.infrastructure.journal.generated.MessageHeaderEncoder;
import com.match.infrastructure.journal.generated.TerminalStatus;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

/**
 * Wire round-trip test for the settlement-journal SBE schema (id=3, package
 * {@code com.match.infrastructure.journal.generated}).
 *
 * <p>This task is ONLY the wire schema + codegen + constants; the journal writer lands
 * separately. These tests just prove the generated codecs encode/decode every field
 * faithfully, including a large egressSeq (the log-position order key) and both boolean
 * values of takerIsBuy, using the real MessageHeaderEncoder/Decoder pair.</p>
 */
public class JournalSchemaRoundTripTest {

    @Test
    public void journalTradeRoundTripsTakerBuy() {
        assertTradeRoundTrips(true);
    }

    @Test
    public void journalTradeRoundTripsTakerSell() {
        assertTradeRoundTrips(false);
    }

    private static void assertTradeRoundTrips(boolean takerIsBuy) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        JournalTradeEncoder encoder = new JournalTradeEncoder();

        final long egressSeq = 9_223_372_036_854_770L; // realistic large log position, near Long.MAX_VALUE
        final long tradeId = 555_444_333L;
        final int marketId = 7;
        final long takerOrderId = 111_222L;
        final long takerUserId = 42L;
        final long makerOrderId = 333_444L;
        final long makerUserId = 84L;
        final long price = 6_012_345_000L;
        final long quantity = 25_000_000L;
        final long timestamp = 1_752_100_000_123L;

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .egressSeq(egressSeq)
                .tradeId(tradeId)
                .marketId(marketId)
                .takerOrderId(takerOrderId)
                .takerUserId(takerUserId)
                .makerOrderId(makerOrderId)
                .makerUserId(makerUserId)
                .price(price)
                .quantity(quantity)
                .takerIsBuy(takerIsBuy ? BooleanType.TRUE : BooleanType.FALSE)
                .timestamp(timestamp);

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(JournalTradeDecoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(JournalTradeDecoder.SCHEMA_ID, headerDecoder.schemaId());

        JournalTradeDecoder decoder = new JournalTradeDecoder().wrap(
                buffer, headerDecoder.encodedLength(), headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(egressSeq, decoder.egressSeq());
        assertEquals(tradeId, decoder.tradeId());
        assertEquals(marketId, decoder.marketId());
        assertEquals(takerOrderId, decoder.takerOrderId());
        assertEquals(takerUserId, decoder.takerUserId());
        assertEquals(makerOrderId, decoder.makerOrderId());
        assertEquals(makerUserId, decoder.makerUserId());
        assertEquals(price, decoder.price());
        assertEquals(quantity, decoder.quantity());
        assertEquals(takerIsBuy ? BooleanType.TRUE : BooleanType.FALSE, decoder.takerIsBuy());
        assertEquals(timestamp, decoder.timestamp());
    }

    @Test
    public void journalTerminalRoundTrips() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        JournalTerminalEncoder encoder = new JournalTerminalEncoder();

        final long egressSeq = 9_223_372_036_854_770L; // same realistic large log position as the trade test
        final long orderId = 987_654_321L;
        final long userId = 17L;
        final int marketId = 3;
        final long timestamp = 1_752_100_000_456L;

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .egressSeq(egressSeq)
                .orderId(orderId)
                .userId(userId)
                .marketId(marketId)
                .status(TerminalStatus.FILLED)
                .timestamp(timestamp);

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(JournalTerminalDecoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(JournalTerminalDecoder.SCHEMA_ID, headerDecoder.schemaId());

        JournalTerminalDecoder decoder = new JournalTerminalDecoder().wrap(
                buffer, headerDecoder.encodedLength(), headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(egressSeq, decoder.egressSeq());
        assertEquals(orderId, decoder.orderId());
        assertEquals(userId, decoder.userId());
        assertEquals(marketId, decoder.marketId());
        assertEquals(TerminalStatus.FILLED, decoder.status());
        assertEquals(timestamp, decoder.timestamp());
    }

    @Test
    public void journalTerminalRoundTripsCancelledAndRejected() {
        assertTerminalStatusRoundTrips(TerminalStatus.CANCELLED);
        assertTerminalStatusRoundTrips(TerminalStatus.REJECTED);
    }

    private static void assertTerminalStatusRoundTrips(TerminalStatus status) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        JournalTerminalEncoder encoder = new JournalTerminalEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .egressSeq(1L)
                .orderId(2L)
                .userId(3L)
                .marketId(4)
                .status(status)
                .timestamp(5L);

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder().wrap(buffer, 0);
        JournalTerminalDecoder decoder = new JournalTerminalDecoder().wrap(
                buffer, headerDecoder.encodedLength(), headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(status, decoder.status());
    }
}
