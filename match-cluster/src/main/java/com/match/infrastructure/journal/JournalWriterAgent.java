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

    /** Byte offset of egressSeq inside every journal message: SBE header(8) + first field. */
    private static final int EGRESS_SEQ_OFFSET = 8;
    private static final int DRAIN_LIMIT = 64;
    private static final long CONNECT_RETRY_BACKOFF_MS = 1_000;

    private final OneToOneRingBuffer ring;
    private final Supplier<AeronArchive.Context> archiveClientContext;
    private final String channel;
    private final int streamId;
    private final JournalCheckpointFile checkpoints;
    private final long segmentFileLength;
    private final IdleStrategy offerIdle = new BackoffIdleStrategy();

    private AeronArchive archive;
    private ExclusivePublication publication;
    private long lastConnectAttemptMs;
    private long lastEgressSeq;
    private long lastCheckpointedSegmentBase = -1;

    private volatile long writtenEntries;
    private volatile long journalPosition;
    private volatile long connectFailures;
    private volatile long reconnects;

    public JournalWriterAgent(
            final OneToOneRingBuffer ring,
            final Supplier<AeronArchive.Context> archiveClientContext,
            final String channel,
            final int streamId,
            final JournalCheckpointFile checkpoints,
            final long segmentFileLength) {
        this.ring = ring;
        this.archiveClientContext = archiveClientContext;
        this.channel = channel;
        this.streamId = streamId;
        this.checkpoints = checkpoints;
        this.segmentFileLength = segmentFileLength;
    }

    @Override
    public int doWork() {
        if (publication == null) {
            return tryConnect() ? 1 : 0;
        }
        return ring.controlledRead(this::onRingMessage, DRAIN_LIMIT);
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
            log.info("Settlement journal writer connected: channel=" + channel
                    + " stream=" + streamId + " session=" + publication.sessionId());
            return true;
        } catch (Exception e) {
            connectFailures = connectFailures + 1;
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

        lastEgressSeq = buffer.getLong(index + EGRESS_SEQ_OFFSET);
        writtenEntries = writtenEntries + 1;
        journalPosition = publication.position();

        final long segmentBase = journalPosition - (journalPosition % segmentFileLength);
        if (segmentBase != lastCheckpointedSegmentBase) {
            checkpoints.append(lastEgressSeq, journalPosition);
            lastCheckpointedSegmentBase = segmentBase;
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
        CloseHelper.quietClose(publication);
        CloseHelper.quietClose(archive);
        CloseHelper.quietClose(checkpoints);
    }

    @Override
    public String roleName() {
        return "settlement-journal-writer";
    }
}
