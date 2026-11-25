// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.Logger;
import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import org.agrona.CloseHelper;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.ControlledMessageHandler;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

import java.util.function.Supplier;

/**
 * Drains the {@link SettlementJournal} ring into a RECORDED exclusive publication on the
 * node's journal archive (the second, disk-backed archive — see InfrastructureConstants).
 *
 * Runs on its own AgentRunner thread (backoff idle): the cluster service thread never
 * touches Aeron or the archive for journaling, it only writes the in-memory ring.
 *
 * Failure policy: never drop. If the archive connection or the publication is unavailable
 * the agent retries with backoff while the ring absorbs the burst; if the ring also fills,
 * {@link SettlementJournal} blocks the service thread (loudly) — the node degrades to a
 * lagging replica rather than losing a settlement.
 */
public final class JournalWriterAgent implements Agent {

    private static final Logger log = Logger.getLogger(JournalWriterAgent.class);

    /**
     * Byte offset of egressSeq inside every journal message: SBE header(8) + the schema 4 common
     * head offset (journalSeq @0, meEpoch @8, egressSeq @16), identical on all five records.
     */
    static final int EGRESS_SEQ_OFFSET =
            com.match.infrastructure.mejournal.generated.MessageHeaderEncoder.ENCODED_LENGTH
            + com.match.infrastructure.mejournal.generated.TradeEncoder.egressSeqEncodingOffset();
    /** Byte offsets of journalSeq and meEpoch in the same common head. */
    static final int JOURNAL_SEQ_OFFSET =
            com.match.infrastructure.mejournal.generated.MessageHeaderEncoder.ENCODED_LENGTH
            + com.match.infrastructure.mejournal.generated.TradeEncoder.journalSeqEncodingOffset();
    static final int ME_EPOCH_OFFSET =
            com.match.infrastructure.mejournal.generated.MessageHeaderEncoder.ENCODED_LENGTH
            + com.match.infrastructure.mejournal.generated.TradeEncoder.meEpochEncodingOffset();
    /** The head file is rewritten at every checkpoint and at most this often in between. */
    private static final long HEAD_INTERVAL_MS = 1_000;
    /** How long a connect waits for the archive to start recording the new publication. */
    private static final long RECORDING_ID_WAIT_MS = 5_000;
    private static final int DRAIN_LIMIT = 64;
    private static final long CONNECT_RETRY_BACKOFF_MS = 1_000;

    private final OneToOneRingBuffer ring;
    private final Supplier<AeronArchive.Context> archiveClientContext;
    private final String channel;
    private final int streamId;
    private final JournalSidecarV2 checkpoints;
    private final JournalHeadFile head;
    private final long segmentFileLength;
    private final IdleStrategy offerIdle = new BackoffIdleStrategy();
    // Drill seam: doWork does nothing while false. Production passes a constant true.
    private final java.util.function.BooleanSupplier drainGate;

    private AeronArchive archive;
    private ExclusivePublication publication;
    private long lastConnectAttemptMs;
    private long lastEgressSeq;
    private long lastJournalSeq;
    private long lastMeEpoch;
    /** The recording of the current publication: the identity of every checkpoint it writes. */
    private long recordingId = io.aeron.Aeron.NULL_VALUE;
    private long lastCheckpointedSegmentBase = -1;
    /** A frame was offered since the head file was last written. */
    private boolean headDirty;
    private long lastHeadWriteMs;

    private volatile long writtenEntries;
    private volatile long journalPosition;
    private volatile long connectFailures;
    private volatile long reconnects;

    public JournalWriterAgent(
            final OneToOneRingBuffer ring,
            final Supplier<AeronArchive.Context> archiveClientContext,
            final String channel,
            final int streamId,
            final JournalSidecarV2 checkpoints,
            final JournalHeadFile head,
            final long segmentFileLength) {
        this(ring, archiveClientContext, channel, streamId, checkpoints, head, segmentFileLength, () -> true);
    }

    /** As above with a drain gate (the kill -9 drill holds it; production never does). */
    JournalWriterAgent(
            final OneToOneRingBuffer ring,
            final Supplier<AeronArchive.Context> archiveClientContext,
            final String channel,
            final int streamId,
            final JournalSidecarV2 checkpoints,
            final JournalHeadFile head,
            final long segmentFileLength,
            final java.util.function.BooleanSupplier drainGate) {
        this.drainGate = drainGate;
        this.ring = ring;
        this.archiveClientContext = archiveClientContext;
        this.channel = channel;
        this.streamId = streamId;
        this.checkpoints = checkpoints;
        this.head = head;
        this.segmentFileLength = segmentFileLength;
    }

    @Override
    public int doWork() {
        if (!drainGate.getAsBoolean()) {
            return 0;
        }
        if (publication == null) {
            return tryConnect() ? 1 : 0;
        }
        final int work = ring.controlledRead(this::onRingMessage, DRAIN_LIMIT);
        if (headDirty) {
            final long nowMs = System.currentTimeMillis();
            if (nowMs - lastHeadWriteMs >= HEAD_INTERVAL_MS) {
                writeHead(nowMs);
            }
        }
        return work;
    }

    /** The head file follows the last frame offered: never ahead of it, at most {@link #HEAD_INTERVAL_MS} behind. */
    private void writeHead(final long nowMs) {
        head.write(lastMeEpoch, lastJournalSeq, recordingId);
        headDirty = false;
        lastHeadWriteMs = nowMs;
    }

    /**
     * The id of the recording the archive started for {@code publication}, read from the recording position
     * counter of its session; {@code NULL_VALUE} when the recording has not started within the wait.
     */
    private long awaitRecordingId() {
        final org.agrona.concurrent.status.CountersReader counters = archive.context().aeron().countersReader();
        final long deadlineMs = System.currentTimeMillis() + RECORDING_ID_WAIT_MS;
        do {
            final int counterId = io.aeron.archive.status.RecordingPos.findCounterIdBySession(
                    counters, publication.sessionId(), archive.archiveId());
            if (counterId != org.agrona.concurrent.status.CountersReader.NULL_COUNTER_ID) {
                return io.aeron.archive.status.RecordingPos.getRecordingId(counters, counterId);
            }
            offerIdle.idle();
        } while (System.currentTimeMillis() < deadlineMs);
        offerIdle.reset();
        return io.aeron.Aeron.NULL_VALUE;
    }

    /**
     * Connect lazily with a retry cadence so a slow-starting journal archive never blocks
     * node boot; the ring buffers entries in the meantime.
     */
    private boolean tryConnect() {
        final long nowMs = System.currentTimeMillis();
        if (nowMs - lastConnectAttemptMs < CONNECT_RETRY_BACKOFF_MS) {
            return false;
        }
        lastConnectAttemptMs = nowMs;
        try {
            archive = AeronArchive.connect(archiveClientContext.get());
            publication = archive.addRecordedExclusivePublication(channel, streamId);
            // every checkpoint names its recording: without the id nothing is offered on this publication
            recordingId = awaitRecordingId();
            if (recordingId == io.aeron.Aeron.NULL_VALUE) {
                throw new IllegalStateException("the archive did not start recording session "
                        + publication.sessionId() + " within " + RECORDING_ID_WAIT_MS + " ms");
            }
            lastCheckpointedSegmentBase = -1;
            log.info("Settlement journal writer connected: channel=" + channel
                    + " stream=" + streamId + " session=" + publication.sessionId()
                    + " recordingId=" + recordingId);
            return true;
        } catch (Exception e) {
            connectFailures = connectFailures + 1;
            CloseHelper.quietClose(publication);
            CloseHelper.quietClose(archive);
            archive = null;
            publication = null;
            log.error("Settlement journal archive connect failed (attempt "
                    + connectFailures + "): " + e.getMessage());
            return false;
        }
    }

    private ControlledMessageHandler.Action onRingMessage(
            final int msgTypeId, final MutableDirectBuffer buffer, final int index, final int length) {
        // Blocking offer inside the handler owns backpressure: a transient negative result
        // (BACK_PRESSURED, NOT_CONNECTED, ADMIN_ACTION) is retried inline with idle backoff.
        // A CLOSED or MAX_POSITION_EXCEEDED result means this publication is permanently unusable,
        // so close it and null it (the next doWork reconnects with a fresh recorded publication),
        // then ABORT so the entry stays in the ring and is retried after reconnect. Never drop
        // (see the class failure policy): controlledRead ABORT does not advance the read position,
        // so this same message is re-presented on the next drain instead of being consumed.
        long result;
        while ((result = publication.offer(buffer, index, length)) < 0) {
            if (result == Publication.CLOSED || result == Publication.MAX_POSITION_EXCEEDED) {
                CloseHelper.quietClose(publication);
                CloseHelper.quietClose(archive);
                publication = null;
                archive = null;
                reconnects = reconnects + 1;
                log.error("Settlement journal publication unusable (" + result
                        + "); reconnecting, entry preserved and retried (reconnects=" + reconnects + ")");
                return ControlledMessageHandler.Action.ABORT;
            }
            offerIdle.idle();
        }
        offerIdle.reset();

        lastEgressSeq = buffer.getLong(index + EGRESS_SEQ_OFFSET, java.nio.ByteOrder.LITTLE_ENDIAN);
        lastJournalSeq = buffer.getLong(index + JOURNAL_SEQ_OFFSET, java.nio.ByteOrder.LITTLE_ENDIAN);
        lastMeEpoch = buffer.getLong(index + ME_EPOCH_OFFSET, java.nio.ByteOrder.LITTLE_ENDIAN);
        writtenEntries = writtenEntries + 1;
        journalPosition = publication.position();
        headDirty = true;

        final long segmentBase = journalPosition - (journalPosition % segmentFileLength);
        if (segmentBase != lastCheckpointedSegmentBase) {
            // the frame just offered ended at journalPosition: every byte below that position belongs to a frame
            // with a journalSeq at or below this one
            checkpoints.append(lastMeEpoch, lastJournalSeq, lastEgressSeq, recordingId, journalPosition);
            lastCheckpointedSegmentBase = segmentBase;
            writeHead(System.currentTimeMillis());
        }
        return ControlledMessageHandler.Action.COMMIT;
    }

    public long writtenEntries() {
        return writtenEntries;
    }

    public long journalPosition() {
        return journalPosition;
    }

    /** Count of publication reconnects forced by a CLOSED / MAX_POSITION_EXCEEDED offer result. */
    public long reconnects() {
        return reconnects;
    }

    @Override
    public void onClose() {
        if (headDirty) {
            try {
                writeHead(System.currentTimeMillis());
            } catch (final RuntimeException e) {
                log.error("Settlement journal head not written on close: " + e);
            }
        }
        CloseHelper.quietClose(publication);
        CloseHelper.quietClose(archive);
        CloseHelper.quietClose(checkpoints);
        CloseHelper.quietClose(head);
    }

    @Override
    public String roleName() {
        return "settlement-journal-writer";
    }
}
