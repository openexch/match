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
    private long nextJournalSeq;
    private long behaviorVersion = EngineConfigState.DEFAULT_BEHAVIOR_VERSION;

    /** True once a FRESH at behaviour >= 2 or a restore has bound the epoch. */
    public boolean isBound() {
        return meEpoch != 0;
    }

    /** The recorded genesisId once bound, else 0 (stamped on every journal record and heartbeat). */
    public long meEpoch() {
        return meEpoch;
    }

    /** The next journalSeq to allocate; 0 while unbound. */
    public long nextJournalSeq() {
        return nextJournalSeq;
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
        this.nextJournalSeq = 1L;
        this.behaviorVersion = recordedBehaviorVersion;
        this.meEpoch = genesisId;
    }

    /** Consume and return the next journalSeq (S2: record 5 only). Bound identities only. */
    long allocateJournalSeq() {
        if (!isBound()) {
            throw new IllegalStateException("journalSeq allocated on an unbound MeIdentity");
        }
        return nextJournalSeq++;
    }

    /**
     * Snapshot restore of the identity block (already range-checked by the codec):
     * {@code meEpoch == genesisId > 0}, {@code nextJournalSeq >= 2}, {@code behaviorVersion >= 2}.
     */
    public void restore(final long restoredMeEpoch, final long restoredNextJournalSeq,
                        final long restoredBehaviorVersion) {
        if (isBound()) {
            throw new IllegalStateException("MeIdentity already bound to meEpoch=" + meEpoch
                    + "; restore runs on a fresh engine only");
        }
        if (restoredMeEpoch <= 0 || restoredNextJournalSeq < 2 || restoredBehaviorVersion < 2) {
            throw new IllegalStateException("MeIdentity restore out of range: meEpoch=" + restoredMeEpoch
                    + " nextJournalSeq=" + restoredNextJournalSeq + " behaviorVersion=" + restoredBehaviorVersion);
        }
        this.nextJournalSeq = restoredNextJournalSeq;
        this.behaviorVersion = restoredBehaviorVersion;
        this.meEpoch = restoredMeEpoch;
    }

    @Override
    public String toString() {
        return "MeIdentity{meEpoch=" + meEpoch + " nextJournalSeq=" + nextJournalSeq
                + " behaviorVersion=" + behaviorVersion + "}";
    }
}
