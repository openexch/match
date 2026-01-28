package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.MarketConfig;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.application.publisher.MatchEventPublisher;
import com.match.infrastructure.websocket.MarketPublisher;
import com.match.infrastructure.websocket.SubscriptionManager;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.logbuffer.Header;
// FragmentHandler replaced with FragmentAssembler for proper multi-fragment reassembly
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
// UnsafeBuffer removed — using ExpandableDirectByteBuffer for broadcast to handle large messages
import org.agrona.collections.Int2ObjectHashMap;
import com.match.infrastructure.Logger;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.GatewayHeartbeatDecoder;

import static com.match.infrastructure.InfrastructureConstants.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class AppClusteredService implements ClusteredService {
    private static final Logger logger = Logger.getLogger(AppClusteredService.class);

    private final Engine engine = new Engine();
    private final ClientSessions clientSessions = new ClientSessions();
    private final SessionMessageContextImpl context = new SessionMessageContextImpl(clientSessions);
    private final TimerManager timerManager = new TimerManager(context);
    private final SbeDemuxer sbeDemuxer = new SbeDemuxer(engine);

    // Gateway heartbeat tracking - only broadcast to active gateway
    // Both fields updated atomically via gatewayLock to prevent stale reads
    private long gatewaySessionId = -1;  // -1 = no gateway connected
    private long gatewayLastHeartbeatMs = 0;
    private final Object gatewayLock = new Object();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final GatewayHeartbeatDecoder heartbeatDecoder = new GatewayHeartbeatDecoder();

    // Event publishing infrastructure
    private final MatchEventPublisher eventPublisher = new MatchEventPublisher();
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();

    // Store cluster reference for snapshot idle strategy and broadcasting
    private Cluster cluster;

    // Buffer for broadcasting market data via Aeron egress
    // Uses ExpandableDirectByteBuffer to handle large book snapshots (can exceed 32KB with many orders)
    private final org.agrona.ExpandableDirectByteBuffer broadcastBuffer = new org.agrona.ExpandableDirectByteBuffer(64 * 1024); // 64KB initial, grows as needed

    // Queue for market data messages (SBE binary, thread-safe for producer on Disruptor thread)
    // Bounded to prevent OOM if cluster egress backs up
    private static final int MARKET_DATA_QUEUE_CAPACITY = 10_000;
    private final Queue<QueuedMessage> marketDataQueue = new ArrayBlockingQueue<>(MARKET_DATA_QUEUE_CAPACITY);
    private final AtomicLong droppedMessages = new AtomicLong(0);

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
        public void flush() {
            // Broadcast to ALL sessions - each client filters what it needs
            List<ClientSession> sessions = clientSessions.getAllSessions();
            if (sessions.isEmpty()) {
                marketDataQueue.clear();
                return;
            }

            QueuedMessage msg;
            while ((msg = marketDataQueue.poll()) != null) {
                broadcastBuffer.putBytes(0, msg.data, 0, msg.length);

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

    private static final long MARKET_DATA_FLUSH_INTERVAL_MS = 10;

    private void scheduleMarketDataFlush() {
        long deadline = System.currentTimeMillis() + MARKET_DATA_FLUSH_INTERVAL_MS;
        timerManager.scheduleTimer(deadline, () -> {
            if (cluster != null && cluster.role() == Cluster.Role.LEADER) {
                aeronBroadcaster.flush();
            }
            // Reschedule
            scheduleMarketDataFlush();
        });
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

        int fragmentCount = 0;
        while (snapshotImage.poll(assembler, 10) > 0) {
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

            matchingEngine.restoreFromSnapshot(bidOrders, askOrders);
            System.out.println("[SNAPSHOT] Market " + marketId + ": restored " + numBidOrders + " bids, " + numAskOrders + " asks");
        }

        System.out.println("[SNAPSHOT] Snapshot load complete. Processed " + pos + " bytes of " + length);
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
        context.setClusterTime(timestamp);
        clientSessions.addSession(session);
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason) {
        context.setClusterTime(timestamp);
        clientSessions.removeSession(session);
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

        if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
            headerDecoder.wrap(buffer, offset);
            int templateId = headerDecoder.templateId();

            if (templateId == GatewayHeartbeatDecoder.TEMPLATE_ID) {
                handleGatewayHeartbeat(session, buffer, offset, timestamp);
                return;
            }
        }

        try {
            sbeDemuxer.dispatch(buffer, offset, length, timestamp);
        } catch (Exception e) {
            System.err.println("ORDER ERROR: " + e.getMessage());
            throw new RuntimeException(e);
        }

        // Schedule flush timer lazily (can't schedule from onStart — cluster not ready)
        if (!flushTimerScheduled) {
            scheduleMarketDataFlush();
            flushTimerScheduled = true;
            System.out.println("SERVICE: Market data flush timer scheduled (10ms)");
            System.out.flush();
        }
    }

    // Heartbeat ACK message format (simple JSON)
    private static final String HEARTBEAT_ACK_PREFIX = "{\"type\":\"HEARTBEAT_ACK\",\"ts\":";
    private static final String HEARTBEAT_ACK_SUFFIX = "}";

    // Heartbeat counter for debugging
    private long heartbeatReceivedCount = 0;
    private long lastHeartbeatLogMs = 0;

    /**
     * Handle gateway heartbeat - update gateway session tracking, flush market data, and send ACK.
     * The ACK keeps the gateway's egress liveness tracker happy even during idle periods.
     */
    private void handleGatewayHeartbeat(ClientSession session, DirectBuffer buffer, int offset, long timestamp) {
        heartbeatDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        long prevSessionId;
        synchronized (gatewayLock) {
            prevSessionId = gatewaySessionId;
            gatewaySessionId = session.id();
            gatewayLastHeartbeatMs = System.currentTimeMillis();
        }
        heartbeatReceivedCount++;

        // Log gateway session changes for debugging
        if (prevSessionId != gatewaySessionId) {
            System.out.println("Gateway session updated: " + prevSessionId + " -> " + gatewaySessionId);
        }

        // Periodic heartbeat stats (every 10 seconds)
        long now = System.currentTimeMillis();
        if (now - lastHeartbeatLogMs > 10_000) {
            System.out.println("CLUSTER: heartbeatsReceived=" + heartbeatReceivedCount + ", session=" + gatewaySessionId);
            System.out.flush();
            lastHeartbeatLogMs = now;
        }

        if (cluster != null && cluster.role() == Cluster.Role.LEADER) {
            // Flush any pending market data
            aeronBroadcaster.flush();

            // Send heartbeat ACK to keep gateway egress alive during idle periods
            sendHeartbeatAck(session);
        }
    }

    /**
     * Send heartbeat acknowledgment to gateway.
     * This ensures egress traffic even when there's no market data to send.
     */
    private void sendHeartbeatAck(ClientSession session) {
        String ack = HEARTBEAT_ACK_PREFIX + System.currentTimeMillis() + HEARTBEAT_ACK_SUFFIX;
        byte[] bytes = ack.getBytes(StandardCharsets.UTF_8);
        broadcastBuffer.putBytes(0, bytes);

        // Best-effort send - don't block or retry, just attempt once
        long result = session.offer(broadcastBuffer, 0, bytes.length);
        if (result < 0) {
            System.err.println("Heartbeat ACK failed: result=" + result + ", session=" + session.id());
        }
    }

    /**
     * Check if gateway is alive (received heartbeat within timeout).
     */
    private boolean isGatewayAlive() {
        synchronized (gatewayLock) {
            if (gatewaySessionId < 0) return false;
            long age = System.currentTimeMillis() - gatewayLastHeartbeatMs;
            return age < GATEWAY_TIMEOUT_MS;
        }
    }

    /**
     * Get the gateway session for broadcasting.
     * Returns null if gateway is dead or not connected.
     */
    private ClientSession getGatewaySession() {
        long sessionId;
        synchronized (gatewayLock) {
            if (gatewaySessionId < 0) return null;
            long age = System.currentTimeMillis() - gatewayLastHeartbeatMs;
            if (age >= GATEWAY_TIMEOUT_MS) return null;
            sessionId = gatewaySessionId;
        }
        for (ClientSession session : clientSessions.getAllSessions()) {
            if (session.id() == sessionId) {
                return session;
            }
        }
        return null;
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

        System.out.println("[SNAPSHOT] Total buffer size: " + pos + " bytes");

        long result;
        while ((result = snapshotPublication.offer(buffer, 0, pos)) < 0) {
            cluster.idleStrategy().idle();
        }
        System.out.println("[SNAPSHOT] Publication result: " + result + " (success)");
    }

    @Override
    public void onRoleChange(final Role newRole) {
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        eventPublisher.shutdown();
        engine.close();
    }
} 