// SPDX-License-Identifier: Apache-2.0
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
     * Thrown when a fixed-point result cannot be represented in a signed 64-bit
     * value. Wrong money must never be returned silently (match#30): callers on
     * order-admission paths catch this and reject with OrderRejectReason.OVERFLOW.
     * The stack trace is suppressed so throwing stays cheap on the match thread
     * under hostile input; the throw sites are the few audited multiply/divide calls.
     */
    public static final class OverflowException extends ArithmeticException {
        public OverflowException() {
            super("fixed-point overflow");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * Multiply two fixed-point values exactly (result in fixed-point, truncated
     * toward zero).
     *
     * Path selection is by whether the PRODUCT fits in 64 bits — never by operand
     * magnitude, so argument order does not matter (the old first-operand guard let
     * multiply(smallQty, largePrice) wrap silently, and products in [2^63, 2^64)
     * came out negative). The wide path is an exact 128/64 division.
     *
     * @throws OverflowException if the true result does not fit in a signed long
     */
    public static long multiply(long a, long b) {
        final long hi = Math.multiplyHigh(a, b);
        final long lo = a * b;
        if (hi == (lo >> 63)) {
            // Product fits in a signed 64-bit value: divide directly (exact).
            return lo / SCALE_FACTOR;
        }
        // Wide path: |a| * |b| as an unsigned 128-bit value, divided exactly.
        // Math.abs(Long.MIN_VALUE) == Long.MIN_VALUE, which IS the correct
        // unsigned magnitude 2^63 for the unsigned multiply below.
        final boolean negative = (a ^ b) < 0;
        final long ua = Math.abs(a);
        final long ub = Math.abs(b);
        final long phi = Math.unsignedMultiplyHigh(ua, ub);
        final long plo = ua * ub;
        if (Long.compareUnsigned(phi, SCALE_FACTOR) >= 0) {
            throw new OverflowException(); // quotient would be >= 2^64
        }
        final long q = divideUnsigned128(phi, plo, SCALE_FACTOR);
        return signedResult(q, negative);
    }

    /**
     * Divide two fixed-point values exactly (result in fixed-point, truncated
     * toward zero): (numerator * SCALE_FACTOR) / denominator with a 128-bit
     * intermediate. The old wide path wrapped silently both when scaling large
     * numerators and when scaling large remainders.
     *
     * @throws ArithmeticException on division by zero
     * @throws OverflowException   if the true result does not fit in a signed long
     */
    public static long divide(long numerator, long denominator) {
        if (denominator == 0) {
            throw new ArithmeticException("Division by zero in fixed-point");
        }
        // Fast path: scaling cannot overflow and |result| <= |numerator * SCALE|.
        if (numerator >= -MAX_SAFE_VALUE && numerator <= MAX_SAFE_VALUE) {
            return (numerator * SCALE_FACTOR) / denominator;
        }
        final boolean negative = (numerator ^ denominator) < 0;
        final long un = Math.abs(numerator);
        final long ud = Math.abs(denominator);
        final long phi = Math.unsignedMultiplyHigh(un, SCALE_FACTOR);
        final long plo = un * SCALE_FACTOR;
        if (Long.compareUnsigned(phi, ud) >= 0) {
            throw new OverflowException(); // quotient would be >= 2^64
        }
        final long q = divideUnsigned128(phi, plo, ud);
        return signedResult(q, negative);
    }

    /** Apply the sign to an unsigned 64-bit quotient, throwing if it cannot fit. */
    private static long signedResult(long unsignedQuotient, boolean negative) {
        if (negative) {
            // Magnitudes up to 2^63 are representable as a negative long.
            if (Long.compareUnsigned(unsignedQuotient, Long.MIN_VALUE) > 0) {
                throw new OverflowException();
            }
            return -unsignedQuotient;
        }
        if (unsignedQuotient < 0) { // top bit set: > Long.MAX_VALUE
            throw new OverflowException();
        }
        return unsignedQuotient;
    }

    /**
     * Unsigned 128-by-64-bit division returning the 64-bit quotient
     * (Hacker's Delight 9-4, divlu). Caller guarantees {@code hi < divisor}
     * unsigned, so the quotient fits in 64 bits; divisor must be nonzero.
     */
    private static long divideUnsigned128(long hi, long lo, long divisor) {
        final long b = 0x1_0000_0000L; // 2^32 digit base
        final int s = Long.numberOfLeadingZeros(divisor);
        final long v = divisor << s;                // normalized divisor, top bit set
        final long vn1 = v >>> 32;
        final long vn0 = v & 0xFFFF_FFFFL;
        final long un32 = (s == 0) ? hi : (hi << s) | (lo >>> (64 - s));
        final long un10 = lo << s;
        final long un1 = un10 >>> 32;
        final long un0 = un10 & 0xFFFF_FFFFL;

        long q1 = Long.divideUnsigned(un32, vn1);
        long rhat = un32 - q1 * vn1;
        while (Long.compareUnsigned(q1, b) >= 0
                || Long.compareUnsigned(q1 * vn0, b * rhat + un1) > 0) {
            q1--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, b) >= 0) {
                break;
            }
        }

        final long un21 = un32 * b + un1 - q1 * v;
        long q0 = Long.divideUnsigned(un21, vn1);
        rhat = un21 - q0 * vn1;
        while (Long.compareUnsigned(q0, b) >= 0
                || Long.compareUnsigned(q0 * vn0, b * rhat + un0) > 0) {
            q0--;
            rhat += vn1;
            if (Long.compareUnsigned(rhat, b) >= 0) {
                break;
            }
        }

        return q1 * b + q0;
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
     * Format fixed-point value as string with appropriate decimal places.
     * Always exactly {@link #SCALE} fractional digits; the canonical wire
     * representation (oms#39): {@code parse(format(x)) == x} for every long.
     */
    public static String format(long fixedPoint) {
        long wholePart = fixedPoint / SCALE_FACTOR;
        long fractionalPart = Math.abs(fixedPoint % SCALE_FACTOR);
        // -0.5 has wholePart == 0, which would print unsigned
        String sign = (fixedPoint < 0 && wholePart == 0) ? "-" : "";
        return String.format("%s%d.%08d", sign, wholePart, fractionalPart);
    }

    /**
     * Parse a decimal string into fixed-point, exactly (oms#39: the wire
     * carries money as decimal strings because the double path is lossy).
     *
     * Grammar: {@code -?digits[.digits]} with 1 to {@link #SCALE} fractional
     * digits. Anything else (empty, lone sign, trailing dot, exponents,
     * grouping, more than 8 decimals) throws {@link NumberFormatException}:
     * money precision is never silently rounded away.
     *
     * @throws NumberFormatException on malformed input
     * @throws OverflowException when the value does not fit in fixed-point
     */
    public static long parse(CharSequence s) {
        if (s == null || s.length() == 0) {
            throw new NumberFormatException("empty fixed-point string");
        }
        int len = s.length();
        int i = 0;
        boolean negative = s.charAt(0) == '-';
        if (negative) {
            i++;
        }
        if (i == len) {
            throw new NumberFormatException("no digits in fixed-point string");
        }

        // Integer part, accumulated SIGNED so Long.MIN_VALUE round-trips
        long whole = 0;
        boolean sawIntegerDigit = false;
        try {
            while (i < len) {
                char c = s.charAt(i);
                if (c == '.') {
                    break;
                }
                if (c < '0' || c > '9') {
                    throw new NumberFormatException("invalid character '" + c + "' in fixed-point string");
                }
                sawIntegerDigit = true;
                whole = Math.addExact(Math.multiplyExact(whole, 10L), negative ? -(c - '0') : (c - '0'));
                i++;
            }
            if (!sawIntegerDigit) {
                throw new NumberFormatException("missing integer part in fixed-point string");
            }

            long fraction = 0;
            int fractionDigits = 0;
            if (i < len) { // at the '.'
                i++;
                if (i == len) {
                    throw new NumberFormatException("trailing decimal point in fixed-point string");
                }
                while (i < len) {
                    char c = s.charAt(i++);
                    if (c < '0' || c > '9') {
                        throw new NumberFormatException("invalid character '" + c + "' in fixed-point string");
                    }
                    if (++fractionDigits > SCALE) {
                        throw new NumberFormatException("more than " + SCALE + " decimal places in fixed-point string");
                    }
                    fraction = fraction * 10 + (c - '0');
                }
                for (int d = fractionDigits; d < SCALE; d++) {
                    fraction *= 10;
                }
            }

            return Math.addExact(Math.multiplyExact(whole, SCALE_FACTOR), negative ? -fraction : fraction);
        } catch (ArithmeticException e) {
            throw new OverflowException();
        }
    }
}
