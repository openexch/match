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
            long makerOmsOrderId);

    /**
     * Publish an order status update (NEW / PARTIALLY_FILLED / FILLED / CANCELLED / REJECTED).
     *
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
            long omsOrderId);
}
