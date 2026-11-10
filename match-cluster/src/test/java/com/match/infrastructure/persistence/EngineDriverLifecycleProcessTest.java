// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The production entry point on an EXTERNAL media driver, both in child JVMs,
 * so the exit status asserted is the one a process manager sees. The core runs the same test on the same kit code
 * ({@code com.openexchange.cluster.EngineLifecycle}).
 *
 * <p>RED evidence: {@code -Dengine.driver.classpath=<pre-fix match-cluster.jar>} runs the child on that jar instead of this
 * build (the pre-fix engine exits 0 on driver death, hangs on the absent driver and on SIGTERM).
 */
public class EngineDriverLifecycleProcessTest {

    private static final String ENGINE_MAIN = "com.match.Main";
    private static final String PROBE_PORT_ENV = "METRICS_PORT";

    private final List<Process> processes = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();

    @After
    public void cleanUp() throws Exception {
        for (final Process p : processes) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        for (final Path d : dirs) {
            deleteTree(d);
        }
    }

    @Test
    public void mediaDriverDeathAfterStartExitsOneNeverZero() throws Exception {
        assertDriverDeathExitsOne(startNodeOnDriver(false), "driver kill -9");
    }

    /**
     * Journal-on case (ME exit 139 in 3/3 before the fix): with the settlement journal on, the node runs a second, SHARED-mode archive
     * recording the journal publication, and the journal writer's archive client had Aeron's default error handler.
     * On driver death that client called {@code Runtime.exit(-1)} (a signal stop, so the FATAL was never recorded),
     * and the journal archive's own client closed and unmapped the CnC before its conductor decremented the
     * recording-session counter in it: SIGSEGV in {@code UnsafeApi.getLong} on "archive-conductor".
     */
    @Test
    public void mediaDriverDeathWithTheSettlementJournalExitsOneWithoutACrash() throws Exception {
        final Node node = startNodeOnDriver(true);
        awaitJournalRecording(node, 30_000); // the journal archive has an active recording (INFO is not logged)
        assertDriverDeathExitsOne(node, "driver kill -9, journal on");
    }

    private static void assertDriverDeathExitsOne(final Node node, final String label) throws Exception {
        node.driver.destroyForcibly(); // kill -9 of the media driver
        final long t0 = System.nanoTime();
        final Exit exit = awaitExit(node.engine, 45_000, t0);
        System.out.println(label + ": exit=" + exit.code + " after " + exit.ms + " ms");
        final String out = node.log();
        assertEquals("driver death must exit 1 (took " + exit.ms + " ms): " + out, 1, exit.code);
        assertTrue("within the 10 s client driver timeout plus the halt: " + exit.ms, exit.ms < 18_000);
        assertTrue("the lifecycle halted on the driver loss: " + out,
                out.contains("media driver lost in EXTERNAL driver mode")
                        && out.contains("halting now, no component close"));
        assertFalse("no JVM crash: " + out, out.contains("A fatal error has been detected by the Java Runtime"));
        assertFalse("no Aeron default error handler exit: " + out, out.contains("exiting client..."));
        try (Stream<Path> files = Files.list(node.base)) {
            assertEquals("no hs_err file", List.of(), files.filter(f -> f.getFileName().toString().startsWith("hs_err"))
                    .toList());
        }
    }

    @Test
    public void absentDriverAtStartExitsOneWithinTheDriverWait() throws Exception {
        final Path base = dir("match-driver-nodriver-");
        final Path aeronDir = Files.createDirectories(base.resolve("aeron"));
        final Path log = base.resolve("engine.log");
        final long t0 = System.nanoTime();
        final Process engine = engine(base, aeronDir, freePort(), log,
                Map.of("TRANSPORT_EXTERNAL_DRIVER_TIMEOUT_MS", "2000"));
        final Exit exit = awaitExit(engine, 20_000, t0);
        System.out.println("no driver, wait 2000 ms: exit=" + exit.code + " after " + exit.ms + " ms from spawn");
        final String out = Files.readString(log);
        assertEquals("absent driver must exit 1 (took " + exit.ms + " ms): " + out, 1, exit.code);
        assertTrue("within the 2000 ms wait plus the JVM start: " + exit.ms, exit.ms < 12_000);
        assertTrue(out, out.contains("No external media driver found"));
    }

    @Test
    public void sigtermShutsDownCleanlyWithinTheShutdownTimeout() throws Exception {
        final Node node = startNodeOnDriver(false);
        final long t0 = System.nanoTime();
        node.engine.destroy(); // SIGTERM
        final Exit exit = awaitExit(node.engine, 20_000, t0);
        System.out.println("SIGTERM: exit=" + exit.code + " after " + exit.ms + " ms");
        final String out = node.log();
        assertTrue("SIGTERM must exit 0 or 143, got " + exit.code + " after " + exit.ms + " ms: " + out,
                exit.code == 0 || exit.code == 143);
        assertTrue("within ENGINE_SHUTDOWN_TIMEOUT_MS (default 4000) plus slack: " + exit.ms, exit.ms < 6_000);
        assertTrue("the close finished, not the bound: " + out, out.contains("stopped cleanly on JVM shutdown"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private record Exit(int code, long ms) { }

    private record Node(Process driver, Process engine, Path logFile, Path base) {
        String log() throws IOException {
            return Files.readString(logFile);
        }
    }

    /** The writer's recorded publication makes the journal archive open a segment file (*.rec). */
    private static void awaitJournalRecording(final Node node, final long timeoutMs) throws Exception {
        final Path archive = node.base.resolve("journal").resolve("node0").resolve("archive");
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (Files.isDirectory(archive)) {
                try (Stream<Path> files = Files.list(archive)) {
                    if (files.anyMatch(f -> f.getFileName().toString().endsWith(".rec"))) {
                        return;
                    }
                }
            }
            assertTrue("no journal recording in " + archive + " within " + timeoutMs + " ms: " + node.log(),
                    System.nanoTime() < deadline && node.engine.isAlive());
            Thread.sleep(100);
        }
    }

    private Node startNodeOnDriver(final boolean settlementJournal) throws Exception {
        final Path base = dir("match-driver-");
        final Map<String, String> extraEnv = settlementJournal
                ? Map.of("SETTLEMENT_JOURNAL_ENABLED", "true",
                        "SETTLEMENT_JOURNAL_DIR", base.resolve("journal").toString(),
                        "SETTLEMENT_JOURNAL_RING_BYTES", String.valueOf(1 << 20))
                : Map.of();
        final Path aeronDir = shmDir("match-driver-aeron-");
        final Process driver = driver(aeronDir, base.resolve("driver.log"));
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(aeronDir.resolve("cnc.dat"))) {
            assertTrue("driver did not start: " + Files.readString(base.resolve("driver.log")),
                    System.nanoTime() < deadline && driver.isAlive());
            Thread.sleep(50);
        }
        final int probePort = freePort();
        final Path log = base.resolve("engine.log");
        final Process engine = engine(base, aeronDir, probePort, log, extraEnv);
        awaitReady(engine, probePort, log, 90_000);
        return new Node(driver, engine, log, base);
    }

    private static String childClasspath() {
        final String override = System.getProperty("engine.driver.classpath");
        return override == null || override.isEmpty() ? System.getProperty("java.class.path") : override;
    }

    private static List<String> java() {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "-Xmx512m", "-Daeron.threading.mode=SHARED", "-Daeron.archive.threading.mode=SHARED",
                "-Daeron.archive.segment.file.length=1048576"));
    }

    private Process driver(final Path aeronDir, final Path log) throws IOException {
        final List<String> cmd = java();
        cmd.addAll(List.of("-Daeron.dir=" + aeronDir, "-Daeron.dir.delete.on.start=true",
                "-Daeron.dir.delete.on.shutdown=true", "-Daeron.term.buffer.sparse.file=true",
                "-cp", System.getProperty("java.class.path"), "io.aeron.driver.MediaDriver"));
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(p);
        return p;
    }

    private Process engine(final Path base, final Path aeronDir, final int probePort, final Path log,
                           final Map<String, String> extraEnv) throws IOException {
        final List<String> cmd = java();
        cmd.addAll(List.of("-XX:ErrorFile=" + base.resolve("hs_err_pid%p.log"), "-cp", childClasspath(), ENGINE_MAIN));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        final Map<String, String> env = pb.environment();
        env.put("BASE_DIR", base.resolve("state").toString());
        env.put("AERON_DIR", aeronDir.toString());
        env.put("TRANSPORT_DRIVER_MODE", "external");
        env.put("TRANSPORT_IDLE_MODE", "backoff");
        env.put("TRANSPORT_LOG_TERM_LENGTH", "4m");
        env.put("TRANSPORT_SNAPSHOT_TERM_LENGTH", "4m");
        env.put("TRANSPORT_JOURNAL_TERM_LENGTH", "4m");
        env.put("CLUSTER_NODE", "0");
        env.put("CLUSTER_ADDRESSES", "localhost");
        env.put("CLUSTER_PORT_BASE", String.valueOf(freePort()));
        env.put(PROBE_PORT_ENV, String.valueOf(probePort));
        env.putAll(extraEnv);
        final Process p = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(p);
        return p;
    }

    private static void awaitReady(final Process engine, final int port, final Path log, final long timeoutMs)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            assertTrue("engine exited before /ready: " + Files.readString(log), engine.isAlive());
            try {
                final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/ready")
                        .toURL().openConnection();
                c.setConnectTimeout(500);
                c.setReadTimeout(1_000);
                if (c.getResponseCode() == 200) {
                    return;
                }
            } catch (final IOException notYet) {
                // probe not up yet
            }
            Thread.sleep(200);
        }
        throw new AssertionError("no /ready 200 within " + timeoutMs + " ms: " + Files.readString(log));
    }

    private static Exit awaitExit(final Process p, final long boundMs, final long t0) throws Exception {
        final boolean exited = p.waitFor(boundMs, TimeUnit.MILLISECONDS);
        final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        if (!exited) {
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("still running " + ms + " ms after the fault (bound " + boundMs + " ms)");
        }
        return new Exit(p.exitValue(), ms);
    }

    private Path dir(final String prefix) throws IOException {
        final Path d = Files.createTempDirectory(prefix);
        dirs.add(d);
        return d;
    }

    private Path shmDir(final String prefix) throws IOException {
        final Path shm = Path.of("/dev/shm");
        final Path d = Files.isDirectory(shm) && Files.isWritable(shm)
                ? Files.createTempDirectory(shm, prefix) : Files.createTempDirectory(prefix);
        dirs.add(d);
        return d;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
