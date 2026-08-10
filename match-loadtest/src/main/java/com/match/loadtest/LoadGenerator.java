// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import com.match.infrastructure.generated.*;
import io.aeron.samples.cluster.ClusterConfig;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;

import static com.match.infrastructure.InfrastructureConstants.SOCKET_BUFFER_LENGTH;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct Aeron Cluster load generator that bypasses HTTP layer.
 * Uses a single ingress thread with lock-free MPSC queue for thread-safe
 * cluster communication while maintaining ultra-low latency.
 */
public class LoadGenerator {

    private static final int QUEUE_CAPACITY = 64 * 1024; // 64K slots, must be power of 2
    private static final int MAX_DRAIN_PER_CYCLE_NORMAL = 64;
    private static final int MAX_DRAIN_PER_CYCLE_LOW_LATENCY = 8;  // Smaller batches = lower latency

    private final LoadConfig config;
    private final MetricsCollector metrics;
    /**
     * correlationId -> the order's SCHEDULED send slot on the fixed-rate timeline. Written by the
     * send path and read by the egress listener, both on this class's single duty thread, so no
     * synchronization is needed and a primitive map keeps it allocation-free. MISSING_VALUE means
     * "not ours or already matched" — a status for an unknown id is ignored rather than recorded,
     * so a stray message cannot invent a latency sample.
     *
     * <p>The slot, not the offer instant: see {@link OrderRequest#scheduledNs}. Storing the offer
     * instant subtracts out any time the order spent waiting for a busy generator or a full queue,
     * which is exactly the delay a saturated engine causes.</p>
     */
    private final Long2LongHashMap inFlight = new Long2LongHashMap(Long.MIN_VALUE);
    private final boolean ultraLowLatency;
    private final int warmupSeconds;
    /** --interval-log: once per second, one ILOG line per latency track (spike forensics). */
    private final boolean intervalLog;
    private final MediaDriver mediaDriver;
    private final AeronCluster cluster;
    private final ExecutorService executorService;
    private final List<OrderPublisher> publishers;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * False while the JIT warmup is running, true for the measured window. The producers watch it to
     * rebase their fixed-rate schedule exactly once, so warmup-era debt is not charged to the
     * measurement. Flipped by the duty thread, together with {@link MetricsCollector#reset()}.
     */
    private final AtomicBoolean measuring = new AtomicBoolean(false);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final ScheduledExecutorService metricsReporter;

    // Lock-free MPSC queue for order requests
    private final ManyToOneConcurrentArrayQueue<OrderRequest> orderQueue;

    // Single ingress/egress thread
    private Thread clusterDutyCycleThread;

    // UI
    private final LoadTestUI ui;
    private final boolean useUI;

    public LoadGenerator(LoadConfig config) throws Exception {
        this(config, true, false, 0, false);
    }

    public LoadGenerator(LoadConfig config, boolean useUI) throws Exception {
        this(config, useUI, false, 0, false);
    }

    public LoadGenerator(LoadConfig config, boolean useUI, boolean ultraLowLatency, int warmupSeconds,
                         boolean intervalLog) throws Exception {
        this.config = config;
        this.metrics = new MetricsCollector();
        this.orderQueue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        this.useUI = useUI;
        this.ultraLowLatency = ultraLowLatency;
        this.warmupSeconds = warmupSeconds;
        this.intervalLog = intervalLog;
        this.ui = useUI ? new LoadTestUI(config) : null;

        final boolean externalDriver = config.getAeronDir() != null;

        if (!useUI) {
            System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║              Aeron Cluster Load Generator Starting...                       ║");
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println(externalDriver
                ? "→ Attaching to external Media Driver: " + config.getAeronDir()
                : "→ Launching embedded Media Driver...");
        }

        final String aeronDirectoryName;
        if (externalDriver) {
            // Attach to a driver someone else runs (the bench rig's pinned busy-spin
            // aeronmd), so client-side driver noise stays out of the measurement. Not
            // launched here, so not configured here either: an external driver owns its
            // own threading, socket-buffer and lifecycle settings. mediaDriver stays
            // null and stop() already skips the close for exactly that case.
            this.mediaDriver = null;
            aeronDirectoryName = config.getAeronDir();
        } else {
            // Initialize Media Driver with ultra-low latency settings
            // Use unique directory to prevent conflicts with cluster nodes
            String loadTestAeronDir = "/dev/shm/aeron-loadtest-" + System.nanoTime();

            this.mediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                    .aeronDirectoryName(loadTestAeronDir)  // Unique dir to avoid conflicts
                    .threadingMode(ThreadingMode.SHARED)    // Single-threaded like gateway for reliable connectivity
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .socketSndbufLength(SOCKET_BUFFER_LENGTH)  // 4MB to match cluster
                    .socketRcvbufLength(SOCKET_BUFFER_LENGTH)  // 4MB to match cluster
                    .publicationLingerTimeoutNs(1_000_000_000L)  // 1s instead of 5s for faster cleanup
            );
            aeronDirectoryName = mediaDriver.aeronDirectoryName();
        }

        if (!useUI) {
            System.out.println(externalDriver ? "✓ Media Driver attached" : "✓ Media Driver launched");
            System.out.println("→ Connecting to Aeron Cluster...");
        }

        // Connect to Aeron Cluster
        final String ingressEndpoints = ClusterConfig.ingressEndpoints(
            config.getClusterHosts(),
            config.getBasePort(),
            ClusterConfig.CLIENT_FACING_PORT_OFFSET
        );

        final AeronCluster.Context clusterCtx = new AeronCluster.Context()
            .egressListener(new LoadTestEgressListener(metrics, inFlight))
            .egressChannel(config.getEgressChannel())
            .ingressChannel(config.getIngressChannel())
            .aeronDirectoryName(aeronDirectoryName)
            .ingressEndpoints(ingressEndpoints);

        this.cluster = AeronCluster.connect(clusterCtx);

        if (!useUI) {
            System.out.println("✓ Connected to cluster: " + ingressEndpoints);
            System.out.println();
        }

        // Create worker publishers
        this.publishers = new ArrayList<>();
        this.executorService = Executors.newFixedThreadPool(config.getWorkerThreads());

        // Create metrics reporter
        this.metricsReporter = Executors.newScheduledThreadPool(1);
    }

    /**
     * Get the order queue for publishers to enqueue orders
     */
    public ManyToOneConcurrentArrayQueue<OrderRequest> getOrderQueue() {
        return orderQueue;
    }

    /**
     * Start the load test
     */
    public void start() {
        if (running.getAndSet(true)) {
            System.out.println("Load test already running!");
            return;
        }

        // Create publishers (one per worker thread)
        for (int i = 0; i < config.getWorkerThreads(); i++) {
            OrderPublisher publisher = new OrderPublisher(
                i,
                orderQueue,
                config,
                metrics,
                messagesSent,
                running,
                measuring,
                !useUI  // Only print worker messages if not using UI
            );
            publishers.add(publisher);
        }

        // Start UI or print config
        if (useUI) {
            ui.start();
        } else {
            System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║                          Load Test Configuration                            ║");
            System.out.println("╠══════════════════════════════════════════════════════════════════════════════╣");
            System.out.printf("║ Target Rate:      %,10d orders/sec                                        ║%n", config.getTargetOrdersPerSecond());
            System.out.printf("║ Duration:         %,10d seconds                                            ║%n", config.getDurationSeconds());
            System.out.printf("║ Worker Threads:   %,10d                                                    ║%n", config.getWorkerThreads());
            System.out.printf("║ Market:           %-20s                                        ║%n", config.getMarket());
            System.out.printf("║ Scenario:         %-20s                                        ║%n", config.getScenario().getName());
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println("→ Starting load generation...");
            System.out.println();
        }

        // Start metrics reporting / UI updates
        // UI mode: 100ms for smooth animation, text mode: 2s for compact output
        int updateIntervalMs = useUI ? 100 : 2000;
        metricsReporter.scheduleAtFixedRate(
            this::updateMetrics,
            updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS
        );

        // Optional per-second ILOG lines for benchmark forensics (align latency spikes with
        // iostat/GC/bridge series). Same single-thread scheduler: both tasks are sub-ms, and
        // sharing the thread means an ILOG tick never interleaves mid-snapshot.
        if (intervalLog) {
            metrics.enableIntervalLogging();
            metricsReporter.scheduleAtFixedRate(
                metrics::printIntervalLog,
                1000, 1000, TimeUnit.MILLISECONDS
            );
        }

        // Start single cluster duty cycle thread
        clusterDutyCycleThread = new Thread(this::clusterDutyCycle, "cluster-duty-cycle");
        clusterDutyCycleThread.start();

        final long startTime = System.currentTimeMillis();
        final long endTime = startTime + (config.getDurationSeconds() * 1000L);

        // Submit all publisher tasks
        List<Future<?>> futures = new ArrayList<>();
        for (OrderPublisher publisher : publishers) {
            futures.add(executorService.submit(publisher));
        }

        // Wait for duration or completion
        try {
            for (Future<?> future : futures) {
                long remaining = endTime - System.currentTimeMillis();
                if (remaining > 0) {
                    future.get(remaining, TimeUnit.MILLISECONDS);
                }
            }
        } catch (TimeoutException e) {
            if (!useUI) {
                System.out.println("\n→ Test duration reached, stopping...");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!useUI) {
                System.out.println("\n→ Test interrupted, stopping...");
            }
        } catch (ExecutionException e) {
            System.err.println("\n✗ Publisher error: " + e.getCause().getMessage());
            e.printStackTrace();
        } finally {
            stop();
        }
    }

    private void updateMetrics() {
        if (!running.get()) return;

        long sent = messagesSent.get();
        long throughput = metrics.calculateThroughput(sent);
        long success = metrics.getSuccessCount();
        long failed = metrics.getFailureCount();
        long bp = metrics.getBackpressureCount();
        MetricsCollector.LatencyStats stats = metrics.getLatencyStats();

        if (useUI) {
            ui.updateStats(
                throughput, sent, success, failed, bp,
                stats.min, stats.p50, stats.p95, stats.p99, stats.max, stats.avg
            );
            ui.render();
        } else {
            // Legacy text output
            double successRate = (success + failed) > 0 ? (success * 100.0 / (success + failed)) : 100.0;
            System.out.printf(
                "│ %,8d msg/s │ %,10d sent │ %,10d success │ %,8d fails │ %,6d BP │ %6.2f%% │ p50: %6.1fμs │ p99: %6.1fμs │%n",
                throughput, sent, success, failed, bp, successRate,
                stats.p50 / 1000.0, stats.p99 / 1000.0
            );
        }
    }

    /**
     * Single-threaded duty cycle that handles:
     * 1. Draining orders from the queue and sending to cluster (ingress)
     * 2. Polling cluster responses (egress)
     * 3. Sending keepalives to maintain session
     */
    private void clusterDutyCycle() {
        // SBE encoders - only used by this thread
        final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(512);
        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();

        // Use busy-spin for lowest latency
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        // Keepalive timing
        long lastKeepAliveTimeNs = System.nanoTime();
        final long keepAliveIntervalNs = TimeUnit.MILLISECONDS.toNanos(250);

        // Batch drain limit - smaller batches = lower latency but more overhead
        final int maxDrainPerCycle = ultraLowLatency ? MAX_DRAIN_PER_CYCLE_LOW_LATENCY : MAX_DRAIN_PER_CYCLE_NORMAL;

        // Warmup phase - run without recording metrics
        if (warmupSeconds > 0) {
            System.out.printf("→ JIT Warmup phase: %d seconds (metrics disabled)...%n", warmupSeconds);
            final long warmupEndNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(warmupSeconds);
            while (running.get() && !cluster.isClosed() && System.nanoTime() < warmupEndNs) {
                cluster.pollEgress();
                OrderRequest order;
                int drainCount = 0;
                while (drainCount < maxDrainPerCycle && (order = orderQueue.poll()) != null) {
                    sendOrder(order, buffer, headerEncoder, createOrderEncoder);
                    messagesSent.incrementAndGet();
                    drainCount++;
                }
                final long nowNs = System.nanoTime();
                if (nowNs - lastKeepAliveTimeNs >= keepAliveIntervalNs) {
                    cluster.sendKeepAlive();
                    lastKeepAliveTimeNs = nowNs;
                }
                idleStrategy.idle(drainCount);
            }
            System.out.println("→ Warmup complete, starting measurement...");
            metrics.reset();  // Reset metrics after warmup
            messagesSent.set(0);
            // Nothing from the warmup may be matched against the measured window's histograms.
            // The warmup loop never populates the map, so this is belt and braces — and it stays,
            // because the listener's "an unknown id is ignored" guarantee is only as good as the
            // map being empty here.
            inFlight.clear();
        }
        // Everything scheduled before this instant belongs to the warmup, whenever it is finally
        // drained. Captured before the flip so no producer can rebase ahead of it. With no warmup
        // there is nothing to carry over, and a boundary here would race the producers' own start:
        // this thread is started first, so an early slot could fall the wrong side of it.
        final long measurementStartNs = warmupSeconds > 0 ? System.nanoTime() : Long.MIN_VALUE;
        // Producers rebase their schedule on this edge (or start already measuring when warmup is 0).
        measuring.set(true);
        long carriedOver = 0;

        while (running.get() && !cluster.isClosed()) {
            int workCount = 0;

            // 1. Poll egress (responses from cluster)
            workCount += cluster.pollEgress();

            // 2. Drain orders from queue and send to cluster
            OrderRequest order;
            int drainCount = 0;
            while (drainCount < maxDrainPerCycle && (order = orderQueue.poll()) != null) {
                boolean success = sendOrder(order, buffer, headerEncoder, createOrderEncoder);
                if (success) {
                    messagesSent.incrementAndGet();
                    if (order.scheduledNs < measurementStartNs) {
                        // A warmup-slot order still in the queue when the window opened. The engine
                        // does process it, so it counts toward throughput, but its latency is warmup
                        // debt: measured from its slot it would land in the tail as a millisecond
                        // outlier that no measured-window request ever experienced.
                        carriedOver++;
                    } else {
                        metrics.recordSuccess(System.nanoTime() - order.enqueueTimeNs);
                        // Same thread polls egress, so a plain primitive map is enough. The value is
                        // the order's SCHEDULED slot, not sentNs: anything measured from sentNs has
                        // already subtracted out the queueing delay a saturated engine causes.
                        inFlight.put(order.correlationId, order.scheduledNs);
                    }
                } else {
                    metrics.recordFailure();
                }
                drainCount++;
                workCount++;
            }

            // 3. Send keepalive periodically to maintain session
            final long nowNs = System.nanoTime();
            if (nowNs - lastKeepAliveTimeNs >= keepAliveIntervalNs) {
                cluster.sendKeepAlive();
                lastKeepAliveTimeNs = nowNs;
                workCount++;
            }

            idleStrategy.idle(workCount);
        }

        if (carriedOver > 0) {
            // Not hidden in a log line nobody greps: these orders count as sent but produced no
            // latency sample, so they are the difference between "sent" and full ack coverage.
            System.out.printf(
                "→ %,d warmup-slot orders drained inside the window (sent, not measured)%n",
                carriedOver
            );
        }
    }

    /**
     * Send an order to the cluster (called only from duty cycle thread)
     */
    private boolean sendOrder(
        OrderRequest order,
        ExpandableDirectByteBuffer buffer,
        MessageHeaderEncoder headerEncoder,
        CreateOrderEncoder createOrderEncoder
    ) {
        // Encode order using SBE - all primitives, zero allocation
        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

        createOrderEncoder.userId(order.userId);           // long
        // SBE v8: no totalPrice on the wire — a MARKET buy's budget rides in the price
        // field (LIMIT keeps its limit price; MARKET sells put 0 there, as before).
        createOrderEncoder.price("MARKET".equals(order.orderType)
            ? order.totalPrice : order.price);             // long (fixed-point)
        createOrderEncoder.quantity(order.quantity);       // long (fixed-point)
        createOrderEncoder.marketId(order.marketId);       // int
        createOrderEncoder.orderType(toOrderType(order.orderType));
        createOrderEncoder.orderSide(toOrderSide(order.orderSide));
        // The correlation key for the round trip. OrderRequest has carried a correlationId field
        // labelled "Unique ID for round-trip latency tracking" all along; it was simply never put on
        // the wire, so nothing could match a status back to the order that caused it.
        createOrderEncoder.omsOrderId(order.correlationId);

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + createOrderEncoder.encodedLength();

        // Try to send with limited retries
        for (int retry = 0; retry < config.getMaxRetries(); retry++) {
            long result = cluster.offer(buffer, 0, length);

            if (result > 0) {
                return true;
            }

            if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED) {
                return false;
            }

            if (result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION) {
                metrics.recordBackpressure();
                // Busy spin instead of yield - avoids context switch overhead
                Thread.onSpinWait();
            }
        }

        metrics.recordTimeout();
        return false;
    }

    private OrderType toOrderType(String type) {
        switch (type) {
            case "LIMIT":
                return OrderType.LIMIT;
            case "MARKET":
                return OrderType.MARKET;
            case "LIMIT_MAKER":
                return OrderType.LIMIT_MAKER;
            default:
                return OrderType.LIMIT;
        }
    }

    private OrderSide toOrderSide(String side) {
        return "BID".equals(side) ? OrderSide.BID : OrderSide.ASK;
    }

    /**
     * Stop the load test
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        // Stop publishers
        for (OrderPublisher publisher : publishers) {
            publisher.stop();
        }

        // Shutdown executor
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Wait for duty cycle thread
        if (clusterDutyCycleThread != null) {
            try {
                clusterDutyCycleThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Stop metrics reporter
        metricsReporter.shutdown();
        try {
            metricsReporter.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Print final report
        long duration = System.currentTimeMillis() - metrics.getStartTime();
        long sent = messagesSent.get();
        long success = metrics.getSuccessCount();
        long failed = metrics.getFailureCount();
        long bp = metrics.getBackpressureCount();
        long timeouts = metrics.getTimeoutCount();
        MetricsCollector.LatencyStats stats = metrics.getLatencyStats();

        if (useUI) {
            ui.stop();
            ui.printFinalReport(
                duration, sent, success, failed, bp, timeouts,
                stats.min, stats.p50, stats.p95, stats.p99, stats.max, stats.avg
            );
        } else {
            System.out.println();
            System.out.println("→ Shutting down load generator...");
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║                              Final Results                                   ║");
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            metrics.printFinalReport(sent);
        }

        // Close cluster connection
        if (cluster != null && !cluster.isClosed()) {
            cluster.close();
        }

        // Close media driver
        if (mediaDriver != null) {
            mediaDriver.close();
        }

        if (!useUI) {
            System.out.println();
            System.out.println("✓ Load generator stopped");
        }
    }

    public static void main(String[] args) {
        try {
            LoadConfig config = parseArgs(args);
            boolean useUI = !hasFlag(args, "--no-ui");
            boolean ultraLowLatency = hasFlag(args, "--ultra");
            int warmupSeconds = getIntFlag(args, "--warmup", 0);
            boolean intervalLog = hasFlag(args, "--interval-log");

            // Ultra-low latency mode implies single thread for minimum contention
            if (ultraLowLatency && config.getWorkerThreads() > 1) {
                System.out.println("→ Ultra-low latency mode: using 1 worker thread");
                config = LoadConfig.builder()
                    .targetOrdersPerSecond(config.getTargetOrdersPerSecond())
                    .durationSeconds(config.getDurationSeconds())
                    .workerThreads(1)  // Single thread for ultra-low latency
                    .scenario(config.getScenario())
                    .clusterHosts(config.getClusterHosts())
                    .aeronDir(config.getAeronDir())  // --ultra must not silently bring the embedded driver back
                    .build();
            }

            LoadGenerator generator = new LoadGenerator(config, useUI, ultraLowLatency, warmupSeconds, intervalLog);

            Runtime.getRuntime().addShutdownHook(new Thread(generator::stop));

            generator.start();

        } catch (Exception e) {
            System.err.println("✗ Failed to start load generator: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static int getIntFlag(String[] args, String flag, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return defaultValue;
    }

    // Package-private so the arg-parsing tests can exercise flag -> config directly.
    static LoadConfig parseArgs(String[] args) {
        LoadConfig.Builder builder = LoadConfig.builder();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--rate":
                case "-r":
                    builder.targetOrdersPerSecond(Integer.parseInt(args[++i]));
                    break;
                case "--duration":
                case "-d":
                    builder.durationSeconds(Integer.parseInt(args[++i]));
                    break;
                case "--threads":
                case "-t":
                    builder.workerThreads(Integer.parseInt(args[++i]));
                    break;
                case "--scenario":
                case "-s":
                    builder.scenario(OrderScenario.valueOf(args[++i].toUpperCase()));
                    break;
                case "--hosts":
                case "-h":
                    builder.clusterHosts(List.of(args[++i].split(",")));
                    break;
                case "--aeron-dir":
                    builder.aeronDir(args[++i]);
                    break;
                case "--no-ui":
                case "--ultra":
                case "--interval-log":
                    // Handled separately
                    break;
                case "--warmup":
                    i++;  // Skip value, handled separately
                    break;
                case "--help":
                    printUsage();
                    System.exit(0);
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        return builder.build();
    }

    private static void printUsage() {
        System.out.println("Usage: LoadGenerator [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -r, --rate <n>        Target orders per second (default: 1000)");
        System.out.println("  -d, --duration <n>    Total run seconds INCLUDING warmup (default: 60; measured window = duration - warmup)");
        System.out.println("  -t, --threads <n>     Number of worker threads (default: 4)");
        System.out.println("  -s, --scenario <name> Scenario: BALANCED, MARKET_MAKER, AGGRESSIVE, SPIKE (default: BALANCED)");
        System.out.println("  -h, --hosts <list>    Cluster hosts comma-separated");
        System.out.println("  --aeron-dir <dir>     Attach to an external Aeron media driver at <dir> instead of launching an embedded one");
        System.out.println("  --no-ui               Disable interactive UI (use text output)");
        System.out.println("  --ultra               Ultra-low latency mode (single thread, small batches)");
        System.out.println("  --warmup <n>          JIT warmup seconds at the START of --duration, metrics discarded (default: 0)");
        System.out.println("  --interval-log        Print one ILOG line per latency track every second (that second's values, for spike forensics)");
        System.out.println("  --help                Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  LoadGenerator -r 5000 -d 120 -t 8 -s MARKET_MAKER");
        System.out.println("  LoadGenerator --rate 100000 --duration 60 --ultra --warmup 15");
    }
}
