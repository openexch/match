// SPDX-License-Identifier: Apache-2.0
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import io.aeron.samples.cluster.ClusterConfig;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.TradeExecutionBatchDecoder;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Bug #9 repro — INDEPENDENT egress observer.
 *
 * A standalone AeronCluster egress client (separate session, own MediaDriver, auto-assigned
 * egress port) that records the cluster's ground truth — every per-trade TradeExecution (id 26,
 * carrying the monotonic tradeId + maker/taker user & oms ids) and every OrderStatus (id 24) —
 * to JSONL, independently of the OMS. It is NOT the OMS path, so comparing what it captured vs
 * what OMS settled reveals missed/duplicate/miscounted fills.
 *
 * Caveat (documented): this client shares OMS's architecture, so it can ALSO gap on its own
 * reconnect during a switchover. It writes a marker to observer-events.jsonl on every leader
 * change / reconnect so those windows are known; tradeId is globally contiguous, so a missing
 * tradeId in an otherwise contiguous run flags a drop that can be attributed to a marked window.
 *
 * Build: javac -cp match-loadtest.jar -d <out> Bug9Observer.java
 * Run:   java  -cp <out>:match-loadtest.jar Bug9Observer <outDir> <durationSeconds>
 *        (stop early by creating <outDir>/STOP)
 */
public final class Bug9Observer implements EgressListener {

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final TradeExecutionBatchDecoder tradeDecoder = new TradeExecutionBatchDecoder();
    private final OrderStatusBatchDecoder statusDecoder = new OrderStatusBatchDecoder();

    private final BufferedWriter trades;
    private final BufferedWriter status;
    private final BufferedWriter events;

    private long recvIdx = 0;
    private long tradeRows = 0;
    private long statusRows = 0;

    private Bug9Observer(Path outDir) throws IOException {
        this.trades = new BufferedWriter(new FileWriter(outDir.resolve("observer-trades.jsonl").toFile(), true));
        this.status = new BufferedWriter(new FileWriter(outDir.resolve("observer-status.jsonl").toFile(), true));
        this.events = new BufferedWriter(new FileWriter(outDir.resolve("observer-events.jsonl").toFile(), true));
    }

    private static long nowMs() { return System.currentTimeMillis(); }

    private synchronized void event(String kind, String detail) {
        try {
            events.write("{\"ts\":" + nowMs() + ",\"recvIdx\":" + recvIdx
                    + ",\"kind\":\"" + kind + "\",\"detail\":\"" + detail.replace("\"", "'") + "\"}\n");
            events.flush();
        } catch (IOException e) { /* best effort */ }
    }

    @Override
    public void onMessage(long clusterSessionId, long timestamp, DirectBuffer buffer,
                          int offset, int length, Header header) {
        recvIdx++;
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) return;
        headerDecoder.wrap(buffer, offset);
        final int templateId = headerDecoder.templateId();
        try {
            if (templateId == TradeExecutionBatchDecoder.TEMPLATE_ID) {
                tradeDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final int marketId = tradeDecoder.marketId();
                for (TradeExecutionBatchDecoder.TradesDecoder t : tradeDecoder.trades()) {
                    trades.write("{\"recvIdx\":" + recvIdx + ",\"ts\":" + nowMs()
                            + ",\"marketId\":" + marketId
                            + ",\"tradeId\":" + t.tradeId()
                            + ",\"takerOrderId\":" + t.takerOrderId()
                            + ",\"makerOrderId\":" + t.makerOrderId()
                            + ",\"takerUserId\":" + t.takerUserId()
                            + ",\"makerUserId\":" + t.makerUserId()
                            + ",\"price\":" + t.price()
                            + ",\"quantity\":" + t.quantity()
                            + ",\"takerSideRaw\":" + t.takerSideRaw()
                            + ",\"takerOmsOrderId\":" + t.takerOmsOrderId()
                            + ",\"makerOmsOrderId\":" + t.makerOmsOrderId()
                            + "}\n");
                    tradeRows++;
                }
            } else if (templateId == OrderStatusBatchDecoder.TEMPLATE_ID) {
                statusDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final int marketId = statusDecoder.marketId();
                for (OrderStatusBatchDecoder.OrdersDecoder o : statusDecoder.orders()) {
                    status.write("{\"recvIdx\":" + recvIdx + ",\"ts\":" + nowMs()
                            + ",\"marketId\":" + marketId
                            + ",\"orderId\":" + o.orderId()
                            + ",\"userId\":" + o.userId()
                            + ",\"statusRaw\":" + o.statusRaw()
                            + ",\"price\":" + o.price()
                            + ",\"remainingQty\":" + o.remainingQty()
                            + ",\"filledQty\":" + o.filledQty()
                            + ",\"sideRaw\":" + o.sideRaw()
                            + ",\"omsOrderId\":" + o.omsOrderId()
                            + "}\n");
                    statusRows++;
                }
            }
        } catch (IOException e) {
            event("writeError", e.toString());
        }
    }

    @Override
    public void onNewLeader(long clusterSessionId, long leadershipTermId, int leaderMemberId,
                            String ingressEndpoints) {
        event("newLeader", "member=" + leaderMemberId + " term=" + leadershipTermId);
    }

    private void flushAll() {
        try { trades.flush(); status.flush(); events.flush(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Bug9Observer <outDir> <durationSeconds> [host1,host2,host3] [basePort]");
            System.exit(2);
        }
        final Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);
        final long durationMs = Long.parseLong(args[1]) * 1000L;
        final List<String> hosts = List.of((args.length > 2 ? args[2] : "localhost,localhost,localhost").split(","));
        final int basePort = args.length > 3 ? Integer.parseInt(args[3]) : 9000;
        final File stopFile = outDir.resolve("STOP").toFile();

        final String ingressEndpoints =
                ClusterConfig.ingressEndpoints(hosts, basePort, ClusterConfig.CLIENT_FACING_PORT_OFFSET);

        final String aeronDir = "/dev/shm/aeron-obs-" + ProcessHandle.current().pid();
        final MediaDriver mediaDriver = MediaDriver.launchEmbedded(
                new MediaDriver.Context()
                        .aeronDirectoryName(aeronDir)
                        .threadingMode(ThreadingMode.SHARED)
                        .dirDeleteOnStart(true)
                        .dirDeleteOnShutdown(true));

        final Bug9Observer observer = new Bug9Observer(outDir);
        observer.event("start", "ingress=" + ingressEndpoints + " aeronDir=" + aeronDir);
        System.out.println("[observer] ingress=" + ingressEndpoints + " aeronDir=" + aeronDir);

        final IdleStrategy idle = new BackoffIdleStrategy(100, 10, 1000, 100_000);
        final long deadline = System.currentTimeMillis() + durationMs;
        AeronCluster cluster = null;
        long lastStat = System.currentTimeMillis();
        long lastKeepAlive = System.currentTimeMillis();

        Runtime.getRuntime().addShutdownHook(new Thread(observer::flushAll));

        try {
            while (System.currentTimeMillis() < deadline && !stopFile.exists()) {
                if (cluster == null || cluster.isClosed()) {
                    if (cluster != null) { observer.event("disconnected", "cluster closed; reconnecting"); }
                    try {
                        cluster = AeronCluster.connect(new AeronCluster.Context()
                                .egressListener(observer)
                                .egressChannel("aeron:udp?endpoint=127.0.0.1:0")
                                .ingressChannel("aeron:udp?term-length=16m|mtu=8k")
                                .aeronDirectoryName(mediaDriver.aeronDirectoryName())
                                .ingressEndpoints(ingressEndpoints));
                        observer.event("connected", "sessionId=" + cluster.clusterSessionId()
                                + " leader=" + cluster.leaderMemberId());
                        System.out.println("[observer] connected sessionId=" + cluster.clusterSessionId()
                                + " leader=" + cluster.leaderMemberId());
                    } catch (Throwable t) {
                        observer.event("connectError", t.toString());
                        idle.idle(0);
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                }
                int work;
                try {
                    work = cluster.pollEgress();
                    // Keep the cluster session alive (10s session timeout). Without this the
                    // cluster closes our session, forcing a reconnect that drops in-flight egress.
                    final long nowKa = System.currentTimeMillis();
                    if (nowKa - lastKeepAlive >= 1000) {
                        lastKeepAlive = nowKa;
                        cluster.sendKeepAlive();
                    }
                } catch (Throwable t) {
                    observer.event("pollError", t.toString());
                    try { cluster.close(); } catch (Throwable ignored) {}
                    cluster = null;
                    continue;
                }
                idle.idle(work);

                final long now = System.currentTimeMillis();
                if (now - lastStat >= 5000) {
                    lastStat = now;
                    observer.flushAll();
                    System.out.println("[observer] tradeRows=" + observer.tradeRows
                            + " statusRows=" + observer.statusRows + " msgs=" + observer.recvIdx);
                }
            }
        } finally {
            observer.event("stop", "tradeRows=" + observer.tradeRows + " statusRows=" + observer.statusRows);
            observer.flushAll();
            if (cluster != null) { try { cluster.close(); } catch (Throwable ignored) {} }
            try { mediaDriver.close(); } catch (Throwable ignored) {}
            System.out.println("[observer] done tradeRows=" + observer.tradeRows
                    + " statusRows=" + observer.statusRows);
        }
    }
}
