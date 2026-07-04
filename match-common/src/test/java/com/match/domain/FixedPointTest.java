// SPDX-License-Identifier: Apache-2.0
package com.match.domain;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for FixedPoint arithmetic — validates overflow safety (C1 fix).
 */
public class FixedPointTest {

    private static final long SCALE = FixedPoint.SCALE_FACTOR; // 10^8

    // ==================== Basic Operations ====================

    @Test
    public void testFromDouble() {
        assertEquals(100_000_000L, FixedPoint.fromDouble(1.0));
        assertEquals(5_050_000_000L, FixedPoint.fromDouble(50.5));
        assertEquals(0L, FixedPoint.fromDouble(0.0));
        assertEquals(-100_000_000L, FixedPoint.fromDouble(-1.0));
    }

    @Test
    public void testToDouble() {
        assertEquals(1.0, FixedPoint.toDouble(100_000_000L), 0.0001);
        assertEquals(50.5, FixedPoint.toDouble(5_050_000_000L), 0.0001);
    }

    @Test
    public void testMultiplyBasic() {
        long price = FixedPoint.fromDouble(100.0);    // 10_000_000_000
        long qty = FixedPoint.fromDouble(0.5);         // 50_000_000
        long result = FixedPoint.multiply(price, qty);
        assertEquals(50.0, FixedPoint.toDouble(result), 0.01);
    }

    @Test
    public void testMultiplySmallValues() {
        long a = FixedPoint.fromDouble(0.001);
        long b = FixedPoint.fromDouble(0.001);
        long result = FixedPoint.multiply(a, b);
        assertEquals(0.000001, FixedPoint.toDouble(result), 0.0000001);
    }

    // ==================== C1: Overflow Safety ====================

    @Test
    public void testMultiplyLargeValues_NoOverflow() {
        // BTC at $100,000 * quantity 1000 = $100,000,000
        // These values would overflow with naive a*b
        long price = FixedPoint.fromDouble(100_000.0);  // 10_000_000_000_000
        long qty = FixedPoint.fromDouble(1000.0);        // 100_000_000_000
        long result = FixedPoint.multiply(price, qty);
        assertEquals(100_000_000.0, FixedPoint.toDouble(result), 1.0);
    }

    @Test
    public void testMultiplyMaxSafeBoundary() {
        // Just at the MAX_SAFE_VALUE boundary
        long maxSafe = FixedPoint.MAX_SAFE_VALUE;
        long one = FixedPoint.fromDouble(1.0);
        long result = FixedPoint.multiply(maxSafe, one);
        // Should not crash — result = maxSafe * 1.0
        assertTrue(result > 0);
    }

    @Test
    public void testMultiplyBeyondSafeValue() {
        // Beyond MAX_SAFE_VALUE — uses overflow-safe path
        long large = FixedPoint.MAX_SAFE_VALUE + 1_000_000L;
        long two = FixedPoint.fromDouble(2.0);
        long result = FixedPoint.multiply(large, two);
        // Should not overflow — uses Math.multiplyHigh path
        assertTrue("Result should be positive for positive inputs", result > 0);
        assertTrue("Result should be roughly 2x input", result > large);
    }

    @Test
    public void testDivideBasic() {
        long hundred = FixedPoint.fromDouble(100.0);
        long two = FixedPoint.fromDouble(2.0);
        long result = FixedPoint.divide(hundred, two);
        assertEquals(50.0, FixedPoint.toDouble(result), 0.01);
    }

    @Test(expected = ArithmeticException.class)
    public void testDivideByZero() {
        FixedPoint.divide(FixedPoint.fromDouble(100.0), 0L);
    }

    @Test
    public void testDivideLargeNumerator_NoOverflow() {
        // Large numerator that would overflow naive numerator * SCALE_FACTOR
        long large = FixedPoint.MAX_SAFE_VALUE + 1_000_000L;
        long two = FixedPoint.fromDouble(2.0);
        long result = FixedPoint.divide(large, two);
        assertTrue("Result should be positive", result > 0);
    }

    // ==================== Edge Cases ====================

    @Test
    public void testMultiplyByZero() {
        assertEquals(0L, FixedPoint.multiply(FixedPoint.fromDouble(100.0), 0L));
        assertEquals(0L, FixedPoint.multiply(0L, FixedPoint.fromDouble(100.0)));
    }

    @Test
    public void testNegativeValues() {
        long neg = FixedPoint.fromDouble(-50.0);
        long pos = FixedPoint.fromDouble(2.0);
        long result = FixedPoint.multiply(neg, pos);
        assertEquals(-100.0, FixedPoint.toDouble(result), 0.01);
    }

    @Test
    public void testIsPositive() {
        assertTrue(FixedPoint.isPositive(FixedPoint.fromDouble(1.0)));
        assertFalse(FixedPoint.isPositive(0L));
        assertFalse(FixedPoint.isPositive(FixedPoint.fromDouble(-1.0)));
    }

    @Test
    public void testIsZero() {
        assertTrue(FixedPoint.isZero(0L));
        assertFalse(FixedPoint.isZero(1L));
    }

    @Test
    public void testFormat() {
        String formatted = FixedPoint.format(FixedPoint.fromDouble(123.456));
        assertTrue(formatted.startsWith("123."));
    }

    // ==================== Missing method coverage ====================

    @Test
    public void testIsNegative() {
        assertTrue(FixedPoint.isNegative(FixedPoint.fromDouble(-1.0)));
        assertFalse(FixedPoint.isNegative(0L));
        assertFalse(FixedPoint.isNegative(FixedPoint.fromDouble(1.0)));
    }

    @Test
    public void testCompare() {
        long a = FixedPoint.fromDouble(100.0);
        long b = FixedPoint.fromDouble(200.0);
        assertTrue(FixedPoint.compare(a, b) < 0);
        assertTrue(FixedPoint.compare(b, a) > 0);
        assertEquals(0, FixedPoint.compare(a, a));
    }

    @Test
    public void testMin() {
        long a = FixedPoint.fromDouble(100.0);
        long b = FixedPoint.fromDouble(200.0);
        assertEquals(a, FixedPoint.min(a, b));
        assertEquals(a, FixedPoint.min(b, a));
        assertEquals(a, FixedPoint.min(a, a));
    }

    @Test
    public void testMax() {
        long a = FixedPoint.fromDouble(100.0);
        long b = FixedPoint.fromDouble(200.0);
        assertEquals(b, FixedPoint.max(a, b));
        assertEquals(b, FixedPoint.max(b, a));
        assertEquals(a, FixedPoint.max(a, a));
    }

    @Test
    public void testFormatNegative() {
        String formatted = FixedPoint.format(FixedPoint.fromDouble(-42.5));
        assertTrue(formatted.startsWith("-42."));
    }

    @Test(expected = FixedPoint.OverflowException.class)
    public void testMultiply_True128BitCase_ThrowsOnUnrepresentableResult() {
        // (Long.MAX_VALUE / 2) * 4.0 = 2 * Long.MAX_VALUE: the true result does
        // not fit a long. The old code returned an approximated wrong value here;
        // wrong money must never be returned (match#30).
        long a = Long.MAX_VALUE / 2;
        long b = FixedPoint.SCALE_FACTOR * 4; // 4.0 in fixed point
        FixedPoint.multiply(a, b);
    }

    // ==================== Determinism & tick-boundary exactness ====================

    @Test
    public void testFromDoubleExactAtUsedTickBoundaries() {
        // The matching engine's correctness depends on these being EXACT (e.g. off-tick rejection).
        assertEquals(6_000_000_000_000L, FixedPoint.fromDouble(60_000.0));
        assertEquals(6_000_050_000_000L, FixedPoint.fromDouble(60_000.50));
        assertEquals(20_000_000_000_000L, FixedPoint.fromDouble(200_000.0));
        assertEquals(50_000_000L, FixedPoint.fromDouble(0.5));
        assertEquals(25_000_000L, FixedPoint.fromDouble(0.25));
        assertEquals(1L, FixedPoint.fromDouble(0.00000001)); // one satoshi / smallest unit
    }

    @Test
    public void testFromDoubleRoundsHalfUpDeterministically() {
        // 0.000000005 * 1e8 = 0.5 → Math.round → 1; 0.000000004 → 0.4 → 0
        assertEquals(1L, FixedPoint.fromDouble(0.000000005));
        assertEquals(0L, FixedPoint.fromDouble(0.000000004));
    }

    @Test
    public void testRoundTripStableInFinancialRange() {
        long[] vals = {
            FixedPoint.fromDouble(0.5), FixedPoint.fromDouble(60_000.0),
            FixedPoint.fromDouble(3_000.0), FixedPoint.fromDouble(0.00000001),
            FixedPoint.fromDouble(149_999.99)
        };
        for (long fp : vals) {
            assertEquals("round-trip fromDouble(toDouble(x)) must be exact in financial range",
                fp, FixedPoint.fromDouble(FixedPoint.toDouble(fp)));
        }
    }

    @Test
    public void testMultiplyCommutativeEverywhere() {
        // Path selection is now by product magnitude, never by operand order:
        // multiply(smallQty, largePrice) and multiply(largePrice, smallQty) are
        // identical in every range (the old first-operand guard made the qty-first
        // order wrap silently; callers no longer need to know an argument order).
        long[][] pairs = {
            {FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(0.5)},
            {FixedPoint.fromDouble(50.0),  FixedPoint.fromDouble(2.0)},
            {FixedPoint.fromDouble(0.001), FixedPoint.fromDouble(7.0)},
            // The historical bug shapes: small qty FIRST with a large price.
            {FixedPoint.fromDouble(0.01),  FixedPoint.fromDouble(100_000.0)}, // $1,000 band case
            {FixedPoint.fromDouble(0.05),  FixedPoint.fromDouble(100_000.0)}, // $5,000 wrap case
            {FixedPoint.fromDouble(1000.0), FixedPoint.fromDouble(100_000.0)} // $100M
        };
        for (long[] p : pairs) {
            assertEquals("multiply must be order-independent in every range",
                FixedPoint.multiply(p[0], p[1]), FixedPoint.multiply(p[1], p[0]));
        }
    }

    @Test
    public void testMultiplyNegativeNotionalBandFixed() {
        // Regression for the [2^63, 2^64) product window (match#30): notionals in
        // ~[$922, $1844] came out NEGATIVE (multiplyHigh 0 but low signed-negative),
        // spuriously rejecting ~3% of typical orders via NOTIONAL_TOO_SMALL.
        long qty = FixedPoint.fromDouble(0.01);          // 1_000_000
        long price = FixedPoint.fromDouble(100_000.0);   // 10^13
        assertEquals(FixedPoint.fromDouble(1_000.0), FixedPoint.multiply(qty, price));
        assertEquals(FixedPoint.fromDouble(1_000.0), FixedPoint.multiply(price, qty));
        assertEquals(FixedPoint.fromDouble(-1_000.0), FixedPoint.multiply(-qty, price));
        assertEquals(FixedPoint.fromDouble(1_000.0), FixedPoint.multiply(-qty, -price));
    }

    @Test
    public void testFromDoubleMonotonicAcrossTicks() {
        long prev = Long.MIN_VALUE;
        for (double d = 50_000.0; d <= 60_000.0; d += 0.5) {
            long fp = FixedPoint.fromDouble(d);
            assertTrue("fromDouble must be strictly monotonic across ticks", fp > prev);
            prev = fp;
        }
    }
}
