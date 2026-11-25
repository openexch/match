// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.openexchange.cluster.OffBoxWatermark;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.CloseHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Three matching engine members under {@code RETENTION_OFFBOX=required}, embedded drivers, 64 KiB log segments. The
 * engine's own cadence takes the snapshots (64 KiB of log each) while orders flow; the off-box watermark is written
 * on each member by the kit's command run as a process of its own; the log start is read from each member's archive.
 *
 * <p>Member 0 is the appointed leader: a follower never purges past the leader's log start, and the leader is
 * bounded by its own watermark alone. One rule is checked on every poll of every wait: no member's log start is ever
 * above what its own watermark file allows.</p>
 */
public class MeOffBoxRetentionClusterTest {

    private static final int SEGMENT = 64 * 1024;
    private static final long GENESIS = 0x5EED_0000_0007L;
    private static final long WAIT_MS = 120_000;
    private static final String TOOL = "com.openexchange.cluster.OffBoxWatermarkTool";

    @Before
    public void settings() {
        System.setProperty("retention.recheck.max.ms", "1000");
    }

    @After
    public void restore() {
        System.clearProperty("retention.recheck.max.ms");
        System.setProperty("snapshot.log.bytes", "0");
        System.setProperty("snapshot.prune.enabled", "false");
    }

    /** The exit code and output of the watermark command, run as an archiver runs it: a process of its own. */
    private static String tool(final int expectedExit, final String... args) throws Exception {
        final List<String> cmd = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                "-cp", System.getProperty("java.class.path"), TOOL));
        cmd.addAll(List.of(args));
        final Path out = Files.createTempFile("offbox-tool-", ".log");
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.toFile()).start();
            assertTrue("the watermark command finished", p.waitFor(60, TimeUnit.SECONDS));
            final String text = Files.readString(out);
            assertEquals("OffBoxWatermarkTool " + String.join(" ", args) + ": " + text, expectedExit, p.exitValue());
            return text;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private final class Story implements AutoCloseable {
        final MeClusterHarness h;
        final long[] allowed = new long[MeClusterHarness.MEMBERS];
        AeronCluster client;
        long nextOrder = 1;
        boolean checking;
        long lastKeepAliveMs;

        Story(final int portBase, final String name, final OffBoxWatermark.Mode leaderMode) {
            h = new MeClusterHarness(portBase, name);
            // after the harness defaults (no cadence, no pruner): this story is about both
            System.setProperty("snapshot.log.bytes", Integer.toString(SEGMENT));
            System.setProperty("snapshot.prune.enabled", "true");
            h.segmentFileLength = SEGMENT;
            h.logTermLength = "64k";
            h.beforeLaunch = (m, service) ->
                    service.offBoxModeOverride = m.id == 0 ? leaderMode : OffBoxWatermark.Mode.REQUIRED;
            h.afterConfig = m -> m.cfg.consensusModuleContext().appointedLeaderId(0);
        }

        Story start(final boolean behaviourTwo) throws Exception {
            h.startAll();
            assertEquals("the appointed leader", 0, h.awaitLeader(null, 30_000).id);
            if (behaviourTwo) {
                try (AeronCluster admin = h.connect(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN)) {
                    assertTrue("config accepted", MeClusterHarness.offer(admin,
                            BehaviourTwoLog.engineConfig(1, 2, GENESIS, 12, 1_000), 10_000) > 0);
                    assertTrue("the config is recorded on every member", MeClusterHarness.await(admin,
                            () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                }
            }
            client = h.connect();
            checking = true;
            return this;
        }

        File clusterDir(final int id) {
            return new File(h.members[id].baseDir(), ClusterConfig.CLUSTER_SUB_DIR);
        }

        void assertNoMemberPastItsWatermark() {
            if (!checking) {
                return;
            }
            for (final Member m : h.members) {
                if (!m.live) {
                    continue;
                }
                final long start = m.logStart();
                if (start > allowed[m.id]) {
                    throw new AssertionError("member " + m.id + ": log start " + start
                            + " passed the off-box position " + allowed[m.id]);
                }
            }
        }

        void set(final long genesis, final long x, final int... ids) throws Exception {
            for (final int id : ids) {
                tool(0, "set", "--cluster-dir", clusterDir(id).getAbsolutePath(), "--engine", "match",
                        "--cluster-id", "0", "--genesis-id", Long.toString(genesis), "--log-position-bytes",
                        Long.toString(x), "--source", "test-archiver");
                allowed[id] = Math.max(allowed[id], x);
            }
        }

        /** Waits for {@code done}, with the rule checked on every poll. */
        void await(final BooleanSupplier done, final String what) {
            final long deadline = System.currentTimeMillis() + WAIT_MS;
            while (true) {
                assertNoMemberPastItsWatermark();
                if (done.getAsBoolean()) {
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("timed out waiting for " + what + ": " + state());
                }
                keepAlive();
                MeClusterHarness.await(client, () -> false, 200);
            }
        }

        /** The session stays open through the waits and through the command runs between them. */
        void keepAlive() {
            if (System.currentTimeMillis() - lastKeepAliveMs >= 1_000) {
                client.sendKeepAlive();
                lastKeepAliveMs = System.currentTimeMillis();
            }
        }

        /** Orders flow until {@code done}; every command is one more frame in the log, whatever its outcome. */
        void loadUntil(final BooleanSupplier done, final String what) {
            final long deadline = System.currentTimeMillis() + WAIT_MS;
            while (true) {
                assertNoMemberPastItsWatermark();
                if (done.getAsBoolean()) {
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("timed out under load waiting for " + what + ": " + state());
                }
                for (int i = 0; i < 100; i++) {
                    final long o = nextOrder++;
                    assertTrue("offer accepted", MeClusterHarness.offer(client,
                            DurableLedgerV2Test.create(o, 0, 100, 0, 59_000 + (o % 100), 1), 10_000) > 0);
                }
                MeClusterHarness.await(client, () -> false, 100);
            }
        }

        void loadUntilSnapshotsReach(final long position) {
            loadUntil(() -> {
                for (final Member m : h.members) {
                    if (m.live && m.newestSnapshot() < position) {
                        return false;
                    }
                }
                return true;
            }, "a snapshot at or above " + position + " on every member");
        }

        void awaitLogStart(final long start, final int... ids) {
            await(() -> {
                for (final int id : ids) {
                    if (h.members[id].logStart() != start) {
                        return false;
                    }
                }
                return true;
            }, "log start " + start + " on members " + java.util.Arrays.toString(ids));
        }

        void awaitState(final int id, final OffBoxWatermark.State state) {
            await(() -> {
                final OffBoxWatermark w = h.members[id].offBox();
                return w != null && w.state() == state;
            }, "member " + id + " off-box state " + state);
        }

        void pump(final long ms) {
            final long until = System.currentTimeMillis() + ms;
            await(() -> System.currentTimeMillis() >= until, "a pause");
        }

        String state() {
            final StringBuilder sb = new StringBuilder();
            for (final Member m : h.members) {
                if (!m.live) {
                    continue;
                }
                final OffBoxWatermark w = m.offBox();
                sb.append("[member ").append(m.id).append(" log start ").append(m.logStart()).append(" allowed ")
                        .append(allowed[m.id]).append(" off-box ").append(w == null ? "unwired" : w.state() + "@"
                                + w.positionBytes()).append(" error ").append(m.prunerLastError())
                        .append(" newest snapshot ").append(m.newestSnapshot()).append("] ");
            }
            return sb.toString();
        }

        @Override
        public void close() {
            CloseHelper.quietClose(client);
            h.close();
        }
    }

    private static long at(final int segments) {
        return (long) segments * SEGMENT + 1_000;
    }

    private static long base(final int segments) {
        return (long) segments * SEGMENT;
    }

    private static String series(final Member m, final String name) {
        for (final String line : m.metrics().split("\n")) {
            if (line.startsWith(name + " ")) {
                return line.substring(name.length() + 1).trim();
            }
        }
        return null;
    }

    @Test(timeout = 900_000)
    public void noMemberPurgesPastItsOwnWatermarkAndEveryHoldRecovers() throws Exception {
        try (Story s = new Story(23_400, "offbox-required", OffBoxWatermark.Mode.REQUIRED).start(true)) {
            final MeClusterHarness h = s.h;

            // 1. no watermark anywhere: snapshots pile up and nobody purges anything
            s.loadUntilSnapshotsReach(base(3));
            for (final Member m : h.members) {
                s.awaitState(m.id, OffBoxWatermark.State.ABSENT);
            }
            s.pump(12_000);
            for (final Member m : h.members) {
                assertEquals("member " + m.id + " holds its whole log with no watermark", 0, m.logStart());
                assertTrue(m.prunerLastError(), m.prunerLastError().contains("off-box watermark ABSENT"));
                assertEquals("1", series(m, "match_retention_offbox_state"));
                assertEquals("2", series(m, "match_retention_offbox_mode"));
            }

            // 2. X on every member: each purges to the segment below X and no further
            s.set(GENESIS, at(1), 0, 1, 2);
            s.awaitLogStart(base(1), 0, 1, 2);
            for (final Member m : h.members) {
                s.awaitState(m.id, OffBoxWatermark.State.VALID);
                assertEquals(Long.toString(at(1)), series(m, "match_retention_offbox_position_bytes"));
            }

            // 3. X advances on two members; the update reaches the third later
            s.set(GENESIS, at(2), 0, 1);
            s.awaitLogStart(base(2), 0, 1);
            s.pump(8_000);
            assertEquals("member 2 missed the update and holds more log, nothing else", base(1),
                    h.members[2].logStart());
            s.set(GENESIS, at(2), 2);
            s.awaitLogStart(base(2), 2);

            // 4. one member's file is deleted: it holds where it is while the others move; restored, it catches up
            Files.delete(OffBoxWatermark.file(s.clusterDir(1)));
            s.allowed[1] = h.members[1].logStart();
            s.awaitState(1, OffBoxWatermark.State.ABSENT);
            s.loadUntilSnapshotsReach(base(5));
            s.set(GENESIS, at(4), 0, 2);
            s.awaitLogStart(base(4), 0, 2);
            assertEquals("the member without a file did not move", base(2), h.members[1].logStart());
            s.set(GENESIS, at(4), 1);
            s.awaitLogStart(base(4), 1);
            s.awaitState(1, OffBoxWatermark.State.VALID);

            // 5. a file of another engine holds; a correct one clears it
            tool(0, "clear", "--cluster-dir", s.clusterDir(2).getAbsolutePath(), "--genesis-id",
                    Long.toString(GENESIS));
            s.allowed[2] = h.members[2].logStart();
            tool(0, "set", "--cluster-dir", s.clusterDir(2).getAbsolutePath(), "--engine", "core", "--cluster-id",
                    "0", "--genesis-id", Long.toString(GENESIS), "--log-position-bytes", Long.toString(at(6)),
                    "--source", "test-archiver");
            s.awaitState(2, OffBoxWatermark.State.FOREIGN);
            s.loadUntilSnapshotsReach(base(7));
            s.set(GENESIS, at(6), 0, 1);
            s.awaitLogStart(base(6), 0, 1);
            assertEquals("the member with a foreign file did not move", base(4), h.members[2].logStart());
            tool(0, "clear", "--cluster-dir", s.clusterDir(2).getAbsolutePath(), "--genesis-id",
                    Long.toString(GENESIS));
            s.set(GENESIS, at(6), 2);
            s.awaitLogStart(base(6), 2);

            // 6. the operator releases the leader's hold: its floor moves, the followers keep their own bound
            s.loadUntilSnapshotsReach(base(9));
            final String released = tool(0, "release", "--cluster-dir", s.clusterDir(0).getAbsolutePath(),
                    "--genesis-id", Long.toString(GENESIS), "--up-to-log-position-bytes", Long.toString(at(8)),
                    "--reason", "archiver down");
            assertTrue(released, released.contains("off-box hold released by operator up to " + at(8)));
            s.allowed[0] = at(8);
            s.awaitLogStart(base(8), 0);
            assertEquals(Long.toString(at(8)), series(h.members[0], "match_retention_offbox_released_bytes"));
            assertEquals("a follower is not released by the leader's release", base(6), h.members[1].logStart());
            s.set(GENESIS, at(8), 0, 1, 2);
            s.awaitLogStart(base(8), 1, 2);

            // 7. a restarted follower keeps holding at its file; restarted without the file it holds everything
            final Member f = h.members[1];
            f.close();
            f.start();
            s.await(() -> f.offBox() != null, "the restarted member's pruner");
            s.loadUntilSnapshotsReach(base(11));
            s.set(GENESIS, at(10), 0, 2);
            s.awaitLogStart(base(10), 0, 2);
            assertEquals("the restarted member holds at the file it kept", base(8), f.logStart());
            f.close();
            Files.delete(OffBoxWatermark.file(s.clusterDir(1)));
            s.allowed[1] = base(8);
            f.start();
            s.await(() -> f.offBox() != null, "the restarted member's pruner");
            s.awaitState(1, OffBoxWatermark.State.ABSENT);
            s.loadUntilSnapshotsReach(base(13));
            s.set(GENESIS, at(12), 0, 2);
            s.awaitLogStart(base(12), 0, 2);
            assertEquals("restarted with the file gone: nothing purged", base(8), f.logStart());
            s.set(GENESIS, at(12), 1);
            s.awaitLogStart(base(12), 1);
            System.out.println("GREEN off-box required (match, embedded): " + s.state());
        }
    }

    /** The control: the leader does not apply the bound, passes X, and the rule fails for that member only. */
    @Test(timeout = 600_000)
    public void controlAMemberThatDoesNotApplyTheBoundPassesItsWatermark() throws Exception {
        try (Story s = new Story(23_700, "offbox-control", OffBoxWatermark.Mode.REPORT).start(true)) {
            s.set(GENESIS, at(1), 0, 1, 2);
            try {
                s.loadUntilSnapshotsReach(base(4));
                s.awaitLogStart(base(3), 0);
                fail("negative control did not fail: the rule cannot see a member that ignores its watermark");
            } catch (final AssertionError expected) {
                assertTrue(String.valueOf(expected.getMessage()), expected.getMessage() != null
                        && expected.getMessage().startsWith("member 0: log start ")
                        && expected.getMessage().contains("passed the off-box position " + at(1)));
            }
            for (final int id : new int[] {1, 2}) {
                assertTrue("member " + id + " applies the bound", s.h.members[id].logStart() <= at(1));
            }
            System.out.println("NC off-box unapplied on the leader -> member 0 passed " + at(1));
        }
    }

    /**
     * A behaviour-1 cluster records no genesis id, so a watermark can never be matched to it: under
     * {@code required} it is UNBOUND and holds its whole log. {@code required} needs behaviour 2 or more.
     */
    @Test(timeout = 600_000)
    public void aBehaviourOneClusterUnderRequiredIsUnboundAndHolds() throws Exception {
        try (Story s = new Story(24_000, "offbox-behaviour-one", OffBoxWatermark.Mode.REQUIRED).start(false)) {
            // allowed stays 0 for every member: the files below must not release anything
            for (int id = 0; id < MeClusterHarness.MEMBERS; id++) {
                tool(0, "set", "--cluster-dir", s.clusterDir(id).getAbsolutePath(), "--engine", "match",
                        "--cluster-id", "0", "--genesis-id", Long.toString(GENESIS), "--log-position-bytes",
                        Long.toString(at(1)), "--source", "test-archiver");
            }
            for (final Member m : s.h.members) {
                s.awaitState(m.id, OffBoxWatermark.State.UNBOUND);
            }
            s.loadUntilSnapshotsReach(base(3));
            s.pump(12_000);
            for (final Member m : s.h.members) {
                assertEquals("member " + m.id + " holds its whole log", 0, m.logStart());
                assertEquals(OffBoxWatermark.State.UNBOUND, m.offBox().state());
                assertTrue(m.prunerLastError(), m.prunerLastError().contains("off-box watermark UNBOUND"));
                assertEquals("3", series(m, "match_retention_offbox_state"));
            }
            System.out.println("GREEN behaviour 1 under required: UNBOUND, log start 0 on every member");
        }
    }
}
