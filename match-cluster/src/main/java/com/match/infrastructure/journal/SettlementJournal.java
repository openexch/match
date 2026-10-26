// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.application.engine.MeIdentity;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import com.match.infrastructure.Logger;
import com.match.infrastructure.mejournal.generated.BooleanType;
import com.match.infrastructure.mejournal.generated.CommandOutcomeEncoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseEncoder;
import com.match.infrastructure.mejournal.generated.ConfigDecision;
import com.match.infrastructure.mejournal.generated.EngineConfigEncoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderEncoder;
import com.match.infrastructure.mejournal.generated.ReleaseResult;
import com.match.infrastructure.mejournal.generated.ReleaseScope;
import com.match.infrastructure.mejournal.generated.TerminalEncoder;
import com.match.infrastructure.mejournal.generated.TerminalStatus;
import com.match.infrastructure.mejournal.generated.TradeEncoder;
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
 * Wire: ME journal schemaId 4 version 1 ({@code me-journal-schema.xml}, RC2 S1 contract
 * section 2). Every record is encoded whole into {@link #scratch} and offered as ONE ring
 * message, which the writer agent offers as ONE unfragmented frame; the largest legal frame is
 * {@link #MAX_FRAME_LENGTH} (a 48-entry {@code CommandRelease}), below the 1376 B payload of the
 * {@code aeron:ipc} default MTU. Schema 3 and the schema 1 template 28 are never written again.
 * {@code meEpoch} is read on every append from the bound {@link MeIdentity} (RC2 S2 section 5):
 * 0 until a FRESH at behaviour >= 2 binds it, then the recorded genesisId. {@code journalSeq} is
 * the ENGINE's allocation on every record (RC2 S3): one dense sequence from FRESH = 1 under
 * behaviour 2, 0 under behaviour 1; this class never allocates or checks it.
 *
 * Single-writer: all methods are called only from the cluster service thread. The counters
 * are volatile so a metrics/diagnostics thread can read them without tearing.
 */
public final class SettlementJournal {

    private static final Logger log = Logger.getLogger(SettlementJournal.class);

    /** Ring message type ids (journal-internal; the SBE header inside the payload is authoritative). */
    public static final int MSG_TYPE_TRADE = 1;
    public static final int MSG_TYPE_TERMINAL = 2;
    public static final int MSG_TYPE_OUTCOME = 3;
    public static final int MSG_TYPE_RELEASE = 4;
    public static final int MSG_TYPE_CONFIG = 5;

    /** Entry cap of a {@code CommandRelease} group (contract section 2, decision 10.6). */
    public static final int MAX_RELEASE_ENTRIES = 48;

    /** Largest legal journal frame: header 8 + root 44 + group header 4 + 48 x 22 = 1112 B. */
    public static final int MAX_FRAME_LENGTH = MessageHeaderEncoder.ENCODED_LENGTH
            + CommandReleaseEncoder.BLOCK_LENGTH + 4
            + MAX_RELEASE_ENTRIES * CommandReleaseEncoder.ReleasesEncoder.sbeBlockLength();

    /**
     * Record 3 {@code ledgerCapacity}: the cap the behaviour-1 ledger actually enforces. Stays
     * 100000 in S2 (contract section 5); S4 switches it to the recorded capacity.
     */
    static final int S1_LEDGER_CAPACITY = 100_000;

    /**
     * RC2 S2: the engine-owned identity (one reference, never a copy, so a later bind is seen).
     * Defaults to an unbound identity for a journal that is never bound (meEpoch 0, behaviour 1).
     * RC2 S3 (S2 review NOTE-2): a plain field, bound once at bootstrap before the cluster
     * service thread starts (thread start publishes it); read on the service thread.
     */
    private MeIdentity identity = new MeIdentity();

    /** Spin count between escalating "journal stalled" logs while blocked on a full ring. */
    private static final long SPINS_PER_STALL_LOG = 50_000_000L;

    private final OneToOneRingBuffer ringBuffer;

    // Scratch encode buffer: max message = MAX_FRAME_LENGTH (1112 B) < 2048.
    private final UnsafeBuffer scratch = new UnsafeBuffer(ByteBuffer.allocateDirect(2048));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final TerminalEncoder terminalEncoder = new TerminalEncoder();
    private final CommandOutcomeEncoder outcomeEncoder = new CommandOutcomeEncoder();
    private final CommandReleaseEncoder releaseEncoder = new CommandReleaseEncoder();
    private final EngineConfigEncoder configEncoder = new EngineConfigEncoder();

    private volatile long appendedTrades;
    private volatile long appendedTerminals;
    /** Entries that hit a full ring at least once (backpressure EVENTS, not spin iterations). */
    private volatile long backpressureEvents;

    public SettlementJournal(final int ringCapacityBytes) {
        final ByteBuffer byteBuffer =
                ByteBuffer.allocateDirect(ringCapacityBytes + RingBufferDescriptor.TRAILER_LENGTH);
        this.ringBuffer = new OneToOneRingBuffer(new UnsafeBuffer(byteBuffer));
    }

    /**
     * RC2 S2: bind the engine's {@link MeIdentity}; every later append stamps its meEpoch (and
     * record 3 its behaviour). Called once at bootstrap (AppClusteredService.setSettlementJournal).
     */
    public void bindIdentity(final MeIdentity engineIdentity) {
        if (engineIdentity == null) {
            throw new IllegalArgumentException("MeIdentity must not be null");
        }
        this.identity = engineIdentity;
    }

    /** The ring the {@link JournalWriterAgent} drains. */
    public OneToOneRingBuffer ringBuffer() {
        return ringBuffer;
    }

    /**
     * Record 3 ({@code CommandOutcome}): the 20 template-28 fields of the outcome, then the tail.
     * {@code timeInForce} and {@code expectedOldRemaining} come from the outcome's intent (0/0 for
     * a v11 frame); {@code ledgerSizeAfter} is the command ledger size after this outcome is
     * recorded (the caller knows whether it will be).
     */
    public void appendCommandOutcome(final long journalSeq, final long egressSeq, final DurableCommandOutcome o,
                                     final int ledgerSizeAfter) {
        final DurableOrderIntent c = o.intent();
        final MeIdentity id = identity;
        outcomeEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
                .journalSeq(journalSeq)
                .meEpoch(id.meEpoch())
                .egressSeq(egressSeq)
                .commandIdHigh(c.idHigh())
                .commandIdLow(c.idLow())
                .userId(c.userId())
                .omsOrderId(c.omsOrderId())
                .oldOrderId(c.oldOrderId())
                .price(c.price())
                .quantity(c.quantity())
                .budget(c.budget())
                .marketId(c.marketId())
                .commandKind((short) c.kind())
                .orderType((short) c.type())
                .orderSide((short) c.side())
                .appliedPosition(o.appliedPosition())
                .timestamp(o.timestamp())
                .orderId(o.orderId())
                .status(o.status())
                .reason(o.reason())
                .oldCancelled((short) (o.oldCancelled() ? 1 : 0))
                .result(o.result())
                .timeInForce((short) c.timeInForce())
                .expectedOldRemaining(c.expectedOldRemaining())
                .ledgerSize(ledgerSizeAfter)
                .ledgerCapacity(S1_LEDGER_CAPACITY)
                .behaviorVersion(id.behaviorVersion());
        blockingWrite(MSG_TYPE_OUTCOME,
                MessageHeaderEncoder.ENCODED_LENGTH + outcomeEncoder.encodedLength(), c.idLow());
    }

    /**
     * Record 4 ({@code CommandRelease}). Written by the {@code ReleaseCommandIds} apply from S4;
     * exists from S1 so the layout is round-trip tested. Entries are passed as parallel arrays
     * (first {@code count} used) so the S4 apply can reuse preallocated storage. More than
     * {@link #MAX_RELEASE_ENTRIES} entries is refused: the frame would outgrow the IPC MTU.
     */
    public void appendCommandRelease(final long journalSeq, final long egressSeq, final long releaseSeq,
                                     final int ledgerSize, final int ledgerCapacity,
                                     final int tombstoneRanges, final int count,
                                     final long[] commandIdHigh, final long[] idLowFloor,
                                     final int[] scope, final int[] result, final int[] removed) {
        if (count < 0 || count > MAX_RELEASE_ENTRIES) {
            throw new IllegalArgumentException("CommandRelease carries " + count
                    + " entries; the cap is " + MAX_RELEASE_ENTRIES);
        }
        final CommandReleaseEncoder.ReleasesEncoder group = releaseEncoder
                .wrapAndApplyHeader(scratch, 0, headerEncoder)
                .journalSeq(journalSeq)
                .meEpoch(identity.meEpoch())
                .egressSeq(egressSeq)
                .releaseSeq(releaseSeq)
                .ledgerSize(ledgerSize)
                .ledgerCapacity(ledgerCapacity)
                .tombstoneRanges(tombstoneRanges)
                .releasesCount(count);
        for (int i = 0; i < count; i++) {
            group.next()
                    .commandIdHigh(commandIdHigh[i])
                    .idLowFloor(idLowFloor[i])
                    .scope(releaseScope(scope[i]))
                    .result(releaseResult(result[i]))
                    .removed(removed[i]);
        }
        blockingWrite(MSG_TYPE_RELEASE,
                MessageHeaderEncoder.ENCODED_LENGTH + releaseEncoder.encodedLength(), releaseSeq);
    }

    /**
     * Record 5 ({@code EngineConfig}): the journaled state-machine decision (FRESH or BUMP at
     * behaviour >= 2, RC2 S2). {@code journalSeq} is the state machine's allocation; meEpoch is
     * stamped from the bound identity.
     */
    public void appendEngineConfig(final long journalSeq, final long egressSeq, final long configVersion,
                                   final long behaviorVersion, final long ledgerCapacity,
                                   final int maxWireVersion, final int decision) {
        configEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
                .journalSeq(journalSeq)
                .meEpoch(identity.meEpoch())
                .egressSeq(egressSeq)
                .configVersion(configVersion)
                .behaviorVersion(behaviorVersion)
                .ledgerCapacity(ledgerCapacity)
                .maxWireVersion(maxWireVersion)
                .decision(configDecision(decision));
        blockingWrite(MSG_TYPE_CONFIG,
                MessageHeaderEncoder.ENCODED_LENGTH + configEncoder.encodedLength(), configVersion);
    }

    private static ReleaseScope releaseScope(final int raw) {
        switch (raw) {
            case 0: return ReleaseScope.UP_TO;
            case 1: return ReleaseScope.ORDER;
            default: throw new IllegalArgumentException("ReleaseScope " + raw);
        }
    }

    private static ReleaseResult releaseResult(final int raw) {
        switch (raw) {
            case 0: return ReleaseResult.APPLIED;
            case 1: return ReleaseResult.NOOP;
            case 2: return ReleaseResult.LEG_LIVE;
            default: throw new IllegalArgumentException("ReleaseResult " + raw);
        }
    }

    private static ConfigDecision configDecision(final int raw) {
        switch (raw) {
            case 0: return ConfigDecision.FRESH;
            case 1: return ConfigDecision.ADOPT;
            case 2: return ConfigDecision.SWITCH;
            case 3: return ConfigDecision.BUMP;
            default: throw new IllegalArgumentException("ConfigDecision " + raw);
        }
    }

    public void appendTrade(
            final long journalSeq,
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
            final long timestamp,
            final long takerOmsOrderId,
            final long makerOmsOrderId) {

        tradeEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
                .journalSeq(journalSeq)
                .meEpoch(identity.meEpoch())
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
                .timestamp(timestamp)
                .takerOmsOrderId(takerOmsOrderId)
                .makerOmsOrderId(makerOmsOrderId);

        blockingWrite(MSG_TYPE_TRADE,
                MessageHeaderEncoder.ENCODED_LENGTH + tradeEncoder.encodedLength(), tradeId);
        appendedTrades = appendedTrades + 1;
    }

    public void appendTerminal(
            final long journalSeq,
            final long egressSeq,
            final long orderId,
            final long userId,
            final int marketId,
            final int orderStatus,
            final long timestamp,
            final long omsOrderId,
            final int rejectReason) {

        // The ME OrderRejectReason code verbatim (NONE = 0 on FILLED/CANCELLED); 255 is the SBE
        // null and is never written.
        if (rejectReason < 0 || rejectReason > 254) {
            throw new IllegalArgumentException("Terminal rejectReason " + rejectReason + " is not a uint8 code");
        }
        terminalEncoder.wrapAndApplyHeader(scratch, 0, headerEncoder)
                .journalSeq(journalSeq)
                .meEpoch(identity.meEpoch())
                .egressSeq(egressSeq)
                .orderId(orderId)
                .userId(userId)
                .marketId(marketId)
                .status(TerminalStatus.get((short) orderStatus))
                .timestamp(timestamp)
                .omsOrderId(omsOrderId)
                .rejectReason((short) rejectReason);

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
