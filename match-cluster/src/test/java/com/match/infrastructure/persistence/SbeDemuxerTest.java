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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("Bid side should have the order", dme.isBidEmpty());
    }

    @Test
    public void createLimitAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(2.0);
        int len = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("Ask side should have the order", dme.isAskEmpty());
    }

    @Test
    public void createLimitMakerBidOrderAppearsInEngine() {
        // LIMIT_MAKER on empty book should succeed (no opposing side to match)
        long price = FixedPoint.fromDouble(59000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(300L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, price, qty);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER bid should appear when no ask to cross", dme.isBidEmpty());
    }

    @Test
    public void createLimitMakerAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(70000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(400L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, price, qty);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER ask should appear when no bid to cross", dme.isAskEmpty());
    }

    @Test
    public void createMarketSellOrderMatchesExistingBid() {
        // Place a bid first
        long bidPrice = FixedPoint.fromDouble(60000.0);
        long bidQty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, bidPrice, bidQty);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isBidEmpty());

        // Market sell should match the bid
        long sellQty = FixedPoint.fromDouble(1.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.MARKET, 0L, sellQty);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

        assertTrue("Bid should be consumed by market sell", dme.isBidEmpty());
    }

    @Test
    public void createOrderOnMultipleMarkets() {
        // BTC-USD (market 1)
        long p1 = FixedPoint.fromDouble(60000.0);
        long q1 = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p1, q1);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        // ETH-USD (market 2)
        long p2 = FixedPoint.fromDouble(3000.0);
        long q2 = FixedPoint.fromDouble(10.0);
        int len2 = encodeCreateOrder(2L, 2, OrderSide.ASK, OrderType.LIMIT, p2, q2);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

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
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isBidEmpty());

        // Cancel it — the engine assigns orderId starting from 1
        long orderId = engine.getOrderIdGenerator() - 1;
        int len2 = encodeCancelOrder(100L, orderId, 1);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

        assertTrue("Order should be cancelled", dme.isBidEmpty());
    }

    @Test
    public void cancelAskOrderRemovesFromEngine() {
        long price = FixedPoint.fromDouble(70000.0);
        long qty = FixedPoint.fromDouble(2.0);
        int len1 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        MatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isAskEmpty());

        long orderId = engine.getOrderIdGenerator() - 1;
        int len2 = encodeCancelOrder(200L, orderId, 1);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

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
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
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
        demuxer.dispatch(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + 20, System.nanoTime());
    }

    @Test
    public void messageTooShortSilentlyIgnored() {
        // Length less than header size (8 bytes)
        demuxer.dispatch(buffer, 0, 4, System.nanoTime());
        // Should not throw
    }

    @Test
    public void zeroLengthMessageSilentlyIgnored() {
        demuxer.dispatch(buffer, 0, 0, System.nanoTime());
    }

    @Test
    public void exactHeaderLengthNoPayload() {
        // Exactly header length but garbage template — should just fall through switch
        headerEncoder.wrap(buffer, 0)
            .blockLength(0)
            .templateId(12345)
            .schemaId(1)
            .version(2);
        demuxer.dispatch(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH, System.nanoTime());
    }

    // ==================== All Order Types / Sides ====================

    @Test
    public void allOrderTypesBidSide() {
        MatchingEngine dme = engine.getEngine(1);

        // LIMIT bid
        long p = FixedPoint.fromDouble(55000.0);
        long q = FixedPoint.fromDouble(0.1);
        int len = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p, q);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isBidEmpty());

        // LIMIT_MAKER bid (lower price, won't cross)
        long p2 = FixedPoint.fromDouble(54000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, p2, q);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
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
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // LIMIT_MAKER ask (higher price, won't cross)
        long p2 = FixedPoint.fromDouble(75000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, p2, q);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

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

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

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
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // Market buy with enough budget to buy 1 BTC at 60000 — since v8 the
        // budget rides in the price field on the wire.
        long budget = FixedPoint.fromDouble(60000.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.BID, OrderType.MARKET, budget, 0L);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

        assertTrue("Ask should be consumed by market buy", dme.isAskEmpty());
    }
}
