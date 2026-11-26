// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * S4 T5 (match): an incompatible member is a production process ({@code com.match.Main} through
 * {@link CompatLevelMain}) joining a three-member cluster whose other two members run in-process at level N
 * ({@code g1.1}). It stops itself with exit 78 and the R-A4 text, the cluster keeps serving, and no healthy member's
 * validator refused anything. Run with the process member on an EMBEDDED and on an EXTERNAL media driver.
 *
 * <ul>
 *   <li>N+2 with local state from this cluster: exit 78 at {@code checkBeforeLaunch}, naming the last seen level;
 *       restarted on N it rejoins (recovery).</li>
 *   <li>N+2 with an empty directory: it joins the election, meets the leader's version and exits 78.</li>
 *   <li>another generation ({@code g2.1}), empty directory: exit 78.</li>
 *   <li>N+2 with state appointed leader of a forced election: exit 78 before it can canvass, no healthy member
 *       stopped. The same forced election with the member at N elects it (the kill window is real).</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class IncompatibleMemberProcessTest {

    @Parameterized.Parameters(name = "{0}")
    public static Object[] modes() {
        return MemberProcess.Driver.values();
    }

    /**
     * Negative controls ({@code -Ds4.control=}), the core's seam shape: {@code a} turns the pre-election check off in
     * the forced-election child (it wins and the assertion "no healthy member stopped" fails); {@code b} gives the
     * empty-directory child Aeron's default validator (it joins and the assertion "exit == 78" fails).
     */
    private static final String CONTROL = System.getProperty("s4.control", "");

    private static final MemberCompat N = MemberCompat.of(1, 1);
    private static final MemberCompat N_PLUS_2 = MemberCompat.of(1, 3);
    private static final MemberCompat OTHER_GENERATION = MemberCompat.of(2, 1);
    private static final String FATAL_78 = "Match Engine node FATAL (exit 78): member version incompatible: this build is ";

    private final MemberProcess.Driver mode;
    private final AtomicInteger refusals = new AtomicInteger();
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private long nextId = 20_001;

    public IncompatibleMemberProcessTest(final MemberProcess.Driver mode) {
        this.mode = mode;
    }

    @After
    public void cleanUp() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeables.get(i).close();
        }
    }

    private int portBase(final int row) {
        return 27_000 + mode.ordinal() * 2_000 + row * 400;
    }

    private MeClusterHarness harness(final int row, final String name, final int appointedLeader) {
        final MeClusterHarness h = new MeClusterHarness(portBase(row), name + "-" + mode);
        closeables.add(h);
        h.afterConfig = m -> {
            m.cfg.memberCompat(N, (own, other) -> {
                refusals.incrementAndGet();
                System.err.println("[T5] healthy member " + m.id + " REFUSED " + other);
            });
            if (appointedLeader >= 0) {
                m.cfg.consensusModuleContext().appointedLeaderId(appointedLeader);
            }
        };
        return h;
    }

    private MemberProcess member(final MeClusterHarness h, final int id, final MemberCompat version,
                                 final List<String> jvmArgs) throws Exception {
        final Path work = Files.createTempDirectory("s4-t5-" + mode + "-" + version + "-");
        final MemberProcess p = MemberProcess.launch(work, mode, version, MemberProcess.joiningEnv(h, id), jvmArgs);
        closeables.add(p);
        closeables.add(() -> org.agrona.IoUtil.delete(work.toFile(), true));
        return p;
    }

    private void configureAndLoad(final MeClusterHarness h, final int members) {
        try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
             AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
            assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
            assertTrue(MeClusterHarness.await(admin, () -> h.engineConfigRecords.get() >= members, 20_000));
            serve(h, forwarder);
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /** Orders that every live member applies: the cluster serves. */
    private void serve(final MeClusterHarness h, final AeronCluster forwarder) {
        final List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final long id = nextId++;
            ids.add(id);
            assertTrue(MeClusterHarness.offer(forwarder, create(id, 0, 100 + i, i % 2, 59_000 + i * 10, 1), 10_000) > 0);
        }
        assertTrue("the cluster serves", MeClusterHarness.await(forwarder,
                () -> ids.stream().allMatch(id -> h.outcomes.containsKey(MeClusterHarness.key(id, 0))), 30_000));
        for (final long id : ids) {
            assertEquals("order " + id + " APPLIED", 0L, h.outcomes.get(MeClusterHarness.key(id, 0))[0]);
        }
    }

    private void assertKeepsServing(final MeClusterHarness h) throws Exception {
        try (AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
            serve(h, forwarder);
        }
        assertEquals("no healthy member stopped (no healthy validator refused)", 0, refusals.get());
    }

    private static void assertExit78(final MemberProcess p, final String text) throws Exception {
        final MemberProcess.Exit exit = p.awaitExit(60_000);
        final String log = p.log();
        assertEquals("exit == 78: " + log, 78, exit.code());
        assertTrue("the R-A4 line: " + log, log.contains(text));
        assertTrue("one line naming the recovery: " + log, log.contains(
                "a cluster runs level N and N-1 of one generation; install a compatible build, a restart cannot fix this"));
    }

    @Test
    public void aMemberTwoLevelsAheadWithLocalStateExits78BeforeLaunchAndRejoinsOnALegalBuild() throws Exception {
        final MeClusterHarness h = harness(0, "t5-state", -1);
        h.startAll();
        final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
        configureAndLoad(h, MeClusterHarness.MEMBERS);
        final MeClusterHarness.Member victim = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
        victim.close();
        assertEquals("the member recorded the version it accepted", N.level(),
                MemberCompat.readSeenOrNull(new java.io.File(victim.baseDir(), ClusterConfig.CLUSTER_SUB_DIR)).levelMax());

        final MemberProcess ahead = member(h, victim.id, N_PLUS_2, List.of());
        assertExit78(ahead, FATAL_78 + N_PLUS_2 + " (build ");
        assertTrue(ahead.log(), ahead.log().contains("the cluster's last seen level is " + N));
        assertKeepsServing(h);

        // recovery: the right build on the same state starts and rejoins
        final MemberProcess legal = member(h, victim.id, N, List.of());
        final String ready = legal.awaitReady(portBase(0) + victim.id * 100
                + com.openexchange.cluster.ClusterPorts.NODE_ENDPOINT_OFFSET, 90_000);
        assertTrue(ready, ready.contains(" compat=" + N + " "));
        assertTrue(ready, ready.contains(" recorded_behavior=2 "));
        assertTrue(ready, ready.contains(" driver=" + mode.name().toLowerCase() + "\n")
                || ready.endsWith(" driver=" + mode.name().toLowerCase()));
        assertKeepsServing(h);
    }

    @Test
    public void aMemberTwoLevelsAheadWithAnEmptyDirectoryExits78AfterItMeetsTheLeader() throws Exception {
        final MeClusterHarness h = harness(1, "t5-empty", -1);
        h.members[0].start();
        h.members[1].start();
        h.awaitLeader(null, 30_000);
        configureAndLoad(h, 2);
        final MemberProcess ahead = member(h, 2, N_PLUS_2,
                "b".equals(CONTROL) ? List.of("-Dtest.member.compat.validator=aeron") : List.of());
        assertExit78(ahead, FATAL_78 + N_PLUS_2 + " (build ");
        assertTrue(ahead.log(), ahead.log().contains("the cluster's leader, log or snapshot is " + N));
        assertKeepsServing(h);
    }

    @Test
    public void aMemberOfAnotherGenerationExits78() throws Exception {
        final MeClusterHarness h = harness(2, "t5-generation", -1);
        h.members[0].start();
        h.members[1].start();
        h.awaitLeader(null, 30_000);
        configureAndLoad(h, 2);
        final MemberProcess other = member(h, 2, OTHER_GENERATION, List.of());
        assertExit78(other, FATAL_78 + OTHER_GENERATION + " (build ");
        assertTrue(other.log(), other.log().contains("the cluster's leader, log or snapshot is " + N));
        assertKeepsServing(h);
    }

    /** Stops the in-process cluster, then restarts members 0 and 1 for a forced election that only member 2 can win. */
    private MeClusterHarness forcedElection(final int row, final String name) throws Exception {
        final MeClusterHarness h = harness(row, name, -1);
        h.startAll();
        h.awaitLeader(null, 30_000);
        configureAndLoad(h, MeClusterHarness.MEMBERS);
        for (final MeClusterHarness.Member m : h.members) {
            m.close();
        }
        final java.util.function.Consumer<MeClusterHarness.Member> base = h.afterConfig;
        h.afterConfig = m -> {
            base.accept(m);
            m.cfg.consensusModuleContext().appointedLeaderId(2);
        };
        h.members[0].start();
        h.members[1].start();
        return h;
    }

    @Test
    public void aMemberTwoLevelsAheadWithStateCannotWinAForcedElection() throws Exception {
        final MeClusterHarness h = forcedElection(3, "t5-forced");
        final MemberProcess ahead = member(h, 2, N_PLUS_2, "a".equals(CONTROL)
                ? List.of("-Daeron.cluster.appointed.leader.id=2", "-Dtest.member.compat.prelaunch=off")
                : List.of("-Daeron.cluster.appointed.leader.id=2"));
        final MemberProcess.Exit exit = ahead.awaitExit(60_000);
        // the window in which an elected N+2 leader would have stopped the followers (it sends them its term
        // before its own log replay refuses the older stamps and stops it too)
        Thread.sleep(10_000);
        assertEquals("no healthy member stopped (no healthy validator refused)", 0, refusals.get());
        assertEquals("exit == 78: " + ahead.log(), 78, exit.code());
        assertTrue("stopped before launch, by the seen file: " + ahead.log(),
                ahead.log().contains(FATAL_78 + N_PLUS_2 + " (build ")
                        && ahead.log().contains("the cluster's last seen level is " + N));
        for (final MeClusterHarness.Member m : new MeClusterHarness.Member[] {h.members[0], h.members[1]}) {
            assertTrue("member " + m.id + " is still running", m.live && m.cfg.consensusModuleContext()
                    .clusterNodeRoleCounter() != null && !m.cfg.consensusModuleContext().clusterNodeRoleCounter().isClosed());
        }
    }

    /** C14: the forced election with every member at one level elects member 2, so the window above is real. */
    @Test
    public void theForcedElectionAtOneLevelElectsTheAppointedMember() throws Exception {
        final MeClusterHarness h = forcedElection(4, "t5-forced-proof");
        final MemberProcess same = member(h, 2, N, List.of("-Daeron.cluster.appointed.leader.id=2"));
        final String ready = same.awaitReady(portBase(4) + 2 * 100
                + com.openexchange.cluster.ClusterPorts.NODE_ENDPOINT_OFFSET, 90_000);
        assertTrue("member 2 leads: " + ready, ready.contains("role=LEADER"));
        assertKeepsServing(h);
    }
}
