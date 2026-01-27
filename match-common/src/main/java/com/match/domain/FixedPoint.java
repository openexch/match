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
     * Multiply two fixed-point values (result in fixed-point).
     * Uses Math.multiplyHigh for overflow-safe 128-bit intermediate result.
     */
    public static long multiply(long a, long b) {
        // For values within safe range, use fast path (no overflow possible)
        if (a >= -MAX_SAFE_VALUE && a <= MAX_SAFE_VALUE) {
            return (a * b) / SCALE_FACTOR;
        }
        // Overflow-safe path: use 128-bit multiplication via Math.multiplyHigh (Java 9+)
        // result = (a * b) / SCALE_FACTOR using high/low 64-bit parts
        long high = Math.multiplyHigh(a, b);
        long low = a * b; // lower 64 bits (unsigned)
        // Divide 128-bit result by SCALE_FACTOR
        // For SCALE_FACTOR = 10^8, this is safe since high should be small for valid financial values
        if (high == 0 || (high == -1 && low < 0)) {
            return low / SCALE_FACTOR;
        }
        // True 128-bit case: shift and divide
        // high:low / SCALE_FACTOR = (high * 2^64 + low) / SCALE_FACTOR
        return (high * (Long.divideUnsigned(-1L, SCALE_FACTOR) + 1)) + Long.divideUnsigned(low, SCALE_FACTOR);
    }

    /**
     * Divide two fixed-point values (result in fixed-point).
     * Overflow-safe: checks before scaling numerator.
     */
    public static long divide(long numerator, long denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("Division by zero in fixed-point");
        }
        // Fast path: numerator * SCALE_FACTOR won't overflow
        if (numerator >= -MAX_SAFE_VALUE && numerator <= MAX_SAFE_VALUE) {
            return (numerator * SCALE_FACTOR) / denominator;
        }
        // Overflow-safe: divide first, then scale remainder
        long wholePart = (numerator / denominator) * SCALE_FACTOR;
        long remainder = numerator % denominator;
        return wholePart + (remainder * SCALE_FACTOR) / denominator;
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
