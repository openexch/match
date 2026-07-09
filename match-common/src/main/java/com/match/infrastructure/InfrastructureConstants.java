// SPDX-License-Identifier: Apache-2.0
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
    /**
     * Protocol-level session keep-alive interval (AeronCluster.sendKeepAlive).
     * Handled by the consensus module — never enters the Raft log.
     * Must be well under SESSION_TIMEOUT_NS (10s); 1s gives 10x margin.
     */
    public static final long SESSION_KEEPALIVE_INTERVAL_MS = 1_000;
    /** Minimum delay between reconnection attempts (ms) */
    public static final long RECONNECT_COOLDOWN_MS = 500;

    // ==================== SETTLEMENT JOURNAL ====================
    // The settlement journal is a per-node, disk-backed second Aeron archive: a recorded
    // publication carrying every trade + every terminal order status, written from the
    // deterministic service thread (settlement-journal-schema.xml, schema id=3). It is a
    // money-loss surface (consumers release residual holds off it) so it is designed to be
    // lossless by construction: separate stream/archive from the consensus log, so journal
    // durability is never entangled with cluster replication/snapshotting.
    /** Aeron stream id the settlement journal is recorded/replayed on. */
    public static final int SETTLEMENT_JOURNAL_STREAM_ID = 4001;
    /** Aeron IPC channel the settlement journal is published on (large term for durability headroom). */
    public static final String SETTLEMENT_JOURNAL_CHANNEL = "aeron:ipc?term-length=64m";
    /**
     * Port offset for the settlement journal's archive control channel, distinct from the
     * consensus archive's {@code ARCHIVE_CONTROL_PORT_OFFSET} (=1) so the two archives never
     * share a control port. Combined with the standard portBase + nodeId*100 scheme this
     * yields 9010/9110/9210 for nodes 0/1/2 on the default portBase 9000.
     */
    public static final int JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET = 10;
    /**
     * Control stream ids for the journal archive, distinct from the consensus archive's
     * defaults (10/11): both archives are clients of the SAME media driver, and the
     * local-control IPC channel is shared driver-wide, so identical stream ids would collide.
     */
    public static final int JOURNAL_ARCHIVE_CONTROL_STREAM_ID = 4010;
    public static final int JOURNAL_ARCHIVE_LOCAL_CONTROL_STREAM_ID = 4011;
}
