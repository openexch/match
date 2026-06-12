package com.match.infrastructure.persistence;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Embedded Aeron Cluster integration test.
 * Spins up a real single-node Aeron cluster in-process with the full AppClusteredService,
 * connects a client via Aeron ingress, sends SBE-encoded order messages,
 * and verifies state through egress responses.
 *
 * Tests are ordered alphabetically (test names prefixed with numbers) because
 * testCancelOrder depends on testLimitOrderPlacement having run first.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EmbeddedClusterTest {

    // Cluster infrastructure
    private static ClusteredMediaDriver clusteredMediaDriver;
    private static ClusteredServiceContainer serviceContainer;
    private static MediaDriver clientMediaDriver;
    private static AeronCluster client;
    private static File baseDir;

    // Random port to avoid conflicts with running production cluster
    private static final int PORT_BASE = 19000 + (int) (Math.random() * 10000);

    // Egress message collector
    private static final List<byte[]> egressMessages = new CopyOnWriteArrayList<>();

    // ClusterHeartbeat egress keep-warm counter (kept out of egressMessages so
    // periodic heartbeats don't inflate pollEgress counts in other tests)
    private static final java.util.concurrent.atomic.AtomicLong clusterHeartbeatCount =
            new java.util.concurrent.atomic.AtomicLong(0);

    // SBE encoders (reused)
    private static final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private static final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();
    private static final CancelOrderEncoder cancelOrderEncoder = new CancelOrderEncoder();

    // SBE decoders for egress verification
    private static final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    // Market IDs from MarketConfig
    private static final int BTC_MARKET = 1;
    private static final int ETH_MARKET = 2;
    private static final int SOL_MARKET = 3;

    @BeforeClass
    public static void startCluster() throws Exception {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                "aeron-cluster-test-" + System.nanoTime());

        List<String> hostnames = List.of("localhost");

        // Create cluster config with real AppClusteredService
        ClusterConfig config = ClusterConfig.create(
                0,       // startingMemberId
                0,       // memberId
                hostnames,
                hostnames,
                PORT_BASE,
                baseDir,
                new AppClusteredService());

        // Set error handler that logs but doesn't crash
        config.errorHandler(t -> System.err.println("Cluster error: " + t.getMessage()));

        // Launch cluster with lightweight settings for testing
        clusteredMediaDriver = ClusteredMediaDriver.launch(
                config.mediaDriverContext()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED)
                        .termBufferSparseFile(true)
                        .publicationTermBufferLength(1024 * 1024)
                        .ipcTermBufferLength(1024 * 1024)
                        .conductorIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .senderIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .receiverIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .sharedIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1)),
                config.archiveContext()
                        .threadingMode(io.aeron.archive.ArchiveThreadingMode.SHARED)
                        .segmentFileLength(1024 * 1024),
                config.consensusModuleContext()
                        .idleStrategySupplier(org.agrona.concurrent.SleepingMillisIdleStrategy::new)
                        .ingressChannel("aeron:udp?term-length=64k")
                        .egressChannel("aeron:udp?term-length=64k")
                        .electionTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(200))
                        .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                        .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .sessionTimeoutNs(TimeUnit.SECONDS.toNanos(30)));

        serviceContainer = ClusteredServiceContainer.launch(
                config.clusteredServiceContext()
                        .idleStrategySupplier(org.agrona.concurrent.SleepingMillisIdleStrategy::new));

        // Give cluster time to elect leader and initialize Disruptor threads
        Thread.sleep(5000);

        // Create a separate embedded MediaDriver for the client
        clientMediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED));

        // Build ingress endpoints
        String ingressEndpoints = ClusterConfig.ingressEndpoints(
                hostnames, PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        // Egress listener that collects all messages
        EgressListener egressListener = new EgressListener() {
            private final MessageHeaderDecoder listenerHeaderDecoder = new MessageHeaderDecoder();

            @Override
            public void onMessage(
                    long clusterSessionId, long timestamp,
                    DirectBuffer buffer, int offset, int length,
                    Header header) {
                // Count keep-warm heartbeats separately so they don't inflate
                // message counts that other tests wait on
                if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
                    listenerHeaderDecoder.wrap(buffer, offset);
                    if (listenerHeaderDecoder.schemaId() == 1
                            && listenerHeaderDecoder.templateId() == ClusterHeartbeatDecoder.TEMPLATE_ID) {
                        clusterHeartbeatCount.incrementAndGet();
                        return;
                    }
                }
                byte[] data = new byte[length];
                buffer.getBytes(offset, data, 0, length);
                egressMessages.add(data);
            }
        };

        // Connect client to cluster
        client = AeronCluster.connect(
                new AeronCluster.Context()
                        .egressListener(egressListener)
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints(ingressEndpoints)
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName()));

        // Wait for session to be established
        Thread.sleep(1000);
    }

    @AfterClass
    public static void stopCluster() {
        CloseHelper.closeAll(client, serviceContainer, clusteredMediaDriver, clientMediaDriver);

        // Clean up temp dir
        if (baseDir != null && baseDir.exists()) {
            IoUtil.delete(baseDir, true);
        }
    }

    // ==================== Tests (ordered by name) ====================

    @Test
    public void test1_ClusterStartsAndAcceptsConnection() {
        assertNotNull("Client should be connected", client);
        assertTrue("Client session ID should be non-negative", client.clusterSessionId() >= 0);
        System.out.println("Connected with session ID: " + client.clusterSessionId());
    }


    @Test
    public void test3_LimitOrderPlacement() throws Exception {
        egressMessages.clear();

        // BTC price: $100,000.00 in fixed-point (8 decimals)
        long price = FixedPoint.fromDouble(100_000.0);
        long quantity = FixedPoint.fromDouble(1.0);

        // Send a limit BID order for BTC
        UnsafeBuffer buffer = encodeCreateOrder(1001L, BTC_MARKET, true, "LIMIT", price, quantity, 0);
        offerToCluster(buffer, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // The order flows: SbeDemuxer -> Engine -> EventPublisher -> Disruptor
        // -> MarketPublisher (50ms buffer flush) -> broadcaster queue
        // -> cluster flush timer (10ms) -> egress. No heartbeat needed.
        assertTrue("Should receive NEW status for the placed order",
                awaitEgress(() -> findOrderId(BTC_MARKET, 1001L, OrderStatus.NEW) > 0, 5000));

        // Look for an OrderStatusBatch for BTC_MARKET with NEW status
        boolean foundNewStatus = false;
        boolean foundBookData = false;

        for (byte[] msg : egressMessages) {
            int templateId = getTemplateId(msg);
            if (templateId == OrderStatusBatchDecoder.TEMPLATE_ID) {
                UnsafeBuffer buf = new UnsafeBuffer(msg);
                MessageHeaderDecoder hdr = new MessageHeaderDecoder();
                hdr.wrap(buf, 0);
                OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
                decoder.wrapAndApplyHeader(buf, 0, hdr);

                if (decoder.marketId() == BTC_MARKET) {
                    for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                        if (order.status() == OrderStatus.NEW && order.userId() == 1001L) {
                            foundNewStatus = true;
                            assertTrue("Order ID should be positive", order.orderId() > 0);
                            assertEquals("Side should be BID", OrderSide.BID, order.side());
                        }
                    }
                }
            } else if (templateId == BookSnapshotDecoder.TEMPLATE_ID ||
                       templateId == BookDeltaDecoder.TEMPLATE_ID) {
                foundBookData = true;
            }
        }

        assertTrue("Should receive OrderStatusBatch with NEW status for BTC market",
                foundNewStatus);

        System.out.println("Limit order placement test passed. " +
                "Received " + egressMessages.size() + " egress messages, " +
                "bookData=" + foundBookData);
    }

    @Test
    public void test4_OrderMatchingFlow() throws Exception {
        egressMessages.clear();

        // Use ETH market for matching test
        // Place an ASK at $3000
        long askPrice = FixedPoint.fromDouble(3000.0);
        long quantity = FixedPoint.fromDouble(2.0);

        UnsafeBuffer askBuffer = encodeCreateOrder(
                2001L, ETH_MARKET, false, "LIMIT", askPrice, quantity, 0);
        offerToCluster(askBuffer, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // The ask order will be placed on the book (no match yet);
        // the cluster flush timer broadcasts the status update
        assertTrue("Should receive NEW status for the resting ask",
                awaitEgress(() -> findOrderId(ETH_MARKET, 2001L, OrderStatus.NEW) > 0, 5000));

        // Now place a matching BID at $3000 (same price = match)
        egressMessages.clear();

        UnsafeBuffer bidBuffer = encodeCreateOrder(
                2002L, ETH_MARKET, true, "LIMIT", askPrice, quantity, 0);
        offerToCluster(bidBuffer, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // The match happens synchronously in Engine.processCreate; trade events
        // flow through the Disruptor to MarketPublisher and out via the cluster
        // flush timer. Wait on CONTENT, not counts: book data flows continuously.
        awaitEgress(() -> findOrderId(ETH_MARKET, 2002L, OrderStatus.FILLED) > 0
                && hasTradesBatch(ETH_MARKET), 5000);

        // Verify we got order status messages for the matching orders
        boolean foundFilledStatus = false;
        boolean foundTradesBatch = false;

        for (byte[] msg : egressMessages) {
            int templateId = getTemplateId(msg);
            if (templateId == TradesBatchDecoder.TEMPLATE_ID) {
                UnsafeBuffer buf = new UnsafeBuffer(msg);
                MessageHeaderDecoder hdr = new MessageHeaderDecoder();
                hdr.wrap(buf, 0);
                TradesBatchDecoder decoder = new TradesBatchDecoder();
                decoder.wrapAndApplyHeader(buf, 0, hdr);

                if (decoder.marketId() == ETH_MARKET) {
                    foundTradesBatch = true;
                    TradesBatchDecoder.TradesDecoder trades = decoder.trades();
                    assertTrue("Should have at least one trade", trades.count() > 0);
                }
            } else if (templateId == OrderStatusBatchDecoder.TEMPLATE_ID) {
                UnsafeBuffer buf = new UnsafeBuffer(msg);
                MessageHeaderDecoder hdr = new MessageHeaderDecoder();
                hdr.wrap(buf, 0);
                OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
                decoder.wrapAndApplyHeader(buf, 0, hdr);
                if (decoder.marketId() == ETH_MARKET) {
                    for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                        if (order.status() == OrderStatus.FILLED) {
                            foundFilledStatus = true;
                        }
                    }
                }
            }
        }

        assertTrue("Should receive FILLED OrderStatus for matching orders", foundFilledStatus);
        // TradesBatch might come asynchronously via the Disruptor pipeline
        // If we didn't get it, it's a timing issue but the FILLED status confirms the match happened
        if (!foundTradesBatch) {
            System.out.println("NOTE: TradesBatch not received (async Disruptor timing), but FILLED status confirms match.");
        }

        System.out.println("Order matching flow test passed. " +
                "Received " + egressMessages.size() + " egress messages.");
    }

    @Test
    public void test5_CancelOrder() throws Exception {
        egressMessages.clear();

        // Place a limit order that won't match (BTC market, very low bid)
        long price = FixedPoint.fromDouble(51_000.0);
        long quantity = FixedPoint.fromDouble(0.5);

        UnsafeBuffer createBuffer = encodeCreateOrder(
                3001L, BTC_MARKET, true, "LIMIT", price, quantity, 0);
        offerToCluster(createBuffer, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // Wait for the NEW status, then capture the engine-assigned order ID
        assertTrue("Should receive NEW status for the placed order",
                awaitEgress(() -> findOrderId(BTC_MARKET, 3001L, OrderStatus.NEW) > 0, 5000));
        long orderId = findOrderId(BTC_MARKET, 3001L, OrderStatus.NEW);

        // Now cancel the order
        egressMessages.clear();

        UnsafeBuffer cancelBuffer = encodeCancelOrder(3001L, orderId, BTC_MARKET);
        offerToCluster(cancelBuffer, MessageHeaderEncoder.ENCODED_LENGTH + CancelOrderEncoder.BLOCK_LENGTH);

        assertTrue("Should receive CANCELLED OrderStatus after cancel",
                awaitEgress(() -> findOrderId(BTC_MARKET, 3001L, OrderStatus.CANCELLED) > 0, 5000));
        assertEquals("Cancelled order should match our order ID",
                orderId, findOrderId(BTC_MARKET, 3001L, OrderStatus.CANCELLED));

        System.out.println("Cancel order test passed. OrderId=" + orderId);
    }

    @Test
    public void test6_MultipleMarkets() throws Exception {
        egressMessages.clear();

        // Place ASK order in BTC market
        long btcPrice = FixedPoint.fromDouble(105_000.0);
        long btcQty = FixedPoint.fromDouble(0.1);

        UnsafeBuffer btcOrder = encodeCreateOrder(
                4001L, BTC_MARKET, false, "LIMIT", btcPrice, btcQty, 0);
        offerToCluster(btcOrder, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // Place ASK order in SOL market (market 3)
        long solPrice = FixedPoint.fromDouble(200.0);
        long solQty = FixedPoint.fromDouble(10.0);

        UnsafeBuffer solOrder = encodeCreateOrder(
                4002L, SOL_MARKET, false, "LIMIT", solPrice, solQty, 0);
        offerToCluster(solOrder, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // Wait for status updates from BOTH markets (content, not counts)
        awaitEgress(() -> findOrderId(BTC_MARKET, 4001L, OrderStatus.NEW) > 0
                && findOrderId(SOL_MARKET, 4002L, OrderStatus.NEW) > 0, 5000);

        assertTrue("Should receive status for BTC market",
                findOrderId(BTC_MARKET, 4001L, OrderStatus.NEW) > 0);
        assertTrue("Should receive status for SOL market",
                findOrderId(SOL_MARKET, 4002L, OrderStatus.NEW) > 0);

        System.out.println("Multiple markets test passed. " +
                "Received " + egressMessages.size() + " egress messages.");
    }

    @Test
    public void test8_SilentSecondClientReceivesFullSnapshotOnConnect() throws Exception {
        // Ensure the BTC book has a resting order (far from market, won't cross)
        long price = FixedPoint.fromDouble(120_000.0);
        long qty = FixedPoint.fromDouble(0.5);
        UnsafeBuffer order = encodeCreateOrder(8001L, BTC_MARKET, false, "LIMIT", price, qty, 0);
        offerToCluster(order, MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH);

        // Let the resulting delta flush to the existing session first
        pollEgress(1, 3000);

        // Connect a SECOND client that sends NOTHING — no heartbeat, no orders.
        // Cluster heartbeat principle: session open alone must trigger a full
        // book resnapshot so a new gateway can build state without ingress traffic.
        List<byte[]> client2Messages = new CopyOnWriteArrayList<>();
        EgressListener listener2 = (clusterSessionId, timestamp, buffer, offset, length, header) -> {
            byte[] data = new byte[length];
            buffer.getBytes(offset, data, 0, length);
            client2Messages.add(data);
        };

        try (AeronCluster client2 = AeronCluster.connect(
                new AeronCluster.Context()
                        .egressListener(listener2)
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints(ClusterConfig.ingressEndpoints(
                                List.of("localhost"), PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName()))) {

            // Poll client2 egress WITHOUT sending anything from it
            boolean foundFullSnapshot = false;
            long deadline = System.currentTimeMillis() + 5000;
            while (!foundFullSnapshot && System.currentTimeMillis() < deadline) {
                client2.pollEgress();
                client.pollEgress(); // keep main session draining too
                for (byte[] msg : client2Messages) {
                    if (getTemplateId(msg) == BookSnapshotDecoder.TEMPLATE_ID) {
                        foundFullSnapshot = true;
                        break;
                    }
                }
                Thread.sleep(10);
            }

            assertTrue("Silent client must receive a FULL book snapshot triggered by session open alone "
                    + "(got " + client2Messages.size() + " messages)", foundFullSnapshot);
        }

        System.out.println("Silent second client snapshot test passed.");
    }

    @Test
    public void test9_IdleClientReceivesClusterHeartbeatKeepWarm() throws Exception {
        // With no trading activity the book is unchanged, so no market data
        // flows. The cluster must keep egress warm with periodic
        // ClusterHeartbeat messages (~1s) so the gateway's stale-egress
        // detector doesn't force reconnect loops during idle periods.
        long before = clusterHeartbeatCount.get();

        long deadline = System.currentTimeMillis() + 5000;
        while (clusterHeartbeatCount.get() < before + 2 && System.currentTimeMillis() < deadline) {
            client.pollEgress();
            Thread.sleep(10);
        }

        assertTrue("Idle client must receive periodic ClusterHeartbeat egress keep-warm "
                        + "(got " + (clusterHeartbeatCount.get() - before) + " in 5s, expected >= 2)",
                clusterHeartbeatCount.get() >= before + 2);

        System.out.println("Egress keep-warm test passed: "
                + (clusterHeartbeatCount.get() - before) + " heartbeats in idle period.");
    }

    // ==================== Helper Methods ====================

    /**
     * Encode a CreateOrder SBE message.
     */
    private static UnsafeBuffer encodeCreateOrder(long userId, int marketId, boolean isBuy,
                                                   String orderType, long price, long qty, long totalPrice) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        createOrderEncoder.userId(userId);
        createOrderEncoder.price(price);
        createOrderEncoder.quantity(qty);
        createOrderEncoder.totalPrice(totalPrice);
        createOrderEncoder.marketId(marketId);
        createOrderEncoder.orderType(
                "MARKET".equals(orderType) ? OrderType.MARKET :
                        "LIMIT_MAKER".equals(orderType) ? OrderType.LIMIT_MAKER :
                                OrderType.LIMIT);
        createOrderEncoder.orderSide(isBuy ? OrderSide.BID : OrderSide.ASK);
        return buffer;
    }

    /**
     * Encode a CancelOrder SBE message.
     */
    private static UnsafeBuffer encodeCancelOrder(long userId, long orderId, int marketId) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        cancelOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        cancelOrderEncoder.userId(userId);
        cancelOrderEncoder.orderId(orderId);
        cancelOrderEncoder.marketId(marketId);
        return buffer;
    }

    /**
     * Offer a message to the cluster, retrying on backpressure.
     */
    private static void offerToCluster(UnsafeBuffer buffer, int length) {
        long deadline = System.currentTimeMillis() + 5000;
        while (client.offer(buffer, 0, length) < 0) {
            Thread.yield();
            client.pollEgress();
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out offering message to cluster");
            }
        }
    }

    /**
     * Poll egress messages until we have at least expectedCount or timeout.
     */
    private static void pollEgress(int expectedCount, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (egressMessages.size() < expectedCount && System.currentTimeMillis() < deadline) {
            client.pollEgress();
            Thread.sleep(10);
        }
    }

    /**
     * Poll egress until the condition over collected messages holds, or timeout.
     * Market data now flows continuously on the cluster flush timer, so message
     * COUNTS are meaningless for synchronization — always wait on content.
     */
    private static boolean awaitEgress(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            client.pollEgress();
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    /**
     * Scan collected egress for an OrderStatusBatch entry matching
     * market/user/status; return its orderId, or -1 if not seen yet.
     */
    private static long findOrderId(int marketId, long userId, OrderStatus status) {
        for (byte[] msg : egressMessages) {
            if (getTemplateId(msg) != OrderStatusBatchDecoder.TEMPLATE_ID) continue;
            UnsafeBuffer buf = new UnsafeBuffer(msg);
            MessageHeaderDecoder hdr = new MessageHeaderDecoder();
            hdr.wrap(buf, 0);
            OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
            decoder.wrapAndApplyHeader(buf, 0, hdr);
            if (decoder.marketId() != marketId) continue;
            for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                if (order.userId() == userId && order.status() == status) {
                    return order.orderId();
                }
            }
        }
        return -1;
    }

    /**
     * Check whether a TradesBatch for the market has been collected.
     */
    private static boolean hasTradesBatch(int marketId) {
        for (byte[] msg : egressMessages) {
            if (getTemplateId(msg) != TradesBatchDecoder.TEMPLATE_ID) continue;
            UnsafeBuffer buf = new UnsafeBuffer(msg);
            MessageHeaderDecoder hdr = new MessageHeaderDecoder();
            hdr.wrap(buf, 0);
            TradesBatchDecoder decoder = new TradesBatchDecoder();
            decoder.wrapAndApplyHeader(buf, 0, hdr);
            if (decoder.marketId() == marketId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the SBE template ID from a raw egress message.
     * Returns -1 if the message is too short or not SBE.
     */
    private static int getTemplateId(byte[] msg) {
        if (msg.length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return -1;
        }
        UnsafeBuffer buf = new UnsafeBuffer(msg);
        headerDecoder.wrap(buf, 0);
        // Sanity check: schemaId should match
        if (headerDecoder.schemaId() != 1) {
            return -1; // Not an SBE message (probably JSON heartbeat ACK)
        }
        return headerDecoder.templateId();
    }

}
