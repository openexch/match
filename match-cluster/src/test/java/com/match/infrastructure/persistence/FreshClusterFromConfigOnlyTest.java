// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.TestEngines;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.ExpandableArrayBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * S4 T9 (R-C2): three members whose settings differ (where legal: ignored; otherwise refused) build the same engines,
 * from the replicated EngineConfig only, at the same log position, and hold byte-identical state (full bytes and
 * SHA-256 of each member's serialized engine, and the same snapshot state hash). Run on embedded drivers and on
 * drivers launched apart from the archive and the consensus module (the external shape).
 *
 * <p>The control puts the removed switch back on one member (a test-factory engine with the compiled markets): an
 * order before the config makes that member's state differ from the others', which this test asserts.</p>
 */
@RunWith(Parameterized.class)
public class FreshClusterFromConfigOnlyTest {

    @Parameterized.Parameters(name = "{0}")
    public static Object[] modes() {
        return MemberProcess.Driver.values();
    }

    private final MemberProcess.Driver mode;

    public FreshClusterFromConfigOnlyTest(final MemberProcess.Driver mode) {
        this.mode = mode;
    }

    private int portBase(final int row) {
        return 28_000 + mode.ordinal() * 1_000 + row * 400;
    }

    private static byte[] engineBytes(final MeClusterHarness.Member m) {
        final ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(m.engine(), 0L, b);
        return Arrays.copyOf(b.byteArray(), len);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Member 0 runs with no setting, member 1 with the accepted switch, member 2 with the switch too. */
    private static void perMemberSettings(final MeClusterHarness h) {
        h.beforeLaunch = (m, s) -> {
            if (m.id == 0) {
                System.clearProperty("match.engine.from.config");
            } else {
                System.setProperty("match.engine.from.config", "true");
            }
        };
    }

    @Test
    public void everyMemberBuildsTheSameEnginesFromTheConfigAtTheSamePosition() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(portBase(0), "fresh-config-only-" + mode)) {
            h.externalDriver = mode == MemberProcess.Driver.EXTERNAL;
            perMemberSettings(h);
            h.members[0].start();
            h.members[1].start();
            // a member with a refused setting does not start; unset, it does
            System.setProperty("match.engine.impl", "direct");
            try {
                final ClusterConfig.SettingRefusedException refused = assertThrows(
                        ClusterConfig.SettingRefusedException.class, () -> h.members[2].start());
                assertTrue(refused.getMessage(), refused.getMessage().startsWith("match.engine.impl is no longer read"));
            } finally {
                System.clearProperty("match.engine.impl");
            }
            h.members[2].start();
            final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
                 AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                for (final MeClusterHarness.Member m : h.members) {
                    assertFalse("member " + m.id + " has no engines before the config", m.engine().hasEngines());
                }
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin,
                        () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                // record 5 carries the FRESH position: one copy in the union means one position on every member
                assertEquals("the FRESH record is identical on every member", 0, h.unionMismatches.get());
                for (final MeClusterHarness.Member m : h.members) {
                    assertTrue("member " + m.id + " created its engines", m.engine().hasEngines());
                    assertEquals(m.engine().getEngineConfig().describe(),
                            h.members[0].engine().getEngineConfig().describe());
                }
                for (int i = 0; i < 12; i++) {
                    assertTrue(MeClusterHarness.offer(forwarder, create(40_001 + i, 0, 100 + i % 3, i % 2,
                            59_000 + (i % 4) * 10, 1 + i % 2), 10_000) > 0);
                }
                assertTrue(h.awaitQuiescence(forwarder, 30_000));
                final byte[] first = engineBytes(h.members[0]);
                for (final MeClusterHarness.Member m : h.members) {
                    final byte[] bytes = engineBytes(m);
                    assertArrayEquals("member " + m.id + " state bytes", first, bytes);
                    assertEquals("member " + m.id + " state SHA-256", sha256(first), sha256(bytes));
                }
                h.snapshot(leader, admin);
                final long[][] series = h.awaitSnapshotSeries(admin, -1, 30_000);
                for (int i = 1; i < series.length; i++) {
                    assertArrayEquals("member " + i + " snapshot {position, state hash}", series[0], series[i]);
                }
                assertEquals("equal journals", 0, h.unionMismatches.get());
            }
        }
    }

    /** Control: the old switch restored on one member forks the state on an order before the config. */
    @Test
    public void controlTheOldSwitchOnOneMemberMakesTheStatesDiffer() throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(portBase(1), "fresh-config-control-" + mode)) {
            h.externalDriver = mode == MemberProcess.Driver.EXTERNAL;
            h.serviceFactory = id -> id == 2 ? AppClusteredService.withEngine(TestEngines.withCompiledMarkets())
                    : new AppClusteredService();
            h.startAll();
            h.awaitLeader(null, 30_000);
            try (AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                assertTrue(MeClusterHarness.offer(forwarder, create(41_001, 0, 101, 0, 59_000, 1), 10_000) > 0);
                assertTrue("the config-only members rejected the order", MeClusterHarness.await(forwarder,
                        () -> h.members[0].demuxer().preConfigOrderRejectCount() == 1
                                && h.members[1].demuxer().preConfigOrderRejectCount() == 1, 20_000));
                MeClusterHarness.await(forwarder, () -> false, 1_000);
                assertEquals("the legacy member did not reject it", 0, h.members[2].demuxer().preConfigOrderRejectCount());
                assertArrayEquals("the two config-only members agree", engineBytes(h.members[0]),
                        engineBytes(h.members[1]));
                assertFalse("the member with the old switch holds another state",
                        Arrays.equals(engineBytes(h.members[0]), engineBytes(h.members[2])));
            }
        }
    }
}
