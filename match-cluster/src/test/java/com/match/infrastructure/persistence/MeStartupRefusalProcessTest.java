// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.cluster.ClusterTool;
import io.aeron.cluster.RecordingLog;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The ME's loud refusals at process level (child JVMs on the production entry point {@code com.match.Main},
 * so the exit status asserted is the one a process manager sees):
 * <ul>
 *   <li>a cluster snapshot image without the durable magic exits 1 and never answers /ready 200;</li>
 *   <li>a lone member of a three-member cluster exits 1 within ENGINE_QUORUM_WAIT_MS plus the fatal
 *       grace, in both driver modes;</li>
 *   <li>regression: a follower restarted into a live cluster with a low ENGINE_QUORUM_WAIT_MS reaches
 *       /ready and is never tripped (its election goes through FOLLOWER_* states).</li>
 * </ul>
 */
public class MeStartupRefusalProcessTest {

    private static final String ENGINE_MAIN = "com.match.Main";
    private static final int STRIDE = 100;
    private static final long FATAL_GRACE_MS = 5_000;

    private final List<Process> processes = new ArrayList<>();
    private final List<Path> dirs = new ArrayList<>();
    private final int portBase = 21_000 + ThreadLocalRandom.current().nextInt(0, 300) * 10;

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

    // ------------------------------------------------------------------------------------------------- magic-less image

    @Test
    public void aMagicLessSnapshotImageExitsOneAndNeverServes() throws Exception {
        final Path root = dir("match-refusal-magic-");
        final Path shm = shmDir("match-refusal-magic-aeron-");
        final String addresses = "localhost";

        // Life 1: a legacy-mode node with no traffic serves, takes one snapshot and stops cleanly.
        final Process first = engine(root, shm, 0, addresses, root.resolve("run1.out"), Map.of());
        awaitReady(first, 0, root.resolve("run1.out"), 90_000);
        final File clusterDir = findDir(root, "cluster-mark.dat");
        final ByteArrayOutputStream text = new ByteArrayOutputStream();
        assertTrue("ClusterTool snapshot: " + text, ClusterTool.snapshot(clusterDir, new PrintStream(text)));
        final long serviceRecording = awaitServiceSnapshot(clusterDir, 30_000);
        first.destroy();
        assertTrue("life 1 stops on SIGTERM", first.waitFor(20, TimeUnit.SECONDS));

        // Stage the magic-less image: the durable magic (if this build wrote one) becomes an order-id generator,
        // the tag's layout. A build whose writer emits a bare image needs no edit.
        final int patched = stripMagic(findDir(root, "archive.catalog"), serviceRecording);
        System.out.println("magic-less image: patched " + patched + " durable magic(s) in service snapshot recording "
                + serviceRecording);
        Thread.sleep(11_000); // Aeron refuses a mark file younger than its 10 s liveness timeout

        // Life 2: the restore must refuse the image, exit 1 and never answer /ready 200.
        final Path log2 = root.resolve("run2.out");
        final long t0 = System.nanoTime();
        final Process second = engine(root, shm, 0, addresses, log2, Map.of());
        final int code = awaitExitNeverReady(second, 0, log2, 90_000);
        final String out = Files.readString(log2);
        System.out.println("magic-less image: exit=" + code + " after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                + " ms");
        assertEquals("a magic-less image exits 1: " + out, 1, code);
        assertTrue(out, out.contains("CRITICAL: snapshot refused: snapshot envelope magic missing (length="));
        assertTrue(out, out.contains("refusing a bare or foreign snapshot image"));
        assertTrue(out, out.contains("this node will not serve an unverified state"));
    }

    // ------------------------------------------------------------------------------------------------- lone member

    @Test
    public void aLoneMemberOfThreeExitsOneWithinTheQuorumWaitEmbeddedDriver() throws Exception {
        assertLoneMemberExits(false);
    }

    @Test
    public void aLoneMemberOfThreeExitsOneWithinTheQuorumWaitExternalDriver() throws Exception {
        assertLoneMemberExits(true);
    }

    private void assertLoneMemberExits(final boolean external) throws Exception {
        final Path root = dir("match-refusal-lone-");
        final Path shm = shmDir("match-refusal-lone-aeron-");
        final Map<String, String> env = new java.util.HashMap<>(Map.of("ENGINE_QUORUM_WAIT_MS", "3000"));
        if (external) {
            final Path driverDir = shmDir("match-refusal-lone-driver-");
            startDriver(driverDir, root.resolve("driver.out"));
            env.put("TRANSPORT_DRIVER_MODE", "external");
            env.put("AERON_DIR", driverDir.toString());
        }
        final Path log = root.resolve("lone.out");
        final long t0 = System.nanoTime();
        final Process lone = engine(root, shm, 0, "localhost,localhost,localhost", log, env);
        final int code = awaitExitNeverReady(lone, 0, log, 3_000 + FATAL_GRACE_MS + 20_000);
        final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        final String out = Files.readString(log);
        System.out.println("lone member (" + (external ? "external" : "embedded") + "): exit=" + code + " after " + ms
                + " ms from spawn");
        assertEquals("a lone member exits 1: " + out, 1, code);
        assertTrue(out, out.contains("FATAL (exit 1): no cluster quorum observed within 3000 ms since the consensus "
                + "module launched"));
        assertTrue(out, out.contains("quorum watchdog armed"));
    }

    // ------------------------------------------------------------------------------------------------- rejoining follower

    @Test
    public void aFollowerRestartedIntoALiveClusterIsNeverTripped() throws Exception {
        final Path root = dir("match-refusal-rejoin-");
        final Path shm = shmDir("match-refusal-rejoin-aeron-");
        final String addresses = "localhost,localhost,localhost";
        final Process[] nodes = new Process[3];
        for (int i = 0; i < 3; i++) {
            nodes[i] = engine(root, shm, i, addresses, root.resolve("node" + i + "-run1.out"), Map.of());
        }
        for (int i = 0; i < 3; i++) {
            awaitReady(nodes[i], i, root.resolve("node" + i + "-run1.out"), 120_000);
        }
        int follower = -1;
        for (int i = 0; i < 3 && follower < 0; i++) {
            if (metric(i, "match_cluster_role") != 2) {
                follower = i;
            }
        }
        assertTrue("a follower exists", follower >= 0);
        nodes[follower].destroy();
        assertTrue(nodes[follower].waitFor(20, TimeUnit.SECONDS));
        Thread.sleep(11_000); // mark-file liveness

        final Path log = root.resolve("node" + follower + "-run2.out");
        final long waitMs = 4_000;
        nodes[follower] = engine(root, shm, follower, addresses, log,
                Map.of("ENGINE_QUORUM_WAIT_MS", Long.toString(waitMs)));
        awaitReady(nodes[follower], follower, log, 90_000);
        Thread.sleep(3 * waitMs + FATAL_GRACE_MS);
        final String out = Files.readString(log);
        assertTrue("the restarted follower is still running: " + out, nodes[follower].isAlive());
        assertFalse(out, out.contains("no cluster quorum observed"));
        assertTrue(out, out.contains("quorum watchdog armed: exit 1 if no leader is known within " + waitMs + " ms"));
    }

    // ------------------------------------------------------------------------------------------------- long onStart

    /**
     * The consensus module sits in INIT while the service container's onStart runs (the snapshot load
     * in production). onStart is held for three quorum waits by a JDWP breakpoint: the node must never trip, reach
     * /ready once released, and stay up.
     */
    @Test
    public void aLongServiceOnStartInInitNeverTripsTheWatchdog() throws Exception {
        final Path root = dir("match-refusal-hold-");
        final Path shm = shmDir("match-refusal-hold-aeron-");
        final int jdwp = freePort();
        final long waitMs = 2_000;
        final Path log = root.resolve("run.out");
        final Process p = engine(root, shm, 0, "localhost", log,
                Map.of("ENGINE_QUORUM_WAIT_MS", Long.toString(waitMs)),
                List.of("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + jdwp));
        try (OnStartHold hold = new OnStartHold(jdwp, "com.match.infrastructure.persistence.AppClusteredService",
                3 * waitMs)) {
            awaitReady(p, 0, log, 90_000);
            assertTrue("the breakpoint held onStart", hold.held.get());
        }
        Thread.sleep(waitMs + FATAL_GRACE_MS);
        final String out = Files.readString(log);
        assertTrue("still running after an onStart three waits long: " + out, p.isAlive());
        assertFalse(out, out.contains("no cluster quorum observed"));
        assertTrue(out, out.contains("quorum watchdog armed: exit 1 if no leader is known within " + waitMs + " ms"));
    }

    /**
     * Hold the service container's onStart for {@code holdMs} with a JDWP breakpoint (the
     * child starts with suspend=y), so the consensus module dwells in INIT waiting for the service's ack, exactly as
     * during a long snapshot load. Only the service thread is suspended; the watchdog thread keeps polling. Returns
     * true once the breakpoint was hit, held and released.
     */
    static final class OnStartHold implements AutoCloseable {
        private final com.sun.jdi.VirtualMachine vm;
        final java.util.concurrent.atomic.AtomicBoolean held = new java.util.concurrent.atomic.AtomicBoolean();
        private final Thread events;

        OnStartHold(final int port, final String serviceClass, final long holdMs) throws Exception {
            final com.sun.jdi.connect.AttachingConnector connector = com.sun.jdi.Bootstrap.virtualMachineManager()
                    .attachingConnectors().stream().filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                    .findFirst().orElseThrow();
            final Map<String, com.sun.jdi.connect.Connector.Argument> args = connector.defaultArguments();
            args.get("hostname").setValue("127.0.0.1");
            args.get("port").setValue(Integer.toString(port));
            com.sun.jdi.VirtualMachine attached = null;
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (attached == null) {
                try {
                    attached = connector.attach(args);
                } catch (final java.io.IOException notYet) {
                    if (System.nanoTime() > deadline) {
                        throw notYet;
                    }
                    Thread.sleep(100);
                }
            }
            vm = attached;
            final com.sun.jdi.request.ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(serviceClass);
            prepare.enable();
            events = new Thread(() -> {
                try {
                    while (true) {
                        final com.sun.jdi.event.EventSet set = vm.eventQueue().remove();
                        boolean release = false;
                        for (final com.sun.jdi.event.Event e : set) {
                            if (e instanceof com.sun.jdi.event.ClassPrepareEvent cp) {
                                final com.sun.jdi.Method onStart = cp.referenceType().methodsByName("onStart").get(0);
                                final com.sun.jdi.request.BreakpointRequest bp =
                                        vm.eventRequestManager().createBreakpointRequest(onStart.location());
                                bp.setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_EVENT_THREAD);
                                bp.enable();
                            } else if (e instanceof com.sun.jdi.event.BreakpointEvent) {
                                System.out.println("[STARTUP-HOLD] onStart held for " + holdMs + " ms");
                                Thread.sleep(holdMs);
                                vm.eventRequestManager().deleteAllBreakpoints();
                                held.set(true);
                                release = true;
                            } else if (e instanceof com.sun.jdi.event.VMDisconnectEvent
                                    || e instanceof com.sun.jdi.event.VMDeathEvent) {
                                return;
                            }
                        }
                        set.resume();
                        if (release) {
                            vm.dispose();
                            return;
                        }
                    }
                } catch (final InterruptedException | com.sun.jdi.VMDisconnectedException done) {
                    // the child exited or the hold ended
                }
            }, "onstart-hold");
            events.setDaemon(true);
            events.start();
            vm.resume(); // suspend=y: the child waits for the debugger before main
        }

        @Override
        public void close() {
            events.interrupt();
            try {
                vm.dispose();
            } catch (final RuntimeException ignored) {
                // already disposed or disconnected
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private int metricsPort(final int node) {
        return portBase + 900 + node;
    }

    private Process engine(final Path root, final Path shm, final int node, final String addresses, final Path log,
                           final Map<String, String> extraEnv) throws IOException {
        return engine(root, shm, node, addresses, log, extraEnv, List.of());
    }

    private Process engine(final Path root, final Path shm, final int node, final String addresses, final Path log,
                           final Map<String, String> extraEnv, final List<String> jvmArgs) throws IOException {
        final List<String> cmd = java();
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-Daeron.dir=" + shm.resolve("aeron"), "-cp", System.getProperty("java.class.path"),
                ENGINE_MAIN));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        final Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("SETTLEMENT_JOURNAL_") || k.startsWith("SNAPSHOT_")
                || k.startsWith("TRANSPORT_") || k.startsWith("CLUSTER_") || k.startsWith("MATCH_")
                || k.startsWith("ENGINE_") || k.equals("BASE_DIR") || k.equals("METRICS_PORT")
                || k.equals("AERON_DIR") || k.equals("ME_AUTH_CREDENTIAL"));
        env.put("ME_AUTH_KEYS", TestAuth.KEYS);
        env.put("CLUSTER_NODE", Integer.toString(node));
        env.put("CLUSTER_ADDRESSES", addresses);
        env.put("CLUSTER_PORT_BASE", Integer.toString(portBase));
        env.put("CLUSTER_PORT_STRIDE", Integer.toString(STRIDE));
        env.put("BASE_DIR", root.resolve("node" + node).toString());
        env.put("METRICS_PORT", Integer.toString(metricsPort(node)));
        env.put("TRANSPORT_IDLE_MODE", "backoff");
        env.put("TRANSPORT_DRIVER_THREADING", "shared");
        env.put("TRANSPORT_LOG_TERM_LENGTH", "4m");
        env.put("TRANSPORT_SNAPSHOT_TERM_LENGTH", "4m");
        env.put("TRANSPORT_JOURNAL_TERM_LENGTH", "4m");
        env.put("SNAPSHOT_LOG_BYTES", "0");
        env.put("SNAPSHOT_INTERVAL_MINUTES", "0");
        env.putAll(extraEnv);
        final Process p = pb.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(p);
        return p;
    }

    private void startDriver(final Path aeronDir, final Path log) throws Exception {
        final List<String> cmd = java();
        cmd.addAll(List.of("-Daeron.dir=" + aeronDir, "-Daeron.dir.delete.on.start=true",
                "-Daeron.dir.delete.on.shutdown=true", "-Daeron.term.buffer.sparse.file=true",
                "-cp", System.getProperty("java.class.path"), "io.aeron.driver.MediaDriver"));
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        processes.add(p);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(aeronDir.resolve("cnc.dat"))) {
            assertTrue("driver did not start", System.nanoTime() < deadline && p.isAlive());
            Thread.sleep(50);
        }
    }

    private static List<String> java() {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "-Xmx512m", "-XX:ActiveProcessorCount=2", "-Daeron.threading.mode=SHARED",
                "-Daeron.archive.threading.mode=SHARED", "-Daeron.archive.segment.file.length=1048576"));
    }

    private boolean ready(final int node) {
        try {
            final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + metricsPort(node)
                    + "/ready").toURL().openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1_000);
            return c.getResponseCode() == 200;
        } catch (final IOException notYet) {
            return false;
        }
    }

    private long metric(final int node, final String name) {
        try {
            final HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + metricsPort(node)
                    + "/metrics").toURL().openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(2_000);
            final String body = new String(c.getInputStream().readAllBytes());
            for (final String line : body.split("\n")) {
                if (line.startsWith(name + " ")) {
                    return (long) Double.parseDouble(line.substring(name.length() + 1).trim());
                }
            }
            return Long.MIN_VALUE;
        } catch (final IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private void awaitReady(final Process p, final int node, final Path log, final long timeoutMs) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            assertTrue("node " + node + " exited before /ready: " + Files.readString(log), p.isAlive());
            if (ready(node)) {
                return;
            }
            Thread.sleep(200);
        }
        fail("node " + node + " no /ready 200 within " + timeoutMs + " ms: " + Files.readString(log));
    }

    /** The exit status, failing if the node ever answered /ready 200 or outlived the bound. */
    private int awaitExitNeverReady(final Process p, final int node, final Path log, final long boundMs)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs);
        while (p.isAlive()) {
            if (ready(node)) {
                fail("node " + node + " answered /ready 200: " + Files.readString(log));
            }
            if (System.nanoTime() > deadline) {
                fail("node " + node + " still running after " + boundMs + " ms: " + Files.readString(log));
            }
            Thread.sleep(100);
        }
        return p.exitValue();
    }

    private static long awaitServiceSnapshot(final File clusterDir, final long timeoutMs) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
                long service = -1;
                boolean consensus = false;
                for (final RecordingLog.Entry e : recordingLog.entries()) {
                    if (e.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && e.isValid) {
                        if (e.serviceId == 0) {
                            service = e.recordingId;
                        }
                        consensus |= e.serviceId == -1;
                    }
                }
                if (service >= 0 && consensus) {
                    return service;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no snapshot entry in " + clusterDir);
    }

    /** Overwrite every durable magic in the recording's segment files with a plausible order-id generator. */
    private static int stripMagic(final File archiveDir, final long recordingId) throws IOException {
        final byte[] magic = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                .putLong(SnapshotCodec.DURABLE_MAGIC).array();
        final byte[] tagOrderIdGen = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(9L).array();
        int patched = 0;
        final File[] segments = archiveDir.listFiles((d, n) -> n.startsWith(recordingId + "-") && n.endsWith(".rec"));
        if (segments == null) {
            return 0;
        }
        for (final File segment : segments) {
            final byte[] bytes = Files.readAllBytes(segment.toPath());
            try (RandomAccessFile raf = new RandomAccessFile(segment, "rw")) {
                for (int i = 0; i + 8 <= bytes.length; i++) {
                    boolean match = true;
                    for (int k = 0; k < 8 && match; k++) {
                        match = bytes[i + k] == magic[k];
                    }
                    if (match) {
                        raf.seek(i);
                        raf.write(tagOrderIdGen);
                        patched++;
                    }
                }
            }
        }
        return patched;
    }

    private static File findDir(final Path root, final String marker) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            final Optional<Path> hit = walk.filter(f -> f.getFileName().toString().equals(marker)).findFirst();
            return hit.orElseThrow(() -> new AssertionError("no " + marker + " under " + root)).getParent().toFile();
        }
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
