// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import com.match.infrastructure.persistence.S4Probe;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * RC2 S4 contract 5.1: {@code MatchingEngine.restingUserId(orderId, isBuy)} on both books returns
 * the owner of a resting order and -1 otherwise, with the books' own collision guards: a
 * cancelled id, a filled id, the wrong side, a never-seen id and an id whose slot was reused.
 */
public class RestingUserIdAccessorTest {

    private static final long MIN = FixedPoint.fromDouble(50_000.0);
    private static final long MAX = FixedPoint.fromDouble(70_000.0);
    private static final long TICK = FixedPoint.fromDouble(10.0);
    private static final long QTY = FixedPoint.fromDouble(1.0);

    private static long px(double p) {
        return FixedPoint.fromDouble(p);
    }

    /** One scenario, the accessor's answers in order. */
    private static List<Long> scenario(MatchingEngine me) {
        final List<Long> out = new ArrayList<>();
        assertEquals(OrderRejectReason.NONE, me.addOrderNoMatch(1, 100, true, px(60_000), QTY));
        assertEquals(OrderRejectReason.NONE, me.addOrderNoMatch(2, 200, false, px(61_000), QTY));
        assertEquals(OrderRejectReason.NONE, me.addOrderNoMatch(3, 300, true, px(59_000), QTY));
        out.add(S4Probe.restingUserId(me, 1, true));    // 100
        out.add(S4Probe.restingUserId(me, 1, false));   // wrong side: -1
        out.add(S4Probe.restingUserId(me, 2, false));   // 200
        out.add(S4Probe.restingUserId(me, 9, true));    // never seen: -1
        me.cancelOrder(1, true);
        out.add(S4Probe.restingUserId(me, 1, true));    // cancelled: -1
        assertEquals(OrderRejectReason.NONE, me.addOrderNoMatch(4, 400, true, px(60_000), QTY)); // reuses slot of 1
        out.add(S4Probe.restingUserId(me, 1, true));    // reused slot, old id: -1
        out.add(S4Probe.restingUserId(me, 4, true));    // 400
        me.processLimitOrder(5, 500, true, px(61_000), QTY); // fills ask 2 fully
        out.add(S4Probe.restingUserId(me, 2, false));   // filled: -1
        out.add(S4Probe.restingUserId(me, 3, true));    // 300 untouched
        return out;
    }

    @Test
    public void bothImplementationsAgreeOnOwnersAndAbsence() {
        final List<Long> expected = List.of(100L, -1L, 200L, -1L, -1L, -1L, 400L, -1L, 300L);
        assertEquals("array", expected, scenario(new ArrayMatchingEngine(MIN, MAX, TICK, 4)));
        assertEquals("direct", expected, scenario(new DirectMatchingEngine(MIN, MAX, TICK)));
    }
}
