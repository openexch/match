package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.MarketConfig;
import com.match.application.orderbook.DirectMatchingEngine;
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

            if (!marketDataQueue.offer(new QueuedMessage(copy, length))) {
                long dropped = droppedMessages.incrementAndGet();
                if (dropped % 10000 == 1) {
                    System.err.println("WARNING: Market data queue full! Dropped " + dropped +
                        " total messages. Queue capacity: " + MARKET_DATA_QUEUE_CAPACITY +
                        ". Clients may have stale data.");
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

            if (!omsEgressQueue.offer(new QueuedMessage(copy, length))) {
                long dropped = droppedOmsEgress.incrementAndGet();
                // Should be unreachable in practice (large queue, drained before market data). If
                // it ever fires, OMS may miss a fill/terminal status — alarm loudly (1st + every 1000th).
                if (dropped == 1 || dropped % 1000 == 0) {
                    System.err.println("CRITICAL: OMS egress queue full! Dropped " + dropped +
                        " OMS-bound settlement message(s) (OrderStatus/TradeExecution). Capacity: " +
                        OMS_EGRESS_QUEUE_CAPACITY + ". Holds/fills may be missed.");
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
                return;
            }

            // Drain OMS-bound settlement egress FIRST (reliable, priority), then lossy UI market data.
            boolean sentAny = drainQueue(omsEgressQueue, sessions);
            sentAny |= drainQueue(marketDataQueue, sessions);
            if (sentAny) {
                lastEgressSendMs = System.currentTimeMillis();
            }
        }

        private boolean drainQueue(Queue<QueuedMessage> queue, List<ClientSession> sessions) {
            QueuedMessage msg;
            boolean sentAny = false;
            while ((msg = queue.poll()) != null) {
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

        System.out.println("SERVICE onStart complete, markets initialized");
        System.out.flush();
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

    private void scheduleMarketDataFlush() {
        long deadline = System.currentTimeMillis() + MARKET_DATA_FLUSH_INTERVAL_MS;
        timerManager.scheduleTimer(deadline, this::onFlushTimer);
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
        }
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
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(length);
        buffer.putBytes(0, buf, offset, length);

        int pos = 0;

        // Read order ID generator
        long orderIdGen = buffer.getLong(pos);
        pos += 8;
        engine.setOrderIdGenerator(orderIdGen);
        System.out.println("[SNAPSHOT] Restored OrderIdGenerator: " + orderIdGen);

        // Read trade ID generator (for event publisher)
        long tradeIdGen = buffer.getLong(pos);
        pos += 8;
        eventPublisher.setTradeIdGenerator(tradeIdGen);
        System.out.println("[SNAPSHOT] Restored TradeIdGenerator: " + tradeIdGen);

        // Read number of markets
        int numMarkets = buffer.getInt(pos);
        pos += 4;
        System.out.println("[SNAPSHOT] Restoring " + numMarkets + " markets");

        for (int m = 0; m < numMarkets; m++) {
            int marketId = buffer.getInt(pos);
            pos += 4;

            DirectMatchingEngine matchingEngine = engine.getEngine(marketId);
            if (matchingEngine == null) {
                System.out.println("[SNAPSHOT] WARNING: No engine for market " + marketId + ", skipping");
                // Need to skip this market's data to continue parsing
                int numBidOrders = buffer.getInt(pos);
                pos += 4;
                pos += numBidOrders * 4 * 8; // 4 longs per order
                int numAskOrders = buffer.getInt(pos);
                pos += 4;
                pos += numAskOrders * 4 * 8;
                continue;
            }

            // Read bid orders
            int numBidOrders = buffer.getInt(pos);
            pos += 4;
            long[] bidOrders = new long[numBidOrders * 4];
            for (int i = 0; i < bidOrders.length; i++) {
                bidOrders[i] = buffer.getLong(pos);
                pos += 8;
            }

            // Read ask orders
            int numAskOrders = buffer.getInt(pos);
            pos += 4;
            long[] askOrders = new long[numAskOrders * 4];
            for (int i = 0; i < askOrders.length; i++) {
                askOrders[i] = buffer.getLong(pos);
                pos += 8;
            }

            int rejected = matchingEngine.restoreFromSnapshot(bidOrders, askOrders);
            System.out.println("[SNAPSHOT] Market " + marketId + ": restored " + numBidOrders + " bids, " + numAskOrders + " asks");
            if (rejected > 0) {
                System.err.println("[SNAPSHOT] ERROR: Market " + marketId + ": " + rejected
                    + " orders DROPPED during restore (geometry mismatch?) — state loss!");
            }
        }

        // Restore ONLY the timer correlation counter (so newly-armed timer ids stay
        // monotonic); backward-compatible: old snapshots won't have this. Deliberately do
        // NOT re-arm/restore the flush timer here. The flush timer is armed fresh once this
        // node becomes LEADER (onRoleChange resets the flag; onSessionOpen/onSessionMessage
        // arm it). Restoring it here used to leave flushTimerScheduled=true with a chain that
        // never fired after a recover-into-leader, silently killing the egress keep-warm and
        // causing gateway/OMS stale-egress reconnect loops. A stale pending timer that Aeron
        // re-delivers simply logs one harmless "unknown timer" and does not reschedule.
        if (pos + 8 <= length) {
            long timerCorrelationId = buffer.getLong(pos);
            pos += 8;
            timerManager.setCorrelationId(timerCorrelationId);
            System.out.println("[SNAPSHOT] Restored TimerCorrelationId (counter only): " + timerCorrelationId);
        }

        System.out.println("[SNAPSHOT] Snapshot load complete. Processed " + pos + " bytes of " + length);
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
        context.setClusterTime(timestamp);
        clientSessions.addSession(session);
        System.out.println("New client session connected: " + session.id());

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
        System.out.println("Client session closed: " + session.id() + " reason=" + closeReason);
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

        try {
            sbeDemuxer.dispatch(buffer, offset, length, timestamp);
        } catch (Exception e) {
            System.err.println("ORDER ERROR: " + e.getMessage());
            throw new RuntimeException(e);
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
        timerManager.onTimerEvent(correlationId, timestamp);
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        System.out.println("[SNAPSHOT] onTakeSnapshot called");

        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
        int pos = 0;

        // Write order ID generator
        long orderId = engine.getOrderIdGenerator();
        buffer.putLong(pos, orderId);
        pos += 8;
        System.out.println("[SNAPSHOT] OrderIdGenerator: " + orderId);

        // Write trade ID generator (for event publisher)
        long tradeId = eventPublisher.getTradeIdGenerator();
        buffer.putLong(pos, tradeId);
        pos += 8;
        System.out.println("[SNAPSHOT] TradeIdGenerator: " + tradeId);

        // Get all engines
        Int2ObjectHashMap<DirectMatchingEngine> engines = engine.getEngines();

        // Write number of markets
        buffer.putInt(pos, engines.size());
        pos += 4;
        System.out.println("[SNAPSHOT] Markets: " + engines.size());

        // Write each market's orders
        Int2ObjectHashMap<DirectMatchingEngine>.KeyIterator keyIt = engines.keySet().iterator();
        while (keyIt.hasNext()) {
            int marketId = keyIt.nextInt();
            DirectMatchingEngine matchingEngine = engines.get(marketId);

            // Write market ID
            buffer.putInt(pos, marketId);
            pos += 4;

            // Get and write bid orders
            long[] bidOrders = matchingEngine.getBidOrders();
            int numBidOrders = bidOrders.length / 4;
            buffer.putInt(pos, numBidOrders);
            pos += 4;
            for (long value : bidOrders) {
                buffer.putLong(pos, value);
                pos += 8;
            }

            // Get and write ask orders
            long[] askOrders = matchingEngine.getAskOrders();
            int numAskOrders = askOrders.length / 4;
            buffer.putInt(pos, numAskOrders);
            pos += 4;
            for (long value : askOrders) {
                buffer.putLong(pos, value);
                pos += 8;
            }

            System.out.println("[SNAPSHOT] Market " + marketId + ": " + numBidOrders + " bids, " + numAskOrders + " asks");
        }

        // Write timer correlation ID (for replay timer chain continuity)
        long timerCorrelationId = timerManager.getCorrelationId();
        buffer.putLong(pos, timerCorrelationId);
        pos += 8;
        System.out.println("[SNAPSHOT] TimerCorrelationId: " + timerCorrelationId);

        System.out.println("[SNAPSHOT] Total buffer size: " + pos + " bytes");

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
        System.out.println("SERVICE onRoleChange: " + newRole);
        System.out.flush();
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
        if (snapshotArchive != null) {
            CloseHelper.quietClose(snapshotArchive);
            snapshotArchive = null;
        }
        eventPublisher.shutdown();
        engine.close();
    }
}