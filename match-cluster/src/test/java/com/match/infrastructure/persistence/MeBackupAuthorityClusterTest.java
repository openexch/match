// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.auth.ClassAuthorisationService;
import com.openexchange.cluster.auth.ClusterCredentials;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ClusterBackup;
import io.aeron.cluster.ClusterBackupEventsListener;
import io.aeron.cluster.ClusterMember;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.order;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.releaseFrame;
import static org.junit.Assert.*;

/**
 * RC2 S6 9.2 (BLOCKER-1, decision A14): the ME backup authenticates as REPLICA, and REPLICA may do BackupQuery
 * and nothing else. Three members plus an in-JVM {@link ClusterBackup} built by {@link ClusterBackupApp#backupContext}:
 * (a) no credential: no response, rejected at the authenticator; (b) FORWARDER: authenticated, BackupQuery denied;
 * (c) REPLICA: responses and live-log progress; (d) an ingress client holding the REPLICA key sends templates
 * 7, 8, 9, 10 at behaviour 2 (7, 8, 9 at behaviour 1, 8 pre-config): every frame refused on every member.
 */
public class MeBackupAuthorityClusterTest {

    private static final int PORT_BASE = 25_700;
    /** Session timeout 4 s + the kit's 1 s margin + 1 s slack. */
    private static final long WINDOW_GAP_MS = 6_000;

    /** Counts what the backup agent saw. */
    private static final class Events implements ClusterBackupEventsListener {
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger responses = new AtomicInteger();
        final AtomicLong liveLogPosition = new AtomicLong(-1);
        final AtomicInteger failures = new AtomicInteger();

        public void onBackupQuery() { queries.incrementAndGet(); }
        public void onPossibleFailure(final Exception ex) {
            failures.incrementAndGet();
            System.out.println("[BACKUP] possible failure: " + ex);
        }

        public void onBackupResponse(final ClusterMember[] clusterMembers, final ClusterMember logSourceMember,
                                     final List<RecordingLog.Snapshot> snapshotsToRetrieve) {
            responses.incrementAndGet();
        }

        public void onUpdatedRecordingLog(final RecordingLog recordingLog,
                                          final List<RecordingLog.Snapshot> snapshotsRetrieved) { }

        public void onLiveLogProgress(final long recordingId, final long recordingPosCounterId, final long logPosition) {
            liveLogPosition.set(logPosition);
        }
    }

    /** A backup process in this JVM, wired as ClusterBackupApp wires it. */
    private static final class Backup implements AutoCloseable {
        final Events events = new Events();
        final MediaDriver driver;
        final Archive archive;
        final ClusterBackup backup;
        final File dir;

        Backup(final MeClusterHarness h, final String name, final ClusterCredentials credentials, final int consensusPort) {
            this(h, name, credentials, consensusPort, TimeUnit.SECONDS.toNanos(1));
        }

        Backup(final MeClusterHarness h, final String name, final ClusterCredentials credentials, final int consensusPort,
               final long intervalNs) {
            dir = new File(h.root, "backup-" + name);
            IoUtil.delete(dir, true);
            final String aeronDir = new File(dir, "driver").getAbsolutePath();
            driver = MediaDriver.launch(new MediaDriver.Context().aeronDirectoryName(aeronDir)
                    .threadingMode(ThreadingMode.SHARED).dirDeleteOnStart(true).dirDeleteOnShutdown(true)
                    .mtuLength(8192)); // the harness members publish with an 8 KiB MTU
            final Archive.Context archiveContext = new Archive.Context()
                    .aeronDirectoryName(aeronDir)
                    .archiveDir(new File(dir, ClusterConfig.ARCHIVE_SUB_DIR))
                    .controlChannel("aeron:udp?endpoint=localhost:0")
                    .localControlChannel("aeron:ipc?term-length=1m")
                    .replicationChannel("aeron:udp?endpoint=localhost:0")
                    .recordingEventsEnabled(false)
                    .threadingMode(ArchiveThreadingMode.SHARED);
            archive = Archive.launch(archiveContext);
            final AeronArchive.Context local = new AeronArchive.Context()
                    .controlRequestChannel(archiveContext.localControlChannel())
                    .controlResponseChannel(archiveContext.localControlChannel())
                    .aeronDirectoryName(aeronDir);
            final AeronArchive.Context clusterArchive = new AeronArchive.Context()
                    .controlRequestChannel("aeron:udp?endpoint=localhost:"
                            + ClusterConfig.calculatePort(0, h.portBase, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET))
                    .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                    .aeronDirectoryName(aeronDir);
            final StringBuilder consensus = new StringBuilder();
            for (int i = 0; i < MeClusterHarness.MEMBERS; i++) {
                consensus.append(i == 0 ? "" : ",").append("localhost:")
                        .append(ClusterConfig.calculatePort(i, h.portBase, ClusterConfig.MEMBER_FACING_PORT_OFFSET));
            }
            final ClusterBackup.Context ctx = ClusterBackupApp.backupContext(aeronDir, dir, local, clusterArchive,
                    intervalNs, consensus.toString(), "aeron:udp?endpoint=localhost:" + consensusPort,
                    "localhost", "aeron:udp?endpoint=localhost:0", events, credentials);
            // A query answered by a follower resets the backup (SourceType.LEADER) for the cool-down, 30 s by
            // default; 1 s keeps the test inside its windows without changing what is judged.
            ctx.clusterBackupCoolDownIntervalNs(TimeUnit.SECONDS.toNanos(1));
            backup = ClusterBackup.launch(ctx.errorHandler(t -> System.out.println("[BACKUP] " + name + " error: " + t)));
        }

        @Override
        public void close() {
            CloseHelper.quietCloseAll(backup, archive, driver);
            IoUtil.delete(dir, true);
        }
    }

    private static long sum(final MeClusterHarness h, final java.util.function.ToLongFunction<MeClusterHarness.Member> f) {
        long n = 0;
        for (MeClusterHarness.Member m : h.members) {
            if (m.live) {
                n += f.applyAsLong(m);
            }
        }
        return n;
    }

    @Test
    public void onlyReplicaMayQueryBackupsAndReplicaSendsNoIngress() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(PORT_BASE, "backup-authority")) {
            // Aeron gives every backup request session id -1; the kit authenticates a -1 session only when its
            // request is alone in a window of the session timeout + 1 s (review DEFECT-1). A short timeout keeps
            // the phases of this test apart: each phase starts once the previous phase's requests aged out.
            h.sessionTimeoutMs = 4_000;
            h.startAll();
            h.awaitLeader(null, 30_000);
            try (AeronCluster idle = h.connect(ClusterSessionClass.ADMIN)) {
                // (a) no credential
                final long rejected0 = sum(h, m -> m.authRejected());
                try (Backup none = new Backup(h, "none", null, 26_701)) {
                    MeClusterHarness.await(idle, () -> false, 10_000);
                    assertEquals("(a) no backup response within 10 s", 0, none.events.responses.get());
                    System.out.println("[BACKUP] (a) re-queries=" + none.events.queries.get()
                            + " possibleFailures=" + none.events.failures.get());
                }
                assertTrue("(a) the authenticator rejected the credential-less query",
                        sum(h, m -> m.authRejected()) > rejected0);

                MeClusterHarness.await(idle, () -> false, WINDOW_GAP_MS); // (a)'s requests age out
                // (b) FORWARDER: authenticated, BackupQuery denied by the class table
                final long accepted0 = sum(h, m -> m.authAccepted());
                final long denied0 = sum(h, m -> m.backupQueriesDenied());
                try (Backup forwarder = new Backup(h, "forwarder", TestAuth.credentialsOf(ClusterSessionClass.FORWARDER), 26_702)) {
                    MeClusterHarness.await(idle, () -> false, 10_000);
                    assertEquals("(b) no backup response within 10 s", 0, forwarder.events.responses.get());
                }
                assertTrue("(b) authenticated", sum(h, m -> m.authAccepted()) > accepted0);
                assertTrue("(b) BackupQuery denied",
                        sum(h, m -> m.backupQueriesDenied()) > denied0);

                MeClusterHarness.await(idle, () -> false, WINDOW_GAP_MS); // (b)'s requests age out
                // (c) REPLICA: the working backup, with authentication on. Its query interval exceeds the window, as
                // the production interval (60 s) exceeds the production window (11 s).
                try (Backup replica = new Backup(h, "replica", TestAuth.credentialsOf(ClusterSessionClass.REPLICA), 26_703,
                        TimeUnit.SECONDS.toNanos(30))) {
                    final boolean responded = MeClusterHarness.await(idle,
                            () -> replica.events.responses.get() > 0, 20_000);
                    System.out.println("[BACKUP] (c) responses=" + replica.events.responses.get()
                            + " granted=" + sum(h, m -> m.authorisationsGranted())
                            + " deniedBackup=" + sum(h, m -> m.backupQueriesDenied())
                            + " accepted=" + sum(h, m -> m.authAccepted())
                            + " rejected=" + sum(h, m -> m.authRejected()));
                    assertTrue("(c) backup response", responded);
                    final long p0 = replica.events.liveLogPosition.get();
                    assertTrue("(c) live-log recording progresses", MeClusterHarness.await(idle,
                            () -> replica.events.liveLogPosition.get() > Math.max(0, p0), 20_000));
                }
            }

            // (d) an ingress client holding the REPLICA key: pre-config 8; behaviour 1 or 2 per harness run
            try (AeronCluster replica = h.connect(ClusterSessionClass.REPLICA);
                 AeronCluster admin = h.connect(ClusterSessionClass.ADMIN)) {
                assertTrue(MeClusterHarness.offer(replica, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue("(d) pre-config template 8 refused on every member", awaitRefusedOnAll(h, replica, 1));
                assertEquals("(d) no config recorded", 0, h.engineConfigRecords.get());

                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin, () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                final int records = h.union.size();
                for (byte[] f : List.of(MeSessionAuthorityTest.openOrdersRequest(3), engineConfig(2, 2, G, 12, 2_000),
                        create(961, 0, 100, 0, 59_000, 1), releaseFrame(1, order(961)))) {
                    assertTrue(MeClusterHarness.offer(replica, f, 10_000) > 0);
                }
                assertTrue("(d) behaviour 2: templates 7, 8, 9, 10 refused on every member",
                        awaitRefusedOnAll(h, replica, 5));
                assertTrue(h.awaitQuiescence(admin, 10_000));
                assertEquals("(d) journals unchanged", records, h.union.size());
                assertEquals(0, h.unionMismatches.get());
                assertEquals(1, h.engineConfigRecords.get() / MeClusterHarness.MEMBERS);
            }
        }
        // behaviour 1 on a second cluster: templates 7, 8, 9
        try (MeClusterHarness h = new MeClusterHarness(PORT_BASE + 300, "backup-authority-b1")) {
            h.startAll();
            h.awaitLeader(null, 30_000);
            try (AeronCluster replica = h.connect(ClusterSessionClass.REPLICA);
                 AeronCluster admin = h.connect(ClusterSessionClass.ADMIN)) {
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 1, 0, 0, 100_000), 10_000) > 0);
                assertTrue("behaviour-1 config recorded on every member", MeClusterHarness.await(admin, () -> {
                    for (MeClusterHarness.Member m : h.members) {
                        if (m.engine().getEngineConfig() == null) {
                            return false;
                        }
                    }
                    return true;
                }, 20_000));
                final int records = h.union.size();
                for (byte[] f : List.of(MeSessionAuthorityTest.openOrdersRequest(4), engineConfig(2, 1, 0, 0, 100_000),
                        create(962, 0, 100, 0, 59_000, 1))) {
                    assertTrue(MeClusterHarness.offer(replica, f, 10_000) > 0);
                }
                assertTrue("(d) behaviour 1: templates 7, 8, 9 refused on every member", awaitRefusedOnAll(h, replica, 3));
                assertTrue(h.awaitQuiescence(admin, 10_000));
                assertEquals("(d) behaviour 1: journals unchanged", records, h.union.size());
                assertTrue("(d) nothing applied", h.outcomes.isEmpty());
                for (MeClusterHarness.Member m : h.members) {
                    assertEquals("(d) behaviour 1: the config is still version 1 on member " + m.id, 1,
                            m.engine().getEngineConfig().configVersion);
                }
            }
        }
    }

    private static boolean awaitRefusedOnAll(final MeClusterHarness h, final AeronCluster client, final long n) {
        return MeClusterHarness.await(client, () -> {
            for (MeClusterHarness.Member m : h.members) {
                if (m.live && m.unauthorized() < n) {
                    return false;
                }
            }
            return true;
        }, 20_000);
    }
}
