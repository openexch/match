package com.match.loadtest;

import com.match.infrastructure.generated.*;
import com.match.infrastructure.persistence.ClusterConfig;
import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.ExpandableDirectByteBuffer;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;

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
    private final boolean ultraLowLatency;
    private final int warmupSeconds;
    private final MediaDriver mediaDriver;
    private final AeronCluster cluster;
    private final ExecutorService executorService;
    private final List<OrderPublisher> publishers;
    private final AtomicBoolean running = new AtomicBoolean(false);
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
        this(config, true, false, 0);
    }

    public LoadGenerator(LoadConfig config, boolean useUI) throws Exception {
        this(config, useUI, false, 0);
    }

    public LoadGenerator(LoadConfig config, boolean useUI, boolean ultraLowLatency, int warmupSeconds) throws Exception {
        this.config = config;
        this.metrics = new MetricsCollector();
        this.orderQueue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        this.useUI = useUI;
        this.ultraLowLatency = ultraLowLatency;
        this.warmupSeconds = warmupSeconds;
        this.ui = useUI ? new LoadTestUI(config) : null;

        if (!useUI) {
            System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
            System.out.println("║              Aeron Cluster Load Generator Starting...                       ║");
            System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println("→ Launching embedded Media Driver...");
        }

        // Initialize Media Driver with ultra-low latency settings
        this.mediaDriver = MediaDriver.launchEmbedded(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.DEDICATED)  // Separate threads for sender/receiver/conductor
                .conductorIdleStrategy(new BusySpinIdleStrategy())
                .senderIdleStrategy(new BusySpinIdleStrategy())
                .receiverIdleStrategy(new BusySpinIdleStrategy())
                .dirDeleteOnStart(true)
                .dirDeleteOnShutdown(true)
                .socketSndbufLength(2 * 1024 * 1024)
                .socketRcvbufLength(2 * 1024 * 1024)
                .publicationLingerTimeoutNs(5_000_000_000L)
        );

        if (!useUI) {
            System.out.println("✓ Media Driver launched");
            System.out.println("→ Connecting to Aeron Cluster...");
        }

        // Connect to Aeron Cluster
        final String ingressEndpoints = ClusterConfig.ingressEndpoints(
            config.getClusterHosts(),
            config.getBasePort(),
            ClusterConfig.CLIENT_FACING_PORT_OFFSET
        );

        final AeronCluster.Context clusterCtx = new AeronCluster.Context()
            .egressListener(new LoadTestEgressListener(metrics))
            .egressChannel(config.getEgressChannel())
            .ingressChannel(config.getIngressChannel())
            .aeronDirectoryName(mediaDriver.aeronDirectoryName())
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
        metricsReporter.scheduleAtFixedRate(
            this::updateMetrics,
            100, 100, TimeUnit.MILLISECONDS  // Update every 100ms for smooth UI
        );

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
        }

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
                    long latency = System.nanoTime() - order.enqueueTimeNs;
                    metrics.recordSuccess(latency);
                    messagesSent.incrementAndGet();
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
        createOrderEncoder.price(order.price);             // long (fixed-point)
        createOrderEncoder.quantity(order.quantity);       // long (fixed-point)
        createOrderEncoder.totalPrice(order.totalPrice);   // long (fixed-point)
        createOrderEncoder.marketId(order.marketId);       // int
        createOrderEncoder.orderType(toOrderType(order.orderType));
        createOrderEncoder.orderSide(toOrderSide(order.orderSide));

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

            // Ultra-low latency mode implies single thread for minimum contention
            if (ultraLowLatency && config.getWorkerThreads() > 1) {
                System.out.println("→ Ultra-low latency mode: using 1 worker thread");
                config = LoadConfig.builder()
                    .targetOrdersPerSecond(config.getTargetOrdersPerSecond())
                    .durationSeconds(config.getDurationSeconds())
                    .workerThreads(1)  // Single thread for ultra-low latency
                    .scenario(config.getScenario())
                    .clusterHosts(config.getClusterHosts())
                    .build();
            }

            LoadGenerator generator = new LoadGenerator(config, useUI, ultraLowLatency, warmupSeconds);

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

    private static LoadConfig parseArgs(String[] args) {
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
                case "--no-ui":
                case "--ultra":
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
        System.out.println("  -d, --duration <n>    Test duration in seconds (default: 60)");
        System.out.println("  -t, --threads <n>     Number of worker threads (default: 4)");
        System.out.println("  -s, --scenario <name> Scenario: BALANCED, MARKET_MAKER, AGGRESSIVE, SPIKE (default: BALANCED)");
        System.out.println("  -h, --hosts <list>    Cluster hosts comma-separated");
        System.out.println("  --no-ui               Disable interactive UI (use text output)");
        System.out.println("  --ultra               Ultra-low latency mode (single thread, small batches)");
        System.out.println("  --warmup <n>          JIT warmup seconds before measurement (default: 0)");
        System.out.println("  --help                Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  LoadGenerator -r 5000 -d 120 -t 8 -s MARKET_MAKER");
        System.out.println("  LoadGenerator --rate 100000 --duration 60 --ultra --warmup 15");
    }
}
