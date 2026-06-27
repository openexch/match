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

    private OrderRejectReason() {} // Constants only

    public static String describe(int reason) {
        switch (reason) {
            case NONE: return "NONE";
            case PRICE_OUT_OF_RANGE: return "PRICE_OUT_OF_RANGE";
            case PRICE_OFF_TICK: return "PRICE_OFF_TICK";
            case LEVEL_FULL: return "LEVEL_FULL";
            case BOOK_FULL: return "BOOK_FULL";
            default: return "UNKNOWN(" + reason + ")";
        }
    }
}
