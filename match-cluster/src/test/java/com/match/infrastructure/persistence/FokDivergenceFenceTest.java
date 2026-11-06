// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.MatchingEngine;
import com.match.application.publisher.MatchEventSink;
import com.match.domain.FixedPoint;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.S5Frames.*;
import static org.junit.Assert.*;

/**
 * RC2 S5 contract 3.2 (D-d, FIX-3): a FOK the pre-walk judged fillable that does not fill is an
 * apply failure, thrown before the first trade is published: no journal frame, no egress, no
 * journalSeq and no tradeId for the command, and the demuxer counts an apply error and rethrows
 * (the service then fails fast at that log position). Section 3.5 proves the case unreachable with
 * the real books; this test reaches it with a {@link MatchingEngine} double whose pre-walk always
 * says "fillable" and whose sweep matches nothing.
 */
public class FokDivergenceFenceTest {

    /** Delegates to a real array engine except: canFillCompletely true, the no-rest sweep leaves everything. */
    static final class DivergingEngine implements MatchingEngine {
        final ArrayMatchingEngine d;
        boolean own;
        long remaining;

        DivergingEngine(ArrayMatchingEngine d) {
            this.d = d;
        }

        public boolean canFillCompletely(boolean isBuy, long price, long quantity) {
            return true;
        }

        public int processLimitOrderNoRest(long orderId, long userId, boolean isBuy, long price, long quantity) {
            own = true;
            remaining = quantity;
            return 0;
        }

        public long restingRemaining(long orderId, boolean isBuy) {
            return -1L;
        }

        public int processLimitOrder(long orderId, long userId, boolean isBuy, long price, long quantity) {
            own = false;
            return d.processLimitOrder(orderId, userId, isBuy, price, quantity);
        }

        public int processMarketOrder(long orderId, long userId, boolean isBuy, long quantity, long budget) {
            own = false;
            return d.processMarketOrder(orderId, userId, isBuy, quantity, budget);
        }

        public int addOrderNoMatch(long orderId, long userId, boolean isBuy, long price, long quantity) {
            return d.addOrderNoMatch(orderId, userId, isBuy, price, quantity);
        }

        public boolean cancelOrder(long orderId, boolean isBuy) { return d.cancelOrder(orderId, isBuy); }
        public long restingUserId(long orderId, boolean isBuy) { return d.restingUserId(orderId, isBuy); }
        public int validateLimitPrice(long price) { return d.validateLimitPrice(price); }
        public int getLastRestRejectReason() { return own ? 12 : d.getLastRestRejectReason(); }
        public boolean wasMatchLimitReached() { return !own && d.wasMatchLimitReached(); }
        public int getMatchCount() { return own ? 0 : d.getMatchCount(); }
        public long getMatchMakerOrderId(int i) { return d.getMatchMakerOrderId(i); }
        public long getMatchMakerUserId(int i) { return d.getMatchMakerUserId(i); }
        public long getMatchPrice(int i) { return d.getMatchPrice(i); }
        public long getMatchQuantity(int i) { return d.getMatchQuantity(i); }
        public boolean isMatchMakerFilled(int i) { return d.isMatchMakerFilled(i); }
        public long getTakerRemainingQuantity() { return own ? remaining : d.getTakerRemainingQuantity(); }
        public long getTakerRemainingBudget() { return d.getTakerRemainingBudget(); }
        public long getBestAsk() { return d.getBestAsk(); }
        public long getBestBid() { return d.getBestBid(); }
        public boolean isAskEmpty() { return d.isAskEmpty(); }
        public boolean isBidEmpty() { return d.isBidEmpty(); }
        public long getBidVersion() { return d.getBidVersion(); }
        public long getAskVersion() { return d.getAskVersion(); }
        public void publishTopOfBook() { d.publishTopOfBook(); }
        public void collectTopLevels(int maxLevels) { d.collectTopLevels(maxLevels); }
        public int getTopBidCount() { return d.getTopBidCount(); }
        public int getTopAskCount() { return d.getTopAskCount(); }
        public long[] getTopBidPrices() { return d.getTopBidPrices(); }
        public long[] getTopBidQuantities() { return d.getTopBidQuantities(); }
        public int[] getTopBidOrderCounts() { return d.getTopBidOrderCounts(); }
        public long[] getTopAskPrices() { return d.getTopAskPrices(); }
        public long[] getTopAskQuantities() { return d.getTopAskQuantities(); }
        public int[] getTopAskOrderCounts() { return d.getTopAskOrderCounts(); }
        public long getCollectedBidVersion() { return d.getCollectedBidVersion(); }
        public long getCollectedAskVersion() { return d.getCollectedAskVersion(); }
        public long getCollectedVersion() { return d.getCollectedVersion(); }
        public void setBookVersion(long version) { d.setBookVersion(version); }
        public long getBookVersion() { return d.getBookVersion(); }
        public long getCollectedBookVersion() { return d.getCollectedBookVersion(); }
        public long[] getBidOrders() { return d.getBidOrders(); }
        public long[] getAskOrders() { return d.getAskOrders(); }
        public int restoreFromSnapshot(long[] bidOrders, long[] askOrders) { return d.restoreFromSnapshot(bidOrders, askOrders); }
        public void clear() { d.clear(); }
    }

    /** Counts every sink call (egress and journal) and forwards it to the real publisher. */
    static final class CountingSink implements MatchEventSink {
        final MatchEventSink p;
        final AtomicInteger calls = new AtomicInteger();

        CountingSink(MatchEventSink p) {
            this.p = p;
        }

        @Override
        public boolean publishTradeExecution(int marketId, long timestamp, long tradeId, long journalSeq,
                long takerOrderId, long takerUserId, long makerOrderId, long makerUserId, long price, long quantity,
                boolean takerIsBuy, long takerOmsOrderId, long makerOmsOrderId, long egressSeq) {
            calls.incrementAndGet();
            return p.publishTradeExecution(marketId, timestamp, tradeId, journalSeq, takerOrderId, takerUserId,
                    makerOrderId, makerUserId, price, quantity, takerIsBuy, takerOmsOrderId, makerOmsOrderId, egressSeq);
        }

        @Override
        public boolean publishOrderStatusUpdate(int marketId, long timestamp, long orderId, long userId, int orderStatus,
                long remainingQty, long filledQty, long orderPrice, boolean orderIsBuy, long omsOrderId,
                int rejectReason, long egressSeq) {
            calls.incrementAndGet();
            return p.publishOrderStatusUpdate(marketId, timestamp, orderId, userId, orderStatus, remainingQty, filledQty,
                    orderPrice, orderIsBuy, omsOrderId, rejectReason, egressSeq);
        }

        @Override
        public boolean publishOrderStatusUpdate(int marketId, long timestamp, long orderId, long userId, int orderStatus,
                long remainingQty, long filledQty, long orderPrice, boolean orderIsBuy, long omsOrderId,
                int rejectReason, long egressSeq, boolean journalTerminal, long journalSeq) {
            calls.incrementAndGet();
            return p.publishOrderStatusUpdate(marketId, timestamp, orderId, userId, orderStatus, remainingQty, filledQty,
                    orderPrice, orderIsBuy, omsOrderId, rejectReason, egressSeq, journalTerminal, journalSeq);
        }

        @Override
        public void publishCommandOutcome(long egressSeq, long journalSeq,
                com.match.domain.commands.DurableOutcomeView outcome, int ledgerSizeAfter, int ledgerCapacity) {
            calls.incrementAndGet();
            p.publishCommandOutcome(egressSeq, journalSeq, outcome, ledgerSizeAfter, ledgerCapacity);
        }
    }

    @Test
    public void divergedFokHaltsBeforeAnyRecord() {
        try (Run r = freshWith(S5Frames.config(false, 100, 0))) {
            final var engine = r.engine();
            final DivergingEngine doubleEngine = new DivergingEngine(new ArrayMatchingEngine(
                    FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(150_000.0), FixedPoint.fromDouble(1.0), 4_096, 100));
            engine.getEngines().put(BehaviourTwoHarness.MARKET, doubleEngine);
            final CountingSink sink = new CountingSink(r.r.h.publisher);
            engine.setEventPublisher(sink);

            appliedOrderId(r.send(limit(1, 0, 100, ASK, 60_000, 1, GTC)));
            final int callsBefore = sink.calls.get();
            final long tradeId = engine.getTradeIdGenerator();
            final long seq = engine.meIdentity().nextJournalSeq();
            final int ledger = S4Probe.ledgerSize(engine);
            final long errors = r.r.demuxer.applyErrorCount();
            final byte[] fok = limit(2, 0, 200, BID, 60_000, 1, FOK);
            IllegalStateException thrown = null;
            try {
                r.send(fok);
            } catch (IllegalStateException e) {
                thrown = e;
            }
            assertNotNull("the diverged FOK must fail the apply", thrown);
            assertEquals("FOK feasibility diverged", thrown.getMessage());
            assertEquals("applyErrorCount + 1", errors + 1, r.r.demuxer.applyErrorCount());
            final List<byte[]> frames = r.r.h.drain();
            assertTrue("no journal frame for the command", frames.isEmpty());
            assertEquals("no egress for the command", callsBefore, sink.calls.get());
            assertEquals("no tradeId", tradeId, engine.getTradeIdGenerator());
            assertEquals("no journalSeq", seq, engine.meIdentity().nextJournalSeq());
            assertEquals("not recorded", ledger, S4Probe.ledgerSize(engine));
        }
    }
}
