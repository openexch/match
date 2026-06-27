package com.match.determinism;

/**
 * Deterministic, injectable timestamp source for scenarios. The value handed to
 * {@code Engine.acceptOrder} is fully controlled here — never wall-clock — so replays see an
 * identical timestamp sequence. The scenario {@code CLOCK <n>} verb sets the absolute value;
 * by default every command in a scenario carries the same logical timestamp.
 */
public final class LogicalClock {

    /** Default starting logical timestamp when a scenario sets no CLOCK. */
    public static final long DEFAULT_START = 1000L;

    private long current;

    public LogicalClock() {
        this(DEFAULT_START);
    }

    public LogicalClock(long start) {
        this.current = start;
    }

    /** The timestamp applied to the next command. */
    public long now() {
        return current;
    }

    /** Set the absolute logical timestamp (the {@code CLOCK <n>} verb). */
    public void set(long value) {
        this.current = value;
    }
}
