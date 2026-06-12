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

    private OrderRejectReason() {} // Constants only

    public static String describe(int reason) {
        switch (reason) {
            case NONE: return "NONE";
            case PRICE_OUT_OF_RANGE: return "PRICE_OUT_OF_RANGE";
            case PRICE_OFF_TICK: return "PRICE_OFF_TICK";
            case LEVEL_FULL: return "LEVEL_FULL";
            default: return "UNKNOWN(" + reason + ")";
        }
    }
}
