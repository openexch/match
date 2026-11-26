// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.journal.MatchJournalProfiles;
import com.match.infrastructure.persistence.MeClusterHarness.Member;
import com.openexchange.cluster.journal.JournalProfile;
import com.openexchange.cluster.journal.JournalReaderTool;
import com.openexchange.cluster.journal.JournalRetention;
import com.openexchange.cluster.journal.JournalRetentionAgent;
import com.openexchange.cluster.shadow.ShadowReplay;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.CloseHelper;
import org.agrona.concurrent.SystemNanoClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A three-member matching engine at behaviour 2 where every member runs the production settlement journal runtime
 * (its own journal archive, 64 KiB term and 64 KiB segments), the journal retention settings as system properties, a
 * durable order load (one journal record 3 per command), a reader that really reads the journal from the members'
 * journal archives, and a pusher that delivers that reader's cursor to each member with the kit's command at least
 * one step after it read it. Everything a test asserts about a purge is read back from a member's journal archive.
 */
final class MeRetentionStory implements AutoCloseable {

    static final int SEG = 64 * 1024;
    static final long GENESIS = 0x5EED_0000_0011L;
    static final JournalProfile PROFILE = MatchJournalProfiles.SETTLEMENT;
    static final String JOURNAL = MatchJournalProfiles.SETTLEMENT_NAME;

    /** One recording as a member's journal archive lists it. */
    record Rec(long id, long start, long end, boolean active) {
        long bytes() {
            return Math.max(0, end - start);
        }
    }

    final MeClusterHarness h;
    final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<String> properties = new ArrayList<>();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final Map<String, AeronArchive> archives = new HashMap<>();
    AeronCluster client;
    Member leader;
    Member f;
    Member g;
    private long nextOrder = 1;
    private long lastKeepAliveMs;

    /** Records every line that names the journal retention or the reader tool, and passes everything on. */
    private final class Capture extends OutputStream {
        private final PrintStream next;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Capture(final PrintStream next) {
            this.next = next;
        }

        @Override
        public synchronized void write(final int b) {
            next.write(b);
            if (b == '\n') {
                final String s = line.toString();
                line.reset();
                if (s.contains("[JOURNAL-RE") || s.contains("journal retention")) {
                    lines.add(s);
                }
            } else {
                line.write(b);
            }
        }

        @Override
        public void flush() {
            next.flush();
        }
    }

    /**
     * @param externalDriver each member on a media driver launched apart from its archive and consensus module
     * @param properties     system property name and value pairs, set before the first member starts and cleared
     *                       in {@link #close}
     */
    MeRetentionStory(final int port, final String name, final boolean externalDriver, final String... properties) {
        set("transport.journal.term.length", "64k");
        set("transport.journal.segment.length", "64k");
        for (int i = 0; i < properties.length; i += 2) {
            set(properties[i], properties[i + 1]);
        }
        System.setOut(new PrintStream(new Capture(originalOut), true));
        System.setErr(new PrintStream(new Capture(originalErr), true));
        h = new MeClusterHarness(port, name);
        h.journalArchives = true;
        h.externalDriver = externalDriver;
    }

    private void set(final String property, final String value) {
        System.setProperty(property, value);
        properties.add(property);
    }

    /** Starts the members, records the behaviour 2 config (the genesis) and connects the order client. */
    MeRetentionStory boot() throws Exception {
        h.startAll();
        leader = h.awaitLeader(null, 30_000);
        f = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
        g = h.members[(leader.id + 2) % MeClusterHarness.MEMBERS];
        try (AeronCluster admin = h.connect(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN)) {
            if (MeClusterHarness.offer(admin, BehaviourTwoLog.engineConfig(1, 2, GENESIS, 12, 1_000), 10_000) <= 0) {
                throw new AssertionError("the engine config was not accepted");
            }
            if (!MeClusterHarness.await(admin, () -> live().stream().allMatch(
                    m -> m.engine().meIdentity().meEpoch() == GENESIS), 20_000)) {
                throw new AssertionError("the genesis is not recorded on every member");
            }
        }
        client = h.connect();
        return this;
    }

    /**
     * As {@link #boot} with a behaviour 1 config: no genesis id is recorded, every journal record carries epoch 0
     * and journalSeq 0.
     */
    MeRetentionStory bootBehaviourOne() throws Exception {
        h.startAll();
        leader = h.awaitLeader(null, 30_000);
        f = h.members[(leader.id + 1) % MeClusterHarness.MEMBERS];
        g = h.members[(leader.id + 2) % MeClusterHarness.MEMBERS];
        try (AeronCluster admin = h.connect(com.openexchange.cluster.auth.ClusterSessionClass.ADMIN)) {
            if (MeClusterHarness.offer(admin, BehaviourTwoLog.engineConfig(1, 1, 0, 0, 100_000), 10_000) <= 0) {
                throw new AssertionError("the engine config was not accepted");
            }
            if (!MeClusterHarness.await(admin, () -> live().stream().allMatch(
                    m -> m.engine().getEngineConfig() != null), 20_000)) {
                throw new AssertionError("the behaviour 1 config is not recorded on every member");
            }
        }
        client = h.connect(com.openexchange.cluster.auth.ClusterSessionClass.GATEWAY);
        return this;
    }

    /**
     * {@code n} durable order commands on a behaviour 1 cluster (journalSeq 0 on every record); waits until every
     * member's journal archive recorded the same end position, above {@code minBytes}.
     */
    void ordersBehaviourOne(final int n, final long minBytes) {
        for (int i = 0; i < n; i++) {
            final long o = nextOrder++;
            if (MeClusterHarness.offer(client, DurableLedgerV2Test.create(o, 0, 100, 0, 59_000 + (o % 100), 1),
                    10_000) <= 0) {
                throw new AssertionError("order " + o + " was not accepted");
            }
            if (i % 100 == 99) {
                MeClusterHarness.await(client, () -> false, 20);
            }
        }
        final long[] last = {-1, 0};
        await(() -> {
            final long end = active(h.members[0]).end();
            final boolean same = end >= minBytes && live().stream().allMatch(m -> active(m).end() == end);
            if (!same || end != last[0]) {
                last[0] = end;
                last[1] = System.currentTimeMillis();
                return false;
            }
            return System.currentTimeMillis() - last[1] >= 500;
        }, 60_000, () -> "every member recorded the same journal end above " + minBytes + ": " + describe());
    }

    List<Member> live() {
        final List<Member> out = new ArrayList<>();
        for (final Member m : h.members) {
            if (m.live) {
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            archives.values().forEach(CloseHelper::quietClose);
            CloseHelper.quietClose(client);
            h.close();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            properties.forEach(System::clearProperty);
            JournalRetention.Seams.reset();
        }
    }

    // ------------------------------------------------------------------ load

    /** {@code n} more durable order commands, each one journal record; waits until every live member applied them. */
    void orders(final int n) {
        for (int i = 0; i < n; i++) {
            final long o = nextOrder++;
            if (MeClusterHarness.offer(client, DurableLedgerV2Test.create(o, 0, 100, 0, 59_000 + (o % 100), 1),
                    10_000) <= 0) {
                throw new AssertionError("order " + o + " was not accepted");
            }
            if (i % 100 == 99) {
                MeClusterHarness.await(client, () -> false, 20);
            }
        }
        final long expected = nextOrder; // the config record is journalSeq 1, order o is journalSeq o + 1
        await(() -> live().stream().allMatch(m -> head(m) >= expected), 60_000,
                () -> "journalSeq " + expected + " applied on every live member: " + heads());
    }

    /** The last journalSeq {@code m}'s engine allocated. */
    static long head(final Member m) {
        return m.engine().meIdentity().nextJournalSeq() - 1;
    }

    long head() {
        return head(h.awaitLeaderQuietly());
    }

    private String heads() {
        final StringBuilder sb = new StringBuilder();
        for (final Member m : live()) {
            sb.append(m.id).append('=').append(head(m)).append(' ');
        }
        return sb.toString();
    }

    void keepAlive() {
        if (client != null && System.currentTimeMillis() - lastKeepAliveMs >= 1_000) {
            client.sendKeepAlive();
            lastKeepAliveMs = System.currentTimeMillis();
        }
    }

    void pumpMs(final long ms) {
        final long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) {
            keepAlive();
            MeClusterHarness.await(client, () -> false, 50);
        }
    }

    void await(final BooleanSupplier done, final long timeoutMs, final Supplier<String> why) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(why.get());
            }
            keepAlive();
            MeClusterHarness.await(client, () -> false, 50);
        }
    }

    // ------------------------------------------------------------------ the journal archive, read back

    /** A session of the test's own on {@code m}'s journal archive, for this process run of the member. */
    AeronArchive archive(final Member m) {
        return archives.computeIfAbsent(m.id + "/" + m.starts,
                k -> AeronArchive.connect(m.journalRuntime.archiveClientContext()));
    }

    List<Rec> recordings(final Member m) {
        final AeronArchive archive = archive(m);
        final List<long[]> rows = new ArrayList<>();
        archive.listRecordingsForUri(0, 10_000, PROFILE.channelFragment(), PROFILE.streamId(),
                (c, k, recordingId, st, sp, startPosition, stopPosition, i, t, se, mm, sid, str, ch, oc, src) ->
                        rows.add(new long[] {recordingId, startPosition, stopPosition}));
        final List<Rec> out = new ArrayList<>();
        for (final long[] r : rows) {
            long end = r[2];
            boolean active = false;
            if (end == AeronArchive.NULL_POSITION) {
                end = archive.getRecordingPosition(r[0]);
                if (end == AeronArchive.NULL_POSITION) {
                    end = archive.getStopPosition(r[0]);
                } else {
                    active = true;
                }
            }
            out.add(new Rec(r[0], r[1], end, active));
        }
        return out;
    }

    /** The one active recording of the journal on {@code m}; fails when there is none or more than one. */
    Rec active(final Member m) {
        Rec found = null;
        for (final Rec r : recordings(m)) {
            if (r.active()) {
                if (found != null) {
                    throw new AssertionError("member " + m.id + ": two active journal recordings");
                }
                found = r;
            }
        }
        if (found == null) {
            throw new AssertionError("member " + m.id + ": no active journal recording");
        }
        return found;
    }

    long retained(final Member m) {
        long sum = 0;
        for (final Rec r : recordings(m)) {
            sum += r.bytes();
        }
        return sum;
    }

    int recordingsWithBytes(final Member m) {
        int n = 0;
        for (final Rec r : recordings(m)) {
            if (r.bytes() > 0) {
                n++;
            }
        }
        return n;
    }

    /** One record as the journal archive holds it. */
    record Held(long position, long seq, long epoch, long logPosition) {
    }

    /** Every record of recording {@code r} on {@code m}, in order; null when the member purged under the read. */
    List<Held> read(final Member m, final Rec r) {
        final List<Held> out = new ArrayList<>();
        if (r.bytes() == 0) {
            return out;
        }
        try {
            new ShadowReplay(archive(m).context().aeron(), archive(m), SystemNanoClock.INSTANCE).readAll(r.id(),
                    r.start(), r.end(), (buffer, offset, length, header) -> out.add(new Held(
                            header.position() - ((header.frameLength() + 31) & -32),
                            buffer.getLong(offset + 8, ByteOrder.LITTLE_ENDIAN),
                            buffer.getLong(offset + 16, ByteOrder.LITTLE_ENDIAN),
                            buffer.getLong(offset + 24, ByteOrder.LITTLE_ENDIAN))),
                    System.nanoTime() + 30_000_000_000L);
        } catch (final io.aeron.archive.client.ArchiveException purgedUnderTheRead) {
            return null;
        }
        return out;
    }

    /** Every record {@code m}'s journal archive still holds: journalSeq to the number of copies; null: read again. */
    TreeMap<Long, Integer> held(final Member m) {
        final TreeMap<Long, Integer> seqs = new TreeMap<>();
        for (final Rec r : recordings(m)) {
            final List<Held> records = read(m, r);
            if (records == null) {
                return null;
            }
            for (final Held record : records) {
                seqs.merge(record.seq(), 1, Integer::sum);
            }
        }
        return seqs;
    }

    /** The last journalSeq in {@code m}'s active recording as the archive has recorded it; -1 when it holds none. */
    long recorded(final Member m) {
        final Rec r = active(m);
        if (r.bytes() == 0) {
            return -1;
        }
        final JournalRetention.Peek last = new JournalRetention.AeronPort(archive(m).context().aeron(), archive(m),
                SystemNanoClock.INSTANCE, PROFILE).last(new JournalRetention.Recording(r.id(), r.start(), r.end(),
                true, SEG, SEG), System.nanoTime() + 10_000_000_000L);
        return last.readable() ? last.key().journalSeq() : -1;
    }

    /** The start an active recording ending at {@code end} is cut to: two whole segments below its head segment. */
    static long expectedStart(final long end) {
        return Math.max(0, (end & -(long) SEG) - 2L * SEG);
    }

    // ------------------------------------------------------------------ the member's own view

    JournalRetentionAgent.Status status(final Member m) {
        final JournalRetentionAgent agent = m.service.journalRetention();
        if (agent == null) {
            throw new AssertionError("member " + m.id + " runs no journal retention");
        }
        return agent.status(JOURNAL);
    }

    /** The value of {@code match_journal_retention_<name>{journal="settlement"}} on {@code m}'s {@code /metrics}. */
    long metric(final Member m, final String name) {
        final String series = "match_journal_retention_" + name + "{journal=\"" + JOURNAL + "\"} ";
        for (final String line : m.service.renderMetrics().split("\n")) {
            if (line.startsWith(series)) {
                return Long.parseLong(line.substring(series.length()).trim());
            }
        }
        throw new AssertionError("member " + m.id + ": no series " + series);
    }

    boolean said(final String text) {
        return lines.stream().anyMatch(l -> l.contains(text));
    }

    // ------------------------------------------------------------------ the reader files

    File clusterDir(final Member m) {
        return new File(m.baseDir(), ClusterConfig.CLUSTER_SUB_DIR);
    }

    /** The kit's command on {@code m}'s cluster directory; returns its exit code, its lines go to the capture. */
    int tool(final Member m, final String command, final String... flags) {
        final String[] args = new String[3 + flags.length];
        args[0] = command;
        args[1] = "--cluster-dir";
        args[2] = clusterDir(m).getAbsolutePath();
        System.arraycopy(flags, 0, args, 3, flags.length);
        return JournalReaderTool.run(args, System.out, System.err, System::currentTimeMillis);
    }

    void set(final Member m, final String reader, final long applied) {
        final int exit = tool(m, "set", "--engine", "match", "--cluster-id", "0", "--genesis-id",
                String.valueOf(GENESIS), "--journal", JOURNAL, "--reader", reader, "--applied-journal-seq",
                String.valueOf(applied), "--source", "test-pusher");
        if (exit != 0) {
            throw new AssertionError("JournalReaderTool set on member " + m.id + " exit " + exit + ": " + lines);
        }
    }

    void setAll(final String reader, final long applied) {
        for (final Member m : live()) {
            set(m, reader, applied);
        }
    }

    // ------------------------------------------------------------------ a reader and its pusher

    /**
     * A reader of the journal: it reads the members' journal archives in turn and applies records in sequence, one
     * after the other from 1. Its cursor is what it has applied. A next record that no member holds any more is a
     * gap: the retention deleted a record this reader had not applied.
     */
    final class Reader {
        final String name;
        volatile long applied;
        /** Test seam: a reader that stopped applying (dead or stuck). */
        boolean frozen;
        private int turn;
        private int pollsSinceProgress;
        private final java.util.TreeSet<Long> pending = new java.util.TreeSet<>();
        /** This reader's own resume memo: per member, run and recording, the position it has read up to. */
        private final Map<String, Long> memo = new HashMap<>();

        Reader(final String name) {
            this.name = name;
        }

        void poll() {
            if (frozen) {
                return;
            }
            final List<Member> live = live();
            final Member source = live.get(turn++ % live.size());
            for (final Rec r : recordings(source)) {
                final String key = source.id + "/" + source.starts + "/" + r.id();
                // a memo below the recording's start is dropped: the member purged what lay between
                final long from = Math.max(r.start(), memo.getOrDefault(key, 0L));
                if (r.end() <= from) {
                    continue;
                }
                final List<Held> records = read(source, new Rec(r.id(), from, r.end(), r.active()));
                if (records == null) {
                    continue; // purged between the listing and the replay: the next turn lists again
                }
                for (final Held record : records) {
                    if (record.epoch() != GENESIS) {
                        throw new AssertionError("reader " + name + ": a record of epoch " + record.epoch());
                    }
                    if (record.seq() > applied) {
                        pending.add(record.seq());
                    }
                }
                memo.put(key, r.end());
            }
            final long before = applied;
            long next = applied;
            while (pending.remove(next + 1)) {
                next++;
            }
            applied = next;
            pollsSinceProgress = next > before ? 0 : pollsSinceProgress + 1;
            if (!pending.isEmpty() && pollsSinceProgress >= 2 * live.size()) {
                throw new AssertionError("reader " + name + " gap: its next journal record " + (applied + 1)
                        + " is on no member (the lowest record above it that a member holds is " + pending.first()
                        + ")");
            }
        }
    }

    /**
     * Delivers a reader's cursor to every live member: each step writes the value the previous step read (so a file
     * always carries a cursor at least one step old), and member {@code late} gets it one step later still.
     */
    final class Pusher {
        final Reader reader;
        final Member late;
        private long read = -1;
        private long older = -1;
        /** Test seam: this is added to every cursor written (a pusher that sends a wrong number). */
        long skew;

        Pusher(final Reader reader, final Member late) {
            this.reader = reader;
            this.late = late;
        }

        void step() {
            for (final Member m : live()) {
                final long value = m == late ? older : read;
                if (value >= 0) {
                    set(m, reader.name, value + skew);
                }
            }
            older = read;
            read = reader.applied;
        }
    }

    /**
     * Runs the readers and their pushers (a pusher step every {@code stepMs}) and the client session until
     * {@code done}; fails with {@code why} after {@code timeoutMs}.
     */
    void pump(final List<Reader> readers, final List<Pusher> pushers, final long stepMs, final BooleanSupplier done,
              final long timeoutMs, final Supplier<String> why) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        long nextStep = 0;
        while (true) {
            readers.forEach(Reader::poll);
            if (System.currentTimeMillis() >= nextStep) {
                pushers.forEach(Pusher::step);
                nextStep = System.currentTimeMillis() + stepMs;
            }
            if (done.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(why.get());
            }
            keepAlive();
            MeClusterHarness.await(client, () -> false, 50);
        }
    }

    String describe() {
        final StringBuilder sb = new StringBuilder();
        for (final Member m : live()) {
            final JournalRetentionAgent agent = m.service.journalRetention();
            final JournalRetentionAgent.Status s = agent == null ? null : agent.status(JOURNAL);
            sb.append("[member ").append(m.id).append(" head ").append(head(m)).append(' ').append(recordings(m))
                    .append(' ').append(agent == null ? "no journal retention thread"
                            : s.report == null ? "no pass" : String.valueOf(s.report)).append("] ");
        }
        return sb.toString();
    }
}
