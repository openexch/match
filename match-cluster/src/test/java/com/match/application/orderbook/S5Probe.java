// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.application.engine.Engine;

/**
 * RC2 S5 test seam (the S4Probe pattern): the one place the S5 tests read APIs that S5 adds (the
 * book contract of 3.3 / 5.4, the direct book's pure slot accessors, the section 8 counters). The
 * same test sources compile against the S4 base (307b48c) with a base copy of this class that
 * returns sentinels, so the RED runs fail by value, never by compilation. Pure delegation.
 */
public final class S5Probe {
    private S5Probe() {
    }

    /** True on the S5 code; the base copy returns false. */
    public static final boolean S5 = true;

    public static long restingRemaining(MatchingEngine me, long orderId, boolean isBuy) {
        return me.restingRemaining(orderId, isBuy);
    }

    public static boolean canFillCompletely(MatchingEngine me, boolean isBuy, long price, long quantity) {
        return me.canFillCompletely(isBuy, price, quantity);
    }

    public static int processLimitOrderNoRest(MatchingEngine me, long orderId, long userId, boolean isBuy,
                                              long price, long quantity) {
        return me.processLimitOrderNoRest(orderId, userId, isBuy, price, quantity);
    }

    private static DirectIndexOrderBook book(MatchingEngine me, boolean askSide) {
        final DirectMatchingEngine d = (DirectMatchingEngine) me;
        return askSide ? d.getAskBook() : d.getBidBook();
    }

    /** Direct book: free slots of the level at {@code price}. */
    public static int directFreeSlots(MatchingEngine me, boolean askSide, long price) {
        final DirectIndexOrderBook b = book(me, askSide);
        return b.freeSlotCount(b.priceIndexOf(price));
    }

    /** Direct book: the stored head slot of the level at {@code price}. */
    public static int directHeadSlot(MatchingEngine me, boolean askSide, long price) {
        final DirectIndexOrderBook b = book(me, askSide);
        return b.levelHeadSlot(b.priceIndexOf(price));
    }

    /**
     * Direct book: tombstones of the level at {@code price}, as {head tombstones (before the first
     * live order), middle tombstones (after it)}; read with the pure accessors and the exact bound.
     */
    public static int[] directTombstones(MatchingEngine me, boolean askSide, long price) {
        final DirectIndexOrderBook b = book(me, askSide);
        final int idx = b.priceIndexOf(price);
        final int m = b.getMaxOrdersPerLevel();
        int head = 0, middle = 0, live = 0;
        if (b.getOrderCount(idx) == 0) {
            return new int[]{0, 0};
        }
        int slot = b.levelHeadSlot(idx);
        int guard = m + 1;
        while (slot >= 0 && slot < m && --guard > 0) {
            if (b.slotQuantity(idx, slot) == 0) {
                if (live == 0) head++; else middle++;
            } else {
                live++;
            }
            slot = b.slotNext(idx, slot);
        }
        return new int[]{head, middle};
    }

    public static long invalidTif(Engine e) { return e.invalidTifCount(); }
    public static long invalidAmendReference(Engine e) { return e.invalidAmendReferenceCount(); }
    public static long invalidAmendType(Engine e) { return e.invalidAmendTypeCount(); }
    public static long ioc(Engine e) { return e.iocCount(); }
    public static long iocRemainder(Engine e) { return e.iocRemainderCount(); }
    public static long fok(Engine e) { return e.fokCount(); }
    public static long fokUnfillable(Engine e) { return e.fokUnfillableCount(); }
    public static long marketBudgetExhausted(Engine e) { return e.marketBudgetExhaustedCount(); }
    public static long amendReduced(Engine e) { return e.amendReducedCount(); }
    public static long amendNoRemainder(Engine e) { return e.amendNoRemainderCount(); }
    public static long amendRemainingMismatch(Engine e) { return e.amendRemainingMismatchCount(); }
}
