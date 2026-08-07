// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.CreateOrderEncoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The acceptance test for the whole point of moving the snapshot rhythm into
 * the engine: <b>a cluster with nothing else running snapshots itself and
 * reclaims its own disk.</b>
 *
 * <p>There is no admin gateway in this JVM, no ClusterTool, no scheduler and no
 * HTTP call. A single node is started, orders are pushed until the log passes a
 * (deliberately tiny) threshold, and the test then waits for two things that
 * used to require an external process: a snapshot entry in recording.log, and
 * the disappearance of the log's first segment file from the archive.</p>
 *
 * <p>Scaled down, not simplified: 64 KiB segments and a 64 KiB threshold stand
 * in for 64 MB and 1 GiB. Every mechanism under test - the control toggle, the
 * consensus module's SNAPSHOT action, {@code onTakeSnapshot}, the recording.log
 * entry, {@code purgeSegments} - is the production one.</p>
 */
public class SnapshotCadenceClusterTest {

    /** Small enough that a few hundred orders cross it, large enough to be a real Aeron term. */
    private static final int SEGMENT_LENGTH = 64 * 1024;

    private static final int PORT_BASE = 29000 + (int) (Math.random() * 10000);
    private static final int BTC_MARKET = 1;
    private static final long TIMEOUT_MS = 90_000;

    private static ClusteredMediaDriver clusteredMediaDriver;
    private static ClusteredServiceContainer serviceContainer;
    private static MediaDriver clientMediaDriver;
    private static AeronCluster client;
    private static File baseDir;
    private static File clusterDir;
    private static File archiveDir;

    private static final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private static final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();

    @BeforeClass
    public static void startCluster() throws Exception {
        // BEFORE the service is constructed: SnapshotCadence is built in a field
        // initialiser, so a property set later would be read by nobody.
        System.setProperty("snapshot.log.bytes", Integer.toString(SEGMENT_LENGTH));
        // Timer off: this test is about the byte trigger, and a timer firing
        // underneath it would let a broken byte path pass.
        System.setProperty("snapshot.interval.minutes", "0");

        baseDir = new File(System.getProperty("java.io.tmpdir"),
                "aeron-cadence-test-" + System.nanoTime());
        final List<String> hostnames = List.of("localhost");

        final ClusterConfig config = ClusterConfig.create(
                0, 0, hostnames, hostnames, PORT_BASE, baseDir, new AppClusteredService());
        config.errorHandler(t -> System.err.println("Cluster error: " + t.getMessage()));

        clusteredMediaDriver = ClusteredMediaDriver.launch(
                config.mediaDriverContext()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED)
                        .termBufferSparseFile(true)
                        .publicationTermBufferLength(1024 * 1024)
                        .ipcTermBufferLength(1024 * 1024)
                        .conductorIdleStrategy(new SleepingMillisIdleStrategy(1))
                        .senderIdleStrategy(new SleepingMillisIdleStrategy(1))
                        .receiverIdleStrategy(new SleepingMillisIdleStrategy(1))
                        .sharedIdleStrategy(new SleepingMillisIdleStrategy(1)),
                config.archiveContext()
                        .threadingMode(io.aeron.archive.ArchiveThreadingMode.SHARED)
                        // Reclamation granularity. A recording's segments can never be
                        // smaller than its term buffer, so the log channel below has to
                        // shrink with it or nothing is ever whole-segment reclaimable.
                        .segmentFileLength(SEGMENT_LENGTH),
                config.consensusModuleContext()
                        .idleStrategySupplier(SleepingMillisIdleStrategy::new)
                        .logChannel("aeron:udp?term-length=64k")
                        .ingressChannel("aeron:udp?term-length=64k")
                        .egressChannel("aeron:udp?term-length=64k")
                        .electionTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(200))
                        .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                        .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .sessionTimeoutNs(TimeUnit.SECONDS.toNanos(30)));

        clusterDir = config.consensusModuleContext().clusterDir();
        archiveDir = config.archiveContext().archiveDir();

        serviceContainer = ClusteredServiceContainer.launch(
                config.clusteredServiceContext()
                        .idleStrategySupplier(SleepingMillisIdleStrategy::new));

        Thread.sleep(5000); // leader election

        clientMediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED));

        client = AeronCluster.connect(
                new AeronCluster.Context()
                        .egressListener((sessionId, timestamp, buffer, offset, length, header) -> { })
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel("aeron:udp?term-length=64k")
                        .ingressEndpoints(ClusterConfig.ingressEndpoints(
                                hostnames, PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET))
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName()));
        Thread.sleep(1000);
    }

    @AfterClass
    public static void stopCluster() {
        CloseHelper.closeAll(client, serviceContainer, clusteredMediaDriver, clientMediaDriver);
        System.clearProperty("snapshot.log.bytes");
        System.clearProperty("snapshot.interval.minutes");
        if (baseDir != null && baseDir.exists()) {
            IoUtil.delete(baseDir, true);
        }
    }

    @Test
    public void theClusterSnapshotsAndReclaimsItsOwnLogWithNoAdminProcess() throws Exception {
        // Enough log to leave several whole segments behind the snapshot.
        for (int i = 0; i < 4000; i++) {
            sendOrder(1000L + i);
        }

        final long snapshotPosition = awaitSnapshot();
        assertTrue("a snapshot should have been taken with nothing external asking for one",
                snapshotPosition >= 0);
        System.out.println("[TEST] snapshot taken at log position " + snapshotPosition
                + " - no admin gateway, no ClusterTool");

        // Keep the log moving: the purge only deletes segments that are complete
        // and behind the snapshot, and the pruner wakes on a timer.
        final long logRecordingId = logRecordingId();
        final File firstSegment = new File(archiveDir, logRecordingId + "-0.rec");
        assertTrue("the log's first segment should exist before pruning: " + firstSegment,
                firstSegment.exists());

        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (firstSegment.exists()) {
            if (System.currentTimeMillis() > deadline) {
                fail("the first log segment was never reclaimed: " + firstSegment
                        + " (snapshot at " + snapshotPosition + ") - the engine took a snapshot "
                        + "but nothing purged the log below it");
            }
            sendOrder(9_000_000L);
            Thread.sleep(200);
        }

        System.out.println("[TEST] first log segment reclaimed: " + firstSegment.getName()
                + " - the engine snapshotted AND pruned on its own");
    }

    /** Wait for a valid snapshot entry to appear in recording.log, and return its position. */
    private static long awaitSnapshot() throws Exception {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
                for (final RecordingLog.Entry entry : recordingLog.entries()) {
                    if (entry.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && entry.isValid) {
                        return entry.logPosition;
                    }
                }
            }
            // Traffic keeps the log growing past the threshold even if the first
            // burst was consumed before the cadence evaluated.
            sendOrder(8_000_000L);
            Thread.sleep(250);
        }
        return -1;
    }

    private static long logRecordingId() {
        try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
            return recordingLog.findLastTermRecordingId();
        }
    }

    private static void sendOrder(final long userId) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        createOrderEncoder.userId(userId);
        createOrderEncoder.price(FixedPoint.fromDouble(100_000.0));
        createOrderEncoder.quantity(FixedPoint.fromDouble(0.001));
        createOrderEncoder.totalPrice(0);
        createOrderEncoder.marketId(BTC_MARKET);
        createOrderEncoder.orderType(OrderType.LIMIT);
        createOrderEncoder.orderSide(OrderSide.BID);

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH;
        final long deadline = System.currentTimeMillis() + 5000;
        while (client.offer(buffer, 0, length) < 0) {
            Thread.yield();
            client.pollEgress();
            if (System.currentTimeMillis() > deadline) {
                fail("timed out offering an order to the cluster");
            }
        }
    }
}
