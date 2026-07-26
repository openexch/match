// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

/**
 * The watermark argument, which nearly shipped as the outage that caused it.
 *
 * <p>The historical call site passes a bare {@code "2"} in the third slot (a
 * long dead {@code snapshotsToKeep}). Read positionally, that parses cleanly as
 * a log position and means "purge nothing" — the log grows unbounded and the
 * disk fills, which is precisely 2026-07-25, reintroduced by an argument nobody
 * changed. Naming the flag is what makes that impossible.
 */
public class HousekeepingWatermarkTest {

    private static long parse(final String... args) throws Exception {
        final Method m = ArchiveHousekeeping.class.getDeclaredMethod("watermarkFrom", String[].class);
        m.setAccessible(true);
        return (long) m.invoke(null, (Object) args);
    }

    @Test
    public void namedFlagIsRead() throws Exception {
        assertEquals(1317464576L, parse("/dir", "/aeron", "--watermark=1317464576"));
    }

    @Test
    public void legacyPositionalArgumentIsNotAWatermark() throws Exception {
        // "2" parses as a position. If it were read as one, the purge would stop
        // dead and the disk would fill.
        assertEquals(Long.MAX_VALUE, parse("/dir", "/aeron", "2"));
    }

    @Test
    public void absentFlagMeansNoExternalConstraint() throws Exception {
        assertEquals(Long.MAX_VALUE, parse("/dir", "/aeron"));
    }

    @Test
    public void flagIsFoundAfterLeftoverPositionals() throws Exception {
        assertEquals(42L, parse("/dir", "/aeron", "2", "--watermark=42"));
    }

    // A negative watermark is a caller bug. Ignoring it purges to the snapshot,
    // which is the safe direction: it keeps LESS than the caller asked to keep
    // only if they asked for something impossible.
    @Test
    public void negativeWatermarkIsIgnored() throws Exception {
        assertEquals(Long.MAX_VALUE, parse("/dir", "/aeron", "--watermark=-1"));
    }

    @Test
    public void zeroWatermarkPurgesNothing() throws Exception {
        // Zero is a legitimate value: "no consumer has passed anything yet".
        assertEquals(0L, parse("/dir", "/aeron", "--watermark=0"));
    }
}
