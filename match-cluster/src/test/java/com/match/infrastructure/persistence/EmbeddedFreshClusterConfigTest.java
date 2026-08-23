// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.orderbook.OrderRejectReason;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Slice C embedded harness: a REAL single-node Aeron cluster started in CONFIG mode
 * ({@code match.engine.from.config=true} — no engines at boot).
 *
 * <p>Scenario (ordered tests): an order sent BEFORE any EngineConfig must come back as a loud
 * deterministic REJECTED egress — orderId=0 + the command's omsOrderId (so the OMS hold
 * releases) + ENGINE_NOT_CONFIGURED — never a silent drop. Then the logged EngineConfig creates
 * the engines, and the SAME order is accepted as NEW.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class EmbeddedFreshClusterConfigTest {

    private static ClusteredMediaDriver clusteredMediaDriver;
    private static ClusteredServiceContainer serviceContainer;
    private static MediaDriver clientMediaDriver;
    private static AeronCluster client;
    private static File baseDir;
    private static AppClusteredService service;

    private static final int PORT_BASE = 31000 + (int) (Math.random() * 10000);
    private static final List<byte[]> egressMessages = new CopyOnWriteArrayList<>();

    private static final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private static final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    private static final int MARKET = 1;
    private static final long USER = 6001L;
    private static final long OMS_ORDER_ID = 777_001L;

    @BeforeClass
    public static void startConfigModeCluster() throws Exception {
        // MUST be set before the service is constructed — the mode is resolved at construction.
        System.setProperty("match.engine.from.config", "true");

        baseDir = new File(System.getProperty("java.io.tmpdir"),
                "aeron-cluster-configmode-test-" + System.nanoTime());
        List<String> hostnames = List.of("localhost");

        service = new AppClusteredService();
        ClusterConfig config = ClusterConfig.create(0, 0, hostnames, hostnames, PORT_BASE, baseDir, service);
        config.errorHandler(t -> System.err.println("Cluster error: " + t.getMessage()));

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

        Thread.sleep(5000);

        clientMediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED));

        EgressListener egressListener = (clusterSessionId, timestamp, buffer, offset, length, header) -> {
            byte[] data = new byte[length];
            buffer.getBytes(offset, data, 0, length);
            egressMessages.add(data);
        };

        client = AeronCluster.connect(
                new AeronCluster.Context()
                        .egressListener(egressListener)
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints(ClusterConfig.ingressEndpoints(
                                hostnames, PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName()));

        Thread.sleep(1000);
    }

    @AfterClass
    public static void stopCluster() {
        try {
            CloseHelper.closeAll(client, serviceContainer, clusteredMediaDriver, clientMediaDriver);
            if (baseDir != null && baseDir.exists()) {
                IoUtil.delete(baseDir, true);
            }
        } finally {
            // Never leak config mode into other test classes in the same JVM fork.
            System.clearProperty("match.engine.from.config");
        }
    }

    // ==================== Tests (ordered by name) ====================

    @Test
    public void test1_OrderBeforeConfigGetsLoudRejectedEgressWithOmsOrderId() throws Exception {
        egressMessages.clear();

        offerToCluster(encodeCreateOrder(USER, MARKET, OMS_ORDER_ID,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0)));

        assertTrue("An order sent before any EngineConfig must produce a REJECTED egress"
                        + " (never a silent drop)",
                awaitEgress(() -> findReject() != null, 5000));

        RejectSeen reject = findReject();
        assertEquals("orderId must be 0 — no engine ever assigned one", 0L, reject.orderId);
        assertEquals("the command's omsOrderId must ride the reject so the OMS hold releases",
                OMS_ORDER_ID, reject.omsOrderId);
        assertEquals("reason must be ENGINE_NOT_CONFIGURED",
                OrderRejectReason.ENGINE_NOT_CONFIGURED, reject.rejectReason);
        assertEquals("addressed to the order's market", MARKET, reject.marketId);

        System.out.println("Pre-config reject test passed: " + reject);
    }

    @Test
    public void test2_EngineConfigThenSameOrderIsAcceptedAsNew() throws Exception {
        egressMessages.clear();

        // The logged EngineConfig: array impl, one market (id 1) with a BTC-like band.
        offerToCluster(encodeEngineConfig());

        // The SAME order that was rejected in test1 — now it must rest as NEW.
        offerToCluster(encodeCreateOrder(USER, MARKET, OMS_ORDER_ID,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0)));

        assertTrue("After the EngineConfig, the same order must be accepted as NEW",
                awaitEgress(() -> findOrderId(MARKET, USER, OrderStatus.NEW) > 0, 10_000));
        assertTrue("engine-assigned orderId must be positive now",
                findOrderId(MARKET, USER, OrderStatus.NEW) > 0);

        System.out.println("Config-then-accept test passed: orderId="
                + findOrderId(MARKET, USER, OrderStatus.NEW));
    }

    // ==================== Helpers ====================

    /** An encoded ingress frame: the buffer plus its true encoded length. */
    private static final class Frame {
        final UnsafeBuffer buffer;
        final int length;

        Frame(UnsafeBuffer buffer, int length) {
            this.buffer = buffer;
            this.length = length;
        }
    }

    private static Frame encodeCreateOrder(long userId, int marketId, long omsOrderId,
                                           long price, long qty) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.price(price);
        encoder.quantity(qty);
        encoder.marketId(marketId);
        encoder.orderType(OrderType.LIMIT);
        encoder.orderSide(OrderSide.BID);
        encoder.omsOrderId(omsOrderId);
        return new Frame(buffer, MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private static Frame encodeEngineConfig() {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        EngineConfigEncoder encoder = new EngineConfigEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .configVersion(1L)
                .impl(EngineImpl.ARRAY)
                .bookCapacity(4096)
                .maxMatchesPerOrder(100)
                .maxOrdersPerLevel(0);
        encoder.marketsCount(1).next()
                .marketId(MARKET)
                .symbol("BTC-USD")
                .minPrice(FixedPoint.fromDouble(50_000.0))
                .maxPrice(FixedPoint.fromDouble(150_000.0))
                .tickSize(FixedPoint.fromDouble(1.0));
        // EngineConfig carries a repeating group, so the frame length is encodedLength (BLOCK_LENGTH
        // alone would under-count and truncate the markets on the wire).
        return new Frame(buffer, MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    private static void offerToCluster(Frame frame) {
        long deadline = System.currentTimeMillis() + 5000;
        while (client.offer(frame.buffer, 0, frame.length) < 0) {
            Thread.yield();
            client.pollEgress();
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out offering message to cluster");
            }
        }
    }

    private static boolean awaitEgress(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            client.pollEgress();
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class RejectSeen {
        long orderId;
        long omsOrderId;
        int rejectReason;
        int marketId;

        @Override
        public String toString() {
            return "RejectSeen{orderId=" + orderId + " omsOrderId=" + omsOrderId
                    + " rejectReason=" + OrderRejectReason.describe(rejectReason)
                    + " marketId=" + marketId + "}";
        }
    }

    /** The first REJECTED OrderStatusBatch entry for our user, or null if not seen yet. */
    private static RejectSeen findReject() {
        for (byte[] msg : egressMessages) {
            if (getTemplateId(msg) != OrderStatusBatchDecoder.TEMPLATE_ID) continue;
            UnsafeBuffer buf = new UnsafeBuffer(msg);
            MessageHeaderDecoder hdr = new MessageHeaderDecoder();
            hdr.wrap(buf, 0);
            OrderStatusBatchDecoder decoder = new OrderStatusBatchDecoder();
            decoder.wrapAndApplyHeader(buf, 0, hdr);
            int marketId = decoder.marketId();
            for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                if (order.userId() == USER && order.status() == OrderStatus.REJECTED) {
                    RejectSeen seen = new RejectSeen();
                    seen.orderId = order.orderId();
                    seen.omsOrderId = order.omsOrderId();
                    seen.rejectReason = order.rejectReason();
                    seen.marketId = marketId;
                    return seen;
                }
            }
        }
        return null;
    }

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

    private static int getTemplateId(byte[] msg) {
        if (msg.length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return -1;
        }
        UnsafeBuffer buf = new UnsafeBuffer(msg);
        headerDecoder.wrap(buf, 0);
        if (headerDecoder.schemaId() != 1) {
            return -1;
        }
        return headerDecoder.templateId();
    }
}
