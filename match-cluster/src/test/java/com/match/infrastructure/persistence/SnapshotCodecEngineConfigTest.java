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
 * Slice C: the snapshot's trailing engineConfig block. With NO config recorded no config block is
 * written (clean EOF of the body where the block would start = no config). RC2 S17 W12 (R-ME-1): every
 * image carries the durable envelope and, at behaviour 1, the v1 ledger block (0 entries allowed); a bare
 * legacy or v9-era image is refused.
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
        orig.setTradeIdGenerator(7L); int l1 = SnapshotCodec.serialize(orig, 0L, b1);
        ExpandableArrayBuffer b2 = new ExpandableArrayBuffer();
        orig.setTradeIdGenerator(7L); int l2 = SnapshotCodec.serialize(orig, 0L, b2);
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
        int l3 = SnapshotCodec.serialize(restored, decoded.timerCorrelationId, b3);
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
        orig.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(orig, 0L, b);

        Engine restored = Engine.deferredUntilConfig();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);
        assertTrue(decoded.engineConfigPresent);
        assertTrue(restored.getEngine(7) instanceof DirectMatchingEngine);
        assertTrue(restored.getEngineConfig().equalsConfig(orig.getEngineConfig()));
    }

    /**
     * RC2 S17 W12 (R-ME-1, M1b; was configlessSnapshotAppendsNothing): the RC2 writer ALWAYS emits the envelope.
     * With no config recorded the image is the v9 body (8 orderIdGen + 8 tradeIdGen + 4 numMarkets + 5 markets *
     * (4 id + 4 nBid + 4 nAsk) + 8 timer + 4 omsMapCount = 92 bytes), then the v1 ledger block with 0 entries
     * (1 marker + 4 version + 4 count = 9 bytes), inside envelope 1 (20 bytes): 121 bytes, not one more.
     */
    @Test
    public void configlessSnapshotIsEnvelopeOneWithAnEmptyLedger() {
        Engine legacy = new Engine();
        assertNull("legacy engine records no config", legacy.getEngineConfig());

        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        legacy.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(legacy, 0L, b);

        int body = 8 + 8 + 4 + MarketConfig.ALL_MARKETS.length * (4 + 4 + 4) + 8 + 4;
        assertEquals("config-less snapshot = v9 body + empty v1 ledger block + envelope", body + 9 + 20, len);
        assertEquals("starts with the durable magic", SnapshotCodec.DURABLE_MAGIC, b.getLong(0));
        assertEquals("envelope 1", SnapshotCodec.ENVELOPE_V1, b.getInt(8));
        assertEquals("payload length", body + 9, b.getInt(12));
        assertEquals("v1 ledger marker", 2, b.getByte(20 + body));
        assertEquals("v1 ledger version", 1, b.getInt(20 + body + 1));
        assertEquals("v1 ledger count 0", 0, b.getInt(20 + body + 5));

        Engine restored = new Engine();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);
        assertFalse("no config block", decoded.engineConfigPresent);
        assertNull(restored.getEngineConfig());
        assertEquals("nothing dropped", 0, decoded.rejectedOrders);
        assertEquals(len, decoded.bytesConsumed);
    }

    /**
     * RC2 S17 W12 (M1c R-a): a pre-genesis config-mode image (no engines, numMarkets 0, no config) written by the
     * new writer carries the envelope, restores and serves.
     */
    @Test
    public void preGenesisConfigModeImageCarriesTheEnvelopeAndServes() {
        S2Services.Service source = new S2Services.Service(true);
        assertFalse(source.engine().hasEngines());
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        int len = SnapshotCodec.serialize(source.engine(), 0L, b);
        assertEquals("pre-genesis image = envelope + 8 + 8 + 4 (numMarkets 0) + 8 + 4 + empty ledger",
                20 + 8 + 8 + 4 + 8 + 4 + 9, len);
        assertEquals(SnapshotCodec.DURABLE_MAGIC, b.getLong(0));

        S2Services.Service node = new S2Services.Service(true);
        assertTrue("the node serves the image", node.load(Arrays.copyOf(b.byteArray(), len)));
        assertEquals(0, node.failFast.get());
        assertFalse(node.engine().hasEngines());
    }

    /**
     * RC2 S17 W12 (M1c R-b): a behaviour-1 legacy-mode image with resting orders and an empty ledger restores on a
     * legacy-mode node with 0 dropped and serves.
     */
    @Test
    public void behaviourOneLegacyImageWithAnEmptyLedgerServes() {
        S2Services.Service source = new S2Services.Service(false);
        source.engine().getEngine(Engine.MARKET_BTC_USD).addOrderNoMatch(1, 100, true, 6_000_000_000_000L, 100_000_000L);
        source.engine().getOrderIdToOmsOrderId().put(1, 9001);
        assertEquals(0, source.engine().commandLedger().size());
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        source.engine().setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(source.engine(), 0L, b);
        assertEquals(SnapshotCodec.ENVELOPE_V1, b.getInt(8));

        S2Services.Service node = new S2Services.Service(false);
        assertTrue("the legacy node serves", node.load(Arrays.copyOf(b.byteArray(), len)));
        assertEquals(0, node.failFast.get());
        assertFalse("the resting bid restored", node.engine().getEngine(Engine.MARKET_BTC_USD).isBidEmpty());
    }

    /** RC2 S17 W12 (M1c R-c): a behaviour-1 image with a non-empty v1 ledger is unchanged (envelope 1). */
    @Test
    public void behaviourOneImageWithANonEmptyLedgerIsUnchanged() {
        Engine source = new Engine();
        source.commandLedger().record(new com.match.domain.commands.DurableCommandOutcome(
                new com.match.domain.commands.DurableOrderIntent(17, 1, 100, 9001, 999, 0, 0, 0, 1, 1, 0, 0),
                0, 1000, 0, -1, 0, false, 4));
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        source.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(source, 0L, b);
        int body = 8 + 8 + 4 + MarketConfig.ALL_MARKETS.length * (4 + 4 + 4) + 8 + 4;
        assertEquals("envelope + body + ledger header + one record",
                20 + body + 9 + com.match.application.engine.DurableCommandLedger.RECORD_BYTES, len);
        assertEquals(SnapshotCodec.DURABLE_MAGIC, b.getLong(0));
        assertEquals(SnapshotCodec.ENVELOPE_V1, b.getInt(8));
        Engine restored = new Engine();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, restored);
        assertEquals(1, restored.commandLedger().size());
        assertEquals(len, decoded.bytesConsumed);
    }

    /** A v9-era body (timer + oms map tail, then EOF of the body) loads inside the envelope; bare it is refused. */
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
        // EOF of the body, exactly where a slice C config block would start. RC2 S17 W12: the bare v9 image is
        // refused; the same body framed as the RC2 writer frames it (empty v1 ledger, envelope 1) loads.
        final int bareLength = p;
        assertThrows(IllegalStateException.class,
                () -> SnapshotCodec.deserialize(b, 0, bareLength, new Engine()));
        final byte[] image = S2Services.envelopeV1(Arrays.copyOf(b.byteArray(), p));

        Engine e = new Engine();
        SnapshotCodec.Decoded d = SnapshotCodec.deserialize(new org.agrona.concurrent.UnsafeBuffer(image), 0,
                image.length, e);

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
        source.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(source, 0L, b);

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
     * from. The codec still counts the stranded orders (its ERROR line is the diagnosis); RC2 S17
     * W12 (R-ME-2, M2): the service refuses the image, fail-fasts once and never serves.
     */
    @Test
    public void configModeRestoreOfLegacyBooksIsLoudStateLoss() {
        Engine legacy = new Engine();
        legacy.getEngine(Engine.MARKET_BTC_USD).addOrderNoMatch(1, 100, true, 6_000_000_000_000L, 100_000_000L);
        legacy.getEngine(Engine.MARKET_ETH_USD).addOrderNoMatch(2, 101, false, 300_000_000_000L, 100_000_000L);
        ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        legacy.setTradeIdGenerator(1L); int len = SnapshotCodec.serialize(legacy, 0L, b);

        Engine configMode = Engine.deferredUntilConfig();
        SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(b, 0, len, configMode);
        assertFalse(decoded.engineConfigPresent);
        assertEquals("every stranded order counted as lost, loud, never silent",
                2, decoded.rejectedOrders);
        assertFalse("no engines conjured from nothing", configMode.hasEngines());

        S2Services.Service node = new S2Services.Service(true);
        final byte[] image = Arrays.copyOf(b.byteArray(), len);
        final String[] err = new String[1];
        final boolean[] served = new boolean[1];
        err[0] = S2Services.stderrOf(() -> served[0] = node.load(image));
        assertFalse("the node refuses the image", served[0]);
        assertEquals("failFast once", 1, node.failFast.get());
        assertTrue(err[0], err[0].contains("CRITICAL: snapshot refused: [SNAPSHOT] 2 order(s) dropped during restore"));
    }

    /** DirectIndexOrderBook's compiled default is part of the adopt cross-check contract. */
    @Test
    public void compiledCapsAreTheDocumentedDefaults() {
        assertEquals(64, DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL);
        assertEquals(10_000, ArrayMatchingEngine.MAX_MATCHES_PER_ORDER);
        assertEquals(10_000, DirectMatchingEngine.MAX_MATCHES_PER_ORDER);
    }
}
