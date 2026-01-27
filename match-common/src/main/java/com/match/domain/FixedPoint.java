package com.match.domain;

/**
 * Fixed-point arithmetic utilities for ultra-low latency price/quantity calculations.
 * Uses 8 decimal places (10^8 scaling factor) - suitable for cryptocurrency exchanges.
 *
 * All prices and quantities are stored as long values to avoid BigDecimal allocations.
 */
public final class FixedPoint {

    /**
     * Number of decimal places in fixed-point representation
     */
    public static final int SCALE = 8;

    /**
     * Scaling factor: 10^8 = 100,000,000
     */
    public static final long SCALE_FACTOR = 100_000_000L;

    /**
     * Maximum safe value before overflow in multiplication
     */
    public static final long MAX_SAFE_VALUE = Long.MAX_VALUE / SCALE_FACTOR;

    private FixedPoint() {
        // Utility class
    }

    /**
     * Convert double to fixed-point long representation
     */
    public static long fromDouble(double value) {
        return Math.round(value * SCALE_FACTOR);
    }

    /**
     * Convert fixed-point long to double
     */
    public static double toDouble(long fixedPoint) {
        return (double) fixedPoint / SCALE_FACTOR;
    }

    /**
     * Multiply two fixed-point values (result in fixed-point)
     */
    public static long multiply(long a, long b) {
        // To avoid overflow: (a * b) / SCALE_FACTOR
        // Use long division to maintain precision
        return (a * b) / SCALE_FACTOR;
    }

    /**
     * Divide two fixed-point values (result in fixed-point)
     */
    public static long divide(long numerator, long denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("Division by zero");
        }
        // (numerator * SCALE_FACTOR) / denominator
        return (numerator * SCALE_FACTOR) / denominator;
    }

    /**
     * Get minimum of two fixed-point values
     */
    public static long min(long a, long b) {
        return a < b ? a : b;
    }

    /**
     * Get maximum of two fixed-point values
     */
    public static long max(long a, long b) {
        return a > b ? a : b;
    }

    /**
     * Check if value is zero
     */
    public static boolean isZero(long value) {
        return value == 0L;
    }

    /**
     * Check if value is positive
     */
    public static boolean isPositive(long value) {
        return value > 0L;
    }

    /**
     * Check if value is negative
     */
    public static boolean isNegative(long value) {
        return value < 0L;
    }

    /**
     * Compare two fixed-point values
     * @return negative if a < b, zero if equal, positive if a > b
     */
    public static int compare(long a, long b) {
        return Long.compare(a, b);
    }

    /**
     * Format fixed-point value as string with appropriate decimal places
     */
    public static String format(long fixedPoint) {
        long wholePart = fixedPoint / SCALE_FACTOR;
        long fractionalPart = Math.abs(fixedPoint % SCALE_FACTOR);
        return String.format("%d.%08d", wholePart, fractionalPart);
    }
}
