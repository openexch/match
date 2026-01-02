package com.match.application.publisher;

/**
 * Interface for broadcasting market data (JSON) to connected clients.
 * Used by MarketPublisher to send data either via:
 * - WebSocket (when running in gateway)
 * - Aeron egress (when running in cluster, for forwarding to gateway)
 */
public interface MarketDataBroadcaster {

    /**
     * Broadcast market data message to all connected clients.
     *
     * @param jsonMessage The JSON message to broadcast (BOOK_SNAPSHOT, TRADES_BATCH, ORDER_STATUS)
     */
    void broadcast(String jsonMessage);

    /**
     * Check if any clients are connected.
     * Used to skip serialization when no one is listening.
     */
    boolean hasSubscribers();
}
