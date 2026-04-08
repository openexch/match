package com.match.infrastructure;

/**
 * Centralized infrastructure configuration constants.
 * Groups related timing, port, and cluster configuration values.
 *
 * IMPORTANT: These constants must be consistent across all components:
 * - Cluster nodes (AeronCluster.java, ClusterConfig.java)
 * - Gateways (AeronGateway.java)
 * - Load generator (LoadGenerator.java)
 */
public final class InfrastructureConstants {
    private InfrastructureConstants() {}

    // ==================== GATEWAY PORTS ====================
    /** Market data WebSocket port */
    public static final int MARKET_GATEWAY_PORT = 8081;
    /** Admin API HTTP port */
    public static final int ADMIN_GATEWAY_PORT = 8082;

    // ==================== AERON BUFFER CONFIGURATION ====================
    /** Socket buffer size - must match cluster for optimal flow control (4MB) */
    public static final int SOCKET_BUFFER_LENGTH = 4 * 1024 * 1024;
    /** Publication/IPC term buffer size - large for high throughput (16MB) */
    public static final int TERM_BUFFER_LENGTH = 16 * 1024 * 1024;
    /** Initial window length for flow control (4MB) */
    public static final int INITIAL_WINDOW_LENGTH = 4 * 1024 * 1024;

    // ==================== CLUSTER TIMEOUTS (nanoseconds) ====================
    /** Leader heartbeat interval - how often leader sends heartbeats (100ms) */
    public static final long LEADER_HEARTBEAT_INTERVAL_NS = 100_000_000L;
    /** Leader heartbeat timeout - 10x interval for stability (1s) */
    public static final long LEADER_HEARTBEAT_TIMEOUT_NS = 1_000_000_000L;
    /** Session timeout - close stale sessions to allow reconnection (10s) */
    public static final long SESSION_TIMEOUT_NS = 10_000_000_000L;

    // ==================== GATEWAY TIMING ====================
    /** Heartbeat interval from gateway to cluster - matches leader interval (100ms) */
    public static final long HEARTBEAT_INTERVAL_MS = 100;
    /** Minimum delay between reconnection attempts (ms) */
    public static final long RECONNECT_COOLDOWN_MS = 500;

    // ==================== CLUSTER TIMING ====================
    /** Time without gateway heartbeat before considering it dead (ms) */
    public static final long GATEWAY_TIMEOUT_MS = 30_000;
}
