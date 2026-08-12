// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.OrderStatusType;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;
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

/**
 * Wire tests for the SBE v9 hard-cut egress additions produced by {@link MarketPublisher}:
 *
 * <ul>
 *   <li><b>#136 firstTradeId</b> on {@code TradesBatch}: the tradeId of the FIRST (min-tradeId)
 *       trade folded into each aggregation bucket. Trades arrive in monotonic tradeId order, so the
 *       first one carries the minimum and it must not move as later trades merge into the same
 *       (price, taker side) bucket. Deterministic candle-OPEN key + D-2 dedup.</li>
 *   <li><b>C-5 clusterTimestamp</b> per entry on {@code TradeExecutionBatch} and
 *       {@code OrderStatusBatch}: the deterministic cluster timestamp of the producing command
 *       ({@code event.getTimestamp()}), carried on every entry (not just the message-level flush
 *       timestamp).</li>
 * </ul>
 *
 * <p>Drives the real MarketPublisher encode path and decodes with the regenerated v9 codecs, exactly
 * as {@link MarketPublisherTradeBatchTest} and {@link MarketPublisherOrderStatusReasonTest} do.</p>
 */
public class MarketPublisherWireV9Test {

    /** #136: each aggregation bucket carries the min (first-arriving) tradeId, unchanged by later merges. */
    @Test
    public void tradesBatchCarriesBucketFirstTradeId() throws Exception {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        CapturingBroadcaster bc = new CapturingBroadcaster();
        pub.setBroadcaster(bc);

        long p1 = FixedPoint.fromDouble(60_000.0);
        long p2 = FixedPoint.fromDouble(60_001.0);
        // (p1, BID): tradeIds 10, 11, 12 -> firstTradeId 10
        pub.onEvent(trade(10, p1, 1.0, true), 0, false);
        pub.onEvent(trade(11, p1, 1.0, true), 0, false);
        pub.onEvent(trade(12, p1, 1.0, true), 0, false);
        // (p1, ASK): tradeIds 13, 14 -> firstTradeId 13
        pub.onEvent(trade(13, p1, 1.0, false), 0, false);
        pub.onEvent(trade(14, p1, 1.0, false), 0, false);
        // (p2, BID): tradeId 15 -> firstTradeId 15
        pub.onEvent(trade(15, p2, 1.0, true), 0, false);

        pub.onShutdown(); // single final flush

        assertEquals("one TradesBatch expected", 1, bc.tradesBatches.size());
        Map<String, Long> firstIdByBucket = new HashMap<>();
        UnsafeBuffer buf = new UnsafeBuffer(bc.tradesBatches.get(0));
        MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        TradesBatchDecoder dec = new TradesBatchDecoder()
                .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
        int n = 0;
        for (TradesBatchDecoder.TradesDecoder t : dec.trades()) {
            n++;
            firstIdByBucket.put(t.price() + "/" + t.takerSide(), t.firstTradeId());
        }
        assertEquals("(p1,BID),(p1,ASK),(p2,BID)", 3, n);
        assertEquals("first trade of the p1 buy bucket is its min tradeId",
                Long.valueOf(10L), firstIdByBucket.get(p1 + "/" + OrderSide.BID));
        assertEquals("first trade of the p1 sell bucket is its min tradeId",
                Long.valueOf(13L), firstIdByBucket.get(p1 + "/" + OrderSide.ASK));
        assertEquals("single-trade bucket carries its own tradeId",
                Long.valueOf(15L), firstIdByBucket.get(p2 + "/" + OrderSide.BID));
    }

    /** C-5: every TradeExecutionBatch entry carries the per-trade deterministic cluster timestamp. */
    @Test
    public void tradeExecutionBatchCarriesPerEntryClusterTimestamp() throws Exception {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        CapturingBroadcaster bc = new CapturingBroadcaster();
        pub.setBroadcaster(bc);

        // Distinct cluster timestamps per trade (arg 2 of setTradeExecution == event.getTimestamp()).
        long[] tsIn = {5_000L, 5_007L, 5_009L};
        for (int i = 0; i < tsIn.length; i++) {
            PublishEvent e = new PublishEvent();
            e.setTradeExecution(1, tsIn[i], 100 + i, 700, 7, 800 + i, 8,
                    FixedPoint.fromDouble(60_000.0 + i), FixedPoint.fromDouble(1.0),
                    true, 0, 0, 0L);
            pub.onEvent(e, i, false);
        }
        pub.onShutdown();

        List<long[]> entries = decodeTradeExec(bc);
        assertEquals("all three trades present as per-trade entries (FIFO)", 3, entries.size());
        for (int i = 0; i < tsIn.length; i++) {
            assertEquals("tradeId in order", 100 + i, entries.get(i)[0]);
            assertEquals("clusterTimestamp carried per entry", tsIn[i], entries.get(i)[1]);
        }
    }

    /** C-5: every OrderStatusBatch entry carries the per-order deterministic cluster timestamp. */
    @Test
    public void orderStatusBatchCarriesPerEntryClusterTimestamp() throws Exception {
        MarketPublisher pub = new MarketPublisher(1, "BTC-USD", null);
        CapturingBroadcaster bc = new CapturingBroadcaster();
        pub.setBroadcaster(bc);

        long[] tsIn = {9_100L, 9_101L, 9_250L};
        for (int i = 0; i < tsIn.length; i++) {
            PublishEvent e = new PublishEvent();
            // arg 2 (timestamp) == event.getTimestamp() == the C-5 cluster time.
            e.setOrderStatusUpdate(1, tsIn[i], 500 + i, 200L, OrderStatusType.NEW,
                    FixedPoint.fromDouble(1.0), 0L, FixedPoint.fromDouble(60_000.0), true, 0L, 0, 0L);
            pub.onEvent(e, i, false);
        }
        pub.onShutdown();

        List<long[]> entries = decodeOrderStatus(bc);
        assertEquals("all three statuses present (FIFO)", 3, entries.size());
        for (int i = 0; i < tsIn.length; i++) {
            assertEquals("orderId in order", 500 + i, entries.get(i)[0]);
            assertEquals("clusterTimestamp carried per entry", tsIn[i], entries.get(i)[1]);
        }
    }

    // ==================== helpers ====================

    private static PublishEvent trade(int tradeId, long price, double qty, boolean takerIsBuy) {
        PublishEvent e = new PublishEvent();
        e.setTradeExecution(1, 1_000L + tradeId, tradeId, 700, 7, 800 + tradeId, 8,
                price, FixedPoint.fromDouble(qty), takerIsBuy, 0, 0, 0L);
        return e;
    }

    /** Returns [tradeId, clusterTimestamp] for each TradeExecutionBatch entry, in wire order. */
    private static List<long[]> decodeTradeExec(CapturingBroadcaster bc) {
        List<long[]> out = new ArrayList<>();
        for (byte[] bytes : bc.tradeExecBatches) {
            UnsafeBuffer buf = new UnsafeBuffer(bytes);
            MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
            TradeExecutionBatchDecoder dec = new TradeExecutionBatchDecoder()
                    .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
            for (TradeExecutionBatchDecoder.TradesDecoder t : dec.trades()) {
                out.add(new long[]{t.tradeId(), t.clusterTimestamp()});
            }
        }
        return out;
    }

    /** Returns [orderId, clusterTimestamp] for each OrderStatusBatch entry, in wire order. */
    private static List<long[]> decodeOrderStatus(CapturingBroadcaster bc) {
        List<long[]> out = new ArrayList<>();
        for (byte[] bytes : bc.orderStatusBatches) {
            UnsafeBuffer buf = new UnsafeBuffer(bytes);
            MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
            OrderStatusBatchDecoder dec = new OrderStatusBatchDecoder()
                    .wrap(buf, header.encodedLength(), header.blockLength(), header.version());
            for (OrderStatusBatchDecoder.OrdersDecoder o : dec.orders()) {
                out.add(new long[]{o.orderId(), o.clusterTimestamp()});
            }
        }
        return out;
    }

    /** Captures TradesBatch (lossy lane) and TradeExecutionBatch/OrderStatusBatch (reliable lane). */
    private static final class CapturingBroadcaster implements MarketDataBroadcaster {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final List<byte[]> tradesBatches = new ArrayList<>();
        final List<byte[]> tradeExecBatches = new ArrayList<>();
        final List<byte[]> orderStatusBatches = new ArrayList<>();

        @Override
        public boolean hasSubscribers() {
            return true;
        }

        @Override
        public void broadcast(DirectBuffer buffer, int offset, int length) {
            header.wrap(buffer, offset);
            if (header.templateId() == TradesBatchDecoder.TEMPLATE_ID) {
                tradesBatches.add(copy(buffer, offset, length));
            }
        }

        @Override
        public void broadcastReliable(DirectBuffer buffer, int offset, int length) {
            header.wrap(buffer, offset);
            int tid = header.templateId();
            if (tid == TradeExecutionBatchDecoder.TEMPLATE_ID) {
                tradeExecBatches.add(copy(buffer, offset, length));
            } else if (tid == OrderStatusBatchDecoder.TEMPLATE_ID) {
                orderStatusBatches.add(copy(buffer, offset, length));
            }
        }

        private static byte[] copy(DirectBuffer buffer, int offset, int length) {
            byte[] out = new byte[length]; // publisher reuses its encode buffer
            buffer.getBytes(offset, out);
            return out;
        }
    }
}
