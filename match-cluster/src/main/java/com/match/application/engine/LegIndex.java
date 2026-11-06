// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import org.agrona.collections.Long2LongHashMap;

/**
 * RC2 S4 (contract 5.2): the one owner of the {@code orderId -> omsOrderId} correlation map and of
 * a primitive {@code omsOrderId -> resting leg count}. Every put and remove in {@link Engine} and the
 * snapshot restore go through here, so the two maps cannot drift; the raw map is exposed read-only
 * (the serializer iterates it) and is never mutated outside this class.
 *
 * <p>Maintained in both behaviours. In behaviour 1 the count is unobservable (no output reads it).
 * In behaviour 2 every resting leg carries {@code omsOrderId = idHigh >= 1} and the keys are in
 * bijection with the resting legs (invariant I6), so {@link #count} is the {@code LEG_LIVE} input of
 * an {@code ORDER} release.</p>
 *
 * <p>Single-threaded (service thread). Allocation-free after warm-up: both maps are agrona primitive
 * maps whose removal compacts the chain in place; only a resize allocates.</p>
 */
public final class LegIndex {

    /** The missing value of the correlation map (as before S4). */
    public static final long MISSING = -1L;

    private final Long2LongHashMap orderIdToOmsOrderId = new Long2LongHashMap(MISSING);
    // omsOrderId -> number of keys mapping to it. 0 is the missing value: a count is removed when it
    // reaches 0, never stored as 0.
    private final Long2LongHashMap legCount = new Long2LongHashMap(0L);

    /** Map {@code orderId} to {@code omsOrderId}, replacing (and un-counting) a previous mapping. */
    public void put(long orderId, long omsOrderId) {
        final long previous = orderIdToOmsOrderId.put(orderId, omsOrderId);
        if (previous != MISSING) {
            decrement(previous);
        }
        legCount.put(omsOrderId, legCount.get(omsOrderId) + 1);
    }

    /** Remove {@code orderId}; returns its omsOrderId or {@link #MISSING}. */
    public long remove(long orderId) {
        final long previous = orderIdToOmsOrderId.remove(orderId);
        if (previous != MISSING) {
            decrement(previous);
        }
        return previous;
    }

    /** The omsOrderId of {@code orderId}, or {@link #MISSING}. */
    public long get(long orderId) {
        return orderIdToOmsOrderId.get(orderId);
    }

    /** Number of mapped orderIds (resting legs, behaviour 2) carrying {@code omsOrderId}. */
    public long count(long omsOrderId) {
        return legCount.get(omsOrderId);
    }

    /** Number of mapped orderIds. */
    public int size() {
        return orderIdToOmsOrderId.size();
    }

    /** Number of distinct omsOrderIds with at least one mapped orderId. */
    public int distinctOmsOrderIds() {
        return legCount.size();
    }

    /** Snapshot restore: forget everything. */
    public void clear() {
        orderIdToOmsOrderId.clear();
        legCount.clear();
    }

    /** Snapshot restore: one (orderId, omsOrderId) pair of the omsMap section. */
    public void restore(long orderId, long omsOrderId) {
        put(orderId, omsOrderId);
    }

    /** Read view for the snapshot serializer and diagnostics. Never mutate it. */
    public Long2LongHashMap view() {
        return orderIdToOmsOrderId;
    }

    private void decrement(long omsOrderId) {
        final long n = legCount.get(omsOrderId) - 1;
        if (n <= 0) {
            legCount.remove(omsOrderId);
        } else {
            legCount.put(omsOrderId, n);
        }
    }
}
