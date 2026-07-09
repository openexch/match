// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.Logger;
import com.match.infrastructure.journal.generated.BooleanType;
import com.match.infrastructure.journal.generated.JournalTerminalEncoder;
import com.match.infrastructure.journal.generated.JournalTradeEncoder;
import com.match.infrastructure.journal.generated.MessageHeaderEncoder;
import com.match.infrastructure.journal.generated.TerminalStatus;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;

import java.nio.ByteBuffer;

/**
 * Service-thread half of the settlement journal: encodes every trade and every terminal
 * order status into an in-memory SPSC ring, drained by {@link JournalWriterAgent} into a
 * recorded (archived) publication.
 *
 * The journal is a money-loss surface: consumers (the Assets Engine settlement bridge)
 * release residual holds off it, and a missing entry is an unrecoverable lost settlement.
 * It is therefore LOSSLESS BY CONSTRUCTION: {@code append*} runs on the deterministic
 * cluster service thread on EVERY replica, before any of the sheddable egress machinery
 * (Disruptor ring, MarketPublisher buffers, omsEgressQueue), and BLOCKS (spins, loudly)
 * if this ring is full rather than shedding. A full ring means the local journal archive
 * has stalled; blocking degrades this node to a lagging replica while the other replicas'
 * journals continue — the correct trade for money integrity.
 *
 * Single-writer: all methods are called only from the cluster service thread. The counters
 * are volatile so a metrics/diagnostics thread can read them without tearing.
 */
public final class SettlementJournal {

    private static final Logger log = Logger.getLogger(SettlementJournal.class);

    /** Ring message type ids (journal-internal; the SBE header inside the payload is authoritative). */
    public static final int MSG_TYPE_TRADE = 1;
    public static final int MSG_TYPE_TERMINAL = 2;

    /** Spin count between escalating "journal stalled" logs while blocked on a full ring. */
    private static final long SPINS_PER_STALL_LOG = 50_000_000L;

    private final OneToOneRingBuffer ringBuffer;

    // Scratch encode buffer: max message = header(8) + JournalTrade block (85) < 128.
    private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(128));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final JournalTradeEncoder tradeEncoder = new JournalTradeEncoder();
    private final JournalTerminalEncoder terminalEncoder = new JournalTerminalEncoder();

    private volatile long appendedTrades;
    private volatile long appendedTerminals;
    /** Entries that hit a full ring at least once (backpressure EVENTS, not spin iterations). */
    private volatile long backpressureEvents;

    public SettlementJournal(final int ringCapacityBytes) {
        final ByteBuffer byteBuffer =
                ByteBuffer.allocateDirect(ringCapacityBytes + RingBufferDescriptor.TRAILER_LENGTH);
        this.ringBuffer = new OneToOneRingBuffer(new UnsafeBuffer(byteBuffer));
    }

    /** The ring the {@link JournalWriterAgent} drains. */
    public OneToOneRingBuffer ringBuffer() {
        return ringBuffer;
    }

    public void appendTrade(
            final long egressSeq,
            final long tradeId,
            final int marketId,
            final long takerOrderId,
            final long takerUserId,
            final long makerOrderId,
            final long makerUserId,
            final long price,
            final long quantity,
            final boolean takerIsBuy,
            final long timestamp) {

        tradeEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
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

        blockingWrite(MSG_TYPE_TRADE,
                MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength(), tradeId);
        appendedTrades = appendedTrades + 1;
    }

    public void appendTerminal(
            final long egressSeq,
            final long orderId,
            final long userId,
            final int marketId,
            final int orderStatus,
            final long timestamp) {

        terminalEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
                .egressSeq(egressSeq)
                .orderId(orderId)
                .userId(userId)
                .marketId(marketId)
                .status(TerminalStatus.get((short) orderStatus))
                .timestamp(timestamp);

        blockingWrite(MSG_TYPE_TERMINAL,
                MessageHeaderEncoder.ENCODED_LENGTH + terminalEncoder.encodedLength(), orderId);
        appendedTerminals = appendedTerminals + 1;
    }

    /**
     * Write to the ring; on a full ring, spin until space frees (block, never shed).
     * Escalates with an ERROR log periodically so a stalled journal archive is impossible
     * to miss, mirroring the existing RING BUFFER FULL idiom in MatchEventPublisher.
     */
    private void blockingWrite(final int msgTypeId, final int length, final long entryId) {
        if (ringBuffer.write(msgTypeId, scratch, 0, length)) {
            return;
        }
        backpressureEvents = backpressureEvents + 1;
        long spins = 0;
        while (!ringBuffer.write(msgTypeId, scratch, 0, length)) {
            spins++;
            if (spins % SPINS_PER_STALL_LOG == 0) {
                log.error("SETTLEMENT JOURNAL STALLED: ring full for " + spins
                        + " spins (msgType=" + msgTypeId + ", id=" + entryId
                        + ") — journal archive not draining; this replica is now lagging");
            }
            Thread.onSpinWait();
        }
    }

    public long appendedTrades() {
        return appendedTrades;
    }

    public long appendedTerminals() {
        return appendedTerminals;
    }

    public long backpressureEvents() {
        return backpressureEvents;
    }
}
