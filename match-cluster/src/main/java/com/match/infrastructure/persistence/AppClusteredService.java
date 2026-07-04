// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.MarketConfig;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.MatchEventPublisher;
import com.match.infrastructure.websocket.MarketPublisher;
import com.match.infrastructure.websocket.SubscriptionManager;
import io.aeron.Aeron;
import io.aeron.Publication;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.logbuffer.Header;
// FragmentHandler replaced with FragmentAssembler for proper multi-fragment reassembly
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import com.match.infrastructure.Logger;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.ClusterHeartbeatEncoder;

import static com.match.infrastructure.InfrastructureConstants.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLong;

public class AppClusteredService implements ClusteredService {
    private static final Logger logger = Logger.getLogger(AppClusteredService.class);

    private final Engine engine = new Engine();
    private final ClientSessions clientSessions = new ClientSessions();
    private final SessionMessageContextImpl context = new SessionMessageContextImpl(clientSessions);
    private final TimerManager timerManager = new TimerManager(context);
    private final SbeDemuxer sbeDemuxer = new SbeDemuxer(engine);

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    // Egress keep-warm: when the leader has sent no egress for this long,
    // broadcast a ClusterHeartbeat so gateway stale-egress detection stays
    // healthy during idle markets. Session liveness is handled by Aeron's
    // protocol-level keep-alive (never enters the Raft log) — NOT by this.
    private static final long EGRESS_KEEP_WARM_INTERVAL_MS = 1_000;
    private volatile long lastEgressSendMs = 0;
    private final MessageHeaderEncoder keepWarmHeaderEncoder = new MessageHeaderEncoder();
    private final ClusterHeartbeatEncoder keepWarmEncoder = new ClusterHeartbeatEncoder();
    private final UnsafeBuffer keepWarmBuffer = new UnsafeBuffer(
            new byte[MessageHeaderEncoder.ENCODED_LENGTH + ClusterHeartbeatEncoder.BLOCK_LENGTH]);

    // Event publishing infrastructure
    private final MatchEventPublisher eventPublisher = new MatchEventPublisher();
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();

    // Store cluster reference for snapshot idle strategy and broadcasting
    private Cluster cluster;

    // match#33: hot-path-safe metrics — plain longs written on this (agent)
    // thread, scraped via a JDK HTTP server on its own daemon thread.
    private final com.match.infrastructure.metrics.NodeMetrics nodeMetrics =
            new com.match.infrastructure.metrics.NodeMetrics();
    private com.match.infrastructure.metrics.NodeMetricsServer metricsServer;

    // Lazy-initialized AeronArchive client used to confirm snapshot recordings are durable
    // before onTakeSnapshot returns. See awaitSnapshotRecorded() for the full reasoning.
    private AeronArchive snapshotArchive;

    // Buffer for broadcasting market data via Aeron egress
    // Uses ExpandableDirectByteBuffer to handle large book snapshots (can exceed 32KB with many orders)
    private final org.agrona.ExpandableDirectByteBuffer broadcastBuffer = new org.agrona.ExpandableDirectByteBuffer(64 * 1024); // 64KB initial, grows as needed

    // Queue for market data messages (SBE binary, thread-safe for producer on Disruptor thread)
    // Bounded to prevent OOM if cluster egress backs up
    private static final int MARKET_DATA_QUEUE_CAPACITY = 10_000;
    private final Queue<QueuedMessage> marketDataQueue = new ArrayBlockingQueue<>(MARKET_DATA_QUEUE_CAPACITY);
    private final AtomicLong droppedMessages = new AtomicLong(0);

    // OMS-bound settlement egress (OrderStatus + TradeExecution) uses a SEPARATE, much larger
    // queue so it is never evicted by a flood of refreshable UI market data (book snapshots/
    // deltas/trades). The shared marketDataQueue silently drops on overflow (fine for UI book
    // data), but dropping an OrderStatus terminal (CANCELLED/REJECTED) leaves an OMS hold stuck
    // forever (oms#21) and dropping a TradeExecution loses a fill. Drained with priority in flush().
    private static final int OMS_EGRESS_QUEUE_CAPACITY = 1 << 18; // 262144
    private final Queue<QueuedMessage> omsEgressQueue = new ArrayBlockingQueue<>(OMS_EGRESS_QUEUE_CAPACITY);
    private final AtomicLong droppedOmsEgress = new AtomicLong(0);

    // The queues above are bounded by ENTRY COUNT, but each entry holds a copied batch buffer
    // (up to ~150KB), so a count limit alone is NOT a memory bound — under a backed-up egress
    // consumer (or while replaying a huge log) they can fill the heap and OOM the matching node.
    // Also cap total queued BYTES. Incremented on offer, decremented on drain.
    private static final long MARKET_DATA_MAX_BYTES = 32L << 20;  // 32 MB (UI data; lossy)
    private static final long OMS_EGRESS_MAX_BYTES = 128L << 20;  // 128 MB (settlement; generous)
    private final AtomicLong marketDataBytes = new AtomicLong(0);
    private final AtomicLong omsEgressBytes = new AtomicLong(0);

    // P1.5/P1.1 (match#32/#30): overflow rejects are counted in Engine at order
    // admission (notional does not fit 64-bit fixed-point) and read from there
    // for the EGRESS-DIAG lines below.

    // Simple wrapper for queued SBE messages (holds copy of buffer content)
    private static final class QueuedMessage {
        final byte[] data;
        final int length;

        QueuedMessage(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }
    }

    /**
     * MarketDataBroadcaster implementation that queues SBE messages for later sending.
     * Messages are queued from the Disruptor thread and flushed on gateway heartbeats.
     * This is required because Aeron Cluster only allows sending from the main service thread.
     */
    private final MarketDataBroadcaster aeronBroadcaster = new MarketDataBroadcaster() {
        private final AtomicLong resnapshotGeneration = new AtomicLong(0);

        @Override
        public void requestResnapshot() {
            resnapshotGeneration.incrementAndGet();
        }

        @Override
        public long resnapshotGeneration() {
            return resnapshotGeneration.get();
        }

        @Override
        public void broadcast(org.agrona.DirectBuffer buffer, int offset, int length) {
            // Copy the buffer content (since the source buffer is reused)
            byte[] copy = new byte[length];
            buffer.getBytes(offset, copy, 0, length);

            boolean queued = marketDataBytes.get() + length <= MARKET_DATA_MAX_BYTES
                    && marketDataQueue.offer(new QueuedMessage(copy, length));
            if (queued) {
                marketDataBytes.addAndGet(length);
            } else {
                long dropped = droppedMessages.incrementAndGet();
                if (dropped % 10000 == 1) {
                    System.err.println("WARNING: Market data egress full (count or "
                        + (MARKET_DATA_MAX_BYTES >> 20) + "MB byte budget)! Dropped " + dropped +
                        " total messages. Clients may have stale data.");
                }
            }
        }

        @Override
        public void broadcastReliable(org.agrona.DirectBuffer buffer, int offset, int length) {
            // OMS-bound settlement egress (OrderStatus / TradeExecution). MUST NOT be silently
            // dropped under market-data load: uses the separate, large omsEgressQueue, drained
            // with priority in flush(). See OMS_EGRESS_QUEUE_CAPACITY for the never-drop rationale.
            byte[] copy = new byte[length];
            buffer.getBytes(offset, copy, 0, length);

            boolean queued = omsEgressBytes.get() + length <= OMS_EGRESS_MAX_BYTES
                    && omsEgressQueue.offer(new QueuedMessage(copy, length));
            if (queued) {
                omsEgressBytes.addAndGet(length);
            } else {
                long dropped = droppedOmsEgress.incrementAndGet();
                // Only under extreme sustained overload (the byte budget bounds heap, preventing OOM).
                // Dropped settlement is recoverable via OMS reconciliation against the cluster log.
                if (dropped == 1 || dropped % 1000 == 0) {
                    System.err.println("CRITICAL: OMS egress full (count or "
                        + (OMS_EGRESS_MAX_BYTES >> 20) + "MB byte budget)! Dropped " + dropped +
                        " OMS-bound settlement message(s). Recoverable via OMS reconciliation.");
                }
            }
        }

        @Override
        public void flush() {
            // Broadcast to ALL sessions - each client filters what it needs
            List<ClientSession> sessions = clientSessions.getAllSessions();
            if (sessions.isEmpty()) {
                // Don't clear the queues — initial book snapshots / OMS settlement egress may be
                // waiting for a gateway to connect. Both queues are bounded so this won't grow
                // unbounded; the gateway uses the latest snapshot and re-reads egress on (re)connect.

                // match#25 diag: queued egress with ZERO sessions to send to is the wedge
                // signature (H2). Rate-limited so a genuinely sessionless cluster doesn't spam.
                final int omsQ = omsEgressQueue.size();
                final int mktQ = marketDataQueue.size();
                if (omsQ > 0 || mktQ > 0) {
                    final long now = System.currentTimeMillis();
                    if (now - lastEmptyFlushDiagMs >= 2_000) {
                        lastEmptyFlushDiagMs = now;
                        System.out.println("EGRESS-DIAG flush-skipped: 0 sessions but queued omsQ="
                                + omsQ + " mktQ=" + mktQ
                                + " (role=" + (cluster != null ? cluster.role() : "null") + ")");
                    }
                }
                return;
            }

            // Drain OMS-bound settlement egress FIRST (reliable, priority), then lossy UI market data.
            boolean sentAny = drainQueue(omsEgressQueue, omsEgressBytes, sessions);
            sentAny |= drainQueue(marketDataQueue, marketDataBytes, sessions);
            if (sentAny) {
                lastEgressSendMs = System.currentTimeMillis();
            }
        }

        private boolean drainQueue(Queue<QueuedMessage> queue, AtomicLong queuedBytes, List<ClientSession> sessions) {
            QueuedMessage msg;
            boolean sentAny = false;
            while ((msg = queue.poll()) != null) {
                queuedBytes.addAndGet(-msg.length);
                broadcastBuffer.putBytes(0, msg.data, 0, msg.length);
                sentAny = true;

                // Send to all connected sessions
                for (ClientSession session : sessions) {
                    int retries = 0;
                    while (retries < 3) {
                        long result = session.offer(broadcastBuffer, 0, msg.length);
                        if (result > 0) {
                            break;
                        } else if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                            retries++;
                            if (cluster != null) {
                                cluster.idleStrategy().idle();
                            }
                        } else {
                            // Session closed or not connected - skip it
                            break;
                        }
                    }
                }
            }
            return sentAny;
        }

        @Override
        public boolean hasSubscribers() {
            // Only leader should broadcast market data
            if (cluster == null || cluster.role() != io.aeron.cluster.service.Cluster.Role.LEADER) {
                return false;
            }
            // Check if any sessions are connected
            return !clientSessions.getAllSessions().isEmpty();
        }
    };

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        context.setIdleStrategy(cluster.idleStrategy());
        timerManager.setCluster(cluster);

        System.out.println("SERVICE onStart: role=" + cluster.role() + ", memberId=" + cluster.memberId());
        System.out.flush();

        // Load snapshot if available
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }

        // Initialize event publishing for all markets
        initializeEventPublishing();

        // P1.2 (match#31): OMS-requested open-order membership snapshot
        sbeDemuxer.setOpenOrdersSnapshotRequestHandler(this::emitOpenOrdersSnapshot);

        startMetricsServer();

        System.out.println("SERVICE onStart complete, markets initialized");
        System.out.flush();
    }

    /** match#33: node /metrics on METRICS_PORT (default 9500 + nodeId). */
    private void startMetricsServer() {
        // cluster.memberId() is -1 during onStart (not yet joined) — use the
        // CLUSTER_NODE env / node.id prop, exactly like AeronCluster bootstrap.
        int nodeId;
        try {
            String envNode = System.getenv("CLUSTER_NODE");
            nodeId = Integer.parseInt(envNode != null ? envNode : System.getProperty("node.id", "0"));
        } catch (NumberFormatException e) {
            nodeId = 0;
        }
        nodeMetrics.setMemberId(nodeId);
        nodeMetrics.setRole(cluster.role().code());
        try {
            String env = System.getenv("METRICS_PORT");
            int port = env != null ? Integer.parseInt(env) : 9500 + nodeId;
            metricsServer = new com.match.infrastructure.metrics.NodeMetricsServer(nodeMetrics)
                    .counter("match_orders_submitted_total", "Orders admitted by the engine", sbeDemuxer::createOrderCount)
                    .counter("match_orders_terminal_total", "Terminal order statuses published", eventPublisher::terminalStatusCount)
                    .counter("match_overflow_rejects_total", "Orders rejected for fixed-point overflow", engine::getOverflowRejectCount)
                    .counter("match_trades_total", "Trades executed (trade id high-water mark)", eventPublisher::getTradeIdGenerator)
                    .counter("match_dropped_market_msgs_total", "Lossy market-data egress drops", droppedMessages::get)
                    .counter("match_dropped_oms_egress_total", "Reliable OMS egress drops (should stay 0)", droppedOmsEgress::get)
                    .counter("match_unknown_timers_total", "Fired cluster timers with no runnable", timerManager::getUnknownTimerCount)
                    .counter("match_flush_timer_fires_total", "Egress flush timer fires", () -> flushTimerFireCount)
                    .counter("match_aeron_errors_total", "Aeron error handler invocations", AeronCluster.AERON_ERROR_COUNT::get)
                    .gauge("match_egress_queue_oms", "Queued reliable OMS egress messages", omsEgressQueue::size)
                    .gauge("match_egress_queue_market", "Queued lossy market-data messages", marketDataQueue::size)
                    .gauge("match_egress_queue_oms_bytes", "Bytes queued for OMS egress", omsEgressBytes::get)
                    .gauge("match_egress_queue_market_bytes", "Bytes queued for market-data egress", marketDataBytes::get)
                    .gauge("match_client_sessions", "Connected cluster client sessions", () -> clientSessions.getAllSessions().size())
                    .gauge("match_last_egress_age_ms", "Milliseconds since the last egress send (leader only)",
                            () -> lastEgressSendMs == 0 ? -1 : System.currentTimeMillis() - lastEgressSendMs);
            metricsServer.start(port);
        } catch (Exception e) {
            // Metrics must never take a node down.
            System.err.println("METRICS: failed to start node metrics server: " + e.getMessage());
        }
    }

    // ---- P1.2 (match#31): open-order membership snapshot for OMS repair ----

    private static final int OPEN_ORDERS_CHUNK = 512;
    private final org.agrona.ExpandableArrayBuffer openOrdersBuffer = new org.agrona.ExpandableArrayBuffer(64 * 1024);
    private final MessageHeaderEncoder openOrdersHeaderEncoder = new MessageHeaderEncoder();
    private final com.match.infrastructure.generated.OpenOrdersSnapshotEncoder openOrdersEncoder =
            new com.match.infrastructure.generated.OpenOrdersSnapshotEncoder();

    /**
     * Emit the full open-order membership set (cluster orderId + omsOrderId when
     * known) as chunked OpenOrdersSnapshot egress. Runs on the service thread for
     * every replica (the command is logged/deterministic) but mutates no state,
     * and only the LEADER enqueues egress — followers have no sessions and must
     * not fill their egress byte budget with output nobody drains.
     *
     * snapshotMaxOrderId is the orderId generator at emission time: the OMS only
     * terminalizes absent orders BELOW this cutoff, so orders created after the
     * snapshot can never be misclassified.
     */
    private void emitOpenOrdersSnapshot(long requestId) {
        if (cluster == null || cluster.role() != Cluster.Role.LEADER) {
            return;
        }
        final long snapshotMaxOrderId = engine.getOrderIdGenerator();

        // Gather all resting orderIds (snapshot-codec enumeration: orderId is
        // tuple slot 0 of every 4-long record). Rare repair path — allocation OK.
        final java.util.ArrayList<long[]> sides = new java.util.ArrayList<>();
        final Int2ObjectHashMap<com.match.application.orderbook.MatchingEngine> engines = engine.getEngines();
        final Int2ObjectHashMap<com.match.application.orderbook.MatchingEngine>.ValueIterator it =
                engines.values().iterator();
        int total = 0;
        while (it.hasNext()) {
            final com.match.application.orderbook.MatchingEngine me = it.next();
            final long[] bids = me.getBidOrders();
            final long[] asks = me.getAskOrders();
            sides.add(bids);
            sides.add(asks);
            total += bids.length / 4 + asks.length / 4;
        }

        int chunkIndex = 0;
        int written = 0;
        long[] chunk = new long[OPEN_ORDERS_CHUNK];
        int inChunk = 0;
        for (long[] side : sides) {
            for (int i = 0; i < side.length; i += 4) {
                chunk[inChunk++] = side[i];
                written++;
                if (inChunk == OPEN_ORDERS_CHUNK) {
                    encodeAndSendOpenOrdersChunk(requestId, snapshotMaxOrderId, chunkIndex++,
                            written == total, chunk, inChunk);
                    inChunk = 0;
                }
            }
        }
        // Final (possibly empty) chunk always carries isLast so the OMS can
        // complete reconciliation even with zero open orders.
        if (inChunk > 0 || chunkIndex == 0) {
            encodeAndSendOpenOrdersChunk(requestId, snapshotMaxOrderId, chunkIndex++, true, chunk, inChunk);
        }
        System.out.println("[P1.2] OpenOrdersSnapshot emitted: requestId=" + requestId
                + " orders=" + total + " chunks=" + chunkIndex
                + " maxOrderId=" + snapshotMaxOrderId);
    }

    private void encodeAndSendOpenOrdersChunk(long requestId, long snapshotMaxOrderId,
                                              int chunkIndex, boolean isLast,
                                              long[] orderIds, int count) {
        openOrdersEncoder.wrapAndApplyHeader(openOrdersBuffer, 0, openOrdersHeaderEncoder)
                .requestId(requestId)
                .snapshotMaxOrderId(snapshotMaxOrderId)
                .chunkIndex(chunkIndex)
                .isLast((short) (isLast ? 1 : 0));
        final com.match.infrastructure.generated.OpenOrdersSnapshotEncoder.OrdersEncoder group =
                openOrdersEncoder.ordersCount(count);
        for (int i = 0; i < count; i++) {
            group.next()
                    .orderId(orderIds[i])
                    .omsOrderId(engine.getOmsOrderIdFor(orderIds[i]));
        }
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + openOrdersEncoder.encodedLength();
        aeronBroadcaster.broadcastReliable(openOrdersBuffer, 0, length);
    }

    private boolean flushTimerScheduled = false;

    // Idle/trailing market-data flush + egress keep-warm cadence. Relaxed from 10ms
    // (100 Hz) to 250ms (4 Hz): every flush-timer reschedule is a replicated cluster
    // timer that enters the Raft log even when idle. This removes the timer's share of
    // idle churn (~2.8 KB/s of the old ~3.9 KB/s; measured idle growth 3.9 -> ~1.2 KB/s).
    // The remaining ~1.1 KB/s is a separate source (OMS still offers a logged
    // GatewayHeartbeat ~10/s — fixed separately in the OMS repo, mirroring loud-limits).
    // Active-trading latency is kept low by an opportunistic flush in onSessionMessage
    // (see ACTIVE_FLUSH_INTERVAL_MS) — egress is only legal from log-driven callbacks,
    // so a periodic timer is still required to cover idle markets, the trailing batch
    // after trading stops, and resnapshot delivery to a client on a quiet market.
    private static final long MARKET_DATA_FLUSH_INTERVAL_MS = 250;

    // Opportunistic active-trading flush cadence. Wall-clock gated and leader-only;
    // pure egress output (ClientSession.offer), so it mutates no replicated state and
    // adds no log entries — unlike the replicated flush timer above.
    private static final long ACTIVE_FLUSH_INTERVAL_MS = 10;
    private long lastActiveFlushMs = 0;

    // --- match#25 egress-wedge diagnostics (logging-only; NO behavior change) ---
    // These never touch replicated state, scheduling, or control flow — they only
    // emit System.out lines so we can pin the EXACT failing condition after repeated
    // switchovers (dead flush chain vs empty clientSessions vs queue overflow).
    private static final long EGRESS_DIAG_INTERVAL_MS = 5_000;   // leader-health heartbeat cadence
    private long lastEgressDiagMs = 0;
    private long lastEmptyFlushDiagMs = 0;
    private long flushTimerFireCount = 0;   // onFlushTimer fires observed while LEADER (chain-alive proxy)

    // Fixed, reserved correlationId for the single egress flush timer (match#25 fix). The flush
    // chain is the ONLY cluster timer in the system and it self-reschedules forever. Using a
    // CONSTANT id (recognized by value in onTimerEvent) instead of TimerManager's monotonic counter
    // means every node recognizes this timer deterministically after recover/replay — eliminating
    // the cross-node correlationId desync that made a freshly-armed timer fire as "unknown" and
    // silently killed the chain (the egress wedge). 1e12 is far above any value the counter could
    // ever reach (~4/s) so it can never collide, even across a mixed-version rolling deploy.
    private static final long FLUSH_TIMER_CORRELATION_ID = 1_000_000_000_000L;

    private void scheduleMarketDataFlush() {
        final long deadline = System.currentTimeMillis() + MARKET_DATA_FLUSH_INTERVAL_MS;
        // Schedule the fixed-id flush timer DIRECTLY (not via the TimerManager counter/runnable map,
        // whose un-snapshotted runnable map is exactly what desynced across switchovers). Rescheduling
        // the same id is idempotent — at most one pending flush timer cluster-wide. No-op on followers.
        cluster.idleStrategy().reset();
        while (!cluster.scheduleTimer(FLUSH_TIMER_CORRELATION_ID, deadline)) {
            cluster.idleStrategy().idle();
        }
    }

    private void onFlushTimer() {
        if (cluster != null && cluster.role() == Cluster.Role.LEADER) {
            aeronBroadcaster.flush();

            // Egress keep-warm: idle markets produce no market data, but the
            // gateway's stale-egress detection needs periodic egress traffic
            long now = System.currentTimeMillis();
            if (now - lastEgressSendMs >= EGRESS_KEEP_WARM_INTERVAL_MS
                    && !clientSessions.getAllSessions().isEmpty()) {
                sendEgressKeepWarm();
                lastEgressSendMs = now;
            }

            // match#25 diag: leader-health heartbeat. The PRESENCE of this line confirms the
            // flush chain is alive on the leader; if these lines STOP while a node is leader, the
            // chain died (H1). sessions=0 with non-zero queues points at H2; climbing droppedOms/
            // droppedMkt is H3; climbing unknownTimers means a fired timer lost its runnable.
            flushTimerFireCount++;
            if (now - lastEgressDiagMs >= EGRESS_DIAG_INTERVAL_MS) {
                lastEgressDiagMs = now;
                System.out.println("EGRESS-DIAG leader-health: sessions=" + clientSessions.getAllSessions().size()
                        + " omsQ=" + omsEgressQueue.size() + " mktQ=" + marketDataQueue.size()
                        + " droppedMkt=" + droppedMessages.get() + " droppedOms=" + droppedOmsEgress.get()
                        + " submitted=" + sbeDemuxer.createOrderCount()
                        + " terminal=" + eventPublisher.terminalStatusCount()
                        + " overflowRej=" + engine.getOverflowRejectCount()
                        + " unknownTimers=" + timerManager.getUnknownTimerCount()
                        + " flushFires=" + flushTimerFireCount
                        + " lastEgressAgeMs=" + (now - lastEgressSendMs)
                        + " flushTimerScheduled=" + flushTimerScheduled);
            }
        }
        // match#33: publish plain-long metric writes to the scraper thread.
        nodeMetrics.publish();
        // Reschedule
        scheduleMarketDataFlush();
    }

    /**
     * Initialize LMAX Disruptor-based event publishing.
     * Market data is broadcast via Aeron egress to connected clients (gateway).
     * Gateway then relays to WebSocket clients.
     */
    private void initializeEventPublishing() {
        try {
            // Create per-market publisher for all configured markets
            for (MarketConfig config : MarketConfig.ALL_MARKETS) {
                MarketPublisher publisher = new MarketPublisher(
                    config.marketId,
                    config.symbol,
                    subscriptionManager
                );

                // Wire matching engine to publisher for direct order book access
                // Order book snapshots are fetched every 50ms on publisher thread
                publisher.setMatchingEngine(engine.getEngine(config.marketId));

                // Use Aeron broadcaster instead of WebSocket
                // Market data flows: Cluster → Aeron egress → Gateway → WebSocket → UI
                publisher.setBroadcaster(aeronBroadcaster);

                // Initialize Disruptor ring buffer for this market
                eventPublisher.initMarket(config.marketId, publisher);

                logger.info("Initialized publisher for {}", config.symbol);
            }

            // Wire publisher to engine (for trade events only)
            engine.setEventPublisher(eventPublisher);

            // Start the Disruptor (creates publisher threads)
            eventPublisher.start();

            logger.info("Event publishing initialized for {} markets with Aeron broadcaster",
                MarketConfig.ALL_MARKETS.length);
        } catch (Exception e) {
            logger.warn("Failed to initialize event publishing: " + e.getMessage());
            // Continue without publishing - engine will still work
        }
    }

    private void loadSnapshot(final Image snapshotImage) {
        System.out.println("[SNAPSHOT] loadSnapshot called, image position: " + snapshotImage.position());

        // Use FragmentAssembler to reassemble messages that span multiple fragments.
        // Without this, snapshots larger than MTU (8KB) would be delivered as separate
        // fragments, and the handler would try to parse an incomplete snapshot — causing
        // either IndexOutOfBoundsException or silently corrupted state.
        final io.aeron.FragmentAssembler assembler = new io.aeron.FragmentAssembler(
            (buf, offset, length, header) -> {
                System.out.println("[SNAPSHOT] Reassembled message: offset=" + offset + ", length=" + length);
                processSnapshotPayload(buf, offset, length);
            }
        );

        // Higher fragment limit shortens replay time on restart. Snapshots are one-shot at
        // startup so spending more time inside one poll() call is fine.
        int fragmentCount = 0;
        while (snapshotImage.poll(assembler, 64) > 0) {
            fragmentCount++;
        }
        System.out.println("[SNAPSHOT] Total fragments polled: " + fragmentCount);
    }

    /**
     * Process a complete (reassembled) snapshot payload.
     * Separated from loadSnapshot for clarity — this expects the FULL snapshot
     * data, not individual fragments.
     */
    private void processSnapshotPayload(final DirectBuffer buf, final int offset, final int length) {
        // Decode via the shared codec (same class that wrote the bytes in onTakeSnapshot).
        // The codec restores the engine's order books + order-id generator in place and hands
        // back the scalars we own externally.
        final SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(buf, offset, length, engine);

        eventPublisher.setTradeIdGenerator(decoded.tradeIdGenerator);
        System.out.println("[SNAPSHOT] Restored OrderIdGenerator=" + decoded.orderIdGenerator
                + " TradeIdGenerator=" + decoded.tradeIdGenerator);

        if (decoded.timerCorrelationIdPresent) {
            // Legacy counter only — the egress flush timer uses a FIXED reserved correlationId
            // (FLUSH_TIMER_CORRELATION_ID) recognized by value in onTimerEvent, so it re-arms
            // deterministically (onRoleChange flag reset → onSessionOpen/onSessionMessage arm;
            // the inherited pending fixed-id timer also continues the chain) and survives
            // recover-into-leader WITHOUT the cross-node counter desync that used to silently
            // kill it. A stray old-id timer re-delivered after recovery just routes to
            // TimerManager and logs one harmless "unknown timer".
            timerManager.setCorrelationId(decoded.timerCorrelationId);
            System.out.println("[SNAPSHOT] Restored TimerCorrelationId (counter only): "
                    + decoded.timerCorrelationId);
        }

        if (decoded.rejectedOrders > 0) {
            System.err.println("[SNAPSHOT] ERROR: " + decoded.rejectedOrders
                    + " orders DROPPED during restore (geometry mismatch?) — state loss!");
        }

        System.out.println("[SNAPSHOT] Snapshot load complete. Consumed "
                + decoded.bytesConsumed + " bytes of " + length);
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
        context.setClusterTime(timestamp);
        clientSessions.addSession(session);
        System.out.println("New client session connected: " + session.id()
                + " (total sessions=" + clientSessions.getAllSessions().size() + ")");   // match#25 diag: track session count

        // A new session (gateway/OMS) needs full book state — trigger resnapshot.
        // Session open is a logged cluster event, so this is deterministic across
        // replicas (unlike the previous heartbeat-driven detection).
        aeronBroadcaster.requestResnapshot();

        // Arm the market data flush timer chain. Same lazy pattern as
        // onSessionMessage: timers scheduled during replay are no-ops and the
        // flag is reset in onRoleChange, so live events must (re-)arm it.
        if (!flushTimerScheduled) {
            scheduleMarketDataFlush();
            flushTimerScheduled = true;
            System.out.println("SERVICE: Market data flush timer scheduled (session open)");
        }
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason) {
        context.setClusterTime(timestamp);
        clientSessions.removeSession(session);
        System.out.println("Client session closed: " + session.id() + " reason=" + closeReason
                + " (total sessions=" + clientSessions.getAllSessions().size() + ")");   // match#25 diag: track session count
    }

    @Override
    public void onSessionMessage(
        final ClientSession session,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header) {

        context.setSessionContext(session, timestamp);

        // match#33: sampled latency (1-in-16) — two nanoTime calls only on
        // sampled messages; recording is plain-long writes on this thread.
        final boolean sampled = nodeMetrics.shouldSample();
        final long t0 = sampled ? System.nanoTime() : 0;
        try {
            sbeDemuxer.dispatch(buffer, offset, length, timestamp);
        } catch (Exception e) {
            System.err.println("ORDER ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }
        if (sampled) {
            nodeMetrics.recordOrderLatency(System.nanoTime() - t0);
        }

        // Schedule the idle/keep-warm flush timer lazily (can't schedule from onStart —
        // cluster not ready).
        if (!flushTimerScheduled) {
            scheduleMarketDataFlush();
            flushTimerScheduled = true;
            System.out.println("SERVICE: Market data flush timer scheduled (" + MARKET_DATA_FLUSH_INTERVAL_MS + "ms)");
            System.out.flush();
        }

        // Drain queued market data promptly while orders are flowing so the relaxed
        // flush-timer cadence doesn't add latency during active trading. Wall-clock
        // gated and leader-only — pure egress output, mutates no replicated state.
        if (cluster != null && cluster.role() == Cluster.Role.LEADER) {
            final long nowMs = System.currentTimeMillis();
            if (nowMs - lastActiveFlushMs >= ACTIVE_FLUSH_INTERVAL_MS) {
                lastActiveFlushMs = nowMs;
                aeronBroadcaster.flush();
            }
        }
    }

    /**
     * Send a ClusterHeartbeat keep-warm to all sessions. Called from the flush
     * timer when no egress has been sent for EGRESS_KEEP_WARM_INTERVAL_MS.
     * Egress-only side effect — does not touch replicated state.
     */
    private void sendEgressKeepWarm() {
        keepWarmEncoder.wrapAndApplyHeader(keepWarmBuffer, 0, keepWarmHeaderEncoder);
        keepWarmEncoder
            .nodeId(cluster.memberId())
            .timestamp(System.currentTimeMillis());
        int length = MessageHeaderEncoder.ENCODED_LENGTH + keepWarmEncoder.encodedLength();

        for (ClientSession session : clientSessions.getAllSessions()) {
            // Best-effort send - don't block or retry
            session.offer(keepWarmBuffer, 0, length);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
        context.setClusterTime(timestamp);
        // match#25 fix: the egress flush timer is recognized by its FIXED reserved id, NOT via the
        // TimerManager runnable map (which isn't restored on recovery — the desync source). This makes
        // onFlushTimer fire deterministically on EVERY node after any recover/replay, so the chain
        // cannot silently die. Any other timer id still routes through TimerManager.
        if (correlationId == FLUSH_TIMER_CORRELATION_ID) {
            onFlushTimer();
            return;
        }
        timerManager.onTimerEvent(correlationId, timestamp);
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        System.out.println("[SNAPSHOT] onTakeSnapshot called");

        // Serialize engine state via the shared codec. SnapshotCodec is the single source of
        // truth for the snapshot byte layout — the same class decodes it on recovery, so encode
        // and decode can never drift. Byte format is identical to the historical inline encoder.
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
        final long orderId = engine.getOrderIdGenerator();
        final long tradeId = eventPublisher.getTradeIdGenerator();
        final long timerCorrelationId = timerManager.getCorrelationId();
        final int pos = SnapshotCodec.serialize(engine, tradeId, timerCorrelationId, buffer);

        System.out.println("[SNAPSHOT] OrderIdGenerator=" + orderId
                + " TradeIdGenerator=" + tradeId
                + " markets=" + engine.getEngines().size()
                + " TimerCorrelationId=" + timerCorrelationId
                + " totalBytes=" + pos);

        // Bounded retry — a wedged snapshot publication used to spin forever, masking the
        // problem. 30s wall-clock is generous (cluster timeouts are seconds, not minutes).
        long result;
        long startMs = System.currentTimeMillis();
        long deadlineMs = startMs + 30_000;
        long attempts = 0;
        while ((result = snapshotPublication.offer(buffer, 0, pos)) < 0) {
            attempts++;
            if (System.currentTimeMillis() > deadlineMs) {
                throw new IllegalStateException("Snapshot offer stuck after 30s: lastResult=" + result
                        + ", attempts=" + attempts + ", payloadBytes=" + pos);
            }
            cluster.idleStrategy().idle();
        }
        System.out.println("[SNAPSHOT] Publication result: " + result
                + " (success after " + attempts + " retries, "
                + (System.currentTimeMillis() - startMs) + "ms)");

        // Block until the Aeron Archive has read all the bytes we offered into its on-disk
        // recording file. Without this, onTakeSnapshot returns while the recording is still in
        // flight; if the node is killed before Archive flushes the catalog stopPosition, the
        // recording is pruned on restart and recovery fails with `unknown recording id`. See
        // /tmp/cluster-forensic2-033046/ROOT-CAUSE-V2.md for the full repro.
        awaitSnapshotRecorded(snapshotPublication);
        nodeMetrics.stampSnapshot(System.currentTimeMillis()); // match#33: wall-clock stamp, metrics only
    }

    /**
     * Wait for the Archive's recording subscription to ingest the snapshot we just published.
     * Blocks until {@code recordingPosition >= snapshotPublication.position()} or 10s elapses.
     */
    private void awaitSnapshotRecorded(final ExclusivePublication snapshotPublication) {
        final long writtenPosition = snapshotPublication.position();
        if (writtenPosition <= 0) {
            return;
        }

        if (snapshotArchive == null) {
            // Clone so we don't share lock state with the cluster's own archive client.
            snapshotArchive = AeronArchive.connect(cluster.context().archiveContext().clone());
        }

        final String channel = snapshotPublication.channel();
        final int streamId = snapshotPublication.streamId();
        final int sessionId = snapshotPublication.sessionId();
        final long startMs = System.currentTimeMillis();
        final long deadlineMs = startMs + 10_000;

        // 1) Wait for the Archive to register the recording for this publication's session.
        long recordingId;
        while ((recordingId = snapshotArchive.findLastMatchingRecording(0L, channel, streamId, sessionId))
                == Aeron.NULL_VALUE) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw new IllegalStateException("Snapshot recording not registered within 10s "
                        + "(channel=" + channel + " stream=" + streamId + " session=" + sessionId + ")");
            }
            cluster.idleStrategy().idle();
        }

        // 2) Wait for the Archive to ingest every byte we offered.
        long observedPosition;
        while ((observedPosition = snapshotArchive.getRecordingPosition(recordingId)) < writtenPosition) {
            if (System.currentTimeMillis() > deadlineMs) {
                throw new IllegalStateException("Archive ingestion stalled: recordingId=" + recordingId
                        + " written=" + writtenPosition + " recorded=" + observedPosition);
            }
            cluster.idleStrategy().idle();
        }

        System.out.println("[SNAPSHOT] Archive ingested recordingId=" + recordingId
                + " position=" + observedPosition + " in "
                + (System.currentTimeMillis() - startMs) + "ms");
    }

    @Override
    public void onRoleChange(final Role newRole) {
        // match#25 diag: snapshot egress state at EVERY role transition (flushTimerScheduled read
        // pre-reset). Lets us see, per switchover, whether sessions/queues/timer-state degrade.
        System.out.println("SERVICE onRoleChange: " + newRole
                + " [EGRESS-DIAG sessions=" + clientSessions.getAllSessions().size()
                + " flushTimerScheduled(pre-reset)=" + flushTimerScheduled
                + " omsQ=" + omsEgressQueue.size() + " mktQ=" + marketDataQueue.size()
                + " droppedMkt=" + droppedMessages.get() + " droppedOms=" + droppedOmsEgress.get()
                + " submitted=" + sbeDemuxer.createOrderCount()
                + " terminal=" + eventPublisher.terminalStatusCount()
                + " overflowRej=" + engine.getOverflowRejectCount()
                + " unknownTimers=" + timerManager.getUnknownTimerCount()
                + " flushFires=" + flushTimerFireCount + "]");
        System.out.flush();
        nodeMetrics.setRole(newRole.code());
        // Always re-arm the flush timer chain on a role change. The chain is a
        // self-rescheduling cluster timer that only the LEADER schedules (a follower's
        // scheduleTimer is a no-op) and that does NOT survive a snapshot recover-into-leader
        // transition. Resetting the flag guarantees the next live onSessionOpen/
        // onSessionMessage arms exactly ONE fresh chain. Since loadSnapshot no longer
        // restores a timer, there is no duplicate-chain risk. If this is missed, the leader
        // sends no idle egress keep-warm and clients fall into stale-egress reconnect loops.
        flushTimerScheduled = false;
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        if (metricsServer != null) {
            metricsServer.stop();
        }
        if (snapshotArchive != null) {
            CloseHelper.quietClose(snapshotArchive);
            snapshotArchive = null;
        }
        eventPublisher.shutdown();
        engine.close();
    }
}