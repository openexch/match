// SPDX-License-Identifier: Apache-2.0
package com.match.determinism;

import com.match.application.publisher.MatchEventSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synchronous {@link MatchEventSink} that records every engine event inline on the calling
 * thread — no Disruptor, no threads, no sleeps. This is what makes "same input → same output"
 * exactly assertable: the engine calls these methods within {@code acceptOrder}, so the captured
 * list is the complete, globally-ordered output stream the moment {@code acceptOrder} returns.
 *
 * <p>RC2 S3: the trade id is the engine's allocation (contract section 3), rendered as given, so
 * captured trade IDs are exactly what production assigns; the sink holds no counter.</p>
 */
public final class RecordingEventSink implements MatchEventSink {

    private final List<EngineEvent> events = new ArrayList<>();

    @Override
    public boolean publishTradeExecution(
            int marketId, long timestamp, long tradeId, long journalSeq, long takerOrderId, long takerUserId,
            long makerOrderId, long makerUserId, long price, long quantity,
            boolean takerIsBuy, long takerOmsOrderId, long makerOmsOrderId, long egressSeq) {
        // egressSeq (Aeron log position) and journalSeq (journal sequence) are transport/journal
        // ordering values, not logical engine event fields — deliberately NOT recorded, so the
        // determinism goldens stay implementation-agnostic.
        events.add(new EngineEvent.Trade(marketId, timestamp, tradeId, takerOrderId, takerUserId,
                makerOrderId, makerUserId, price, quantity, takerIsBuy, takerOmsOrderId, makerOmsOrderId));
        return true;
    }

    @Override
    public boolean publishOrderStatusUpdate(
            int marketId, long timestamp, long orderId, long userId,
            int orderStatus, long remainingQty, long filledQty,
            long orderPrice, boolean orderIsBuy, long omsOrderId, int rejectReason, long egressSeq) {
        // egressSeq (Aeron log position) is intentionally not recorded — see publishTradeExecution.
        events.add(new EngineEvent.Status(marketId, timestamp, orderId, userId, orderStatus,
                remainingQty, filledQty, orderPrice, orderIsBuy, omsOrderId, rejectReason));
        return true;
    }

    /** All captured events, in emission (== processing) order. */
    public List<EngineEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /** Render the full captured stream to canonical text, one event per line (trailing newline). */
    public String render() {
        StringBuilder sb = new StringBuilder();
        for (EngineEvent e : events) {
            sb.append(e.render()).append('\n');
        }
        return sb.toString();
    }
}
