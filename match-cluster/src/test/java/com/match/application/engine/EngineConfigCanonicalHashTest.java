// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * #224: the canonical engine-creation config hash is a CROSS-LANGUAGE CONTRACT — cloud-console
 * (Go) computes the declared side of the declared-vs-effective precheck from the exact same
 * canonical string. These tests pin (1) determinism, (2) sensitivity to every field the adopt
 * cross-check compares, (3) configVersion EXCLUSION (the cross-check is equalsIgnoringVersion),
 * and (4) the canonical string format ITSELF plus a full hash test vector, so a refactor cannot
 * silently change the hash and desynchronize the Go side.
 */
public class EngineConfigCanonicalHashTest {

    private static EngineConfigState.MarketDef md(int id, String symbol, long min, long max, long tick) {
        return new EngineConfigState.MarketDef(id, symbol, min, max, tick);
    }

    /** Same shape as EngineConfigStateMachineTest.customConfig — markets deliberately unsorted. */
    private static EngineConfigState baseline(long configVersion) {
        return EngineConfigState.of(configVersion, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        md(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                        md(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                });
    }

    // ---- (4) the format itself, pinned — change EngineConfigState.canonicalString and this
    // literal ONLY together with a version bump of the "engine-config-v1" prefix and the Go side.

    @Test
    public void canonicalStringFormatIsPinned() {
        assertEquals("engine-config-v1"
                        + "|impl=array|bookCapacity=4096|maxMatchesPerOrder=100|maxOrdersPerLevel=0"
                        + "|markets=2"
                        + "|7,AAA-USD,500000000,1500000000,500000"
                        + "|42,ZZZ-USD,1000000000,2000000000,1000000",
                baseline(7).canonicalString());
    }

    @Test
    public void hashTestVectorIsPinned() {
        // SHA-256("engine-config-v1|impl=array|...") first 8 bytes, unsigned 64-bit big-endian.
        // Recompute ONLY on a deliberate format-version bump; this is also the Go side's vector.
        assertEquals(251824785580910312L, baseline(7).canonicalHash());
    }

    // ---- (1) determinism ----

    @Test
    public void sameValuesSameHash() {
        assertEquals("two objects from the same values must hash identically",
                baseline(7).canonicalHash(), baseline(7).canonicalHash());
    }

    @Test
    public void marketWireOrderDoesNotAffectHash() {
        // of() normalizes ascending by marketId, so producer wire order is irrelevant.
        final EngineConfigState reversed = EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        md(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                        md(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                });
        assertEquals(baseline(7).canonicalHash(), reversed.canonicalHash());
    }

    // ---- (3) configVersion is EXCLUDED, exactly like the adopt cross-check ----

    @Test
    public void configVersionDoesNotAffectHash() {
        assertEquals("the precheck compares VALUES; a node has no local config generation",
                baseline(1).canonicalHash(), baseline(999).canonicalHash());
    }

    // ---- (2) every compared field moves the hash ----

    @Test
    public void eachFieldChangeChangesHash() {
        final long base = baseline(7).canonicalHash();
        final EngineConfigState.MarketDef m7 = md(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L);
        final EngineConfigState.MarketDef m42 = md(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L);

        assertNotEquals("impl", base, EngineConfigState.of(7, EngineConfigState.IMPL_DIRECT,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42, m7}).canonicalHash());
        assertNotEquals("bookCapacity", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                8192, 100, 0, new EngineConfigState.MarketDef[]{m42, m7}).canonicalHash());
        assertNotEquals("maxMatchesPerOrder", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 101, 0, new EngineConfigState.MarketDef[]{m42, m7}).canonicalHash());
        assertNotEquals("maxOrdersPerLevel", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 64, new EngineConfigState.MarketDef[]{m42, m7}).canonicalHash());
        assertNotEquals("market removed", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m7}).canonicalHash());
        assertNotEquals("marketId", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42,
                        md(8, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L)}).canonicalHash());
        assertNotEquals("symbol", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42,
                        md(7, "AAB-USD", 500_000_000L, 1_500_000_000L, 500_000L)}).canonicalHash());
        assertNotEquals("minPrice", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42,
                        md(7, "AAA-USD", 500_000_001L, 1_500_000_000L, 500_000L)}).canonicalHash());
        assertNotEquals("maxPrice", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42,
                        md(7, "AAA-USD", 500_000_000L, 1_500_000_001L, 500_000L)}).canonicalHash());
        assertNotEquals("tickSize", base, EngineConfigState.of(7, EngineConfigState.IMPL_ARRAY,
                4096, 100, 0, new EngineConfigState.MarketDef[]{m42,
                        md(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_001L)}).canonicalHash());
    }

    // ---- impl wire mapping (match_engine_effective_impl uses SBE EngineImpl values) ----

    @Test
    public void implWireValuesMatchTheSbeEnum() {
        assertEquals(0, baseline(7).implWireValue());
        assertEquals(1, EngineConfigState.of(7, EngineConfigState.IMPL_DIRECT, 0, 100, 64,
                new EngineConfigState.MarketDef[]{
                        md(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L)}).implWireValue());
    }
}
