package com.match.infrastructure.gateway;

import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.gateway.state.GatewayStateManager;
import com.match.infrastructure.websocket.MarketDataWebSocket;

/**
 * Market Gateway - broadcasts market data from cluster to WebSocket clients.
 * Maintains local state (order book, trades, open orders) for serving client queries.
 * Runs as a separate process on port 8081.
 */
public class MarketGatewayMain implements AutoCloseable {

    private final AeronGateway aeronGateway;
    private final MarketDataWebSocket marketDataWebSocket;
    private final GatewayStateManager stateManager;
    private final ClusterStatus clusterStatus;

    public MarketGatewayMain() {
        this.clusterStatus = new ClusterStatus();

        // Create state manager for local state caching
        this.stateManager = new GatewayStateManager();

        this.aeronGateway = new AeronGateway();
        this.aeronGateway.setClusterStatus(clusterStatus);

        this.marketDataWebSocket = new MarketDataWebSocket();
        this.marketDataWebSocket.setClusterStatus(clusterStatus);
        this.marketDataWebSocket.setStateManager(stateManager);

        // Wire state manager as egress listener (handles state updates and broadcasts)
        this.stateManager.setWebSocket(marketDataWebSocket);
        this.aeronGateway.setEgressListener(stateManager);
    }

    public void start() throws Exception {
        // Connect to cluster
        aeronGateway.connect();

        // Start WebSocket server
        marketDataWebSocket.start();

        clusterStatus.setMarketChannels(marketDataWebSocket.getChannels());
    }

    public void startPolling() {
        aeronGateway.startPolling();
    }

    @Override
    public void close() {
        if (marketDataWebSocket != null) {
            marketDataWebSocket.close();
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
            MarketGatewayMain gateway = new MarketGatewayMain();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            gateway.start();
            gateway.startPolling();
        } catch (Throwable t) {
            System.err.println("Failed to start market gateway: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }
}
