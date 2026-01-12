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
}
