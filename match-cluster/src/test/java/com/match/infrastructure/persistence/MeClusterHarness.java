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
 * RC2 S4 test harness (contract 9.1, topology J-ME3): three {@link AppClusteredService} members
 * in config mode in one JVM on the shared-host port stride (the AE pattern of
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
    final Member[] members = new Member[MEMBERS];
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
            final AppClusteredService s = new AppClusteredService();
            s.failFast = failFast::incrementAndGet; // never halt the test JVM
            final SettlementJournal j = new SettlementJournal(4 << 20);
            s.setSettlementJournal(j);
            cfg = ClusterConfig.create(id, HOSTS, HOSTS, portBase, s);
            cfg.baseDir(baseDir());
            cfg.aeronDirectoryName(driverDir());
            cfg.idleStrategySupplier(BackoffIdleStrategy::new);
            cfg.errorHandler(t -> {
                if (live) {
                    System.err.println("[ME3] member " + id + " Aeron error: " + t);
                }
            });
            cfg.archiveContext().segmentFileLength(4 * 1024 * 1024);
            cfg.consensusModuleContext()
                    .ingressChannel(INGRESS)
                    .logChannel("aeron:udp?term-length=1m")
                    .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))
                    .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                    .electionTimeoutNs(TimeUnit.SECONDS.toNanos(1))
                    .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(2))
                    .terminationTimeoutNs(TimeUnit.SECONDS.toNanos(2))
                    .sessionTimeoutNs(TimeUnit.SECONDS.toNanos(30));
            driver = ClusteredMediaDriver.launch(
                    cfg.mediaDriverContext().dirDeleteOnStart(true).dirDeleteOnShutdown(true)
                            .threadingMode(ThreadingMode.SHARED)
                            .sharedIdleStrategy(new BackoffIdleStrategy())
                            .receiveChannelEndpointSupplier((channel, dispatcher, status, context) ->
                                    new io.aeron.driver.ext.DebugReceiveChannelEndpoint(channel, dispatcher, status,
                                            context, logLoss(), noLoss()))
                            .mtuLength(8192),
                    cfg.archiveContext(), cfg.consensusModuleContext());
            container = ClusteredServiceContainer.launch(cfg.clusteredServiceContext());
            journal = j;
            service = s;
            live = true;
        }

        /** Close container then driver (a stop, as the AE kill pattern); the journal ring is drained last. */
        void close() {
            live = false;
            CloseHelper.quietClose(container);
            CloseHelper.quietClose(driver);
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

        /** The member's ledger v2 block bytes, via the codec's writer (quiescent members only). */
        byte[] ledgerBlock() {
            return S4Probe.ledgerBlock(engine());
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

    /** A client through the live members' ingress endpoints, on the harness's own driver. */
    AeronCluster connect() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 60_000;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                return AeronCluster.connect(new AeronCluster.Context()
                        .egressListener((sessionId, ts, buffer, offset, length, h) -> { })
                        .egressChannel("aeron:udp?endpoint=localhost:0")
                        .ingressChannel(INGRESS)
                        .aeronDirectoryName(clientDriver.aeronDirectoryName())
                        .ingressEndpoints(liveIngressEndpoints())
                        .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5)));
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
        for (String p : new String[]{"cluster.port.stride", "match.engine.from.config", "transport.idle.mode",
                "transport.driver.threading", "transport.snapshot.term.length", "snapshot.log.bytes",
                "snapshot.interval.minutes", "snapshot.prune.enabled"}) {
            System.clearProperty(p);
        }
        com.openexchange.cluster.ClusterPorts.resetStrideForTest();
    }
}
