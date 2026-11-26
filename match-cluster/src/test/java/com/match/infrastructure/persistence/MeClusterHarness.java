// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.infrastructure.journal.SettlementJournal;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import io.aeron.Counter;
import io.aeron.archive.ArchiveTool;
import io.aeron.cluster.ClusterControl;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.RecordingLog;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test harness: three {@link AppClusteredService} members
 * in config mode in one JVM on the shared-host port stride (the core pattern of
 * {@code SweepSurvivesLeaderChangeClusterTest}), each with its settlement journal armed and its
 * ring drained in-process into a per-member frame list, plus one reference client on its own
 * media driver. Members can be closed and restarted on the same state directories. Quiet idle
 * strategies and SHARED drivers keep the three members off the dev box's cores.
 *
 * <p>Apply evidence is read the way the bridge reads it: a record 3 for a command id, or a
 * record 4 for a release frame, in the union of the members' journals.</p>
 */
final class MeClusterHarness implements AutoCloseable {

    static final int MEMBERS = 3;
    private static final String INGRESS = "aeron:udp?term-length=1m";
    private static final List<String> HOSTS = List.of("localhost", "localhost", "localhost");

    final int portBase;
    final File root;
    /** Consensus-module session timeout of every member (set before {@link #startAll}). */
    volatile long sessionTimeoutMs = 30_000;
    final Member[] members = new Member[MEMBERS];
    /** The members' archive segment length and log term length; a test that needs purges sets both small before start. */
    int segmentFileLength = 4 * 1024 * 1024;
    String logTermLength = "1m";
    /** Runs on each new service before its ClusterConfig is built (test seams of the service). */
    java.util.function.BiConsumer<Member, AppClusteredService> beforeLaunch = (m, s) -> { };
    /** Runs on each member's ClusterConfig before the launch (an appointed leader, for example). */
    java.util.function.Consumer<Member> afterConfig = m -> { };
    /**
     * Builds each new member's service (default: the production constructor). A test that needs a
     * service around a test-factory engine (the removed legacy launch mode) supplies its own.
     */
    java.util.function.IntFunction<AppClusteredService> serviceFactory = id -> new AppClusteredService();
    /**
     * When set before {@link #startAll}, each member runs on its own media driver launched apart from the
     * archive and the consensus module (the production EXTERNAL driver shape) instead of a ClusteredMediaDriver.
     */
    boolean externalDriver;
    private final MediaDriver clientDriver;
    private final Thread drainer;
    private volatile boolean running = true;

    /** Command id -> {result, orderId, journalSeq}, from the first record 3 seen for it in any journal. */
    final Map<String, long[]> outcomes = new ConcurrentHashMap<>();
    /** releaseSeq -> record 4 seen. */
    final Map<Long, Boolean> releases = new ConcurrentHashMap<>();
    /** The union of all journals, by journalSeq (first copy kept; copies compared). */
    final ConcurrentSkipListMap<Long, byte[]> union = new ConcurrentSkipListMap<>();
    final AtomicInteger unionMismatches = new AtomicInteger();
    final AtomicInteger maxLedgerSize = new AtomicInteger();
    final AtomicInteger capacityOutcomes = new AtomicInteger();
    final AtomicInteger engineConfigRecords = new AtomicInteger();

    /** One member: service, journal, driver, container; restartable on the same directories. */
    final class Member {
        final int id;
        ClusterConfig cfg;
        volatile AppClusteredService service;
        volatile SettlementJournal journal;
        ClusteredMediaDriver driver;
        MediaDriver extDriver;
        io.aeron.archive.Archive extArchive;
        io.aeron.cluster.ConsensusModule extConsensus;
        ClusteredServiceContainer container;
        volatile boolean live;
        final AtomicInteger failFast = new AtomicInteger();
        final List<byte[]> frames = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong lastJournalSeq = new AtomicLong();
        final AtomicInteger records4 = new AtomicInteger();
        /**
         * While set, this member's driver drops every incoming DATA frame of the cluster log stream
         * (a partition of the log only): its archive records nothing new and its consensus module
         * appends nothing, while the consensus channel (heartbeats, append positions) still flows,
         * so a leader keeps its role and appends entries no follower holds.
         */
        volatile boolean logPartitioned;

        Member(int id) {
            this.id = id;
        }

        String driverDir() {
            return new File(root, "driver" + id).getAbsolutePath();
        }

        File baseDir() {
            return new File(root, "node" + id);
        }

        private io.aeron.driver.ext.LossGenerator logLoss() {
            return new io.aeron.driver.ext.LossGenerator() {
                @Override
                public boolean shouldDropFrame(java.net.InetSocketAddress address, UnsafeBuffer buffer, int length) {
                    return false;
                }

                @Override
                public boolean shouldDropFrame(java.net.InetSocketAddress address, UnsafeBuffer buffer, int streamId,
                                               int sessionId, int termId, int termOffset, int length) {
                    return logPartitioned && streamId == io.aeron.cluster.ConsensusModule.Configuration.LOG_STREAM_ID_DEFAULT;
                }
            };
        }

        void start() {
            logPartitioned = false;
            final AppClusteredService s = serviceFactory.apply(id);
            s.failFast = failFast::incrementAndGet; // never halt the test JVM
            s.failFastIncompatible = failFast::incrementAndGet; // the exit-78 seam: never halt the test JVM either
            final SettlementJournal j = new SettlementJournal(4 << 20);
            s.setSettlementJournal(j);
            beforeLaunch.accept(this, s);
            cfg = ClusterConfig.create(id, HOSTS, HOSTS, portBase, s);
            cfg.memberCompat(cfg.memberCompat(), com.openexchange.cluster.MemberCompat.testRefusal("rig"));
            cfg.baseDir(baseDir());
            cfg.aeronDirectoryName(driverDir());
            cfg.idleStrategySupplier(BackoffIdleStrategy::new);
            cfg.errorHandler(t -> {
                if (live) {
                    System.err.println("[ME3] member " + id + " Aeron error: " + t);
                }
            });
            cfg.archiveContext().segmentFileLength(segmentFileLength);
            cfg.consensusModuleContext()
                    .ingressChannel(INGRESS)
                    .logChannel("aeron:udp?term-length=" + logTermLength)
                    .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                    .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                    .electionTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                    .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(2))
                    .terminationTimeoutNs(TimeUnit.SECONDS.toNanos(2))
                    .sessionTimeoutNs(TimeUnit.MILLISECONDS.toNanos(sessionTimeoutMs));
            afterConfig.accept(this);
            final MediaDriver.Context driverCtx = cfg.mediaDriverContext().dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED)
                    .sharedIdleStrategy(new BackoffIdleStrategy())
                    .receiveChannelEndpointSupplier((channel, dispatcher, status, context) ->
                            new io.aeron.driver.ext.DebugReceiveChannelEndpoint(channel, dispatcher, status,
                                    context, logLoss(), noLoss()))
                    .mtuLength(8192);
            if (externalDriver) {
                extDriver = MediaDriver.launch(driverCtx);
                extArchive = io.aeron.archive.Archive.launch(cfg.archiveContext()
                        .threadingMode(io.aeron.archive.ArchiveThreadingMode.SHARED));
                extConsensus = io.aeron.cluster.ConsensusModule.launch(cfg.consensusModuleContext());
            } else {
                driver = ClusteredMediaDriver.launch(driverCtx, cfg.archiveContext(), cfg.consensusModuleContext());
            }
            container = ClusteredServiceContainer.launch(cfg.clusteredServiceContext());
            journal = j;
            service = s;
            live = true;
        }

        /** Close container then driver (a stop, as the core kill pattern); the journal ring is drained last. */
        void close() {
            live = false;
            CloseHelper.quietClose(container);
            CloseHelper.quietClose(driver);
            CloseHelper.quietClose(extConsensus);
            CloseHelper.quietClose(extArchive);
            CloseHelper.quietClose(extDriver);
            driver = null;
            extConsensus = null;
            extArchive = null;
            extDriver = null;
            drainMember(this);
            journal = null;
        }

        Cluster.Role role() {
            if (!live) {
                throw new IllegalStateException("member " + id + " is closed");
            }
            final Counter counter = cfg.consensusModuleContext().clusterNodeRoleCounter();
            return counter == null ? Cluster.Role.FOLLOWER : Cluster.Role.get(counter);
        }

        Engine engine() {
            try {
                final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("engine");
                f.setAccessible(true);
                return (Engine) f.get(service);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        /** This member's election is CLOSED (it has joined the current term, not mid-startup election). */
        boolean electionClosed() {
            final Counter c = cfg.consensusModuleContext().electionStateCounter();
            return c != null && !c.isClosed() && io.aeron.cluster.ElectionState.get(c) == io.aeron.cluster.ElectionState.CLOSED;
        }

        /** Frames this member refused by the session-class rows. */
        long unauthorized() {
            return demuxer().unauthorizedRejectCount();
        }

        SbeDemuxer demuxer() {
            try {
                final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("sbeDemuxer");
                f.setAccessible(true);
                return (SbeDemuxer) f.get(service);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        /** The authenticator ClusterConfig installed on this member's consensus module. */
        com.openexchange.cluster.auth.ClusterAuthenticator authenticator() {
            return (com.openexchange.cluster.auth.ClusterAuthenticator) field("sessionAuthenticator");
        }

        /** Authenticator and class-table counters of this member; 0 where none is installed (a build without authentication). */
        long authAccepted() {
            final var a = authenticator();
            return a == null ? 0 : a.acceptedCount();
        }

        long authRejected() {
            final var a = authenticator();
            return a == null ? 0 : a.rejectedCount();
        }

        long backupQueriesDenied() {
            final var z = authorisation();
            return z == null ? 0 : z.deniedCount(com.openexchange.cluster.auth.ClassAuthorisationService.BACKUP_QUERY);
        }

        long authorisationsGranted() {
            final var z = authorisation();
            return z == null ? 0 : z.grantedCount();
        }

        com.openexchange.cluster.auth.ClassAuthorisationService authorisation() {
            return (com.openexchange.cluster.auth.ClassAuthorisationService) field("sessionAuthorisation");
        }

        private Object field(final String name) {
            try {
                final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(service);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        /** This member's {@code /metrics} text, rendered by its own registry (the HTTP port is not needed). */
        String metrics() {
            final Object server = field("metricsServer");
            if (server == null) {
                throw new IllegalStateException("member " + id + " has no metrics registry yet");
            }
            return ((com.match.infrastructure.metrics.NodeMetricsServer) server).render();
        }

        /**
         * {match_snapshot_last_log_position, match_snapshot_state_hash} as this member renders them; the hash
         * reads -1 when the series is omitted.
         */
        long[] snapshotSeries() {
            return parseSnapshotSeries(metrics());
        }

        /** The member's ledger v2 block bytes, via the codec's writer (quiescent members only). */
        byte[] ledgerBlock() {
            return DurableLedgerProbe.ledgerBlock(engine());
        }

        /** Stop position of this member's (closed) last log recording, from its archive catalog. */
        long logStopPosition() {
            if (live) {
                throw new IllegalStateException("read the catalog of a closed member only");
            }
            final long recordingId;
            try (RecordingLog log = new RecordingLog(new File(baseDir(), ClusterConfig.CLUSTER_SUB_DIR), false)) {
                recordingId = log.findLastTermRecordingId();
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8)) {
                ArchiveTool.describeRecording(ps, new File(baseDir(), ClusterConfig.ARCHIVE_SUB_DIR), recordingId);
            }
            final Matcher m = Pattern.compile("stopPosition=(-?\\d+)").matcher(out.toString(StandardCharsets.UTF_8));
            if (!m.find()) {
                throw new AssertionError("no stopPosition for recording " + recordingId + ": " + out);
            }
            return Long.parseLong(m.group(1));
        }

        /** The start position of this live member's log recording, read from its archive (0 until a purge). */
        long logStart() {
            final long recordingId;
            try (RecordingLog log = new RecordingLog(new File(baseDir(), ClusterConfig.CLUSTER_SUB_DIR), false)) {
                recordingId = log.findLastTermRecordingId();
            }
            try (io.aeron.archive.client.AeronArchive archive = io.aeron.archive.client.AeronArchive.connect(
                    cfg.aeronArchiveContext().clone().aeronDirectoryName(driverDir()))) {
                return archive.getStartPosition(recordingId);
            }
        }

        /** The log position of this member's newest valid service snapshot, -1 when it has none. */
        long newestSnapshot() {
            long newest = -1;
            try (RecordingLog log = new RecordingLog(new File(baseDir(), ClusterConfig.CLUSTER_SUB_DIR), false)) {
                for (final RecordingLog.Entry e : log.entries()) {
                    if (e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && e.isValid && e.serviceId == 0) {
                        newest = Math.max(newest, e.logPosition);
                    }
                }
            }
            return newest;
        }

        /** The off-box watermark wired into this member's pruner; null while the pruner is not started. */
        com.openexchange.cluster.OffBoxWatermark offBox() {
            final com.openexchange.cluster.SnapshotLogPruner p =
                    (com.openexchange.cluster.SnapshotLogPruner) field("logPruner");
            return p == null ? null : p.offBoxWatermark();
        }

        /** The pruner's last error text, or null. */
        String prunerLastError() {
            final com.openexchange.cluster.SnapshotLogPruner p =
                    (com.openexchange.cluster.SnapshotLogPruner) field("logPruner");
            return p == null ? null : p.lastError();
        }

        /** {recorded position of the live log recording, commit position} of a live member. */
        long[] livePositions() {
            final long recordingId;
            try (RecordingLog log = new RecordingLog(new File(baseDir(), ClusterConfig.CLUSTER_SUB_DIR), false)) {
                recordingId = log.findLastTermRecordingId();
            }
            final org.agrona.concurrent.status.CountersReader counters =
                    cfg.consensusModuleContext().aeron().countersReader();
            final int id = io.aeron.archive.status.RecordingPos.findCounterIdByRecording(counters, recordingId);
            return new long[]{id < 0 ? -1 : counters.getCounterValue(id),
                    cfg.consensusModuleContext().commitPositionCounter().get()};
        }

        /** {leadershipTermId, termBaseLogPosition} of the last term in this (closed) member's recording log. */
        long[] lastTerm() {
            if (live) {
                throw new IllegalStateException("read the recording log of a closed member only");
            }
            try (RecordingLog log = new RecordingLog(new File(baseDir(), ClusterConfig.CLUSTER_SUB_DIR), false)) {
                final RecordingLog.Entry e = log.findLastTerm();
                return new long[]{e.leadershipTermId, e.termBaseLogPosition};
            }
        }

        /** Highest record 4 releaseSeq seen on this member, or whether one exists for {@code releaseSeq}. */
        boolean hasRelease(long releaseSeq) {
            synchronized (frames) {
                for (byte[] f : frames) {
                    if (templateId(f) == 4 && release(f).releaseSeq() == releaseSeq) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    MeClusterHarness(int portBase, String name) {
        this.portBase = portBase;
        System.setProperty("cluster.port.stride", Integer.toString(com.openexchange.cluster.ClusterPorts.SHARED_HOST_STRIDE));
        com.openexchange.cluster.ClusterPorts.resetStrideForTest();
        System.setProperty("match.engine.from.config", "true");
        System.setProperty("transport.idle.mode", "sleep");
        System.setProperty("transport.driver.threading", "shared");
        System.setProperty("transport.snapshot.term.length", "4m");
        System.setProperty("snapshot.log.bytes", "0");
        System.setProperty("snapshot.interval.minutes", "0");
        System.setProperty("snapshot.prune.enabled", "false");
        TestAuth.install(); // Every member authenticates against the test key table
        this.root = new File(System.getProperty("java.io.tmpdir"), "me3-" + name + "-" + portBase);
        IoUtil.delete(root, true);
        for (int i = 0; i < MEMBERS; i++) {
            members[i] = new Member(i);
        }
        clientDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(new File(root, "client").getAbsolutePath())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(new SleepingMillisIdleStrategy(1)));
        drainer = new Thread(this::drainLoop, "me3-journal-drainer");
        drainer.setDaemon(true);
        drainer.start();
    }

    void startAll() {
        for (Member m : members) {
            m.start();
        }
    }

    private static io.aeron.driver.ext.LossGenerator noLoss() {
        return (address, buffer, length) -> false;
    }

    private void drainLoop() {
        while (running) {
            int work = 0;
            for (Member m : members) {
                work += drainMember(m);
            }
            if (work == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private final MessageHeaderDecoder header = new MessageHeaderDecoder();

    private synchronized int drainMember(Member m) {
        final SettlementJournal j = m.journal;
        if (j == null) {
            return 0;
        }
        return j.ringBuffer().read((t, b, i, n) -> {
            final byte[] f = new byte[n];
            b.getBytes(i, f);
            m.frames.add(f);
            observe(m, f);
        }, 4096);
    }

    static int templateId(byte[] f) {
        return new UnsafeBuffer(f).getShort(2, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    static long journalSeq(byte[] f) {
        return new UnsafeBuffer(f).getLong(8, ByteOrder.LITTLE_ENDIAN);
    }

    static CommandOutcomeDecoder outcome(byte[] f) {
        return new CommandOutcomeDecoder().wrapAndApplyHeader(new UnsafeBuffer(f), 0, new MessageHeaderDecoder());
    }

    static CommandReleaseDecoder release(byte[] f) {
        return new CommandReleaseDecoder().wrapAndApplyHeader(new UnsafeBuffer(f), 0, new MessageHeaderDecoder());
    }

    static String key(long h, long l) {
        return h + ":" + l;
    }

    private void observe(Member m, byte[] f) {
        final long seq = journalSeq(f);
        m.lastJournalSeq.set(seq);
        final byte[] prior = union.putIfAbsent(seq, f);
        if (prior != null && !Arrays.equals(prior, f)) {
            unionMismatches.incrementAndGet();
            System.err.println("[ME3] journalSeq " + seq + " differs between journals (member " + m.id + ")");
        }
        switch (templateId(f)) {
            case 3: {
                final CommandOutcomeDecoder o = outcome(f);
                maxLedgerSize.accumulateAndGet(o.ledgerSize(), Math::max);
                if (o.result() == 3) {
                    capacityOutcomes.incrementAndGet();
                }
                outcomes.putIfAbsent(key(o.commandIdHigh(), o.commandIdLow()),
                        new long[]{o.result(), o.orderId(), seq});
                break;
            }
            case 4: {
                final CommandReleaseDecoder r = release(f);
                maxLedgerSize.accumulateAndGet(r.ledgerSize(), Math::max);
                releases.put(r.releaseSeq(), Boolean.TRUE);
                m.records4.incrementAndGet();
                break;
            }
            case 5:
                engineConfigRecords.incrementAndGet();
                break;
            default:
                break;
        }
    }

    Member awaitLeader(Member except, long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Member m : members) {
                if (m.live && m != except && m.role() == Cluster.Role.LEADER) {
                    return m;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no leader" + (except == null ? "" : " other than member " + except.id)
                + " within " + timeoutMs + " ms");
    }

    String liveIngressEndpoints() {
        final StringBuilder sb = new StringBuilder();
        for (Member m : members) {
            if (!m.live) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(m.id).append("=localhost:")
                    .append(ClusterConfig.calculatePort(m.id, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET));
        }
        return sb.toString();
    }

    /** A FORWARDER client (the durable lane's class at behaviour 2) through the live members' ingress endpoints. */
    AeronCluster connect() throws InterruptedException {
        return connect(com.openexchange.cluster.auth.ClusterSessionClass.FORWARDER);
    }

    /** A client of {@code sessionClass} presenting the test key of that class. */
    AeronCluster connect(com.openexchange.cluster.auth.ClusterSessionClass sessionClass) throws InterruptedException {
        return connect(TestAuth.credentials(sessionClass), (sessionId, ts, buffer, offset, length, h) -> { }, 60_000);
    }

    /** A client with explicit credentials and egress listener, on the harness's own driver. */
    AeronCluster connect(final io.aeron.security.CredentialsSupplier credentials,
                         final io.aeron.cluster.client.EgressListener listener,
                         final long timeoutMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return AeronCluster.connect(new AeronCluster.Context()
                        .credentialsSupplier(credentials)
                        .egressListener(listener)
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel(INGRESS)
                        .aeronDirectoryName(clientDriver.aeronDirectoryName())
                        .ingressEndpoints(liveIngressEndpoints())
                        .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5))
                        // A client may wait out a whole-cluster restart (MeRestoredEgressTest)
                        .newLeaderTimeoutNs(TimeUnit.SECONDS.toNanos(60)));
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("could not connect via " + liveIngressEndpoints(), last);
    }

    /** Offer until accepted (polling egress); returns the accepted position. */
    static long offer(AeronCluster client, byte[] frame, long timeoutMs) {
        final UnsafeBuffer b = new UnsafeBuffer(frame);
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final BackoffIdleStrategy idle = new BackoffIdleStrategy();
        while (System.currentTimeMillis() < deadline) {
            final long p = client.offer(b, 0, frame.length);
            if (p > 0) {
                return p;
            }
            client.pollEgress();
            idle.idle();
        }
        return -1;
    }

    /** Poll egress and send keep-alives until {@code done} or the timeout; returns done. */
    static boolean await(AeronCluster client, java.util.function.BooleanSupplier done, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        long keepAlive = System.currentTimeMillis();
        final BackoffIdleStrategy idle = new BackoffIdleStrategy();
        while (System.currentTimeMillis() < deadline) {
            if (done.getAsBoolean()) {
                return true;
            }
            if (client != null) {
                client.pollEgress();
                if (System.currentTimeMillis() - keepAlive >= 1_000) {
                    client.sendKeepAlive();
                    keepAlive = System.currentTimeMillis();
                }
            }
            idle.idle();
        }
        return done.getAsBoolean();
    }

    /**
     * Wait until {@code m} has finished its (startup) election and its commit position equals the
     * leader's for 500 ms. A leader killed while a restarted member is still inside its startup election makes
     * that election a leader startup, and Aeron then closes every open session with TIMEOUT
     * ({@code SessionManager.prepareSessionsForNewTerm(isStartup)}); tests that fail over after a restart wait
     * here first.
     */
    boolean awaitJoined(final Member m, final Member leader, final AeronCluster client, final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        long since = -1;
        while (System.currentTimeMillis() < deadline) {
            final long[] p = m.livePositions();
            if (m.electionClosed() && p[1] > 0 && p[1] == leader.livePositions()[1]) {
                if (since < 0) {
                    since = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - since >= 500) {
                    return true;
                }
            } else {
                since = -1;
            }
            await(client, () -> false, 50);
        }
        return false;
    }

    /** Toggle a snapshot on the leader and wait until the toggle returns to NEUTRAL. */
    void snapshot(Member leader, AeronCluster client) {
        final Counter toggle = leader.cfg.consensusModuleContext().controlToggleCounter();
        if (!ClusterControl.ToggleState.SNAPSHOT.toggle(toggle)) {
            throw new AssertionError("snapshot toggle refused");
        }
        if (!await(client, () -> ClusterControl.ToggleState.get(toggle) == ClusterControl.ToggleState.NEUTRAL, 30_000)) {
            throw new AssertionError("snapshot did not complete within 30 s");
        }
    }

    /** {position, hash} from a {@code /metrics} body; the hash is -1 when the series is omitted. */
    static long[] parseSnapshotSeries(final String body) {
        long position = Long.MIN_VALUE;
        long hash = -1;
        for (String line : body.split("\n")) {
            if (line.startsWith("match_snapshot_last_log_position ")) {
                position = Long.parseLong(line.substring("match_snapshot_last_log_position ".length()));
            } else if (line.startsWith("match_snapshot_state_hash ")) {
                hash = Long.parseLong(line.substring("match_snapshot_state_hash ".length()));
            }
        }
        if (position == Long.MIN_VALUE) {
            throw new AssertionError("no match_snapshot_last_log_position series in:\n" + body);
        }
        return new long[]{position, hash};
    }

    /**
     * Wait until every live member renders a snapshot position above {@code after} and they all agree on it;
     * returns the rendered {position, hash} by member id (null for a closed member). On timeout returns what the
     * members last rendered, so the caller's assertion names the disagreement.
     */
    long[][] awaitSnapshotSeries(final AeronCluster client, final long after, final long timeoutMs) {
        final long[][] seen = new long[MEMBERS][];
        await(client, () -> {
            long position = Long.MIN_VALUE;
            boolean done = true;
            for (Member m : members) {
                seen[m.id] = m.live ? m.snapshotSeries() : null;
                if (seen[m.id] == null) {
                    continue;
                }
                if (seen[m.id][0] <= after) {
                    done = false;
                } else if (position == Long.MIN_VALUE) {
                    position = seen[m.id][0];
                } else if (seen[m.id][0] != position) {
                    done = false;
                }
            }
            return done;
        }, timeoutMs);
        return seen;
    }

    /** Wait until every live member journaled up to the same journalSeq and it stayed for 500 ms. */
    boolean awaitQuiescence(AeronCluster client, long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        long stableSince = -1;
        long last = -1;
        while (System.currentTimeMillis() < deadline) {
            long seq = -1;
            boolean equal = true;
            for (Member m : members) {
                if (!m.live) {
                    continue;
                }
                final long s = m.lastJournalSeq.get();
                if (seq == -1) {
                    seq = s;
                } else if (s != seq) {
                    equal = false;
                }
            }
            final long now = System.currentTimeMillis();
            if (equal && seq == last) {
                if (stableSince < 0) {
                    stableSince = now;
                } else if (now - stableSince >= 500) {
                    return true;
                }
            } else {
                stableSince = -1;
            }
            last = equal ? seq : -1;
            await(client, () -> false, 50);
        }
        return false;
    }

    @Override
    public void close() {
        running = false;
        try {
            drainer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Member m : members) {
            if (m.live) {
                m.close();
            }
        }
        CloseHelper.quietClose(clientDriver);
        IoUtil.delete(root, true);
        TestAuth.clear();
        for (String p : new String[]{"cluster.port.stride", "match.engine.from.config", "transport.idle.mode",
                "transport.driver.threading", "transport.snapshot.term.length", "snapshot.log.bytes",
                "snapshot.interval.minutes", "snapshot.prune.enabled"}) {
            System.clearProperty(p);
        }
        com.openexchange.cluster.ClusterPorts.resetStrideForTest();
    }
}
