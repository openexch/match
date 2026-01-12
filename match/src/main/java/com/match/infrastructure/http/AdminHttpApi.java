package com.match.infrastructure.http;

import com.google.gson.Gson;
import com.match.infrastructure.admin.ClusterAdminService;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP handlers for admin API endpoints.
 * Delegates to ClusterAdminService for actual operations.
 */
public class AdminHttpApi {

    private static final Gson gson = new Gson();
    private final ClusterAdminService adminService;

    public AdminHttpApi(ClusterAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Register all admin endpoints on the given HTTP server.
     */
    public void registerEndpoints(HttpServer server) {
        server.createContext("/api/admin/status", this::handleStatus);
        server.createContext("/api/admin/restart-node", this::handleRestartNode);
        server.createContext("/api/admin/stop-node", this::handleStopNode);
        server.createContext("/api/admin/start-node", this::handleStartNode);
        server.createContext("/api/admin/stop-all-nodes", this::handleStopAllNodes);
        server.createContext("/api/admin/start-all-nodes", this::handleStartAllNodes);
        server.createContext("/api/admin/stop-backup", this::handleStopBackup);
        server.createContext("/api/admin/start-backup", this::handleStartBackup);
        server.createContext("/api/admin/restart-backup", this::handleRestartBackup);
        server.createContext("/api/admin/stop-gateway", this::handleStopGateway);
        server.createContext("/api/admin/start-gateway", this::handleStartGateway);
        server.createContext("/api/admin/restart-gateway", this::handleRestartGateway);
        // Individual gateway controls
        server.createContext("/api/admin/stop-market-gateway", this::handleStopMarketGateway);
        server.createContext("/api/admin/start-market-gateway", this::handleStartMarketGateway);
        server.createContext("/api/admin/restart-market-gateway", this::handleRestartMarketGateway);
        server.createContext("/api/admin/stop-order-gateway", this::handleStopOrderGateway);
        server.createContext("/api/admin/start-order-gateway", this::handleStartOrderGateway);
        server.createContext("/api/admin/restart-order-gateway", this::handleRestartOrderGateway);
        server.createContext("/api/admin/rolling-update", this::handleRollingUpdate);
        server.createContext("/api/admin/snapshot", this::handleSnapshot);
        server.createContext("/api/admin/compact", this::handleCompact);
        server.createContext("/api/admin/auto-snapshot", this::handleAutoSnapshot);
        server.createContext("/api/admin/progress", this::handleProgress);
        server.createContext("/api/admin/logs", this::handleLogs);
        server.createContext("/api/admin/cleanup", this::handleCleanup);
    }

    // ==================== Status ====================

    private void handleStatus(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        sendJsonResponse(exchange, 200, adminService.getStatus());
    }

    // ==================== Node Operations ====================

    private void handleRestartNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            int nodeId = getNodeIdFromBody(exchange);
            adminService.restartNode(nodeId);
            sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " restart initiated"));
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private void handleStopNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            int nodeId = getNodeIdFromBody(exchange);
            adminService.stopNode(nodeId);
            sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " stop initiated"));
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private void handleStartNode(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            int nodeId = getNodeIdFromBody(exchange);
            adminService.startNode(nodeId);
            sendJsonResponse(exchange, 202, Map.of("message", "Node " + nodeId + " start initiated"));
        } catch (IllegalArgumentException e) {
            sendJsonResponse(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    private void handleStopAllNodes(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.stopAllNodes();
        sendJsonResponse(exchange, 202, Map.of("message", "All nodes stop initiated"));
    }

    private void handleStartAllNodes(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.startAllNodes();
        sendJsonResponse(exchange, 202, Map.of("message", "All nodes start initiated"));
    }

    // ==================== Backup Operations ====================

    private void handleStopBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.stopBackup();
        sendJsonResponse(exchange, 202, Map.of("message", "Backup node stop initiated"));
    }

    private void handleStartBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.startBackup();
        sendJsonResponse(exchange, 202, Map.of("message", "Backup node start initiated"));
    }

    private void handleRestartBackup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.restartBackup();
        sendJsonResponse(exchange, 202, Map.of("message", "Backup node restart initiated"));
    }

    // ==================== Gateway Operations ====================

    private void handleStopGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.stopGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Gateway stop initiated"));
    }

    private void handleStartGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.startGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Gateway start initiated"));
    }

    private void handleRestartGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        adminService.restartGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "All gateways restart initiated"));
    }

    // ==================== Individual Gateway Operations ====================

    private void handleStopMarketGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.stopMarketGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Market gateway stop initiated"));
    }

    private void handleStartMarketGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.startMarketGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Market gateway start initiated"));
    }

    private void handleRestartMarketGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.restartMarketGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Market gateway restart initiated"));
    }

    private void handleStopOrderGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.stopOrderGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Order gateway stop initiated"));
    }

    private void handleStartOrderGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.startOrderGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Order gateway start initiated"));
    }

    private void handleRestartOrderGateway(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        adminService.restartOrderGateway();
        sendJsonResponse(exchange, 202, Map.of("message", "Order gateway restart initiated"));
    }

    // ==================== Complex Operations ====================

    private void handleRollingUpdate(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            adminService.rollingUpdate();
            sendJsonResponse(exchange, 202, Map.of("message", "Rolling update initiated"));
        } catch (IllegalStateException e) {
            sendJsonResponse(exchange, 409, Map.of("error", e.getMessage()));
        }
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            adminService.snapshot();
            sendJsonResponse(exchange, 202, Map.of("message", "Snapshot initiated"));
        } catch (IllegalStateException e) {
            sendJsonResponse(exchange, 409, Map.of("error", e.getMessage()));
        }
    }

    private void handleCompact(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        if (!requirePost(exchange)) return;

        try {
            adminService.compact();
            sendJsonResponse(exchange, 202, Map.of("message", "Archive compaction initiated"));
        } catch (IllegalStateException e) {
            sendJsonResponse(exchange, 409, Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle auto-snapshot configuration.
     * POST: Start auto-snapshot with {"intervalMinutes": N}
     * DELETE: Stop auto-snapshot
     * GET: Get auto-snapshot status
     */
    private void handleAutoSnapshot(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        String method = exchange.getRequestMethod().toUpperCase();

        switch (method) {
            case "POST":
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> request = gson.fromJson(body, Map.class);
                    Number intervalNum = (Number) request.get("intervalMinutes");
                    if (intervalNum == null || intervalNum.longValue() <= 0) {
                        sendJsonResponse(exchange, 400, Map.of("error", "intervalMinutes must be a positive number"));
                        return;
                    }
                    long interval = intervalNum.longValue();
                    adminService.startAutoSnapshot(interval);
                    sendJsonResponse(exchange, 200, Map.of(
                        "status", "started",
                        "intervalMinutes", interval,
                        "message", "Auto-snapshot enabled: every " + interval + " minutes"
                    ));
                } catch (Exception e) {
                    sendJsonResponse(exchange, 400, Map.of("error", e.getMessage()));
                }
                break;

            case "DELETE":
                adminService.stopAutoSnapshot();
                sendJsonResponse(exchange, 200, Map.of(
                    "status", "stopped",
                    "message", "Auto-snapshot disabled"
                ));
                break;

            case "GET":
            default:
                long lastPos = adminService.getLastSnapshotPosition();
                Map<String, Object> status = new java.util.HashMap<>();
                status.put("enabled", adminService.isAutoSnapshotEnabled());
                status.put("intervalMinutes", adminService.getSnapshotIntervalMinutes());
                if (lastPos >= 0) {
                    status.put("lastPosition", lastPos);
                }
                sendJsonResponse(exchange, 200, status);
                break;
        }
    }

    // ==================== Cleanup Operations ====================

    private void handleCleanup(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;
        if (!requirePost(exchange)) return;

        Map<String, Object> result = adminService.cleanup();
        boolean success = (boolean) result.getOrDefault("success", false);
        sendJsonResponse(exchange, success ? 200 : 400, result);
    }

    // ==================== Progress & Logs ====================

    private void handleProgress(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        // Check for reset parameter
        String query = exchange.getRequestURI().getQuery();
        if (query != null && query.contains("reset=true") &&
            adminService.getOperationProgress().isComplete()) {
            adminService.getOperationProgress().reset();
        }

        sendJsonResponse(exchange, 200, adminService.getOperationProgress().toMap());
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if (handleCorsPreFlight(exchange)) return;

        String query = exchange.getRequestURI().getQuery();
        int nodeId = -1;
        String service = null;
        int lines = 50;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2) {
                    if ("node".equals(kv[0])) nodeId = Integer.parseInt(kv[1]);
                    if ("service".equals(kv[0])) service = kv[1];
                    if ("lines".equals(kv[0])) lines = Math.min(500, Integer.parseInt(kv[1]));
                }
            }
        }

        if (service != null) {
            sendJsonResponse(exchange, 200, adminService.getServiceLogs(service, lines));
        } else {
            sendJsonResponse(exchange, 200, adminService.getLogs(Math.max(0, nodeId), lines));
        }
    }

    // ==================== Utility Methods ====================

    private void setCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private boolean handleCorsPreFlight(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJsonResponse(exchange, 405, Map.of("error", "POST required"));
            return false;
        }
        return true;
    }

    private int getNodeIdFromBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> request = gson.fromJson(body, Map.class);
        int nodeId = ((Number) request.get("nodeId")).intValue();

        if (nodeId < 0 || nodeId > 2) {
            throw new IllegalArgumentException("Invalid nodeId. Must be 0, 1, or 2");
        }
        return nodeId;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
