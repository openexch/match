// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.publisher.MarketDataBroadcaster;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.BookDeltaDecoder;
import com.match.infrastructure.generated.BookSnapshotDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression test for match#115: the emitted Book* stream must be chain-continuous — every
 * BookDelta's {@code fromVersion} names the {@code bookVersion} of the frame the consumer actually
 * received before it.
 *
 * <p>The defect: a book mutation below the visible top-{@code MAX_BOOK_LEVELS} depth bumps the side
 * version (so {@code encodeBookSnapshot} sees a change) but produces an EMPTY visible diff, so no
 * frame is broadcast — yet {@code lastPublishedBookVersion} advanced anyway. The next visible delta
 * then carries a {@code fromVersion} no consumer ever saw, the gateway flags a chain break
 * (match#96), and the book freezes until the next periodic resnapshot (match#112). Observed live as
 * continuous steady-state chain flaps (~4/market/min) with zero cluster-side shed.
 */
public class MarketPublisherBookChainTest {

    private static final long BASE = FixedPoint.fromDouble(100.0);
    private static final long MAX = FixedPoint.fromDouble(200.0);
    private static final long TICK = FixedPoint.fromDouble(1.0);

    private MarketPublisher pub;
    private ArrayMatchingEngine engine;
    private BookFrameCapturingBroadcaster bc;
    private long nextOrderId = 1;

    @Before
    public void setUp() {
        pub = new MarketPublisher(1, "BTC-USD", null);
        engine = new ArrayMatchingEngine(BASE, MAX, TICK, 1024);
        bc = new BookFrameCapturingBroadcaster();
        pub.setMatchingEngine(engine);
        pub.setBroadcaster(bc);
        NegativeControlSeams.apply(pub);
    }

    /** Rest a bid at {@code BASE + ticksAboveBase * TICK} (no asks in the test, so nothing matches). */
    private void restBid(int ticksAboveBase) {
        engine.processLimitOrder(nextOrderId++, 7, true, BASE + ticksAboveBase * TICK, FixedPoint.fromDouble(1.0));
    }

    /** The writer-thread protocol around each mutating command: version, then publish. */
    private void commit(long bookVersion) {
        engine.setBookVersion(bookVersion);
        engine.publishTopOfBook();
    }

    @Test
    public void deepBookChangeMustNotBreakTheEmittedVersionChain() {
        // 25 resting bids: best 20 are visible, ranks 21-25 are below the published depth.
        for (int i = 6; i <= 30; i++) {
            restBid(i);
        }
        commit(1000);
        pub.flushBuffers();
        assertEquals("initial full snapshot expected", 1, bc.frames.size());
        assertEquals(1000, bc.frames.get(0).bookVersion);

        // Deep-only change: a bid at rank 26 (BASE+1 < the 20 visible prices). The bid side version
        // bumps, but the visible top-20 is unchanged — by design nothing needs to be broadcast.
        restBid(1);
        commit(2000);
        pub.flushBuffers();
        assertEquals("a deep-only change has an empty visible diff — no frame expected",
                1, bc.frames.size());

        // Visible change: a new best bid. This MUST be delivered as a delta that extends the last
        // frame the consumer received (bookVersion=1000) — not the silently-skipped 2000.
        restBid(31);
        commit(3000);
        pub.flushBuffers();
        assertEquals("visible change must emit a delta", 2, bc.frames.size());

        BookFrame delta = bc.frames.get(1);
        assertTrue("expected a BookDelta", delta.isDelta);
        assertEquals(3000, delta.bookVersion);
        assertEquals("match#115: delta must chain from the last EMITTED frame's bookVersion; a "
                        + "fromVersion the consumer never received breaks the chain and freezes the book",
                1000, delta.fromVersion);
    }

    @Test
    public void emittedStreamIsChainContinuousAcrossManyMixedFlushes() {
        // Seed 30 bids and snapshot.
        for (int i = 6; i <= 35; i++) {
            restBid(i);
        }
        commit(1000);
        pub.flushBuffers();

        // Interleave deep-only and visible mutations across flush cycles.
        long v = 1000;
        for (int round = 0; round < 10; round++) {
            v += 100;
            if (round % 2 == 0) {
                restBid(1 + round % 4); // deep: far below the visible window
            } else {
                restBid(36 + round);    // visible: new best bid
            }
            commit(v);
            pub.flushBuffers();
        }

        assertTrue("expected the snapshot plus several deltas", bc.frames.size() >= 2);
        long consumerVersion = -1;
        for (BookFrame f : bc.frames) {
            if (f.isDelta) {
                assertEquals("delta does not extend the previously emitted frame", consumerVersion, f.fromVersion);
            }
            consumerVersion = f.bookVersion;
        }
    }

    // ---- an emptied book is published ----

    private static String describe(List<BookFrame> frames) {
        return frames.isEmpty() ? "length 0" : frames.toString();
    }

    @Test
    public void emptiedBookPublishesAllDeleteDelta() {
        restBid(6);
        restBid(7);
        restBid(8);
        commit(1000);
        pub.flushBuffers();
        assertEquals("initial full snapshot expected", 1, bc.frames.size());
        engine.cancelOrder(1, true);
        engine.cancelOrder(2, true);
        engine.cancelOrder(3, true);
        commit(2000);
        bc.frames.clear();
        pub.flushBuffers();
        if (bc.frames.size() != 1 || !bc.frames.get(0).isDelta) {
            fail("expected a BookDelta after the last level went, got " + describe(bc.frames));
        }
        final BookFrame d = bc.frames.get(0);
        assertEquals(2000, d.bookVersion);
        assertEquals("chains to the last emitted frame", 1000, d.fromVersion);
        assertEquals("one DELETE per vanished level", List.of(2, 2, 2), d.updateTypes);
        // and the chain continues from the empty state
        restBid(9);
        commit(3000);
        bc.frames.clear();
        pub.flushBuffers();
        assertEquals(1, bc.frames.size());
        assertEquals(2000, bc.frames.get(0).fromVersion);
        assertEquals(List.of(0), bc.frames.get(0).updateTypes);
        // second cycle of the reset: empty again, then silent
        engine.cancelOrder(4, true);
        commit(4000);
        bc.frames.clear();
        pub.flushBuffers();
        if (bc.frames.size() != 1 || !bc.frames.get(0).isDelta) {
            fail("expected a BookDelta after the last level went again, got " + describe(bc.frames));
        }
        assertEquals(4000, bc.frames.get(0).bookVersion);
        assertEquals("chains to the NEW frame", 3000, bc.frames.get(0).fromVersion);
        assertEquals("one DELETE for the one vanished level", List.of(2), bc.frames.get(0).updateTypes);
        bc.frames.clear();
        pub.flushBuffers();
        assertEquals("the emptied state was published, the next flush is silent", "length 0", describe(bc.frames));
    }

    @Test
    public void emptyBookAfterResnapshotSendsEmptySnapshot() {
        restBid(6);
        commit(1000);
        pub.flushBuffers();
        engine.cancelOrder(1, true);
        commit(2000);
        pub.flushBuffers();
        bc.requestResnapshot();
        bc.frames.clear();
        pub.flushBuffers();
        if (bc.frames.size() != 1 || bc.frames.get(0).isDelta) {
            fail("expected an empty BookSnapshot after the resnapshot, got " + describe(bc.frames));
        }
        assertEquals(2000, bc.frames.get(0).bookVersion);
        assertEquals(0, bc.frames.get(0).levels);
        // a consumer holding the empty snapshot: the returning level chains from it
        restBid(7);
        commit(3000);
        bc.frames.clear();
        pub.flushBuffers();
        if (bc.frames.size() != 1 || !bc.frames.get(0).isDelta) {
            fail("expected a BookDelta from the empty snapshot, got " + describe(bc.frames));
        }
        assertEquals("chains to the empty snapshot", 2000, bc.frames.get(0).fromVersion);
        assertEquals(3000, bc.frames.get(0).bookVersion);
        assertEquals("one NEW", List.of(0), bc.frames.get(0).updateTypes);
        // the reset's second cycle, from the non-empty side
        bc.requestResnapshot();
        bc.frames.clear();
        pub.flushBuffers();
        if (bc.frames.size() != 1 || bc.frames.get(0).isDelta) {
            fail("expected a BookSnapshot after the second resnapshot, got " + describe(bc.frames));
        }
        assertEquals(3000, bc.frames.get(0).bookVersion);
        assertEquals(1, bc.frames.get(0).levels);
    }

    @Test
    public void emptyBookWithNothingPublishedStaysSilent() {
        // a versioned empty book: the published state is empty (sent once after a reset) ...
        restBid(6);
        engine.cancelOrder(1, true);
        commit(1000);
        pub.flushBuffers();
        final int afterFirst = bc.frames.size();
        assertEquals("the empty state was published once", 1, afterFirst);
        // ... and an add + cancel between two flushes changes the side versions, not the empty state
        restBid(7);
        engine.cancelOrder(2, true);
        commit(2000);
        pub.flushBuffers();
        pub.flushBuffers();
        assertEquals("no frame while the published state is empty and stays empty", afterFirst, bc.frames.size());
    }

    @Test
    public void emptyNeverMutatedMarketStaysSilentAfterResnapshot() {
        pub.flushBuffers();
        bc.requestResnapshot();
        pub.flushBuffers();
        if (!bc.frames.isEmpty()) {
            final BookFrame f = bc.frames.get(0);
            fail("expected no frame for a never-mutated market, got " + (f.isDelta ? "BookDelta" : "BookSnapshot")
                    + " bookVersion " + f.bookVersion);
        }
    }

    private static final class BookFrame {
        boolean isDelta;
        long bookVersion;
        long fromVersion;
        int levels;                                   // snapshot: bid + ask levels
        final List<Integer> updateTypes = new ArrayList<>(); // delta: raw update type per change

        @Override
        public String toString() {
            return (isDelta ? "BookDelta{from=" + fromVersion + " " : "BookSnapshot{levels=" + levels + " ")
                    + "bookVersion=" + bookVersion + "}";
        }
    }

    /** Captures broadcast() BookSnapshot/BookDelta frames, decoding versions immediately. */
    private static final class BookFrameCapturingBroadcaster implements MarketDataBroadcaster {
        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final BookSnapshotDecoder snapshotDecoder = new BookSnapshotDecoder();
        final BookDeltaDecoder deltaDecoder = new BookDeltaDecoder();
        final List<BookFrame> frames = new ArrayList<>();
        long generation;

        @Override
        public boolean hasSubscribers() {
            return true;
        }

        @Override
        public void requestResnapshot() {
            generation++;
        }

        @Override
        public long resnapshotGeneration() {
            return generation;
        }

        @Override
        public void broadcast(DirectBuffer buffer, int offset, int length) {
            // Decode now: the publisher reuses the same encode buffer for the next frame.
            byte[] copy = new byte[length];
            buffer.getBytes(offset, copy, 0, length);
            UnsafeBuffer buf = new UnsafeBuffer(copy);
            header.wrap(buf, 0);
            BookFrame f = new BookFrame();
            if (header.templateId() == BookSnapshotDecoder.TEMPLATE_ID) {
                snapshotDecoder.wrap(buf, header.encodedLength(), header.blockLength(), header.version());
                f.isDelta = false;
                f.bookVersion = snapshotDecoder.bookVersion();
                f.fromVersion = -1;
                for (BookSnapshotDecoder.BidsDecoder ignored : snapshotDecoder.bids()) {
                    f.levels++; // a group is read in order: walk the bids before the asks
                }
                for (BookSnapshotDecoder.AsksDecoder ignored : snapshotDecoder.asks()) {
                    f.levels++;
                }
            } else if (header.templateId() == BookDeltaDecoder.TEMPLATE_ID) {
                deltaDecoder.wrap(buf, header.encodedLength(), header.blockLength(), header.version());
                f.isDelta = true;
                f.bookVersion = deltaDecoder.bookVersion();
                f.fromVersion = deltaDecoder.fromVersion();
                for (BookDeltaDecoder.ChangesDecoder c : deltaDecoder.changes()) {
                    f.updateTypes.add((int) c.updateTypeRaw());
                }
            } else {
                return; // TradesBatch etc. — not under test
            }
            frames.add(f);
        }

        @Override
        public void broadcastReliable(DirectBuffer buffer, int offset, int length) {
            // OMS-bound path — not under test.
        }
    }
}
