// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.metrics;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.engine.EngineConfigStateMachine;
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

    /** The three parts of the last snapshot as the service thread timed them: -1 before one, seconds after. */
    @Test
    public void rendersTheLastSnapshotsTimings() {
        NodeMetrics metrics = new NodeMetrics();
        NodeMetricsServer server = new NodeMetricsServer(metrics);
        String before = server.render();
        assertTrue(before, before.contains("match_snapshot_last_serialize_seconds -1\n"));
        assertTrue(before, before.contains("match_snapshot_last_offer_seconds -1\n"));
        assertTrue(before, before.contains("match_snapshot_last_record_seconds -1\n"));
        assertTrue(before, before.contains("# TYPE match_snapshot_last_serialize_seconds gauge\n"));

        metrics.stampSnapshotTimings(1_250_000_000L, 40_000_000L, 3_000_000L);
        String after = server.render();
        assertTrue(after, after.contains("match_snapshot_last_serialize_seconds 1.25\n"));
        assertTrue(after, after.contains("match_snapshot_last_offer_seconds 0.04\n"));
        assertTrue(after, after.contains("match_snapshot_last_record_seconds 0.003\n"));
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

    /**
     * #224: the effective engine-creation gauges must render next to match_engine_from_config —
     * wired from the state machine exactly as AppClusteredService.startMetricsServer does. The
     * hash gauge renders UNSIGNED so cloud-console's Go side can strconv.ParseUint it.
     */
    @Test
    public void effectiveEngineConfigGaugesRender() {
        // Fresh accept: engines created FROM the config, which becomes the recorded truth —
        // the gauges must then publish exactly its values (test-vector config, hash pinned in
        // EngineConfigCanonicalHashTest).
        EngineConfigStateMachine sm = new EngineConfigStateMachine(Engine.deferredUntilConfig(),
                () -> fail("failFast must not fire here"), null);
        sm.onEngineConfig(EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                }));

        NodeMetrics metrics = new NodeMetrics();
        metrics.publish();
        String out = new NodeMetricsServer(metrics)
                .gauge("match_engine_effective_book_capacity", "Effective per-book capacity",
                        sm::effectiveBookCapacity)
                .gauge("match_engine_effective_max_matches_per_order", "Effective per-order match cap",
                        sm::effectiveMaxMatchesPerOrder)
                .gauge("match_engine_effective_max_orders_per_level", "Effective per-level cap",
                        sm::effectiveMaxOrdersPerLevel)
                .gauge("match_engine_effective_impl", "EngineImpl wire value",
                        sm::effectiveImplWire)
                .gaugeUnsigned("match_engine_effective_config_hash", "Canonical config hash",
                        sm::effectiveConfigHash)
                .render();

        assertTrue(out.contains("match_engine_effective_book_capacity 4096"));
        assertTrue(out.contains("match_engine_effective_max_matches_per_order 100"));
        assertTrue(out.contains("match_engine_effective_max_orders_per_level 0"));
        assertTrue(out.contains("match_engine_effective_impl 0"));
        assertTrue(out.contains("match_engine_effective_config_hash 251824785580910312"));
        assertTrue(out.contains("# TYPE match_engine_effective_config_hash gauge"));
    }

    /** #224: before any config in config mode there are no effective values — sentinels render. */
    @Test
    public void effectiveEngineConfigGaugesRenderSentinelsPreConfig() {
        EngineConfigStateMachine sm = new EngineConfigStateMachine(Engine.deferredUntilConfig(),
                () -> fail("failFast must not fire here"), null);

        NodeMetrics metrics = new NodeMetrics();
        metrics.publish();
        String out = new NodeMetricsServer(metrics)
                .gauge("match_engine_effective_book_capacity", "Effective per-book capacity",
                        sm::effectiveBookCapacity)
                .gauge("match_engine_effective_impl", "EngineImpl wire value",
                        sm::effectiveImplWire)
                .gaugeUnsigned("match_engine_effective_config_hash", "Canonical config hash",
                        sm::effectiveConfigHash)
                .render();

        assertTrue(out.contains("match_engine_effective_book_capacity -1"));
        assertTrue(out.contains("match_engine_effective_impl -1"));
        assertTrue(out.contains("match_engine_effective_config_hash 0"));
    }

    /** #224: unsigned gauges must render high-bit longs as their unsigned decimal text. */
    @Test
    public void unsignedGaugeRendersHighBitValuesUnsigned() {
        NodeMetrics metrics = new NodeMetrics();
        metrics.publish();
        String out = new NodeMetricsServer(metrics)
                .gaugeUnsigned("match_test_unsigned", "Raw unsigned 64-bit value", () -> -1L)
                .render();
        assertTrue("expected 2^64-1, never a minus sign",
                out.contains("match_test_unsigned 18446744073709551615"));
    }

    /** The value of a series line {@code name value}, or null when the series is absent. */
    private static String series(String body, String name) {
        for (String line : body.split("\n")) {
            if (line.startsWith(name + " ")) {
                return line.substring(name.length() + 1);
            }
        }
        return null;
    }

    @Test
    public void snapshotPositionIsMinusOneAndTheHashIsOmittedBeforeAnySnapshot() {
        String out = new NodeMetricsServer(new NodeMetrics()).render();
        assertEquals("never snapshotted", "-1", series(out, "match_snapshot_last_log_position"));
        assertNull("an unknown hash is omitted, never rendered as a value three nodes could agree on",
                series(out, "match_snapshot_state_hash"));
        assertFalse(out.contains("match_snapshot_state_hash"));
    }

    @Test
    public void snapshotStateRendersThePositionAndTheUnsignedHashOfOneStamp() {
        NodeMetrics metrics = new NodeMetrics();
        metrics.stampSnapshotState(1_048_576L, 0xFFFF_FFFEL);
        String out = new NodeMetricsServer(metrics).render();
        assertEquals("1048576", series(out, "match_snapshot_last_log_position"));
        assertEquals("unsigned decimal, never a minus sign", "4294967294", series(out, "match_snapshot_state_hash"));
        assertTrue(out.contains("# TYPE match_snapshot_state_hash gauge"));
        assertTrue(out.contains("# TYPE match_snapshot_last_log_position gauge"));
    }

    @Test
    public void aStampWithoutAHashShowsThePositionAndOmitsTheHash() {
        NodeMetrics metrics = new NodeMetrics();
        metrics.stampSnapshotState(4_096L, 7L);
        metrics.stampSnapshotState(8_192L, NodeMetrics.UNKNOWN_HASH);
        String out = new NodeMetricsServer(metrics).render();
        assertEquals("8192", series(out, "match_snapshot_last_log_position"));
        assertNull("the earlier hash is not paired with the new position", series(out, "match_snapshot_state_hash"));
    }

    @Test
    public void aHashOutsideTheUnsigned32BitRangeIsRefused() {
        NodeMetrics metrics = new NodeMetrics();
        assertThrows(IllegalArgumentException.class, () -> metrics.stampSnapshotState(1L, 0x1_0000_0000L));
        assertThrows(IllegalArgumentException.class, () -> metrics.stampSnapshotState(1L, -2L));
    }

    @Test
    public void aScrapeNeverPairsAPositionWithAnotherStampsHash() throws Exception {
        // The writer stamps (p, p) pairs; every render must show a position and a hash from the same stamp.
        NodeMetrics metrics = new NodeMetrics();
        NodeMetricsServer server = new NodeMetricsServer(metrics);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread writer = new Thread(() -> {
            long p = 1;
            while (!stop.get()) {
                metrics.stampSnapshotState(p, p & NodeMetrics.MAX_STATE_HASH);
                p++;
            }
        }, "snapshot-stamper");
        writer.start();
        int seen = 0;
        try {
            for (int i = 0; i < 20_000; i++) {
                String out = server.render();
                String position = series(out, "match_snapshot_last_log_position");
                String hash = series(out, "match_snapshot_state_hash");
                if ("-1".equals(position)) {
                    assertNull(hash);
                    continue;
                }
                assertEquals("render " + i + ": position and hash come from one stamp", position, hash);
                seen++;
            }
        } finally {
            stop.set(true);
            writer.join();
        }
        assertTrue("non-vacuous: renders saw stamps", seen > 0);
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
