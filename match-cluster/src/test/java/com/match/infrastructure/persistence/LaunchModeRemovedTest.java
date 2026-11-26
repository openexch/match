// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import org.junit.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * S4 T8 (R-C1..R-C3): the match launch mode is gone. The old settings refuse the boot with exit 78 and one line
 * (only {@code MATCH_ENGINE_FROM_CONFIG=true}, which launchers set today, is accepted and ignored); no member has
 * engines before the first EngineConfig, and an order before it is rejected on every member alike.
 */
public class LaunchModeRemovedTest {

    private static String refusal(final Map<String, String> set) {
        return ClusterConfig.removedSettingsRefusal(set::get);
    }

    @Test
    public void theSwitchAcceptsExactlyTrueAndRefusesEveryOtherValue() {
        for (final String name : new String[] {"MATCH_ENGINE_FROM_CONFIG", "match.engine.from.config"}) {
            assertNull(name + "=true is accepted and ignored", refusal(Map.of(name, "true")));
            for (final String value : new String[] {"false", "1", "", "TRUE", " true", "true "}) {
                assertEquals(name + "=" + value, name + "=" + value + " is no longer supported: engines are created"
                        + " only from the replicated EngineConfig; unset it", refusal(Map.of(name, value)));
            }
        }
        assertNull("nothing set", refusal(Map.of()));
    }

    @Test
    public void theImplementationAndCapacitySettingsAreRefusedWhenSet() {
        for (final String name : new String[] {"MATCH_ENGINE_IMPL", "match.engine.impl", "MATCH_ENGINE_BOOK_CAPACITY",
                "match.engine.book.capacity"}) {
            for (final String value : new String[] {"array", "direct", "4096", ""}) {
                assertEquals(name + "=" + value, name + " is no longer read: the implementation and the capacity come"
                        + " from the replicated EngineConfig; unset it", refusal(Map.of(name, value)));
            }
        }
    }

    @Test
    public void clusterConfigCreateRefusesBeforeAnyAeronComponent() {
        System.setProperty("match.engine.from.config", "false");
        try {
            final ClusterConfig.SettingRefusedException e = assertThrows(ClusterConfig.SettingRefusedException.class,
                    () -> ClusterConfig.create(0, List.of("localhost"), 29_900, new AppClusteredService()));
            assertTrue(e.getMessage(), e.getMessage().startsWith("match.engine.from.config=false is no longer supported"));
        } finally {
            System.clearProperty("match.engine.from.config");
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void assertProcessRefuses(final MemberProcess.Driver mode, final String name, final String value,
                                             final String text) throws Exception {
        final Path work = Files.createTempDirectory("s4-t8-" + mode + "-");
        final Map<String, String> env = new HashMap<>(MemberProcess.standaloneEnv(work,
                freePort() - com.openexchange.cluster.ClusterPorts.NODE_ENDPOINT_OFFSET));
        env.put(name, value);
        try (MemberProcess p = MemberProcess.launch(work, mode, MatchMemberCompat.MEMBER_COMPAT, env, List.of())) {
            final MemberProcess.Exit exit = p.awaitExit(60_000);
            final String log = p.log();
            assertEquals(mode + " " + name + "=" + value + " exit: " + log, 78, exit.code());
            assertTrue(log, log.contains("Match Engine node FATAL (exit 78): " + text));
            assertFalse("refused before any Aeron component: " + log, log.contains("Driver mode"));
        } finally {
            org.agrona.IoUtil.delete(work.toFile(), true);
        }
    }

    @Test
    public void aMemberProcessWithAnOldSettingExits78InBothDriverModes() throws Exception {
        for (final MemberProcess.Driver mode : MemberProcess.Driver.values()) {
            assertProcessRefuses(mode, "MATCH_ENGINE_FROM_CONFIG", "false", "MATCH_ENGINE_FROM_CONFIG=false is no longer"
                    + " supported: engines are created only from the replicated EngineConfig; unset it");
            assertProcessRefuses(mode, "MATCH_ENGINE_IMPL", "direct", "MATCH_ENGINE_IMPL is no longer read: the"
                    + " implementation and the capacity come from the replicated EngineConfig; unset it");
        }
    }

    @Test
    public void aMemberProcessWithTheSwitchSetToTrueStarts() throws Exception {
        final Path work = Files.createTempDirectory("s4-t8-true-");
        final int probe = freePort();
        final Map<String, String> env = new HashMap<>(MemberProcess.standaloneEnv(work,
                probe - com.openexchange.cluster.ClusterPorts.NODE_ENDPOINT_OFFSET));
        env.put("MATCH_ENGINE_FROM_CONFIG", "true");
        try (MemberProcess p = MemberProcess.launch(work, MemberProcess.Driver.EMBEDDED, MatchMemberCompat.MEMBER_COMPAT,
                env, List.of())) {
            final String ready = p.awaitReady(probe, 90_000);
            assertTrue(ready, ready.contains(" recorded_behavior=0 "));
        } finally {
            org.agrona.IoUtil.delete(work.toFile(), true);
        }
    }

    @Test
    public void noMemberHasEnginesBeforeTheFirstEngineConfigAndAnOrderBeforeItIsRejectedOnEveryMember()
            throws Exception {
        try (MeClusterHarness h = new MeClusterHarness(26_800, "launch-mode-removed")) {
            h.startAll();
            h.awaitLeader(null, 30_000);
            try (AeronCluster admin = h.connect(ClusterSessionClass.ADMIN);
                 AeronCluster forwarder = h.connect(ClusterSessionClass.FORWARDER)) {
                for (final MeClusterHarness.Member m : h.members) {
                    assertFalse("member " + m.id + " has no engines at boot", m.engine().hasEngines());
                }
                assertTrue(MeClusterHarness.offer(forwarder, create(30_001, 0, 101, 0, 59_000, 1), 10_000) > 0);
                assertTrue("every member rejected the pre-config order", MeClusterHarness.await(forwarder,
                        () -> java.util.Arrays.stream(h.members).allMatch(m -> m.demuxer().preConfigOrderRejectCount() == 1),
                        20_000));
                for (final MeClusterHarness.Member m : h.members) {
                    assertFalse("member " + m.id + " still has no engines", m.engine().hasEngines());
                }
                assertTrue(MeClusterHarness.offer(admin, engineConfig(1, 2, G, 12, 1_000), 10_000) > 0);
                assertTrue(MeClusterHarness.await(admin,
                        () -> h.engineConfigRecords.get() >= MeClusterHarness.MEMBERS, 20_000));
                for (final MeClusterHarness.Member m : h.members) {
                    assertTrue("member " + m.id + " created its engines from the config", m.engine().hasEngines());
                    assertEquals("member " + m.id + " rejected exactly the one order", 1,
                            m.demuxer().preConfigOrderRejectCount());
                }
            }
        }
    }

    /** The member version this test's members carry is the build's. */
    @Test
    public void theBuildsMemberVersionIsGenerationOneLevelOne() {
        assertEquals(MemberCompat.of(1, 1), MatchMemberCompat.MEMBER_COMPAT);
    }
}
