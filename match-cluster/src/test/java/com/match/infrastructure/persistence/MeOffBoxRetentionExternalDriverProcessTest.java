// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.EngineDriverLifecycleProcessTest.Node;
import com.openexchange.cluster.ClusterPorts;
import com.openexchange.cluster.OffBoxWatermark;
import com.openexchange.cluster.OffBoxWatermarkTool;
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
 * The off-box watermark through the production entry point ({@code com.match.Main} in a child JVM) on an EXTERNAL
 * media driver, {@code RETENTION_OFFBOX} from the environment: the mode, the file's state and the commit position
 * (read from the external driver's counters) as the member's own {@code /metrics} reports them, and the boot refusal
 * of {@code off} with a file present.
 *
 * <p>This row does not purge. The member's archive segments are 64 MiB at this entry point, so a purge needs more
 * log than a test writes; the purge itself, with the same pruner code, is {@code MeOffBoxRetentionClusterTest}
 * (embedded drivers, small segments).</p>
 */
public class MeOffBoxRetentionExternalDriverProcessTest {

    private static final long GENESIS = 0x5EED_0000_0007L;

    private final EngineDriverLifecycleProcessTest rig = new EngineDriverLifecycleProcessTest();

    @After
    public void cleanUp() throws Exception {
        rig.cleanUp();
    }

    private static Map<String, String> env(final String offBoxMode) {
        final Map<String, String> env = new HashMap<>();
        if (offBoxMode != null) {
            env.put("RETENTION_OFFBOX", offBoxMode);
        }
        // the child takes the test key table and builds its engines from a replicated config, as the harness does
        env.put("JAVA_TOOL_OPTIONS", "-D" + com.match.infrastructure.auth.MatchAuth.POLICY.keysProperty() + "="
                + TestAuth.KEYS + " -Dmatch.engine.from.config=true");
        return env;
    }

    private static String metric(final Node node, final String name) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + node.probePort()
                + "/metrics").toURL().openConnection();
        c.setConnectTimeout(1_000);
        c.setReadTimeout(2_000);
        final String body = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (final String line : body.split("\n")) {
            if (line.startsWith(name + " ")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    private static void awaitMetric(final Node node, final String name, final String value) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        String last = null;
        while (System.nanoTime() < deadline) {
            assertTrue("the engine is running: " + node.log(), node.engine().isAlive());
            last = metric(node, name);
            if (value.equals(last)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError(name + " never became " + value + " (last " + last + "): " + node.log());
    }

    private static File clusterDir(final Node node) throws IOException {
        try (Stream<Path> walk = Files.walk(node.base().resolve("state"))) {
            return walk.filter(p -> p.getFileName().toString().equals("recording.log")).findFirst()
                    .orElseThrow(() -> new AssertionError("no recording.log under " + node.base())).getParent()
                    .toFile();
        }
    }

    private static void set(final File clusterDir, final long position, final String... more) {
        final String[] base = {"set", "--cluster-dir", clusterDir.getAbsolutePath(), "--engine", "match",
            "--cluster-id", "0", "--genesis-id", Long.toString(GENESIS), "--log-position-bytes",
            Long.toString(position), "--source", "test-archiver"};
        final String[] args = new String[base.length + more.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(more, 0, args, base.length, more.length);
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(err.toString(), 0, OffBoxWatermarkTool.run(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err), System::currentTimeMillis));
    }

    @Test(timeout = 300_000)
    public void theMemberReportsItsWatermarkOnAnExternalDriverAndOffWithAFileRefusesTheStart() throws Exception {
        final Node node = rig.startNodeOnDriver(env("required"));
        // the rig starts the member with TRANSPORT_DRIVER_MODE=external on a driver process of its own
        assertTrue("the driver process is running", node.driver().isAlive());
        final File clusterDir = clusterDir(node);

        // no file: ABSENT, and the member says it holds
        awaitMetric(node, "match_retention_offbox_mode", "2");
        awaitMetric(node, "match_retention_offbox_state", "1");
        assertEquals("-1", metric(node, "match_retention_offbox_position_bytes"));

        // a file, and no recorded genesis id yet (no engine config: behaviour 1): UNBOUND
        set(clusterDir, 32);
        awaitMetric(node, "match_retention_offbox_state", "3");

        // the replicated config at behaviour 2 records the genesis id: the same file is VALID now
        final int ingressPort = ClusterConfig.calculatePort(0, node.probePort() - ClusterPorts.NODE_ENDPOINT_OFFSET,
                ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        try (AeronCluster admin = AeronCluster.connect(new AeronCluster.Context()
                .credentialsSupplier(TestAuth.credentials(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN))
                .egressListener((sessionId, ts, buffer, offset, length, h) -> { })
                .errorHandler(t -> System.out.println("[offbox external] test-JVM cluster client: " + t))
                .egressChannel("aeron:udp?endpoint=localhost:0")
                .ingressChannel("aeron:udp")
                .aeronDirectoryName(node.aeronDir().toString())
                .ingressEndpoints("0=localhost:" + ingressPort)
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10)))) {
            assertTrue("config accepted", MeClusterHarness.offer(admin,
                    BehaviourTwoLog.engineConfig(1, 2, GENESIS, 12, 1_000), 10_000) > 0);
            awaitMetric(node, "match_retention_offbox_state", "0");
        }
        assertEquals("32", metric(node, "match_retention_offbox_position_bytes"));
        assertEquals("-1", metric(node, "match_retention_offbox_released_bytes"));

        // a position above this member's commit position, read from the external driver's counters: AHEAD
        set(clusterDir, 1L << 40);
        awaitMetric(node, "match_retention_offbox_state", "5");
        assertEquals("-1", metric(node, "match_retention_offbox_position_bytes"));
        set(clusterDir, 32, "--allow-lower");
        awaitMetric(node, "match_retention_offbox_state", "0");
        final String log = node.log();
        assertTrue(log, log.contains(" -> UNBOUND (mode=required state=UNBOUND floor=0"));
        assertTrue(log, log.contains("[RETENTION] off-box: state UNBOUND -> VALID"));
        assertTrue(log, log.contains("[RETENTION] off-box: state VALID -> AHEAD"));

        // the same member started again with the mode left at its default (off) and the file still there: refused
        node.engine().destroyForcibly();
        assertTrue(node.engine().waitFor(20, TimeUnit.SECONDS));
        final Path secondLog = node.base().resolve("engine-off.log");
        final long t0 = System.nanoTime();
        final Process second = rig.engine(node.base(), node.aeronDir(), node.probePort(), secondLog, env(null));
        final EngineDriverLifecycleProcessTest.Exit exit =
                EngineDriverLifecycleProcessTest.awaitExit(second, 60_000, t0);
        final String out = Files.readString(secondLog);
        assertEquals("off with a watermark file present refuses the start: " + out, 1, exit.code());
        assertTrue(out, out.contains("RETENTION_OFFBOX=off but " + OffBoxWatermark.file(clusterDir) + " exists: set"
                + " RETENTION_OFFBOX=required, or remove the file with OffBoxWatermarkTool clear"));
        System.out.println("GREEN off-box on an external driver (match): ABSENT, UNBOUND, VALID, AHEAD reported;"
                + " off with a file refused in " + exit.ms() + " ms");
    }
}
