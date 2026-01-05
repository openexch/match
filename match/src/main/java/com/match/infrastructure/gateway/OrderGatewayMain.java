package com.match.infrastructure.gateway;

import com.match.infrastructure.http.HttpOrderApi;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Order Gateway - accepts HTTP order submissions and forwards to cluster.
 * Runs as a separate process on port 8080.
 */
public class OrderGatewayMain implements AutoCloseable {

    private static final int HTTP_PORT = 8080;

    private final AeronGateway aeronGateway;
    private final HttpServer httpServer;

    public OrderGatewayMain() throws IOException {
        // Order gateway doesn't need to send heartbeats - only market gateway does
        this.aeronGateway = new AeronGateway(false);

        // Create HTTP server
        this.httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        // Register order endpoint
        HttpOrderApi orderApi = new HttpOrderApi(aeronGateway);
        httpServer.createContext("/order", orderApi);

        // Health check endpoint
        httpServer.createContext("/health", exchange -> {
            String response = "{\"status\":\"ok\",\"service\":\"order-gateway\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        // CORS preflight for /order
        httpServer.createContext("/", exchange -> {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        httpServer.setExecutor(null);
    }

    public void start() throws Exception {
        // Connect to cluster
        aeronGateway.connect();

        // Start HTTP server
        httpServer.start();

        System.out.println("Order Gateway started on http://localhost:" + HTTP_PORT);
    }

    public void startPolling() {
        aeronGateway.startPolling();
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (aeronGateway != null) {
            aeronGateway.close();
        }
    }

    public static void main(String[] args) {
        try {
            OrderGatewayMain gateway = new OrderGatewayMain();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            gateway.start();
            gateway.startPolling();
        } catch (Exception e) {
            System.err.println("Failed to start order gateway: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
