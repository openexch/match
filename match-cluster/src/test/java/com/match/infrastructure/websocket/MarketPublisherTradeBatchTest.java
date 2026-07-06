// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.TradeExecutionBatchDecoder;
import com.match.infrastructure.generated.TradesBatchDecoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

    @Test
    public void tradesAggregateByPriceAndTakerSide() throws Exception {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        TradesBatchCapturingBroadcaster bc = new TradesBatchCapturingBroadcaster();
        pub.setBroadcaster(bc);

        long p1 = FixedPoint.fromDouble(60_000.0);
        long p2 = FixedPoint.fromDouble(60_001.0);
        // 3 buy-taker at p1, 2 sell-taker at p1, 1 buy-taker at p2 — one flush window
        int tradeId = 0;
        for (int i = 0; i < 3; i++) {
            pub.onEvent(tradeEvent(++tradeId, p1, FixedPoint.fromDouble(1.0), true), tradeId, true);
        }
        for (int i = 0; i < 2; i++) {
            pub.onEvent(tradeEvent(++tradeId, p1, FixedPoint.fromDouble(2.0), false), tradeId, true);
        }
        pub.onEvent(tradeEvent(++tradeId, p2, FixedPoint.fromDouble(5.0), true), tradeId, true);

        pub.onShutdown(); // single final flush

        assertEquals("one TradesBatch expected", 1, bc.tradesBatches.size());
        List<DecodedTrade> entries = decodeTradesBatch(bc.tradesBatches.get(0));
        assertEquals("(price,side) buckets: (p1,BID), (p1,ASK), (p2,BID)", 3, entries.size());

        Map<String, DecodedTrade> byKey = new HashMap<>();
        int totalTradeCount = 0;
        for (DecodedTrade t : entries) {
            byKey.put(t.price + "/" + t.takerSide, t);
            totalTradeCount += t.tradeCount;
        }
        assertEquals("all 6 trades accounted for", 6, totalTradeCount);

        DecodedTrade p1Buy = byKey.get(p1 + "/" + OrderSide.BID);
        assertNotNull(p1Buy);
        assertEquals(3, p1Buy.tradeCount);
        assertEquals(FixedPoint.fromDouble(3.0), p1Buy.quantity);

        DecodedTrade p1Sell = byKey.get(p1 + "/" + OrderSide.ASK);
        assertNotNull(p1Sell);
        assertEquals(2, p1Sell.tradeCount);
        assertEquals(FixedPoint.fromDouble(4.0), p1Sell.quantity);

        DecodedTrade p2Buy = byKey.get(p2 + "/" + OrderSide.BID);
        assertNotNull(p2Buy);
        assertEquals(1, p2Buy.tradeCount);
        assertEquals(FixedPoint.fromDouble(5.0), p2Buy.quantity);
    }

    @Test
    public void preV5DecoderGetsNullValTakerSide() throws Exception {
        // Mixed-version safety: a consumer that believes the stream is v4
        // (header rewritten, as an old peer would have encoded) must read
        // takerSide as NULL_VAL while price/quantity/timestamp still parse.
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        TradesBatchCapturingBroadcaster bc = new TradesBatchCapturingBroadcaster();
        pub.setBroadcaster(bc);

        long price = FixedPoint.fromDouble(60_000.0);
        long qty = FixedPoint.fromDouble(1.5);
        pub.onEvent(tradeEvent(1, price, qty, true), 0, true);
        pub.onShutdown();

        assertEquals(1, bc.tradesBatches.size());
        byte[] bytes = bc.tradesBatches.get(0);
        UnsafeBuffer buf = new UnsafeBuffer(bytes);

        // Rewrite the message header's version field to 4 (pre-takerSide)
        new MessageHeaderEncoder().wrap(buf, 0).version(4);

        MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(4, header.version());
        TradesBatchDecoder decoder = new TradesBatchDecoder()
                .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
        assertEquals(1, decoder.marketId());
        int n = 0;
        for (TradesBatchDecoder.TradesDecoder t : decoder.trades()) {
            n++;
            assertEquals(price, t.price());
            assertEquals(qty, t.quantity());
            assertEquals(1, t.tradeCount());
            assertEquals(200, t.timestamp());
            assertEquals("acting version 4 must yield the null value",
                    OrderSide.NULL_VAL, t.takerSide());
        }
        assertEquals(1, n);
    }

    private static PublishEvent tradeEvent(int tradeId, long price, long quantity, boolean takerIsBuy) {
        PublishEvent e = new PublishEvent();
        e.setTradeExecution(1, 200L, tradeId, 100, 7, 300 + tradeId, 8,
                price, quantity, takerIsBuy, 0, 0);
        return e;
    }

    private static final class DecodedTrade {
        long price;
        long quantity;
        int tradeCount;
        OrderSide takerSide;
    }

    private static List<DecodedTrade> decodeTradesBatch(byte[] bytes) {
        UnsafeBuffer buf = new UnsafeBuffer(bytes);
        MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(TradesBatchDecoder.TEMPLATE_ID, header.templateId());
        TradesBatchDecoder decoder = new TradesBatchDecoder()
                .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
        List<DecodedTrade> out = new ArrayList<>();
        for (TradesBatchDecoder.TradesDecoder t : decoder.trades()) {
            DecodedTrade d = new DecodedTrade();
            d.price = t.price();
            d.quantity = t.quantity();
            d.tradeCount = t.tradeCount();
            d.takerSide = t.takerSide();
            out.add(d);
        }
        return out;
    }

    /** Captures the non-reliable (market-data) TradesBatch broadcasts as byte copies. */
    private static final class TradesBatchCapturingBroadcaster implements MarketDataBroadcaster {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final List<byte[]> tradesBatches = new ArrayList<>();

        @Override
        public boolean hasSubscribers() {
            return true;
        }

        @Override
        public void broadcast(DirectBuffer buffer, int offset, int length) {
            header.wrap(buffer, offset);
            if (header.templateId() == TradesBatchDecoder.TEMPLATE_ID) {
                byte[] copy = new byte[length]; // publisher reuses the encode buffer
                buffer.getBytes(offset, copy);
                tradesBatches.add(copy);
            }
        }

        @Override
        public void broadcastReliable(DirectBuffer buffer, int offset, int length) {
            // OMS-bound path (TradeExecutionBatch / OrderStatusBatch) — not under test here.
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
