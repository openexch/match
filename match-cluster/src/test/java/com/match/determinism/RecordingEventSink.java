package com.match.determinism;

import com.match.application.publisher.MatchEventSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronous {@link MatchEventSink} that records every engine event inline on the calling
 * thread — no Disruptor, no threads, no sleeps. This is what makes "same input → same output"
 * exactly assertable: the engine calls these methods within {@code acceptOrder}, so the captured
 * list is the complete, globally-ordered output stream the moment {@code acceptOrder} returns.
 *
 * <p>The trade-id counter mirrors {@code MatchEventPublisher} (an {@code AtomicLong} starting at
 * 1, incremented once per trade) so captured trade IDs equal what production would assign. The
 * counter is exposed so a snapshot round-trip can carry it across an in-scenario "restart",
 * exactly as production restores it from the snapshot.</p>
 */
public final class RecordingEventSink implements MatchEventSink {

    private final List<EngineEvent> events = new ArrayList<>();
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    @Override
    public boolean publishTradeExecution(
            int marketId, long timestamp, long takerOrderId, long takerUserId,
            long makerOrderId, long makerUserId, long price, long quantity,
            boolean takerIsBuy, long takerOmsOrderId, long makerOmsOrderId) {
        long tradeId = tradeIdGenerator.getAndIncrement();
        events.add(new EngineEvent.Trade(marketId, timestamp, tradeId, takerOrderId, takerUserId,
                makerOrderId, makerUserId, price, quantity, takerIsBuy, takerOmsOrderId, makerOmsOrderId));
        return true;
    }

    @Override
    public boolean publishOrderStatusUpdate(
            int marketId, long timestamp, long orderId, long userId,
            int orderStatus, long remainingQty, long filledQty,
            long orderPrice, boolean orderIsBuy, long omsOrderId) {
        events.add(new EngineEvent.Status(marketId, timestamp, orderId, userId, orderStatus,
                remainingQty, filledQty, orderPrice, orderIsBuy, omsOrderId));
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

    // --- trade-id counter, mirroring MatchEventPublisher's snapshot get/set ---

    public long getTradeIdGenerator() {
        return tradeIdGenerator.get();
    }

    public void setTradeIdGenerator(long value) {
        tradeIdGenerator.set(value);
    }
}
