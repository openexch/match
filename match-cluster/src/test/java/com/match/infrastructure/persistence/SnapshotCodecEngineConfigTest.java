// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.application.engine.MarketConfig;
import org.agrona.ExpandableArrayBuffer;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Slice C: the snapshot's trailing engineConfig block. The red line is byte-level: with NO config
 * recorded the serialized bytes are IDENTICAL to the pre-slice-C format (nothing appended), and
 * legacy / v9-era snapshots load fine (clean EOF where the block would start = no config).
 */
public class SnapshotCodecEngineConfigTest {

    private static EngineConfigState customConfig() {
        return EngineConfigState.of(5L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L),
                        new EngineConfigState.MarketDef(42, "ZZZ-USD", 1_000_000_000L, 2_000_000_000L, 1_000_000L),
                });
    }

    private static byte[] bytes(ExpandableArrayBuffer b, int len) {
        return Arrays.copyOf(b.byteArray(), len);
    }

    /** Config-bearing snapshot: byte-stable across two passes, and a fresh-from-snapshot
     *  config-mode engine rebuilds identically (same config, same books, same re-serialized bytes). */
    @Test
    public void configBearingSnapshotIsByteStableAndRebuildsIdentically() {
        Engine orig = Engine.deferredUntilConfig();
        orig.createEnginesFromConfig(customConfig());
        orig.getEngine(7).addOrderNoMatch(1, 100, true, 600_000_000L, 5_000_000L);
        orig.getEngine(7).addOrderNoMatch(2, 101, false, 1_000_000_000L, 3_000_000L);
        orig.getEngine(42).addOrderNoMatch(3, 102, true, 1_500_000_000L, 1_000_000L);
        orig.getOrderIdToOmsOrderId().put(1, 9100);
        orig.getOrderIdToOmsOrderId().put(3, 9300);

        ExpandableArrayBuffer b1 = new ExpandableArrayBuffer();
        int l1 = SnapshotCodec.serialize(orig, 7L, 0L, b1);
        ExpandableArrayBuffer b2 = new ExpandableArrayBuffer();
        int l2 = SnapshotCodec.serialize(orig, 7L, 0L, b2);
        assertEquals("length stable across two passes", l1, l2);
        assertArrayEquals("config-bearing snapshot must be byte-deterministic", bytes(b1, l1), bytes(b2, l2));

        // Fresh config-mode node restores: engines are rebuilt FROM the recorded config, books land.
        Engine restored = Engine.deferredUntilConfig();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b1, 0, l1, restored);

        assertTrue("config block detected", decoded.engineConfigPresent);
        assertEquals("no order lost in the rebuild", 0, decoded.rejectedOrders);
        assertEquals("whole payload consumed", l1, decoded.bytesConsumed);
        assertNotNull(restored.getEngineConfig());
        assertTrue("recorded config restored exactly",
                restored.getEngineConfig().equalsConfig(orig.getEngineConfig()));
        assertTrue(restored.getEngine(7) instanceof ArrayMatchingEngine);
        assertArrayEquals("bids market 7", orig.getEngine(7).getBidOrders(), restored.getEngine(7).getBidOrders());
        assertArrayEquals("asks market 7", orig.getEngine(7).getAskOrders(), restored.getEngine(7).getAskOrders());
        assertArrayEquals("bids market 42", orig.getEngine(42).getBidOrders(), restored.getEngine(42).getBidOrders());
        assertEquals(9100L, restored.getOrderIdToOmsOrderId().get(1L));

        // ...and the rebuilt engine re-serializes to the SAME bytes (replica-identical snapshots).
        ExpandableArrayBuffer b3 = new ExpandableArrayBuffer();
        int l3 = SnapshotCodec.serialize(restored, decoded.tradeIdGenerator, decoded.timerCorrelationId, b3);
        assertArrayEquals("fresh-from-snapshot rebuild must re-serialize byte-identically",
                bytes(b1, l1), bytes(b3, l3));
    }

    /** Direct-impl config round-trips too (impl byte 1 + per-level cap). */
    @Test
    public void directImplConfigRoundTrips() {
        Engine orig = Engine.deferredUntilConfig();
        orig.createEnginesFromConfig(EngineConfigState.of(2L, EngineConfigState.IMPL_DIRECT, 0, 100, 8,
                new EngineConfigState.MarketDef[]{
                        new EngineConfigState.MarketDef(7, "AAA-USD", 500_000_000L, 1_500_000_000L, 500_000L)}));

        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(orig, 1L, 0L, b);

        Engine restored = Engine.deferredUntilConfig();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);
        assertTrue(decoded.engineConfigPresent);
        assertTrue(restored.getEngine(7) instanceof DirectMatchingEngine);
        assertTrue(restored.getEngineConfig().equalsConfig(orig.getEngineConfig()));
    }

    /**
     * RED LINE, byte level: with no config recorded, serialize appends NOTHING. A pristine legacy
     * engine's snapshot is exactly the hand-computed v9 layout size:
     * 8 (orderIdGen) + 8 (tradeIdGen) + 4 (numMarkets) + 5 markets * (4 id + 4 nBid + 4 nAsk)
     * + 8 (timer) + 4 (omsMapCount) = 92 bytes — not one trailing byte more.
     */
    @Test
    public void configlessSnapshotAppendsNothing() {
        Engine legacy = new Engine();
        assertNull("legacy engine records no config", legacy.getEngineConfig());

        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(legacy, 1L, 0L, b);

        int expected = 8 + 8 + 4 + MarketConfig.ALL_MARKETS.length * (4 + 4 + 4) + 8 + 4;
        assertEquals("config-less snapshot must stay bit-for-bit the v9 layout", expected, len);

        Engine restored = new Engine();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);
        assertFalse("clean EOF where the block would start = no config, not an error",
                decoded.engineConfigPresent);
        assertNull(restored.getEngineConfig());
        assertEquals(len, decoded.bytesConsumed);
    }

    /** A v9-era snapshot fixture (timer + oms map tail, then EOF) still loads — trailing-tolerant. */
    @Test
    public void v9EraSnapshotFixtureStillLoads() {
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int p = 0;
        b.putLong(p, 9);  p += 8;             // orderIdGen
        b.putLong(p, 3);  p += 8;             // tradeIdGen
        b.putInt(p, 1);   p += 4;             // numMarkets = 1
        b.putInt(p, Engine.MARKET_BTC_USD); p += 4;
        b.putInt(p, 1);   p += 4;             // numBidOrders = 1
        b.putLong(p, 1);  p += 8;             // orderId
        b.putLong(p, 100); p += 8;            // userId
        b.putLong(p, 6_000_000_000_000L); p += 8; // price $60,000 (inside the BTC band)
        b.putLong(p, 100_000_000L); p += 8;   // qty 1.0
        b.putInt(p, 0);   p += 4;             // numAskOrders = 0
        b.putLong(p, 1_000_000_000_000L); p += 8; // timerCorrelationId
        b.putInt(p, 1);   p += 4;             // omsMapCount = 1 (the v9 A-1 tail)
        b.putLong(p, 1);  p += 8;             // orderId
        b.putLong(p, 9001); p += 8;           // omsOrderId
        // EOF — exactly where a slice C config block would start.

        Engine e = new Engine();
        SnapshotCodec.Decoded d = SnapshotCodec.deserialize(b, 0, p, e);

        assertEquals(9, e.getOrderIdGenerator());
        assertEquals(3, d.tradeIdGenerator);
        assertTrue(d.timerCorrelationIdPresent);
        assertEquals("v9 oms map still restores", 9001L, e.getOrderIdToOmsOrderId().get(1L));
        assertEquals("no order lost", 0, d.rejectedOrders);
        assertFalse("v9-era snapshot has no config — trailing-tolerant load", d.engineConfigPresent);
        assertFalse("the fixture's resting bid restored", e.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
    }

    /**
     * Adopted-config snapshot restored on a LEGACY-mode node: the config is recorded (declared
     * truth) but the env-built engines are NOT recreated — the books restore into them as always.
     * (The service then runs the adopt cross-check + fail-fast; that seam is service-level.)
     */
    @Test
    public void configBearingSnapshotIntoLegacyEngineRecordsConfigWithoutRecreating() {
        // The recorded config mirrors the compiled table (the dogfood adopt shape).
        Engine source = new Engine("array");
        EngineConfigState.MarketDef[] markets = new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < MarketConfig.ALL_MARKETS.length; i++) {
            MarketConfig m = MarketConfig.ALL_MARKETS[i];
            markets[i] = new EngineConfigState.MarketDef(m.marketId, m.symbol, m.basePrice, m.maxPrice, m.tickSize);
        }
        EngineConfigState adopted = EngineConfigState.of(1L, EngineConfigState.IMPL_ARRAY,
                source.getEffectiveBookCapacity(), ArrayMatchingEngine.MAX_MATCHES_PER_ORDER, 0, markets);
        source.setEngineConfig(adopted);
        source.getEngine(Engine.MARKET_BTC_USD).addOrderNoMatch(1, 100, true, 6_000_000_000_000L, 100_000_000L);

        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(source, 1L, 0L, b);

        Engine restored = new Engine("array");
        com.match.application.orderbook.MatchingEngine before = restored.getEngine(Engine.MARKET_BTC_USD);
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);

        assertTrue(decoded.engineConfigPresent);
        assertTrue("declared truth restored", restored.getEngineConfig().equalsConfig(adopted));
        assertSame("legacy engines NOT recreated on restore", before, restored.getEngine(Engine.MARKET_BTC_USD));
        assertFalse("books restored into the existing engines",
                restored.getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
        assertEquals(0, decoded.rejectedOrders);
    }

    /**
     * Cross-mode operator error: a CONFIG-mode node restoring a legacy (config-less) snapshot
     * that carries resting orders has no engines to restore into and no config to build them
     * from. That is state loss and must be LOUD (counted), never a silent drop.
     */
    @Test
    public void configModeRestoreOfLegacyBooksIsLoudStateLoss() {
        Engine legacy = new Engine();
        legacy.getEngine(Engine.MARKET_BTC_USD).addOrderNoMatch(1, 100, true, 6_000_000_000_000L, 100_000_000L);
        legacy.getEngine(Engine.MARKET_ETH_USD).addOrderNoMatch(2, 101, false, 300_000_000_000L, 100_000_000L);
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(legacy, 1L, 0L, b);

        Engine configMode = Engine.deferredUntilConfig();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, configMode);

        assertFalse(decoded.engineConfigPresent);
        assertEquals("every stranded order counted as lost — loud, never silent",
                2, decoded.rejectedOrders);
        assertFalse("no engines conjured from nothing", configMode.hasEngines());
    }

    /** DirectIndexOrderBook's compiled default is part of the adopt cross-check contract. */
    @Test
    public void compiledCapsAreTheDocumentedDefaults() {
        assertEquals(64, DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL);
        assertEquals(10_000, ArrayMatchingEngine.MAX_MATCHES_PER_ORDER);
        assertEquals(10_000, DirectMatchingEngine.MAX_MATCHES_PER_ORDER);
    }
}
