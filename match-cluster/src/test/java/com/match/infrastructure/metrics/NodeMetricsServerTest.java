// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.metrics;

import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.MatchEventPublisher;
import com.match.application.publisher.PublishEvent;
import org.junit.Test;

import static org.junit.Assert.*;

/** match#33: Prometheus rendering from plain-long node metrics. */
public class NodeMetricsServerTest {

    @Test
    public void rendersCountersGaugesRoleAndHistogram() {
        NodeMetrics metrics = new NodeMetrics();
        metrics.setMemberId(1);
        metrics.setRole(2); // leader
        metrics.recordOrderLatency(3_000);       // ~2^11-12 ns bucket
        metrics.recordOrderLatency(3_000_000);   // ~2^21-22 ns bucket
        metrics.publish();

        NodeMetricsServer server = new NodeMetricsServer(metrics)
                .counter("match_orders_submitted_total", "Orders admitted", () -> 42)
                .gauge("match_egress_queue_oms", "Queued OMS egress", () -> 7);

        String out = server.render();
        assertTrue(out.contains("match_orders_submitted_total 42"));
        assertTrue(out.contains("# TYPE match_orders_submitted_total counter"));
        assertTrue(out.contains("match_egress_queue_oms 7"));
        assertTrue(out.contains("match_cluster_role 2"));
        assertTrue(out.contains("match_member_id 1"));
        assertTrue(out.contains("match_snapshot_age_seconds -1"));
        assertTrue(out.contains("match_order_latency_seconds_count 2"));
        assertTrue(out.contains("match_order_latency_seconds_bucket{le=\"+Inf\"} 2"));
        // Cumulative buckets: the 3us sample must be counted at every le >= its bucket.
        assertTrue("expected a mid-range bucket containing only the first sample",
                out.contains("match_order_latency_seconds_bucket{le=\"6.5536E-5\"} 1"));
    }

    @Test
    public void histogramBucketsSaturateAtEnds() {
        NodeMetrics metrics = new NodeMetrics();
        metrics.recordOrderLatency(1);                    // below first bucket → clamps to idx 0
        metrics.recordOrderLatency(Long.MAX_VALUE / 4);   // beyond last → clamps to overflow
        metrics.publish();

        String out = new NodeMetricsServer(metrics).render();
        assertTrue(out.contains("match_order_latency_seconds_count 2"));
        assertTrue(out.contains("_bucket{le=\"+Inf\"} 2"));
    }

    @Test
    public void samplingIsOneInSixteen() {
        NodeMetrics metrics = new NodeMetrics();
        int sampled = 0;
        for (int i = 0; i < 160; i++) {
            if (metrics.shouldSample()) sampled++;
        }
        assertEquals(10, sampled);
    }

    /**
     * match#132: the per-publisher reliable-egress drop counters are aggregated across handlers by
     * MatchEventPublisher and must render on /metrics under their new names. Wires the aggregators as
     * counters exactly as AppClusteredService.startMetricsServer does.
     */
    @Test
    public void publisherDropCountersAggregateAndRender() {
        MatchEventPublisher eventPublisher = new MatchEventPublisher();
        eventPublisher.initMarket(1, new StubDroppingHandler(1, 5, 3));
        eventPublisher.initMarket(2, new StubDroppingHandler(2, 2, 4));
        // No start(): initMarket alone populates the handler map without spinning up disruptor threads.

        assertEquals("trade drops summed across handlers", 7, eventPublisher.droppedTradeEgressTotal());
        assertEquals("status drops summed across handlers", 7, eventPublisher.droppedStatusEgressTotal());

        NodeMetrics metrics = new NodeMetrics();
        metrics.publish();
        NodeMetricsServer server = new NodeMetricsServer(metrics)
                .counter("match_publisher_dropped_trade_total", "Reliable OMS trade-egress dropped",
                        eventPublisher::droppedTradeEgressTotal)
                .counter("match_publisher_dropped_status_total", "Reliable OMS status-egress dropped",
                        eventPublisher::droppedStatusEgressTotal);

        String out = server.render();
        assertTrue(out.contains("match_publisher_dropped_trade_total 7"));
        assertTrue(out.contains("# TYPE match_publisher_dropped_trade_total counter"));
        assertTrue(out.contains("match_publisher_dropped_status_total 7"));
        assertTrue(out.contains("# TYPE match_publisher_dropped_status_total counter"));
    }

    /** Minimal MarketEventHandler that reports fixed reliable-egress drop counts. */
    private static final class StubDroppingHandler implements MarketEventHandler {
        private final int marketId;
        private final long droppedTrades;
        private final long droppedStatuses;

        StubDroppingHandler(int marketId, long droppedTrades, long droppedStatuses) {
            this.marketId = marketId;
            this.droppedTrades = droppedTrades;
            this.droppedStatuses = droppedStatuses;
        }

        @Override
        public void onEvent(PublishEvent event, long sequence, boolean endOfBatch) {
            // Not exercised in this test.
        }

        @Override
        public int getMarketId() {
            return marketId;
        }

        @Override
        public long getDroppedTradeEvents() {
            return droppedTrades;
        }

        @Override
        public long getDroppedStatusEvents() {
            return droppedStatuses;
        }
    }
}
