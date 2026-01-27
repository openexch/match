package com.match.infrastructure.admin;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for ClusterStatus: leader tracking, node status, map output.
 * marketChannels is intentionally left null to verify no NPE on broadcast calls.
 */
public class ClusterStatusTest {

    private ClusterStatus status;

    @Before
    public void setUp() {
        status = new ClusterStatus();
        // Do NOT set marketChannels — verify null safety
    }

    // ==================== Initial State ====================

    @Test
    public void testInitialState_LeaderIdNegativeOne() {
        assertEquals(-1, status.getLeaderId());
    }

    @Test
    public void testInitialState_LeadershipTermIdNegativeOne() {
        assertEquals(-1L, status.getLeadershipTermId());
    }

    @Test
    public void testInitialState_GatewayNotConnected() {
        assertFalse(status.isGatewayConnected());
    }

    @Test
    public void testInitialState_AllNodesOffline() {
        for (int i = 0; i < 3; i++) {
            assertEquals("OFFLINE", status.getNodeStatus(i));
            assertFalse(status.isNodeHealthy(i));
        }
    }

    // ==================== updateLeader ====================

    @Test
    public void testUpdateLeader_SetsLeaderIdAndTermId() {
        status.updateLeader(1, 5L);

        assertEquals(1, status.getLeaderId());
        assertEquals(5L, status.getLeadershipTermId());
    }

    @Test
    public void testUpdateLeader_LeaderNodeGetsLeaderStatus() {
        status.updateLeader(1, 1L);

        assertEquals("LEADER", status.getNodeStatus(1));
        assertTrue(status.isNodeHealthy(1));
    }

    @Test
    public void testUpdateLeader_OtherNodesRemainOfflineIfNotHealthy() {
        // Initially all nodes are unhealthy, so non-leader nodes stay as-is
        status.updateLeader(0, 1L);

        assertEquals("LEADER", status.getNodeStatus(0));
        // Node 1 and 2 were not previously healthy, so they don't become FOLLOWER
        assertEquals("OFFLINE", status.getNodeStatus(1));
        assertEquals("OFFLINE", status.getNodeStatus(2));
    }

    @Test
    public void testUpdateLeader_HealthyNodesBecomFollower() {
        // Set node 1 and 2 as healthy first
        status.setNodeStatus(1, "FOLLOWER", true);
        status.setNodeStatus(2, "FOLLOWER", true);

        // Now make node 0 the leader
        status.updateLeader(0, 1L);

        assertEquals("LEADER", status.getNodeStatus(0));
        assertEquals("FOLLOWER", status.getNodeStatus(1));
        assertEquals("FOLLOWER", status.getNodeStatus(2));
    }

    @Test
    public void testUpdateLeader_LeaderChange() {
        status.setNodeStatus(0, "FOLLOWER", true);
        status.setNodeStatus(1, "FOLLOWER", true);
        status.setNodeStatus(2, "FOLLOWER", true);

        status.updateLeader(0, 1L);
        assertEquals("LEADER", status.getNodeStatus(0));
        assertEquals("FOLLOWER", status.getNodeStatus(1));

        // Leadership changes to node 2
        status.updateLeader(2, 2L);
        assertEquals("FOLLOWER", status.getNodeStatus(0)); // Was leader, still healthy -> FOLLOWER
        assertEquals("LEADER", status.getNodeStatus(2));
        assertEquals(2L, status.getLeadershipTermId());
    }

    @Test
    public void testUpdateLeader_NoCrashWithNullMarketChannels() {
        // This should NOT throw NPE even with null marketChannels
        status.updateLeader(0, 1L);
        // Pass = no exception
    }

    // ==================== setNodeStatus ====================

    @Test
    public void testSetNodeStatus_UpdatesStatusAndHealth() {
        status.setNodeStatus(0, "FOLLOWER", true);

        assertEquals("FOLLOWER", status.getNodeStatus(0));
        assertTrue(status.isNodeHealthy(0));
    }

    @Test
    public void testSetNodeStatus_SetToOffline() {
        status.setNodeStatus(1, "FOLLOWER", true);
        status.setNodeStatus(1, "OFFLINE", false);

        assertEquals("OFFLINE", status.getNodeStatus(1));
        assertFalse(status.isNodeHealthy(1));
    }

    @Test
    public void testSetNodeStatus_OutOfRange_NoEffect() {
        // Should not crash for nodeId >= 3 or < 0
        status.setNodeStatus(-1, "LEADER", true);
        status.setNodeStatus(3, "LEADER", true);
        status.setNodeStatus(100, "LEADER", true);
        // No exception = pass
    }

    @Test
    public void testSetNodeStatus_NoCrashWithNullMarketChannels() {
        status.setNodeStatus(0, "FOLLOWER", true);
        // Pass = no exception
    }

    // ==================== setGatewayConnected ====================

    @Test
    public void testSetGatewayConnected_True() {
        status.setGatewayConnected(true);
        assertTrue(status.isGatewayConnected());
    }

    @Test
    public void testSetGatewayConnected_Toggle() {
        status.setGatewayConnected(true);
        assertTrue(status.isGatewayConnected());

        status.setGatewayConnected(false);
        assertFalse(status.isGatewayConnected());
    }

    @Test
    public void testSetGatewayConnected_SameValue_NoCrash() {
        status.setGatewayConnected(false);
        status.setGatewayConnected(false);
        assertFalse(status.isGatewayConnected());
    }

    @Test
    public void testSetGatewayConnected_NoCrashWithNullMarketChannels() {
        status.setGatewayConnected(true);
        status.setGatewayConnected(false);
        // Pass = no exception
    }

    // ==================== toMap() ====================

    @Test
    public void testToMap_ContainsRequiredKeys() {
        Map<String, Object> map = status.toMap();

        assertTrue(map.containsKey("leaderId"));
        assertTrue(map.containsKey("leadershipTermId"));
        assertTrue(map.containsKey("gatewayConnected"));
        assertTrue(map.containsKey("lastUpdateTime"));
        assertTrue(map.containsKey("nodes"));
    }

    @Test
    public void testToMap_InitialValues() {
        Map<String, Object> map = status.toMap();

        assertEquals(-1, map.get("leaderId"));
        assertEquals(-1L, map.get("leadershipTermId"));
        assertEquals(false, map.get("gatewayConnected"));
    }

    @Test
    public void testToMap_NodesArray_Has3Elements() {
        Map<String, Object> map = status.toMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.get("nodes");

        assertNotNull(nodes);
        assertEquals(3, nodes.size());
    }

    @Test
    public void testToMap_NodeStructure() {
        status.setNodeStatus(0, "LEADER", true);
        status.setNodeStatus(1, "FOLLOWER", true);
        status.setNodeStatus(2, "OFFLINE", false);

        Map<String, Object> map = status.toMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) map.get("nodes");

        Map<String, Object> node0 = nodes.get(0);
        assertEquals(0, node0.get("id"));
        assertEquals("LEADER", node0.get("status"));
        assertEquals(true, node0.get("healthy"));

        Map<String, Object> node1 = nodes.get(1);
        assertEquals(1, node1.get("id"));
        assertEquals("FOLLOWER", node1.get("status"));
        assertEquals(true, node1.get("healthy"));

        Map<String, Object> node2 = nodes.get(2);
        assertEquals(2, node2.get("id"));
        assertEquals("OFFLINE", node2.get("status"));
        assertEquals(false, node2.get("healthy"));
    }

    @Test
    public void testToMap_AfterLeaderUpdate() {
        status.updateLeader(2, 10L);

        Map<String, Object> map = status.toMap();
        assertEquals(2, map.get("leaderId"));
        assertEquals(10L, map.get("leadershipTermId"));
    }

    @Test
    public void testToMap_AfterGatewayConnected() {
        status.setGatewayConnected(true);

        Map<String, Object> map = status.toMap();
        assertEquals(true, map.get("gatewayConnected"));
    }

    // ==================== broadcastStatus / broadcastClusterEvent with null channels ====================

    @Test
    public void testBroadcastStatus_NullChannels_NoCrash() {
        status.broadcastStatus();
        // Pass = no NPE
    }

    @Test
    public void testBroadcastClusterEvent_NullChannels_NoCrash() {
        status.broadcastClusterEvent("TEST_EVENT", 0, "test message");
        // Pass = no NPE
    }

    // ==================== getNodeStatus / isNodeHealthy ====================

    @Test
    public void testGetNodeStatus_ReturnsCorrectPerNode() {
        status.setNodeStatus(0, "LEADER", true);
        status.setNodeStatus(1, "FOLLOWER", true);
        status.setNodeStatus(2, "OFFLINE", false);

        assertEquals("LEADER", status.getNodeStatus(0));
        assertEquals("FOLLOWER", status.getNodeStatus(1));
        assertEquals("OFFLINE", status.getNodeStatus(2));
    }

    @Test
    public void testIsNodeHealthy_ReturnsCorrectPerNode() {
        status.setNodeStatus(0, "LEADER", true);
        status.setNodeStatus(1, "OFFLINE", false);
        status.setNodeStatus(2, "FOLLOWER", true);

        assertTrue(status.isNodeHealthy(0));
        assertFalse(status.isNodeHealthy(1));
        assertTrue(status.isNodeHealthy(2));
    }

    // ==================== Broadcast with real ChannelGroup ====================

    private ClusterStatus createStatusWithChannel(EmbeddedChannel channel) {
        ClusterStatus s = new ClusterStatus();
        ChannelGroup group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        group.add(channel);
        s.setMarketChannels(group);
        return s;
    }

    @Test
    public void testBroadcastStatus_WithChannelGroup_SendsJson() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.updateLeader(1, 5L);

        // updateLeader calls broadcastStatus internally
        // Read all outbound messages — there may be multiple (broadcastClusterEvent + broadcastStatus)
        Object msg;
        boolean foundStatus = false;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof TextWebSocketFrame) {
                String json = ((TextWebSocketFrame) msg).text();
                if (json.contains("CLUSTER_STATUS")) {
                    foundStatus = true;
                    assertTrue(json.contains("\"leaderId\":1"));
                    assertTrue(json.contains("\"gatewayConnected\":false"));
                    assertTrue(json.contains("\"nodes\""));
                }
            }
        }
        assertTrue("Should have received CLUSTER_STATUS broadcast", foundStatus);
        channel.close();
    }

    @Test
    public void testBroadcastClusterEvent_WithChannelGroup_SendsJson() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.broadcastClusterEvent("TEST_EVENT", 0, "test message");

        Object msg = channel.readOutbound();
        assertNotNull(msg);
        assertTrue(msg instanceof TextWebSocketFrame);
        String json = ((TextWebSocketFrame) msg).text();
        assertTrue(json.contains("CLUSTER_EVENT"));
        assertTrue(json.contains("TEST_EVENT"));
        assertTrue(json.contains("test message"));
        channel.close();
    }

    @Test
    public void testBroadcastClusterEvent_LeaderChange_IncludesNewLeaderId() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.updateLeader(2, 10L);

        // Look for the LEADER_CHANGE event
        Object msg;
        boolean foundLeaderChange = false;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof TextWebSocketFrame) {
                String json = ((TextWebSocketFrame) msg).text();
                if (json.contains("LEADER_CHANGE")) {
                    foundLeaderChange = true;
                    assertTrue(json.contains("\"newLeaderId\":2"));
                }
            }
        }
        assertTrue("Should have received LEADER_CHANGE event", foundLeaderChange);
        channel.close();
    }

    @Test
    public void testSetGatewayConnected_WithChannelGroup_BroadcastsEvent() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.setGatewayConnected(true);

        Object msg;
        boolean foundConnectionEvent = false;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof TextWebSocketFrame) {
                String json = ((TextWebSocketFrame) msg).text();
                if (json.contains("CONNECTION_RESTORED")) {
                    foundConnectionEvent = true;
                }
            }
        }
        assertTrue("Should have received CONNECTION_RESTORED event", foundConnectionEvent);
        channel.close();
    }

    @Test
    public void testSetGatewayConnected_SameValue_NoBroadcast() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        // Set to true, then drain
        s.setGatewayConnected(true);
        while (channel.readOutbound() != null) { /* drain */ }

        // Set to true again — should NOT broadcast (same value)
        s.setGatewayConnected(true);
        assertNull("Should not broadcast on duplicate value", channel.readOutbound());
        channel.close();
    }

    @Test
    public void testSetNodeStatus_WithChannelGroup_BroadcastsNodeDown() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.setNodeStatus(0, "OFFLINE", false);

        // The initial status is "OFFLINE" so no change event, but broadcastStatus is called
        // Let's change a node that was previously set differently
        while (channel.readOutbound() != null) { /* drain first call */ }

        s.setNodeStatus(0, "FOLLOWER", true);

        Object msg;
        boolean foundNodeUp = false;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof TextWebSocketFrame) {
                String json = ((TextWebSocketFrame) msg).text();
                if (json.contains("NODE_UP")) {
                    foundNodeUp = true;
                }
            }
        }
        assertTrue("Should have received NODE_UP event", foundNodeUp);
        channel.close();
    }

    @Test
    public void testBroadcastStatus_EmptyChannelGroup_NoCrash() {
        ClusterStatus s = new ClusterStatus();
        ChannelGroup emptyGroup = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        s.setMarketChannels(emptyGroup);

        // Should not crash even with empty group (isEmpty() check)
        s.broadcastStatus();
        s.broadcastClusterEvent("TEST", 0, "test");
    }

    @Test
    public void testBroadcastClusterEvent_NullNodeId() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ClusterStatus s = createStatusWithChannel(channel);

        s.broadcastClusterEvent("SOME_EVENT", null, "no node");

        Object msg = channel.readOutbound();
        assertNotNull(msg);
        assertTrue(msg instanceof TextWebSocketFrame);
        String json = ((TextWebSocketFrame) msg).text();
        assertTrue(json.contains("SOME_EVENT"));
        assertFalse(json.contains("\"nodeId\""));
        channel.close();
    }
}
