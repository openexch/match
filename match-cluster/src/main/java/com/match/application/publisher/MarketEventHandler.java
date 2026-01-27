package com.match.application.publisher;

import com.lmax.disruptor.EventHandler;

/**
 * Event handler interface for market event publishing.
 * Implementations run on dedicated publisher threads (one per market).
 *
 * Implementations handle:
 * - SBE encoding for Aeron cluster broadcast
 * - JSON encoding for WebSocket clients
 * - Routing to appropriate output channels
 */
public interface MarketEventHandler extends EventHandler<PublishEvent> {

    /**
     * Called by Disruptor for each event.
     * Implementation must be thread-safe within single consumer thread.
     *
     * @param event       The publish event (pre-allocated, reused)
     * @param sequence    Ring buffer sequence number
     * @param endOfBatch  True if this is the last event in current batch
     */
    @Override
    void onEvent(PublishEvent event, long sequence, boolean endOfBatch) throws Exception;

    /**
     * Get the market ID this handler is responsible for.
     */
    int getMarketId();

    /**
     * Called when handler is started.
     */
    default void onStart() {}

    /**
     * Called when handler is shutting down.
     */
    default void onShutdown() {}
}
