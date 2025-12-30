package com.match.loadtest;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terminal UI for load test visualization with real-time updates.
 * Uses ANSI escape codes for colors and cursor control.
 */
public class LoadTestUI {

    // ANSI escape codes
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    // Colors
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";
    private static final String RED = "\u001B[31m";
    private static final String MAGENTA = "\u001B[35m";

    // Background colors
    private static final String BG_GREEN = "\u001B[42m";
    private static final String BG_RED = "\u001B[41m";
    private static final String BG_BLUE = "\u001B[44m";

    // Cursor control
    private static final String CLEAR_SCREEN = "\u001B[2J";
    private static final String HOME = "\u001B[H";
    private static final String CLEAR_LINE = "\u001B[2K";
    private static final String HIDE_CURSOR = "\u001B[?25l";
    private static final String SHOW_CURSOR = "\u001B[?25h";

    // Box drawing characters
    private static final String TOP_LEFT = "┌";
    private static final String TOP_RIGHT = "┐";
    private static final String BOTTOM_LEFT = "└";
    private static final String BOTTOM_RIGHT = "┘";
    private static final String HORIZONTAL = "─";
    private static final String VERTICAL = "│";
    private static final String T_DOWN = "┬";
    private static final String T_UP = "┴";
    private static final String T_RIGHT = "├";
    private static final String T_LEFT = "┤";
    private static final String CROSS = "┼";

    private final PrintStream out;
    private final LoadConfig config;
    private final long startTimeMs;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Stats
    private volatile long currentThroughput = 0;
    private volatile long totalSent = 0;
    private volatile long totalSuccess = 0;
    private volatile long totalFailed = 0;
    private volatile long backpressure = 0;
    private volatile double successRate = 100.0;

    // Latencies in nanoseconds
    private volatile long latencyMin = 0;
    private volatile long latencyP50 = 0;
    private volatile long latencyP95 = 0;
    private volatile long latencyP99 = 0;
    private volatile long latencyMax = 0;
    private volatile long latencyAvg = 0;

    public LoadTestUI(LoadConfig config) {
        this.out = System.out;
        this.config = config;
        this.startTimeMs = System.currentTimeMillis();
    }

    public void start() {
        out.print(HIDE_CURSOR);
        out.print(CLEAR_SCREEN);
        out.print(HOME);
        out.flush();
    }

    public void stop() {
        running.set(false);
        out.print(SHOW_CURSOR);
        out.flush();
    }

    public void updateStats(
        long throughput,
        long sent,
        long success,
        long failed,
        long bp,
        long minNs, long p50Ns, long p95Ns, long p99Ns, long maxNs, long avgNs
    ) {
        this.currentThroughput = throughput;
        this.totalSent = sent;
        this.totalSuccess = success;
        this.totalFailed = failed;
        this.backpressure = bp;
        this.successRate = (success + failed) > 0 ? (success * 100.0 / (success + failed)) : 100.0;

        this.latencyMin = minNs;
        this.latencyP50 = p50Ns;
        this.latencyP95 = p95Ns;
        this.latencyP99 = p99Ns;
        this.latencyMax = maxNs;
        this.latencyAvg = avgNs;
    }

    public void render() {
        if (!running.get()) return;

        StringBuilder sb = new StringBuilder();
        sb.append(HOME);

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        int elapsedSec = (int) (elapsedMs / 1000);
        int remainingSec = Math.max(0, config.getDurationSeconds() - elapsedSec);
        double progress = Math.min(100.0, (elapsedMs * 100.0) / (config.getDurationSeconds() * 1000.0));

        // Header
        sb.append(CYAN).append(BOLD);
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║").append(RESET).append(CYAN);
        sb.append("          ⚡ AERON CLUSTER LOAD TEST ⚡                                       ");
        sb.append(BOLD).append("║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
        sb.append(RESET);

        // Configuration section
        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" CONFIGURATION\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);
        sb.append(String.format(" Target Rate: %s%,d msg/s%s", YELLOW, config.getTargetOrdersPerSecond(), RESET));
        sb.append(String.format("    Duration: %s%ds%s", YELLOW, config.getDurationSeconds(), RESET));
        sb.append(String.format("    Threads: %s%d%s", YELLOW, config.getWorkerThreads(), RESET));
        sb.append(String.format("    Scenario: %s%s%s\n", YELLOW, config.getScenario().name(), RESET));

        // Progress bar
        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" PROGRESS\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);
        sb.append(" ");
        int barWidth = 50;
        int filled = (int) (barWidth * progress / 100);
        sb.append(GREEN).append("█".repeat(Math.max(0, filled)));
        sb.append(DIM).append("░".repeat(Math.max(0, barWidth - filled)));
        sb.append(RESET);
        sb.append(String.format(" %s%.1f%%%s  [%02d:%02d / %02d:%02d]\n",
            WHITE, progress, RESET,
            elapsedSec / 60, elapsedSec % 60,
            config.getDurationSeconds() / 60, config.getDurationSeconds() % 60));

        // Live stats
        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" LIVE METRICS\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);

        // Throughput gauge
        String throughputColor = currentThroughput >= config.getTargetOrdersPerSecond() * 0.95 ? GREEN :
                                 currentThroughput >= config.getTargetOrdersPerSecond() * 0.8 ? YELLOW : RED;
        sb.append(String.format(" Throughput:    %s%s%,10d msg/s%s", BOLD, throughputColor, currentThroughput, RESET));

        // Success rate with color
        String rateColor = successRate >= 99.9 ? GREEN : successRate >= 95 ? YELLOW : RED;
        sb.append(String.format("        Success Rate: %s%s%6.2f%%%s\n", BOLD, rateColor, successRate, RESET));

        // Counters
        sb.append(String.format(" Sent:          %s%,14d%s", CYAN, totalSent, RESET));
        sb.append(String.format("        Success:      %s%,14d%s\n", GREEN, totalSuccess, RESET));
        sb.append(String.format(" Failed:        %s%,14d%s", RED, totalFailed, RESET));
        sb.append(String.format("        Backpressure: %s%,14d%s\n", YELLOW, backpressure, RESET));

        // Latency section
        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" LATENCY (μs)\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);

        // Latency visualization
        sb.append(" ┌──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐\n");
        sb.append(" │").append(BOLD).append("   MIN    ").append(RESET);
        sb.append("│").append(BOLD).append("   P50    ").append(RESET);
        sb.append("│").append(BOLD).append("   P95    ").append(RESET);
        sb.append("│").append(BOLD).append("   P99    ").append(RESET);
        sb.append("│").append(BOLD).append("   MAX    ").append(RESET);
        sb.append("│").append(BOLD).append("   AVG    ").append(RESET);
        sb.append("│\n");
        sb.append(" ├──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤\n");

        // Values in microseconds
        sb.append(" │").append(GREEN).append(String.format("%8.1f  ", latencyMin / 1000.0)).append(RESET);
        sb.append("│").append(GREEN).append(String.format("%8.1f  ", latencyP50 / 1000.0)).append(RESET);
        sb.append("│").append(YELLOW).append(String.format("%8.1f  ", latencyP95 / 1000.0)).append(RESET);
        sb.append("│").append(latencyP99 / 1000.0 > 100 ? RED : YELLOW).append(String.format("%8.1f  ", latencyP99 / 1000.0)).append(RESET);
        sb.append("│").append(latencyMax / 1000.0 > 500 ? RED : YELLOW).append(String.format("%8.1f  ", latencyMax / 1000.0)).append(RESET);
        sb.append("│").append(CYAN).append(String.format("%8.1f  ", latencyAvg / 1000.0)).append(RESET);
        sb.append("│\n");
        sb.append(" └──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘\n");

        // Latency bar visualization
        sb.append("\n");
        sb.append(" Latency Distribution:\n");
        sb.append(" ").append(DIM).append("0μs").append(RESET);
        sb.append("      ");
        renderLatencyBar(sb, latencyP50, latencyP95, latencyP99, latencyMax);
        sb.append("  ").append(DIM).append(String.format("%.0fμs", latencyMax / 1000.0)).append(RESET);
        sb.append("\n");
        sb.append("        ").append(GREEN).append("▲p50").append(RESET);
        sb.append("        ").append(YELLOW).append("▲p95").append(RESET);
        sb.append("      ").append(RED).append("▲p99").append(RESET);
        sb.append("\n");

        // Footer
        sb.append("\n");
        sb.append(DIM).append(" Press Ctrl+C to stop the test").append(RESET);
        sb.append("\n");

        out.print(sb);
        out.flush();
    }

    private void renderLatencyBar(StringBuilder sb, long p50, long p95, long p99, long max) {
        int barWidth = 50;
        if (max == 0) max = 1; // Avoid division by zero

        int p50Pos = (int) Math.min(barWidth - 1, (p50 * barWidth) / max);
        int p95Pos = (int) Math.min(barWidth - 1, (p95 * barWidth) / max);
        int p99Pos = (int) Math.min(barWidth - 1, (p99 * barWidth) / max);

        for (int i = 0; i < barWidth; i++) {
            if (i <= p50Pos) {
                sb.append(GREEN).append("█");
            } else if (i <= p95Pos) {
                sb.append(YELLOW).append("▓");
            } else if (i <= p99Pos) {
                sb.append(RED).append("▒");
            } else {
                sb.append(DIM).append("░");
            }
        }
        sb.append(RESET);
    }

    public void printFinalReport(
        long durationMs,
        long sent,
        long success,
        long failed,
        long bp,
        long timeouts,
        long minNs, long p50Ns, long p95Ns, long p99Ns, long maxNs, long avgNs
    ) {
        out.print(CLEAR_SCREEN);
        out.print(HOME);
        out.print(SHOW_CURSOR);

        double avgThroughput = durationMs > 0 ? (sent * 1000.0 / durationMs) : 0;
        double rate = (success + failed) > 0 ? (success * 100.0 / (success + failed)) : 100.0;

        StringBuilder sb = new StringBuilder();

        sb.append(CYAN).append(BOLD);
        sb.append("╔══════════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║              ⚡ LOAD TEST COMPLETE ⚡                                        ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════════╝\n");
        sb.append(RESET);

        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" SUMMARY\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);

        sb.append(String.format(" Duration:           %s%,10d ms%s  (%.1f seconds)\n", CYAN, durationMs, RESET, durationMs / 1000.0));
        sb.append(String.format(" Messages Sent:      %s%,10d%s\n", CYAN, sent, RESET));
        sb.append(String.format(" Successful:         %s%,10d%s  ", GREEN, success, RESET));
        String rateColor = rate >= 99.9 ? GREEN : rate >= 95 ? YELLOW : RED;
        sb.append(String.format("(%s%.2f%%%s)\n", rateColor, rate, RESET));
        sb.append(String.format(" Failed:             %s%,10d%s\n", failed > 0 ? RED : GREEN, failed, RESET));
        sb.append(String.format(" Backpressure:       %s%,10d%s\n", bp > 100 ? YELLOW : GREEN, bp, RESET));
        sb.append(String.format(" Timeouts:           %s%,10d%s\n", timeouts > 0 ? RED : GREEN, timeouts, RESET));
        sb.append(String.format(" Avg Throughput:     %s%,10.2f msg/s%s\n", YELLOW, avgThroughput, RESET));

        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" LATENCY DISTRIBUTION (μs)\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);

        sb.append(" ┌────────────────┬────────────────┐\n");
        sb.append(String.format(" │ %sMin%s            │ %s%,12.2f μs%s │\n", BOLD, RESET, GREEN, minNs / 1000.0, RESET));
        sb.append(String.format(" │ %sP50 (median)%s   │ %s%,12.2f μs%s │\n", BOLD, RESET, GREEN, p50Ns / 1000.0, RESET));
        sb.append(String.format(" │ %sP95%s            │ %s%,12.2f μs%s │\n", BOLD, RESET, YELLOW, p95Ns / 1000.0, RESET));
        sb.append(String.format(" │ %sP99%s            │ %s%,12.2f μs%s │\n", BOLD, RESET, p99Ns / 1000.0 > 100 ? RED : YELLOW, p99Ns / 1000.0, RESET));
        sb.append(String.format(" │ %sMax%s            │ %s%,12.2f μs%s │\n", BOLD, RESET, maxNs / 1000.0 > 500 ? RED : YELLOW, maxNs / 1000.0, RESET));
        sb.append(String.format(" │ %sAverage%s        │ %s%,12.2f μs%s │\n", BOLD, RESET, CYAN, avgNs / 1000.0, RESET));
        sb.append(" └────────────────┴────────────────┘\n");

        // Performance rating
        sb.append("\n");
        sb.append(WHITE).append(BOLD).append(" PERFORMANCE RATING\n").append(RESET);
        sb.append(DIM).append(" ─────────────────────────────────────────────────────────────────────────────\n").append(RESET);

        String rating;
        String ratingColor;
        if (rate >= 99.9 && p99Ns / 1000.0 < 100) {
            rating = "★★★★★ EXCELLENT";
            ratingColor = GREEN;
        } else if (rate >= 99 && p99Ns / 1000.0 < 500) {
            rating = "★★★★☆ VERY GOOD";
            ratingColor = GREEN;
        } else if (rate >= 95 && p99Ns / 1000.0 < 1000) {
            rating = "★★★☆☆ GOOD";
            ratingColor = YELLOW;
        } else if (rate >= 90) {
            rating = "★★☆☆☆ FAIR";
            ratingColor = YELLOW;
        } else {
            rating = "★☆☆☆☆ NEEDS IMPROVEMENT";
            ratingColor = RED;
        }

        sb.append(String.format(" %s%s%s%s\n", BOLD, ratingColor, rating, RESET));

        sb.append("\n");

        out.print(sb);
        out.flush();
    }
}
