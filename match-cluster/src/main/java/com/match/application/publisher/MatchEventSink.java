// SPDX-License-Identifier: Apache-2.0
package com.match.application.publisher;

/**
 * Synchronous sink for matching-engine output events.
 *
 * <p>The {@link com.match.application.engine.Engine} depends on this interface rather than
 * the concrete {@link MatchEventPublisher} so that the deterministic output of matching
 * (trade executions + order status updates) can be captured inline, on the calling thread,
 * without the async Disruptor. Production wires in {@link MatchEventPublisher}; determinism
 * tests wire in a synchronous recorder. The two methods are exactly the ones the engine
 * calls in its hot path — no lifecycle or snapshot concerns belong here.</p>
 */
public interface MatchEventSink {
    /**
     * Journal-only side output of a durable command (ME journal record 3). Whether a node
     * journals is node configuration, never replicated state, so the command is applied either
     * way.
     *
     * <p>RC2 S3 compile fence (review FIX-9): the pre-S3 {@code (long, DurableCommandOutcome, int)}
     * form is gone with no bridge, and every implementor carries {@code @Override}, so a sink left
     * on the old signature fails to compile instead of silently journaling nothing.</p>
     *
     * @param egressSeq       the entry's log position
     * @param journalSeq      the engine's allocation for this record (0 under behaviour 1)
     * @param ledgerSizeAfter the command ledger size after this outcome is recorded (unchanged
     *                        for a conflict, capacity or invalid outcome); record 3 {@code ledgerSize}
     */
    default void publishCommandOutcome(long egressSeq, long journalSeq,
                                       com.match.domain.commands.DurableCommandOutcome outcome,
                                       int ledgerSizeAfter) {
    }

    /**
     * RC2 S2: journal-only side output of a FRESH or BUMP EngineConfig decision (ME journal
     * record 5). Node configuration decides whether it is journaled, never replicated state.
     *
     * @param egressSeq  the entry's log position
     * @param journalSeq the journalSeq the state machine allocated for this record
     * @param decision   ConfigDecision wire value: FRESH 0 or BUMP 3
     */
    default void publishEngineConfig(long egressSeq, long journalSeq,
                                     com.match.application.engine.EngineConfigState recorded, int decision) {
    }

    /**
     * Publish a single trade execution. Called once per match, in match order. RC2 S3: the
     * engine owns both ids (contract section 3); the sink holds no counter.
     *
     * @param tradeId    the engine-allocated trade id (1, +1 per trade in match order)
     * @param journalSeq the engine-allocated journalSeq of record 1 (0 under behaviour 1)
     * @return true if accepted by the sink, false if the market is unknown
     */
    boolean publishTradeExecution(
            int marketId,
            long timestamp,
            long tradeId,
            long journalSeq,
            long takerOrderId,
            long takerUserId,
            long makerOrderId,
            long makerUserId,
            long price,
            long quantity,
            boolean takerIsBuy,
            long takerOmsOrderId,
            long makerOmsOrderId,
            long egressSeq);

    /**
     * Publish an order status update (NEW / PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED) to
     * egress only; this form never journals.
     *
     * @param rejectReason raw {@link com.match.application.orderbook.OrderRejectReason} code explaining
     *                     WHY a REJECTED (or reason-carrying CANCELLED) status fired;
     *                     {@code OrderRejectReason.NONE} (0) on non-reject statuses. Carried on the
     *                     order-status egress from SBE v6 (match#75) so the OMS/UI can surface it.
     * @param egressSeq    Aeron log position of the producing command (Layer 2): the OMS's ordering
     *                     key across the trade + status egress streams. Deterministic across
     *                     replicas, monotonic across leaders. 0 when unset (replay/tests/no log).
     * @return true if accepted by the sink, false if the market is unknown
     */
    boolean publishOrderStatusUpdate(
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
            long egressSeq);

    /**
     * RC2 S3: the engine's terminal journal decision travels with the status. The engine sets
     * {@code journalTerminal} for every FILLED / CANCELLED / REJECTED it publishes through its
     * plain status path, and clears it for the pre-cancel amend rejection and the replace
     * old-leg CANCELLED (neither closes the OMS order, the AE money key). A sink journals a
     * terminal iff the engine says so, at the engine's {@code journalSeq}. Sinks without a
     * settlement journal can ignore both: this default delegates to the egress-only form.
     */
    default boolean publishOrderStatusUpdate(
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
            boolean journalTerminal,
            long journalSeq) {
        return publishOrderStatusUpdate(marketId, timestamp, orderId, userId, orderStatus,
            remainingQty, filledQty, orderPrice, orderIsBuy, omsOrderId, rejectReason, egressSeq);
    }
}
