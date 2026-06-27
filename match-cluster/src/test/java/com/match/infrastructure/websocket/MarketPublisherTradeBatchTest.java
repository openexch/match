package com.match.infrastructure.websocket;

import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.TradeExecutionBatchDecoder;
import org.agrona.DirectBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for the trade-execution batch overflow: a flush carrying more trades than fit in
 * the fixed 256KB encode buffer (e.g. an egress re-emission burst on a leader takeover) must be
 * chunked into multiple messages, never overflow. Steady-state load (~500 trades/50ms) never hits
 * this, so it is exercised here with a forced burst well above {@code MAX_TRADE_EXEC_PER_BATCH}.
 */
public class MarketPublisherTradeBatchTest {

    @Test
    public void largeTradeBurstIsChunkedNotOverflowed() {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        CountingBroadcaster bc = new CountingBroadcaster();
        pub.setBroadcaster(bc);

        final int n = 5000; // > 2 * MAX_TRADE_EXEC_PER_BATCH (2000) -> 3 chunks
        for (int i = 0; i < n; i++) {
            PublishEvent e = new PublishEvent();
            // Distinct trade ids; spread across 50 prices so aggregation stays small.
            e.setTradeExecution(1, 1000L, i + 1, 100, 7, 200 + i, 8,
                    FixedPoint.fromDouble(60_000.0 + (i % 50)), FixedPoint.fromDouble(1.0),
                    true, 0, 0);
            try {
                pub.onEvent(e, i, true);
            } catch (Exception ex) {
                throw new AssertionError("onEvent threw for trade " + i, ex);
            }
        }

        // No scheduler was started, so onShutdown() performs exactly one final flushBuffers().
        pub.onShutdown();

        // The fix: chunked into ceil(5000/2000) = 3 reliable broadcasts, none overflowing, no trade lost.
        assertTrue("expected >= 3 chunked trade-exec broadcasts, got " + bc.reliableCalls,
                bc.reliableCalls >= 3);
        assertEquals("every trade must be encoded across the chunks", n, bc.totalTradesDecoded);
    }

    @Test
    public void egressBufferIsBoundedAndDropsUnderOverload() throws Exception {
        // The OOM root cause: the OMS-bound egress buffers were unbounded and only cleared by the
        // 50ms flush. If the flush falls behind, they must DROP (bounded), not grow into OOM.
        String prev = System.getProperty("match.egress.buffer.max");
        System.setProperty("match.egress.buffer.max", "1000");
        try {
            MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
            CountingBroadcaster bc = new CountingBroadcaster();
            pub.setBroadcaster(bc);

            final int cap = 1000;
            final int pushed = 1500; // exceed the cap with no flush in between
            for (int i = 0; i < pushed; i++) {
                PublishEvent e = new PublishEvent();
                e.setTradeExecution(1, 1000L, i + 1, 100, 7, 200 + i, 8,
                        FixedPoint.fromDouble(60_000.0 + (i % 50)), FixedPoint.fromDouble(1.0), true, 0, 0);
                pub.onEvent(e, i, true);
            }

            // Bounded: the excess is dropped, not accumulated into OOM.
            assertEquals("excess trades must be dropped once the buffer is full",
                    pushed - cap, pub.getDroppedTradeEvents());

            pub.onShutdown(); // final flush
            assertEquals("only the bounded buffer is broadcast", cap, bc.totalTradesDecoded);
        } finally {
            if (prev == null) {
                System.clearProperty("match.egress.buffer.max");
            } else {
                System.setProperty("match.egress.buffer.max", prev);
            }
        }
    }

    /** Captures reliable broadcasts and decodes each TradeExecutionBatch's trade count immediately. */
    private static final class CountingBroadcaster implements MarketDataBroadcaster {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final TradeExecutionBatchDecoder decoder = new TradeExecutionBatchDecoder();
        int reliableCalls = 0;
        int totalTradesDecoded = 0;

        @Override
        public boolean hasSubscribers() {
            return true;
        }

        @Override
        public void broadcast(DirectBuffer buffer, int offset, int length) {
            // Non-reliable path (aggregated trades / book snapshot) — not under test.
        }

        @Override
        public void broadcastReliable(DirectBuffer buffer, int offset, int length) {
            reliableCalls++;
            // Decode now: the publisher reuses the same encode buffer for the next chunk.
            header.wrap(buffer, offset);
            decoder.wrap(buffer, offset + header.encodedLength(), header.blockLength(), header.version());
            totalTradesDecoded += decoder.trades().count();
        }
    }
}
