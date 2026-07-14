// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.Publication;
import org.agrona.collections.Long2LongHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * match#140: per-session egress delivery observability for the ME leader.
 *
 * <p>The ME-&gt;OMS egress could silently stop delivering to a SINGLE session while every leader
 * counter read clean, because {@code MarketDataBroadcaster.drainQueue()} had two blind spots:
 * <ul>
 *   <li>the non-backpressure skip path ({@code NOT_CONNECTED}/{@code CLOSED}/
 *       {@code MAX_POSITION_EXCEEDED}) was a bare {@code break} with NO counter, so a whole session
 *       going dark was invisible; and</li>
 *   <li>{@code lastEgressSendMs} (behind {@code match_last_egress_age_ms}) was stamped whenever a
 *       message was POLLED, not whether any session actually RECEIVED it, so the age read
 *       false-healthy during a single-session blackout.</li>
 * </ul>
 *
 * <p>This class is <b>observability only</b>: it measures dispositions and delivery age; it never
 * alters the delivery / backpressure / retry BEHAVIOR (what gets retried or dropped is unchanged).
 *
 * <p><b>Thread model.</b> Every mutator here is invoked on the single Aeron clustered-service
 * thread: {@code drainQueue()} runs inside the leader-gated flush timer, the keep-warm offer loop
 * runs on the same flush-timer callback, and {@code removeSession()} runs from {@code onSessionClose}.
 * The two {@link Long2LongHashMap}s and the {@code deliveredThisDrain} flag are therefore touched by
 * exactly ONE thread and need no synchronization (confirmed: no off-thread mutation). The only values
 * read cross-thread (by the {@code /metrics} HTTP scrape thread) are the {@link AtomicLong} skip
 * counters and the single {@code volatile long oldestOfferMs} snapshot, which is recomputed on the
 * service thread. The non-concurrent maps are never read off the service thread. This mirrors the
 * existing {@code NodeMetrics} visibility idiom (plain writes on the agent thread, AtomicLong /
 * volatile for the scrape).
 */
final class EgressSessionMetrics {

    /** Log at the first skip for a session and every N thereafter (mirrors egressOfferGiveUps at 1000). */
    static final long SKIP_LOG_INTERVAL = 1000L;

    // Per-disposition skip counters. AtomicLong for the same reason the neighbouring egress counters
    // (droppedMessages, egressOfferGiveUps, ...) are: written on the service thread, read on the scrape.
    private final AtomicLong skipsNotConnected = new AtomicLong();
    private final AtomicLong skipsClosed = new AtomicLong();
    private final AtomicLong skipsMaxPosition = new AtomicLong();
    // Defensive catch-all so a future / unexpected non-positive disposition is never invisible again.
    private final AtomicLong skipsOther = new AtomicLong();

    // Per-session running skip tally, service-thread only. Drives the rate-limited log line so a live
    // blackout is greppable. Absent session -> 0 (missingValue), so the first skip reads tally 1.
    private final Long2LongHashMap skipTallyBySession = new Long2LongHashMap(0L);

    // Per-session wall-clock ms of the last offer that returned > 0, service-thread only. Real values
    // are always positive, so -1 is a safe missingValue.
    private static final long NO_OFFER = -1L;
    private final Long2LongHashMap lastOfferMsBySession = new Long2LongHashMap(NO_OFFER);

    // Snapshot of the OLDEST per-session last-successful-offer ms across live sessions (== the minimum
    // timestamp == the MAX age). Recomputed on the service thread; read by the metrics gauge.
    // 0 == no live session has ever had a successful offer.
    private volatile long oldestOfferMs = 0L;

    // Per-drain delivery flag: drainQueue() calls beginDrain() before iterating, recordDelivered() on
    // each positive offer, and returns deliveredThisDrain(). flush() then stamps lastEgressSendMs ONLY
    // when that is true — delivery-based, not poll-based (the false-healthy fix). Service-thread only.
    private boolean deliveredThisDrain;

    // ---- service-thread mutators ----

    /** Reset the per-drain delivery flag. Called at the start of each drainQueue pass. */
    void beginDrain() {
        deliveredThisDrain = false;
    }

    /** Record a successful (&gt; 0) offer to a session at wall-clock {@code nowMs}. */
    void recordDelivered(final long sessionId, final long nowMs) {
        deliveredThisDrain = true;
        lastOfferMsBySession.put(sessionId, nowMs);
    }

    /**
     * Record a non-backpressure skip (a session going dark: {@code NOT_CONNECTED}, {@code CLOSED},
     * {@code MAX_POSITION_EXCEEDED}, or any other non-positive disposition). Returns the updated
     * per-session tally so the caller can rate-limit its log line (logging is kept out of this class
     * so it stays a pure, unit-testable accumulator).
     */
    long recordSkip(final long sessionId, final long result) {
        if (result == Publication.NOT_CONNECTED) {
            skipsNotConnected.incrementAndGet();
        } else if (result == Publication.CLOSED) {
            skipsClosed.incrementAndGet();
        } else if (result == Publication.MAX_POSITION_EXCEEDED) {
            skipsMaxPosition.incrementAndGet();
        } else {
            skipsOther.incrementAndGet();
        }
        final long tally = skipTallyBySession.get(sessionId) + 1;
        skipTallyBySession.put(sessionId, tally);
        return tally;
    }

    /** Drop a closed session from per-session state so it can never freeze the age gauge. */
    void removeSession(final long sessionId) {
        lastOfferMsBySession.remove(sessionId);
        skipTallyBySession.remove(sessionId);
        recomputeOldestOffer();
    }

    /** Refresh the volatile oldest-offer snapshot from live per-session state. Service-thread only. */
    void recomputeOldestOffer() {
        oldestOfferMs = lastOfferMsBySession.isEmpty() ? 0L : lastOfferMsBySession.minValue();
    }

    // ---- static helpers (pure) ----

    /** True if this per-session tally should emit a (rate-limited) log line. */
    static boolean shouldLog(final long tally) {
        return tally == 1 || tally % SKIP_LOG_INTERVAL == 0;
    }

    /** Human-readable disposition for logs. */
    static String dispositionName(final long result) {
        if (result == Publication.NOT_CONNECTED) {
            return "NOT_CONNECTED";
        }
        if (result == Publication.CLOSED) {
            return "CLOSED";
        }
        if (result == Publication.MAX_POSITION_EXCEEDED) {
            return "MAX_POSITION_EXCEEDED";
        }
        if (result == Publication.BACK_PRESSURED) {
            return "BACK_PRESSURED";
        }
        if (result == Publication.ADMIN_ACTION) {
            return "ADMIN_ACTION";
        }
        return "OTHER(" + result + ")";
    }

    // ---- reads (service thread and, where noted, the metrics scrape thread) ----

    /** Did any session receive a positive offer during the current drain? (service thread) */
    boolean deliveredThisDrain() {
        return deliveredThisDrain;
    }

    long skipsNotConnected() {
        return skipsNotConnected.get();
    }

    long skipsClosed() {
        return skipsClosed.get();
    }

    long skipsMaxPosition() {
        return skipsMaxPosition.get();
    }

    long skipsOther() {
        return skipsOther.get();
    }

    /** All non-backpressure egress skips across dispositions (the aggregate _total series). */
    long totalSkips() {
        return skipsNotConnected.get() + skipsClosed.get() + skipsMaxPosition.get() + skipsOther.get();
    }

    /**
     * Max age (ms) across sessions of the last successful egress offer, or -1 if no session has
     * delivered yet. Reads only the volatile snapshot, so it is safe from the metrics scrape thread.
     * During a single-session blackout the dark session's last-offer ms stops advancing while the
     * healthy session's keeps moving, so this climbs.
     */
    long maxSessionOfferAgeMs(final long nowMs) {
        final long oldest = oldestOfferMs;
        return oldest == 0 ? -1 : nowMs - oldest;
    }

    // ---- inspection helpers (service-thread state; used by tests) ----

    long oldestOfferMsSnapshot() {
        return oldestOfferMs;
    }

    long lastOfferMs(final long sessionId) {
        return lastOfferMsBySession.get(sessionId);
    }

    long skipTally(final long sessionId) {
        return skipTallyBySession.get(sessionId);
    }
}
