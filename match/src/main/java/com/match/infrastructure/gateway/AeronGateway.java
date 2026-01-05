package com.match.infrastructure.gateway;

import com.match.infrastructure.admin.ClusterStatus;
import com.match.infrastructure.generated.*;
import com.match.infrastructure.http.ConnectionState;
import io.aeron.Image;
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
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.agrona.concurrent.SystemEpochClock;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core Aeron Cluster client connection manager.
 * Handles cluster connection, reconnection, heartbeats, and egress polling.
 */
public class AeronGateway implements EgressListener, AutoCloseable {

    /**
     * Listener interface for egress messages from the cluster.
     */
    public interface EgressMessageListener {
        void onMessage(String json);
        void onNewLeader(int leaderMemberId, long leadershipTermId);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 250;
    private static final long RECONNECT_COOLDOWN_MS = 500;
    // Stale connection timeout - 60 seconds
    // If no egress received for this long, force reconnect to detect dead sessions
    // The cluster sends timer-based flushes every 50ms, so no egress for 60s means session is dead
    private static final long STALE_CONNECTION_TIMEOUT_MS = 60_000;

    private volatile MediaDriver mediaDriver;
    private volatile AeronCluster cluster;
    private volatile ConnectionState connectionState = ConnectionState.NOT_CONNECTED;
    private volatile boolean needsReconnect = false;
    private volatile long lastReconnectAttempt = 0;
    private volatile long lastEgressTime = System.currentTimeMillis();

    // Cluster connection config
    private final String ingressEndpoints;
    private final String egressChannel;

    // Encoders for outbound messages
    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(512);
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();
    private final GatewayHeartbeatEncoder heartbeatEncoder = new GatewayHeartbeatEncoder();
    private final ExpandableDirectByteBuffer heartbeatBuffer = new ExpandableDirectByteBuffer(64);
    private final long gatewayId = System.nanoTime();

    // External dependencies
    private volatile EgressMessageListener egressListener;
    private volatile ClusterStatus clusterStatus;
    private final boolean sendHeartbeats;

    // Dedicated heartbeat thread - prevents session timeout when egress processing is slow
    private volatile Thread heartbeatThread;
    private final AtomicBoolean heartbeatRunning = new AtomicBoolean(false);

    public AeronGateway() {
        this(true); // Default: send heartbeats
    }

    public AeronGateway(boolean sendHeartbeats) {
        this.sendHeartbeats = sendHeartbeats;
        // Use environment variable or default to localhost for development
        final String clusterAddresses = System.getenv().getOrDefault("CLUSTER_ADDRESSES", "localhost,localhost,localhost");
        final String egressHost = System.getenv().getOrDefault("EGRESS_HOST", "localhost");
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
        this.mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));

        int maxStartupRetries = 30;
        for (int attempt = 1; attempt <= maxStartupRetries; attempt++) {
            try {
                connectToCluster();
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
     * Create or recreate the MediaDriver with clean state.
     */
    private void ensureMediaDriver() {
        if (mediaDriver != null) {
            CloseHelper.quietClose(mediaDriver);
        }
        mediaDriver = MediaDriver.launchEmbedded(new MediaDriver.Context()
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));
        System.out.println("MediaDriver created: " + mediaDriver.aeronDirectoryName());
    }

    private synchronized void connectToCluster() {
        // Close existing cluster connection cleanly
        if (cluster != null) {
            CloseHelper.quietClose(cluster);
            cluster = null;
        }

        // Ensure we have a valid MediaDriver
        if (mediaDriver == null) {
            ensureMediaDriver();
        }

        final AeronCluster.Context clusterCtx = new AeronCluster.Context()
            .egressListener(this)
            .egressChannel(egressChannel)
            .ingressChannel("aeron:udp?term-length=16m|mtu=8k")
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
            .ingressEndpoints(ingressEndpoints)
            .messageTimeoutNs(TimeUnit.SECONDS.toNanos(5)); // Increased timeout for stability

        System.out.println("Connecting to Aeron Cluster...");
        this.cluster = AeronCluster.connect(clusterCtx);
        System.out.println("Connected to Cluster");
        connectionState = ConnectionState.CONNECTED;
        needsReconnect = false;
        lastEgressTime = System.currentTimeMillis(); // Reset stale connection timer

        // Get current leader from cluster
        int currentLeader = cluster.leaderMemberId();
        if (currentLeader >= 0 && clusterStatus != null) {
            clusterStatus.updateLeader(currentLeader, cluster.leadershipTermId());
            System.out.println("Current leader: Node " + currentLeader);
        }

        // Broadcast gateway connection
        if (clusterStatus != null) {
            clusterStatus.setGatewayConnected(true);
        }
    }

    private synchronized void tryReconnectIfNeeded() {
        if (!needsReconnect) return;

        long now = System.currentTimeMillis();
        if (now - lastReconnectAttempt < RECONNECT_COOLDOWN_MS) return;

        lastReconnectAttempt = now;
        System.out.println("Attempting to reconnect to cluster...");

        try {
            // Always recreate MediaDriver on reconnect for clean state
            ensureMediaDriver();
            connectToCluster();
            System.out.println("Reconnected to cluster successfully!");
        } catch (Throwable e) {
            // Catch Throwable to handle both Exception and Error
            System.err.println("Reconnection failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            connectionState = ConnectionState.NOT_CONNECTED;
            if (clusterStatus != null) {
                clusterStatus.setGatewayConnected(false);
            }
            // Clear MediaDriver for fresh start on next attempt
            CloseHelper.quietClose(mediaDriver);
            mediaDriver = null;
        }
    }

    /**
     * Start dedicated heartbeat thread.
     * This thread runs independently of egress processing to prevent session timeouts
     * when the main polling loop is busy processing many egress messages.
     */
    // Counter for heartbeat logging
    private volatile long heartbeatCount = 0;

    private void startHeartbeatThread() {
        if (heartbeatRunning.compareAndSet(false, true)) {
            heartbeatThread = new Thread(() -> {
                System.out.println("[HEARTBEAT] Dedicated heartbeat thread started");
                while (heartbeatRunning.get()) {
                    try {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);

                        if (connectionState == ConnectionState.CONNECTED && cluster != null && !cluster.isClosed()) {
                            try {
                                cluster.sendKeepAlive();
                                if (sendHeartbeats) {
                                    sendGatewayHeartbeat(SystemEpochClock.INSTANCE.time());
                                }
                                heartbeatCount++;
                                // Log every 40 heartbeats (~10 seconds)
                                if (heartbeatCount % 40 == 1) {
                                    System.out.printf("[HEARTBEAT] Sent %d heartbeats, connected=%s%n",
                                        heartbeatCount, connectionState);
                                }
                            } catch (Exception e) {
                                System.err.println("[HEARTBEAT] Error sending heartbeat: " + e.getMessage());
                                needsReconnect = true;
                            }
                        } else {
                            // Log why we're not sending
                            if (heartbeatCount % 40 == 0) {
                                System.out.printf("[HEARTBEAT] Not sending: state=%s, cluster=%s, closed=%s%n",
                                    connectionState, cluster != null, cluster != null && cluster.isClosed());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                System.out.println("[HEARTBEAT] Dedicated heartbeat thread stopped");
            }, "gateway-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }
    }

    /**
     * Stop the heartbeat thread.
     */
    private void stopHeartbeatThread() {
        heartbeatRunning.set(false);
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            try {
                heartbeatThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Main polling loop - runs forever polling egress.
     * Heartbeats are now handled by a dedicated thread to prevent session timeouts.
     */
    public void startPolling() {
        // Start dedicated heartbeat thread
        startHeartbeatThread();

        final IdleStrategy idleStrategy = new SleepingMillisIdleStrategy(100);
        while (true) {
            // Handle reconnection
            if (needsReconnect || cluster == null || cluster.isClosed()) {
                needsReconnect = true;
                connectionState = ConnectionState.NOT_CONNECTED;
                if (clusterStatus != null) {
                    clusterStatus.setGatewayConnected(false);
                }
                tryReconnectIfNeeded();
                idleStrategy.idle(0);
                continue;
            }

            try {
                int work = cluster.pollEgress();
                final long now = SystemEpochClock.INSTANCE.time();

                // Check egress image health - detect silent connection loss
                if (cluster.egressSubscription() != null && cluster.egressSubscription().imageCount() > 0) {
                    Image egressImage = cluster.egressSubscription().imageAtIndex(0);
                    if (egressImage != null && egressImage.isClosed()) {
                        System.out.println("Egress image closed, triggering reconnect");
                        needsReconnect = true;
                        connectionState = ConnectionState.NOT_CONNECTED;
                        continue;
                    }
                }

                // Check for stale connection - no egress for too long
                long egressAge = now - lastEgressTime;
                if (egressAge > STALE_CONNECTION_TIMEOUT_MS) {
                    System.out.println("[STALE] No egress for " + egressAge + "ms, forcing reconnect");
                    needsReconnect = true;
                    connectionState = ConnectionState.NOT_CONNECTED;
                    continue;
                } else if (egressAge > 30_000 && egressAge % 10_000 < 100) {
                    // Warn when approaching stale threshold
                    System.out.printf("[STALE-WARN] No egress for %dms (threshold: %dms)%n",
                        egressAge, STALE_CONNECTION_TIMEOUT_MS);
                }

                // Note: Heartbeats are now sent by dedicated heartbeat thread
                idleStrategy.idle(work);
            } catch (Throwable e) {
                // Catch Throwable to handle both Exception and Error
                System.err.println("Polling error, will reconnect: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                needsReconnect = true;
                connectionState = ConnectionState.NOT_CONNECTED;
                if (clusterStatus != null) {
                    clusterStatus.setGatewayConnected(false);
                }
            }
        }
    }

    // Lock for heartbeat sending - prevents concurrent access from heartbeat thread and polling thread
    private final Object heartbeatLock = new Object();

    // Counter for tracking consecutive offer failures
    private volatile int consecutiveOfferFailures = 0;
    private static final int MAX_OFFER_FAILURES = 100;

    private void sendGatewayHeartbeat(long timestamp) {
        synchronized (heartbeatLock) {
            if (cluster == null || cluster.isClosed()) {
                return;
            }
            heartbeatEncoder.wrapAndApplyHeader(heartbeatBuffer, 0, headerEncoder);
            heartbeatEncoder.gatewayId(gatewayId);
            heartbeatEncoder.timestamp(timestamp);

            final int length = MessageHeaderEncoder.ENCODED_LENGTH + heartbeatEncoder.encodedLength();
            long result = cluster.offer(heartbeatBuffer, 0, length);

            if (result < 0) {
                consecutiveOfferFailures++;
                // Log all failures periodically
                if (consecutiveOfferFailures % 10 == 1) {
                    System.err.printf("[HEARTBEAT-OFFER] Failed: result=%d (%s), consecutive=%d%n",
                        result, getOfferResultName(result), consecutiveOfferFailures);
                }
                if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                    System.err.println("[HEARTBEAT-OFFER] Connection lost, triggering reconnect");
                    needsReconnect = true;
                } else if (consecutiveOfferFailures >= MAX_OFFER_FAILURES) {
                    // Too many consecutive failures - force reconnect
                    System.err.println("[HEARTBEAT-OFFER] Too many consecutive failures, forcing reconnect");
                    needsReconnect = true;
                    consecutiveOfferFailures = 0;
                }
            } else {
                // Success - reset counter
                if (consecutiveOfferFailures > 0) {
                    System.out.printf("[HEARTBEAT-OFFER] Recovered after %d failures%n", consecutiveOfferFailures);
                }
                consecutiveOfferFailures = 0;
                // Log success periodically (every 100 successful offers)
                if (heartbeatCount % 100 == 0) {
                    System.out.printf("[HEARTBEAT-OFFER] Success: position=%d, count=%d%n", result, heartbeatCount);
                }
            }
        }
    }

    private String getOfferResultName(long result) {
        if (result == Publication.NOT_CONNECTED) return "NOT_CONNECTED";
        if (result == Publication.BACK_PRESSURED) return "BACK_PRESSURED";
        if (result == Publication.ADMIN_ACTION) return "ADMIN_ACTION";
        if (result == Publication.CLOSED) return "CLOSED";
        if (result == Publication.MAX_POSITION_EXCEEDED) return "MAX_POSITION_EXCEEDED";
        return "UNKNOWN(" + result + ")";
    }

    // ==================== Offer Methods for Sending Messages ====================

    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED && !needsReconnect && cluster != null && !cluster.isClosed();
    }

    /**
     * Offer raw buffer to cluster.
     */
    public void offer(DirectBuffer buffer, int offset, int length) {
        if (!isConnected()) {
            needsReconnect = true;
            throw new IllegalStateException("Cluster is not connected. Reconnecting...");
        }

        long result;
        int retryCount = 0;
        final int maxRetries = 3;
        final long retryDelayMs = 10;

        while ((result = cluster.offer(buffer, offset, length)) < 0) {
            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                needsReconnect = true;
                throw new IllegalStateException("Cluster connection lost. Reconnecting...");
            }

            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                if (++retryCount > maxRetries) {
                    throw new RuntimeException("Message rejected due to backpressure after " + maxRetries + " retries");
                }
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while handling backpressure", e);
                }
            } else {
                throw new RuntimeException("Failed to send message to cluster: " + result);
            }
        }
        }

    /**
     * Get buffer and encoder for creating orders.
     * Caller is responsible for encoding and then calling offer().
     */
    public ExpandableDirectByteBuffer getBuffer() {
        return buffer;
    }

    public MessageHeaderEncoder getHeaderEncoder() {
        return headerEncoder;
    }

    public CreateOrderEncoder getCreateOrderEncoder() {
        return createOrderEncoder;
    }

    // ==================== Egress Listener Implementation ====================

    // DEBUG: Message counter for egress
    private long egressMsgCount = 0;

    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer, int offset, int length, Header header) {
        egressMsgCount++;
        lastEgressTime = System.currentTimeMillis(); // Track last egress for stale detection
        final String response = buffer.getStringWithoutLengthUtf8(offset, length);

        // DEBUG: Log every 10th message or any message with trades/book
        if (egressMsgCount % 10 == 1 || response.contains("TRADES") || response.contains("BOOK")) {
            System.out.printf("[EGRESS-MSG] count=%d, len=%d, startsWithType=%s, hasListener=%s, preview=%.50s%n",
                egressMsgCount, length, response.startsWith("{\"type\":"), egressListener != null,
                response.length() > 50 ? response.substring(0, 50) : response);
        }

        // Check if this is market data (JSON with type field) and forward to listener
        if (response.startsWith("{\"type\":") && egressListener != null) {
            egressListener.onMessage(response);
        }
    }

    /**
     * CRITICAL: Handle session events from the cluster.
     * This is how we detect session errors and closures that require reconnection.
     * Per Aeron documentation: "if it loses the connection for whatever reason, it will not reconnect -
     * this needs to be done in application code."
     */
    @Override
    public void onSessionEvent(
            long correlationId,
            long clusterSessionId,
            long leadershipTermId,
            int leaderMemberId,
            EventCode code,
            String detail) {

        System.out.printf("Session event: code=%s, detail=%s, correlationId=%d, sessionId=%d, term=%d, leader=%d%n",
            code, detail, correlationId, clusterSessionId, leadershipTermId, leaderMemberId);

        // OK means successful connection or operation
        if (code == EventCode.OK) {
            lastEgressTime = System.currentTimeMillis();
            return;
        }

        // Any non-OK event means we need to reconnect
        // CLOSED: Session was closed by cluster
        // ERROR: An error occurred
        // REDIRECT: Being redirected to leader (handled automatically, but log it)
        System.out.println("Session event requires reconnection: " + code);
        needsReconnect = true;
        connectionState = ConnectionState.NOT_CONNECTED;
        if (clusterStatus != null) {
            clusterStatus.setGatewayConnected(false);
        }
    }

    @Override
    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId, String ingressEndpoints) {
        System.out.printf("New cluster leader: memberId=%d, termId=%d, endpoints=%s%n",
            leaderMemberId, leadershipTermId, ingressEndpoints);

        lastEgressTime = System.currentTimeMillis(); // Leader change is egress activity

        // Update cluster status
        if (clusterStatus != null) {
            clusterStatus.updateLeader(leaderMemberId, leadershipTermId);
        }

        // Notify listener
        if (egressListener != null) {
            egressListener.onNewLeader(leaderMemberId, leadershipTermId);
        }
    }

    @Override
    public void close() {
        stopHeartbeatThread();
        CloseHelper.quietClose(cluster);
        CloseHelper.quietClose(mediaDriver);
        cluster = null;
        mediaDriver = null;
    }
}
