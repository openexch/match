package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
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
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import com.match.infrastructure.Logger;

import java.nio.charset.StandardCharsets;

public class AppClusteredService implements ClusteredService {
    private static final Logger logger = Logger.getLogger(AppClusteredService.class);

    private final Engine engine = new Engine();
    private final ClientSessions clientSessions = new ClientSessions();
    private final SessionMessageContextImpl context = new SessionMessageContextImpl(clientSessions);
    private final TimerManager timerManager = new TimerManager(context);
    private final SbeDemuxer sbeDemuxer = new SbeDemuxer(engine);

    // Event publishing infrastructure
    private final MatchEventPublisher eventPublisher = new MatchEventPublisher();
    private final SubscriptionManager subscriptionManager = new SubscriptionManager();

    // Store cluster reference for snapshot idle strategy and broadcasting
    private Cluster cluster;

    // Buffer for broadcasting market data via Aeron egress
    private final UnsafeBuffer broadcastBuffer = new UnsafeBuffer(new byte[32 * 1024]); // 32KB for JSON messages

    /**
     * MarketDataBroadcaster implementation that sends via Aeron cluster egress.
     * This broadcasts to all connected Aeron clients (including gateway).
     */
    private final MarketDataBroadcaster aeronBroadcaster = new MarketDataBroadcaster() {
        @Override
        public void broadcast(String jsonMessage) {
            if (clientSessions.getAllSessions().isEmpty()) {
                return;
            }

            byte[] bytes = jsonMessage.getBytes(StandardCharsets.UTF_8);
            broadcastBuffer.putBytes(0, bytes);

            // Broadcast to all connected Aeron clients
            for (ClientSession session : clientSessions.getAllSessions()) {
                int retries = 0;
                while (retries < 3) {
                    long result = session.offer(broadcastBuffer, 0, bytes.length);
                    if (result > 0) {
                        break; // Success
                    } else if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                        retries++;
                        if (cluster != null) {
                            cluster.idleStrategy().idle();
                        }
                    } else {
                        // NOT_CONNECTED or other error - skip this session
                        break;
                    }
                }
            }
        }

        @Override
        public boolean hasSubscribers() {
            return !clientSessions.getAllSessions().isEmpty();
        }
    };

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        this.cluster = cluster;
        context.setIdleStrategy(cluster.idleStrategy());
        timerManager.setCluster(cluster);

        // Load snapshot if available
        if (snapshotImage != null) {
            loadSnapshot(snapshotImage);
        }

        // Initialize event publishing for BTC-USD market
        initializeEventPublishing();
    }

    /**
     * Initialize LMAX Disruptor-based event publishing.
     * Market data is broadcast via Aeron egress to connected clients (gateway).
     * Gateway then relays to WebSocket clients.
     */
    private void initializeEventPublishing() {
        try {
            // Create per-market publisher for BTC-USD
            MarketPublisher btcPublisher = new MarketPublisher(
                Engine.MARKET_BTC_USD,
                "BTC-USD",
                subscriptionManager
            );

            // Wire matching engine to publisher for direct order book access
            // Order book snapshots are fetched every 50ms on publisher thread (not matching engine thread)
            btcPublisher.setMatchingEngine(engine.getEngine(Engine.MARKET_BTC_USD));

            // Use Aeron broadcaster instead of WebSocket
            // Market data flows: Cluster → Aeron egress → Gateway → WebSocket → UI
            btcPublisher.setBroadcaster(aeronBroadcaster);

            // Initialize Disruptor ring buffer for BTC-USD market
            eventPublisher.initMarket(Engine.MARKET_BTC_USD, btcPublisher);

            // Wire publisher to engine (for trade events only)
            engine.setEventPublisher(eventPublisher);

            // Start the Disruptor (creates publisher threads)
            eventPublisher.start();

            logger.info("Event publishing initialized with Aeron broadcaster (no WebSocket on cluster node)");
        } catch (Exception e) {
            logger.warn("Failed to initialize event publishing: " + e.getMessage());
            // Continue without publishing - engine will still work
        }
    }

    private void loadSnapshot(final Image snapshotImage) {
        System.out.println("Loading snapshot...");
        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();

        final FragmentHandler handler = (buf, offset, length, header) -> {
            // Copy to our buffer for processing
            buffer.putBytes(0, buf, offset, length);

            int pos = 0;

            // Read order ID generator
            long orderIdGen = buffer.getLong(pos);
            pos += 8;
            engine.setOrderIdGenerator(orderIdGen);

            // Read trade ID generator (for event publisher)
            long tradeIdGen = buffer.getLong(pos);
            pos += 8;
            eventPublisher.setTradeIdGenerator(tradeIdGen);

            // Read number of markets
            int numMarkets = buffer.getInt(pos);
            pos += 4;

            for (int m = 0; m < numMarkets; m++) {
                int marketId = buffer.getInt(pos);
                pos += 4;

                DirectMatchingEngine matchingEngine = engine.getEngine(marketId);
                if (matchingEngine == null) continue;

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

                // Restore the engine state
                matchingEngine.restoreFromSnapshot(bidOrders, askOrders);
                System.out.printf("Restored market %d: %d bid orders, %d ask orders%n",
                    marketId, numBidOrders, numAskOrders);
            }
        };

        while (snapshotImage.poll(handler, 1) > 0) {
            // Continue polling until no more fragments
        }

        System.out.println("Snapshot loaded. OrderId: " + engine.getOrderIdGenerator() +
                          ", TradeId: " + eventPublisher.getTradeIdGenerator());
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
        try {
            // Pass cluster timestamp (in nanoseconds) for order timing
            sbeDemuxer.dispatch(buffer, offset, length, timestamp);
        } catch (Exception e) {
            System.out.printf("order exception %s", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
        context.setClusterTime(timestamp);
        timerManager.onTimerEvent(correlationId, timestamp);
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        System.out.println("Taking snapshot...");

        final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer();
        int pos = 0;

        // Write order ID generator
        buffer.putLong(pos, engine.getOrderIdGenerator());
        pos += 8;

        // Write trade ID generator (for event publisher)
        buffer.putLong(pos, eventPublisher.getTradeIdGenerator());
        pos += 8;

        // Get all engines
        Int2ObjectHashMap<DirectMatchingEngine> engines = engine.getEngines();

        // Write number of markets
        buffer.putInt(pos, engines.size());
        pos += 4;

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

            System.out.printf("Snapshot market %d: %d bid orders, %d ask orders%n",
                marketId, numBidOrders, numAskOrders);
        }

        // Offer to publication with proper idle strategy
        while (snapshotPublication.offer(buffer, 0, pos) < 0) {
            cluster.idleStrategy().idle();
        }

        System.out.println("Snapshot complete. Size: " + pos + " bytes, OrderId: " + engine.getOrderIdGenerator() +
                          ", TradeId: " + eventPublisher.getTradeIdGenerator());
    }

    @Override
    public void onRoleChange(final Role newRole) {
        System.out.println("role changed: " + newRole);
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        logger.info("Terminating cluster service gracefully");

        // Shutdown event publisher (Disruptor)
        if (eventPublisher != null) {
            eventPublisher.shutdown();
        }

        // Close all active sessions immediately
        clientSessions.getAllSessions().forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing session %d: %s", session, e.getMessage());
            }
        });

        // Close engine
        engine.close();

        logger.info("Cluster service terminated gracefully");
    }
} 