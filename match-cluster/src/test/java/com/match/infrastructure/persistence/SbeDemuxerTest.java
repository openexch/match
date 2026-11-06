// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Integration test for SbeDemuxer — encodes real SBE messages,
 * dispatches through the demuxer, and verifies state in the Engine.
 */
public class SbeDemuxerTest {

    private Engine engine;
    private SbeDemuxer demuxer;
    private UnsafeBuffer buffer;
    private MessageHeaderEncoder headerEncoder;

    @Before
    public void setUp() {
        engine = new Engine();
        demuxer = new SbeDemuxer(engine);
        buffer = new UnsafeBuffer(new byte[256]);
        headerEncoder = new MessageHeaderEncoder();
    }

    // ==================== Helpers ====================

    /** v8: no totalPrice on the wire — a MARKET buy's budget rides in {@code price}. */
    private int encodeCreateOrder(long userId, int marketId,
                                  OrderSide side, OrderType type,
                                  long price, long quantity) {
        CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.marketId(marketId);
        encoder.orderSide(side);
        encoder.orderType(type);
        encoder.price(price);
        encoder.quantity(quantity);
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    private int encodeCancelOrder(long userId, long orderId, int marketId) {
        CancelOrderEncoder encoder = new CancelOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.orderId(orderId);
        encoder.marketId(marketId);
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    private int encodeUpdateOrder(long userId, long orderId, int marketId,
                                  OrderSide side, OrderType type,
                                  long price, long quantity) {
        UpdateOrderEncoder encoder = new UpdateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.orderId(orderId);
        encoder.marketId(marketId);
        encoder.orderSide(side);
        encoder.orderType(type);
        encoder.price(price);
        encoder.quantity(quantity);
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    // ==================== CreateOrder Tests ====================

    @Test
    public void createLimitBidOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("Bid side should have the order", dme.isBidEmpty());
    }

    @Test
    public void createLimitAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(2.0);
        int len = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("Ask side should have the order", dme.isAskEmpty());
    }

    @Test
    public void createLimitMakerBidOrderAppearsInEngine() {
        // LIMIT_MAKER on empty book should succeed (no opposing side to match)
        long price = FixedPoint.fromDouble(59000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(300L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, price, qty);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER bid should appear when no ask to cross", dme.isBidEmpty());
    }

    @Test
    public void createLimitMakerAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(70000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(400L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, price, qty);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER ask should appear when no bid to cross", dme.isAskEmpty());
    }

    @Test
    public void createMarketSellOrderMatchesExistingBid() {
        // Place a bid first
        long bidPrice = FixedPoint.fromDouble(60000.0);
        long bidQty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, bidPrice, bidQty);
        demuxer.dispatchTrusted(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isBidEmpty());

        // Market sell should match the bid
        long sellQty = FixedPoint.fromDouble(1.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.MARKET, 0L, sellQty);
        demuxer.dispatchTrusted(buffer, 0, len2, System.nanoTime());

        assertTrue("Bid should be consumed by market sell", dme.isBidEmpty());
    }

    @Test
    public void createOrderOnMultipleMarkets() {
        // BTC-USD (market 1)
        long p1 = FixedPoint.fromDouble(60000.0);
        long q1 = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p1, q1);
        demuxer.dispatchTrusted(buffer, 0, len1, System.nanoTime());

        // ETH-USD (market 2)
        long p2 = FixedPoint.fromDouble(3000.0);
        long q2 = FixedPoint.fromDouble(10.0);
        int len2 = encodeCreateOrder(2L, 2, OrderSide.ASK, OrderType.LIMIT, p2, q2);
        demuxer.dispatchTrusted(buffer, 0, len2, System.nanoTime());

        assertFalse(engine.getEngine(1).isBidEmpty());
        assertFalse(engine.getEngine(2).isAskEmpty());
        assertTrue(engine.getEngine(1).isAskEmpty());
        assertTrue(engine.getEngine(2).isBidEmpty());
    }

    // ==================== CancelOrder Tests ====================

    @Test
    public void cancelOrderRemovesFromEngine() {
        // Place bid
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);
        demuxer.dispatchTrusted(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isBidEmpty());

        // Cancel it — the engine assigns orderId starting from 1
        long orderId = engine.getOrderIdGenerator() - 1;
        int len2 = encodeCancelOrder(100L, orderId, 1);
        demuxer.dispatchTrusted(buffer, 0, len2, System.nanoTime());

        assertTrue("Order should be cancelled", dme.isBidEmpty());
    }

    @Test
    public void cancelAskOrderRemovesFromEngine() {
        long price = FixedPoint.fromDouble(70000.0);
        long qty = FixedPoint.fromDouble(2.0);
        int len1 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty);
        demuxer.dispatchTrusted(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isAskEmpty());

        long orderId = engine.getOrderIdGenerator() - 1;
        int len2 = encodeCancelOrder(200L, orderId, 1);
        demuxer.dispatchTrusted(buffer, 0, len2, System.nanoTime());

        assertTrue("Ask order should be cancelled", dme.isAskEmpty());
    }

    // ==================== UpdateOrder Tests ====================

    @Test
    public void updateOrderDispatchesWithoutError() {
        // The Engine.acceptOrder for CMD_UPDATE is not fully implemented (commented as
        // "Update not implemented for direct engine"), but dispatch should not crash.
        long price = FixedPoint.fromDouble(61000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeUpdateOrder(100L, 42L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);

        // Should not throw
        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());
    }

    // ==================== Edge Cases ====================

    @Test
    public void unknownTemplateIdSilentlyIgnored() {
        // Write a header with an unknown template ID
        headerEncoder.wrap(buffer, 0)
            .blockLength(20)
            .templateId(999)
            .schemaId(1)
            .version(2);
        // Should not throw or crash
        demuxer.dispatchTrusted(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + 20, System.nanoTime());
    }

    @Test
    public void messageTooShortSilentlyIgnored() {
        // Length less than header size (8 bytes)
        demuxer.dispatchTrusted(buffer, 0, 4, System.nanoTime());
        // Should not throw
    }

    @Test
    public void zeroLengthMessageSilentlyIgnored() {
        demuxer.dispatchTrusted(buffer, 0, 0, System.nanoTime());
    }

    @Test
    public void exactHeaderLengthNoPayload() {
        // Exactly header length but garbage template — should just fall through switch
        headerEncoder.wrap(buffer, 0)
            .blockLength(0)
            .templateId(12345)
            .schemaId(1)
            .version(2);
        demuxer.dispatchTrusted(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH, System.nanoTime());
    }

    // ==================== All Order Types / Sides ====================

    @Test
    public void allOrderTypesBidSide() {
        MatchingEngine dme = engine.getEngine(1);

        // LIMIT bid
        long p = FixedPoint.fromDouble(55000.0);
        long q = FixedPoint.fromDouble(0.1);
        int len = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p, q);
        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isBidEmpty());

        // LIMIT_MAKER bid (lower price, won't cross)
        long p2 = FixedPoint.fromDouble(54000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, p2, q);
        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());
        // Still has bids
        assertFalse(dme.isBidEmpty());
    }

    @Test
    public void allOrderTypesAskSide() {
        MatchingEngine dme = engine.getEngine(1);

        // LIMIT ask
        long p = FixedPoint.fromDouble(70000.0);
        long q = FixedPoint.fromDouble(0.1);
        int len = encodeCreateOrder(1L, 1, OrderSide.ASK, OrderType.LIMIT, p, q);
        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // LIMIT_MAKER ask (higher price, won't cross)
        long p2 = FixedPoint.fromDouble(75000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, p2, q);
        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isAskEmpty());
    }

    // ==================== Schema-Version Gate (G-2 range) ====================
    //
    // The gate accepts versions in [MIN_SUPPORTED_SCHEMA_VERSION, SCHEMA_VERSION] and drops
    // everything else BEFORE any body decode. Today the floor equals the ceiling (both 9), so
    // the range degenerates to the old exact match — the below-floor and above-ceiling tests
    // prove exactly that. All assertions reference the constants, never literal versions, so a
    // future ceiling bump (e.g. an additive v10) keeps them meaningful: the floor test then
    // proves an older-but-supported producer still flows during a rolling upgrade.

    /** Re-stamps only the version field of an already-encoded frame's header. */
    private void overwriteHeaderVersion(final int version) {
        headerEncoder.wrap(buffer, 0).version(version);
    }

    /** Re-stamps only the schemaId field of an already-encoded frame's header. */
    private void overwriteHeaderSchemaId(final int schemaId) {
        headerEncoder.wrap(buffer, 0).schemaId(schemaId);
    }

    @Test
    public void gateRangeIsWellFormed() {
        assertTrue("Version floor must never exceed this build's schema version",
                SbeDemuxer.MIN_SUPPORTED_SCHEMA_VERSION <= MessageHeaderDecoder.SCHEMA_VERSION);
    }

    @Test
    public void currentVersionAccepted() {
        // encodeCreateOrder stamps this build's version (the ceiling) — no overwrite needed.
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        assertEquals("Current-version frame must not count as a schema reject",
                0L, demuxer.schemaRejectCount());
        assertFalse("Current-version frame must reach the engine",
                engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void minSupportedVersionAccepted() {
        // Today MIN == ceiling, so this duplicates currentVersionAccepted; after a ceiling
        // bump it becomes the rolling-upgrade guarantee (a floor-version producer keeps flowing).
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);
        overwriteHeaderVersion(SbeDemuxer.MIN_SUPPORTED_SCHEMA_VERSION);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        assertEquals("Floor-version frame must not count as a schema reject",
                0L, demuxer.schemaRejectCount());
        assertFalse("Floor-version frame must reach the engine",
                engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void belowFloorVersionDropped() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);
        overwriteHeaderVersion(SbeDemuxer.MIN_SUPPORTED_SCHEMA_VERSION - 1);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        assertEquals("Below-floor frame must count as a schema reject",
                1L, demuxer.schemaRejectCount());
        assertTrue("Below-floor frame must never reach the engine",
                engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void futureVersionDropped() {
        // One past this build's ceiling: a frame carrying fields this build cannot decode.
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);
        overwriteHeaderVersion(MessageHeaderDecoder.SCHEMA_VERSION + 1);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        assertEquals("Above-ceiling frame must count as a schema reject",
                1L, demuxer.schemaRejectCount());
        assertTrue("Above-ceiling frame must never reach the engine",
                engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void wrongSchemaIdDroppedEvenWithSupportedVersion() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(1.0);
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty);
        overwriteHeaderSchemaId(MessageHeaderDecoder.SCHEMA_ID + 1);

        demuxer.dispatchTrusted(buffer, 0, len, System.nanoTime());

        assertEquals("Foreign-schema frame must count as a schema reject",
                1L, demuxer.schemaRejectCount());
        assertTrue("Foreign-schema frame must never reach the engine",
                engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void marketBuyWithBudgetConsumesAsk() {
        MatchingEngine dme = engine.getEngine(1);

        // Place an ask first
        long askPrice = FixedPoint.fromDouble(60000.0);
        long askQty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.ASK, OrderType.LIMIT, askPrice, askQty);
        demuxer.dispatchTrusted(buffer, 0, len1, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // Market buy with enough budget to buy 1 BTC at 60000 — since v8 the
        // budget rides in the price field on the wire.
        long budget = FixedPoint.fromDouble(60000.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.BID, OrderType.MARKET, budget, 0L);
        demuxer.dispatchTrusted(buffer, 0, len2, System.nanoTime());

        assertTrue("Ask should be consumed by market buy", dme.isAskEmpty());
    }
    // ==================== order-schema v12 (RC2 S1 contract 3, 8.2) ====================

    /** A durable-command engine with a schema-4 journal, the way the cluster wires it. */
    private static final class Durable implements AutoCloseable {
        final Engine engine = new Engine("direct");
        final com.match.application.publisher.MatchEventPublisher publisher =
                new com.match.application.publisher.MatchEventPublisher();
        final com.match.infrastructure.journal.SettlementJournal journal =
                new com.match.infrastructure.journal.SettlementJournal(1 << 16);
        final SbeDemuxer demuxer = new SbeDemuxer(engine);

        Durable() {
            publisher.setSettlementJournal(journal);
            publisher.initMarket(1, new com.match.application.publisher.MarketEventHandler() {
                public int getMarketId() { return 1; }
                public void onEvent(com.match.application.publisher.PublishEvent e, long seq, boolean end) { }
            });
            publisher.start();
            engine.setEventPublisher(publisher);
        }

        java.util.List<byte[]> drain() {
            final java.util.List<byte[]> out = new java.util.ArrayList<>();
            journal.ringBuffer().read((t, b, i, n) -> {
                final byte[] v = new byte[n];
                b.getBytes(i, v);
                out.add(v);
            }, 1024);
            return out;
        }

        @Override public void close() { publisher.shutdown(); engine.close(); }
    }

    private static final long V12_PRICE = FixedPoint.fromDouble(59000);
    private static final long V12_QTY = FixedPoint.fromDouble(1);

    /** A v12 DurableOrderCommand CREATE (block 80) through the generated encoder. */
    private int encodeDurableV12(final long idLow, final int tif, final long expectedOldRemaining) {
        return encodeDurableV12(idLow, 0 /* CREATE */, 0L, tif, expectedOldRemaining);
    }

    /** A v12 DurableOrderCommand of any kind (0 CREATE, 1 CANCEL, 2 UPDATE) for user 100 / oms 9001. */
    private int encodeDurableV12(final long idLow, final int kind, final long oldOrderId, final int tif,
                                 final long expectedOldRemaining) {
        new DurableOrderCommandEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
                .commandIdHigh(17).commandIdLow(idLow).userId(100).omsOrderId(9001).oldOrderId(oldOrderId)
                .price(V12_PRICE).quantity(V12_QTY).budget(0).marketId(1)
                .commandKind((short) kind).orderType((short) 0).orderSide((short) 0)
                .timeInForce((short) tif).expectedOldRemaining(expectedOldRemaining);
        return MessageHeaderEncoder.ENCODED_LENGTH + DurableOrderCommandEncoder.BLOCK_LENGTH;
    }

    /** The same command as a v11 frame: header version 11, block 71, the first 71 root bytes. */
    private int encodeDurableV11(final long idLow) {
        encodeDurableV12(idLow, 0, 0);
        headerEncoder.wrap(buffer, 0).blockLength(71).version(11);
        // Bytes past the v11 frame are not part of it; poison them so a decoder that read past
        // the declared block would see garbage.
        buffer.putByte(8 + 71, (byte) 0x7F);
        return MessageHeaderEncoder.ENCODED_LENGTH + 71;
    }

    private static com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder outcome(final byte[] frame) {
        final UnsafeBuffer b = new UnsafeBuffer(frame);
        return new com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder().wrapAndApplyHeader(
                b, 0, new com.match.infrastructure.mejournal.generated.MessageHeaderDecoder());
    }

    @Test
    public void perVersionMinimumBlockOfTheGrownTemplates() {
        assertEquals(71, SbeDemuxer.minimumBlockLength(DurableOrderCommandDecoder.TEMPLATE_ID, 9));
        assertEquals(71, SbeDemuxer.minimumBlockLength(DurableOrderCommandDecoder.TEMPLATE_ID, 11));
        assertEquals(80, SbeDemuxer.minimumBlockLength(DurableOrderCommandDecoder.TEMPLATE_ID, 12));
        assertEquals(17, SbeDemuxer.minimumBlockLength(EngineConfigDecoder.TEMPLATE_ID, 9));
        assertEquals(17, SbeDemuxer.minimumBlockLength(EngineConfigDecoder.TEMPLATE_ID, 11));
        assertEquals(35, SbeDemuxer.minimumBlockLength(EngineConfigDecoder.TEMPLATE_ID, 12));
        assertEquals(8, SbeDemuxer.minimumBlockLength(ReleaseCommandIdsDecoder.TEMPLATE_ID, 12));
        for (int v = 9; v <= 12; v++) {
            assertEquals(CreateOrderDecoder.BLOCK_LENGTH, SbeDemuxer.minimumBlockLength(CreateOrderDecoder.TEMPLATE_ID, v));
            assertEquals(CancelOrderDecoder.BLOCK_LENGTH, SbeDemuxer.minimumBlockLength(CancelOrderDecoder.TEMPLATE_ID, v));
            assertEquals(UpdateOrderDecoder.BLOCK_LENGTH, SbeDemuxer.minimumBlockLength(UpdateOrderDecoder.TEMPLATE_ID, v));
        }
    }

    // RC2 S2 split (F1): the CREATE keeps tif 2 with expectedOldRemaining 0 and stays applied;
    // the value 5 moved to an UPDATE against a resting leg (v12UpdateWithExpectedOldRemainingIsApplied).
    @Test
    public void v12DurableCommandCarriesTimeInForceAndExpectedOldRemaining() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 2, 0), 1000L);
            assertEquals(0, d.demuxer.decodeRejectCount());
            final java.util.List<byte[]> records = d.drain();
            assertEquals(1, records.size());
            final var o = outcome(records.get(0));
            assertEquals(2, o.timeInForce());
            assertEquals(0L, o.expectedOldRemaining());
            assertEquals(1, d.engine.commandLedger().size());
        }
    }

    @Test
    public void v12CreateWithExpectedOldRemainingIsDecodePoison() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, 5), 1000L);
            assertEquals("F1: expectedOldRemaining on a CREATE is decode poison", 1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.applyErrorCount());
            assertEquals(0, d.engine.commandLedger().size());
            assertTrue(d.drain().isEmpty());
        }
    }

    @Test
    public void v12CancelWithExpectedOldRemainingIsDecodePoison() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, 0), 1000L);
            final long resting = outcome(d.drain().get(0)).orderId();
            assertTrue("the CREATE rests", resting > 0);
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(2, 1 /* CANCEL */, resting, 0, 5), 1001L);
            assertEquals("F1: expectedOldRemaining on a CANCEL is decode poison", 1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.applyErrorCount());
            assertEquals("only the CREATE is in the ledger", 1, d.engine.commandLedger().size());
            assertTrue("no record for the refused CANCEL", d.drain().isEmpty());
            assertFalse("the leg still rests", d.engine.getEngine(1).isBidEmpty());
        }
    }

    @Test
    public void v12UpdateWithExpectedOldRemainingIsApplied() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, 0), 1000L);
            final long resting = outcome(d.drain().get(0)).orderId();
            assertTrue("the CREATE rests", resting > 0);
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(2, 2 /* UPDATE */, resting, 0, 5), 1001L);
            assertEquals(0, d.demuxer.decodeRejectCount());
            final java.util.List<byte[]> records = d.drain().stream()
                    .filter(r -> new UnsafeBuffer(r).getShort(2, java.nio.ByteOrder.LITTLE_ENDIAN)
                            == com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder.TEMPLATE_ID)
                    .collect(java.util.stream.Collectors.toList());
            assertEquals(1, records.size());
            final var o = outcome(records.get(0));
            assertEquals(2, o.commandKind());
            assertEquals("echoed into record 3", 5L, o.expectedOldRemaining());
            assertEquals(com.match.domain.commands.DurableCommandOutcome.APPLIED, o.result());
            assertEquals(2, d.engine.commandLedger().size());
        }
    }

    @Test
    public void v12TimeInForceThreeIsDecodePoison() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 3, 0), 1000L);
            assertEquals(1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.applyErrorCount());
            assertEquals(0, d.engine.commandLedger().size());
            assertTrue(d.drain().isEmpty());
        }
    }

    @Test
    public void v12NegativeExpectedOldRemainingIsDecodePoison() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, -1), 1000L);
            assertEquals(1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.applyErrorCount());
            assertEquals(0, d.engine.commandLedger().size());
            assertTrue(d.drain().isEmpty());
        }
    }

    @Test
    public void v11DurableCommandMapsToZeroZeroAndJournalsLikeAV12ZeroZeroFrame() {
        final byte[] v11Record;
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV11(1), 1000L);
            assertEquals("a v11 block-71 frame is not a poison or bounds reject", 0, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.schemaRejectCount());
            assertEquals(1, d.engine.commandLedger().size());
            final java.util.List<byte[]> records = d.drain();
            assertEquals(1, records.size());
            v11Record = records.get(0);
            assertEquals(0, outcome(v11Record).timeInForce());
            assertEquals(0L, outcome(v11Record).expectedOldRemaining());
        }
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, 0), 1000L);
            assertArrayEquals("v11 and v12 0/0 frames journal identical records",
                    v11Record, d.drain().get(0));
        }
    }

    @Test
    public void v12HeaderWithThePre12BlockOnTemplate9IsAFrameBoundsReject() {
        try (Durable d = new Durable()) {
            encodeDurableV12(1, 0, 0);
            headerEncoder.wrap(buffer, 0).blockLength(71);
            d.demuxer.dispatchTrusted(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + 71, 1000L);
            assertEquals(1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.engine.commandLedger().size());
            assertTrue(d.drain().isEmpty());
        }
    }

    private int encodeRelease(final int entries) {
        final ReleaseCommandIdsEncoder e = new ReleaseCommandIdsEncoder();
        final ReleaseCommandIdsEncoder.ReleasesEncoder g = e.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .releaseSeq(3).releasesCount(entries);
        for (int i = 0; i < entries; i++) {
            g.next().commandIdHigh(17).idLowFloor(i).scope(ReleaseScope.UP_TO);
        }
        return MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
    }

    @Test
    public void releaseCommandIdsIsCountedUnservedAndLeavesTheLedgerAlone() {
        try (Durable d = new Durable()) {
            d.demuxer.dispatchTrusted(buffer, 0, encodeDurableV12(1, 0, 0), 1000L);
            d.drain();
            final int before = d.engine.commandLedger().size();
            d.demuxer.dispatchTrusted(buffer, 0, encodeRelease(2), 1001L);
            assertEquals(1, d.demuxer.unservedTemplateCount());
            assertEquals(0, d.demuxer.decodeRejectCount());
            assertEquals(before, d.engine.commandLedger().size());
            assertTrue("no journal record for an unserved template", d.drain().isEmpty());
        }
    }

    @Test
    public void releaseCommandIdsWithNumInGroupPastTheFrameIsAFrameBoundsReject() {
        try (Durable d = new Durable()) {
            final int len = encodeRelease(2);
            // group header after the 8-byte root: numInGroup at 8 + 8 + 2
            buffer.putShort(8 + 8 + 2, (short) 3, java.nio.ByteOrder.LITTLE_ENDIAN);
            d.demuxer.dispatchTrusted(buffer, 0, len, 1000L);
            assertEquals(1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.unservedTemplateCount());
        }
    }

    @Test
    public void releaseCommandIdsWithATruncatedGroupHeaderIsAFrameBoundsReject() {
        try (Durable d = new Durable()) {
            encodeRelease(0);
            d.demuxer.dispatchTrusted(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + 8 + 2, 1000L);
            assertEquals(1, d.demuxer.decodeRejectCount());
            assertEquals(0, d.demuxer.unservedTemplateCount());
        }
    }
}
