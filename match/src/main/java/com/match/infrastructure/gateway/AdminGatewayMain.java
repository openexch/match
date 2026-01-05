package com.match.infrastructure.gateway;

import com.match.infrastructure.admin.ClusterAdminService;
import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.admin.OperationProgress;
import com.match.infrastructure.http.AdminHttpApi;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Admin Gateway - serves admin API only.
 * Runs as a separate process on port 8082.
 * Does not require cluster connection - uses systemctl for management.
 * UI runs separately via Vite dev server or static file server.
 */
public class AdminGatewayMain implements AutoCloseable {

    private static final int HTTP_PORT = 8082;

    private final HttpServer httpServer;
    private final ClusterStatus clusterStatus;
    private final OperationProgress operationProgress;
    private final ClusterAdminService adminService;

    public AdminGatewayMain() throws IOException {
        this.clusterStatus = new ClusterStatus();
        this.operationProgress = new OperationProgress();
        this.adminService = new ClusterAdminService(clusterStatus, operationProgress);

        // Create HTTP server
        this.httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        // Register admin endpoints
        AdminHttpApi adminHttpApi = new AdminHttpApi(adminService);
        adminHttpApi.registerEndpoints(httpServer);

        // Root handler for health check and CORS
        httpServer.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();

            // Handle CORS preflight
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            // Health check
            if ("/".equals(path) || "/health".equals(path)) {
                String response = "{\"status\":\"ok\",\"service\":\"admin-gateway\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // 404 for other unhandled paths
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        httpServer.setExecutor(null);
    }

    public void start() {
        httpServer.start();
        System.out.println("Admin Gateway started on http://localhost:" + HTTP_PORT);
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    public static void main(String[] args) {
        try {
            AdminGatewayMain gateway = new AdminGatewayMain();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            gateway.start();

            // Keep the main thread alive
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("Failed to start admin gateway: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
