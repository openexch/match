// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.ClusterPorts;
import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for ClusterConfig static utility methods.
 * No Aeron instances needed — tests only pure computational methods.
 */
public class ClusterConfigTest {

    /**
     * Several members on one host: the historical layout, and now the special case.
     * Set BEFORE the numbers are read, because the stride is resolved once.
     */
    private static void sharedHost() {
        System.setProperty("cluster.port.stride", Integer.toString(ClusterConfig.PORTS_PER_NODE));
        ClusterPorts.resetStrideForTest();
    }

    @After
    public void restoreDefaultLayout() {
        System.clearProperty("cluster.port.stride");
        ClusterPorts.resetStrideForTest();
    }

    // ==================== Port Constants ====================

    @Test
    public void portsPerNodeIsTheSharedHostStride() {
        assertEquals(100, ClusterConfig.PORTS_PER_NODE);
        assertEquals(ClusterPorts.SHARED_HOST_STRIDE, ClusterConfig.PORTS_PER_NODE);
    }

    /**
     * The default layout is one member per address, so the member id contributes
     * nothing. This is the contract every client derives independently, and it is
     * what makes a StatefulSet need one container port instead of one per ordinal.
     */
    @Test
    public void byDefaultEveryMemberListensOnTheSamePorts() {
        assertEquals(9002, ClusterConfig.calculatePort(0, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET));
        assertEquals(9002, ClusterConfig.calculatePort(1, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET));
        assertEquals(9002, ClusterConfig.calculatePort(2, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET));
    }

    /** And the endpoints that go into the membership string follow it. */
    @Test
    public void byDefaultTheMembershipStringUsesOnePortSet() {
        final String result = ClusterConfig.clusterMembers(
                Arrays.asList("ing1", "ing2", "ing3"), Arrays.asList("cls1", "cls2", "cls3"), 9000);
        final String[] members = result.split("\\|");
        assertTrue(members[0].contains("ing1:9002"));
        assertTrue(members[1].contains("ing2:9002"));
        assertTrue(members[2].contains("ing3:9002"));
        assertTrue(members[0].contains("cls1:9003"));
        assertTrue(members[1].contains("cls2:9003"));
    }

    @Test
    public void portOffsetConstants() {
        assertEquals(1, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET);
        assertEquals(2, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        assertEquals(3, ClusterConfig.MEMBER_FACING_PORT_OFFSET);
        assertEquals(4, ClusterConfig.LOG_PORT_OFFSET);
        assertEquals(5, ClusterConfig.TRANSFER_PORT_OFFSET);
    }

    // ==================== calculatePort ====================

    @Test
    public void calculatePortNode0() {
        sharedHost();
        int port = ClusterConfig.calculatePort(0, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        // 9000 + (0 * 100) + 2 = 9002
        assertEquals(9002, port);
    }

    @Test
    public void calculatePortNode1() {
        sharedHost();
        int port = ClusterConfig.calculatePort(1, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        // 9000 + (1 * 100) + 2 = 9102
        assertEquals(9102, port);
    }

    @Test
    public void calculatePortNode2() {
        sharedHost();
        int port = ClusterConfig.calculatePort(2, 9000, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET);
        // 9000 + (2 * 100) + 1 = 9201
        assertEquals(9201, port);
    }

    @Test
    public void calculatePortFormula() {
        sharedHost();
        // portBase + (nodeId * stride) + offset
        int nodeId = 5;
        int portBase = 10000;
        int offset = 3;
        int expected = portBase + (nodeId * ClusterConfig.PORTS_PER_NODE) + offset;
        assertEquals(expected, ClusterConfig.calculatePort(nodeId, portBase, offset));
    }

    @Test
    public void calculatePortWithAllOffsets() {
        sharedHost();
        int nodeId = 1;
        int portBase = 8000;

        assertEquals(8101, ClusterConfig.calculatePort(nodeId, portBase, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET));
        assertEquals(8102, ClusterConfig.calculatePort(nodeId, portBase, ClusterConfig.CLIENT_FACING_PORT_OFFSET));
        assertEquals(8103, ClusterConfig.calculatePort(nodeId, portBase, ClusterConfig.MEMBER_FACING_PORT_OFFSET));
        assertEquals(8104, ClusterConfig.calculatePort(nodeId, portBase, ClusterConfig.LOG_PORT_OFFSET));
        assertEquals(8105, ClusterConfig.calculatePort(nodeId, portBase, ClusterConfig.TRANSFER_PORT_OFFSET));
    }

    // ==================== clusterMembers ====================

    @Test
    public void clusterMembersSingleNode() {
        List<String> ingress = Collections.singletonList("host1");
        List<String> cluster = Collections.singletonList("host1");

        String result = ClusterConfig.clusterMembers(ingress, cluster, 9000);

        // Should start with "0," and contain host1 with various ports
        assertTrue(result.startsWith("0,"));
        assertTrue(result.contains("host1:"));
        assertTrue(result.endsWith("|"));
    }

    @Test
    public void clusterMembersThreeNodes() {
        sharedHost();
        List<String> ingress = Arrays.asList("ing1", "ing2", "ing3");
        List<String> cluster = Arrays.asList("cls1", "cls2", "cls3");

        String result = ClusterConfig.clusterMembers(ingress, cluster, 9000);

        // Should contain 3 member entries separated by |
        String[] members = result.split("\\|");
        assertEquals(3, members.length);

        // First member starts with "0,"
        assertTrue(members[0].startsWith("0,"));
        // Second starts with "1,"
        assertTrue(members[1].startsWith("1,"));
        // Third starts with "2,"
        assertTrue(members[2].startsWith("2,"));

        // Ingress hostnames used for client-facing port
        assertTrue(members[0].contains("ing1:9002")); // CLIENT_FACING_PORT_OFFSET=2
        assertTrue(members[1].contains("ing2:9102"));
        assertTrue(members[2].contains("ing3:9202"));

        // Cluster hostnames used for member-facing, log, transfer, archive
        assertTrue(members[0].contains("cls1:9003")); // MEMBER_FACING_PORT_OFFSET=3
        assertTrue(members[1].contains("cls2:9103"));
    }

    @Test
    public void clusterMembersWithStartingMemberId() {
        sharedHost();
        List<String> hosts = Arrays.asList("h1", "h2");

        String result = ClusterConfig.clusterMembers(10, hosts, hosts, 9000);

        // Members start from id 10
        String[] members = result.split("\\|");
        assertTrue(members[0].startsWith("10,"));
        assertTrue(members[1].startsWith("11,"));

        // Port calculation uses memberId: 9000 + (10 * 100) + 2 = 10002
        assertTrue(members[0].contains("h1:10002")); // CLIENT_FACING
    }

    @Test
    public void clusterMembersDefaultStartsAtZero() {
        List<String> hosts = Collections.singletonList("localhost");
        String result = ClusterConfig.clusterMembers(hosts, hosts, 9000);
        assertTrue(result.startsWith("0,"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void clusterMembersMismatchedListsThrows() {
        List<String> ingress = Arrays.asList("h1", "h2");
        List<String> cluster = Collections.singletonList("c1");

        ClusterConfig.clusterMembers(ingress, cluster, 9000);
    }

    // ==================== ingressEndpoints ====================

    @Test
    public void ingressEndpointsSingleNode() {
        List<String> hosts = Collections.singletonList("host1");

        String result = ClusterConfig.ingressEndpoints(hosts, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        assertEquals("0=host1:9002", result);
    }

    @Test
    public void ingressEndpointsThreeNodes() {
        sharedHost();
        List<String> hosts = Arrays.asList("h1", "h2", "h3");

        String result = ClusterConfig.ingressEndpoints(hosts, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        assertEquals("0=h1:9002,1=h2:9102,2=h3:9202", result);
    }

    @Test
    public void ingressEndpointsWithStartingMemberId() {
        sharedHost();
        List<String> hosts = Arrays.asList("a", "b");

        String result = ClusterConfig.ingressEndpoints(5, hosts, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        // memberId 5: 9000 + (5 * 100) + 2 = 9502
        // memberId 6: 9000 + (6 * 100) + 2 = 9602
        assertEquals("5=a:9502,6=b:9602", result);
    }

    @Test
    public void ingressEndpointsDefaultStartsAtZero() {
        List<String> hosts = Collections.singletonList("local");
        String result = ClusterConfig.ingressEndpoints(hosts, 8000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        assertTrue(result.startsWith("0="));
    }

    // ==================== Subdirectory Constants ====================

    @Test
    public void subdirectoryConstants() {
        assertEquals("archive", ClusterConfig.ARCHIVE_SUB_DIR);
        assertEquals("cluster", ClusterConfig.CLUSTER_SUB_DIR);
    }
}
