package com.match.infrastructure.persistence;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.RecordingLog;
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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Proves that ArchiveHousekeeping reclaims disk after a snapshot.
 *
 * Aeron snapshots do NOT truncate the log — they only ADD recordings.
 * Reclamation is an explicit archive operation (purgeSegments below the
 * snapshot position, whole segment files only). This test runs a real
 * single-node cluster with tiny (64KB) segments, grows the log across
 * several segment files, snapshots, runs housekeeping, and asserts the
 * archive actually shrinks while the cluster keeps working.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ArchiveHousekeepingTest {

    private static final int SEGMENT_LENGTH = 64 * 1024;
    private static final int TERM_LENGTH = 64 * 1024;

    private static ClusteredMediaDriver clusteredMediaDriver;
    private static ClusteredServiceContainer serviceContainer;
    private static MediaDriver clientMediaDriver;
    private static AeronCluster client;
    private static AeronArchive aeronArchive;
    private static File baseDir;
    private static File clusterDir;
    private static File archiveDir;

    private static final int PORT_BASE = 31000 + (int) (Math.random() * 9000);

    private static final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private static final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();

    private static final int BTC_MARKET = 1;

    @BeforeClass
    public static void startCluster() throws Exception {
        baseDir = new File(System.getProperty("java.io.tmpdir"),
                "aeron-housekeeping-test-" + System.nanoTime());
        clusterDir = new File(new File(baseDir, "aeron-cluster-0"), ClusterConfig.CLUSTER_SUB_DIR);
        archiveDir = new File(new File(baseDir, "aeron-cluster-0"), ClusterConfig.ARCHIVE_SUB_DIR);

        launchClusterNode();

        clientMediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED));

        connectClientAndArchive();
    }

    /**
     * Launch (or relaunch) the single cluster node against the persistent baseDir. The archive and
     * cluster dirs live under baseDir and survive a relaunch; only the transient media-driver dir is
     * recreated — so a close + relaunch exercises the real recover-from-snapshot path.
     */
    private static void launchClusterNode() throws Exception {
        List<String> hostnames = List.of("localhost");

        ClusterConfig config = ClusterConfig.create(
                0, 0, hostnames, hostnames, PORT_BASE, baseDir, new AppClusteredService());
        config.errorHandler(t -> System.err.println("Cluster error: " + t.getMessage()));

        clusteredMediaDriver = ClusteredMediaDriver.launch(
                config.mediaDriverContext()
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true)
                        .threadingMode(ThreadingMode.SHARED)
                        .termBufferSparseFile(true)
                        .publicationTermBufferLength(TERM_LENGTH)
                        .ipcTermBufferLength(1024 * 1024)
                        .conductorIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .senderIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .receiverIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1))
                        .sharedIdleStrategy(new org.agrona.concurrent.SleepingMillisIdleStrategy(1)),
                config.archiveContext()
                        .threadingMode(io.aeron.archive.ArchiveThreadingMode.SHARED)
                        .segmentFileLength(SEGMENT_LENGTH),
                config.consensusModuleContext()
                        .idleStrategySupplier(org.agrona.concurrent.SleepingMillisIdleStrategy::new)
                        // Admin requests (snapshot) are denied by default; allow for tests.
                        // Production snapshots go via ClusterTool, which bypasses this.
                        .authorisationServiceSupplier(() -> io.aeron.security.AuthorisationService.ALLOW_ALL)
                        .ingressChannel("aeron:udp?term-length=64k")
                        .egressChannel("aeron:udp?term-length=64k")
                        .logChannel("aeron:udp?term-length=" + TERM_LENGTH + "|control-mode=manual")
                        .electionTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(200))
                        .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                        .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(3))
                        .sessionTimeoutNs(TimeUnit.SECONDS.toNanos(30)));

        serviceContainer = ClusteredServiceContainer.launch(
                config.clusteredServiceContext()
                        .idleStrategySupplier(org.agrona.concurrent.SleepingMillisIdleStrategy::new));

        Thread.sleep(5000);
    }

    /** Connect (or reconnect) the cluster client and the node-local archive client. */
    private static void connectClientAndArchive() {
        List<String> hostnames = List.of("localhost");
        String ingressEndpoints = ClusterConfig.ingressEndpoints(
                hostnames, PORT_BASE, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        EgressListener egressListener = (clusterSessionId, timestamp, buffer, offset, length, header) -> { };

        client = AeronCluster.connect(
                new AeronCluster.Context()
                        .egressListener(egressListener)
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel("aeron:udp")
                        .ingressEndpoints(ingressEndpoints)
                        .aeronDirectoryName(clientMediaDriver.aeronDirectoryName()));

        // Archive client to the node's local archive (same pattern as ClusterBackupApp)
        aeronArchive = AeronArchive.connect(
                new AeronArchive.Context()
                        .controlRequestChannel("aeron:ipc?term-length=16m")
                        .controlResponseChannel("aeron:ipc?term-length=16m")
                        .aeronDirectoryName(clusteredMediaDriver.mediaDriver().aeronDirectoryName()));
    }

    @AfterClass
    public static void stopCluster() {
        CloseHelper.closeAll(aeronArchive, client, serviceContainer, clusteredMediaDriver, clientMediaDriver);
        if (baseDir != null && baseDir.exists()) {
            IoUtil.delete(baseDir, true);
        }
    }

    @Test
    public void test1_HousekeepingPurgesLogSegmentsBelowSnapshot() throws Exception {
        long logRecordingId = awaitLogRecordingId();

        // Grow the log past several segment files
        pumpOrdersUntilLogExceeds(logRecordingId, 4L * SEGMENT_LENGTH);

        long segmentFilesBefore = countSegmentFiles(logRecordingId);
        assertTrue("Log should span multiple segment files (got " + segmentFilesBefore + ")",
                segmentFilesBefore >= 4);

        // Take a snapshot and wait for it to land in the recording log
        takeSnapshotAndAwait();

        long startPositionBefore = aeronArchive.getStartPosition(logRecordingId);

        ArchiveHousekeeping.Result result =
                ArchiveHousekeeping.purgeBelowLatestSnapshot(clusterDir, aeronArchive, 2);

        assertEquals(logRecordingId, result.logRecordingId);
        assertTrue("Housekeeping must advance the log start position "
                        + "(was " + startPositionBefore + ", now " + result.newStartPosition + ")",
                result.newStartPosition > startPositionBefore);
        assertTrue("Must purge at least one segment", result.segmentsPurged >= 1);
        assertEquals("Archive must agree on the new start position",
                result.newStartPosition, aeronArchive.getStartPosition(logRecordingId));

        long segmentFilesAfter = countSegmentFiles(logRecordingId);
        assertTrue("Segment files on disk must reduce (before=" + segmentFilesBefore
                        + ", after=" + segmentFilesAfter + ")",
                segmentFilesAfter < segmentFilesBefore);

        // Cluster must still function after the purge
        sendOrder(99_001L, 60_500.0, 0.1);
        long position = aeronArchive.getRecordingPosition(logRecordingId);
        assertTrue("Cluster should still be processing orders", position > 0);
    }

    @Test
    public void test2_HousekeepingNeverPurgesSnapshotRecordings() throws Exception {
        // We already have 1 snapshot from test1; take two more so we have 3.
        takeSnapshotAndAwait();
        takeSnapshotAndAwait();

        ArchiveHousekeeping.Result result =
                ArchiveHousekeeping.purgeBelowLatestSnapshot(clusterDir, aeronArchive, 2);

        // Snapshot recordings must NEVER be purged: Aeron recovery selects a snapshot by the
        // recording.log isValid flag without checking the archive, so purging a recording while
        // its entry stays valid breaks recover-from-snapshot ("unknown recording id").
        assertEquals("Housekeeping must not purge any snapshot recording",
                0, result.snapshotRecordingsPurged);
        // Invariant: every snapshot recording the recording.log references still resolves in the
        // archive (errors counts orphaned entries — the exact thing that breaks recovery).
        assertEquals("No snapshot recording.log entry may be orphaned from the archive",
                0, result.errors);

        // Idempotent: a second run is also a clean, error-free no-op.
        ArchiveHousekeeping.Result second =
                ArchiveHousekeeping.purgeBelowLatestSnapshot(clusterDir, aeronArchive, 2);
        assertEquals(0, second.snapshotRecordingsPurged);
        assertEquals(0, second.errors);
    }

    /**
     * The regression guard for #14: after housekeeping, the node must still RECOVER FROM SNAPSHOT.
     * Earlier tests only proved housekeeping ran — never that the cluster could restart afterward.
     * Take several snapshots, run housekeeping, then restart the node (recover-from-snapshot reads
     * the archive + recording.log housekeeping just touched) and prove the recovered node is a
     * serving leader. If housekeeping orphaned any snapshot recording, recovery would throw
     * "unknown recording id" here and the node would never serve this order.
     */
    @Test
    public void test3_RecoverySucceedsAfterHousekeeping() throws Exception {
        // Several snapshots so old groups exist (with the old code these would be pruned + orphaned).
        takeSnapshotAndAwait();
        takeSnapshotAndAwait();
        takeSnapshotAndAwait();

        ArchiveHousekeeping.Result hk =
                ArchiveHousekeeping.purgeBelowLatestSnapshot(clusterDir, aeronArchive, 2);
        assertEquals("Housekeeping must not orphan any snapshot recording", 0, hk.errors);

        // Restart the node: archive + cluster dirs (under baseDir) persist; only the transient
        // media-driver dir is recreated. This forces a real recover-from-snapshot.
        CloseHelper.closeAll(client, aeronArchive, serviceContainer, clusteredMediaDriver);
        launchClusterNode();
        connectClientAndArchive();

        // Recovery succeeded only if the node came back as a serving leader.
        long logRecordingId = awaitLogRecordingId();
        assertTrue("Recovered node must have a cluster log recording", logRecordingId >= 0);
        sendOrder(98_001L, 55_000.0, 0.2);
        long position = aeronArchive.getRecordingPosition(logRecordingId);
        assertTrue("Recovered cluster must process orders (got position " + position + ")", position > 0);

        // And the recovered node's recording.log must reference no orphaned snapshot recordings.
        ArchiveHousekeeping.Result post =
                ArchiveHousekeeping.purgeBelowLatestSnapshot(clusterDir, aeronArchive, 2);
        assertEquals("No orphaned snapshot recordings after recovery", 0, post.errors);
    }

    // ==================== Helpers ====================

    private static long awaitLogRecordingId() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
                long recordingId = recordingLog.findLastTermRecordingId();
                if (recordingId != -1) {
                    return recordingId;
                }
            } catch (Exception ignore) {
                // recording.log may not exist yet
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for cluster log recording");
        return -1;
    }

    private static void pumpOrdersUntilLogExceeds(long logRecordingId, long targetPosition) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        long userId = 7000;
        while (aeronArchive.getRecordingPosition(logRecordingId) < targetPosition) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out growing log to " + targetPosition + " (at "
                        + aeronArchive.getRecordingPosition(logRecordingId) + ")");
            }
            // Resting bids far below market at varied prices
            sendOrder(userId, 51_000.0 + (userId % 2000), 0.01);
            userId++;
            if (userId % 200 == 0) {
                client.pollEgress();
            }
        }
    }

    private static void sendOrder(long userId, double price, double qty) {
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        createOrderEncoder.userId(userId);
        createOrderEncoder.price(FixedPoint.fromDouble(price));
        createOrderEncoder.quantity(FixedPoint.fromDouble(qty));
        createOrderEncoder.totalPrice(0);
        createOrderEncoder.marketId(BTC_MARKET);
        createOrderEncoder.orderType(OrderType.LIMIT);
        createOrderEncoder.orderSide(OrderSide.BID);
        int length = MessageHeaderEncoder.ENCODED_LENGTH + CreateOrderEncoder.BLOCK_LENGTH;

        long deadline = System.currentTimeMillis() + 5000;
        while (client.offer(buffer, 0, length) < 0) {
            client.pollEgress();
            Thread.yield();
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out offering order to cluster");
            }
        }
    }

    private static void takeSnapshotAndAwait() throws Exception {
        long snapshotsBefore = countValidSnapshotGroups();

        assertTrue("Snapshot admin request must be accepted",
                client.sendAdminRequestToTakeASnapshot(System.nanoTime()));

        long deadline = System.currentTimeMillis() + 30_000;
        while (countValidSnapshotGroups() <= snapshotsBefore) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for snapshot to appear in recording log");
            }
            client.pollEgress();
            Thread.sleep(100);
        }
    }

    private static long countValidSnapshotGroups() {
        try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
            return recordingLog.entries().stream()
                    .filter(e -> e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && e.isValid)
                    .map(e -> e.logPosition)
                    .distinct()
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long countSegmentFiles(long recordingId) {
        File[] files = archiveDir.listFiles(
                (dir, name) -> name.startsWith(recordingId + "-") && name.endsWith(".rec"));
        return files == null ? 0 : files.length;
    }
}
