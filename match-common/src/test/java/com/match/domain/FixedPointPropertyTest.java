package com.match.domain;

import org.junit.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * BigInteger-oracle property tests for FixedPoint.multiply/divide (match#30).
 *
 * The contract under test: results are EXACT (truncated toward zero) whenever
 * the true result fits in a signed long, and {@link FixedPoint.OverflowException}
 * is thrown whenever it does not — never a silently wrong value. Seeded random
 * sampling deliberately hammers the historical failure windows:
 *
 *   - products in [2^63, 2^64): the negative-notional band (~$922-$1844)
 *   - products >= 2^64: the old approximate wide division
 *   - small-first operand orders: the old first-operand fast-path guard
 *   - financial ranges from the issue: prices to >= $10M, qty to book capacity
 */
public class FixedPointPropertyTest {

    private static final BigInteger SCALE = BigInteger.valueOf(FixedPoint.SCALE_FACTOR);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final int ITERATIONS = 200_000;

    private static void checkMultiply(long a, long b) {
        BigInteger expected = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(SCALE);
        boolean fits = expected.compareTo(LONG_MAX) <= 0 && expected.compareTo(LONG_MIN) >= 0;
        try {
            long actual = FixedPoint.multiply(a, b);
            if (!fits) {
                fail("multiply(" + a + ", " + b + ") returned " + actual
                        + " but the true result " + expected + " does not fit a long");
            }
            assertEquals("multiply(" + a + ", " + b + ")", expected.longValueExact(), actual);
        } catch (FixedPoint.OverflowException e) {
            if (fits) {
                fail("multiply(" + a + ", " + b + ") threw but the true result "
                        + expected + " fits a long");
            }
        }
    }

    private static void checkDivide(long n, long d) {
        if (d == 0) {
            return;
        }
        // BigInteger.divide truncates toward zero — the same contract as divide().
        BigInteger expected = BigInteger.valueOf(n).multiply(SCALE).divide(BigInteger.valueOf(d));
        boolean fits = expected.compareTo(LONG_MAX) <= 0 && expected.compareTo(LONG_MIN) >= 0;
        try {
            long actual = FixedPoint.divide(n, d);
            if (!fits) {
                fail("divide(" + n + ", " + d + ") returned " + actual
                        + " but the true result " + expected + " does not fit a long");
            }
            assertEquals("divide(" + n + ", " + d + ")", expected.longValueExact(), actual);
        } catch (FixedPoint.OverflowException e) {
            if (fits) {
                fail("divide(" + n + ", " + d + ") threw but the true result "
                        + expected + " fits a long");
            }
        }
    }

    // ---- targeted boundaries ----

    private static final long[] EDGES = {
            0, 1, -1, 2, -2,
            FixedPoint.SCALE_FACTOR, FixedPoint.SCALE_FACTOR - 1, FixedPoint.SCALE_FACTOR + 1,
            -FixedPoint.SCALE_FACTOR,
            FixedPoint.MAX_SAFE_VALUE, FixedPoint.MAX_SAFE_VALUE + 1, -FixedPoint.MAX_SAFE_VALUE,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
            // the negative-notional band edges (product 2^63 / 2^64 with price 10^13)
            922_337_203L, 922_337_204L, 1_844_674_407L, 1_844_674_408L,
            10_000_000_000_000L,  // $100,000
            1_000_000_000_000_000L // $10,000,000
    };

    @Test
    public void multiplyAllEdgePairs() {
        for (long a : EDGES) {
            for (long b : EDGES) {
                checkMultiply(a, b);
            }
        }
    }

    @Test
    public void divideAllEdgePairs() {
        for (long n : EDGES) {
            for (long d : EDGES) {
                checkDivide(n, d);
            }
        }
    }

    // ---- randomized ranges (fixed seeds: reruns are identical) ----

    @Test
    public void multiplyFinancialRange() {
        // Issue exit criterion range: prices to >= $10M, qty to book capacity.
        Random r = new Random(0xF1ED0001L);
        for (int i = 0; i < ITERATIONS; i++) {
            long price = 1 + (long) (r.nextDouble() * 1_000_000_000_000_000L); // up to $10M
            long qty = 1 + (long) (r.nextDouble() * 13_107_200_000_000L);      // up to 131072 units
            // Both operand orders every time: order must never matter again.
            checkMultiply(qty, price);
            checkMultiply(price, qty);
        }
    }

    @Test
    public void multiplyProductWindows() {
        // Products concentrated around 2^62..2^66: the band and the old wide path.
        Random r = new Random(0xF1ED0002L);
        for (int i = 0; i < ITERATIONS; i++) {
            int bits = 62 + r.nextInt(5);
            BigInteger target = BigInteger.ONE.shiftLeft(bits)
                    .add(BigInteger.valueOf(Math.abs(r.nextLong()) % (1L << 40)));
            long a = 1 + (Math.abs(r.nextLong()) % 100_000_000_000L);
            long b = target.divide(BigInteger.valueOf(a)).longValue();
            if (b == 0) {
                continue;
            }
            long sa = r.nextBoolean() ? a : -a;
            long sb = r.nextBoolean() ? b : -b;
            checkMultiply(sa, sb);
            checkMultiply(sb, sa);
        }
    }

    @Test
    public void multiplyUniformRandomLongs() {
        Random r = new Random(0xF1ED0003L);
        for (int i = 0; i < ITERATIONS; i++) {
            checkMultiply(r.nextLong(), r.nextLong());
        }
    }

    @Test
    public void divideFinancialAndWideRanges() {
        Random r = new Random(0xF1ED0004L);
        for (int i = 0; i < ITERATIONS; i++) {
            // Mix of magnitudes across the full space, including numerators far
            // beyond MAX_SAFE (the old wide path wrapped on both of its terms).
            long n = r.nextLong() >> r.nextInt(48);
            long d = r.nextLong() >> r.nextInt(48);
            checkDivide(n, d);
        }
    }

    @Test
    public void multiplyMatchesDivideRoundTripWhereExact() {
        // For b != 0 with a*b exactly divisible by SCALE, divide(multiply(a,b), b) == a.
        Random r = new Random(0xF1ED0005L);
        for (int i = 0; i < 50_000; i++) {
            long a = (1 + (Math.abs(r.nextLong()) % 1_000_000_000L)) * 100; // multiple of 100
            long b = (1 + (Math.abs(r.nextLong()) % 1_000_000L)) * 1_000_000; // multiple of 10^6
            BigInteger product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(SCALE);
            if (product.compareTo(LONG_MAX) > 0) {
                continue;
            }
            long notional = FixedPoint.multiply(a, b);
            assertEquals("divide(multiply(a,b), b) must return a exactly",
                    a, FixedPoint.divide(notional, b));
        }
    }
}
