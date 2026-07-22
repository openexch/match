// SPDX-License-Identifier: Apache-2.0
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
        private List<String> clusterHosts = Arrays.asList("127.0.0.1", "127.0.0.1", "127.0.0.1");
        private int basePort = 9000;
        private String egressChannel; // resolved in build(): EGRESS_HOST env or route-based detection
        private String ingressChannel = "aeron:udp?term-length=16m|mtu=8k"; // Match cluster config
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
            if (egressChannel == null) {
                egressChannel = detectEgressChannel(clusterHosts.isEmpty() ? "127.0.0.1" : clusterHosts.get(0));
            }
            return new LoadConfig(this);
        }

        /**
         * Resolve the egress (response) channel endpoint the cluster publishes back to.
         * The address must be reachable FROM the cluster nodes, so loopback is only
         * correct when the cluster itself is local. Resolution order:
         *   1. EGRESS_HOST env (operator override; same name the gateway and OMS use)
         *   2. the local address the OS routes toward the first cluster host
         *      (interface-name agnostic: eth0, ens5, bonded NICs all work)
         *   3. first non-loopback IPv4 on any up interface
         *   4. loopback (single-host development)
         */
        private static String detectEgressChannel(String peerHost) {
            String env = System.getenv("EGRESS_HOST");
            if (env != null && !env.isBlank()) {
                return "aeron:udp?endpoint=" + env + ":0";
            }
            try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
                probe.connect(java.net.InetAddress.getByName(peerHost), 9);
                java.net.InetAddress local = probe.getLocalAddress();
                if (local instanceof java.net.Inet4Address && !local.isAnyLocalAddress()) {
                    return "aeron:udp?endpoint=" + local.getHostAddress() + ":0";
                }
            } catch (Exception e) {
                // fall through
            }
            try {
                java.util.Enumeration<java.net.NetworkInterface> ifaces =
                        java.net.NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    java.net.NetworkInterface ni = ifaces.nextElement();
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        java.net.InetAddress addr = addrs.nextElement();
                        if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                            return "aeron:udp?endpoint=" + addr.getHostAddress() + ":0";
                        }
                    }
                }
            } catch (Exception e) {
                // fall through
            }
            return "aeron:udp?endpoint=127.0.0.1:0";
        }
    }
}
