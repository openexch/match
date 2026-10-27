// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * RC2 S5 contract 5.4: {@code restingRemaining} on both books: the remaining after partial fills,
 * -1 after a cancel, after a full fill, on the wrong side and for an id whose slot was reused.
 */
public class RestingRemainingAccessorTest {

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }

    private static MatchingEngine[] engines() {
        return new MatchingEngine[]{
                new ArrayMatchingEngine(fp(50_000), fp(51_000), fp(1), 16, 100),
                new DirectMatchingEngine(fp(50_000), fp(51_000), fp(1), 100, 4)};
    }

    @Test
    public void remainingAfterFillsCancelAndSlotReuse() {
        for (MatchingEngine me : engines()) {
            final String impl = me.getClass().getSimpleName();
            assertEquals(0, me.addOrderNoMatch(1, 100, false, fp(50_500), fp(10)));
            assertEquals(impl + ": fresh", fp(10), S5Probe.restingRemaining(me, 1, false));
            assertEquals(impl + ": wrong side", -1L, S5Probe.restingRemaining(me, 1, true));
            me.processLimitOrder(2, 200, true, fp(50_500), fp(3));
            assertEquals(impl + ": after a partial fill", fp(7), S5Probe.restingRemaining(me, 1, false));
            me.processLimitOrder(3, 200, true, fp(50_500), fp(2.5));
            assertEquals(impl + ": after two partial fills", fp(4.5), S5Probe.restingRemaining(me, 1, false));
            me.processLimitOrder(4, 200, true, fp(50_500), fp(4.5));
            assertEquals(impl + ": after the full fill", -1L, S5Probe.restingRemaining(me, 1, false));
            assertEquals(0, me.addOrderNoMatch(5, 101, false, fp(50_600), fp(2)));
            assertEquals(true, me.cancelOrder(5, false));
            assertEquals(impl + ": after a cancel", -1L, S5Probe.restingRemaining(me, 5, false));
            // Slot reuse: the next order takes a freed slot; the old ids still read -1.
            assertEquals(0, me.addOrderNoMatch(6, 102, false, fp(50_500), fp(1)));
            assertEquals(0, me.addOrderNoMatch(7, 103, false, fp(50_600), fp(1.5)));
            assertEquals(impl + ": old id on a reused slot", -1L, S5Probe.restingRemaining(me, 1, false));
            assertEquals(impl + ": old id on a reused slot", -1L, S5Probe.restingRemaining(me, 5, false));
            assertEquals(impl + ": new order", fp(1), S5Probe.restingRemaining(me, 6, false));
            assertEquals(impl + ": new order", fp(1.5), S5Probe.restingRemaining(me, 7, false));
            assertEquals(impl + ": never seen", -1L, S5Probe.restingRemaining(me, 99, false));
        }
    }
}
