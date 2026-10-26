// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.domain.FixedPoint;
import org.junit.Test;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.G2;
import static com.match.application.engine.BehaviourTwoHarness.config;
import static com.match.application.engine.BehaviourTwoHarness.fresh2;
import static com.match.application.engine.EngineConfigStateMachine.Decision.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 review additions (S2/review.md): decision-table cases the S2 tests leave implicit —
 * row 3 normalization of the four, row 5 with differing v12 values, row 4 at an unsupported
 * target, and the first-match precedence of rows 10-14 when several conditions hold at once.
 */
public class EngineConfigTablePrecedenceReviewTest {

    /** Behaviour {@code t} config equal to {@code engine}'s legacy effective values (check c passes). */
    private static EngineConfigState nodeMatching(Engine engine, long t, long genesisId, long ceiling,
                                                  long capacity) {
        final boolean array = EngineConfigState.IMPL_ARRAY.equals(engine.getImplName());
        final EngineConfigState.MarketDef[] m = new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < m.length; i++) {
            final MarketConfig c = MarketConfig.ALL_MARKETS[i];
            m[i] = new EngineConfigState.MarketDef(c.marketId, c.symbol, c.basePrice, c.maxPrice, c.tickSize);
        }
        return EngineConfigState.of(1, engine.getImplName(), engine.getEffectiveBookCapacity(),
                array ? ArrayMatchingEngine.MAX_MATCHES_PER_ORDER : DirectMatchingEngine.MAX_MATCHES_PER_ORDER,
                array ? 0 : DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL, m, t, capacity, genesisId, ceiling);
    }

    // Row 3: ADOPT at t == 1 records the four NORMALIZED (2.1), whatever the v12 frame carried.
    @Test
    public void adoptAtOneNormalizesTheFour() {
        try (BehaviourTwoHarness h = new BehaviourTwoHarness(new Engine("array"))) {
            assertEquals(ADOPT, h.apply(nodeMatching(h.engine, 1, 99, 12, 5), 64L));
            final EngineConfigState r = h.engine.getEngineConfig();
            assertEquals(1L, r.behaviorVersion);
            assertEquals(0L, r.genesisId);
            assertEquals(0L, r.maxWireVersion);
            assertEquals(100_000L, r.commandLedgerCapacity);
            assertFalse(h.engine.meIdentity().isBound());
            assertEquals("check (c) passes on a matching node", 0, h.failFast.get());
            assertTrue("no record 5 at behaviour 1", h.drain().isEmpty());
        }
    }

    // Row 5: at R = 1 the four stay excluded, so a t = 1 v12 re-send with other v12 values is a DUPLICATE.
    @Test
    public void duplicateAtOneIgnoresTheFour() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(FRESH_LEGACY, h.apply(config(1, 1, 0, 0, 100_000), 64L));
            final EngineConfigState before = h.engine.getEngineConfig();
            assertEquals(DUPLICATE, h.apply(config(1, 1, 99, 13, 5), 128L));
            assertSame(before, h.engine.getEngineConfig());
            assertEquals(1, h.sm.duplicateCount());
            assertTrue(h.drain().isEmpty());
        }
    }

    // Row 4 at an unsupported target: still the deterministic REJECTED_ADOPT_AT_2 plus one halt.
    @Test
    public void adoptAtThreeIsRow4AndHaltsOnce() {
        try (BehaviourTwoHarness h = new BehaviourTwoHarness(new Engine("array"))) {
            assertEquals(REJECTED_ADOPT_AT_2, h.apply(config(1, 3, G, 13, 50_000), 64L));
            assertNull(h.engine.getEngineConfig());
            assertFalse(h.engine.meIdentity().isBound());
            assertEquals(1, h.failFast.get());
            assertTrue(h.drain().isEmpty());
        }
    }

    // Rows 10-14, first match wins: downgrade > switch > genesis > change > ceiling.
    @Test
    public void rowsTenToFourteenFirstMatchWins() {
        final EngineConfigState.MarketDef[] otherMarket = {new EngineConfigState.MarketDef(1, "BTC-USD",
                FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(150_000.0), FixedPoint.fromDouble(2.0))};
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(fresh2(), 64L);
            h.drain();
            // t < R with every other field different too -> DOWNGRADE (row 10).
            assertEquals(REJECTED_DOWNGRADE, h.apply(EngineConfigState.of(2, EngineConfigState.IMPL_ARRAY, 8192,
                    100, 0, otherMarket, 1, 7, 0, 0), 128L));
            // t > R with another genesis and a lowered... (t 3 at ceiling 12) -> SWITCH (row 11).
            assertEquals(REJECTED_SWITCH, h.apply(EngineConfigState.of(2, EngineConfigState.IMPL_ARRAY, 8192,
                    100, 0, otherMarket, 3, 7, G2, 12), 192L));
            // Same t, another genesis, other market, other capacity -> GENESIS (row 12).
            assertEquals(REJECTED_GENESIS, h.apply(EngineConfigState.of(2, EngineConfigState.IMPL_ARRAY, 8192,
                    100, 0, otherMarket, 2, 7, G2, 12), 256L));
            // Same genesis, other market AND a lowered ceiling is impossible at R 12 (S-c floor);
            // raise R first, then: change + lowered ceiling -> CHANGE (row 13) before CEILING (row 14).
            assertEquals(BUMP, h.apply(config(2, 2, G, 14, 50_000), 320L));
            assertEquals(1, h.failFast.get()); // this 12-jar halts on (b); the replica state still moves
            h.drain();
            assertEquals(REJECTED_CHANGE, h.apply(config(3, 2, G, 12, 60_000), 384L));
            assertEquals(REJECTED_CEILING_LOWERED, h.apply(config(3, 2, G, 13, 50_000), 448L));
            assertEquals(14L, h.engine.getEngineConfig().maxWireVersion);
            assertEquals(2L, h.engine.getEngineConfig().configVersion);
            assertEquals(G, h.engine.meIdentity().meEpoch());
            assertEquals(5, h.sm.rejectCount());
            assertTrue(h.drain().isEmpty());
        }
    }
}
