package com.match.domain;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * parse/format are the canonical wire representation for money (oms#39):
 * parse(format(x)) must equal x for EVERY long, and parse must never round.
 */
public class FixedPointParseTest {

    @Test
    public void roundTripsEveryLongShape() {
        Random rnd = new Random(42);
        for (int i = 0; i < 1_000_000; i++) {
            long x = rnd.nextLong();
            assertEquals("round-trip failed for " + x, x, FixedPoint.parse(FixedPoint.format(x)));
        }
    }

    @Test
    public void roundTripsEdgeValues() {
        long[] edges = {
            0, 1, -1, FixedPoint.SCALE_FACTOR, -FixedPoint.SCALE_FACTOR,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
            // the (-1, 0) open interval whose sign format() used to drop
            -50_000_000L, -99_999_999L,
        };
        for (long x : edges) {
            assertEquals("round-trip failed for " + x, x, FixedPoint.parse(FixedPoint.format(x)));
        }
    }

    @Test
    public void formatKeepsSignForSubUnitNegatives() {
        assertEquals("-0.50000000", FixedPoint.format(-50_000_000L));
        assertEquals("-0.00000001", FixedPoint.format(-1L));
        assertEquals("0.00000001", FixedPoint.format(1L));
    }

    @Test
    public void parsesExactValuesTheDoublePathCannot() {
        // 0.1 is not representable in binary; the string path must be exact
        assertEquals(10_000_000L, FixedPoint.parse("0.1"));
        assertEquals(12_345_678L, FixedPoint.parse("0.12345678"));
        // full 18-significant-digit value: fromDouble would round this
        assertEquals(923_456_789_012_345_678L, FixedPoint.parse("9234567890.12345678"));
        assertEquals(-923_456_789_012_345_678L, FixedPoint.parse("-9234567890.12345678"));
    }

    @Test
    public void padsShortFractions() {
        assertEquals(150_000_000L, FixedPoint.parse("1.5"));
        assertEquals(100_000_000L, FixedPoint.parse("1"));
        assertEquals(11_022_400_000_000L, FixedPoint.parse("110224.00"));
    }

    @Test
    public void rejectsMalformedInput() {
        String[] bad = {
            "", "-", ".", "1.", ".5", "-.5", "1..2", "1.2.3",
            "1e5", "0x10", " 1", "1 ", "+1", "1,000", "NaN", "Infinity",
            "1.123456789", // 9 decimal places
        };
        for (String s : bad) {
            assertThrows("should reject: '" + s + "'", NumberFormatException.class, () -> FixedPoint.parse(s));
        }
        assertThrows(NumberFormatException.class, () -> FixedPoint.parse(null));
    }

    @Test
    public void throwsOverflowNotGarbage() {
        // max representable is 92233720368.54775807
        assertEquals(Long.MAX_VALUE, FixedPoint.parse("92233720368.54775807"));
        assertEquals(Long.MIN_VALUE, FixedPoint.parse("-92233720368.54775808"));
        assertThrows(FixedPoint.OverflowException.class, () -> FixedPoint.parse("92233720368.54775808"));
        assertThrows(FixedPoint.OverflowException.class, () -> FixedPoint.parse("-92233720368.54775809"));
        assertThrows(FixedPoint.OverflowException.class, () -> FixedPoint.parse("99999999999999999999"));
    }
}
