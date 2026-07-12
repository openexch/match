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
     * Publish a single trade execution. Called once per match, in match order.
     *
     * @return true if accepted by the sink, false if the market is unknown
     */
    boolean publishTradeExecution(
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
            long egressSeq);

    /**
     * Publish an order status update (NEW / PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED).
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
     * Variant with journal-terminal suppression. {@code suppressJournalTerminal} is set ONLY for
     * the old-leg CANCELLED of an accepted cancel-replace: it shares the live replacement's
     * omsOrderId (the AE money key), so a journaled terminal would feed the AE a TerminalRelease
     * that strips the still-open order's hold. Sinks without a settlement journal can ignore the
     * flag — this default delegates to the 12-arg form.
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
            boolean suppressJournalTerminal) {
        return publishOrderStatusUpdate(marketId, timestamp, orderId, userId, orderStatus,
            remainingQty, filledQty, orderPrice, orderIsBuy, omsOrderId, rejectReason, egressSeq);
    }
}
