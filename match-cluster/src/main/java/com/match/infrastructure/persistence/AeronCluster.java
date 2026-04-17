package com.match.infrastructure.persistence;

import com.match.infrastructure.Logger;
import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.agrona.concurrent.SystemEpochClock;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.match.infrastructure.InfrastructureConstants.SESSION_TIMEOUT_NS;
import static java.lang.Integer.parseInt;

public class AeronCluster {
    private static final Logger log = Logger.getLogger(AeronCluster.class);
    /** Counts errors surfaced by the Aeron error handler. Exposed for monitoring. */
    public static final AtomicLong AERON_ERROR_COUNT = new AtomicLong();

    public AeronCluster() {
        final ShutdownSignalBarrier barrier = new ShutdownSignalBarrier();
        final int portBase = getBasePort();
        final int nodeId = getClusterNode();
        final String hosts = getClusterAddresses();

        final List<String> hostAddresses = List.of(hosts.split(","));
        final ClusterConfig clusterConfig = ClusterConfig.create(nodeId, hostAddresses, hostAddresses, portBase,
                new AppClusteredService());

        clusterConfig.baseDir(getBaseDir(nodeId));

        // Route Aeron driver/archive/consensus/service errors to the application logger so
        // operators see them in node{N}.log instead of just the cluster mark file. Without
        // this, a misconfigured channel or back-pressure storm is silent.
        final ErrorHandler errorHandler = (Throwable t) -> {
            AERON_ERROR_COUNT.incrementAndGet();
            final StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            log.error("Aeron error #%d: %s", AERON_ERROR_COUNT.get(), sw.toString());
        };
        clusterConfig.errorHandler(errorHandler);

        // ==================== ULTRA-LOW LATENCY CHANNEL CONFIG ====================
        // Large term buffers for high throughput without fragmentation.
        // WARNING: mtu=8k requires path MTU >= 8192. Loopback (lo, 65536) and jumbo-frame
        // networks (MTU 9000) are fine. Standard Ethernet (MTU 1500) will IP-fragment every
        // packet > 1408 bytes, dramatically increasing loss and triggering NAK storms. Before
        // moving cluster nodes off 127.0.0.1, either enable jumbo frames end-to-end or drop
        // mtu to 1408 (Aeron default). Same warning applies to the MTU set on the MediaDriver
        // below and to the OMS / market-gateway client ingress channels.
        clusterConfig.consensusModuleContext().ingressChannel("aeron:udp?term-length=16m|mtu=8k");
        clusterConfig.consensusModuleContext().egressChannel("aeron:udp?term-length=16m|mtu=8k");

        // ==================== FAST LEADER ELECTION CONFIG ====================
        // Single source of truth for cluster timing. Aeron defaults are: heartbeat-interval=200ms,
        // heartbeat-timeout=10s, election-timeout=1s, startup-canvass=60s, termination=10s.
        // We tighten heartbeat/canvass/termination for sub-second failover; election-timeout stays
        // at the default 1s. JVM uses ZGC so safepoint pauses fit well under the 1s heartbeat
        // timeout. systemd force-kills at 5s, so termination must complete in <5s.
        clusterConfig.consensusModuleContext()
            .leaderHeartbeatIntervalNs(TimeUnit.MILLISECONDS.toNanos(100))   // 100ms
            .leaderHeartbeatTimeoutNs(TimeUnit.SECONDS.toNanos(1))           // 1s (10x interval)
            .electionTimeoutNs(TimeUnit.SECONDS.toNanos(1))                  // 1s (Aeron default)
            .startupCanvassTimeoutNs(TimeUnit.SECONDS.toNanos(2))            // 2s
            .terminationTimeoutNs(TimeUnit.SECONDS.toNanos(2))               // 2s (systemd kills at 5s)
            .sessionTimeoutNs(SESSION_TIMEOUT_NS)                            // 10s
            ;
        //await DNS resolution of all the hostnames
        hostAddresses.forEach(AeronCluster::awaitDnsResolution);

        try (
                ClusteredMediaDriver ignored = ClusteredMediaDriver.launch(
                        clusterConfig.mediaDriverContext()
                            // Directory management
                            .dirDeleteOnStart(true)
                            .dirDeleteOnShutdown(true)
                            // Timeouts
                            .driverTimeoutMs(500)
                            .timerIntervalNs(TimeUnit.MICROSECONDS.toNanos(500))  // 500us for faster polling
                            .clientLivenessTimeoutNs(TimeUnit.SECONDS.toNanos(5))
                            .publicationUnblockTimeoutNs(TimeUnit.SECONDS.toNanos(10))
                            .untetheredWindowLimitTimeoutNs(TimeUnit.MILLISECONDS.toNanos(100))
                            .untetheredRestingTimeoutNs(TimeUnit.MILLISECONDS.toNanos(100))
                            // Socket buffers - 4MB for high throughput (requires tuned OS)
                            .socketSndbufLength(4 * 1024 * 1024)
                            .socketRcvbufLength(4 * 1024 * 1024)
                            // Initial window - 4MB for high throughput
                            .initialWindowLength(4 * 1024 * 1024)
                            // IPC term buffer for inter-process communication
                            .ipcTermBufferLength(16 * 1024 * 1024)  // 16MB IPC buffer
                            .publicationTermBufferLength(16 * 1024 * 1024) // 16MB pub buffer
                            // MTU for reduced system calls
                            .mtuLength(8192),  // 8KB MTU
                        clusterConfig.archiveContext(),
                        clusterConfig.consensusModuleContext().shutdownSignalBarrier(barrier));
                ClusteredServiceContainer ignored1 = ClusteredServiceContainer.launch(
                        clusterConfig.clusteredServiceContext().shutdownSignalBarrier(barrier)))
        {
            barrier.await();
        } catch (Exception e) {
            System.err.println("FATAL: Cluster node failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /***
     * Get the base directory for the cluster configuration
     * @param nodeId node id
     * @return base directory
     */
    private static File getBaseDir(final int nodeId)
    {
        final String baseDir = System.getenv("BASE_DIR");
        if (null == baseDir || baseDir.isEmpty())
        {
            return new File(System.getProperty("user.dir"), "node" + nodeId);
        }

        return new File(baseDir);
    }

    /**
     * Read the cluster addresses from the environment variable CLUSTER_ADDRESSES or the
     * system property cluster. Addresses
     * @return cluster addresses
     */
    private static String getClusterAddresses()
    {
        String clusterAddresses = System.getenv("CLUSTER_ADDRESSES");
        if (null == clusterAddresses || clusterAddresses.isEmpty())
        {
            clusterAddresses = System.getProperty("cluster.addresses", "localhost");
        }
        return clusterAddresses;
    }

    /**
     * Get the cluster node id
     * @return cluster node id, default 0
     */
    private static int getClusterNode()
    {
        String clusterNode = System.getenv("CLUSTER_NODE");
        if (null == clusterNode || clusterNode.isEmpty())
        {
            clusterNode = System.getProperty("node.id", "0");
        }
        return parseInt(clusterNode);
    }

    /**
     * Get the base port for the cluster configuration
     * @return base port, default 9000
     */
    private static int getBasePort()
    {
        String portBaseString = System.getenv("CLUSTER_PORT_BASE");
        if (null == portBaseString || portBaseString.isEmpty())
        {
            portBaseString = System.getProperty("port.base", "9000");
        }
        return parseInt(portBaseString);
    }
    /**
     * Await DNS resolution of the given host. Under Kubernetes, this can take a while.
     * @param host of the node to resolve
     */
    private static void awaitDnsResolution(final String host)
    {
        if (applyDnsDelay())
        {
            quietSleep(5000);
        }

        final long endTime = SystemEpochClock.INSTANCE.time() + 60000;
        java.security.Security.setProperty("networkaddress.cache.ttl", "0");

        boolean resolved = false;
        while (!resolved)
        {
            if (SystemEpochClock.INSTANCE.time() > endTime)
            {
                throw new RuntimeException("FATAL: Cannot resolve DNS name '" + host + "' after 60s timeout");
            }

            try
            {
                InetAddress.getByName(host);
                resolved = true;
            }
            catch (final UnknownHostException e)
            {
                quietSleep(3000);
            }
        }
    }

    /**
     * Sleeps for the given number of milliseconds, ignoring any interrupts.
     *
     * @param millis the number of milliseconds to sleep.
     */
    private static void quietSleep(final long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (final InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Apply DNS delay
     * @return true if DNS delay should be applied
     */
    private static boolean applyDnsDelay()
    {
        final String dnsDelay = System.getenv("DNS_DELAY");
        if (null == dnsDelay || dnsDelay.isEmpty())
        {
            return false;
        }
        return Boolean.parseBoolean(dnsDelay);
    }
} 