package com.match.application.publisher;

/**
 * Order status constants for publisher events.
 * Using int constants instead of enum for zero-allocation dispatch.
 */
public final class OrderStatusType {
    public static final int NEW = 0;
    public static final int PARTIALLY_FILLED = 1;
    public static final int FILLED = 2;
    public static final int CANCELLED = 3;
    public static final int REJECTED = 4;

    private OrderStatusType() {} // Constants only
}
