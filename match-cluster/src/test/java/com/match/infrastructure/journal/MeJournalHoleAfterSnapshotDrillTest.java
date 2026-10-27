// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.DurableOrderCommandEncoder;
import com.match.infrastructure.generated.EngineConfigEncoder;
import com.match.infrastructure.generated.EngineImpl;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.journal.MeJournalArchiveReader.Frame;
import com.match.infrastructure.journal.MeJournalArchiveReader.Recording;
import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.RecordingLog;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * RC2 S3 contract section 9 and 11 (RR2 M3, critic K8), T1 child-process drill. Three forked JVMs
 * run the production bootstrap ({@link MeJournalDrillNodeMain}) as a three-member behaviour-2
 * cluster with the settlement journal on. A follower's journal writer is held (drain gate) while
 * load flows, the leader snapshots at P, and the follower is killed with SIGKILL right after its
 * own snapshot: the undrained entries below P are gone from that node's journal for good, while
 * the survivors keep the whole dense sequence (drill A). The control (drill B) drains before the
 * kill, so the restart re-journals every entry above P byte for byte into a new recording and no
 * node has a hole.
 *
 * <p>Opt-in: {@code -Dmatch.drills=true}; run ALONE under {@code taskset -c 0-11} (dev-box rule).</p>
 */
public class MeJournalHoleAfterSnapshotDrillTest {

    private static final long G = 0x1234_5678_9ABC_DEF1L;
    private static final int NODES = 3;
    private static final int STRIDE = 100;
    private static final int CLIENT_FACING_OFFSET = 2;
    private static final long DRAIN_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(90);
    private static final long COMMAND_PACING_NS = TimeUnit.MILLISECONDS.toNanos(2); // about 500 commands/s

    private Path root;
    private File shmRoot;
    private int portBase;
    private final Process[] nodes = new Process[NODES];
    private final int[] starts = new int[NODES];
    private MediaDriver clientDriver;
    private io.aeron.cluster.client.AeronCluster client;
    private MeJournalArchiveReader reader;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final Load load = new Load();
    private final StringBuilder log = new StringBuilder();

    @Before
    public void startCluster() throws Exception {
        Assume.assumeTrue("opt-in drill: -Dmatch.drills=true", Boolean.getBoolean("match.drills"));
        root = Files.createTempDirectory("s3drill");
        shmRoot = new File("/dev/shm/s3drill-" + UUID.randomUUID());
        assertTrue(shmRoot.mkdirs());
        portBase = 30_000 + ThreadLocalRandom.current().nextInt(0, 30) * 1_000;
        for (int i = 0; i < NODES; i++) {
            start(i);
        }
        clientDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
                .aeronDirectoryName(new File(shmRoot, "client").getAbsolutePath())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .threadingMode(ThreadingMode.SHARED)
                .sharedIdleStrategy(new SleepingMillisIdleStrategy(1)));
        reader = new MeJournalArchiveReader(clientDriver.aeronDirectoryName());
        awaitLeader();
        final StringBuilder ingress = new StringBuilder();
        for (int i = 0; i < NODES; i++) {
            ingress.append(i).append("=localhost:").append(port(i, CLIENT_FACING_OFFSET)).append(i + 1 < NODES ? "," : "");
        }
        // RC2 S6: the FRESH is ADMIN's (R2); the durable load is the FORWARDER's (R3).
        client = connectAs(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN, ingress.toString());
        send(engineConfigFresh());
        CloseHelper.quietClose(client);
        client = connectAs(com.openexchange.cluster.auth.ClusterSessionClass.FORWARDER, ingress.toString());
        awaitDrained(allNodes());
        note("cluster up: portBase=" + portBase + " root=" + root);
    }

    @After
    public void stopCluster() {
        CloseHelper.quietClose(client);
        for (final Process p : nodes) {
            if (p != null) {
                p.destroyForcibly();
                try {
                    p.waitFor(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        CloseHelper.quietClose(clientDriver);
        if (log.length() > 0) {
            System.out.println(log);
        }
        if (shmRoot != null) {
            IoUtil.delete(shmRoot, true);
        }
        if (root != null && !Boolean.getBoolean("match.drills.keep")) {
            IoUtil.delete(root.toFile(), true);
        }
    }

    // ---------------------------------------------------------------- drill A

    @Test(timeout = 600_000)
    public void holeIsConfinedToTheNodeKilledRightAfterItsSnapshot() throws Exception {
        final int victim = follower();
        // 1. Load, pause, drain; a = the victim's max seq.
        sendCommands(1_000);
        final long a = awaitDrained(allNodes());
        note("drill A: victim=" + victim + " a=" + a);

        // 2. Hold the victim's writer; load; pause.
        final File hold = holdFile(victim);
        assertTrue(hold.createNewFile());
        sendCommands(400);

        // 3. Snapshot at P; the victim's own snapshot entry; checks; kill -9.
        final long p = snapshotAndAwaitVictimEntry(victim);
        assertEquals("the victim's ring never back-pressured (it would have parked before P)", 0L,
                metric(victim, "match_journal_ring_backpressure_events_total"));
        assertEquals("exactly one snapshot since the run began", 1, snapshotPositions(victim).size());
        final int exit = kill(victim);
        assertEquals("SIGKILL", 137, exit);
        note("drill A: P=" + p + " victim killed, exit " + exit);

        // 4. Load while the victim is down; restart it without the hold; pause; drain.
        sendCommands(300);
        assertTrue(hold.delete());
        restartAfterMarkFilesExpire(victim);
        final long s = awaitDrained(allNodes());

        // 5. Survivors dense 1..S and byte-equal; the victim misses exactly (a, h].
        final Map<Integer, List<Recording>> journals = readAll();
        final TreeMap<Long, Frame> survivor = assertSurvivorsDenseAndEqual(journals, victim, s);
        long h = 0;
        for (final Frame f : survivor.values()) {
            if (f.egressSeq() <= p) {
                h = Math.max(h, f.journalSeq());
            }
        }
        final TreeMap<Long, Frame> victimSeqs = new TreeMap<>();
        for (final Recording r : journals.get(victim)) {
            for (final Frame f : r.frames()) {
                final Frame prior = victimSeqs.put(f.journalSeq(), f);
                if (prior != null) {
                    assertArrayEquals("victim duplicate seq " + f.journalSeq(), prior.bytes(), f.bytes());
                }
                assertArrayEquals("victim frame of seq " + f.journalSeq() + " equals the survivors'",
                        survivor.get(f.journalSeq()).bytes(), f.bytes());
            }
        }
        final TreeSet<Long> missing = new TreeSet<>();
        for (long seq = 1; seq <= s; seq++) {
            if (!victimSeqs.containsKey(seq)) {
                missing.add(seq);
            }
        }
        note("drill A: S=" + s + " h=" + h + " hole=" + (missing.isEmpty() ? "none"
                : "[" + missing.first() + ", " + missing.last() + "] (" + missing.size() + " seqs)")
                + " victim recordings=" + recordingIds(journals.get(victim)));
        assertTrue("non-vacuous: h - a >= 200 (h=" + h + " a=" + a + ")", h - a >= 200);
        assertEquals("the victim misses exactly the contiguous range (a, h]", range(a + 1, h), missing);
        final TreeSet<Long> union = new TreeSet<>(survivor.keySet());
        union.addAll(victimSeqs.keySet());
        assertEquals("the union over nodes is dense 1..S (one crash)", range(1, s), union);
    }

    // ---------------------------------------------------------------- drill B (control)

    @Test(timeout = 600_000)
    public void drainedRingLeavesNoHoleAndReplayReJournalsByteForByte() throws Exception {
        final int victim = follower();
        // 1. Load, pause, drain (the gate is never held).
        sendCommands(1_000);
        awaitDrained(allNodes());
        // 2. Snapshot at P and the victim's recording-log entry.
        final long p = snapshotAndAwaitVictimEntry(victim);
        // 3. N = 300 above P, pause, drain: every entry above P is in the victim's archive.
        sendCommands(300);
        final long s = awaitDrained(allNodes());
        // 4. Checks, kill -9, restart; it replays from P and re-journals into a new recording.
        assertEquals(0L, metric(victim, "match_journal_ring_backpressure_events_total"));
        assertEquals("exactly one snapshot since the run began", 1, snapshotPositions(victim).size());
        final int exit = kill(victim);
        assertEquals("SIGKILL", 137, exit);
        restartAfterMarkFilesExpire(victim);
        assertEquals("no new seq after the restart (no load)", s, awaitDrained(allNodes()));

        // 5. No hole anywhere; the duplicated set is exactly the seqs above P, byte for byte.
        final Map<Integer, List<Recording>> journals = readAll();
        final TreeMap<Long, Frame> survivor = assertSurvivorsDenseAndEqual(journals, victim, s);
        final TreeSet<Long> aboveP = new TreeSet<>();
        for (final Frame f : survivor.values()) {
            if (f.egressSeq() > p) {
                aboveP.add(f.journalSeq());
            }
        }
        final List<Recording> victimRecordings = journals.get(victim);
        assertEquals("the restart opened a new recording", 2, victimRecordings.size());
        final Map<Long, List<Frame>> bySeq = new HashMap<>();
        for (final Recording r : victimRecordings) {
            for (final Frame f : r.frames()) {
                bySeq.computeIfAbsent(f.journalSeq(), k -> new ArrayList<>()).add(f);
            }
        }
        final TreeSet<Long> twice = new TreeSet<>();
        for (final Map.Entry<Long, List<Frame>> e : bySeq.entrySet()) {
            assertTrue("seq " + e.getKey() + " held at most twice", e.getValue().size() <= 2);
            if (e.getValue().size() == 2) {
                twice.add(e.getKey());
                assertTrue("one copy in each recording", e.getValue().get(0).recordingId() != e.getValue().get(1).recordingId());
            }
            for (final Frame f : e.getValue()) {
                assertArrayEquals("seq " + e.getKey() + " byte-identical in R1, R2 and on the survivors",
                        survivor.get(e.getKey()).bytes(), f.bytes());
            }
        }
        note("drill B: victim=" + victim + " P=" + p + " S=" + s + " duplicated=" + twice.size()
                + (twice.isEmpty() ? "" : " [" + twice.first() + ", " + twice.last() + "]")
                + " victim recordings=" + recordingIds(victimRecordings));
        assertEquals("no hole on the victim", range(1, s), new TreeSet<>(bySeq.keySet()));
        assertEquals("duplicated set == the seqs with egressSeq > P written before the kill", aboveP, twice);
        assertTrue("non-vacuous: at least 200 duplicated seqs (" + twice.size() + ")", twice.size() >= 200);
    }

    // ---------------------------------------------------------------- assertions

    private TreeMap<Long, Frame> assertSurvivorsDenseAndEqual(final Map<Integer, List<Recording>> journals,
                                                            final int victim, final long s) {
        TreeMap<Long, Frame> first = null;
        for (int i = 0; i < NODES; i++) {
            if (i == victim) {
                continue;
            }
            final List<Frame> frames = new ArrayList<>();
            for (final Recording r : journals.get(i)) {
                frames.addAll(r.frames());
            }
            final TreeMap<Long, Frame> seqs = new TreeMap<>();
            for (int k = 0; k < frames.size(); k++) {
                final Frame f = frames.get(k);
                assertEquals("survivor " + i + " frame " + k + ": dense from 1 in order", k + 1L, f.journalSeq());
                assertEquals("survivor " + i + " frame " + k + ": meEpoch G", G, f.meEpoch());
                seqs.put(f.journalSeq(), f);
            }
            assertEquals("survivor " + i + ": dense 1..S", s, frames.size());
            if (first == null) {
                first = seqs;
            } else {
                for (final Map.Entry<Long, Frame> e : seqs.entrySet()) {
                    assertArrayEquals("survivors byte-equal at seq " + e.getKey(), first.get(e.getKey()).bytes(),
                            e.getValue().bytes());
                }
            }
        }
        assertNotNull(first);
        return first;
    }

    private static TreeSet<Long> range(final long fromInclusive, final long toInclusive) {
        final TreeSet<Long> out = new TreeSet<>();
        for (long v = fromInclusive; v <= toInclusive; v++) {
            out.add(v);
        }
        return out;
    }

    private static List<Long> recordingIds(final List<Recording> recordings) {
        final List<Long> out = new ArrayList<>();
        for (final Recording r : recordings) {
            out.add(r.recordingId());
        }
        return out;
    }

    // ---------------------------------------------------------------- the members

    private int port(final int node, final int offset) {
        return portBase + node * STRIDE + offset;
    }

    private int metricsPort(final int node) {
        return portBase + 900 + node;
    }

    private File holdFile(final int node) {
        return root.resolve("hold-node" + node).toFile();
    }

    private File clusterDir(final int node) {
        return root.resolve("node" + node).resolve("cluster").toFile();
    }

    private int[] allNodes() {
        return new int[] {0, 1, 2};
    }

    private void start(final int node) throws IOException {
        final File java = new File(new File(System.getProperty("java.home"), "bin"), "java");
        final List<String> cmd = new ArrayList<>(Arrays.asList(java.getAbsolutePath(),
                "-Xmx768m", "-XX:ActiveProcessorCount=2",
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens", "java.base/java.nio=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "-Daeron.dir=" + new File(shmRoot, "aeron").getAbsolutePath(),
                "-cp", System.getProperty("java.class.path"),
                MeJournalDrillNodeMain.class.getName(), holdFile(node).getAbsolutePath()));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        final Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("SETTLEMENT_JOURNAL_") || k.startsWith("SNAPSHOT_")
                || k.startsWith("TRANSPORT_") || k.startsWith("CLUSTER_") || k.startsWith("MATCH_")
                || k.equals("BASE_DIR") || k.equals("METRICS_PORT") || k.equals("AERON_DIR"));
        env.put("CLUSTER_NODE", Integer.toString(node));
        env.remove("ME_AUTH_CREDENTIAL");
        env.put("ME_AUTH_KEYS", com.match.infrastructure.persistence.TestAuth.KEYS); // RC2 S6: every node authenticates
        env.put("CLUSTER_ADDRESSES", "localhost,localhost,localhost");
        env.put("CLUSTER_PORT_BASE", Integer.toString(portBase));
        env.put("CLUSTER_PORT_STRIDE", Integer.toString(STRIDE));
        env.put("BASE_DIR", root.resolve("node" + node).toString());
        env.put("METRICS_PORT", Integer.toString(metricsPort(node)));
        env.put("SETTLEMENT_JOURNAL_ENABLED", "true");
        env.put("SETTLEMENT_JOURNAL_DIR", root.resolve("journal" + node).toString());
        // The contract's MATCH_ENGINE_FROM_CONFIG=1: the parser accepts only the literal true/false.
        env.put("MATCH_ENGINE_FROM_CONFIG", "true");
        env.put("TRANSPORT_IDLE_MODE", "backoff");
        env.put("TRANSPORT_DRIVER_THREADING", "shared");
        env.put("TRANSPORT_LOG_TERM_LENGTH", "16m");
        env.put("TRANSPORT_SNAPSHOT_TERM_LENGTH", "8m");
        env.put("TRANSPORT_JOURNAL_TERM_LENGTH", "16m");
        env.put("SNAPSHOT_LOG_BYTES", "0");
        env.put("SNAPSHOT_INTERVAL_MINUTES", "0");
        final File out = root.resolve("node" + node + "-run" + (starts[node]++) + ".out").toFile();
        pb.redirectErrorStream(true).redirectOutput(out); // files, never pipes
        nodes[node] = pb.start();
    }

    /**
     * Restart on the same dirs once the killed process's archive and cluster mark files are no
     * longer "active": Aeron refuses a mark file whose activity timestamp is younger than its
     * 10 s liveness timeout, exactly as it would refuse an operator restarting too fast. The
     * client session is kept alive meanwhile.
     */
    private void restartAfterMarkFilesExpire(final int node) throws IOException {
        final long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        while (System.currentTimeMillis() < until) {
            client.sendKeepAlive();
            client.pollEgress();
            sleep(250);
        }
        start(node);
    }

    private int kill(final int node) throws InterruptedException {
        final Process p = nodes[node];
        p.destroyForcibly();
        assertTrue("victim did not die", p.waitFor(30, TimeUnit.SECONDS));
        nodes[node] = null;
        return p.exitValue();
    }

    private long metric(final int node, final String name) {
        try {
            final HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + metricsPort(node) + "/metrics"))
                    .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
            for (final String line : r.body().split("\n")) {
                if (line.startsWith(name + " ")) {
                    return (long) Double.parseDouble(line.substring(name.length() + 1).trim());
                }
            }
            return Long.MIN_VALUE;
        } catch (final IOException e) {
            return Long.MIN_VALUE;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitLeader() {
        final long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (int i = 0; i < NODES; i++) {
                if (metric(i, "match_cluster_role") == 2) {
                    return;
                }
            }
            sleep(200);
        }
        fail("no leader within " + DRAIN_TIMEOUT_MS + " ms");
    }

    private int leader() {
        for (int i = 0; i < NODES; i++) {
            if (nodes[i] != null && metric(i, "match_cluster_role") == 2) {
                return i;
            }
        }
        throw new AssertionError("no leader");
    }

    private int follower() {
        final int leader = leader();
        return leader == NODES - 1 ? 0 : leader + 1;
    }

    /**
     * "Drained" (contract 11 step 3): every member's journal max seq is equal and equals its
     * {@code match_engine_next_journal_seq - 1}, polled while the load is paused.
     */
    private long awaitDrained(final int... members) {
        final long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            client.sendKeepAlive();
            client.pollEgress();
            final long[] next = new long[NODES];
            final long[] max = new long[NODES];
            boolean ok = true;
            for (final int m : members) {
                if (nodes[m] == null || !nodes[m].isAlive()) {
                    throw new AssertionError("member " + m + " is not running (see node" + m + "-run*.out)");
                }
                next[m] = metric(m, "match_engine_next_journal_seq");
                try {
                    max[m] = MeJournalArchiveReader.maxSeq(reader.read(port(m, InfrastructureConstantsAccess.JOURNAL_OFFSET)));
                } catch (final RuntimeException e) {
                    max[m] = -1;
                }
                ok &= next[m] >= 2 && max[m] == next[m] - 1 && max[m] == max[members[0]];
            }
            last = "next=" + Arrays.toString(next) + " max=" + Arrays.toString(max);
            if (ok) {
                return max[members[0]];
            }
            sleep(250);
        }
        throw new AssertionError("not drained within " + DRAIN_TIMEOUT_MS + " ms: " + last);
    }

    private Map<Integer, List<Recording>> readAll() {
        final Map<Integer, List<Recording>> out = new HashMap<>();
        for (int i = 0; i < NODES; i++) {
            out.put(i, reader.read(port(i, InfrastructureConstantsAccess.JOURNAL_OFFSET)));
        }
        return out;
    }

    /** Distinct log positions of valid snapshot entries in the member's recording log. */
    private TreeSet<Long> snapshotPositions(final int node) {
        final TreeSet<Long> out = new TreeSet<>();
        try (RecordingLog recordingLog = new RecordingLog(clusterDir(node), false)) {
            for (final RecordingLog.Entry e : recordingLog.entries()) {
                if (e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && e.isValid) {
                    out.add(e.logPosition);
                }
            }
        }
        return out;
    }

    /** ClusterTool snapshot on the leader's cluster dir; P = the victim's service + CM snapshot entry. */
    private long snapshotAndAwaitVictimEntry(final int victim) {
        final int leader = leader();
        final ByteArrayOutputStream text = new ByteArrayOutputStream();
        final boolean toggled = ClusterTool.snapshot(clusterDir(leader), new PrintStream(text));
        assertTrue("ClusterTool snapshot on leader " + leader + ": " + text, toggled);
        final long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try (RecordingLog recordingLog = new RecordingLog(clusterDir(victim), false)) {
                boolean service = false;
                boolean consensus = false;
                long position = -1;
                for (final RecordingLog.Entry e : recordingLog.entries()) {
                    if (e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && e.isValid) {
                        position = e.logPosition;
                        service |= e.serviceId == 0;
                        consensus |= e.serviceId == -1;
                    }
                }
                if (service && consensus) {
                    return position;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50)); // the kill follows within one poll
        }
        throw new AssertionError("victim " + victim + " recorded no snapshot entry");
    }

    private void note(final String line) {
        log.append("[S3-DRILL] ").append(line).append('\n');
        System.out.println("[S3-DRILL] " + line);
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- the client load

    private io.aeron.cluster.client.AeronCluster connectAs(final com.openexchange.cluster.auth.ClusterSessionClass c,
                                                         final String ingress) {
        return io.aeron.cluster.client.AeronCluster.connect(new io.aeron.cluster.client.AeronCluster.Context()
                .credentialsSupplier(com.match.infrastructure.persistence.TestAuth.credentials(c))
                .aeronDirectoryName(clientDriver.aeronDirectoryName())
                .egressListener((sessionId, ts, buffer, offset, length, header) -> { })
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .ingressChannel("aeron:udp")
                .ingressEndpoints(ingress)
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10)));
    }

    private void send(final byte[] frame) {
        final UnsafeBuffer buffer = new UnsafeBuffer(frame);
        final long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
        while (client.offer(buffer, 0, frame.length) < 0) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("ingress offer back-pressured for " + DRAIN_TIMEOUT_MS + " ms");
            }
            client.pollEgress();
            LockSupport.parkNanos(TimeUnit.MICROSECONDS.toNanos(100));
        }
        client.pollEgress();
    }

    /** Send {@code n} v12 template 9 commands at about 500/s, then return (the load is paused). */
    private void sendCommands(final int n) {
        long lastKeepAlive = System.nanoTime();
        for (int i = 0; i < n; i++) {
            send(load.next());
            LockSupport.parkNanos(COMMAND_PACING_NS);
            if (System.nanoTime() - lastKeepAlive > TimeUnit.SECONDS.toNanos(1)) {
                client.sendKeepAlive();
                lastKeepAlive = System.nanoTime();
            }
        }
    }

    private static byte[] engineConfigFresh() {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        final EngineConfigEncoder encoder = new EngineConfigEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .configVersion(1)
                .impl(EngineImpl.ARRAY)
                .bookCapacity(65_536)
                .maxMatchesPerOrder(100)
                .maxOrdersPerLevel(0)
                .behaviorVersion(2)
                .commandLedgerCapacity(50_000)
                .genesisId(G)
                .maxWireVersion(12);
        encoder.marketsCount(1).next()
                .marketId(1)
                .symbol("BTC-USD")
                .minPrice(FixedPoint.fromDouble(50_000.0))
                .maxPrice(FixedPoint.fromDouble(150_000.0))
                .tickSize(FixedPoint.fromDouble(1.0));
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    /**
     * The drill load: units of five commands (a resting maker bid, a resting ask and a bid that
     * crosses it fully, a replace of the maker, a cancel of the replacement), every tenth command an
     * equal retry of the previous one and every fiftieth a conflicting copy (same id, other price).
     * Order ids are tracked from the FRESH engine's generator (1, +1 per create or accepted replace).
     */
    private static final class Load {
        private long sent;
        private long idLow;
        private long oms;
        private long nextOrderId = 1;
        private long unit;
        private int step;
        private long makerOrderId;
        private long makerUser;
        private long makerOms;
        private long askPrice;
        private byte[] last;
        private long lastIdLow;
        private int lastKind;
        private long lastUser;
        private long lastOms;
        private long lastOld;
        private int lastSide;
        private double lastPrice;

        byte[] next() {
            final long n = sent++;
            if (last != null && n % 50 == 49 && lastKind == 0) {
                // CONFLICT: the previous create's id with another price (never recorded, journaled).
                return frame(lastIdLow, lastKind, lastUser, lastOms, lastOld, lastSide, lastPrice + 1, 1);
            }
            if (last != null && n % 10 == 9) {
                return last; // equal retry: suppressed under behaviour 2
            }
            final byte[] f;
            switch (step) {
                case 0 -> {
                    makerUser = 1_000 + unit % 50;
                    makerOms = ++oms;
                    makerOrderId = nextOrderId++;
                    f = remember(++idLow, 0, makerUser, makerOms, 0, 0, 50_000 + unit % 500);
                }
                case 1 -> {
                    askPrice = 70_000 + unit % 1_000;
                    nextOrderId++;
                    f = remember(++idLow, 0, 2_000 + unit % 50, ++oms, 0, 1, askPrice);
                }
                case 2 -> {
                    nextOrderId++;
                    f = remember(++idLow, 0, 3_000 + unit % 50, ++oms, 0, 0, askPrice);
                }
                case 3 -> {
                    final long old = makerOrderId;
                    makerOrderId = nextOrderId++;
                    f = remember(++idLow, 2, makerUser, makerOms, old, 0, 51_000 + unit % 500);
                }
                default -> f = remember(++idLow, 1, makerUser, makerOms, makerOrderId, 0, 0);
            }
            if (++step == 5) {
                step = 0;
                unit++;
            }
            return f;
        }

        private byte[] remember(final long id, final int kind, final long user, final long omsOrderId,
                                final long old, final int side, final double price) {
            lastIdLow = id;
            lastKind = kind;
            lastUser = user;
            lastOms = omsOrderId;
            lastOld = old;
            lastSide = side;
            lastPrice = price;
            last = frame(id, kind, user, omsOrderId, old, side, price, kind == 1 ? 0 : 1);
            return last;
        }

        private static byte[] frame(final long idLow, final int kind, final long userId, final long omsOrderId,
                                    final long oldOrderId, final int side, final double price, final double qty) {
            final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
            new DurableOrderCommandEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                    .commandIdHigh(17).commandIdLow(idLow).userId(userId).omsOrderId(omsOrderId)
                    .oldOrderId(oldOrderId).price(FixedPoint.fromDouble(price)).quantity(FixedPoint.fromDouble(qty))
                    .budget(0).marketId(1)
                    .commandKind((short) kind).orderType((short) 0).orderSide((short) side)
                    .timeInForce((short) 0).expectedOldRemaining(0);
            return Arrays.copyOf(buffer.byteArray(),
                    MessageHeaderEncoder.ENCODED_LENGTH + DurableOrderCommandEncoder.BLOCK_LENGTH);
        }
    }

    /** The journal archive control port offset, as the runtime derives it. */
    private static final class InfrastructureConstantsAccess {
        static final int JOURNAL_OFFSET = com.match.infrastructure.InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET;
    }
}
