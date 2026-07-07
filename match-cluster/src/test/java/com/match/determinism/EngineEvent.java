// SPDX-License-Identifier: Apache-2.0
package com.match.determinism;

/**
 * An immutable matching-engine output event captured by {@link RecordingEventSink}.
 *
 * <p>These are the two events the engine emits through {@code MatchEventSink}. Each renders to
 * a single canonical line for golden-master comparison; record equality also backs the
 * run-twice / wall-clock-invariance determinism checks.</p>
 */
public sealed interface EngineEvent permits EngineEvent.Trade, EngineEvent.Status {

    /** Canonical one-line form for golden files. Fields are fixed-order; FixedPoint values are raw longs. */
    String render();

    record Trade(
            int marketId,
            long timestamp,
            long tradeId,
            long takerOrderId,
            long takerUserId,
            long makerOrderId,
            long makerUserId,
            long price,
            long quantity,
            boolean takerIsBuy,
            long takerOmsOrderId,
            long makerOmsOrderId) implements EngineEvent {

        @Override
        public String render() {
            return String.format(
                    "TRADE  m=%d ts=%d tradeId=%d taker=%d takerUser=%d maker=%d makerUser=%d "
                            + "px=%d qty=%d takerBuy=%d takerOms=%d makerOms=%d",
                    marketId, timestamp, tradeId, takerOrderId, takerUserId, makerOrderId,
                    makerUserId, price, quantity, takerIsBuy ? 1 : 0, takerOmsOrderId, makerOmsOrderId);
        }
    }

    record Status(
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
            int rejectReason) implements EngineEvent {

        @Override
        public String render() {
            return String.format(
                    "STATUS m=%d ts=%d order=%d user=%d status=%s rem=%d filled=%d px=%d buy=%d oms=%d reason=%s",
                    marketId, timestamp, orderId, userId, statusName(orderStatus), remainingQty,
                    filledQty, orderPrice, orderIsBuy ? 1 : 0, omsOrderId,
                    com.match.application.orderbook.OrderRejectReason.describe(rejectReason));
        }
    }

    /** Map the int status constant to a stable, readable name for golden files. */
    static String statusName(int status) {
        switch (status) {
            case 0:  return "NEW";
            case 1:  return "PARTIALLY_FILLED";
            case 2:  return "FILLED";
            case 3:  return "CANCELLED";
            case 4:  return "REJECTED";
            default: return "UNKNOWN(" + status + ")";
        }
    }
}
