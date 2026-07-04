// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.metrics;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Minimal Prometheus /metrics endpoint for a cluster node (match#33).
 *
 * JDK-built-in HTTP server on its own daemon thread — zero dependencies, zero
 * interaction with the cluster agent thread beyond reading plain-long counters
 * (visibility via {@link NodeMetrics#acquire()}). Counter suppliers are
 * registered at startup and evaluated per scrape.
 */
public final class NodeMetricsServer {

    private final NodeMetrics metrics;
    private final Map<String, LongSupplier> counters = new LinkedHashMap<>();
    private final Map<String, LongSupplier> gauges = new LinkedHashMap<>();
    private HttpServer server;

    public NodeMetricsServer(NodeMetrics metrics) {
        this.metrics = metrics;
    }

    /** Monotonic counter; name must end in _total per Prometheus convention. */
    public NodeMetricsServer counter(String name, String help, LongSupplier value) {
        counters.put(name + " " + help, value);
        return this;
    }

    public NodeMetricsServer gauge(String name, String help, LongSupplier value) {
        gauges.put(name + " " + help, value);
        return this;
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(runnable -> {
            Thread t = new Thread(runnable, "node-metrics-http");
            t.setDaemon(true);
            t.start();
        });
        server.start();
        System.out.println("METRICS: node /metrics on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String render() {
        metrics.acquire(); // volatile read: makes agent-thread plain writes visible
        StringBuilder sb = new StringBuilder(4096);

        for (Map.Entry<String, LongSupplier> e : counters.entrySet()) {
            appendSeries(sb, e.getKey(), "counter", e.getValue().getAsLong());
        }
        for (Map.Entry<String, LongSupplier> e : gauges.entrySet()) {
            appendSeries(sb, e.getKey(), "gauge", e.getValue().getAsLong());
        }

        sb.append("# HELP match_cluster_role Cluster role ordinal (0 follower, 1 candidate, 2 leader)\n");
        sb.append("# TYPE match_cluster_role gauge\n");
        sb.append("match_cluster_role ").append(metrics.role()).append('\n');
        sb.append("# HELP match_member_id Cluster member id\n");
        sb.append("# TYPE match_member_id gauge\n");
        sb.append("match_member_id ").append(metrics.memberId()).append('\n');

        long snapMs = metrics.lastSnapshotMs();
        sb.append("# HELP match_snapshot_age_seconds Seconds since the last cluster snapshot this node took (-1 = never)\n");
        sb.append("# TYPE match_snapshot_age_seconds gauge\n");
        sb.append("match_snapshot_age_seconds ")
          .append(snapMs == 0 ? -1 : (System.currentTimeMillis() - snapMs) / 1000)
          .append('\n');

        // Order-processing latency histogram (sampled 1-in-16 on the agent thread).
        sb.append("# HELP match_order_latency_seconds Order processing latency on the cluster service thread (sampled)\n");
        sb.append("# TYPE match_order_latency_seconds histogram\n");
        long cumulative = 0;
        for (int i = 0; i < NodeMetrics.BUCKETS - 1; i++) {
            cumulative += metrics.bucketCount(i);
            sb.append("match_order_latency_seconds_bucket{le=\"")
              .append(NodeMetrics.bucketUpperSeconds(i)).append("\"} ").append(cumulative).append('\n');
        }
        cumulative += metrics.bucketCount(NodeMetrics.BUCKETS - 1);
        sb.append("match_order_latency_seconds_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
        sb.append("match_order_latency_seconds_sum ").append(metrics.latencySumNanos() / 1e9).append('\n');
        sb.append("match_order_latency_seconds_count ").append(metrics.latencyCount()).append('\n');
        return sb.toString();
    }

    private static void appendSeries(StringBuilder sb, String nameAndHelp, String type, long value) {
        int space = nameAndHelp.indexOf(' ');
        String name = nameAndHelp.substring(0, space);
        String help = nameAndHelp.substring(space + 1);
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name).append(' ').append(value).append('\n');
    }
}
