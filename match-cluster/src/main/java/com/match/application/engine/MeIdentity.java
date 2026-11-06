// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

/**
 * RC2 S2: the genesis identity of this ME (contract section 2.1). ONE mutable object owned by
 * {@link Engine}, created unbound (epoch 0) at construction. It is bound once, by a FRESH at
 * behaviour >= 2, or filled by a snapshot restore, and never rebound. It is never derived from
 * env, time, recordingId or the node.
 *
 * <p>The settlement journal and the heartbeat hold THIS reference, never a copy, so a later bind
 * is seen by both. Every field is written on the cluster service thread only. {@link #meEpoch} is
 * volatile because the /metrics scraper reads it ({@code match_engine_me_epoch}).</p>
 *
 * <p>Invariant on every restore and after every decision: bound iff the recorded
 * {@code behaviorVersion >= 2}, and then {@code meEpoch == genesisId > 0} and
 * {@code nextJournalSeq >= 2}.</p>
 */
public final class MeIdentity {

    private volatile long meEpoch;
    // RC2 S3: written on the service thread only (the one allocator), published with release
    // semantics and read with acquire so the /metrics scraper (match_engine_next_journal_seq, the
    // S7 4.4 rollback precheck input) never sees a torn or stale-forever value (review NOTE-1).
    private long nextJournalSeq;
    private static final java.lang.invoke.VarHandle NEXT_JOURNAL_SEQ;
    static {
        try {
            NEXT_JOURNAL_SEQ = java.lang.invoke.MethodHandles.lookup()
                    .findVarHandle(MeIdentity.class, "nextJournalSeq", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    private long behaviorVersion = EngineConfigState.DEFAULT_BEHAVIOR_VERSION;

    /** True once a FRESH at behaviour >= 2 or a restore has bound the epoch. */
    public boolean isBound() {
        return meEpoch != 0;
    }

    /** The recorded genesisId once bound, else 0 (stamped on every journal record and heartbeat). */
    public long meEpoch() {
        return meEpoch;
    }

    /** The next journalSeq to allocate; 0 while unbound. Safe from any thread (acquire). */
    public long nextJournalSeq() {
        return (long) NEXT_JOURNAL_SEQ.getAcquire(this);
    }

    /**
     * The recorded behaviour version as journal record 3 carries it: the bound behaviour, or 1
     * while unbound (behaviour 1 recorded, or nothing recorded).
     */
    public long behaviorVersion() {
        return behaviorVersion;
    }

    /**
     * FRESH at behaviour >= 2: bind {@code meEpoch := genesisId} and start journalSeq at 1. The
     * caller then consumes journalSeq 1 with {@link #allocateJournalSeq()}.
     *
     * @throws IllegalStateException when already bound (an epoch is never rebound) or when the
     *                               arguments break the invariant (the state machine validated
     *                               them, so this is a programming error)
     */
    void bindFresh(final long genesisId, final long recordedBehaviorVersion) {
        if (isBound()) {
            throw new IllegalStateException("MeIdentity already bound to meEpoch=" + meEpoch
                    + "; an epoch is never rebound");
        }
        if (genesisId <= 0 || recordedBehaviorVersion < 2) {
            throw new IllegalStateException("MeIdentity bind needs genesisId > 0 and behaviorVersion >= 2"
                    + " (genesisId=" + genesisId + " behaviorVersion=" + recordedBehaviorVersion + ")");
        }
        NEXT_JOURNAL_SEQ.setRelease(this, 1L);
        this.behaviorVersion = recordedBehaviorVersion;
        this.meEpoch = genesisId;
    }

    /**
     * The ONE journalSeq allocator (RC2 S3 contract section 2): consume and return the next
     * journalSeq of records 1-5. Bound identities only. No volatile read on this path (review
     * NOTE-2): an unbound identity has {@code nextJournalSeq == 0}, a bound one {@code >= 1}.
     */
    long allocateJournalSeq() {
        final long seq = nextJournalSeq;
        if (seq == 0L) {
            throw new IllegalStateException("journalSeq allocated on an unbound MeIdentity");
        }
        NEXT_JOURNAL_SEQ.setRelease(this, seq + 1);
        return seq;
    }

    /**
     * Snapshot restore of the identity block (already range-checked by the codec):
     * {@code meEpoch == genesisId > 0}, {@code nextJournalSeq >= 2}, {@code behaviorVersion >= 2}.
     */
    // RC2 S3 review FIX-2: package-private, so Engine.restoreIdentity (which also turns dense
    // allocation on) is the only way to bind an engine's identity from a snapshot.
    void restore(final long restoredMeEpoch, final long restoredNextJournalSeq,
                        final long restoredBehaviorVersion) {
        if (isBound()) {
            throw new IllegalStateException("MeIdentity already bound to meEpoch=" + meEpoch
                    + "; restore runs on a fresh engine only");
        }
        if (restoredMeEpoch <= 0 || restoredNextJournalSeq < 2 || restoredBehaviorVersion < 2) {
            throw new IllegalStateException("MeIdentity restore out of range: meEpoch=" + restoredMeEpoch
                    + " nextJournalSeq=" + restoredNextJournalSeq + " behaviorVersion=" + restoredBehaviorVersion);
        }
        NEXT_JOURNAL_SEQ.setRelease(this, restoredNextJournalSeq);
        this.behaviorVersion = restoredBehaviorVersion;
        this.meEpoch = restoredMeEpoch;
    }

    @Override
    public String toString() {
        return "MeIdentity{meEpoch=" + meEpoch + " nextJournalSeq=" + nextJournalSeq
                + " behaviorVersion=" + behaviorVersion + "}";
    }
}
