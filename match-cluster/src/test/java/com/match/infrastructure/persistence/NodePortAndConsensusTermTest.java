// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.ClusterPorts;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Where a member's node endpoint listens and what term its consensus channel takes, as {@code ClusterConfig.create}
 * wires them: the endpoint on the member ports' rule (the same port on every host at the default stride), an override
 * that disagrees refused before any Aeron component exists, and the consensus channel on
 * {@code TRANSPORT_CONSENSUS_TERM_LENGTH} instead of the driver's 16m.
 */
public class NodePortAndConsensusTermTest {

    private static final List<String> HOSTS = List.of("localhost", "localhost", "localhost");

    @After
    public void tearDown() {
        System.clearProperty("cluster.port.stride");
        System.clearProperty("metrics.port");
        System.clearProperty("transport.consensus.term.length");
        ClusterPorts.resetStrideForTest();
    }

    /** One member per host (stride 0): members 0 and 2 serve the endpoint on the same port. The old rule gave 9500/9502. */
    @Test
    public void membersZeroAndTwoShareTheEndpointPortAtTheDefaultStride() {
        assertEquals(0, ClusterPorts.stride());
        assertEquals(9006, ClusterConfig.nodeEndpointPort(0, 9000));
        assertEquals(ClusterConfig.nodeEndpointPort(0, 9000), ClusterConfig.nodeEndpointPort(2, 9000));
        final AppClusteredService two = new AppClusteredService();
        ClusterConfig.create(2, HOSTS, HOSTS, 9000, two);
        assertEquals("the port create hands the service", 9006, two.nodeEndpointPort());
    }

    /** Co-located members (stride 100) get distinct endpoint ports, one stride apart, from create. */
    @Test
    public void aSharedHostStrideGivesEachMemberItsOwnEndpointPort() {
        System.setProperty("cluster.port.stride", "100");
        ClusterPorts.resetStrideForTest();
        for (int id = 0; id < 3; id++) {
            final AppClusteredService s = new AppClusteredService();
            ClusterConfig.create(id, HOSTS, HOSTS, 9000, s);
            assertEquals(9006 + id * 100, s.nodeEndpointPort());
        }
    }

    /** An agreeing override is accepted; one that disagrees refuses the boot in create, naming both ports. */
    @Test
    public void aConflictingOverrideRefusesTheBoot() {
        System.setProperty("cluster.port.stride", "100");
        ClusterPorts.resetStrideForTest();
        System.setProperty("metrics.port", "9206");
        final AppClusteredService ok = new AppClusteredService();
        ClusterConfig.create(2, HOSTS, HOSTS, 9000, ok);
        assertEquals(9206, ok.nodeEndpointPort());

        System.setProperty("metrics.port", "9502");
        try {
            ClusterConfig.create(2, HOSTS, HOSTS, 9000, new AppClusteredService());
            fail("a disagreeing METRICS_PORT must refuse the boot");
        } catch (final IllegalArgumentException expected) {
            assertEquals("METRICS_PORT=9502 disagrees with the node endpoint port 9206 (port base 9000 + node 2 x stride"
                    + " 100 + 6); unset it, or fix the port base or CLUSTER_PORT_STRIDE", expected.getMessage());
        }
    }

    /** The consensus channel names its term: 1m by default, the setting when set, a bad value refused. */
    @Test
    public void theConsensusChannelTakesTheConfiguredTerm() {
        assertEquals("aeron:udp?term-length=1m", ClusterConfig.create(0, HOSTS, HOSTS, 9000, new AppClusteredService())
                .consensusModuleContext().consensusChannel());
        System.setProperty("transport.consensus.term.length", "256k");
        assertEquals("aeron:udp?term-length=256k", ClusterConfig.create(0, HOSTS, HOSTS, 9000,
                new AppClusteredService()).consensusModuleContext().consensusChannel());
        System.setProperty("transport.consensus.term.length", "1000k");
        try {
            ClusterConfig.create(0, HOSTS, HOSTS, 9000, new AppClusteredService());
            fail("a term that is not a power of two must refuse the boot");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().equals(
                    "TRANSPORT_CONSENSUS_TERM_LENGTH 1000k is not a power of two"));
        }
    }
}
