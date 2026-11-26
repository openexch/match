// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.EngineDriverLifecycleProcessTest.Exit;
import com.match.infrastructure.persistence.EngineDriverLifecycleProcessTest.Node;
import com.openexchange.cluster.ClusterPorts;
import com.openexchange.cluster.journal.JournalReaderTool;
import io.aeron.cluster.client.AeronCluster;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Journal retention through the production entry point on an EXTERNAL media driver, both in child JVMs: the member
 * purges its settlement journal behind a reader file, the driver process is killed (the member exits 1 by the
 * engine's driver-loss rule, with the retention thread and its archive session running), and after the restart the
 * journal archive is consistent and the next passes go on: the recording the dead run left goes whole once the new
 * run has passed it, and the new one is cut. The settings are checked by the real bootstrap: a value that is no
 * mode, and a mode on a member that writes no journal, refuse the start.
 */
public class MeJournalRetentionExternalDriverProcessTest {

    private static final long GENESIS = 0x5EED_0000_0013L;
    private static final int SEG = 64 * 1024;
    private static final String SERIES = "{journal=\"settlement\"}";

    private final EngineDriverLifecycleProcessTest rig = new EngineDriverLifecycleProcessTest();

    @After
    public void cleanUp() throws Exception {
        rig.cleanUp();
    }

    private static Map<String, String> env(final Path journalDir, final String mode) {
        final Map<String, String> env = new HashMap<>();
        if (journalDir != null) {
            env.put("SETTLEMENT_JOURNAL_ENABLED", "true");
            env.put("SETTLEMENT_JOURNAL_DIR", journalDir.toString());
            env.put("SETTLEMENT_JOURNAL_RING_BYTES", String.valueOf(1 << 22));
        }
        env.put("TRANSPORT_JOURNAL_TERM_LENGTH", "64k");
        env.put("TRANSPORT_JOURNAL_SEGMENT_LENGTH", "64k");
        if (mode != null) {
            env.put("JOURNAL_RETENTION_SETTLEMENT", mode);
        }
        env.put("JOURNAL_RETENTION_POLL_MS", "1000");
        env.put("JOURNAL_RETENTION_REQUIRED_READERS_SETTLEMENT", "ct-bridge");
        // the child takes the test key table and builds its engines from a replicated config, as the harness does
        env.put("JAVA_TOOL_OPTIONS", "-D" + com.match.infrastructure.auth.MatchAuth.POLICY.keysProperty() + "="
                + TestAuth.KEYS + " -Dmatch.engine.from.config=true");
        return env;
    }

    private static String metrics(final Node node) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + node.probePort()
                + "/metrics").toURL().openConnection();
        c.setConnectTimeout(1_000);
        c.setReadTimeout(2_000);
        return new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static long metric(final Node node, final String name) throws IOException {
        for (final String line : metrics(node).split("\n")) {
            if (line.startsWith(name + " ")) {
                return Long.parseLong(line.substring(name.length() + 1).trim());
            }
        }
        return Long.MIN_VALUE;
    }

    private interface Check {
        boolean ok() throws IOException;
    }

    private static void await(final Node node, final String what, final Check check) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            assertTrue("the engine is running: " + node.log(), node.engine().isAlive());
            try {
                if (check.ok()) {
                    return;
                }
            } catch (final IOException notYet) {
                // the endpoint is not up yet
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out waiting for " + what + ": " + metrics(node).lines()
                .filter(l -> l.startsWith("match_journal_retention")).toList() + "\n" + node.log());
    }

    private static File clusterDir(final Node node) throws IOException {
        try (Stream<Path> walk = Files.walk(node.base().resolve("state"))) {
            return walk.filter(p -> p.getFileName().toString().equals("recording.log")).findFirst()
                    .orElseThrow(() -> new AssertionError("no recording.log under " + node.base())).getParent()
                    .toFile();
        }
    }

    private static void set(final File clusterDir, final long applied) {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(err.toString(), 0, JournalReaderTool.run(new String[] {"set", "--cluster-dir",
            clusterDir.getAbsolutePath(), "--engine", "match", "--cluster-id", "0", "--genesis-id",
            Long.toString(GENESIS), "--journal", "settlement", "--reader", "ct-bridge", "--applied-journal-seq",
            Long.toString(applied), "--source", "test-pusher"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err), System::currentTimeMillis));
    }

    private static AeronCluster connect(final Node node, final com.openexchange.cluster.auth.ClusterSessionClass c) {
        final int ingressPort = ClusterConfig.calculatePort(0, node.probePort() - ClusterPorts.NODE_ENDPOINT_OFFSET,
                ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        return AeronCluster.connect(new AeronCluster.Context()
                .credentialsSupplier(TestAuth.credentials(c))
                .egressListener((sessionId, ts, buffer, offset, length, h) -> { })
                .errorHandler(t -> System.out.println("[mjr external] test-JVM cluster client: " + t))
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .ingressChannel("aeron:udp")
                .aeronDirectoryName(node.aeronDir().toString())
                .ingressEndpoints("0=localhost:" + ingressPort)
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10)));
    }

    @Test(timeout = 480_000)
    public void theMemberPurgesOnAnExternalDriverExitsOneWhenItDiesAndGoesOnAfterTheRestart() throws Exception {
        final Map<String, String> env = env(rig.newDir("match-mjr-journal-"), "readers");
        final Node node = rig.startNodeOnDriver(env);
        assertTrue("the driver process is running", node.driver().isAlive());
        final String p = "match_journal_retention_";
        // the thread runs; no journal bytes yet
        await(node, "the retention thread", () -> metric(node, p + "agent_state") == 1
                && metric(node, p + "mode" + SERIES) == 2 && metric(node, p + "state" + SERIES) == 2);
        final File clusterDir;
        final int orders = 1_500;
        try (AeronCluster admin = connect(node, com.openexchange.cluster.auth.ClusterSessionClass.ADMIN)) {
            assertTrue("config accepted", MeClusterHarness.offer(admin,
                    BehaviourTwoLog.engineConfig(1, 2, GENESIS, 12, 1_000), 10_000) > 0);
        }
        // the genesis is recorded and the config record journaled: the required reader has no file, so MISSING holds
        await(node, "MISSING before the reader is registered", () -> metric(node, p + "state" + SERIES) == 4
                || metric(node, p + "state" + SERIES) == 9);
        clusterDir = clusterDir(node);
        set(clusterDir, 0);
        try (AeronCluster client = connect(node, com.openexchange.cluster.auth.ClusterSessionClass.FORWARDER)) {
            for (long o = 1; o <= orders; o++) {
                assertTrue("order " + o, MeClusterHarness.offer(client,
                        DurableLedgerV2Test.create(o, 0, 100, 0, 59_000 + (o % 100), 1), 10_000) > 0);
            }
            MeClusterHarness.await(client, () -> false, 200);
        }
        final long head = orders + 1; // the config record is journalSeq 1
        await(node, "the journal tail " + head, () -> metric(node, p + "tail_seq" + SERIES) == head);
        assertEquals("held at the reader's cursor 0", 0, metric(node, p + "purged_bytes_total" + SERIES));
        set(clusterDir, head);
        await(node, "a purge behind the reader", () -> metric(node, p + "purged_bytes_total" + SERIES) > 0
                && metric(node, p + "state" + SERIES) == 0 && metric(node, p + "safe_seq" + SERIES) == head);
        final long purged = metric(node, p + "purged_bytes_total" + SERIES);
        assertEquals("whole segments", 0, purged % SEG);
        assertTrue("at most three segments stay: " + metric(node, p + "retained_bytes" + SERIES),
                metric(node, p + "retained_bytes" + SERIES) <= 3L * SEG);

        // kill -9 of the media driver under the running retention thread: the member exits 1, never 0
        node.driver().destroyForcibly();
        final long t0 = System.nanoTime();
        final Exit exit = EngineDriverLifecycleProcessTest.awaitExit(node.engine(), 45_000, t0);
        final String out = node.log();
        assertEquals("driver death exits 1 (took " + exit.ms() + " ms): " + out, 1, exit.code());
        assertTrue(out, out.contains("media driver lost in EXTERNAL driver mode"));
        assertTrue("no JVM crash: " + out, !out.contains("A fatal error has been detected by the Java Runtime"));
        assertTrue(out, out.contains("[JOURNAL-RETENTION] purged settlement journal recording"));

        // a new driver, the same state: the member journals again from the start of its log into a new recording;
        // the dead run's recording goes whole once the new one has passed it, and the new one is cut
        // (a halted run leaves its archive and cluster mark files active; Aeron refuses a start inside their 10 s
        // liveness with "active mark file detected", as MeJournalHoleAfterSnapshotDrillTest waits out too)
        Thread.sleep(15_000);
        final Node again = rig.restartOnNewDriver(node, env);
        await(again, "one recording again, cut, after the restart", () -> metric(again, p + "state" + SERIES) == 0
                && metric(again, p + "tail_seq" + SERIES) == head && metric(again, p + "recordings" + SERIES) == 1
                && metric(again, p + "purged_bytes_total" + SERIES) > 0
                && metric(again, p + "retained_bytes" + SERIES) <= 3L * SEG
                && metric(again, p + "recordings_anomaly" + SERIES) == 0);
        final String log2 = again.log();
        assertTrue(log2, log2.contains(": stopped, last journalSeq " + head + ", whole recording"));
        System.out.println("GREEN journal retention on an external driver (match): purged " + purged
                + " bytes, exit 1 in " + exit.ms() + " ms on driver death, one recording of at most three segments"
                + " after the restart");
    }

    private void refused(final Map<String, String> env, final String text) throws Exception {
        final Path base = rig.newDir("match-mjr-refuse-");
        final Path aeronDir = Files.createDirectories(base.resolve("aeron"));
        final Path log = base.resolve("engine.log");
        env.put("TRANSPORT_EXTERNAL_DRIVER_TIMEOUT_MS", "2000");
        final long t0 = System.nanoTime();
        final Process engine = rig.engine(base, aeronDir, 20_000 + (int) (System.nanoTime() % 10_000), log, env);
        final Exit exit = EngineDriverLifecycleProcessTest.awaitExit(engine, 30_000, t0);
        final String out = Files.readString(log);
        assertEquals(out, 1, exit.code());
        assertTrue(out, out.contains(text));
        assertTrue("refused before the driver was waited for: " + out,
                !out.contains("No external media driver found"));
    }

    @Test(timeout = 120_000)
    public void aBadRetentionSettingRefusesTheStart() throws Exception {
        final Path journal = rig.newDir("match-mjr-j-");
        refused(env(journal, "purge"), "JOURNAL_RETENTION_SETTLEMENT must be off, report or readers, got 'purge'");
        refused(env(null, "readers"), "JOURNAL_RETENTION_SETTLEMENT=readers on a member that writes no settlement"
                + " journal");
        final Map<String, String> badPoll = env(journal, "report");
        badPoll.put("JOURNAL_RETENTION_POLL_MS", "5");
        refused(badPoll, "JOURNAL_RETENTION_POLL_MS must be 1000..3600000, got '5'");
        final Map<String, String> badSegment = env(journal, "off");
        badSegment.put("TRANSPORT_JOURNAL_SEGMENT_LENGTH", "32k");
        refused(badSegment, "must be a power of two in 64k..1g, got '32k'");
    }
}
