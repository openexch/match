// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.Publication;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * match#140: per-session egress delivery observability. Verifies the skip accounting, the
 * delivery-based (not poll-based) per-drain signal that gates lastEgressSendMs, and the per-session
 * last-offer age gauge.
 */
public class EgressSessionMetricsTest {

    @Test
    public void notConnectedSkipIncrementsCounterAndPerSessionState() {
        EgressSessionMetrics m = new EgressSessionMetrics();

        long tally = m.recordSkip(42L, Publication.NOT_CONNECTED);

        assertEquals("first skip for a session tallies to 1", 1L, tally);
        assertEquals(1L, m.skipsNotConnected());
        assertEquals(1L, m.totalSkips());
        assertEquals(0L, m.skipsClosed());
        assertEquals(0L, m.skipsMaxPosition());
        assertEquals("per-session tally is keyed by session id", 1L, m.skipTally(42L));

        // Rate-limited log: fires on the first skip, then not again until the interval.
        assertTrue(EgressSessionMetrics.shouldLog(tally));
        assertFalse(EgressSessionMetrics.shouldLog(m.recordSkip(42L, Publication.NOT_CONNECTED)));
        assertEquals(2L, m.skipTally(42L));
        assertEquals(2L, m.skipsNotConnected());
    }

    @Test
    public void dispositionsRouteToDistinctCounters() {
        EgressSessionMetrics m = new EgressSessionMetrics();

        m.recordSkip(1L, Publication.NOT_CONNECTED);
        m.recordSkip(1L, Publication.CLOSED);
        m.recordSkip(2L, Publication.MAX_POSITION_EXCEEDED);
        m.recordSkip(3L, -99L); // unexpected non-positive -> defensive "other" bucket

        assertEquals(1L, m.skipsNotConnected());
        assertEquals(1L, m.skipsClosed());
        assertEquals(1L, m.skipsMaxPosition());
        assertEquals(1L, m.skipsOther());
        assertEquals("aggregate _total sums every disposition incl. other", 4L, m.totalSkips());
    }

    @Test
    public void dispositionNamesAreHumanReadable() {
        assertEquals("NOT_CONNECTED", EgressSessionMetrics.dispositionName(Publication.NOT_CONNECTED));
        assertEquals("CLOSED", EgressSessionMetrics.dispositionName(Publication.CLOSED));
        assertEquals("MAX_POSITION_EXCEEDED", EgressSessionMetrics.dispositionName(Publication.MAX_POSITION_EXCEEDED));
        assertEquals("BACK_PRESSURED", EgressSessionMetrics.dispositionName(Publication.BACK_PRESSURED));
        assertTrue(EgressSessionMetrics.dispositionName(-99L).startsWith("OTHER"));
    }

    /**
     * The false-healthy fix: a drain where every session offer is non-positive must report NO
     * delivery, so flush() does NOT advance lastEgressSendMs (which it stamps only on
     * deliveredThisDrain()).
     */
    @Test
    public void drainWithOnlyNonPositiveOffersReportsNoDelivery() {
        EgressSessionMetrics m = new EgressSessionMetrics();

        m.beginDrain();
        m.recordSkip(1L, Publication.NOT_CONNECTED);
        m.recordSkip(2L, Publication.NOT_CONNECTED);

        assertFalse("all-skip drain must report no delivery so lastEgressSendMs is NOT advanced",
                m.deliveredThisDrain());
        // ...and because nothing delivered, the age gauge stays "never" (-1), never a false fresh 0.
        m.recomputeOldestOffer();
        assertEquals(-1L, m.maxSessionOfferAgeMs(50_000L));
        assertEquals(0L, m.oldestOfferMsSnapshot());
        assertEquals(2L, m.totalSkips());
    }

    @Test
    public void drainWithAPositiveOfferReportsDelivery() {
        EgressSessionMetrics m = new EgressSessionMetrics();

        m.beginDrain();
        m.recordSkip(1L, Publication.NOT_CONNECTED); // one session dark
        m.recordDelivered(2L, 1_234L);               // the other delivers

        assertTrue("a single positive offer makes the drain delivery-positive", m.deliveredThisDrain());

        // A subsequent drain resets the flag.
        m.beginDrain();
        assertFalse(m.deliveredThisDrain());
    }

    @Test
    public void deliveredOfferSeedsPerSessionAgeAndOldestSnapshot() {
        EgressSessionMetrics m = new EgressSessionMetrics();
        assertEquals("no session has delivered yet -> -1", -1L, m.maxSessionOfferAgeMs(10_000L));

        m.recordDelivered(1L, 1_000L);
        m.recordDelivered(2L, 5_000L);
        m.recomputeOldestOffer();

        assertEquals(1_000L, m.lastOfferMs(1L));
        assertEquals(5_000L, m.lastOfferMs(2L));
        // oldest offer = min timestamp = 1_000 -> max age at now=10_000 is 9_000
        assertEquals(1_000L, m.oldestOfferMsSnapshot());
        assertEquals(9_000L, m.maxSessionOfferAgeMs(10_000L));
    }

    /** During a single-session blackout the dark session's age climbs while the fresh one advances. */
    @Test
    public void stalledSessionAgeClimbsWhileFreshSessionAdvances() {
        EgressSessionMetrics m = new EgressSessionMetrics();

        // Both sessions deliver at t=1000.
        m.recordDelivered(1L, 1_000L); // e.g. OMS session
        m.recordDelivered(2L, 1_000L); // e.g. market session
        m.recomputeOldestOffer();
        assertEquals(0L, m.maxSessionOfferAgeMs(1_000L));

        // Market session keeps delivering; OMS session goes dark (only skips now).
        m.recordDelivered(2L, 30_000L);
        m.recordSkip(1L, Publication.NOT_CONNECTED);
        m.recomputeOldestOffer();

        // oldest = OMS's stale 1_000 -> at now=31_000 the max age is 30_000 (the blackout is visible),
        // even though the market session is fresh.
        assertEquals(1_000L, m.oldestOfferMsSnapshot());
        assertEquals(30_000L, m.maxSessionOfferAgeMs(31_000L));
        assertEquals(1L, m.skipsNotConnected());
    }

    /** A normally-closed session must not freeze the age gauge high forever. */
    @Test
    public void closedSessionIsPrunedFromAgeGauge() {
        EgressSessionMetrics m = new EgressSessionMetrics();
        m.recordDelivered(1L, 1_000L);
        m.recordDelivered(2L, 2_000L);
        m.recomputeOldestOffer();
        assertEquals(1_000L, m.oldestOfferMsSnapshot());

        // Session 1 closes -> pruned -> oldest becomes session 2's 2_000.
        m.removeSession(1L);
        assertEquals(2_000L, m.oldestOfferMsSnapshot());

        // The last remaining session closes -> the gauge returns to "never" (-1), not a stale high.
        m.removeSession(2L);
        assertEquals(0L, m.oldestOfferMsSnapshot());
        assertEquals(-1L, m.maxSessionOfferAgeMs(9_999L));
    }
}
