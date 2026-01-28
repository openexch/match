package com.match.infrastructure.gateway;

import com.match.infrastructure.http.HttpOrderApi;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static com.match.infrastructure.InfrastructureConstants.*;

/**
 * Order Gateway - accepts HTTP order submissions and forwards to cluster.
 * Runs as a separate process on port 8080.
 */
public class OrderGatewayMain implements AutoCloseable {

    private final AeronGateway aeronGateway;
    private final HttpServer httpServer;

    public OrderGatewayMain() throws IOException {
        this.aeronGateway = new AeronGateway();

        // Create HTTP server
        this.httpServer = HttpServer.create(new InetSocketAddress(ORDER_GATEWAY_PORT), 0);

        // Register order endpoint
        HttpOrderApi orderApi = new HttpOrderApi(aeronGateway);
        httpServer.createContext("/order", orderApi);

        // Health check endpoint — reflects cluster connectivity
        httpServer.createContext("/health", exchange -> {
            boolean connected = aeronGateway.isConnected();
            boolean transitioning = aeronGateway.isTransitioning();
            String status = connected ? "ok" : (transitioning ? "transitioning" : "disconnected");
            int code = connected ? 200 : 503;
            String response = "{\"status\":\"" + status + "\",\"service\":\"order-gateway\"" +
                ",\"connected\":" + connected + ",\"transitioning\":" + transitioning + "}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, response.length());
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

        httpServer.start();
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
        // Set global uncaught exception handler to prevent silent JVM death
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("FATAL: Uncaught exception in thread " + thread.getName());
            throwable.printStackTrace();
            System.err.flush();
        });

        try {
            OrderGatewayMain gateway = new OrderGatewayMain();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            gateway.start();
            gateway.startPolling();
        } catch (Throwable t) {
            System.err.println("Failed to start order gateway: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
