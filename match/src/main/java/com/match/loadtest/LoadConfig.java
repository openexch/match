package com.match.loadtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration for load testing
 */
public class LoadConfig {
    private final int targetOrdersPerSecond;
    private final int durationSeconds;
    private final int workerThreads;
    private final String market;
    private final OrderScenario scenario;
    private final List<String> clusterHosts;
    private final int basePort;
    private final String egressChannel;
    private final String ingressChannel;
    private final int maxRetries;
    private final long retryDelayMs;

    private LoadConfig(Builder builder) {
        this.targetOrdersPerSecond = builder.targetOrdersPerSecond;
        this.durationSeconds = builder.durationSeconds;
        this.workerThreads = builder.workerThreads;
        this.market = builder.market;
        this.scenario = builder.scenario;
        this.clusterHosts = builder.clusterHosts;
        this.basePort = builder.basePort;
        this.egressChannel = builder.egressChannel;
        this.ingressChannel = builder.ingressChannel;
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
    }

    public int getTargetOrdersPerSecond() {
        return targetOrdersPerSecond;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public String getMarket() {
        return market;
    }

    public OrderScenario getScenario() {
        return scenario;
    }

    public List<String> getClusterHosts() {
        return clusterHosts;
    }

    public int getBasePort() {
        return basePort;
    }

    public String getEgressChannel() {
        return egressChannel;
    }

    public String getIngressChannel() {
        return ingressChannel;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int targetOrdersPerSecond = 1000;
        private int durationSeconds = 60;
        private int workerThreads = 4;
        private String market = "BTC-USD";
        private OrderScenario scenario = OrderScenario.BALANCED;
        private List<String> clusterHosts = Arrays.asList("localhost", "localhost", "localhost");
        private int basePort = 9000;
        private String egressChannel = getDefaultEgressChannel();
        private String ingressChannel = "aeron:udp?term-length=1m"; // Increased from 64k to 1MB
        private int maxRetries = 10; // Increased retries but with shorter delays
        private long retryDelayMs = 0; // Remove sleep - use busy retry instead

        public Builder targetOrdersPerSecond(int targetOrdersPerSecond) {
            this.targetOrdersPerSecond = targetOrdersPerSecond;
            return this;
        }

        public Builder durationSeconds(int durationSeconds) {
            this.durationSeconds = durationSeconds;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder market(String market) {
            this.market = market;
            return this;
        }

        public Builder scenario(OrderScenario scenario) {
            this.scenario = scenario;
            return this;
        }

        public Builder clusterHosts(List<String> clusterHosts) {
            this.clusterHosts = new ArrayList<>(clusterHosts);
            return this;
        }

        public Builder basePort(int basePort) {
            this.basePort = basePort;
            return this;
        }

        public Builder egressChannel(String egressChannel) {
            this.egressChannel = egressChannel;
            return this;
        }

        public Builder ingressChannel(String ingressChannel) {
            this.ingressChannel = ingressChannel;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
            return this;
        }

        public LoadConfig build() {
            return new LoadConfig(this);
        }

        /**
         * Get the default egress channel configuration.
         * Uses network interface detection to find the appropriate endpoint.
         */
        private static String getDefaultEgressChannel() {
            try {
                // Try to get the container's IP address from network interfaces
                java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getByName("eth0");
                if (networkInterface != null) {
                    java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        java.net.InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                            // Found a valid IPv4 address on eth0, use it for egress
                            return "aeron:udp?endpoint=" + addr.getHostAddress() + ":0";
                        }
                    }
                }
            } catch (Exception e) {
                // Fall through to default
            }

            // Default to wildcard binding if we can't determine the IP
            return "aeron:udp?endpoint=0.0.0.0:0";
        }
    }
}
