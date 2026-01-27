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
}
