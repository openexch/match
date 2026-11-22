// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.metrics;

/**
 * Hot-path-safe metrics state for a cluster node (match#33).
 *
 * All record/stamp methods are called ONLY on the cluster service (agent)
 * thread and write plain fields — no volatiles, no allocation, no locks on the
 * per-order path. Cross-thread visibility for the /metrics scraper uses the
 * piggyback pattern: the agent thread bumps the volatile {@link #publish()}
 * sequence from the flush timer, and the scraper reads {@link #acquire()}
 * FIRST — the volatile read then makes every plain write that happened before
 * the corresponding publish visible.
 *
 * Order latency uses a preallocated log2 histogram, sampled 1-in-{@value
 * #SAMPLE_MASK}+1 so the two System.nanoTime() calls stay off most orders
 * (the load-test baseline is ~800k orders/s; see docs/perf/).
 */
public final class NodeMetrics {

    /** Bucket i counts latencies < 2^(FIRST_POW+i) ns: 1us .. ~1s, then overflow. */
    static final int FIRST_POW = 10;
    static final int LAST_POW = 30;
    static final int BUCKETS = LAST_POW - FIRST_POW + 2;

    static final int SAMPLE_MASK = 15; // record 1 in 16

    // Written only on the agent thread.
    private final long[] latencyBuckets = new long[BUCKETS];
    private long latencySumNanos;
    private long latencyCount;
    private int sampleTick;
    private long lastSnapshotMs;

    // Rare writes (role change / startup) — volatile is fine here.
    private volatile int role = -1; // Cluster.Role ordinal: 0=follower,1=candidate,2=leader
    private volatile int memberId = -1;

    private volatile long published;

    // The cluster log position of the last entry this node applied. One writer (the service thread), stored with
    // release semantics: no fence on the per-message path beyond an ordered store, no lock, no allocation, and a
    // scraper sees a fresh value without waiting for the next publish().
    private static final java.lang.invoke.VarHandle LOG_POSITION;
    static {
        try {
            LOG_POSITION = java.lang.invoke.MethodHandles.lookup()
                    .findVarHandle(NodeMetrics.class, "logPosition", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    @SuppressWarnings("unused") // written through LOG_POSITION
    private volatile long logPosition = NEVER;

    /** Whether the next order should be latency-sampled (call before nanoTime). */
    public boolean shouldSample() {
        return (sampleTick++ & SAMPLE_MASK) == 0;
    }

    public void recordOrderLatency(long nanos) {
        int bucket = 63 - Long.numberOfLeadingZeros(Math.max(1, nanos)); // floor(log2)
        int idx = Math.min(Math.max(bucket - FIRST_POW, 0), BUCKETS - 1);
        latencyBuckets[idx]++;
        latencySumNanos += nanos;
        latencyCount++;
    }

    public void stampSnapshot(long epochMs) {
        lastSnapshotMs = epochMs;
    }

    /**
     * Where the last snapshot this node took or loaded sits in the log, and the hash of the state it holds, as
     * one pair read by a scraper: a position and a hash from two different stamps would show as a false
     * divergence for one poll.
     *
     * @param logPosition the snapshot's log position ({@link #NEVER} before any snapshot)
     * @param stateHash   the unsigned 32-bit state hash, or {@link #UNKNOWN_HASH}
     */
    public record SnapshotState(long logPosition, long stateHash) {
        public SnapshotState {
            checkStateHash(stateHash);
        }

        public boolean hashKnown() {
            return stateHash != UNKNOWN_HASH;
        }
    }

    /** {@link SnapshotState#logPosition()} before this node took or loaded any snapshot. */
    public static final long NEVER = -1L;
    /** {@link SnapshotState#stateHash()} when no hash is known; the series is then omitted. */
    public static final long UNKNOWN_HASH = -1L;
    static final long MAX_STATE_HASH = 0xFFFF_FFFFL;

    // A sequence lock over two longs, so the service thread publishes the pair without allocating. One writer
    // (the service thread); an odd sequence means a stamp is in progress. All three fields are volatile, so the
    // reader's sequence check orders against the writer's field writes without extra fences.
    private volatile long snapshotStateSeq;
    private volatile long snapshotPosition = NEVER;
    private volatile long snapshotStateHash = UNKNOWN_HASH;

    private static void checkStateHash(final long stateHash) {
        if (stateHash < UNKNOWN_HASH || stateHash > MAX_STATE_HASH) {
            throw new IllegalArgumentException("state hash out of the unsigned 32-bit range: " + stateHash);
        }
    }

    /**
     * Service thread only, after a snapshot was taken or loaded: publish its position and hash as one pair.
     * Allocates nothing. A hash outside {@code 0..4294967295} and not {@link #UNKNOWN_HASH} is refused before
     * anything is written, so the previous pair stays readable.
     */
    public void stampSnapshotState(long logPosition, long stateHash) {
        checkStateHash(stateHash);
        final long seq = snapshotStateSeq;
        snapshotStateSeq = seq + 1;
        snapshotPosition = logPosition;
        snapshotStateHash = stateHash;
        snapshotStateSeq = seq + 2;
    }

    /** The last stamped snapshot state, both values from one stamp; any thread. */
    public SnapshotState snapshotState() {
        while (true) {
            final long before = snapshotStateSeq;
            if ((before & 1L) == 0) {
                final long position = snapshotPosition;
                final long hash = snapshotStateHash;
                if (snapshotStateSeq == before) {
                    return new SnapshotState(position, hash);
                }
            }
            Thread.onSpinWait();
        }
    }

    /**
     * Service thread only: the cluster log position of the entry just applied. Allocates nothing; an ordered
     * store, so the hot path pays no full fence.
     */
    public void recordLogPosition(long position) {
        LOG_POSITION.setRelease(this, position);
    }

    /** The cluster log position of the last entry this node applied, {@link #NEVER} before any since start. */
    public long logPosition() {
        return (long) LOG_POSITION.getAcquire(this);
    }

    public void setRole(int roleOrdinal) {
        this.role = roleOrdinal;
    }

    public void setMemberId(int memberId) {
        this.memberId = memberId;
    }

    /** Agent thread: publish plain writes to scraper threads (flush-timer cadence). */
    public void publish() {
        published++;
    }

    /** Scraper thread: MUST be called before reading any snapshot getter below. */
    public long acquire() {
        return published;
    }

    // ---- scraper-side reads (after acquire()) ----

    public long bucketCount(int idx) {
        return latencyBuckets[idx];
    }

    public long latencySumNanos() {
        return latencySumNanos;
    }

    public long latencyCount() {
        return latencyCount;
    }

    public long lastSnapshotMs() {
        return lastSnapshotMs;
    }

    public int role() {
        return role;
    }

    public int memberId() {
        return memberId;
    }

    /** Upper bound (seconds) of bucket idx, for Prometheus le labels. */
    public static double bucketUpperSeconds(int idx) {
        return Math.pow(2, FIRST_POW + idx) / 1e9;
    }
}
