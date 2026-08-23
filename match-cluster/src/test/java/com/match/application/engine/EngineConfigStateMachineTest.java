// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.application.orderbook.MatchingEngine;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Slice C: unit tests for the {@link EngineConfigStateMachine} decision table. No Aeron — the
 * state machine is driven directly, exactly as the demuxer drives it on the service thread.
 */
public class EngineConfigStateMachineTest {

    // A config whose markets deliberately do NOT match MarketConfig.ALL_MARKETS, so a fresh
    // accept that consulted the compiled table instead of the config would fail these tests.
    private static EngineConfigState customConfig() {
        return EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        // Deliberately unsorted on the way in: of() must normalize by marketId.
                        new EngineConfigState.MarketDef(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                });
    }

    private static final class Fixture {
        final Engine engine;
        final AtomicInteger failFastInvocations = new AtomicInteger();
        final AtomicInteger enginesCreatedNotifications = new AtomicInteger();
        final EngineConfigStateMachine sm;

        Fixture(Engine engine) {
            this.engine = engine;
            // The fail-fast hook RECORDS instead of exiting (and must not throw: a throwing hook
            // would be swallowed by the demuxer's non-throwing dispatch in production wiring).
            this.sm = new EngineConfigStateMachine(engine, failFastInvocations::incrementAndGet,
                    config -> enginesCreatedNotifications.incrementAndGet());
        }
    }

    // ---- fresh accept: engines are created FROM the config, not from MarketConfig ----

    @Test
    public void freshAcceptCreatesEnginesFromConfigMarketsNotMarketConfig() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());
        assertFalse("precondition: deferred engine has no engines", f.engine.hasEngines());

        f.sm.onEngineConfig(customConfig());

        assertEquals("accepted once", 1, f.sm.acceptedCount());
        assertEquals("service notified to bring up publishers", 1, f.enginesCreatedNotifications.get());
        assertNotNull("config recorded", f.engine.getEngineConfig());
        assertNotNull("engine for config market 7", f.engine.getEngine(7));
        assertNotNull("engine for config market 42", f.engine.getEngine(42));
        assertNull("NO engine for compiled market 1 (BTC) — markets came from the config",
                f.engine.getEngine(1));
        assertTrue("array impl requested", f.engine.getEngine(7) instanceof ArrayMatchingEngine);
        assertEquals("markets normalized ascending by marketId",
                7, f.engine.getEngineConfig().markets[0].marketId);
        assertEquals(42, f.engine.getEngineConfig().markets[1].marketId);
        assertEquals("no failFast on the fresh path", 0, f.failFastInvocations.get());
        assertEquals(0, f.sm.rejectCount());
        assertEquals(0, f.sm.duplicateCount());
    }

    @Test
    public void freshAcceptDirectImplCreatesDirectEngines() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());
        EngineConfigState direct = EngineConfigState.of(1L, EngineConfigState.IMPL_DIRECT, 0, 100, 8,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                });

        f.sm.onEngineConfig(direct);

        assertEquals(1, f.sm.acceptedCount());
        assertTrue("direct impl requested", f.engine.getEngine(7) instanceof DirectMatchingEngine);
    }

    // ---- duplicate: idempotent no-op ACK ----

    @Test
    public void identicalDuplicateIsNoOpAckAndCounted() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());
        f.sm.onEngineConfig(customConfig());
        MatchingEngine before = f.engine.getEngine(7);

        f.sm.onEngineConfig(customConfig());

        assertEquals("duplicate counted", 1, f.sm.duplicateCount());
        assertEquals("not accepted twice", 1, f.sm.acceptedCount());
        assertEquals("no reject for an identical re-send", 0, f.sm.rejectCount());
        assertSame("engines untouched (no recreation)", before, f.engine.getEngine(7));
        assertEquals("publisher bring-up not repeated", 1, f.enginesCreatedNotifications.get());
    }

    @Test
    public void duplicateEqualityIsOrderInsensitiveForMarkets() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());
        f.sm.onEngineConfig(customConfig());

        // Same config, markets supplied in the OTHER wire order — normalization makes it equal.
        EngineConfigState reordered = EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                        new EngineConfigState.MarketDef(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                });
        f.sm.onEngineConfig(reordered);

        assertEquals("wire market order must not defeat idempotence", 1, f.sm.duplicateCount());
        assertEquals(0, f.sm.rejectCount());
    }

    // ---- different config while one is recorded: deterministic reject ----

    @Test
    public void differentConfigIsRejectedAndRecordedStateUntouched() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());
        EngineConfigState first = customConfig();
        f.sm.onEngineConfig(first);
        MatchingEngine before = f.engine.getEngine(7);

        EngineConfigState different = EngineConfigState.of(2L, EngineConfigState.IMPL_ARRAY, 8192, 100, 0,
                first.markets);
        f.sm.onEngineConfig(different);

        assertEquals("reject counted", 1, f.sm.rejectCount());
        assertEquals("still exactly one accept", 1, f.sm.acceptedCount());
        assertTrue("recorded config unchanged", f.engine.getEngineConfig().equalsConfig(first));
        assertSame("engines untouched", before, f.engine.getEngine(7));
        assertEquals("reject must NOT exit the node", 0, f.failFastInvocations.get());
    }

    // ---- invalid config: deterministic reject, nothing recorded, nothing created ----

    @Test
    public void invalidConfigIsRejectedNeverRecordedNeverApplied() {
        Fixture f = new Fixture(Engine.deferredUntilConfig());

        // Empty market set — a cluster with no markets cannot create engines.
        f.sm.onEngineConfig(EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[0]));
        // array impl must not carry a per-level cap (the array book has none).
        f.sm.onEngineConfig(EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY, 4096, 100, 64,
                customConfig().markets));
        // Duplicate marketId.
        f.sm.onEngineConfig(EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                        new EngineConfigState.MarketDef(7, "BBB-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                }));

        assertEquals("every invalid config rejected", 3, f.sm.rejectCount());
        assertEquals(0, f.sm.acceptedCount());
        assertNull("nothing recorded", f.engine.getEngineConfig());
        assertFalse("nothing created", f.engine.hasEngines());
        assertEquals(0, f.failFastInvocations.get());
    }

    // ---- adopt (dogfood path): record without recreating; cross-check node-local truth ----

    /** The declared config that mirrors a legacy node's effective values exactly. */
    private static EngineConfigState declaredMatching(Engine legacy) {
        EngineConfigState.MarketDef[] markets = new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < MarketConfig.ALL_MARKETS.length; i++) {
            MarketConfig m = MarketConfig.ALL_MARKETS[i];
            markets[i] = new EngineConfigState.MarketDef(m.marketId, m.symbol, m.basePrice, m.maxPrice, m.tickSize);
        }
        boolean array = EngineConfigState.IMPL_ARRAY.equals(legacy.getImplName());
        return EngineConfigState.of(1L, legacy.getImplName(), legacy.getEffectiveBookCapacity(),
                array ? ArrayMatchingEngine.MAX_MATCHES_PER_ORDER : DirectMatchingEngine.MAX_MATCHES_PER_ORDER,
                array ? 0 : DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL,
                markets);
    }

    @Test
    public void adoptWithMatchingLocalValuesRecordsAndContinues() {
        Fixture f = new Fixture(new Engine("array"));
        MatchingEngine before = f.engine.getEngine(1);
        EngineConfigState declared = declaredMatching(f.engine);

        f.sm.onEngineConfig(declared);

        assertEquals("adopted (accepted)", 1, f.sm.acceptedCount());
        assertTrue("config recorded as declared truth", f.engine.getEngineConfig().equalsConfig(declared));
        assertSame("engines NOT recreated on adopt", before, f.engine.getEngine(1));
        assertEquals("matching node continues untouched", 0, f.failFastInvocations.get());
        assertEquals("publisher bring-up is the fresh path only", 0, f.enginesCreatedNotifications.get());
        assertEquals(0, f.sm.rejectCount());
    }

    @Test
    public void adoptWithLocalMismatchRecordsThenExitsNeverRejects() {
        Fixture f = new Fixture(new Engine("array"));
        // Declared truth differs from this node: one market's tickSize is not what this build runs.
        EngineConfigState matching = declaredMatching(f.engine);
        EngineConfigState.MarketDef[] skewed = matching.markets.clone();
        EngineConfigState.MarketDef m0 = skewed[0];
        skewed[0] = new EngineConfigState.MarketDef(m0.marketId, m0.symbol, m0.minPrice, m0.maxPrice,
                m0.tickSize * 2);
        EngineConfigState declared = EngineConfigState.of(matching.configVersion, matching.impl,
                matching.bookCapacity, matching.maxMatchesPerOrder, matching.maxOrdersPerLevel, skewed);

        f.sm.onEngineConfig(declared);

        assertEquals("node-local mismatch EXITS (fail-fast), it never rejects the logged command",
                1, f.failFastInvocations.get());
        assertEquals("a reject from node-local truth would fork the cluster", 0, f.sm.rejectCount());
        assertTrue("the declared config was still recorded on every replica before the local check",
                f.engine.getEngineConfig().equalsConfig(declared));
        assertEquals(1, f.sm.acceptedCount());
    }

    @Test
    public void adoptCrossCheckIgnoresConfigVersion() {
        // A node has no local config generation: only the effective values are cross-checked.
        Fixture f = new Fixture(new Engine("array"));
        EngineConfigState matching = declaredMatching(f.engine);
        EngineConfigState otherVersion = EngineConfigState.of(99L, matching.impl, matching.bookCapacity,
                matching.maxMatchesPerOrder, matching.maxOrdersPerLevel, matching.markets);

        f.sm.onEngineConfig(otherVersion);

        assertEquals("version alone must not exit a matching node", 0, f.failFastInvocations.get());
        assertEquals(1, f.sm.acceptedCount());
    }
}
