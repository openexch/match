package com.match.application.publisher;

import org.agrona.DirectBuffer;

/**
 * Interface for broadcasting market data (SBE binary) to connected clients.
 * Used by MarketPublisher to send data via Aeron egress to gateway.
 *
 * IMPORTANT: In cluster mode, broadcast() queues messages instead of sending directly,
 * because Aeron Cluster doesn't allow sending from background threads.
 * The cluster service must call flush() from a timer callback to actually send.
 */
public interface MarketDataBroadcaster {

    /**
     * Queue an SBE-encoded market data message for broadcast.
     * In cluster mode: queues for later sending via flush().
     *
     * @param buffer The buffer containing the SBE message
     * @param offset Offset into the buffer
     * @param length Length of the message
     */
    void broadcast(DirectBuffer buffer, int offset, int length);

    /**
     * Queue an OMS-bound settlement message (OrderStatus / TradeExecution) for RELIABLE broadcast.
     * Unlike {@link #broadcast}, these must NOT be silently dropped under market-data load: a
     * dropped terminal OrderStatus (CANCELLED/REJECTED) leaves an OMS balance hold stuck forever,
     * and a dropped TradeExecution loses a fill. Cluster impls route these through a separate,
     * large, priority-drained queue. Refreshable UI market data (book snapshots/deltas, UI trades)
     * should use {@link #broadcast} instead.
     *
     * Default impl (non-cluster) delegates to {@link #broadcast}.
     */
    default void broadcastReliable(DirectBuffer buffer, int offset, int length) {
        broadcast(buffer, offset, length);
    }

    /**
     * Flush queued messages (cluster mode only).
     * Must be called from the cluster service's main thread (during onTimerEvent).
     */
    default void flush() {
        // Default no-op
    }

    /**
     * Check if any clients are connected.
     * Used to skip serialization when no one is listening.
     */
    boolean hasSubscribers();

    /**
     * Signal that publishers should re-send a full book snapshot.
     * Called from the service thread when a new gateway connects or reconnects.
     * Thread-safe: increments a generation counter that publishers poll.
     */
    default void requestResnapshot() {
        // Default no-op
    }

    /**
     * Get the current resnapshot generation counter.
     * Each call to requestResnapshot() increments this.
     * Publishers compare against their local counter to detect changes.
     * @return current generation (0 = no resnapshot ever requested)
     */
    default long resnapshotGeneration() {
        return 0;
    }
}
