package com.match.infrastructure.gateway;

import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.websocket.MarketDataWebSocket;

/**
 * Market Gateway - broadcasts market data from cluster to WebSocket clients.
 * Runs as a separate process on port 8081.
 */
public class MarketGatewayMain implements AutoCloseable {

    private final AeronGateway aeronGateway;
    private final MarketDataWebSocket marketDataWebSocket;
    private final ClusterStatus clusterStatus;

    public MarketGatewayMain() {
        this.clusterStatus = new ClusterStatus();

        this.aeronGateway = new AeronGateway();
        this.aeronGateway.setClusterStatus(clusterStatus);

        this.marketDataWebSocket = new MarketDataWebSocket();
        this.marketDataWebSocket.setClusterStatus(clusterStatus);
        this.aeronGateway.setEgressListener(marketDataWebSocket);
    }

    public void start() throws Exception {
        // Connect to cluster
        aeronGateway.connect();

        // Start WebSocket server
        marketDataWebSocket.start();

        // Wire up WebSocket channels for status broadcasts
        clusterStatus.setMarketChannels(marketDataWebSocket.getChannels());

        System.out.println("Market Gateway started on ws://localhost:8081/ws");
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
        try {
            MarketGatewayMain gateway = new MarketGatewayMain();
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close));
            gateway.start();
            gateway.startPolling();
        } catch (Exception e) {
            System.err.println("Failed to start market gateway: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
