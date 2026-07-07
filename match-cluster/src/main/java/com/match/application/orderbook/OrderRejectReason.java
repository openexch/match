// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

/**
 * Reject reason codes for order book admission.
 * Using int constants instead of enum for zero-allocation dispatch.
 *
 * Loud-limits principle: every capacity or validity boundary must be
 * reported to the caller with a reason — never silently dropped.
 */
public final class OrderRejectReason {
    public static final int NONE = 0;
    public static final int PRICE_OUT_OF_RANGE = 1;
    public static final int PRICE_OFF_TICK = 2;
    public static final int LEVEL_FULL = 3;
    /**
     * The book's shared order pool is exhausted (array-backed book). This is the
     * array-backed equivalent of {@link #LEVEL_FULL}: a single, book-wide, runtime-
     * adjustable capacity bound rather than a per-level 64-order cap.
     */
    public static final int BOOK_FULL = 4;
    /**
     * A notional/cost computation would overflow 64-bit fixed-point. Rejecting is
     * the only correct answer: wrong money must never enter the book (P1.1,
     * match#30). Reserved here so the diag counter and harness can report it;
     * detection is wired when FixedPoint.multiply learns to throw.
     */
    public static final int OVERFLOW = 5;
    /**
     * The order's quantity (or a market buy's total budget) is not strictly positive
     * (match#91). {@code quantity} is signed int64 on the wire, so 0/negative are
     * representable; admitting them silently loses a LIMIT (no status ever fires) and
     * rests a qty=0 LIMIT_MAKER as a head-of-level poison pill that abandons the whole
     * price level. Rejecting at admission is the only correct answer. A distinct code
     * (not a reused one) because reject codes become user-visible wire values.
     */
    public static final int INVALID_QUANTITY = 6;

    private OrderRejectReason() {} // Constants only

    public static String describe(int reason) {
        switch (reason) {
            case NONE: return "NONE";
            case PRICE_OUT_OF_RANGE: return "PRICE_OUT_OF_RANGE";
            case PRICE_OFF_TICK: return "PRICE_OFF_TICK";
            case LEVEL_FULL: return "LEVEL_FULL";
            case BOOK_FULL: return "BOOK_FULL";
            case OVERFLOW: return "OVERFLOW";
            case INVALID_QUANTITY: return "INVALID_QUANTITY";
            default: return "UNKNOWN(" + reason + ")";
        }
    }
}
