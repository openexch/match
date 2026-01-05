package com.match.infrastructure.admin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Service for cluster administration operations.
 * Handles node management, rolling updates, snapshots, and compaction.
 */
public class ClusterAdminService {

    private final ClusterStatus clusterStatus;
    private final OperationProgress operationProgress;

    public ClusterAdminService(ClusterStatus clusterStatus, OperationProgress operationProgress) {
        this.clusterStatus = clusterStatus;
        this.operationProgress = operationProgress;
    }

    public ClusterStatus getClusterStatus() {
        return clusterStatus;
    }

    public OperationProgress getOperationProgress() {
        return operationProgress;
    }

    // ==================== Status Operations ====================

    /**
     * Get complete cluster status including all nodes, backup, and gateways.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();

        // Check if any node is in a transitional state (during rolling update)
        boolean anyTransitioning = false;
        for (int i = 0; i < 3; i++) {
            String tracked = clusterStatus.getNodeStatus(i);
            if (tracked.equals("STOPPING") || tracked.equals("STARTING") ||
                tracked.equals("REJOINING") || tracked.equals("ELECTION")) {
                anyTransitioning = true;
                break;
            }
        }

        // Only query ClusterTool if no nodes are transitioning (to avoid stale reads during operations)
        int leaderNode = clusterStatus.getLeaderId();
        if (!anyTransitioning) {
            int detectedLeader = detectLeaderFromCluster();
            if (detectedLeader >= 0) {
                leaderNode = detectedLeader;
                // Update cached leader if changed
                if (detectedLeader != clusterStatus.getLeaderId()) {
                    clusterStatus.updateLeader(detectedLeader, clusterStatus.getLeadershipTermId() + 1);
                }
            }
            // If detection failed but we have a cached leader, keep using it
        }

        // Check each node
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", i);

            String trackedStatus = clusterStatus.getNodeStatus(i);
            boolean isTransitional = trackedStatus.equals("STOPPING") ||
                                     trackedStatus.equals("STARTING") ||
                                     trackedStatus.equals("REJOINING") ||
                                     trackedStatus.equals("ELECTION");

            if (trackedStatus.equals("STOPPING")) {
                node.put("running", false);
                node.put("role", "STOPPING");
            } else {
                try {
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + i);
                    boolean isActive = "active".equals(activeResult.trim());

                    if (isActive) {
                        String pidResult = executeCommand("systemctl", "--user", "show", "-p", "MainPID", "--value", "match-node" + i);
                        int pid = Integer.parseInt(pidResult.trim());

                        node.put("running", true);
                        node.put("pid", pid);
                        if (isTransitional) {
                            node.put("role", trackedStatus);
                        } else if (trackedStatus.equals("LEADER")) {
                            // Trust the tracked LEADER status set during rolling update
                            node.put("role", "LEADER");
                        } else {
                            node.put("role", i == leaderNode ? "LEADER" : "FOLLOWER");
                        }
                    } else {
                        throw new RuntimeException("Service not active");
                    }
                } catch (Exception e) {
                    node.put("running", false);
                    node.put("role", isTransitional ? trackedStatus : "OFFLINE");
                }
            }
            nodes.add(node);
        }
        status.put("nodes", nodes);
        status.put("leader", leaderNode);

        // Check backup node
        Map<String, Object> backup = new HashMap<>();
        try {
            String result = executeCommand("pgrep", "-f", "ClusterBackupApp");
            backup.put("running", !result.trim().isEmpty());
            if (!result.trim().isEmpty()) {
                backup.put("pid", Integer.parseInt(result.trim().split("\n")[0]));
            }
        } catch (Exception e) {
            backup.put("running", false);
        }
        status.put("backup", backup);

        // Check all 3 gateways
        Map<String, Object> gateways = new HashMap<>();

        // Market Gateway (WebSocket on port 8081)
        Map<String, Object> marketGw = new HashMap<>();
        marketGw.put("port", 8081);
        marketGw.put("running", isServiceActive("match-market-gateway"));
        gateways.put("market", marketGw);

        // Order Gateway (HTTP on port 8080)
        Map<String, Object> orderGw = new HashMap<>();
        orderGw.put("port", 8080);
        orderGw.put("running", isServiceActive("match-order-gateway"));
        gateways.put("order", orderGw);

        // Admin Gateway (HTTP on port 8082) - always running since we're it
        Map<String, Object> adminGw = new HashMap<>();
        adminGw.put("port", 8082);
        adminGw.put("running", true);
        gateways.put("admin", adminGw);

        status.put("gateways", gateways);

        // Legacy single gateway field for backward compatibility
        Map<String, Object> gateway = new HashMap<>();
        gateway.put("running", isServiceActive("match-order-gateway"));
        gateway.put("port", 8080);
        status.put("gateway", gateway);

        // Archive size
        try {
            String result = executeCommand("du", "-sb", "--apparent-size", "/tmp/aeron-cluster/");
            String[] parts = result.trim().split("\t");
            if (parts.length > 0) {
                status.put("archiveBytes", Long.parseLong(parts[0]));
            }
            result = executeCommand("du", "-s", "/tmp/aeron-cluster/");
            parts = result.trim().split("\t");
            if (parts.length > 0) {
                status.put("archiveDiskBytes", Long.parseLong(parts[0]) * 1024);
            }
        } catch (Exception e) {
            status.put("archiveBytes", 0);
            status.put("archiveDiskBytes", 0);
        }

        return status;
    }

    /**
     * Detect the cluster leader using ClusterTool.
     */
    private int detectLeaderFromCluster() {
        String jarPath = "match/target/cluster-engine-1.0.jar";
        for (int nodeId = 0; nodeId < 3; nodeId++) {
            try {
                String result = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node" + nodeId + "/cluster", "list-members");
                if (result != null && result.contains("leaderMemberId=")) {
                    return Integer.parseInt(result.split("leaderMemberId=")[1].split(",")[0].trim());
                }
            } catch (Exception ignored) {
                // Try next node
            }
        }
        return -1;
    }

    /**
     * Check if a systemd service is active.
     */
    private boolean isServiceActive(String serviceName) {
        try {
            String result = executeCommand("systemctl", "--user", "is-active", serviceName);
            return "active".equals(result.trim());
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Node Operations ====================

    public void restartNode(int nodeId) {
        if (nodeId < 0 || nodeId > 2) {
            throw new IllegalArgumentException("Invalid nodeId. Must be 0, 1, or 2");
        }

        clusterStatus.setNodeStatus(nodeId, "STOPPING", false);

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                Thread.sleep(1000);

                clusterStatus.setNodeStatus(nodeId, "STARTING", false);

                executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + nodeId);
                    if ("active".equals(activeResult.trim())) {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            }
        }).start();
    }

    public void stopNode(int nodeId) {
        if (nodeId < 0 || nodeId > 2) {
            throw new IllegalArgumentException("Invalid nodeId. Must be 0, 1, or 2");
        }

        clusterStatus.setNodeStatus(nodeId, "STOPPING", false);

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                Thread.sleep(500);
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            } catch (Exception e) {
                e.printStackTrace();
                clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
            }
        }).start();
    }

    public void startNode(int nodeId) {
        if (nodeId < 0 || nodeId > 2) {
            throw new IllegalArgumentException("Invalid nodeId. Must be 0, 1, or 2");
        }

        clusterStatus.setNodeStatus(nodeId, "STARTING", false);

        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "match-node" + nodeId);
                    if ("active".equals(activeResult.trim())) {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== Backup Operations ====================

    public void stopBackup() {
        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void startBackup() {
        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void restartBackup() {
        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "match-backup");
                Thread.sleep(1000);
                executeCommand("systemctl", "--user", "start", "match-backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== Gateway Operations ====================
    // Now manages 3 separate gateways: market, order, admin

    private static final String[] GATEWAY_SERVICES = {
        "match-market-gateway",
        "match-order-gateway"
        // Note: admin-gateway excluded since it would stop itself
    };

    public void stopGateway() {
        new Thread(() -> {
            try {
                for (String service : GATEWAY_SERVICES) {
                    executeCommand("systemctl", "--user", "stop", service);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void startGateway() {
        new Thread(() -> {
            try {
                for (String service : GATEWAY_SERVICES) {
                    executeCommand("systemctl", "--user", "start", service);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void restartGateway() {
        new Thread(() -> {
            try {
                for (String service : GATEWAY_SERVICES) {
                    executeCommand("systemctl", "--user", "restart", service);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // Individual gateway operations
    public void stopMarketGateway() {
        executeAsync("systemctl", "--user", "stop", "match-market-gateway");
    }

    public void startMarketGateway() {
        executeAsync("systemctl", "--user", "start", "match-market-gateway");
    }

    public void restartMarketGateway() {
        executeAsync("systemctl", "--user", "restart", "match-market-gateway");
    }

    public void stopOrderGateway() {
        executeAsync("systemctl", "--user", "stop", "match-order-gateway");
    }

    public void startOrderGateway() {
        executeAsync("systemctl", "--user", "start", "match-order-gateway");
    }

    public void restartOrderGateway() {
        executeAsync("systemctl", "--user", "restart", "match-order-gateway");
    }

    private void executeAsync(String... command) {
        new Thread(() -> {
            try {
                executeCommand(command);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== Complex Operations ====================

    public void rollingUpdate() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("rolling-update", 14);

                // Step 1: Build application
                operationProgress.update(1, "Building application...");
                String projectDir = System.getenv("MATCH_PROJECT_DIR");
                if (projectDir == null || projectDir.isEmpty()) {
                    projectDir = System.getProperty("user.dir");
                }
                executeCommand("bash", "-c", "cd " + projectDir + "/match && mvn clean package -DskipTests -q");

                // Step 2: Find current leader from cached cluster status
                operationProgress.update(2, "Finding cluster leader...");
                int leaderNode = clusterStatus.getLeaderId();

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                System.out.println("[ROLLING-UPDATE] Leader is node " + leaderNode);

                // Get follower nodes
                int[] followers = new int[2];
                int idx = 0;
                for (int i = 0; i < 3; i++) {
                    if (i != leaderNode) followers[idx++] = i;
                }

                // Steps 3-12: Update both followers (5 steps each)
                int step = 3;
                for (int f = 0; f < 2; f++) {
                    int nodeId = followers[f];
                    String nodeLabel = "Node " + nodeId;

                    operationProgress.update(step, "Stopping " + nodeLabel + "...");
                    clusterStatus.setNodeStatus(nodeId, "STOPPING", false);
                    executeCommand("systemctl", "--user", "stop", "match-node" + nodeId);
                    Thread.sleep(1000);
                    step++;

                    operationProgress.update(step, nodeLabel + ": Cleaning state...");
                    executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-*node" + nodeId + "* 2>/dev/null || true");
                    executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + nodeId + "/cluster/cluster-mark.dat 2>/dev/null || true");
                    executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + nodeId + "/cluster/*.lck 2>/dev/null || true");
                    Thread.sleep(500);
                    step++;

                    operationProgress.update(step, "Starting " + nodeLabel + "...");
                    clusterStatus.setNodeStatus(nodeId, "STARTING", false);
                    Thread.sleep(300);
                    executeCommand("systemctl", "--user", "start", "match-node" + nodeId);
                    Thread.sleep(2000);
                    step++;

                    operationProgress.update(step, nodeLabel + ": Waiting to rejoin cluster...");
                    clusterStatus.setNodeStatus(nodeId, "REJOINING", true);
                    // Wait for node to rejoin - Aeron Cluster handles rejoining automatically
                    Thread.sleep(5000);
                    clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                    operationProgress.update(step, nodeLabel + " rejoined as follower");
                    step++;
                    Thread.sleep(500);
                }

                // Step 11: Stop old leader
                operationProgress.update(11, "Stopping Node " + leaderNode + " (Leader)...");
                clusterStatus.setNodeStatus(leaderNode, "STOPPING", false);
                for (int nodeId : followers) {
                    clusterStatus.setNodeStatus(nodeId, "ELECTION", true);
                }
                executeCommand("systemctl", "--user", "stop", "match-node" + leaderNode);
                Thread.sleep(1000);

                // Step 12: Election - wait for new leader to emerge
                operationProgress.update(12, "Leader election in progress...");

                // Wait for election - new leader will be detected by gateway heartbeats
                Thread.sleep(3000);

                // Find new leader from followers (one of them should become leader)
                int newLeader = followers[0]; // Default to first follower
                clusterStatus.updateLeader(newLeader, clusterStatus.getLeadershipTermId() + 1);
                for (int nodeId : followers) {
                    if (nodeId == newLeader) {
                        clusterStatus.setNodeStatus(nodeId, "LEADER", true);
                    } else {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                    }
                }
                operationProgress.update(12, "New leader elected: Node " + newLeader);

                // Step 13: Clean old leader
                operationProgress.update(13, "Cleaning Node " + leaderNode + " state...");
                executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-*" + leaderNode + "*");
                executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + leaderNode + "/cluster/cluster-mark.dat");
                executeCommand("bash", "-c", "rm -f /tmp/aeron-cluster/node" + leaderNode + "/cluster/*.lck");
                Thread.sleep(500);

                // Step 14: Start old leader as follower
                operationProgress.update(14, "Starting Node " + leaderNode + " as follower...");
                clusterStatus.setNodeStatus(leaderNode, "STARTING", false);
                executeCommand("systemctl", "--user", "start", "match-node" + leaderNode);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(leaderNode, "REJOINING", true);
                // Wait for old leader to rejoin as follower
                Thread.sleep(5000);
                clusterStatus.setNodeStatus(leaderNode, "FOLLOWER", true);
                operationProgress.update(14, "Node " + leaderNode + " rejoined as follower");

                operationProgress.finish(true, "All nodes updated successfully");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    public void snapshot() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("snapshot", 5);
                String jarPath = "match/target/cluster-engine-1.0.jar";

                operationProgress.update(1, "Finding cluster leader...");
                int leaderNode = -1;
                String listResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node0/cluster", "list-members");
                if (listResult.contains("leaderMemberId=")) {
                    leaderNode = Integer.parseInt(listResult.split("leaderMemberId=")[1].split(",")[0].trim());
                }

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                operationProgress.update(2, "Requesting snapshot from Node " + leaderNode + "...");
                String snapshotResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/tmp/aeron-cluster/node" + leaderNode + "/cluster", "snapshot");

                operationProgress.update(3, "Verifying snapshot...");
                Thread.sleep(2000);

                operationProgress.update(4, "Checking snapshot status...");
                boolean success = snapshotResult.contains("SNAPSHOT") &&
                                 (snapshotResult.contains("completed") || snapshotResult.contains("applied"));

                if (success) {
                    operationProgress.update(5, "Snapshot complete!");
                    Thread.sleep(500);
                    operationProgress.finish(true, "Snapshot created successfully");
                } else {
                    operationProgress.finish(false, "Snapshot may have failed: " + snapshotResult.trim());
                }

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    public void compact() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("compact", 7);
                String jarPath = "match/target/cluster-engine-1.0.jar";

                int step = 1;
                for (int i = 0; i < 3; i++) {
                    String archiveDir = "/tmp/aeron-cluster/node" + i + "/archive";

                    operationProgress.update(step++, "Compacting Node " + i + " archive...");
                    executeCommand("bash", "-c",
                        "echo 'y' | java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED " +
                        "-cp " + jarPath + " io.aeron.archive.ArchiveTool " + archiveDir + " compact");

                    operationProgress.update(step++, "Cleaning Node " + i + " orphaned segments...");
                    executeCommand("bash", "-c",
                        "echo 'y' | java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED " +
                        "-cp " + jarPath + " io.aeron.archive.ArchiveTool " + archiveDir + " delete-orphaned-segments");
                }

                operationProgress.update(7, "Compaction complete!");
                Thread.sleep(500);
                operationProgress.finish(true, "Archives compacted successfully");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    // ==================== Log Operations ====================

    public Map<String, Object> getLogs(int nodeId, int lines) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path logPath = Path.of("/tmp/aeron-cluster/node" + nodeId + ".log");
            if (Files.exists(logPath)) {
                List<String> allLines = Files.readAllLines(logPath);
                int start = Math.max(0, allLines.size() - lines);
                response.put("logs", allLines.subList(start, allLines.size()));
                response.put("node", nodeId);
                response.put("totalLines", allLines.size());
            } else {
                response.put("logs", List.of());
                response.put("error", "Log file not found");
            }
        } catch (Exception e) {
            response.put("logs", List.of());
            response.put("error", e.getMessage());
        }
        return response;
    }

    /**
     * Get logs for a service using journalctl.
     */
    public Map<String, Object> getServiceLogs(String service, int lines) {
        Map<String, Object> response = new HashMap<>();

        // Map service names to systemd unit names
        String unitName;
        switch (service) {
            case "backup":
                unitName = "match-backup";
                break;
            case "market-gateway":
                unitName = "match-market-gateway";
                break;
            case "order-gateway":
                unitName = "match-order-gateway";
                break;
            case "admin-gateway":
                unitName = "match-admin-gateway";
                break;
            default:
                response.put("logs", List.of());
                response.put("error", "Unknown service: " + service);
                return response;
        }

        try {
            String result = executeCommand("journalctl", "--user", "-u", unitName,
                "--no-pager", "-n", String.valueOf(lines), "--output=short-iso");
            String[] logLines = result.split("\n");
            response.put("logs", Arrays.asList(logLines));
            response.put("service", service);
            response.put("totalLines", logLines.length);
        } catch (Exception e) {
            response.put("logs", List.of());
            response.put("error", e.getMessage());
        }
        return response;
    }

    // ==================== Utility ====================

    private String executeCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Command timed out");
        }

        return output.toString();
    }
}
