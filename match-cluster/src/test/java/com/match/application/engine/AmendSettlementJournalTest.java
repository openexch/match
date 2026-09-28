// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.OrderRejectReason;
import com.match.application.publisher.*;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.journal.SettlementJournal;
import com.match.infrastructure.journal.generated.JournalTerminalDecoder;
import com.match.infrastructure.journal.generated.JournalTradeDecoder;
import com.match.infrastructure.journal.generated.MessageHeaderDecoder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

/** Exercises Engine -> real publisher -> settlement journal and the separate OMS wire lane. */
public class AmendSettlementJournalTest {
    private static final int MARKET = Engine.MARKET_BTC_USD;
    private static final long OMS = 9001;
    private static final long PRICE = FixedPoint.fromDouble(59000);
    private static final long QTY = FixedPoint.fromDouble(1);
    private Engine engine;
    private MatchEventPublisher publisher;
    private SettlementJournal journal;
    private final List<Status> wire = new CopyOnWriteArrayList<>();
    private record Status(long order, long oms, int status, int reason) {}
    private record Entry(int template, long order, long oms, int status) {}

    @Before public void setUp() {
        engine = new Engine("direct");
        journal = new SettlementJournal(1 << 16);
        publisher = new MatchEventPublisher();
        publisher.setSettlementJournal(journal);
        publisher.initMarket(MARKET, new MarketEventHandler() {
            @Override public int getMarketId() { return MARKET; }
            @Override public void onEvent(PublishEvent event, long seq, boolean end) {
                if (event.getEventType() == PublishEventType.ORDER_STATUS_UPDATE) {
                    wire.add(new Status(event.getOrderId(), event.getOmsOrderId(),
                        event.getOrderStatus(), event.getRejectReason()));
                }
            }
        });
        engine.setEventPublisher(publisher);
        publisher.start();
    }

    @After public void tearDown() {
        if (publisher != null && publisher.isRunning()) publisher.shutdown();
        if (engine != null) engine.close();
    }

    private long create(OrderSide side, long price, long quantity, long oms) {
        long id = engine.getOrderIdGenerator();
        CreateOrderCommand c = new CreateOrderCommand();
        c.setUserId(oms); c.setOmsOrderId(oms); c.setOrderSide(side);
        c.setOrderType(OrderType.LIMIT_MAKER); c.setPrice(price); c.setQuantity(quantity);
        engine.acceptOrder(MARKET, Engine.CMD_CREATE, c, 1000);
        return id;
    }

    private void amend(long id, OrderSide side, long price, long qty) {
        UpdateOrderCommand c = new UpdateOrderCommand();
        c.setUserId(OMS); c.setOrderId(id); c.setOrderSide(side);
        c.setOrderType(OrderType.LIMIT_MAKER); c.setPrice(price); c.setQuantity(qty);
        engine.acceptOrder(MARKET, Engine.CMD_UPDATE, c, 2000);
    }

    private List<Entry> drain() {
        List<Entry> result = new ArrayList<>();
        journal.ringBuffer().read((type, buffer, index, length) -> {
            MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(buffer, index);
            if (h.templateId() == JournalTerminalDecoder.TEMPLATE_ID) {
                JournalTerminalDecoder d = new JournalTerminalDecoder().wrap(buffer,
                    index + MessageHeaderDecoder.ENCODED_LENGTH, h.blockLength(), h.version());
                result.add(new Entry(h.templateId(), d.orderId(), d.omsOrderId(), d.status().value()));
            } else {
                assertEquals(JournalTradeDecoder.TEMPLATE_ID, h.templateId());
                result.add(new Entry(h.templateId(), 0, 0, 0));
            }
        }, 1024);
        return result;
    }

    private void awaitWire(Status expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!wire.contains(expected) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue("missing wire response " + expected + " in " + wire, wire.contains(expected));
    }

    private void rejectedAmendKeepsLiveOrder(long newPrice, long newQty, OrderSide side, int reason) {
        long old = create(OrderSide.BID, PRICE, QTY, OMS);
        amend(old, side, newPrice, newQty);
        assertEquals(PRICE, engine.getEngine(MARKET).getBestBid());
        assertEquals(OMS, engine.getOrderIdToOmsOrderId().get(old));
        // The live order can still fill: its eventual trade must precede any release terminal.
        CreateOrderCommand sell = new CreateOrderCommand();
        sell.setUserId(9002); sell.setOmsOrderId(9002); sell.setOrderSide(OrderSide.ASK);
        sell.setOrderType(OrderType.MARKET); sell.setQuantity(QTY);
        engine.acceptOrder(MARKET, Engine.CMD_CREATE, sell, 3000);
        // Production shutdown halts rather than drains; await the actual delivery explicitly.
        awaitWire(new Status(old, OMS, OrderStatusType.REJECTED, reason));
        assertTrue(engine.getEngine(MARKET).isBidEmpty());
        List<Entry> entries = drain();
        assertEquals("amend rejection must not release a still-live order's hold", 3, entries.size());
        assertEquals(JournalTradeDecoder.TEMPLATE_ID, entries.get(0).template());
        assertTrue(entries.contains(new Entry(JournalTerminalDecoder.TEMPLATE_ID, old, OMS, OrderStatusType.FILLED)));
        assertEquals(2, journal.appendedTerminals());
    }

    @Test public void invalidQuantityKeepsHoldUntilRealFill() {
        rejectedAmendKeepsLiveOrder(PRICE, 0, OrderSide.BID, OrderRejectReason.INVALID_QUANTITY);
    }
    @Test public void invalidPriceKeepsHoldUntilRealFill() {
        rejectedAmendKeepsLiveOrder(0, QTY, OrderSide.BID, OrderRejectReason.PRICE_OUT_OF_RANGE);
    }
    @Test public void overflowingNotionalKeepsHoldUntilRealFill() {
        rejectedAmendKeepsLiveOrder(PRICE, Long.MAX_VALUE, OrderSide.BID, OrderRejectReason.OVERFLOW);
    }
    @Test public void crossingPostOnlyAmendKeepsHoldUntilRealFill() {
        create(OrderSide.ASK, PRICE + FixedPoint.fromDouble(1000), QTY, 9003);
        rejectedAmendKeepsLiveOrder(PRICE + FixedPoint.fromDouble(2000), QTY,
            OrderSide.BID, OrderRejectReason.WOULD_CROSS);
    }
    @Test public void wrongSideAmendDoesNotCloseExistingOrder() {
        rejectedAmendKeepsLiveOrder(PRICE + FixedPoint.fromDouble(100), QTY,
            OrderSide.ASK, OrderRejectReason.ORDER_NOT_FOUND);
    }
    @Test public void unknownAmendIsCommandRejectionWithoutReleaseProof() {
        amend(999999, OrderSide.BID, PRICE, QTY);
        awaitWire(new Status(999999, 0, OrderStatusType.REJECTED, OrderRejectReason.ORDER_NOT_FOUND));
        assertTrue("unknown amend must not invent a terminal", drain().isEmpty());
    }
    @Test public void successfulReplaceReleasesOnlyOnActualCancel() {
        long old = create(OrderSide.BID, PRICE, QTY, OMS);
        long replacement = engine.getOrderIdGenerator();
        amend(old, OrderSide.BID, PRICE + FixedPoint.fromDouble(100), QTY);
        assertTrue(drain().isEmpty());
        CancelOrderCommand c = new CancelOrderCommand();
        c.setUserId(OMS); c.setOrderId(replacement);
        engine.acceptOrder(MARKET, Engine.CMD_CANCEL, c, 3000);
        assertEquals(List.of(new Entry(JournalTerminalDecoder.TEMPLATE_ID,
            replacement, OMS, OrderStatusType.CANCELLED)), drain());
    }
    @Test public void failedReplacementAfterOldCancelStillJournalsTerminal() {
        for (int i = 0; i < 64; i++) create(OrderSide.BID, PRICE, QTY, 10000 + i);
        long old = create(OrderSide.BID, PRICE - FixedPoint.fromDouble(1000), QTY, OMS);
        long replacement = engine.getOrderIdGenerator();
        amend(old, OrderSide.BID, PRICE, QTY);
        assertEquals(List.of(new Entry(JournalTerminalDecoder.TEMPLATE_ID,
            replacement, OMS, OrderStatusType.REJECTED)), drain());
        assertEquals(-1, engine.getOrderIdToOmsOrderId().get(old));
    }
    @Test public void rejectedCreateStillJournalsTerminal() {
        long id = create(OrderSide.BID, PRICE, 0, OMS);
        assertEquals(List.of(new Entry(JournalTerminalDecoder.TEMPLATE_ID,
            id, OMS, OrderStatusType.REJECTED)), drain());
    }
}
