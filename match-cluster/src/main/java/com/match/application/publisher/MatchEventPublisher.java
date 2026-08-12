// SPDX-License-Identifier: Apache-2.0
package com.match.application.publisher;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.match.infrastructure.Logger;
import com.match.infrastructure.TransportConfig;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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
public class MatchEventPublisher implements MatchEventSink {

    private static final Logger logger = Logger.getLogger(MatchEventPublisher.class);

    /**
     * Disruptor wait strategy for the per-market publisher threads, following {@code TRANSPORT_IDLE_MODE}
     * (the same knob as the cluster idle/driver threading). Each market's consumer thread otherwise
     * busy-spins a whole core waiting for events — the right choice on isolated prod cores, but on a
     * shared/cloud host with many markets that is most of the box. A fresh instance per market: some
     * strategies (BlockingWaitStrategy) hold per-disruptor lock state.
     * <ul>
     *   <li>busy_spin (default): {@link BusySpinWaitStrategy} — lowest latency, one core per market.</li>
     *   <li>backoff: {@link SleepingWaitStrategy} — spin/yield then park; low CPU, ~µs latency.</li>
     *   <li>sleep: {@link BlockingWaitStrategy} — lock/condition; ~0% CPU when idle, ~µs wakeup.</li>
     * </ul>
     */
    private static WaitStrategy waitStrategy() {
        switch (TransportConfig.idleMode()) {
            case SLEEP:
                return new BlockingWaitStrategy();
            case BACKOFF:
                return new SleepingWaitStrategy();
            case BUSY_SPIN:
            default:
                return new BusySpinWaitStrategy();
        }
    }

    // Ring buffer size (must be power of 2)
    // 64K events provides ~1-2 seconds buffer at 50K events/sec peak
    private static final int RING_BUFFER_SIZE = 65536;

    // C-1: bounded spin budget for claimSlotOrHalt before it declares the egress ring wedged.
    // A full egress ring means the per-market publisher thread cannot keep up; parking the
    // consensus thread on ringBuffer.next() would livelock the whole node (this was the
    // mechanism behind the 60s failover blindness). Once this budget is spent with the ring
    // still full, the node halts instead of parking.
    private static final int RING_FULL_MAX_SPINS = 1000;

    // Per-market Disruptors, ring buffers, and handlers
    private final Int2ObjectHashMap<Disruptor<PublishEvent>> disruptors;
    private final Int2ObjectHashMap<RingBuffer<PublishEvent>> ringBuffers;
    private final Int2ObjectHashMap<MarketEventHandler> handlers;

    // Trade ID generator (atomic for snapshot consistency)
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    // P1.5 diag counter (match#32): terminal OrderStatus events (FILLED/CANCELLED/
    // REJECTED) published by the engine, counted BEFORE the Disruptor so egress
    // shedding cannot hide a terminalization. Plain long: publishOrderStatusUpdate
    // and the diag reader both run on the cluster service thread.
    private long terminalStatusCount;

    // C-6 diag counter: the per-market Disruptor default exception handler (see initMarket)
    // swallows any exception thrown by a market's MarketEventHandler. That event dies BEFORE
    // a statusSeq is stamped on the wire, so it leaves neither a wire gap nor an OMS-side
    // reconciliation trigger; it is completely invisible without this counter. AtomicLong,
    // NOT a plain long like terminalStatusCount: the handlers run on the per-market publisher
    // threads (multiple writers) and the /metrics scraper reads it from another thread, so the
    // single-thread discipline that makes terminalStatusCount safe does not hold here. Should
    // be ~zero; any non-zero value is a silently-dropped OMS-lane egress event.
    private final AtomicLong disruptorExceptionCount = new AtomicLong();

    // Settlement journal (money-loss surface): every trade and every terminal status is
    // appended HERE, on the deterministic service thread, BEFORE the sheddable Disruptor —
    // so the journal sees every settlement-relevant event on every replica regardless of
    // egress-side shedding or leadership. null = journaling disabled (dark); the volatile
    // read is the only overhead on the hot path in that case.
    private volatile com.match.infrastructure.journal.SettlementJournal settlementJournal;

    // Publisher state
    private volatile boolean running = false;

    /**
     * Arm the settlement journal. Called once at node bootstrap (before the cluster starts
     * processing) when SETTLEMENT_JOURNAL_ENABLED; never called on the hot path.
     */
    public void setSettlementJournal(final com.match.infrastructure.journal.SettlementJournal journal) {
        this.settlementJournal = journal;
    }

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
            waitStrategy()               // busy-spin in prod, sleep/block on constrained hosts (TRANSPORT_IDLE_MODE)
        );

        // Add exception handler to prevent silent failures
        final int mktId = marketId;
        disruptor.setDefaultExceptionHandler(new ExceptionHandler<PublishEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, PublishEvent event) {
                // C-6: KEEP SWALLOWING (rethrow would kill this market's publisher thread and
                // wedge all of its egress), but make it counted and LOUD. The event dies here,
                // before any statusSeq is stamped, so it leaves no wire gap and triggers no
                // OMS-side reconciliation; this counter and ERROR line are the only trace it
                // ever leaves. logged at ERROR (not WARN) because the default level is ERROR
                // and WARN was being suppressed. No rate-limit: these should ~never fire.
                disruptorExceptionCount.incrementAndGet();
                logger.error("EGRESS EVENT LOST (C-6): market=" + mktId + " sequence=" + sequence
                        + " swallowed handler exception " + ex.getClass().getName() + ": " + ex.getMessage()
                        + "; event dropped before statusSeq was stamped (no wire gap, no OMS"
                        + " reconciliation); count exported as match_egress_disruptor_exceptions_total");
            }
            @Override
            public void handleOnStartException(Throwable ex) {
                // Lifecycle, not per-event: raised to ERROR so it is not suppressed. Not folded
                // into disruptorExceptionCount, which tracks per-event egress loss specifically.
                logger.error("Exception in onStart for market " + mktId + ": "
                        + ex.getClass().getName() + ": " + ex.getMessage());
            }
            @Override
            public void handleOnShutdownException(Throwable ex) {
                logger.error("Exception in onShutdown for market " + mktId + ": "
                        + ex.getClass().getName() + ": " + ex.getMessage());
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
     * Claim the next egress ring slot for {@code marketId}, or halt the node if the ring is wedged (C-1).
     *
     * <p>Happy path (the ring has capacity): a single {@link RingBuffer#remainingCapacity()} read
     * then {@link RingBuffer#next()}, zero allocation. Only when the ring is full do we spin a
     * bounded number of times ({@link #RING_FULL_MAX_SPINS}) waiting for the per-market publisher
     * thread to drain a slot. If the ring is STILL full after that budget the ring is wedged: we
     * log loudly and {@link Runtime#halt(int)} the node. We do NOT call {@code next()} on a full
     * ring, because next() would park THIS thread (the cluster service / consensus thread) until
     * the publisher drains, livelocking the node (the mechanism behind the 60s failover blindness).
     *
     * <p>This is leader-side egress, NOT replicated state, so halting one node here does not diverge
     * cluster state; it just removes a node that can no longer ship egress.
     *
     * @return a claimed ring sequence; only returns when a slot is available (otherwise it halts).
     */
    private long claimSlotOrHalt(RingBuffer<PublishEvent> ringBuffer, int marketId) {
        if (ringBuffer.remainingCapacity() == 0) {
            int spins = 0;
            while (ringBuffer.remainingCapacity() == 0 && spins < RING_FULL_MAX_SPINS) {
                Thread.onSpinWait();
                spins++;
            }
            if (ringBuffer.remainingCapacity() == 0) {
                // Allocation here is fine: this path halts the JVM, it is not the hot path.
                String crit = "CRITICAL: egress ring buffer for market " + marketId + " still full after "
                        + RING_FULL_MAX_SPINS + " spins; halting node to avoid livelocking the consensus thread "
                        + "(C-1). A wedged egress ring means the market publisher cannot keep up.";
                logger.error(crit);
                System.err.println(crit);
                Runtime.getRuntime().halt(1);
            }
        }
        return ringBuffer.next();
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
            boolean takerIsBuy,
            long takerOmsOrderId,
            long makerOmsOrderId,
            long egressSeq) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false; // Market not initialized
        }

        long tradeId = tradeIdGenerator.getAndIncrement();

        final com.match.infrastructure.journal.SettlementJournal journal = settlementJournal;
        if (journal != null) {
            journal.appendTrade(egressSeq, tradeId, marketId, takerOrderId, takerUserId,
                    makerOrderId, makerUserId, price, quantity, takerIsBuy, timestamp,
                    takerOmsOrderId, makerOmsOrderId);
        }

        // Early-warning log when the egress ring is running low. The full-ring case
        // (bounded backpressure and, if wedged, halt) is handled by claimSlotOrHalt below.
        long remaining = ringBuffer.remainingCapacity();
        long bufferSize = ringBuffer.getBufferSize();
        if (remaining < bufferSize / 2) {
            logger.warn("RING BUFFER LOW: market=" + marketId + ", remaining=" + remaining + "/" + bufferSize);
        }

        // Claim a slot, or halt the node if the egress ring is wedged (C-1). Never parks the
        // consensus thread on a full ring.
        long sequence = claimSlotOrHalt(ringBuffer, marketId);
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setTradeExecution(
                marketId, timestamp, tradeId,
                takerOrderId, takerUserId,
                makerOrderId, makerUserId,
                price, quantity, takerIsBuy,
                takerOmsOrderId, makerOmsOrderId,
                egressSeq
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

        long sequence = claimSlotOrHalt(ringBuffer, marketId);
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

        long sequence = claimSlotOrHalt(ringBuffer, marketId);
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
            boolean orderIsBuy,
            long omsOrderId,
            int rejectReason,
            long egressSeq) {
        return publishOrderStatusUpdate(marketId, timestamp, orderId, userId, orderStatus,
            remainingQty, filledQty, orderPrice, orderIsBuy, omsOrderId, rejectReason, egressSeq,
            false);
    }

    /**
     * @param suppressJournalTerminal true ONLY for the old-leg CANCELLED of an accepted
     *        cancel-replace: it shares the live replacement's omsOrderId (the AE money key), so
     *        journaling it would feed a TerminalRelease that strips the still-open order's hold.
     *        The wire status itself is unchanged — the OMS's replace leg-routing depends on it.
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
            boolean orderIsBuy,
            long omsOrderId,
            int rejectReason,
            long egressSeq,
            boolean suppressJournalTerminal) {

        RingBuffer<PublishEvent> ringBuffer = ringBuffers.get(marketId);
        if (ringBuffer == null) {
            return false;
        }

        if (orderStatus >= OrderStatusType.FILLED) {
            terminalStatusCount++;
            final com.match.infrastructure.journal.SettlementJournal journal = settlementJournal;
            if (journal != null && !suppressJournalTerminal) {
                journal.appendTerminal(egressSeq, orderId, userId, marketId, orderStatus, timestamp, omsOrderId);
            }
        }

        long sequence = claimSlotOrHalt(ringBuffer, marketId);
        try {
            PublishEvent event = ringBuffer.get(sequence);
            event.setOrderStatusUpdate(
                marketId, timestamp,
                orderId, userId, orderStatus,
                remainingQty, filledQty,
                orderPrice, orderIsBuy,
                omsOrderId, rejectReason,
                egressSeq
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
                // Use halt() instead of shutdown() to avoid blocking on pending events
                disruptor.halt();
            } catch (Exception e) {
                logger.warn("Error halting disruptor: " + e.getMessage());
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

    public long terminalStatusCount() {
        return terminalStatusCount;
    }

    /**
     * Count of OMS-lane Disruptor handler exceptions swallowed by the per-market default
     * exception handler (C-6). Each one is an egress event lost before its statusSeq was
     * stamped (no wire gap, no OMS reconciliation), so this counter is its only signal.
     * Read off the hot path (metrics scrape thread); should be ~zero. Exported as
     * match_egress_disruptor_exceptions_total.
     */
    public long disruptorExceptionCount() {
        return disruptorExceptionCount.get();
    }

    /**
     * Sum of RELIABLE OMS-lane trade-egress events dropped across every per-market handler
     * (bounded-buffer overflow or a flush error discarding buffered egress). Exported to /metrics
     * as match_publisher_dropped_trade_total. Read off the hot path (metrics scrape thread).
     */
    public long droppedTradeEgressTotal() {
        long sum = 0;
        Int2ObjectHashMap<MarketEventHandler>.ValueIterator it = handlers.values().iterator();
        while (it.hasNext()) {
            sum += it.next().getDroppedTradeEvents();
        }
        return sum;
    }

    /**
     * Sum of RELIABLE OMS-lane order-status-egress events dropped across every per-market handler.
     * See {@link #droppedTradeEgressTotal()}. Exported as match_publisher_dropped_status_total.
     */
    public long droppedStatusEgressTotal() {
        long sum = 0;
        Int2ObjectHashMap<MarketEventHandler>.ValueIterator it = handlers.values().iterator();
        while (it.hasNext()) {
            sum += it.next().getDroppedStatusEvents();
        }
        return sum;
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
