package com.match.infrastructure.admin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for cluster administration operations.
 * Handles node management, rolling updates, snapshots, and compaction.
 */
public class ClusterAdminService {

    // User-accessible log directory (no sudo required)
    private static final String LOG_DIR = System.getProperty("user.home") + "/.local/log/cluster";

    // JAR paths for multi-module structure
    private static final String CLUSTER_JAR = "match-cluster/target/match-cluster.jar";
    private static final String GATEWAY_JAR = "match-gateway/target/match-gateway.jar";

    private final ClusterStatus clusterStatus;
    private final OperationProgress operationProgress;

    // Auto-snapshot scheduling
    private ScheduledExecutorService snapshotScheduler;
    private volatile long snapshotIntervalMinutes = 0;
    private volatile long lastSnapshotPosition = -1;

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
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "node" + i);
                    boolean isActive = "active".equals(activeResult.trim());

                    if (isActive) {
                        String pidResult = executeCommand("systemctl", "--user", "show", "-p", "MainPID", "--value", "node" + i);
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

            // Add per-node position and archive data
            long logPos = getLogPositionForNode(i);
            long snapPos = getLatestSnapshotPosition(i);
            long archiveSize = getArchiveSizeForNode(i);
            long archiveDiskSize = getArchiveDiskUsageForNode(i);

            if (logPos >= 0) node.put("logPosition", logPos);
            if (snapPos >= 0) node.put("snapshotPosition", snapPos);
            if (archiveSize >= 0) node.put("archiveBytes", archiveSize);
            if (archiveDiskSize >= 0) node.put("archiveDiskBytes", archiveDiskSize);

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
        marketGw.put("running", isServiceActive("market"));
        gateways.put("market", marketGw);

        // Order Gateway (HTTP on port 8080)
        Map<String, Object> orderGw = new HashMap<>();
        orderGw.put("port", 8080);
        orderGw.put("running", isServiceActive("order"));
        gateways.put("order", orderGw);

        // Admin Gateway (HTTP on port 8082) - always running since we're it
        Map<String, Object> adminGw = new HashMap<>();
        adminGw.put("port", 8082);
        adminGw.put("running", true);
        gateways.put("admin", adminGw);

        status.put("gateways", gateways);

        // Legacy single gateway field for backward compatibility
        Map<String, Object> gateway = new HashMap<>();
        gateway.put("running", isServiceActive("order"));
        gateway.put("port", 8080);
        status.put("gateway", gateway);

        // Note: Archive size is now per-node (included in each node object above)

        // Auto-snapshot status
        Map<String, Object> autoSnapshot = new HashMap<>();
        autoSnapshot.put("enabled", isAutoSnapshotEnabled());
        autoSnapshot.put("intervalMinutes", snapshotIntervalMinutes);
        if (lastSnapshotPosition >= 0) {
            autoSnapshot.put("lastPosition", lastSnapshotPosition);
        }
        status.put("autoSnapshot", autoSnapshot);

        return status;
    }

    /**
     * Detect the cluster leader using ClusterTool.
     */
    private int detectLeaderFromCluster() {
        String jarPath = CLUSTER_JAR;
        for (int nodeId = 0; nodeId < 3; nodeId++) {
            try {
                String result = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node" + nodeId + "/cluster", "list-members");
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
     * Get the current log position for a node using ClusterTool recording-log.
     * Returns the highest logPosition from any entry type (LOG, SNAPSHOT, TERM).
     * Returns -1 if parse error or node not available.
     */
    private long getLogPositionForNode(int nodeId) {
        try {
            String jarPath = CLUSTER_JAR;
            String clusterDir = "/dev/shm/aeron-cluster/node" + nodeId + "/cluster";

            String result = executeCommand("java",
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                clusterDir, "recording-log");

            if (result != null) {
                long maxPosition = -1;
                String[] entries = result.split("Entry\\{");
                for (String entry : entries) {
                    if (entry.contains("logPosition=")) {
                        try {
                            String posStr = entry.split("logPosition=")[1].split(",")[0];
                            long pos = Long.parseLong(posStr.trim());
                            if (pos > maxPosition) {
                                maxPosition = pos;
                            }
                        } catch (Exception ignored) {
                            // Continue parsing other entries
                        }
                    }
                }
                return maxPosition;
            }
        } catch (Exception e) {
            // Node may be offline or archive not available
        }
        return -1;
    }

    /**
     * Get archive apparent size for a specific node in bytes.
     * Returns -1 if node directory doesn't exist or error.
     */
    private long getArchiveSizeForNode(int nodeId) {
        try {
            String nodeDir = "/dev/shm/aeron-cluster/node" + nodeId;
            String result = executeCommand("du", "-sb", "--apparent-size", nodeDir);
            String[] parts = result.trim().split("\t");
            if (parts.length > 0) {
                return Long.parseLong(parts[0]);
            }
        } catch (Exception e) {
            // Node directory may not exist
        }
        return -1;
    }

    /**
     * Get archive disk usage for a specific node in bytes.
     * Returns -1 if node directory doesn't exist or error.
     */
    private long getArchiveDiskUsageForNode(int nodeId) {
        try {
            String nodeDir = "/dev/shm/aeron-cluster/node" + nodeId;
            String result = executeCommand("du", "-s", nodeDir);
            String[] parts = result.trim().split("\t");
            if (parts.length > 0) {
                return Long.parseLong(parts[0]) * 1024; // Convert from KB to bytes
            }
        } catch (Exception e) {
            // Node directory may not exist
        }
        return -1;
    }

    /**
     * Get the log position of the latest snapshot using ClusterTool recording-log.
     * Returns -1 if no snapshot found or parse error.
     */
    private long getLatestSnapshotPosition(int nodeId) {
        try {
            String jarPath = CLUSTER_JAR;
            String clusterDir = "/dev/shm/aeron-cluster/node" + nodeId + "/cluster";

            // Use recording-log command to get snapshot entries
            String result = executeCommand("java",
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                clusterDir, "recording-log");

            // Parse logPosition from snapshot entries
            // Format: Entry{..., logPosition=704, ..., type=SNAPSHOT, ...}
            if (result != null) {
                long latestPosition = -1;
                // Split by "Entry{" to get individual entries
                String[] entries = result.split("Entry\\{");
                for (String entry : entries) {
                    if (entry.contains("type=SNAPSHOT") && entry.contains("logPosition=")) {
                        try {
                            String posStr = entry.split("logPosition=")[1].split(",")[0];
                            long pos = Long.parseLong(posStr.trim());
                            if (pos > latestPosition) {
                                latestPosition = pos;
                            }
                        } catch (Exception ignored) {
                            // Continue parsing other entries
                        }
                    }
                }
                return latestPosition;
            }
        } catch (Exception e) {
            System.err.println("Failed to get snapshot position: " + e.getMessage());
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
                executeCommand("systemctl", "--user", "stop", "node" + nodeId);
                Thread.sleep(1000);

                clusterStatus.setNodeStatus(nodeId, "STARTING", false);

                executeCommand("systemctl", "--user", "start", "node" + nodeId);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "node" + nodeId);
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
                executeCommand("systemctl", "--user", "stop", "node" + nodeId);
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
                executeCommand("systemctl", "--user", "start", "node" + nodeId);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

                for (int attempt = 0; attempt < 30; attempt++) {
                    Thread.sleep(500);
                    String activeResult = executeCommand("systemctl", "--user", "is-active", "node" + nodeId);
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

    public void stopAllNodes() {
        new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    clusterStatus.setNodeStatus(i, "STOPPING", false);
                }
                for (int i = 0; i < 3; i++) {
                    executeCommand("systemctl", "--user", "stop", "node" + i);
                }
                Thread.sleep(1000);
                for (int i = 0; i < 3; i++) {
                    clusterStatus.setNodeStatus(i, "OFFLINE", false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void startAllNodes() {
        new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    clusterStatus.setNodeStatus(i, "STARTING", false);
                }
                for (int i = 0; i < 3; i++) {
                    executeCommand("systemctl", "--user", "start", "node" + i);
                }
                Thread.sleep(3000);
                for (int i = 0; i < 3; i++) {
                    clusterStatus.setNodeStatus(i, "REJOINING", true);
                }
                Thread.sleep(5000);
                int leader = detectLeaderFromCluster();
                if (leader >= 0) {
                    clusterStatus.updateLeader(leader, clusterStatus.getLeadershipTermId() + 1);
                }
                for (int i = 0; i < 3; i++) {
                    clusterStatus.setNodeStatus(i, i == leader ? "LEADER" : "FOLLOWER", true);
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
                executeCommand("systemctl", "--user", "stop", "backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void startBackup() {
        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "start", "backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void restartBackup() {
        new Thread(() -> {
            try {
                executeCommand("systemctl", "--user", "stop", "backup");
                Thread.sleep(1000);
                executeCommand("systemctl", "--user", "start", "backup");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ==================== Gateway Operations ====================
    // Now manages 3 separate gateways: market, order, admin

    private static final String[] GATEWAY_SERVICES = {
        "market",
        "order"
        // Note: admin excluded since it would stop itself
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
        executeAsync("systemctl", "--user", "stop", "market");
    }

    public void startMarketGateway() {
        executeAsync("systemctl", "--user", "start", "market");
    }

    public void restartMarketGateway() {
        executeAsync("systemctl", "--user", "restart", "market");
    }

    public void stopOrderGateway() {
        executeAsync("systemctl", "--user", "stop", "order");
    }

    public void startOrderGateway() {
        executeAsync("systemctl", "--user", "start", "order");
    }

    public void restartOrderGateway() {
        executeAsync("systemctl", "--user", "restart", "order");
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

    // ==================== Build Operations ====================

    /**
     * Rebuild the gateway JAR and optionally restart gateways.
     * Safe to run while cluster is running (gateway JAR is separate from cluster JAR).
     */
    public void rebuildGateway(boolean restart) {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("rebuild-gateway", restart ? 3 : 2);
                String projectDir = System.getenv("MATCH_PROJECT_DIR");
                if (projectDir == null || projectDir.isEmpty()) {
                    projectDir = System.getProperty("user.dir");
                }

                // Step 1: Build gateway module (includes match-common dependency)
                operationProgress.update(1, "Building gateway module...");
                executeCommand("bash", "-c",
                    "cd " + projectDir + " && mvn package -pl match-gateway -am -DskipTests -q");

                // Verify JAR was built
                if (!Files.exists(Path.of(projectDir + "/" + GATEWAY_JAR))) {
                    operationProgress.finish(false, "Build failed: gateway JAR not found");
                    return;
                }
                operationProgress.update(2, "Gateway JAR built successfully");

                // Step 3: Restart gateways if requested
                if (restart) {
                    operationProgress.update(3, "Restarting gateways...");
                    for (String service : GATEWAY_SERVICES) {
                        executeCommand("systemctl", "--user", "restart", service);
                    }
                    Thread.sleep(2000);
                    operationProgress.finish(true, "Gateway rebuilt and restarted");
                } else {
                    operationProgress.finish(true, "Gateway JAR rebuilt. Use restart-gateway to apply.");
                }

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Build error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Rebuild the cluster JAR (for use with rolling-update).
     * Does NOT restart nodes - use rolling-update for safe deployment.
     */
    public void rebuildCluster() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("rebuild-cluster", 2);
                String projectDir = System.getenv("MATCH_PROJECT_DIR");
                if (projectDir == null || projectDir.isEmpty()) {
                    projectDir = System.getProperty("user.dir");
                }

                // Step 1: Build cluster module (includes match-common dependency)
                operationProgress.update(1, "Building cluster module...");
                executeCommand("bash", "-c",
                    "cd " + projectDir + " && mvn package -pl match-cluster -am -DskipTests -q");

                // Verify JAR was built
                if (!Files.exists(Path.of(projectDir + "/" + CLUSTER_JAR))) {
                    operationProgress.finish(false, "Build failed: cluster JAR not found");
                    return;
                }

                operationProgress.finish(true, "Cluster JAR rebuilt. Use rolling-update to deploy safely.");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Build error: " + e.getMessage());
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
                operationProgress.start("rolling-update", 11);

                String projectDir = System.getenv("MATCH_PROJECT_DIR");
                if (projectDir == null || projectDir.isEmpty()) {
                    projectDir = System.getProperty("user.dir");
                }
                String jarPath = projectDir + "/" + CLUSTER_JAR;
                String stagingDir = projectDir + "/match-cluster/target/staging";
                String stagingJarPath = stagingDir + "/match-cluster.jar";

                // Step 1: Build cluster module in staging
                // Multi-module structure: gateway JAR is separate from cluster JAR.
                // We only need to rebuild match-cluster for rolling node updates.
                // Building match-cluster also builds match-common (dependency).
                operationProgress.update(1, "Building cluster module...");

                String buildScript = String.join(" && ",
                    "mkdir -p " + stagingDir,
                    "cd " + projectDir,
                    // Build match-common + match-cluster only
                    "mvn package -pl match-cluster -am -DskipTests -q",
                    // Copy to staging (live JAR untouched during build!)
                    "cp " + projectDir + "/" + CLUSTER_JAR + " " + stagingJarPath
                );
                executeCommand("bash", "-c", buildScript);

                // Verify staging JAR exists
                if (!Files.exists(Path.of(stagingJarPath))) {
                    operationProgress.finish(false, "Build failed: staging JAR not found");
                    return;
                }
                
                operationProgress.update(1, "Build complete, staged for deployment");

                // Step 2: Find current leader from cached cluster status
                operationProgress.update(2, "Finding cluster leader...");
                int leaderNode = clusterStatus.getLeaderId();

                if (leaderNode < 0) {
                    // Try to detect from cluster
                    leaderNode = detectLeaderFromCluster();
                }

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                // Get follower nodes
                int[] followers = new int[2];
                int idx = 0;
                for (int i = 0; i < 3; i++) {
                    if (i != leaderNode) followers[idx++] = i;
                }

                // Track if JAR has been swapped
                boolean jarSwapped = false;

                // Steps 3-8: Update both followers
                int step = 3;
                for (int f = 0; f < 2; f++) {
                    int nodeId = followers[f];
                    String nodeLabel = "Node " + nodeId;

                    // Stop follower
                    operationProgress.update(step, "Stopping " + nodeLabel + "...");
                    clusterStatus.setNodeStatus(nodeId, "STOPPING", false);
                    executeCommand("systemctl", "--user", "stop", "node" + nodeId);
                    Thread.sleep(1000);
                    clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);
                    step++;

                    // Swap JAR after FIRST node is stopped (minimizes risk window)
                    // At this point: 1 node stopped, 2 nodes running with old JAR in memory
                    // The running nodes should not lazy-load during this brief window
                    if (!jarSwapped) {
                        operationProgress.update(step, "Deploying new JAR...");
                        executeCommand("mv", stagingJarPath, jarPath);
                        jarSwapped = true;
                        Thread.sleep(100);
                    }

                    // Start follower (will load NEW JAR)
                    operationProgress.update(step, "Starting " + nodeLabel + " with new code...");
                    clusterStatus.setNodeStatus(nodeId, "STARTING", false);
                    executeCommand("systemctl", "--user", "start", "node" + nodeId);
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

                // Step 9: Stop old leader
                operationProgress.update(9, "Stopping Node " + leaderNode + " (Leader)...");
                clusterStatus.setNodeStatus(leaderNode, "STOPPING", false);
                for (int nodeId : followers) {
                    clusterStatus.setNodeStatus(nodeId, "ELECTION", true);
                }
                executeCommand("systemctl", "--user", "stop", "node" + leaderNode);
                Thread.sleep(1000);
                clusterStatus.setNodeStatus(leaderNode, "OFFLINE", false);

                // Wait for election - new leader will be detected by gateway heartbeats
                operationProgress.update(10, "Leader election in progress...");
                Thread.sleep(3000);

                // Detect new leader
                int newLeader = detectLeaderFromCluster();
                if (newLeader < 0) {
                    newLeader = followers[0]; // Default to first follower
                }
                clusterStatus.updateLeader(newLeader, clusterStatus.getLeadershipTermId() + 1);
                for (int nodeId : followers) {
                    if (nodeId == newLeader) {
                        clusterStatus.setNodeStatus(nodeId, "LEADER", true);
                    } else {
                        clusterStatus.setNodeStatus(nodeId, "FOLLOWER", true);
                    }
                }
                operationProgress.update(10, "New leader elected: Node " + newLeader);

                // Step 11: Start old leader as follower (will load NEW JAR)
                operationProgress.update(11, "Starting Node " + leaderNode + " as follower...");
                clusterStatus.setNodeStatus(leaderNode, "STARTING", false);
                executeCommand("systemctl", "--user", "start", "node" + leaderNode);
                Thread.sleep(2000);

                clusterStatus.setNodeStatus(leaderNode, "REJOINING", true);
                // Wait for old leader to rejoin as follower
                Thread.sleep(5000);
                clusterStatus.setNodeStatus(leaderNode, "FOLLOWER", true);
                operationProgress.update(11, "Node " + leaderNode + " rejoined as follower");

                // Cleanup staging directory
                executeCommand("rm", "-rf", stagingDir);

                operationProgress.finish(true, "All nodes updated successfully with new code");

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Take a cluster snapshot for state persistence.
     * Note: Archive cleanup is done separately via compactArchive() which requires restart.
     */
    public void snapshot() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("snapshot", 4);
                String jarPath = CLUSTER_JAR;

                // Step 1: Find cluster leader
                operationProgress.update(1, "Finding cluster leader...");
                int leaderNode = -1;
                String listResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node0/cluster", "list-members");
                if (listResult != null && listResult.contains("leaderMemberId=")) {
                    leaderNode = Integer.parseInt(listResult.split("leaderMemberId=")[1].split(",")[0].trim());
                }

                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                // Step 2: Take snapshot on leader
                operationProgress.update(2, "Taking snapshot on Node " + leaderNode + "...");
                String snapshotResult = executeCommand("java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node" + leaderNode + "/cluster", "snapshot");

                // Step 3: Wait for snapshot propagation to all nodes
                operationProgress.update(3, "Waiting for snapshot propagation...");
                Thread.sleep(2000);

                // Step 4: Verify snapshot and get log position
                operationProgress.update(4, "Verifying snapshot position...");
                long snapshotPosition = getLatestSnapshotPosition(leaderNode);
                lastSnapshotPosition = snapshotPosition;

                boolean success = snapshotResult != null && snapshotResult.contains("SNAPSHOT") &&
                                 (snapshotResult.contains("completed") || snapshotResult.contains("applied"));

                if (!success) {
                    operationProgress.finish(false, "Snapshot may have failed: " +
                        (snapshotResult != null ? snapshotResult.trim() : "null result"));
                    return;
                }

                String positionInfo = snapshotPosition >= 0 ? " at position " + snapshotPosition : "";
                operationProgress.finish(true, "Snapshot created" + positionInfo);

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
                String jarPath = CLUSTER_JAR;

                int step = 1;
                for (int i = 0; i < 3; i++) {
                    String archiveDir = "/dev/shm/aeron-cluster/node" + i + "/archive";

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

    /**
     * Full archive compaction - requires brief cluster downtime.
     * 
     * Proper workflow:
     * 1. Take final snapshot
     * 2. Stop all nodes
     * 3. Use seed-recording-log-from-snapshot to reset recording-log
     * 4. Mark unreferenced recordings as INVALID in archive catalog
     * 5. Run ArchiveTool compact to delete INVALID recordings
     * 6. Restart nodes
     */
    public void compactArchive() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("compact-archive", 12);
                String jarPath = CLUSTER_JAR;

                // Step 1: Take final snapshot to ensure we have latest state
                operationProgress.update(1, "Taking final snapshot...");
                int leaderNode = detectLeaderFromCluster();
                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                String snapshotResult = executeCommand("java",
                    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node" + leaderNode + "/cluster", "snapshot");

                if (snapshotResult == null || !snapshotResult.contains("SNAPSHOT")) {
                    operationProgress.finish(false, "Failed to take snapshot before compaction");
                    return;
                }

                // Wait for snapshot to propagate
                Thread.sleep(3000);

                // Step 2-4: Stop all 3 nodes
                for (int i = 0; i < 3; i++) {
                    operationProgress.update(2 + i, "Stopping node " + i + "...");
                    clusterStatus.setNodeStatus(i, "STOPPING", false);
                    executeCommand("systemctl", "--user", "stop", "node" + i);
                    Thread.sleep(500);
                    clusterStatus.setNodeStatus(i, "OFFLINE", false);
                }

                // Wait for all nodes to fully stop
                Thread.sleep(2000);

                // Step 5: Seed fresh recording log from snapshot on each node
                operationProgress.update(5, "Seeding recording-log from snapshot...");
                for (int i = 0; i < 3; i++) {
                    String clusterDir = "/dev/shm/aeron-cluster/node" + i + "/cluster";
                    try {
                        executeCommand("java",
                            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                            "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                            clusterDir, "seed-recording-log-from-snapshot");
                        System.out.println("Node " + i + " recording-log seeded from snapshot");
                    } catch (Exception e) {
                        System.err.println("Warning: seed failed for node " + i + ": " + e.getMessage());
                    }
                }

                // Step 6: Mark unreferenced recordings as INVALID and compact
                operationProgress.update(6, "Compacting archives...");
                for (int i = 0; i < 3; i++) {
                    try {
                        compactNodeArchive(i, jarPath);
                    } catch (Exception e) {
                        System.err.println("Warning: compact failed for node " + i + ": " + e.getMessage());
                    }
                }

                // Step 7-9: Start all 3 nodes
                for (int i = 0; i < 3; i++) {
                    operationProgress.update(7 + i, "Starting node " + i + "...");
                    clusterStatus.setNodeStatus(i, "STARTING", false);
                    executeCommand("systemctl", "--user", "start", "node" + i);
                    Thread.sleep(2000);
                    clusterStatus.setNodeStatus(i, "REJOINING", true);
                }

                // Step 10: Wait for cluster to form and elect leader
                operationProgress.update(10, "Waiting for cluster election...");
                Thread.sleep(5000);

                int newLeader = detectLeaderFromCluster();
                if (newLeader >= 0) {
                    clusterStatus.updateLeader(newLeader, clusterStatus.getLeadershipTermId() + 1);
                    for (int i = 0; i < 3; i++) {
                        clusterStatus.setNodeStatus(i, i == newLeader ? "LEADER" : "FOLLOWER", true);
                    }
                    operationProgress.finish(true, "Archive compacted successfully. Leader: Node " + newLeader);
                } else {
                    // Nodes are running but couldn't detect leader yet
                    for (int i = 0; i < 3; i++) {
                        clusterStatus.setNodeStatus(i, "FOLLOWER", true);
                    }
                    operationProgress.finish(true, "Archive compacted. Cluster restarted (leader detection pending)");
                }

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Compact a single node's archive by marking unreferenced recordings as INVALID
     * and then running ArchiveTool compact.
     * 
     * After seed-recording-log-from-snapshot, the recording-log only references
     * snapshot recordings. We need to mark all OTHER recordings in the catalog
     * as INVALID before compacting.
     */
    private void compactNodeArchive(int nodeId, String jarPath) throws Exception {
        String clusterDir = "/dev/shm/aeron-cluster/node" + nodeId + "/cluster";
        String archiveDir = "/dev/shm/aeron-cluster/node" + nodeId + "/archive";

        // Get recording IDs referenced in the recording-log
        Set<Long> referencedIds = getRecordingLogRecordingIds(clusterDir, jarPath);
        System.out.println("Node " + nodeId + " recording-log references: " + referencedIds);

        // Get all recording IDs from the archive catalog
        Set<Long> catalogIds = getArchiveCatalogRecordingIds(archiveDir, jarPath);
        System.out.println("Node " + nodeId + " archive catalog contains: " + catalogIds);

        // Find unreferenced recordings (in catalog but not in recording-log)
        Set<Long> unreferencedIds = new HashSet<>(catalogIds);
        unreferencedIds.removeAll(referencedIds);
        System.out.println("Node " + nodeId + " unreferenced recordings to invalidate: " + unreferencedIds);

        // Mark unreferenced recordings as INVALID
        for (Long recordingId : unreferencedIds) {
            try {
                executeCommand("java",
                    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.archive.ArchiveTool",
                    archiveDir, "mark-invalid", String.valueOf(recordingId));
                System.out.println("Node " + nodeId + " marked recording " + recordingId + " as INVALID");
            } catch (Exception e) {
                System.err.println("Warning: failed to mark recording " + recordingId + " invalid: " + e.getMessage());
            }
        }

        // Run compact to delete INVALID recordings and their segment files
        if (!unreferencedIds.isEmpty()) {
            executeCommand("bash", "-c",
                "echo 'y' | java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED " +
                "-cp " + jarPath + " io.aeron.archive.ArchiveTool " + archiveDir + " compact");
            System.out.println("Node " + nodeId + " archive compacted");
        }
    }

    /**
     * Parse the cluster recording-log and extract all referenced recording IDs.
     */
    private Set<Long> getRecordingLogRecordingIds(String clusterDir, String jarPath) {
        Set<Long> ids = new HashSet<>();
        try {
            String result = executeCommand("java",
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                clusterDir, "recording-log");

            if (result != null) {
                // Parse recordingId=N from the output
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("recordingId=(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(result);
                while (matcher.find()) {
                    ids.add(Long.parseLong(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse recording-log: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Parse the archive catalog and extract all recording IDs.
     */
    private Set<Long> getArchiveCatalogRecordingIds(String archiveDir, String jarPath) {
        Set<Long> ids = new HashSet<>();
        try {
            String result = executeCommand("java",
                "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", jarPath, "io.aeron.archive.ArchiveTool",
                archiveDir, "describe");

            if (result != null) {
                // Parse recordingId=N from the output
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("recordingId=(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(result);
                while (matcher.find()) {
                    ids.add(Long.parseLong(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to parse archive catalog: " + e.getMessage());
        }
        return ids;
    }

    // ==================== Rolling Archive Cleanup (Zero Downtime) ====================

    private static final long ARCHIVE_SIZE_THRESHOLD_BYTES = 300 * 1024 * 1024; // 300MB

    /**
     * Rolling archive cleanup - ZERO cluster downtime.
     * Cleans archive on each node one at a time while maintaining quorum.
     *
     * Process:
     * 1. Take snapshot on leader
     * 2. For each follower: stop → clean → restart → wait for rejoin
     * 3. Finally clean leader (causes leader election)
     */
    public void rollingArchiveCleanup() {
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            throw new IllegalStateException("Another operation in progress");
        }

        new Thread(() -> {
            try {
                operationProgress.start("rolling-cleanup", 7);
                String jarPath = CLUSTER_JAR;

                // Step 1: Take snapshot to ensure all nodes have latest state
                operationProgress.update(1, "Taking snapshot...");
                int leaderNode = detectLeaderFromCluster();
                if (leaderNode < 0) {
                    operationProgress.finish(false, "Could not find cluster leader");
                    return;
                }

                String snapshotResult = executeCommand("java",
                    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node" + leaderNode + "/cluster", "snapshot");

                if (snapshotResult == null || !snapshotResult.contains("SNAPSHOT")) {
                    operationProgress.finish(false, "Failed to take snapshot");
                    return;
                }

                Thread.sleep(5000); // Wait for snapshot propagation

                // Verify snapshot was properly linked to recording-log
                // Nodes being cleaned will rebuild from this snapshot via catchup
                String recordingLog = executeCommand("java",
                    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                    "/dev/shm/aeron-cluster/node" + leaderNode + "/cluster", "recording-log");

                System.out.println("Recording-log after snapshot:\n" + recordingLog);

                if (recordingLog == null || !recordingLog.contains("type=SNAPSHOT")) {
                    operationProgress.finish(false,
                        "Snapshot not found in recording-log. " +
                        "Cluster needs valid snapshot for nodes to rebuild. " +
                        "Try taking a snapshot first or use compactArchive().");
                    return;
                }

                System.out.println("Snapshot verified, nodes will rebuild from it via catchup");

                // Step 2-5: Clean followers first (maintains quorum)
                int step = 2;
                for (int nodeId = 0; nodeId < 3; nodeId++) {
                    if (nodeId == leaderNode) continue; // Skip leader for now

                    operationProgress.update(step++, "Cleaning Node " + nodeId + " archive...");
                    if (!cleanSingleNodeArchive(nodeId, jarPath)) {
                        operationProgress.finish(false, "Failed to clean Node " + nodeId);
                        return;
                    }

                    operationProgress.update(step++, "Waiting for Node " + nodeId + " rejoin...");
                    waitForNodeRejoin(nodeId, 30000); // 30 second timeout
                }

                // Step 6: Clean leader last (causes automatic leader election)
                operationProgress.update(6, "Cleaning leader Node " + leaderNode + "...");
                if (!cleanSingleNodeArchive(leaderNode, jarPath)) {
                    operationProgress.finish(false, "Failed to clean leader node");
                    return;
                }

                // Step 7: Wait for new leader election
                operationProgress.update(7, "Waiting for leader election...");
                Thread.sleep(5000);

                int newLeader = detectLeaderFromCluster();
                if (newLeader >= 0) {
                    clusterStatus.updateLeader(newLeader, clusterStatus.getLeadershipTermId() + 1);
                    for (int i = 0; i < 3; i++) {
                        clusterStatus.setNodeStatus(i, i == newLeader ? "LEADER" : "FOLLOWER", true);
                    }
                    operationProgress.finish(true,
                        "Rolling cleanup complete. New leader: Node " + newLeader);
                } else {
                    for (int i = 0; i < 3; i++) {
                        clusterStatus.setNodeStatus(i, "FOLLOWER", true);
                    }
                    operationProgress.finish(true,
                        "Rolling cleanup complete (leader detection pending)");
                }

            } catch (Exception e) {
                e.printStackTrace();
                operationProgress.finish(false, "Error: " + e.getMessage());
            }
        }, "rolling-cleanup").start();
    }

    /**
     * Clean a single node's archive by deleting ALL data and letting it rebuild.
     * The node will sync from other cluster members via snapshot + catchup.
     * This is the only safe way to reduce archive size on a live cluster.
     */
    private boolean cleanSingleNodeArchive(int nodeId, String jarPath) {
        try {
            String nodeDir = "/dev/shm/aeron-cluster/node" + nodeId;

            // Stop node
            clusterStatus.setNodeStatus(nodeId, "STOPPING", false);
            executeCommand("systemctl", "--user", "stop", "node" + nodeId);
            Thread.sleep(2000);
            clusterStatus.setNodeStatus(nodeId, "OFFLINE", false);

            // Get archive size before cleanup for logging
            String sizeBefore = executeCommand("du", "-sh", nodeDir);
            System.out.println("Node " + nodeId + " size before cleanup: " +
                (sizeBefore != null ? sizeBefore.split("\\t")[0] : "unknown"));

            // Delete ALL data - node will rebuild from scratch via catchup
            // This is safe because other nodes have the complete log and snapshots
            executeCommand("rm", "-rf", nodeDir);
            executeCommand("mkdir", "-p", nodeDir);

            System.out.println("Node " + nodeId + " data wiped, will rebuild from cluster");

            // Restart node - it will sync from leader via snapshot catchup
            clusterStatus.setNodeStatus(nodeId, "STARTING", false);
            executeCommand("systemctl", "--user", "start", "node" + nodeId);
            Thread.sleep(3000);
            clusterStatus.setNodeStatus(nodeId, "REJOINING", true);

            return true;
        } catch (Exception e) {
            System.err.println("Failed to clean node " + nodeId + ": " + e.getMessage());
            return false;
        }
    }

    private void waitForNodeRejoin(int nodeId, long timeoutMs) throws InterruptedException {
        String jarPath = CLUSTER_JAR;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                // Poll actual cluster state via ClusterTool list-members
                // Check any running node's cluster directory
                String members = null;
                for (int i = 0; i < 3; i++) {
                    if (i == nodeId) continue; // Don't check the node we're waiting for
                    try {
                        members = executeCommand("java",
                            "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                            "-cp", jarPath, "io.aeron.cluster.ClusterTool",
                            "/dev/shm/aeron-cluster/node" + i + "/cluster", "list-members");
                        if (members != null && !members.isEmpty()) break;
                    } catch (Exception e) {
                        // This node may be down, try next
                    }
                }

                if (members != null && members.contains("memberId=" + nodeId)) {
                    System.out.println("Node " + nodeId + " confirmed in cluster membership");
                    // Update cluster status from actual state
                    int leader = detectLeaderFromCluster();
                    if (leader >= 0) {
                        for (int i = 0; i < 3; i++) {
                            clusterStatus.setNodeStatus(i, i == leader ? "LEADER" : "FOLLOWER", true);
                        }
                    }
                    return;
                }
            } catch (Exception e) {
                // Cluster may be in transition, continue waiting
                System.out.println("Node " + nodeId + " rejoin check error: " + e.getMessage());
            }
            Thread.sleep(1000);
        }
        // Timeout - node may still be catching up, continue anyway
        System.out.println("Node " + nodeId + " rejoin timeout after " + timeoutMs + "ms, continuing...");
    }

    /**
     * Check if any node's archive exceeds threshold and auto-trigger cleanup.
     * Called periodically from status check.
     */
    public void checkAutoCleanupThreshold() {
        // Skip if operation in progress
        if (operationProgress.getOperation() != null && !operationProgress.isComplete()) {
            return;
        }

        for (int nodeId = 0; nodeId < 3; nodeId++) {
            String archiveDir = "/dev/shm/aeron-cluster/node" + nodeId + "/archive";
            try {
                String sizeOutput = executeCommand("du", "-sb", archiveDir);
                if (sizeOutput != null) {
                    long sizeBytes = Long.parseLong(sizeOutput.split("\\s+")[0]);
                    if (sizeBytes > ARCHIVE_SIZE_THRESHOLD_BYTES) {
                        System.out.println("Auto-cleanup triggered: Node " + nodeId +
                            " archive at " + (sizeBytes / 1024 / 1024) + "MB (threshold: 300MB)");
                        rollingArchiveCleanup();
                        return; // Only trigger once
                    }
                }
            } catch (Exception e) {
                // Ignore errors in size check
            }
        }
    }

    // ==================== Auto-Snapshot Operations ====================

    /**
     * Start automatic periodic snapshots with segment purge.
     * @param intervalMinutes interval between snapshots in minutes
     */
    public void startAutoSnapshot(long intervalMinutes) {
        stopAutoSnapshot();  // Stop existing scheduler if any
        snapshotIntervalMinutes = intervalMinutes;
        snapshotScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-snapshot");
            t.setDaemon(true);
            return t;
        });
        snapshotScheduler.scheduleAtFixedRate(() -> {
            try {
                // Only run if no other operation is in progress
                if (operationProgress.getOperation() == null || operationProgress.isComplete()) {
                    snapshot();
                } else {
                    System.out.println("Auto-snapshot skipped: another operation in progress");
                }
            } catch (Exception e) {
                System.err.println("Auto-snapshot failed: " + e.getMessage());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        System.out.println("Auto-snapshot enabled: every " + intervalMinutes + " minutes");
    }

    /**
     * Stop automatic periodic snapshots.
     */
    public void stopAutoSnapshot() {
        if (snapshotScheduler != null) {
            snapshotScheduler.shutdown();
            try {
                if (!snapshotScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    snapshotScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                snapshotScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            snapshotScheduler = null;
        }
        snapshotIntervalMinutes = 0;
        System.out.println("Auto-snapshot disabled");
    }

    /**
     * Check if auto-snapshot is enabled.
     */
    public boolean isAutoSnapshotEnabled() {
        return snapshotIntervalMinutes > 0 && snapshotScheduler != null;
    }

    /**
     * Get the auto-snapshot interval in minutes.
     */
    public long getSnapshotIntervalMinutes() {
        return snapshotIntervalMinutes;
    }

    /**
     * Get the last known snapshot position.
     */
    public long getLastSnapshotPosition() {
        return lastSnapshotPosition;
    }

    // ==================== Log Operations ====================

    public Map<String, Object> getLogs(int nodeId, int lines) {
        Map<String, Object> response = new HashMap<>();
        try {
            Path logPath = Path.of(LOG_DIR + "/node" + nodeId + ".log");
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
     * Get logs for a service from log files.
     * Reads from ~/.local/log/cluster/{service}.log
     */
    public Map<String, Object> getServiceLogs(String service, int lines) {
        Map<String, Object> response = new HashMap<>();

        // Map service names to log file names
        String logFileName;
        switch (service) {
            case "backup":
                logFileName = "backup";
                break;
            case "market-gateway":
            case "market":
                logFileName = "market";
                break;
            case "order-gateway":
            case "order":
                logFileName = "order";
                break;
            case "admin-gateway":
            case "admin":
                logFileName = "admin";
                break;
            default:
                response.put("logs", List.of());
                response.put("error", "Unknown service: " + service);
                return response;
        }

        try {
            Path logPath = Path.of(LOG_DIR + "/" + logFileName + ".log");
            if (Files.exists(logPath)) {
                List<String> allLines = Files.readAllLines(logPath);
                int start = Math.max(0, allLines.size() - lines);
                response.put("logs", allLines.subList(start, allLines.size()));
                response.put("service", service);
                response.put("totalLines", allLines.size());
            } else {
                response.put("logs", List.of());
                response.put("error", "Log file not found: " + logPath);
            }
        } catch (Exception e) {
            response.put("logs", List.of());
            response.put("error", e.getMessage());
        }
        return response;
    }

    // ==================== Cleanup Operations ====================

    /**
     * Clean up stale Aeron files (shared memory, mark files, lock files).
     * Use this after a crash or when nodes fail to start due to "active Mark file detected".
     * IMPORTANT: All cluster nodes must be stopped before calling this.
     */
    public Map<String, Object> cleanup() {
        Map<String, Object> response = new HashMap<>();
        List<String> cleaned = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Check if any nodes are running
        for (int i = 0; i < 3; i++) {
            try {
                String activeResult = executeCommand("systemctl", "--user", "is-active", "node" + i);
                if ("active".equals(activeResult.trim())) {
                    response.put("success", false);
                    response.put("error", "Node " + i + " is still running. Stop all nodes before cleanup.");
                    return response;
                }
            } catch (Exception ignored) {
                // Service not active or doesn't exist - ok to proceed
            }
        }

        // Clean shared memory aeron files
        try {
            executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-* 2>/dev/null || true");
            cleaned.add("/dev/shm/aeron-*");
        } catch (Exception e) {
            errors.add("Failed to clean /dev/shm: " + e.getMessage());
        }

        // Clean cluster mark files and lock files for each node
        for (int i = 0; i < 3; i++) {
            try {
                executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-cluster/node" + i + "/cluster/cluster-mark*.dat 2>/dev/null || true");
                executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-cluster/node" + i + "/cluster/*.lck 2>/dev/null || true");
                executeCommand("bash", "-c", "rm -rf /dev/shm/aeron-cluster/node" + i + "/archive/archive-mark.dat 2>/dev/null || true");
                cleaned.add("/dev/shm/aeron-cluster/node" + i + " (mark files, locks)");
            } catch (Exception e) {
                errors.add("Failed to clean node" + i + ": " + e.getMessage());
            }
        }

        // Clean gateway aeron files
        try {
            executeCommand("bash", "-c", "rm -rf /tmp/aeron-* 2>/dev/null || true");
            cleaned.add("/tmp/aeron-* (gateway files)");
        } catch (Exception e) {
            errors.add("Failed to clean /tmp/aeron-*: " + e.getMessage());
        }

        response.put("success", errors.isEmpty());
        response.put("cleaned", cleaned);
        if (!errors.isEmpty()) {
            response.put("errors", errors);
        }
        response.put("message", errors.isEmpty()
            ? "Cleanup completed successfully. You can now start the cluster."
            : "Cleanup completed with some errors.");

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
