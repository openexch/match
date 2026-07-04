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
