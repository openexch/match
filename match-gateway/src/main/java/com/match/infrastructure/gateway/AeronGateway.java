package com.match.infrastructure.gateway;

import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.generated.*;

import static com.match.infrastructure.InfrastructureConstants.*;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core Aeron Cluster client connection manager.
 *
 * IMPORTANT: Following official Aeron best practices:
 * - Single-threaded model: all cluster operations (pollEgress, sendKeepAlive, offer) happen on same thread
 * - MediaDriver is kept alive across reconnections for faster recovery
 * - AeronCluster has internal state machine - we trust isClosed() as primary indicator
 * - Exponential backoff prevents reconnection storms
 *
 * References:
 * - https://theaeronfiles.com/aeron-cluster/messages/egress/
 * - https://aeron.io/docs/aeron-cluster/cluster-clients/
 */
public class AeronGateway implements EgressListener, AutoCloseable {

    /**
     * Listener interface for egress messages from the cluster.
     * Receives decoded SBE messages for zero-allocation processing.
     */
    public interface EgressMessageListener {
        void onBookSnapshot(BookSnapshotDecoder decoder);
        void onBookDelta(BookDeltaDecoder decoder);
        void onTradesBatch(TradesBatchDecoder decoder);
        void onOrderStatusBatch(OrderStatusBatchDecoder decoder);
        void onNewLeader(int leaderMemberId, long leadershipTermId);
    }

    private volatile MediaDriver mediaDriver;
    private volatile AeronCluster cluster;
    private volatile long lastReconnectAttempt = 0;
    private volatile long reconnectBackoffMs = RECONNECT_COOLDOWN_MS;
    private static final long MAX_RECONNECT_BACKOFF_MS = 4000;
    private volatile int consecutiveReconnectFailures = 0;
    private static final int MAX_FAILURES_BEFORE_DRIVER_RESET = 3;

    // Leader transition tracking — during this window, offers return 503 instead of 500
    private volatile long leaderTransitionDeadlineMs = 0;
    private static final long LEADER_TRANSITION_TIMEOUT_MS = 10_000; // 10s max transition

    // Cluster connection config
    private final String ingressEndpoints;
    private final String egressChannel;

    // Decoders for inbound SBE messages (egress from cluster)
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final BookSnapshotDecoder bookSnapshotDecoder = new BookSnapshotDecoder();
    private final BookDeltaDecoder bookDeltaDecoder = new BookDeltaDecoder();
    private final TradesBatchDecoder tradesBatchDecoder = new TradesBatchDecoder();
    private final OrderStatusBatchDecoder orderStatusBatchDecoder = new OrderStatusBatchDecoder();

    // Heartbeat encoder — SBE-encoded heartbeat sent to cluster to trigger market data flush
    private final UnsafeBuffer heartbeatBuffer = new UnsafeBuffer(new byte[MessageHeaderEncoder.ENCODED_LENGTH + GatewayHeartbeatEncoder.BLOCK_LENGTH]);
    private final MessageHeaderEncoder heartbeatHeaderEncoder = new MessageHeaderEncoder();
    private final GatewayHeartbeatEncoder heartbeatEncoder = new GatewayHeartbeatEncoder();

    // External dependencies
    private volatile EgressMessageListener egressListener;
    private volatile ClusterStatus clusterStatus;

    // Shutdown flag for polling loop
    private final AtomicBoolean pollingRunning = new AtomicBoolean(false);

    // Heartbeat interval in nanoseconds for single-threaded heartbeat
    private static final long HEARTBEAT_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MS);

    // Stats tracking
    private volatile long heartbeatCount = 0;
    private volatile long egressMessageCount = 0;
    private volatile long lastStatsLogMs = 0;
    private static final long STATS_LOG_INTERVAL_MS = 10_000;

    // Stale egress detection — force reconnect if connected but no egress for too long
    private volatile long lastEgressMessageMs = System.currentTimeMillis();
    private static final long STALE_EGRESS_TIMEOUT_MS = 30_000; // 30s with no egress = stale

    public AeronGateway() {
        // Use environment variable or default to 127.0.0.1 for development (avoids IPv6 issues)
        final String clusterAddresses = System.getenv().getOrDefault("CLUSTER_ADDRESSES", "127.0.0.1,127.0.0.1,127.0.0.1");
        final String egressHost = System.getenv().getOrDefault("EGRESS_HOST", "127.0.0.1");
        final int egressPort = Integer.parseInt(System.getenv().getOrDefault("EGRESS_PORT", "9091"));

        final List<String> hostnames = Arrays.asList(clusterAddresses.split(","));
        this.ingressEndpoints = ClusterConfig.ingressEndpoints(
            hostnames, 9000, ClusterConfig.CLIENT_FACING_PORT_OFFSET);
        this.egressChannel = "aeron:udp?endpoint=" + egressHost + ":" + egressPort;

    }

    public void setEgressListener(EgressMessageListener listener) {
        this.egressListener = listener;
    }

    public void setClusterStatus(ClusterStatus status) {
        this.clusterStatus = status;
    }

    /**
     * Initialize connection with retries on startup.
     */
    public void connect() throws Exception {
        createMediaDriver();

        int maxStartupRetries = 30;
        for (int attempt = 1; attempt <= maxStartupRetries; attempt++) {
            try {
                connectToCluster();
                System.out.println("Connected to cluster on attempt " + attempt);
                break;
            } catch (Exception e) {
                System.err.println("Startup connection attempt " + attempt + "/" + maxStartupRetries + " failed: " + e.getMessage());
                if (attempt == maxStartupRetries) {
                    throw new RuntimeException("Failed to connect to cluster after " + maxStartupRetries + " attempts", e);
                }
                Thread.sleep(2000);
            }
        }
    }

    /**
     * Create MediaDriver with settings optimized for cluster client.
     * Uses /dev/shm for pure memory operations (lower latency than /tmp).
     * Uses a fixed directory name per gateway type (order/market) to prevent
     * stale directory accumulation. dirDeleteOnStart cleans any leftover state.
     */
    private void createMediaDriver() {
        if (mediaDriver != null) {
            return; // Already have a MediaDriver
        }

        // Clean up any stale gateway directories from previous crashed sessions
        cleanStaleGatewayDirs();

        // Use /dev/shm for pure memory operations (tmpfs in RAM)
        // Fixed directory name per process - dirDeleteOnStart handles stale state
        String gatewayType = System.getenv().getOrDefault("GATEWAY_TYPE", "gateway");
        String dir = "/dev/shm/aeron-" + gatewayType + "-" + ProcessHandle.current().pid();

        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED)
            .aeronDirectoryName(dir)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));

        System.out.println("MediaDriver created: " + mediaDriver.aeronDirectoryName());
    }

    /**
     * Clean up stale /dev/shm/aeron-gateway-* directories that were left behind
     * by previous sessions that didn't shut down cleanly.
     * Only removes directories whose PID suffix corresponds to a dead process.
     */
    private void cleanStaleGatewayDirs() {
        long myPid = ProcessHandle.current().pid();
        java.io.File shmDir = new java.io.File("/dev/shm");
        java.io.File[] staleDirs = shmDir.listFiles((dir, name) ->
            name.startsWith("aeron-gateway-") || name.startsWith("aeron-order-") || name.startsWith("aeron-market-"));
        if (staleDirs == null) return;

        for (java.io.File staleDir : staleDirs) {
            String name = staleDir.getName();
            // Extract PID from directory name (last segment after -)
            int lastDash = name.lastIndexOf('-');
            if (lastDash >= 0) {
                try {
                    long dirPid = Long.parseLong(name.substring(lastDash + 1));
                    // Skip our own PID — dirDeleteOnStart will handle it
                    if (dirPid == myPid) continue;
                    // Skip if PID is still alive
                    if (ProcessHandle.of(dirPid).map(ProcessHandle::isAlive).orElse(false)) continue;
                } catch (NumberFormatException e) {
                    // Old-style nanoTime directory — always clean up
                }
            }

            try {
                deleteDirectory(staleDir);
                System.out.println("Cleaned stale MediaDriver dir: " + name);
            } catch (Exception e) {
                System.err.println("Failed to clean " + name + ": " + e.getMessage());
            }
        }
    }

    private void deleteDirectory(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Connect to the Aeron Cluster.
     * Per Aeron docs: AeronCluster has internal state machine that handles leader changes.
     * We only need to reconnect when isClosed() returns true.
     */
    private void connectToCluster() {
        // Close existing cluster connection cleanly
        if (cluster != null) {
            CloseHelper.quietClose(cluster);
            cluster = null;
        }

        final AeronCluster.Context clusterCtx = new AeronCluster.Context()
            .egressListener(this)
            .egressChannel(egressChannel)
            .ingressChannel("aeron:udp?term-length=16m|mtu=8k")
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
            .ingressEndpoints(ingressEndpoints)
            .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5));

        this.cluster = AeronCluster.connect(clusterCtx);
        System.out.println("AeronCluster.connect() completed, sessionId=" + cluster.clusterSessionId() +
                          ", leader=" + cluster.leaderMemberId() + ", term=" + cluster.leadershipTermId());

        int currentLeader = cluster.leaderMemberId();
        if (currentLeader >= 0 && clusterStatus != null) {
            clusterStatus.updateLeader(currentLeader, cluster.leadershipTermId());
        }

        if (clusterStatus != null) {
            clusterStatus.setGatewayConnected(true);
        }

        // Reset backoff, failure counter, and egress timer on successful connection
        reconnectBackoffMs = RECONNECT_COOLDOWN_MS;
        consecutiveReconnectFailures = 0;
        lastEgressMessageMs = System.currentTimeMillis();
    }

    /**
     * Attempt to reconnect with exponential backoff.
     * Recreates MediaDriver after consecutive failures to clear stale state.
     */
    private void tryReconnect() {
        long now = System.currentTimeMillis();
        if (now - lastReconnectAttempt < reconnectBackoffMs) {
            return; // Still in backoff period
        }

        lastReconnectAttempt = now;

        // Close old cluster connection
        CloseHelper.quietClose(cluster);
        cluster = null;

        if (clusterStatus != null) {
            clusterStatus.setGatewayConnected(false);
        }

        // After consecutive failures, recreate MediaDriver to clear stale state
        // This handles cases where publications/subscriptions have stale images
        if (consecutiveReconnectFailures >= MAX_FAILURES_BEFORE_DRIVER_RESET) {
            System.out.println("Resetting MediaDriver after " + consecutiveReconnectFailures + " consecutive failures");
            CloseHelper.quietClose(mediaDriver);
            mediaDriver = null;
            consecutiveReconnectFailures = 0;
        }

        try {
            // Ensure MediaDriver exists (create if needed)
            if (mediaDriver == null) {
                createMediaDriver();
            }

            connectToCluster();
            System.out.println("Reconnected to cluster successfully");
            consecutiveReconnectFailures = 0; // Reset on success
        } catch (Throwable e) {
            consecutiveReconnectFailures++;
            System.err.println("Reconnection failed (attempt " + consecutiveReconnectFailures +
                ", next in " + reconnectBackoffMs + "ms): " +
                e.getClass().getSimpleName() + ": " + e.getMessage());

            // Exponential backoff: 500ms -> 1s -> 2s -> 4s (max)
            reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, MAX_RECONNECT_BACKOFF_MS);

            // Also reset MediaDriver if error indicates publication/connection issues
            String msg = e.getMessage();
            if (msg != null && (msg.contains("ingressPublication=null") ||
                               msg.contains("AWAIT_PUBLICATION") ||
                               msg.contains("MediaDriver"))) {
                System.out.println("Publication connection issue detected, will reset MediaDriver");
                CloseHelper.quietClose(mediaDriver);
                mediaDriver = null;
            }
        }
    }

    /**
     * Main polling loop - SINGLE THREADED as per Aeron best practices.
     *
     * Per official docs: "The client should not be used from another thread,
     * e.g. a separate thread calling AeronCluster#sendKeepAlive() - which is
     * described as 'awful design'."
     *
     * All operations (pollEgress, sendKeepAlive, heartbeats, order drain) happen in this thread.
     */
    public void startPolling() {
        if (!pollingRunning.compareAndSet(false, true)) {
            System.err.println("Polling already running");
            return;
        }

        // BackoffIdleStrategy: minimal spinning, progressive backoff to 100us max
        final IdleStrategy idleStrategy = new BackoffIdleStrategy(1, 1, 1_000, 100_000);

        // Heartbeat tracking - done in same thread as polling
        long lastHeartbeatNs = System.nanoTime();

        System.out.println("Starting single-threaded polling loop (Aeron best practice)");
        System.out.println("Egress channel: " + egressChannel);
        if (cluster != null) {
            var ingressPub = cluster.ingressPublication();
            System.out.println("Ingress publication: connected=" + ingressPub.isConnected() +
                              ", channel=" + ingressPub.channel() +
                              ", sessionId=" + ingressPub.sessionId());
            System.out.println("Egress subscription connected: " + cluster.egressSubscription().isConnected());
            System.out.println("Egress subscription imageCount: " + cluster.egressSubscription().imageCount());
            for (int i = 0; i < cluster.egressSubscription().imageCount(); i++) {
                var img = cluster.egressSubscription().imageAtIndex(i);
                System.out.println("Image[" + i + "]: sessionId=" + img.sessionId() + ", pos=" + img.position() + ", closed=" + img.isClosed());
            }
            System.out.println("Cluster sessionId: " + cluster.clusterSessionId());
            System.out.println("Leader memberId: " + cluster.leaderMemberId());
        }

        while (pollingRunning.get()) {
            final AeronCluster currentCluster = cluster;

            // Check if reconnection needed
            // Per Aeron docs: AeronCluster will close itself if connection lost for > newLeaderTimeoutNs
            if (currentCluster == null || currentCluster.isClosed()) {
                tryReconnect();
                idleStrategy.idle(0);
                continue;
            }

            try {
                // Poll egress - this handles leader changes internally
                int work = currentCluster.pollEgress();

                // Debug: log first few poll results with work > 0
                if (work > 0 && heartbeatCount < 10) {
                    System.out.println("POLL work=" + work + ", egressSub=" + currentCluster.egressSubscription().isConnected());
                }

                // Send SBE heartbeat to cluster in SAME thread (single-threaded model)
                // This triggers handleGatewayHeartbeat on the cluster service, which:
                //   1. Flushes queued market data (book snapshots, trades) via session.offer()
                //   2. Sends heartbeat ACK back to keep egress alive
                // Using cluster.offer() instead of sendKeepAlive() because sendKeepAlive()
                // does NOT trigger onSessionMessage — it only keeps the session alive internally.
                long nowNs = System.nanoTime();
                if (nowNs - lastHeartbeatNs >= HEARTBEAT_INTERVAL_NS) {
                    if (!currentCluster.isClosed()) {
                        heartbeatEncoder.wrapAndApplyHeader(heartbeatBuffer, 0, heartbeatHeaderEncoder);
                        heartbeatEncoder
                            .gatewayId(currentCluster.clusterSessionId())
                            .timestamp(System.currentTimeMillis());
                        int heartbeatLength = MessageHeaderEncoder.ENCODED_LENGTH + heartbeatEncoder.encodedLength();
                        long result = currentCluster.offer(heartbeatBuffer, 0, heartbeatLength);
                        if (result < 0 && heartbeatCount % 100 == 0) {
                            System.err.println("Heartbeat offer failed: " + getOfferResultName(result));
                        }
                    }
                    lastHeartbeatNs = nowNs;
                    heartbeatCount++;
                    work++;
                }

                // Periodic stats logging
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastStatsLogMs > STATS_LOG_INTERVAL_MS) {
                    long egressAgeMs = nowMs - lastEgressMessageMs;
                    var sub = currentCluster.egressSubscription();
                    System.out.println("GATEWAY STATS: egress=" + egressMessageCount +
                                      ", heartbeats=" + heartbeatCount +
                                      ", connected=" + isConnected() +
                                      ", subConnected=" + sub.isConnected() +
                                      ", images=" + sub.imageCount() +
                                      ", sessionId=" + currentCluster.clusterSessionId() +
                                      ", egressAge=" + egressAgeMs + "ms");
                    lastStatsLogMs = nowMs;

                    // STALE EGRESS DETECTION: connected but no data flowing
                    // This catches the case where the cluster client reports connected
                    // but the egress subscription image is stale (e.g., after leader change)
                    if (egressAgeMs > STALE_EGRESS_TIMEOUT_MS && egressMessageCount > 0) {
                        System.err.println("STALE EGRESS DETECTED: no egress messages for " +
                            egressAgeMs + "ms while connected. Forcing reconnect.");
                        // Force cluster close to trigger reconnection
                        CloseHelper.quietClose(currentCluster);
                        cluster = null;
                        if (clusterStatus != null) {
                            clusterStatus.setGatewayConnected(false);
                        }
                        // Reset backoff for fast recovery
                        lastReconnectAttempt = 0;
                        reconnectBackoffMs = RECONNECT_COOLDOWN_MS;
                    }
                }

                idleStrategy.idle(work);
            } catch (Throwable t) {
                System.err.println("POLL ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                if (t instanceof Error) {
                    t.printStackTrace();
                }
                // On error, the cluster may be in bad state - let next iteration handle reconnect
            }
        }

        System.out.println("Polling loop exited gracefully");
    }

    private String getOfferResultName(long result) {
        if (result == Publication.NOT_CONNECTED) return "NOT_CONNECTED";
        if (result == Publication.BACK_PRESSURED) return "BACK_PRESSURED";
        if (result == Publication.ADMIN_ACTION) return "ADMIN_ACTION";
        if (result == Publication.CLOSED) return "CLOSED";
        if (result == Publication.MAX_POSITION_EXCEEDED) return "MAX_POSITION_EXCEEDED";
        return "UNKNOWN(" + result + ")";
    }

    // ==================== Public API ====================

    /**
     * Check if connected to cluster and ready to accept orders.
     * Returns false during leader transitions to prevent stale publication offers.
     */
    public boolean isConnected() {
        AeronCluster c = cluster;
        if (c == null || c.isClosed()) {
            return false;
        }
        // During leader transition, publication may be stale
        if (isTransitioning()) {
            // Check if ingress publication is actually connected to new leader
            return c.ingressPublication() != null && c.ingressPublication().isConnected();
        }
        return true;
    }

    /**
     * Check if we're in a leader transition window.
     * During this period, offer failures are transient (503) not permanent (500).
     */
    public boolean isTransitioning() {
        long deadline = leaderTransitionDeadlineMs;
        return deadline > 0 && System.currentTimeMillis() < deadline;
    }

    /**
     * Offer raw buffer to cluster — MUST be called from the polling thread only.
     * For thread-safe order submission from HTTP threads, use {@link #submitOrder}.
     *
     * Throws IllegalStateException for transient disconnects (503-worthy)
     * and RuntimeException for permanent failures (500-worthy).
     */
    public void offer(DirectBuffer buffer, int offset, int length) {
        AeronCluster c = cluster;
        if (c == null || c.isClosed()) {
            throw new IllegalStateException("Cluster is not connected");
        }

        long result;
        int retryCount = 0;
        final int maxRetries = 3;

        while ((result = c.offer(buffer, offset, length)) < 0) {
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                // If we're in a leader transition, this is expected — signal transient error
                if (isTransitioning()) {
                    throw new IllegalStateException("Leader transition in progress, retry shortly");
                }
                throw new IllegalStateException("Cluster connection lost: " + getOfferResultName(result));
            }

            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                if (++retryCount > maxRetries) {
                    throw new RuntimeException("Message rejected due to backpressure after " + maxRetries + " retries");
                }
                // Poll egress while waiting to help process any pending responses
                c.pollEgress();
                Thread.yield();
            } else {
                throw new RuntimeException("Failed to send message to cluster: " + getOfferResultName(result));
            }
        }

        // Successful offer means leader transition is complete
        if (leaderTransitionDeadlineMs > 0) {
            System.out.println("Leader transition complete — ingress publication connected to new leader");
            leaderTransitionDeadlineMs = 0;
        }
    }

    // ==================== EgressListener Implementation ====================

    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length, Header header) {
        egressMessageCount++;
        lastEgressMessageMs = System.currentTimeMillis();

        // Decode SBE header to determine message type
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return; // Too short to be a valid SBE message
        }

        headerDecoder.wrap(buffer, offset);
        int templateId = headerDecoder.templateId();

        // Debug: log first few egress messages
        if (egressMessageCount <= 5) {
            System.out.println("EGRESS[" + egressMessageCount + "]: templateId=" + templateId + ", length=" + length);
        }

        if (egressListener == null) {
            return;
        }

        // Dispatch based on SBE message type
        switch (templateId) {
            case BookSnapshotDecoder.TEMPLATE_ID:
                bookSnapshotDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                egressListener.onBookSnapshot(bookSnapshotDecoder);
                break;
            case BookDeltaDecoder.TEMPLATE_ID:
                bookDeltaDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                egressListener.onBookDelta(bookDeltaDecoder);
                break;
            case TradesBatchDecoder.TEMPLATE_ID:
                tradesBatchDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                egressListener.onTradesBatch(tradesBatchDecoder);
                break;
            case OrderStatusBatchDecoder.TEMPLATE_ID:
                orderStatusBatchDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                egressListener.onOrderStatusBatch(orderStatusBatchDecoder);
                break;
            default:
                // Unknown message type - could be heartbeat ACK or other internal messages
                if (egressMessageCount <= 10) {
                    System.out.println("EGRESS: Unknown templateId=" + templateId);
                }
                break;
        }
    }

    /**
     * Handle session events from the cluster.
     * Per Aeron docs: EventCode indicates connection state.
     * - OK: successful connection
     * - ERROR/CLOSED: session terminated, need to reconnect
     * - REDIRECT: connected to follower, AeronCluster handles this internally
     */
    @Override
    public void onSessionEvent(
            long correlationId,
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            EventCode code,
            String detail) {

        if (code == EventCode.OK) {
            System.out.println("Session connected: leader=" + leaderMemberId + ", term=" + leadershipTermId);
            return;
        }

        System.err.println("SESSION EVENT: " + code + " - " + detail);

        // Update cluster status for UI
        if (clusterStatus != null && (code == EventCode.ERROR || code == EventCode.CLOSED)) {
            clusterStatus.setGatewayConnected(false);
        }

        // On ERROR or CLOSED, force immediate reconnection attempt (bypass backoff)
        if (code == EventCode.ERROR || code == EventCode.CLOSED) {
            System.out.println("Session lost, forcing immediate reconnection...");
            lastReconnectAttempt = 0; // Reset backoff timer
            reconnectBackoffMs = RECONNECT_COOLDOWN_MS; // Reset backoff delay
        }
    }

    /**
     * Handle leader change events.
     * Per Aeron docs: AeronCluster handles reconnection to new leader automatically
     * via the egress subscription. However, the ingress publication may need time
     * to reconnect to the new leader's ingress endpoint.
     *
     * We mark the gateway as temporarily unavailable during the transition to
     * prevent HTTP handlers from attempting offers on a stale publication.
     */
    @Override
    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId, String ingressEndpoints) {
        System.out.println("New leader elected: member=" + leaderMemberId + ", term=" + leadershipTermId +
                          ", ingress=" + ingressEndpoints);

        // Mark as transitioning — HTTP handlers should return 503 during this window
        leaderTransitionDeadlineMs = System.currentTimeMillis() + LEADER_TRANSITION_TIMEOUT_MS;

        if (clusterStatus != null) {
            clusterStatus.updateLeader(leaderMemberId, leadershipTermId);
        }

        if (egressListener != null) {
            egressListener.onNewLeader(leaderMemberId, leadershipTermId);
        }
    }

    @Override
    public void close() {
        // Signal polling loop to exit
        pollingRunning.set(false);

        CloseHelper.quietClose(cluster);
        CloseHelper.quietClose(mediaDriver);
        cluster = null;
        mediaDriver = null;
    }
}
