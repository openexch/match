package com.match.application.publisher;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.match.infrastructure.Logger;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-producer event publisher for matching engine results.
 * Uses LMAX Disruptor for lock-free publishing to per-market consumer threads.
 *
 * ZERO allocations in publish path - all events pre-allocated in ring buffer.
 *
 * Usage:
 * 1. Call initMarket() for each market during startup
 * 2. Call publish* methods from matching engine after each match
 * 3. Call shutdown() during graceful shutdown
 */
public class MatchEventPublisher {

    private static final Logger logger = Logger.getLogger(MatchEventPublisher.class);

    // Ring buffer size (must be power of 2)
    // 64K events provides ~1-2 seconds buffer at 50K events/sec peak
    private static final int RING_BUFFER_SIZE = 65536;

    // Per-market Disruptors, ring buffers, and handlers
    private final Int2ObjectHashMap<Disruptor<PublishEvent>> disruptors;
    private final Int2ObjectHashMap<RingBuffer<PublishEvent>> ringBuffers;
    private final Int2ObjectHashMap<MarketEventHandler> handlers;

    // Trade ID generator (atomic for snapshot consistency)
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    // Publisher state
    private volatile boolean running = false;

    public MatchEventPublisher() {
        this.disruptors = new Int2ObjectHashMap<>();
        this.ringBuffers = new Int2ObjectHashMap<>();
        this.handlers = new Int2ObjectHashMap<>();
    }

    /**
     * Initialize Disruptor for a specific market.
     * Call during startup, NOT in hot path.
     *
     * @param marketId Market ID
     * @param handler  Event handler for this market
     */
    public void initMarket(int marketId, MarketEventHandler handler) {
        if (running) {
            throw new IllegalStateException("Cannot add markets after publisher is started");
        }

        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "publisher-market-" + marketId);
            t.setDaemon(true);
            return t;
        };

        Disruptor<PublishEvent> disruptor = new Disruptor<>(
            PublishEvent::new,           // EventFactory - allocates once at startup
            RING_BUFFER_SIZE,
            threadFactory,
            ProducerType.SINGLE,         // Single producer (matching engine thread)
            new BusySpinWaitStrategy()   // Lowest latency wait strategy
        );

        // Add exception handler to prevent silent failures
        final int mktId = marketId;
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<PublishEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, PublishEvent event) {
                logger.warn("Exception in event handler for market " + mktId + ": " + ex.getMessage());
            }
            @Override
            public void handleOnStartException(Throwable ex) {
                logger.warn("Exception in onStart for market " + mktId + ": " + ex.getMessage());
            }
            @Override
            public void handleOnShutdownException(Throwable ex) {
                logger.warn("Exception in onShutdown for market " + mktId + ": " + ex.getMessage());
            }
        });

        // Register event handler
        disruptor.handleEventsWith(handler);

        disruptors.put(marketId, disruptor);
        ringBuffers.put(marketId, disruptor.getRingBuffer());
        handlers.put(marketId, handler);
    }

    /**
     * Start all market publishers.
     * Call after all markets are initialized.
     */
    public void start() {
        if (running) {
            return;
        }

        // Start each disruptor AND call handler lifecycle method
        Int2ObjectHashMap<Disruptor<PublishEvent>>.KeyIterator keyIt = disruptors.keySet().iterator();
        while (keyIt.hasNext()) {
            int marketId = keyIt.nextInt();
            Disruptor<PublishEvent> disruptor = disruptors.get(marketId);

            // Call handler.onStart() to initialize scheduler BEFORE disruptor starts
            MarketEventHandler handler = handlers.get(marketId);
            if (handler != null) {
                handler.onStart();
            }

            disruptor.start();
        }

        running = true;
        logger.info("MatchEventPublisher started with " + disruptors.size() + " markets");
    }

    /**
     * Publish trade execution event.
     * ZERO allocations - uses pre-allocated ring buffer slot.
     *
     * @return true if published, false if market not found or buffer full
     */
    public boolean publishTradeExecution(
            int marketId,
            long timestamp,
            long takerOrderId,
            long takerUserId,
            long makerOrderId,
            long makerUserId,
            long price,
            long quantity,
            boolean takerIsBuy) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false; // Market not initialized
        }

        long tradeId = tradeIdGenerator.getAndIncrement();

        // Check ring buffer capacity before blocking
        long remaining = ringBuffer.remainingCapacity();
        long bufferSize = ringBuffer.getBufferSize();
        if (remaining < bufferSize / 2) {
            logger.warn("RING BUFFER LOW: market=" + marketId + ", remaining=" + remaining + "/" + bufferSize);
        }
        if (remaining == 0) {
            logger.error("RING BUFFER FULL - engine will block! market=" + marketId);
        }

        // Get next sequence - this may block briefly if buffer is full
        // In a well-tuned system, this should never block
        long sequence = ringBuffer.next();
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setTradeExecution(
                marketId, timestamp, tradeId,
                takerOrderId, takerUserId,
                makerOrderId, makerUserId,
                price, quantity, takerIsBuy
            );
        } finally {
            ringBuffer.publish(sequence);
        }

        return true;
    }

    /**
     * Publish incremental order book level update.
     * ZERO allocations.
     *
     * @return true if published, false if market not found
     */
    public boolean publishOrderBookLevelUpdate(
            int marketId,
            long timestamp,
            long price,
            long quantity,
            int orderCount,
            boolean isBid) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false;
        }

        long sequence = ringBuffer.next();
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setOrderBookLevelUpdate(
                marketId, timestamp,
                price, quantity, orderCount, isBid
            );
        } finally {
            ringBuffer.publish(sequence);
        }

        return true;
    }

    /**
     * Publish order book snapshot (top N levels).
     * ZERO allocations (arrays copied in-place).
     *
     * @return true if published, false if market not found
     */
    public boolean publishOrderBookSnapshot(
            int marketId,
            long timestamp,
            long[] bidPrices, long[] bidQuantities, int[] bidOrderCounts, int bidCount,
            long[] askPrices, long[] askQuantities, int[] askOrderCounts, int askCount) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false;
        }

        long sequence = ringBuffer.next();
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setOrderBookSnapshot(
                marketId, timestamp,
                bidPrices, bidQuantities, bidOrderCounts, bidCount,
                askPrices, askQuantities, askOrderCounts, askCount
            );
        } finally {
            ringBuffer.publish(sequence);
        }

        return true;
    }

    /**
     * Publish order status update.
     * ZERO allocations.
     *
     * @return true if published, false if market not found
     */
    public boolean publishOrderStatusUpdate(
            int marketId,
            long timestamp,
            long orderId,
            long userId,
            int orderStatus,
            long remainingQty,
            long filledQty,
            long orderPrice,
            boolean orderIsBuy) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false;
        }

        long sequence = ringBuffer.next();
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setOrderStatusUpdate(
                marketId, timestamp,
                orderId, userId, orderStatus,
                remainingQty, filledQty,
                orderPrice, orderIsBuy
            );
        } finally {
            ringBuffer.publish(sequence);
        }

        return true;
    }

    /**
     * Shutdown all disruptors gracefully.
     */
    public void shutdown() {
        if (!running) {
            return;
        }

        running = false;

        // Shutdown each disruptor AND call handler lifecycle method
        Int2ObjectHashMap<Disruptor<PublishEvent>>.KeyIterator keyIt = disruptors.keySet().iterator();
        while (keyIt.hasNext()) {
            int marketId = keyIt.nextInt();
            Disruptor<PublishEvent> disruptor = disruptors.get(marketId);

            try {
                disruptor.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down disruptor: " + e.getMessage());
            }

            // Call handler.onShutdown() to cleanup scheduler
            MarketEventHandler handler = handlers.get(marketId);
            if (handler != null) {
                handler.onShutdown();
            }
        }

        logger.info("MatchEventPublisher shutdown complete");
    }

    // ==================== Snapshot Support ====================

    /**
     * Get current trade ID generator value for snapshot.
     */
    public long getTradeIdGenerator() {
        return tradeIdGenerator.get();
    }

    /**
     * Set trade ID generator value (for snapshot restore).
     */
    public void setTradeIdGenerator(long value) {
        tradeIdGenerator.set(value);
    }

    /**
     * Check if publisher is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get ring buffer size (for monitoring/debugging).
     */
    public int getRingBufferSize() {
        return RING_BUFFER_SIZE;
    }

    /**
     * Get remaining capacity for a market's ring buffer.
     */
    public long getRemainingCapacity(int marketId) {
        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return -1;
        }
        return ringBuffer.remainingCapacity();
    }
}
