// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.LedgerV2TestAccess;
import com.match.application.orderbook.MatchingEngine;

/**
 * RC2 S4 test seam: the one place the S4 tests read APIs that S4 adds (counters, the ledger v2,
 * {@code LegIndex}, {@code restingUserId}). Every S4 test reaches them only through here, so the
 * same test sources compile against the S3 base with a base copy of this class that returns
 * sentinels (the RED runs then fail by value, never by compilation). Pure delegation.
 */
public final class S4Probe {
    private S4Probe() {
    }

    /** Owner of a resting order on one side, -1 when absent. */
    public static long restingUserId(MatchingEngine me, long orderId, boolean isBuy) {
        return me.restingUserId(orderId, isBuy);
    }

    /** Resting legs (omsMap keys) carrying {@code omsOrderId}. */
    public static long legCount(Engine e, long omsOrderId) {
        return e.legIndex().count(omsOrderId);
    }

    /** Distinct omsOrderIds with at least one leg. */
    public static long legDistinct(Engine e) {
        return e.legIndex().distinctOmsOrderIds();
    }

    /** Inflight size of the behaviour-2 ledger. */
    public static int ledgerSize(Engine e) {
        return e.ledgerV2().size();
    }

    /** True once a behaviour-2 ledger is installed. */
    public static boolean hasLedgerV2(Engine e) {
        return e.ledgerV2() != null;
    }

    /** The ledger v2 snapshot block bytes (the codec's writer), empty without a v2 ledger. */
    public static byte[] ledgerBlock(Engine e) {
        if (e.ledgerV2() == null) {
            return new byte[0];
        }
        final org.agrona.ExpandableArrayBuffer b = new org.agrona.ExpandableArrayBuffer();
        final int end = e.ledgerV2().write(b, 0);
        return java.util.Arrays.copyOf(b.byteArray(), end);
    }

    /** Floor count of the behaviour-2 ledger. */
    public static int floorCount(Engine e) {
        return e.ledgerV2().floorCount();
    }

    /** Tombstone ranges as {@code [lo0, hi0, lo1, hi1, ...]}. */
    public static long[] ranges(Engine e) {
        return e.ledgerV2().ranges();
    }

    /** The negative control: drop the entries of {@code h} without a tombstone. */
    public static int forgetWithoutTombstone(Engine e, long h) {
        return LedgerV2TestAccess.forgetWithoutTombstone(e, h);
    }

    public static long released(Engine e) { return e.releasedCount(); }
    public static long invalidWire(Engine e) { return e.invalidWireCount(); }
    public static long invalidKindBits(Engine e) { return e.invalidKindBitsCount(); }
    public static long invalidOwnerId(Engine e) { return e.invalidOwnerIdCount(); }
    public static long invalidSign(Engine e) { return e.invalidSignCount(); }
    public static long capacity(Engine e) { return e.capacityCount(); }
    public static long releaseApplied(Engine e) { return e.releaseAppliedCount(); }
    public static long releaseNoop(Engine e) { return e.releaseNoopCount(); }
    public static long releaseLegLive(Engine e) { return e.releaseLegLiveCount(); }
    public static long releaseNoopFrames(Engine e) { return e.releaseNoopFrameCount(); }
    public static long legacyLaneRejects(SbeDemuxer d) { return d.legacyLaneRejectCount(); }
}
