// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.CloseHelper;
import org.agrona.IoUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.match.infrastructure.persistence.DurableLedgerV2Test.cancel;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.order;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.releaseFrame;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.upTo;
import static com.match.infrastructure.persistence.S5Frames.ASK;
import static com.match.infrastructure.persistence.S5Frames.BID;
import static com.match.infrastructure.persistence.S5Frames.FOK;
import static com.match.infrastructure.persistence.S5Frames.GTC;
import static com.match.infrastructure.persistence.S5Frames.IOC;
import static com.match.infrastructure.persistence.S5Frames.amendRaw;
import static com.match.infrastructure.persistence.S5Frames.limit;
import static com.match.infrastructure.persistence.S5Frames.market;

/**
 * RC2 S9h section 8 (decision D-S9h-2, W6): the E1 scenario corpus, captured once from J-ME3 ({@link MeClusterHarness}:
 * three ME members in one JVM, each member's settlement-journal ring drained into its own frame list). Every scenario
 * boots a fresh three-member cluster, binds it with a behaviour-2 FRESH EngineConfig and drives the commands of
 * {@code cpp:} 3.2 E1 through a FORWARDER session; the output per scenario is {@code member<k>.fjc} (k = 0..2) and a
 * {@code MANIFEST}, all under one {@code SHA256SUMS}.
 *
 * <p>Recording boundaries (review N-6): the harness drains rings, not Archive recordings, so this tool synthesises them:
 * an FJC {@code RECORDING <ordinal>} marker at every start of a member (ordinal = the member's start count) and a
 * {@code STOP} at every close (the ring is drained at close, so the STOP follows the member's last frame). A member
 * restart therefore maps to STOP, RECORDING. The one non-ME frame (a schema 3 v1 JournalTrade, scenario
 * {@code schema3_record}) is appended to member 0's last recording before its STOP and named in the MANIFEST.</p>
 *
 * <p>J-ME3 runs are not byte-reproducible (cluster time, log positions), so the corpus is a recording, not a regenerated
 * fixture: {@code MeJournalCorpusCaptureTest} checks the SUMS and the MANIFEST's structural claims, never byte equality
 * with a fresh capture. Usage: {@code MeJournalCorpusCaptureTest#capture} with {@code -Dme.e1.capture.dir=<dir>} and
 * optionally {@code -Dme.e1.capture.scenarios=a,b} (a subset per JVM).</p>
 */
final class MeJournalCorpusCapture {

    static final long G = BehaviourTwoHarness.G;
    static final long G2 = 0x2234_5678_9ABC_DEF2L;
    static final String MATCH_BASE_COMMIT = "421226c";
    static final List<String> SCENARIOS = List.of("plain_fill", "partial_cancel", "iceberg_slices", "replace", "ioc", "fok",
            "stop_child", "market_budget", "release", "fresh_config", "dark_node_hole", "regenesis", "schema3_record",
            "maker_sweep", "restart_rejournal");

    /** One member's capture: its frames and the [from, to) frame index of each recording. */
    static final class MemberLog {
        final List<int[]> recordings = new ArrayList<>();
        int open = -1;
        final List<byte[]> appended = new ArrayList<>();
    }

    /** A running scenario: the harness, the clients, the per-member recording boundaries. */
    static final class Run implements AutoCloseable {
        final MeClusterHarness h;
        final MemberLog[] logs = new MemberLog[MeClusterHarness.MEMBERS];
        AeronCluster admin;
        AeronCluster fwd;
        int freshRecords;
        final List<String> notes = new ArrayList<>();
        private boolean closed;

        Run(final int portBase, final String name) throws InterruptedException {
            h = new MeClusterHarness(portBase, "e1-" + name);
            for (int i = 0; i < logs.length; i++) {
                logs[i] = new MemberLog();
            }
            h.startAll();
            for (int i = 0; i < logs.length; i++) {
                started(i);
            }
            h.awaitLeader(null, 30_000);
            connect();
        }

        void started(final int m) {
            logs[m].open = h.members[m].frames.size();
        }

        void stopped(final int m) {
            logs[m].recordings.add(new int[] {logs[m].open, h.members[m].frames.size()});
            logs[m].open = -1;
        }

        void connect() throws InterruptedException {
            CloseHelper.quietClose(admin);
            CloseHelper.quietClose(fwd);
            admin = h.connect(ClusterSessionClass.ADMIN);
            fwd = h.connect(ClusterSessionClass.FORWARDER);
        }

        void fresh(final long genesis) {
            send(admin, BehaviourTwoLog.engineConfigWith(1, 2, genesis, 12, 1_000_000, false, 4_096, 100, 0, 1));
            freshRecords += MeClusterHarness.MEMBERS;
            final int expect = freshRecords;
            if (!MeClusterHarness.await(admin, () -> h.engineConfigRecords.get() >= expect, 30_000)) {
                throw new IllegalStateException("FRESH not journaled on every member");
            }
        }

        static void send(final AeronCluster c, final byte[] frame) {
            if (MeClusterHarness.offer(c, frame, 10_000) <= 0) {
                throw new IllegalStateException("offer refused");
            }
        }

        /** Sends a durable command and waits for its record 3; returns {result, orderId, journalSeq}. */
        long[] command(final byte[] frame) {
            final long hi = new UnsafeBuffer(frame).getLong(8, ByteOrder.LITTLE_ENDIAN);
            final long lo = new UnsafeBuffer(frame).getLong(16, ByteOrder.LITTLE_ENDIAN);
            send(fwd, frame);
            final String key = MeClusterHarness.key(hi, lo);
            if (!MeClusterHarness.await(fwd, () -> h.outcomes.containsKey(key), 20_000)) {
                throw new IllegalStateException("no record 3 for " + key);
            }
            return h.outcomes.get(key);
        }

        void release(final long releaseSeq, final long[]... entries) {
            send(fwd, releaseFrame(releaseSeq, entries));
            if (!MeClusterHarness.await(fwd, () -> h.releases.containsKey(releaseSeq), 20_000)) {
                throw new IllegalStateException("no record 4 for release " + releaseSeq);
            }
        }

        void quiesce() {
            if (!h.awaitQuiescence(fwd, 30_000)) {
                throw new IllegalStateException("the live members did not quiesce");
            }
        }

        MeClusterHarness.Member follower() throws InterruptedException {
            final MeClusterHarness.Member leader = h.awaitLeader(null, 30_000);
            for (final MeClusterHarness.Member m : h.members) {
                if (m != leader && m.live) {
                    return m;
                }
            }
            throw new IllegalStateException("no live follower");
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            CloseHelper.quietClose(admin);
            CloseHelper.quietClose(fwd);
            for (final MeClusterHarness.Member m : h.members) {
                if (m.live) {
                    m.close();
                    stopped(m.id);
                }
            }
            h.close();
        }
    }

    /** The scenarios, by name. */
    static void drive(final String name, final Run r) throws Exception {
        r.fresh(G);
        switch (name) {
            case "plain_fill" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
            }
            case "partial_cancel" -> {
                final long leg = r.command(limit(1, 0, 100, ASK, 60_000, 3, GTC))[1];
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.command(cancel(1, 1, 100, leg));
            }
            case "iceberg_slices" -> {
                // the core works an iceberg as successive CREATE slices of one order (attempts 0, 1, 2)
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.command(limit(1, 1, 100, ASK, 60_000, 1, GTC));
                r.command(limit(3, 0, 200, BID, 60_000, 1, GTC));
                r.command(limit(1, 2, 100, ASK, 60_000, 1, GTC));
            }
            case "replace" -> {
                final long leg = r.command(limit(1, 0, 100, ASK, 60_000, 10, GTC))[1];
                r.command(limit(2, 0, 200, BID, 60_000, 3, GTC));
                final long[] applied = r.command(amendRaw(1, 1, 100, leg, ASK, 60_000, 8, 0, GTC, DurableLedgerV2Test.fp(10)));
                r.notes.add("replace APPLIED (oldCancelled 1) attempt 1 -> leg " + applied[1]);
                r.command(limit(3, 0, 200, BID, 60_000, 4, GTC));
                r.command(amendRaw(1, 2, 100, applied[1], ASK, 60_000, 2, 0, GTC, DurableLedgerV2Test.fp(5)));
                r.notes.add("replace REJECTED (oldCancelled 1, AMEND_NO_REMAINDER) attempt 2");
            }
            case "ioc" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 3, IOC));
            }
            case "fok" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 2, FOK)); // unfillable
                r.command(limit(3, 0, 200, BID, 60_000, 1, FOK)); // fills
            }
            case "stop_child" -> {
                // a triggered stop reaches the ME as its MARKET child CREATE
                r.command(limit(1, 0, 100, BID, 60_000, 2, GTC));
                r.command(market(2, 0, 200, ASK, 1, 0, GTC));
            }
            case "market_budget" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(market(2, 0, 200, BID, 1, 30_000, GTC)); // the budget pays half
            }
            case "release" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.release(1, upTo(1, 0), order(2));
            }
            case "fresh_config" -> r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
            case "dark_node_hole" -> {
                // the drill A shape of MeJournalHoleAfterSnapshotDrillTest (S3 9, critic K8): a follower's journal is
                // held (its ring no longer drained) while load flows, the cluster snapshots at P, the follower is closed
                // with its undrained ring discarded, then restarted: it restores at P and journals only above P
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.quiesce();
                final MeClusterHarness.Member dark = r.follower();
                dark.journal = null; // hold: nothing more of this member's ring reaches its stream
                r.notes.add("member " + dark.id + ": journal held after h1 (ring undrained)");
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.command(limit(3, 0, 100, ASK, 61_000, 1, GTC));
                final MeClusterHarness.Member leader = r.h.awaitLeader(null, 30_000);
                r.h.snapshot(leader, r.fwd);
                r.notes.add("cluster snapshot after h3; member " + dark.id + " closed with its held ring discarded, restarted");
                dark.close();
                r.stopped(dark.id);
                dark.start();
                r.started(dark.id);
                final MeClusterHarness.Member leader2 = r.h.awaitLeader(null, 30_000);
                if (!r.h.awaitJoined(dark, leader2, r.fwd, 60_000)) {
                    throw new IllegalStateException("member " + dark.id + " did not rejoin");
                }
                r.command(limit(4, 0, 200, BID, 61_000, 1, GTC));
                r.command(limit(5, 0, 100, ASK, 62_000, 1, GTC));
            }
            case "maker_sweep" -> {
                // review NOTE-5 (a 62 in E1): one taker against three resting makers and one taker partially filling a
                // larger maker; records as the ME journals them (whether two trades are ever adjacent is the question)
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 101, ASK, 60_000, 1, GTC));
                r.command(limit(3, 0, 102, ASK, 60_000, 1, GTC));
                r.command(limit(4, 0, 200, BID, 60_000, 3, GTC));
                r.command(limit(5, 0, 103, ASK, 61_000, 5, GTC));
                r.command(limit(6, 0, 201, BID, 61_000, 2, IOC));
                r.command(market(7, 0, 202, BID, 2, 1_000_000, GTC));
            }
            case "restart_rejournal" -> {
                // review NOTE-6: the realistic K8 shape (S9a 1.6): a follower closed and restarted with no snapshot
                // re-journals from seq 1 in a new recording, overlapping what it had already delivered
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.quiesce();
                final MeClusterHarness.Member m = r.follower();
                m.close();
                r.stopped(m.id);
                r.notes.add("member " + m.id + " closed after h2 (no snapshot), restarted after h4: its new recording "
                        + "re-journals from seq 1");
                r.command(limit(3, 0, 100, ASK, 61_000, 1, GTC));
                r.command(limit(4, 0, 200, BID, 61_000, 1, GTC));
                m.start();
                r.started(m.id);
                final MeClusterHarness.Member leader = r.h.awaitLeader(null, 30_000);
                if (!r.h.awaitJoined(m, leader, r.fwd, 60_000)) {
                    throw new IllegalStateException("member " + m.id + " did not rejoin");
                }
                r.command(limit(5, 0, 100, ASK, 62_000, 1, GTC));
            }
            case "regenesis" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.quiesce();
                CloseHelper.quietClose(r.admin);
                CloseHelper.quietClose(r.fwd);
                for (final MeClusterHarness.Member m : r.h.members) {
                    m.close();
                    r.stopped(m.id);
                    IoUtil.delete(m.baseDir(), false); // a clean genesis: the ME state is gone, the stream is not
                }
                r.notes.add("every member closed and wiped, restarted, FRESH under the second epoch");
                for (final MeClusterHarness.Member m : r.h.members) {
                    m.start();
                    r.started(m.id);
                }
                r.h.awaitLeader(null, 30_000);
                r.connect();
                r.fresh(G2);
                r.command(limit(11, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(12, 0, 200, BID, 60_000, 1, GTC));
            }
            case "schema3_record" -> {
                r.command(limit(1, 0, 100, ASK, 60_000, 1, GTC));
                r.command(limit(2, 0, 200, BID, 60_000, 1, GTC));
                r.quiesce();
                r.logs[0].appended.add(schema3Trade());
                r.notes.add("member 0: one schema 3 v1 JournalTrade appended after the ME's frames (synthetic, not ME-produced)");
            }
            default -> throw new IllegalArgumentException(name);
        }
        r.quiesce();
    }

    /** A schema 3 v1 JournalTrade (the frozen bridge's wire), the only non-ME frame of the corpus. */
    static byte[] schema3Trade() {
        final UnsafeBuffer b = new UnsafeBuffer(new byte[256]);
        final com.match.infrastructure.journal.generated.JournalTradeEncoder e =
                new com.match.infrastructure.journal.generated.JournalTradeEncoder();
        e.wrapAndApplyHeader(b, 0, new com.match.infrastructure.journal.generated.MessageHeaderEncoder())
                .egressSeq(1000L).tradeId(42L).marketId(1).takerOrderId(11L).takerUserId(200L).makerOrderId(22L)
                .makerUserId(100L).price(6_000_000_000_000L).quantity(100_000_000L)
                .takerIsBuy(com.match.infrastructure.journal.generated.BooleanType.TRUE).timestamp(1_752_100_000_123L)
                .takerOmsOrderId(2L).makerOmsOrderId(1L);
        final byte[] out = new byte[8 + e.encodedLength()];
        b.getBytes(0, out);
        return out;
    }

    // ------------------------------------------------------------------ output

    static byte[] fjc(final MeClusterHarness.Member m, final MemberLog log) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x46, 0x4A, 0x43, 0x31});
        long ordinal = 0;
        for (int i = 0; i < log.recordings.size(); i++) {
            final int[] rec = log.recordings.get(i);
            out.write(1);
            i64(out, ordinal++);
            for (int f = rec[0]; f < rec[1]; f++) {
                frame(out, m.frames.get(f));
            }
            if (i == log.recordings.size() - 1) {
                for (final byte[] extra : log.appended) {
                    frame(out, extra);
                }
            }
            out.write(3);
        }
        return out.toByteArray();
    }

    private static void frame(final ByteArrayOutputStream out, final byte[] f) {
        out.write(2);
        out.write(f.length & 0xFF);
        out.write((f.length >>> 8) & 0xFF);
        out.writeBytes(f);
    }

    private static void i64(final ByteArrayOutputStream out, final long v) {
        for (int i = 0; i < 8; i++) {
            out.write((int) (v >>> (8 * i)) & 0xFF);
        }
    }

    /** The structural MANIFEST of one scenario, computed from the captured streams (the test recomputes it). */
    static String manifest(final String name, final List<List<List<byte[]>>> members, final List<String> notes) {
        final StringBuilder sb = new StringBuilder();
        sb.append("scenario=").append(name).append('\n');
        sb.append("match_commit=").append(MATCH_BASE_COMMIT).append(" (capture tool uncommitted on this base)\n");
        sb.append("boundary_rule=RECORDING at every member start, STOP at every member close (MeClusterHarness drains rings,"
                + " not archives; review N-6)\n");
        sb.append(structure(members));
        for (final String n : notes) {
            sb.append("note=").append(n).append('\n');
        }
        return sb.toString();
    }

    /**
     * The claims a reader can check: per member the recordings with their seq ranges and epochs, the template counts, the
     * seqs of the scenario's journal (per epoch) the member lacks, the non-schema-4 frames (expected refusals).
     */
    static String structure(final List<List<List<byte[]>>> members) {
        final StringBuilder sb = new StringBuilder();
        final TreeMap<Long, TreeSet<Long>> union = new TreeMap<>();
        final TreeSet<Long> epochs = new TreeSet<>();
        for (final List<List<byte[]>> m : members) {
            for (final List<byte[]> rec : m) {
                for (final byte[] f : rec) {
                    if (schema(f) == 4) {
                        union.computeIfAbsent(epoch(f), k -> new TreeSet<>()).add(seq(f));
                        epochs.add(epoch(f));
                    }
                }
            }
        }
        sb.append("epochs=");
        boolean first = true;
        for (final long e : epochs) {
            sb.append(first ? "" : ",").append(String.format("0x%016x", e));
            first = false;
        }
        sb.append('\n');
        for (final Map.Entry<Long, TreeSet<Long>> e : union.entrySet()) {
            sb.append(String.format("epoch_0x%016x_seqs=%d-%d", e.getKey(), e.getValue().first(), e.getValue().last()))
                    .append('\n');
        }
        for (int k = 0; k < members.size(); k++) {
            final List<List<byte[]>> m = members.get(k);
            sb.append("member").append(k).append(".recordings=").append(m.size()).append('\n');
            final int[] templates = new int[6];
            final TreeMap<Long, TreeSet<Long>> have = new TreeMap<>();
            int index = 0;
            for (int r = 0; r < m.size(); r++) {
                final List<byte[]> rec = m.get(r);
                long lo = Long.MAX_VALUE;
                long hi = Long.MIN_VALUE;
                final TreeSet<Long> recEpochs = new TreeSet<>();
                for (final byte[] f : rec) {
                    if (schema(f) != 4) {
                        sb.append("member").append(k).append(".refused=").append(index).append(" SCHEMA schemaId ")
                                .append(schema(f)).append('\n');
                    } else {
                        final int t = template(f);
                        if (t >= 1 && t <= 5) {
                            templates[t]++;
                        }
                        lo = Math.min(lo, seq(f));
                        hi = Math.max(hi, seq(f));
                        recEpochs.add(epoch(f));
                        have.computeIfAbsent(epoch(f), x -> new TreeSet<>()).add(seq(f));
                    }
                    index++;
                }
                sb.append("member").append(k).append(".recording").append(r).append('=').append(rec.size()).append(" frames");
                if (lo <= hi) {
                    sb.append(" seqs ").append(lo).append('-').append(hi);
                }
                for (final long e : recEpochs) {
                    sb.append(String.format(" epoch 0x%016x", e));
                }
                sb.append('\n');
            }
            sb.append("member").append(k).append(".templates=");
            for (int t = 1; t <= 5; t++) {
                sb.append(t).append(':').append(templates[t]).append(t < 5 ? "," : "\n");
            }
            for (final Map.Entry<Long, TreeSet<Long>> e : union.entrySet()) {
                final TreeSet<Long> missing = new TreeSet<>(e.getValue());
                missing.removeAll(have.getOrDefault(e.getKey(), new TreeSet<>()));
                if (!missing.isEmpty()) {
                    sb.append("member").append(k).append(String.format(".missing_0x%016x=", e.getKey()))
                            .append(ranges(missing)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    static String ranges(final TreeSet<Long> s) {
        final StringBuilder sb = new StringBuilder();
        Long start = null;
        Long prev = null;
        for (final long v : s) {
            if (start == null) {
                start = v;
            } else if (v != prev + 1) {
                sb.append(sb.length() > 0 ? "," : "").append(start).append('-').append(prev);
                start = v;
            }
            prev = v;
        }
        if (start != null) {
            sb.append(sb.length() > 0 ? "," : "").append(start).append('-').append(prev);
        }
        return sb.toString();
    }

    static int schema(final byte[] f) {
        return f.length >= 8 ? (f[4] & 0xFF) | (f[5] & 0xFF) << 8 : -1;
    }

    static int template(final byte[] f) {
        return (f[2] & 0xFF) | (f[3] & 0xFF) << 8;
    }

    static long seq(final byte[] f) {
        return new UnsafeBuffer(f).getLong(8, ByteOrder.LITTLE_ENDIAN);
    }

    static long epoch(final byte[] f) {
        return new UnsafeBuffer(f).getLong(16, ByteOrder.LITTLE_ENDIAN);
    }

    /** Parses an FJC file into recordings of frames (RECORDING .. STOP). */
    static List<List<byte[]>> parseFjc(final byte[] file) {
        if (file.length < 4 || file[0] != 0x46 || file[1] != 0x4A || file[2] != 0x43 || file[3] != 0x31) {
            throw new IllegalArgumentException("FJC1: bad magic");
        }
        final List<List<byte[]>> out = new ArrayList<>();
        List<byte[]> cur = null;
        int p = 4;
        while (p < file.length) {
            final int type = file[p++] & 0xFF;
            switch (type) {
                case 1 -> {
                    if (cur != null) {
                        throw new IllegalArgumentException("FJC1: RECORDING inside an open recording");
                    }
                    p += 8;
                    cur = new ArrayList<>();
                }
                case 2 -> {
                    final int len = (file[p] & 0xFF) | (file[p + 1] & 0xFF) << 8;
                    p += 2;
                    cur.add(java.util.Arrays.copyOfRange(file, p, p + len));
                    p += len;
                }
                case 3 -> {
                    out.add(cur);
                    cur = null;
                }
                default -> throw new IllegalArgumentException("FJC1: type " + type);
            }
        }
        if (cur != null) {
            throw new IllegalArgumentException("FJC1: unterminated recording");
        }
        return out;
    }

    static String sha256(final byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    /**
     * Captures {@code scenarios} into {@code out} (one directory per scenario), then rewrites the root SHA256SUMS over
     * every scenario file present. Several JVMs may each capture a subset (the dev box's 2 GB test heap holds about ten
     * three-member clusters); the SUMS is complete once every scenario is there.
     */
    static void capture(final Path out, final List<String> scenarios) throws Exception {
        int port = 27_000;
        for (final String name : SCENARIOS) {
            port += 300;
            if (!scenarios.contains(name)) {
                continue;
            }
            final List<String> notes;
            final List<byte[]> fjcs = new ArrayList<>();
            System.out.println("[E1] capturing " + name);
            try (Run r = new Run(port, name)) {
                try {
                    drive(name, r);
                } finally {
                    notes = r.notes;
                }
                r.close(); // stops every member (the STOP markers); idempotent with try-with-resources
                for (int k = 0; k < MeClusterHarness.MEMBERS; k++) {
                    fjcs.add(fjc(r.h.members[k], r.logs[k]));
                }
            }
            final List<List<List<byte[]>>> members = new ArrayList<>();
            for (int k = 0; k < fjcs.size(); k++) {
                final Path p = out.resolve(name + "/member" + k + ".fjc");
                Files.createDirectories(p.getParent());
                Files.write(p, fjcs.get(k));
                members.add(parseFjc(fjcs.get(k)));
            }
            Files.write(out.resolve(name + "/MANIFEST"), manifest(name, members, notes).getBytes(StandardCharsets.US_ASCII));
        }
        final StringBuilder sums = new StringBuilder();
        final TreeMap<String, byte[]> files = new TreeMap<>();
        for (final String name : SCENARIOS) {
            for (final String f : List.of("MANIFEST", "member0.fjc", "member1.fjc", "member2.fjc")) {
                final Path p = out.resolve(name + "/" + f);
                if (Files.isRegularFile(p)) {
                    files.put(name + "/" + f, Files.readAllBytes(p));
                }
            }
        }
        for (final Map.Entry<String, byte[]> e : files.entrySet()) {
            sums.append(sha256(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        Files.writeString(out.resolve("SHA256SUMS"), sums.toString(), StandardCharsets.US_ASCII);
        System.out.println("[E1] SHA256SUMS " + sha256(sums.toString().getBytes(StandardCharsets.US_ASCII)) + " over "
                + files.size() + " files");
    }

    private MeJournalCorpusCapture() {
    }
}
