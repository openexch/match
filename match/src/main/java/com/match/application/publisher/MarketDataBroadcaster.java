package com.match.application.publisher;

/**
 * Interface for broadcasting market data (JSON) to connected clients.
 * Used by MarketPublisher to send data either via:
 * - WebSocket (when running in gateway)
 * - Aeron egress (when running in cluster, for forwarding to gateway)
 *
 * IMPORTANT: In cluster mode, broadcast() queues messages instead of sending directly,
 * because Aeron Cluster doesn't allow sending from background threads.
 * The cluster service must call flush() from a timer callback to actually send.
 */
public interface MarketDataBroadcaster {

    /**
     * Queue a market data message for broadcast.
     * In gateway mode: sends immediately via WebSocket.
     * In cluster mode: queues for later sending via flush().
     *
     * @param jsonMessage The JSON message to broadcast (BOOK_SNAPSHOT, TRADES_BATCH, ORDER_STATUS)
     */
    void broadcast(String jsonMessage);

    /**
     * Flush queued messages (cluster mode only).
     * Must be called from the cluster service's main thread (during onTimerEvent).
     * In gateway mode: no-op.
     */
    default void flush() {
        // Default no-op for gateway mode
    }

    /**
     * Check if any clients are connected.
     * Used to skip serialization when no one is listening.
     */
    boolean hasSubscribers();
}
