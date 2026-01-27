package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.DirectMatchingEngine;
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

    private int encodeCreateOrder(long userId, int marketId,
                                  OrderSide side, OrderType type,
                                  long price, long quantity, long totalPrice) {
        CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.marketId(marketId);
        encoder.orderSide(side);
        encoder.orderType(type);
        encoder.price(price);
        encoder.quantity(quantity);
        encoder.totalPrice(totalPrice);
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
        int len = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty, 0L);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
        assertFalse("Bid side should have the order", dme.isBidEmpty());
    }

    @Test
    public void createLimitAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(60000.0);
        long qty = FixedPoint.fromDouble(2.0);
        int len = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty, 0L);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
        assertFalse("Ask side should have the order", dme.isAskEmpty());
    }

    @Test
    public void createLimitMakerBidOrderAppearsInEngine() {
        // LIMIT_MAKER on empty book should succeed (no opposing side to match)
        long price = FixedPoint.fromDouble(59000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(300L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, price, qty, 0L);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER bid should appear when no ask to cross", dme.isBidEmpty());
    }

    @Test
    public void createLimitMakerAskOrderAppearsInEngine() {
        long price = FixedPoint.fromDouble(70000.0);
        long qty = FixedPoint.fromDouble(0.5);
        int len = encodeCreateOrder(400L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, price, qty, 0L);

        demuxer.dispatch(buffer, 0, len, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
        assertFalse("LIMIT_MAKER ask should appear when no bid to cross", dme.isAskEmpty());
    }

    @Test
    public void createMarketSellOrderMatchesExistingBid() {
        // Place a bid first
        long bidPrice = FixedPoint.fromDouble(60000.0);
        long bidQty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, bidPrice, bidQty, 0L);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
        assertFalse(dme.isBidEmpty());

        // Market sell should match the bid
        long sellQty = FixedPoint.fromDouble(1.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.MARKET, 0L, sellQty, 0L);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

        assertTrue("Bid should be consumed by market sell", dme.isBidEmpty());
    }

    @Test
    public void createOrderOnMultipleMarkets() {
        // BTC-USD (market 1)
        long p1 = FixedPoint.fromDouble(60000.0);
        long q1 = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p1, q1, 0L);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        // ETH-USD (market 2)
        long p2 = FixedPoint.fromDouble(3000.0);
        long q2 = FixedPoint.fromDouble(10.0);
        int len2 = encodeCreateOrder(2L, 2, OrderSide.ASK, OrderType.LIMIT, p2, q2, 0L);
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
        int len1 = encodeCreateOrder(100L, 1, OrderSide.BID, OrderType.LIMIT, price, qty, 0L);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
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
        int len1 = encodeCreateOrder(200L, 1, OrderSide.ASK, OrderType.LIMIT, price, qty, 0L);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());

        DirectMatchingEngine dme = engine.getEngine(1);
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
        DirectMatchingEngine dme = engine.getEngine(1);

        // LIMIT bid
        long p = FixedPoint.fromDouble(55000.0);
        long q = FixedPoint.fromDouble(0.1);
        int len = encodeCreateOrder(1L, 1, OrderSide.BID, OrderType.LIMIT, p, q, 0L);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isBidEmpty());

        // LIMIT_MAKER bid (lower price, won't cross)
        long p2 = FixedPoint.fromDouble(54000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.BID, OrderType.LIMIT_MAKER, p2, q, 0L);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        // Still has bids
        assertFalse(dme.isBidEmpty());
    }

    @Test
    public void allOrderTypesAskSide() {
        DirectMatchingEngine dme = engine.getEngine(1);

        // LIMIT ask
        long p = FixedPoint.fromDouble(70000.0);
        long q = FixedPoint.fromDouble(0.1);
        int len = encodeCreateOrder(1L, 1, OrderSide.ASK, OrderType.LIMIT, p, q, 0L);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // LIMIT_MAKER ask (higher price, won't cross)
        long p2 = FixedPoint.fromDouble(75000.0);
        len = encodeCreateOrder(2L, 1, OrderSide.ASK, OrderType.LIMIT_MAKER, p2, q, 0L);
        demuxer.dispatch(buffer, 0, len, System.nanoTime());
        assertFalse(dme.isAskEmpty());
    }

    @Test
    public void marketBuyWithBudgetConsumesAsk() {
        DirectMatchingEngine dme = engine.getEngine(1);

        // Place an ask first
        long askPrice = FixedPoint.fromDouble(60000.0);
        long askQty = FixedPoint.fromDouble(1.0);
        int len1 = encodeCreateOrder(100L, 1, OrderSide.ASK, OrderType.LIMIT, askPrice, askQty, 0L);
        demuxer.dispatch(buffer, 0, len1, System.nanoTime());
        assertFalse(dme.isAskEmpty());

        // Market buy with enough budget to buy 1 BTC at 60000
        long budget = FixedPoint.fromDouble(60000.0);
        int len2 = encodeCreateOrder(200L, 1, OrderSide.BID, OrderType.MARKET, 0L, 0L, budget);
        demuxer.dispatch(buffer, 0, len2, System.nanoTime());

        assertTrue("Ask should be consumed by market buy", dme.isAskEmpty());
    }
}
