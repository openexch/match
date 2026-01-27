package com.match.application.publisher;

/**
 * Event type constants for publisher ring buffer.
 * Using int constants instead of enum for zero-allocation dispatch.
 */
public final class PublishEventType {
    public static final int TRADE_EXECUTION = 0;
    public static final int ORDER_BOOK_UPDATE = 1;
    public static final int ORDER_STATUS_UPDATE = 2;

    private PublishEventType() {} // Constants only
}
