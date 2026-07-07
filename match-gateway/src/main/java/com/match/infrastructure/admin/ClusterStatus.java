// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.admin;

import com.google.gson.Gson;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Tracks cluster status for real-time UI updates.
 * Uses thread-safe atomic types to ensure visibility across threads.
 */
public class ClusterStatus {
    private static final Gson gson = new Gson();

    private volatile int leaderId = -1;
    private volatile long leadershipTermId = -1;
    private volatile boolean gatewayConnected = false;
    private volatile long lastUpdateTime = 0;

    // Thread-safe arrays for cross-thread visibility
    private final AtomicBoolean[] nodeHealthy = new AtomicBoolean[]{
        new AtomicBoolean(false), new AtomicBoolean(false), new AtomicBoolean(false)
    };
    private final AtomicReferenceArray<String> nodeStatus = new AtomicReferenceArray<>(
        new String[]{"OFFLINE", "OFFLINE", "OFFLINE"}
    );

    // WebSocket channel for broadcasting (set by external component)
    private volatile ChannelGroup marketChannels;
    private volatile java.util.function.Consumer<String> edgeTee;

    public void setMarketChannels(ChannelGroup channels) {
        this.marketChannels = channels;
    }

    public void setEdgeTee(java.util.function.Consumer<String> edgeTee) {
        this.edgeTee = edgeTee;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public long getLeadershipTermId() {
        return leadershipTermId;
    }

    public boolean isGatewayConnected() {
        return gatewayConnected;
    }

    public String getNodeStatus(int nodeId) {
        return nodeStatus.get(nodeId);
    }

    public boolean isNodeHealthy(int nodeId) {
        return nodeHealthy[nodeId].get();
    }

    public synchronized void updateLeader(int newLeaderId, long termId) {
        int oldLeaderId = this.leaderId;
        this.leaderId = newLeaderId;
        this.leadershipTermId = termId;
        this.lastUpdateTime = System.currentTimeMillis();

        // Update node statuses based on leader
        for (int i = 0; i < 3; i++) {
            if (i == newLeaderId) {
                nodeStatus.set(i, "LEADER");
                nodeHealthy[i].set(true);
            } else if (nodeHealthy[i].get()) {
                nodeStatus.set(i, "FOLLOWER");
            }
        }

        // Broadcast leader change event
        broadcastClusterEvent("LEADER_CHANGE", newLeaderId,
            "Leader changed from Node " + oldLeaderId + " to Node " + newLeaderId);
        broadcastStatus();
    }

    public synchronized void setNodeStatus(int nodeId, String status, boolean healthy) {
        if (nodeId >= 0 && nodeId < 3) {
            String oldStatus = nodeStatus.get(nodeId);
            nodeStatus.set(nodeId, status);
            nodeHealthy[nodeId].set(healthy);
            lastUpdateTime = System.currentTimeMillis();

            // Broadcast node status change
            if (!oldStatus.equals(status)) {
                String event = healthy ? "NODE_UP" : "NODE_DOWN";
                broadcastClusterEvent(event, nodeId, "Node " + nodeId + " is now " + status);
            }
            broadcastStatus();
        }
    }

    public synchronized void setGatewayConnected(boolean connected) {
        boolean wasConnected = this.gatewayConnected;
        this.gatewayConnected = connected;
        this.lastUpdateTime = System.currentTimeMillis();

        if (wasConnected != connected) {
            String event = connected ? "CONNECTION_RESTORED" : "CONNECTION_LOST";
            broadcastClusterEvent(event, null,
                connected ? "Gateway connected to cluster" : "Gateway lost connection to cluster");
            broadcastStatus();
        }
    }

    public synchronized void broadcastStatus() {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "CLUSTER_STATUS");
        msg.put("leaderId", leaderId);
        msg.put("leadershipTermId", leadershipTermId);
        msg.put("gatewayConnected", gatewayConnected);
        msg.put("timestamp", System.currentTimeMillis());

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", i);
            node.put("status", nodeStatus.get(i));
            node.put("healthy", nodeHealthy[i].get());
            nodes.add(node);
        }
        msg.put("nodes", nodes);

        broadcast(gson.toJson(msg));
    }

    public synchronized void broadcastClusterEvent(String event, Integer nodeId, String message) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "CLUSTER_EVENT");
        msg.put("event", event);
        if (nodeId != null) {
            msg.put("nodeId", nodeId);
        }
        if (event.equals("LEADER_CHANGE")) {
            msg.put("newLeaderId", leaderId);
        }
        msg.put("message", message);
        msg.put("timestamp", System.currentTimeMillis());

        broadcast(gson.toJson(msg));
    }

    /** Local viewers via the channel group; edge viewers via the tee. The tee
     *  fires even with zero local channels: edge viewers are still watching. */
    private void broadcast(String json) {
        if (edgeTee != null) {
            edgeTee.accept(json);
        }
        if (marketChannels != null && !marketChannels.isEmpty()) {
            marketChannels.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /**
     * Get current status as a map (for REST API responses).
     */
    public synchronized Map<String, Object> toMap() {
        Map<String, Object> status = new HashMap<>();
        status.put("leaderId", leaderId);
        status.put("leadershipTermId", leadershipTermId);
        status.put("gatewayConnected", gatewayConnected);
        status.put("lastUpdateTime", lastUpdateTime);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", i);
            node.put("status", nodeStatus.get(i));
            node.put("healthy", nodeHealthy[i].get());
            nodes.add(node);
        }
        status.put("nodes", nodes);

        return status;
    }
}
