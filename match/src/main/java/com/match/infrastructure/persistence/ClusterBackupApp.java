/*
 * ClusterBackup Node Application
 *
 * A non-voting observer that replicates cluster snapshots and logs
 * for disaster recovery purposes WITHOUT pausing the cluster.
 */
package com.match.infrastructure.persistence;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusterBackup;
import io.aeron.cluster.ClusterBackupEventsListener;
import io.aeron.cluster.ClusterMember;
import io.aeron.cluster.RecordingLog;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.SystemEpochClock;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ClusterBackup application that replicates cluster data for disaster recovery.
 *
 * This node:
 * - Connects to the cluster leader as a non-voting observer
 * - Retrieves snapshots periodically (default 60s) WITHOUT pausing the cluster
 * - Replicates log entries after the snapshot for complete recovery
 * - Can be used to seed new nodes or recover from cluster failure
 *
 * Environment variables:
 * - CLUSTER_ADDRESSES: Comma-separated list of cluster member addresses
 * - BACKUP_HOST: This backup node's address
 * - CLUSTER_PORT_BASE: Base port for cluster (default 9000)
 * - BACKUP_INTERVAL_SEC: Interval between backup queries (default 60)
 * - BASE_DIR: Directory for backup data (default ./backup)
 */
public class ClusterBackupApp {

    private static final long DEFAULT_BACKUP_INTERVAL_SEC = 60;

    public static void main(final String[] args) {
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();

        final String clusterAddresses = getClusterAddresses();
        final String backupHost = getBackupHost();
        final int portBase = getBasePort();
        final long backupIntervalNs = getBackupIntervalNs();
        final File baseDir = getBaseDir();

        final List<String> hostAddresses = List.of(clusterAddresses.split(","));

        // Wait for DNS resolution of all cluster hosts
        hostAddresses.forEach(ClusterBackupApp::awaitDnsResolution);

        System.out.println("Starting ClusterBackup...");
        System.out.println("Cluster addresses: " + clusterAddresses);
        System.out.println("Backup host: " + backupHost);
        System.out.println("Port base: " + portBase);
        System.out.println("Backup interval: " + TimeUnit.NANOSECONDS.toSeconds(backupIntervalNs) + "s");
        System.out.println("Base directory: " + baseDir.getAbsolutePath());

        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-backup";

        // Build cluster consensus endpoints string
        final String clusterConsensusEndpoints = buildConsensusEndpoints(hostAddresses, portBase);
        System.out.println("Consensus endpoints: " + clusterConsensusEndpoints);

        // Configure MediaDriver
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
            .aeronDirectoryName(aeronDirName)
            .threadingMode(ThreadingMode.SHARED)  // Shared for backup (lower resource usage)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);

        // Configure Archive for storing replicated data
        final Archive.Context archiveContext = new Archive.Context()
            .aeronDirectoryName(aeronDirName)
            .archiveDir(new File(baseDir, ClusterConfig.ARCHIVE_SUB_DIR))
            .controlChannel("aeron:udp?endpoint=" + backupHost + ":0")
            .localControlChannel("aeron:ipc?term-length=1m")
            .replicationChannel("aeron:udp?endpoint=" + backupHost + ":0")
            .recordingEventsEnabled(false)
            .threadingMode(ArchiveThreadingMode.SHARED);

        // Configure AeronArchive client context
        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);

        // Configure ClusterBackup
        // The backup needs a local consensus channel for receiving cluster data
        final String consensusChannel = "aeron:udp?endpoint=" + backupHost + ":9876";
        final String catchupChannel = "aeron:udp?endpoint=" + backupHost + ":0";

        final ClusterBackup.Context clusterBackupContext = new ClusterBackup.Context()
            .aeronDirectoryName(aeronDirName)
            .clusterDirectoryName(new File(baseDir, ClusterConfig.CLUSTER_SUB_DIR).getAbsolutePath())
            .archiveContext(aeronArchiveContext.clone())
            .clusterBackupIntervalNs(backupIntervalNs)
            .clusterConsensusEndpoints(clusterConsensusEndpoints)
            .consensusChannel(consensusChannel)
            .catchupEndpoint(backupHost + ":0")
            .catchupChannel(catchupChannel)
            .sourceType(ClusterBackup.SourceType.LEADER)
            .eventsListener(new BackupEventsListener())
            .deleteDirOnStart(true);

        MediaDriver mediaDriver = null;
        Archive archive = null;
        ClusterBackup clusterBackup = null;

        try {
            mediaDriver = MediaDriver.launch(mediaDriverContext);
            System.out.println("MediaDriver started");

            archive = Archive.launch(archiveContext);
            System.out.println("Archive started");

            clusterBackup = ClusterBackup.launch(clusterBackupContext);
            System.out.println("ClusterBackup started - waiting for cluster connection...");

            barrier.await();
            System.out.println("Shutting down ClusterBackup...");

        } catch (final Exception e) {
            System.err.println("Error starting ClusterBackup: " + e.getMessage());
            e.printStackTrace();
        } finally {
            CloseHelper.quietCloseAll(clusterBackup, archive, mediaDriver);
        }

        System.out.println("ClusterBackup shutdown complete");
    }

    /**
     * Build the cluster consensus endpoints string for ClusterBackup configuration.
     * Format: host1:port1,host2:port2,host3:port3
     */
    private static String buildConsensusEndpoints(final List<String> hosts, final int portBase) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hosts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            // Member facing port for consensus
            final int port = ClusterConfig.calculatePort(i, portBase, ClusterConfig.MEMBER_FACING_PORT_OFFSET);
            sb.append(hosts.get(i)).append(':').append(port);
        }
        return sb.toString();
    }

    private static String getClusterAddresses() {
        String clusterAddresses = System.getenv("CLUSTER_ADDRESSES");
        if (null == clusterAddresses || clusterAddresses.isEmpty()) {
            clusterAddresses = System.getProperty("cluster.addresses", "localhost");
        }
        return clusterAddresses;
    }

    private static String getBackupHost() {
        String backupHost = System.getenv("BACKUP_HOST");
        if (null == backupHost || backupHost.isEmpty()) {
            backupHost = System.getProperty("backup.host", "localhost");
        }
        return backupHost;
    }

    private static int getBasePort() {
        String portBaseString = System.getenv("CLUSTER_PORT_BASE");
        if (null == portBaseString || portBaseString.isEmpty()) {
            portBaseString = System.getProperty("port.base", "9000");
        }
        return Integer.parseInt(portBaseString);
    }

    private static long getBackupIntervalNs() {
        String intervalString = System.getenv("BACKUP_INTERVAL_SEC");
        if (null == intervalString || intervalString.isEmpty()) {
            intervalString = System.getProperty("backup.interval.sec", String.valueOf(DEFAULT_BACKUP_INTERVAL_SEC));
        }
        return TimeUnit.SECONDS.toNanos(Long.parseLong(intervalString));
    }

    private static File getBaseDir() {
        final String baseDir = System.getenv("BASE_DIR");
        if (null == baseDir || baseDir.isEmpty()) {
            return new File(System.getProperty("user.dir"), "backup");
        }
        return new File(baseDir);
    }

    /**
     * Wait for DNS resolution of the given host.
     */
    private static void awaitDnsResolution(final String host) {
        final long endTime = SystemEpochClock.INSTANCE.time() + 60000;
        java.security.Security.setProperty("networkaddress.cache.ttl", "0");

        boolean resolved = false;
        while (!resolved) {
            if (SystemEpochClock.INSTANCE.time() > endTime) {
                System.out.printf("Cannot resolve name %s, exiting%n", host);
                System.exit(-1);
            }

            try {
                InetAddress.getByName(host);
                resolved = true;
            } catch (final UnknownHostException e) {
                System.out.printf("Cannot yet resolve name %s, retrying in 3 seconds%n", host);
                try {
                    Thread.sleep(3000);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Event listener for ClusterBackup to log backup progress.
     */
    static class BackupEventsListener implements ClusterBackupEventsListener {

        @Override
        public void onBackupQuery() {
            System.out.println("Querying cluster for backup updates...");
        }

        @Override
        public void onPossibleFailure(final Exception ex) {
            System.err.println("WARNING: Possible cluster failure detected: " + ex.getMessage());
        }

        @Override
        public void onBackupResponse(
            final ClusterMember[] clusterMembers,
            final ClusterMember logSourceMember,
            final List<RecordingLog.Snapshot> snapshotsToRetrieve) {

            System.out.printf("Backup response: cluster members=%s, log source=%s, snapshots to retrieve=%d%n",
                Arrays.toString(clusterMembers),
                logSourceMember,
                snapshotsToRetrieve.size());

            for (final RecordingLog.Snapshot snapshot : snapshotsToRetrieve) {
                System.out.printf("  Snapshot: recordingId=%d, leadershipTermId=%d, " +
                    "termBaseLogPosition=%d, logPosition=%d%n",
                    snapshot.recordingId, snapshot.leadershipTermId,
                    snapshot.termBaseLogPosition, snapshot.logPosition);
            }
        }

        @Override
        public void onUpdatedRecordingLog(
            final RecordingLog recordingLog,
            final List<RecordingLog.Snapshot> snapshotsRetrieved) {

            System.out.printf("Recording log updated: snapshots retrieved=%d%n",
                snapshotsRetrieved.size());

            for (final RecordingLog.Snapshot snapshot : snapshotsRetrieved) {
                System.out.printf("  Retrieved snapshot: recordingId=%d, leadershipTermId=%d%n",
                    snapshot.recordingId, snapshot.leadershipTermId);
            }
        }

        @Override
        public void onLiveLogProgress(
            final long recordingId,
            final long recordingPosCounterId,
            final long logPosition) {

            System.out.printf("Live log progress: recordingId=%d, counterId=%d, logPosition=%d%n",
                recordingId, recordingPosCounterId, logPosition);
        }
    }
}
