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
 * Supervision (match#36): the in-JVM media driver, archive, and backup agent
 * can die or zombify individually while the JVM stays up — observed as
 * ConductorServiceTimeoutException killing the local archive under load, and
 * as the consensus-response subscription unbinding so BACKUP_QUERY stalls
 * forever. A watchdog thread therefore tracks RESPONSE-side listener callbacks
 * (query sends don't count: a wedged agent still sends) and halts the JVM when
 * no progress happens for BACKUP_STALL_EXIT_SEC, so the process manager
 * restarts the whole stack fresh (bounded by its crash-loop cap). It also
 * writes a heartbeat JSON next to the backup data for the admin gateway's
 * backup-info freshness reporting.
 *
 * Environment variables:
 * - CLUSTER_ADDRESSES: Comma-separated list of cluster member addresses
 * - BACKUP_HOST: This backup node's address
 * - CLUSTER_PORT_BASE: Base port for cluster (default 9000)
 * - BACKUP_INTERVAL_SEC: Interval between backup queries (default 60)
 * - BASE_DIR: Directory for backup data (default ./backup)
 * - BACKUP_STALL_EXIT_SEC: halt the JVM when no backup progress (response,
 *   recording-log update, or live-log advance) for this long (default 300, 0 disables)
 */
public class ClusterBackupApp {

    private static final long DEFAULT_BACKUP_INTERVAL_SEC = 60;
    private static final long DEFAULT_STALL_EXIT_SEC = 300;
    private static final long WATCHDOG_TICK_MS = 5_000;
    static final String PROGRESS_FILE_NAME = "backup-progress.json";

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

        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-backup";
        final String clusterConsensusEndpoints = buildConsensusEndpoints(hostAddresses, portBase);

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

        // Configure local AeronArchive client context (for local backup archive)
        final AeronArchive.Context localArchiveContext = new AeronArchive.Context()
            .controlRequestChannel(archiveContext.localControlChannel())
            .controlResponseChannel(archiveContext.localControlChannel())
            .aeronDirectoryName(aeronDirName);

        // Configure ClusterBackup
        // The backup needs channels for consensus and catchup from cluster
        final String consensusChannel = "aeron:udp?endpoint=" + backupHost + ":9876";
        final String catchupChannel = "aeron:udp?endpoint=" + backupHost + ":0";

        // Configure cluster archive context (for connecting to cluster's archive)
        // We need to specify how to reach each cluster member's archive
        final String leaderArchiveEndpoint = hostAddresses.get(0) + ":" +
            ClusterConfig.calculatePort(0, portBase, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET);

        final AeronArchive.Context clusterArchiveContext = new AeronArchive.Context()
            .controlRequestChannel("aeron:udp?endpoint=" + leaderArchiveEndpoint)
            .controlResponseChannel("aeron:udp?endpoint=" + backupHost + ":0")
            .aeronDirectoryName(aeronDirName);

        final BackupEventsListener eventsListener = new BackupEventsListener();

        final ClusterBackup.Context clusterBackupContext = new ClusterBackup.Context()
            .aeronDirectoryName(aeronDirName)
            .clusterDirectoryName(new File(baseDir, ClusterConfig.CLUSTER_SUB_DIR).getAbsolutePath())
            .archiveContext(localArchiveContext.clone())
            .clusterArchiveContext(clusterArchiveContext)
            .clusterBackupIntervalNs(backupIntervalNs)
            .clusterConsensusEndpoints(clusterConsensusEndpoints)
            .consensusChannel(consensusChannel)
            .catchupEndpoint(backupHost + ":0")
            .catchupChannel(catchupChannel)
            .sourceType(ClusterBackup.SourceType.LEADER)
            .eventsListener(eventsListener)
            .deleteDirOnStart(true);

        MediaDriver mediaDriver = null;
        Archive archive = null;
        ClusterBackup clusterBackup = null;

        try {
            mediaDriver = MediaDriver.launch(mediaDriverContext);
            archive = Archive.launch(archiveContext);
            clusterBackup = ClusterBackup.launch(clusterBackupContext);
            startWatchdog(baseDir, eventsListener, getStallExitSec());
            barrier.await();
        } catch (final Exception e) {
            System.err.println("FATAL: ClusterBackup failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            CloseHelper.quietCloseAll(clusterBackup, archive, mediaDriver);
        }
    }

    /**
     * Watchdog: heartbeat file for the admin gateway + stall fail-fast.
     *
     * Progress = response-side events only (backup response, recording-log
     * update, live-log advance). On a healthy cluster the live log advances
     * even at idle (the cluster's timer chain writes the Raft log) and a
     * backup response arrives every BACKUP_INTERVAL_SEC, so a quiet period of
     * stallExitSec means the backup is providing no protection. Halt (not
     * System.exit: shutdown hooks can block on the same wedged agents) so the
     * process manager restarts us fresh; deleteDirOnStart/dirDeleteOnStart
     * make the restart clean, and the PM's crash-loop cap bounds a persistent
     * failure loudly instead of letting us spin for days (match#36).
     */
    private static void startWatchdog(
        final File baseDir, final BackupEventsListener listener, final long stallExitSec) {

        final long startMs = SystemEpochClock.INSTANCE.time();
        listener.noteProgress(startMs); // grace: count from launch, not epoch 0

        final Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_TICK_MS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }

                final long nowMs = SystemEpochClock.INSTANCE.time();
                final long sinceProgressMs = nowMs - listener.lastProgressMs();
                final boolean stalled = stallExitSec > 0 && sinceProgressMs > TimeUnit.SECONDS.toMillis(stallExitSec);

                writeProgressFile(baseDir, listener, startMs, nowMs, stalled);

                if (stalled) {
                    System.err.printf(
                        "FATAL: cluster backup made no progress for %ds (limit %ds, stallWarnings=%d, " +
                        "lastResponse %dms ago, lastLiveLog %dms ago) — halting for a clean supervised restart%n",
                        TimeUnit.MILLISECONDS.toSeconds(sinceProgressMs), stallExitSec, listener.stallCount(),
                        nowMs - listener.lastResponseMs(), nowMs - listener.lastLiveLogMs());
                    Runtime.getRuntime().halt(3);
                }
            }
        }, "backup-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Atomically (write+rename) publish the heartbeat JSON the admin gateway reads. */
    private static void writeProgressFile(
        final File baseDir, final BackupEventsListener listener,
        final long startMs, final long nowMs, final boolean stalled) {
        final File tmp = new File(baseDir, PROGRESS_FILE_NAME + ".tmp");
        final File target = new File(baseDir, PROGRESS_FILE_NAME);
        final String json = String.format(
            "{\"pid\":%d,\"startedEpochMs\":%d,\"updatedEpochMs\":%d,\"lastProgressEpochMs\":%d," +
            "\"lastQueryEpochMs\":%d,\"lastResponseEpochMs\":%d,\"lastLiveLogEpochMs\":%d," +
            "\"liveLogPosition\":%d,\"snapshotsRetrieved\":%d,\"stallWarnings\":%d,\"state\":\"%s\"}%n",
            ProcessHandle.current().pid(), startMs, nowMs, listener.lastProgressMs(),
            listener.lastQueryMs(), listener.lastResponseMs(), listener.lastLiveLogMs(),
            listener.liveLogPosition(), listener.snapshotsRetrieved(), listener.stallCount(),
            stalled ? "STALLED" : "OK");
        try {
            java.nio.file.Files.writeString(tmp.toPath(), json);
            java.nio.file.Files.move(tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (final Exception e) {
            System.err.println("WARN: cannot write " + target + ": " + e.getMessage());
        }
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

    private static long getStallExitSec() {
        String value = System.getenv("BACKUP_STALL_EXIT_SEC");
        if (null == value || value.isEmpty()) {
            value = System.getProperty("backup.stall.exit.sec", String.valueOf(DEFAULT_STALL_EXIT_SEC));
        }
        return Long.parseLong(value);
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
                System.err.println("FATAL: Cannot resolve DNS name " + host + ", exiting");
                System.exit(-1);
            }

            try {
                InetAddress.getByName(host);
                resolved = true;
            } catch (final UnknownHostException e) {
                try {
                    Thread.sleep(3000);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Tracks backup liveness for the watchdog. Callbacks run on the
     * cluster-backup agent thread; the watchdog thread reads the volatiles.
     * Query sends are tracked but do NOT count as progress — a wedged agent
     * whose responses can't arrive still sends queries (observed in match#36).
     */
    static class BackupEventsListener implements ClusterBackupEventsListener {

        private volatile long lastQueryMs;
        private volatile long lastResponseMs;
        private volatile long lastLiveLogMs;
        private volatile long lastProgressMs;
        private volatile long liveLogPosition;
        private volatile long snapshotsRetrieved;
        private volatile long stallCount;

        void noteProgress(final long nowMs) {
            lastProgressMs = nowMs;
        }

        long lastQueryMs() { return lastQueryMs; }
        long lastResponseMs() { return lastResponseMs; }
        long lastLiveLogMs() { return lastLiveLogMs; }
        long lastProgressMs() { return lastProgressMs; }
        long liveLogPosition() { return liveLogPosition; }
        long snapshotsRetrieved() { return snapshotsRetrieved; }
        long stallCount() { return stallCount; }

        @Override
        public void onBackupQuery() {
            lastQueryMs = SystemEpochClock.INSTANCE.time();
        }

        @Override
        public void onPossibleFailure(final Exception ex) {
            stallCount++;
            System.err.println("BACKUP WARNING: " + ex.getMessage());
        }

        @Override
        public void onBackupResponse(
            final ClusterMember[] clusterMembers,
            final ClusterMember logSourceMember,
            final List<RecordingLog.Snapshot> snapshotsToRetrieve) {
            final long nowMs = SystemEpochClock.INSTANCE.time();
            lastResponseMs = nowMs;
            noteProgress(nowMs);
            System.out.println("BACKUP: response, log source member " +
                (null != logSourceMember ? logSourceMember.id() : -1) +
                ", snapshots to retrieve " + snapshotsToRetrieve.size());
        }

        @Override
        public void onUpdatedRecordingLog(
            final RecordingLog recordingLog,
            final List<RecordingLog.Snapshot> snapshotsRetrieved) {
            final long nowMs = SystemEpochClock.INSTANCE.time();
            this.snapshotsRetrieved += snapshotsRetrieved.size();
            noteProgress(nowMs);
            System.out.println("BACKUP: recording log updated, snapshots retrieved " + snapshotsRetrieved.size());
        }

        @Override
        public void onLiveLogProgress(
            final long recordingId,
            final long recordingPosCounterId,
            final long logPosition) {
            final long nowMs = SystemEpochClock.INSTANCE.time();
            liveLogPosition = logPosition;
            lastLiveLogMs = nowMs;
            noteProgress(nowMs);
        }
    }
}
