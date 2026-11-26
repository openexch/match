// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test rig (S4): one production member process ({@code com.match.Main} through {@link CompatLevelMain}, so it runs a
 * chosen member version) in EMBEDDED or EXTERNAL driver mode; in external mode it gets its own media driver process.
 * It can join a {@link MeClusterHarness} as one of its three members (same ports, stride, keys and timing), or run
 * alone. The exit status asserted is the one a process manager sees.
 */
final class MemberProcess implements AutoCloseable {

    enum Driver { EMBEDDED, EXTERNAL }

    record Exit(int code, long ms) { }

    final Process engine;
    final Process driver;
    final Path log;
    final Path work;
    private final Path aeronBase;

    private MemberProcess(final Process engine, final Process driver, final Path log, final Path work,
                          final Path aeronBase) {
        this.engine = engine;
        this.driver = driver;
        this.log = log;
        this.work = work;
        this.aeronBase = aeronBase;
    }

    String log() throws IOException {
        return Files.exists(log) ? Files.readString(log) : "";
    }

    /** The environment of harness member {@code memberId} (its state directory, ports, keys, timing, cadence). */
    static Map<String, String> joiningEnv(final MeClusterHarness h, final int memberId) {
        final Map<String, String> env = new HashMap<>();
        env.put("BASE_DIR", h.members[memberId].baseDir().getAbsolutePath());
        env.put("CLUSTER_NODE", Integer.toString(memberId));
        env.put("CLUSTER_ADDRESSES", "localhost,localhost,localhost");
        env.put("CLUSTER_PORT_BASE", Integer.toString(h.portBase));
        env.put("CLUSTER_PORT_STRIDE", Integer.toString(com.openexchange.cluster.ClusterPorts.SHARED_HOST_STRIDE));
        env.put("ME_AUTH_KEYS", TestAuth.KEYS);
        env.put("TRANSPORT_LOG_TERM_LENGTH", h.logTermLength);
        env.put("SNAPSHOT_LOG_BYTES", "0");
        env.put("SNAPSHOT_INTERVAL_MINUTES", "0");
        env.put("SNAPSHOT_PRUNE_ENABLED", "false");
        return env;
    }

    /** A standalone single-member cluster on {@code portBase} under {@code work}. */
    static Map<String, String> standaloneEnv(final Path work, final int portBase) {
        final Map<String, String> env = new HashMap<>();
        env.put("BASE_DIR", work.resolve("state").toString());
        env.put("CLUSTER_NODE", "0");
        env.put("CLUSTER_ADDRESSES", "localhost");
        env.put("CLUSTER_PORT_BASE", Integer.toString(portBase));
        env.put("ME_AUTH_KEYS", TestAuth.KEYS);
        env.put("TRANSPORT_LOG_TERM_LENGTH", "4m");
        return env;
    }

    static MemberProcess launch(final Path work, final Driver mode, final MemberCompat version,
                                final Map<String, String> extraEnv, final List<String> extraJvmArgs)
            throws Exception {
        Files.createDirectories(work);
        final Path aeronBase = shmDir(work);
        Process driver = null;
        final Map<String, String> env = new HashMap<>();
        env.put("TRANSPORT_IDLE_MODE", "backoff");
        env.put("TRANSPORT_SNAPSHOT_TERM_LENGTH", "4m");
        env.put("TRANSPORT_JOURNAL_TERM_LENGTH", "4m");
        if (mode == Driver.EXTERNAL) {
            final Path aeronDir = aeronBase.resolve("driver");
            final List<String> cmd = java();
            cmd.addAll(List.of("-Daeron.dir=" + aeronDir, "-Daeron.dir.delete.on.start=true",
                    "-Daeron.dir.delete.on.shutdown=true", "-Daeron.term.buffer.sparse.file=true",
                    "-Daeron.mtu.length=8192",
                    "-cp", System.getProperty("java.class.path"), "io.aeron.driver.MediaDriver"));
            driver = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(work.resolve("driver.log").toFile()).start();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(aeronDir.resolve("cnc.dat"))) {
                if (System.nanoTime() > deadline || !driver.isAlive()) {
                    driver.destroyForcibly();
                    throw new AssertionError("driver did not start: " + Files.readString(work.resolve("driver.log")));
                }
                Thread.sleep(50);
            }
            env.put("TRANSPORT_DRIVER_MODE", "external");
            env.put("AERON_DIR", aeronDir.toString());
        } else {
            env.put("TRANSPORT_DRIVER_MODE", "embedded");
        }
        env.putAll(extraEnv);
        final List<String> cmd = java();
        // embedded mode derives its driver directory from aeron.dir: keep it under this rig's directory
        cmd.add("-Daeron.dir=" + aeronBase.resolve("embedded"));
        cmd.add("-XX:ErrorFile=" + work.resolve("hs_err_pid%p.log"));
        cmd.addAll(extraJvmArgs);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), CompatLevelMain.class.getName(),
                Integer.toString(version.generation()), Integer.toString(version.level())));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        // a clean environment for the settings this test controls: nothing leaks in from the runner
        for (final String name : new String[] {"MATCH_ENGINE_FROM_CONFIG", "MATCH_ENGINE_IMPL",
                "MATCH_ENGINE_BOOK_CAPACITY", "METRICS_PORT", "AERON_DIR"}) {
            pb.environment().remove(name);
        }
        pb.environment().putAll(env);
        final Path log = work.resolve("engine.log");
        final Process engine = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new MemberProcess(engine, driver, log, work, aeronBase);
    }

    private static Path shmDir(final Path work) throws IOException {
        final Path shm = Path.of("/dev/shm");
        return Files.isDirectory(shm) && Files.isWritable(shm)
                ? Files.createTempDirectory(shm, "s4-member-") : Files.createDirectories(work.resolve("aeron"));
    }

    private static List<String> java() {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "-Xmx512m", "-Daeron.threading.mode=SHARED", "-Daeron.archive.threading.mode=SHARED",
                "-Daeron.archive.segment.file.length=4194304"));
    }

    /** The code of {@link Exit} when the member was still running at the bound (it is then killed). */
    static final int STILL_RUNNING = -1;

    /** Waits for the engine to exit; a member still running after {@code boundMs} is killed and reads {@link #STILL_RUNNING}. */
    Exit awaitExit(final long boundMs) throws Exception {
        final long t0 = System.nanoTime();
        final boolean exited = engine.waitFor(boundMs, TimeUnit.MILLISECONDS);
        final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        if (!exited) {
            engine.destroyForcibly();
            engine.waitFor(10, TimeUnit.SECONDS);
            return new Exit(STILL_RUNNING, ms);
        }
        return new Exit(engine.exitValue(), ms);
    }

    /** {@code /ready} of this member on {@code port}: {status, body}, or null while the probe is not up. */
    static String[] ready(final int port) {
        try {
            final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/ready")
                    .toURL().openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1_000);
            final int status = c.getResponseCode();
            final java.io.InputStream in = status == 200 ? c.getInputStream() : c.getErrorStream();
            final String body = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return new String[] {Integer.toString(status), body};
        } catch (final IOException notYet) {
            return null;
        }
    }

    /** Waits for {@code /ready} 200 on {@code port}; returns its body. */
    String awaitReady(final int port, final long timeoutMs) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (!engine.isAlive()) {
                throw new AssertionError("member exited " + engine.exitValue() + " before /ready: " + log());
            }
            final String[] r = ready(port);
            if (r != null && "200".equals(r[0])) {
                return r[1];
            }
            Thread.sleep(200);
        }
        throw new AssertionError("no /ready 200 within " + timeoutMs + " ms: " + log());
    }

    @Override
    public void close() throws Exception {
        engine.destroyForcibly();
        engine.waitFor(10, TimeUnit.SECONDS);
        if (driver != null) {
            driver.destroyForcibly();
            driver.waitFor(10, TimeUnit.SECONDS);
        }
        org.agrona.IoUtil.delete(aeronBase.toFile(), true);
    }
}
