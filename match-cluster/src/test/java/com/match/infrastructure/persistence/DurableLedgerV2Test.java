// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.application.engine.BehaviourTwoHarness.record4;
import static com.match.application.engine.BehaviourTwoHarness.templateId;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 contract sections 2-6 and 9 (W1, W2): the behaviour-2 ledger v2, the
 * {@code ReleaseCommandIds} apply and record 4, derived ownership, the B0/B1 INVALID rows, the
 * recorded capacity C and its report. Every command goes through the real {@link SbeDemuxer}
 * (frames, log positions, the journal ring), and every assertion reads the journal, the books or
 * the engine's replicated state; S4-only APIs are read through {@link S4Probe}.
 */
public class DurableLedgerV2Test {

    static final int CREATE = 0, CANCEL = 1, UPDATE = 2;
    static final int LIMIT = 0, MARKET = 1, LIMIT_MAKER = 2;
    static final int RELEASED = DurableCommandOutcome.RELEASED, INVALID = DurableCommandOutcome.INVALID;
    static final int R_APPLIED = 0, R_NOOP = 1, R_LEG_LIVE = 2;

    /** A behaviour-2 replica plus its log cursor (entry i at 192 + 128 i after the FRESH at 64). */
    static final class Run implements AutoCloseable {
        final Replica r;
        long position = 192L;
        long timestamp = 1_001L;

        Run(Replica r) {
            this.r = r;
        }

        List<byte[]> send(byte[] frame) {
            final List<byte[]> out = r.apply(new Entry(frame, position, timestamp));
            position += 128;
            timestamp++;
            return out;
        }

        Engine engine() {
            return r.h.engine;
        }

        @Override
        public void close() {
            r.close();
        }
    }

    static Run fresh(long capacity) {
        return fresh(capacity, Shape.JOURNALED);
    }

    static Run fresh(long capacity, Shape shape) {
        final Replica r = new Replica(Engine.deferredUntilConfig(), shape);
        r.apply(new Entry(engineConfig(1, 2, G, 12, capacity), 64L, 1_000L));
        return new Run(r);
    }

    static Run freshWith(byte[] config) {
        final Replica r = new Replica();
        r.apply(new Entry(config, 64L, 1_000L));
        return new Run(r);
    }

    static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }

    // ---- core-shaped frames: idHigh = omsOrderId = h, idLow = attempt << 2 | kind ----

    static byte[] create(long h, long attempt, long user, int side, double price, double qty) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, fp(price), fp(qty), 0L, LIMIT, 0, 0L);
    }

    static byte[] createOfType(long h, long attempt, long user, int side, double price, double qty, int type) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, fp(price), fp(qty), 0L, type, 0, 0L);
    }

    static byte[] createOn(int marketId, long h, long attempt, long user, int side, double price, double qty) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, fp(price), fp(qty), 0L, LIMIT, 0, 0L,
                marketId);
    }

    static byte[] cancel(long h, long attempt, long user, long leg) {
        return durableRaw(h, (attempt << 2) | CANCEL, CANCEL, user, h, leg, 0, 0L, 0L, 0L, LIMIT, 0, 0L);
    }

    static byte[] cancelOn(int marketId, long h, long attempt, long user, long leg) {
        return durableRaw(h, (attempt << 2) | CANCEL, CANCEL, user, h, leg, 0, 0L, 0L, 0L, LIMIT, 0, 0L, marketId);
    }

    static byte[] amend(long h, long attempt, long user, long leg, int side, double price, double qty, int type) {
        return durableRaw(h, (attempt << 2) | UPDATE, UPDATE, user, h, leg, side, fp(price), fp(qty), 0L, type, 0, 0L);
    }

    static long[] upTo(long h, long floor) {
        return new long[]{h, floor, 0};
    }

    static long[] order(long h) {
        return new long[]{h, 3, 1};
    }

    static byte[] releaseFrame(long releaseSeq, long[]... entries) {
        final long[] flat = new long[entries.length * 3];
        for (int i = 0; i < entries.length; i++) {
            System.arraycopy(entries[i], 0, flat, 3 * i, 3);
        }
        return release(releaseSeq, flat);
    }

    // ---- journal readers ----

    static List<byte[]> of(List<byte[]> frames, int template) {
        return BehaviourTwoHarness.ofTemplate(frames, template);
    }

    /** The one record 3 of an entry. */
    static CommandOutcomeDecoder only3(List<byte[]> frames) {
        final List<byte[]> r3 = of(frames, 3);
        assertEquals("exactly one record 3 in " + frames.size() + " frame(s)", 1, r3.size());
        return record3(r3.get(0));
    }

    /** The one record 4 of an entry. */
    static CommandReleaseDecoder only4(List<byte[]> frames) {
        final List<byte[]> r4 = of(frames, 4);
        assertEquals("exactly one record 4 in " + frames.size() + " frame(s)", 1, r4.size());
        return record4(r4.get(0));
    }

    /** Entry results of a record 4, as {result, removed} pairs. */
    static List<int[]> entries(CommandReleaseDecoder d) {
        final List<int[]> out = new ArrayList<>();
        final CommandReleaseDecoder.ReleasesDecoder g = d.releases();
        while (g.hasNext()) {
            g.next();
            out.add(new int[]{g.resultRaw(), g.removed()});
        }
        return out;
    }

    static long appliedOrderId(List<byte[]> frames) {
        final CommandOutcomeDecoder o = only3(frames);
        assertEquals("APPLIED", DurableCommandOutcome.APPLIED, o.result());
        return o.orderId();
    }

    static int resting(Engine e) {
        final var me = e.getEngine(BehaviourTwoHarness.MARKET);
        return me.getBidOrders().length / 4 + me.getAskOrders().length / 4;
    }

    // ==================== F2: release then duplicate ====================

    /** The F2 assertions after the release step; shared by the test and its negative control. */
    private static void assertDuplicateCreateRefused(Run r, long h) {
        final long gen = r.engine().getOrderIdGenerator();
        final List<byte[]> dup = r.send(create(h, 0, 100, 0, 59_000, 1));
        final CommandOutcomeDecoder o = only3(dup);
        assertEquals("the duplicate CREATE is RELEASED", RELEASED, o.result());
        assertEquals("not recorded", 0, o.ledgerSize());
        assertEquals("no new order", gen, r.engine().getOrderIdGenerator());
        assertTrue("no trade", of(dup, 1).isEmpty());
        assertTrue("no terminal", of(dup, 2).isEmpty());
        assertEquals("no second leg", 0, resting(r.engine()));
    }

    @Test
    public void releasedCreateDuplicateIsRefusedNotReapplied() {
        final long h = 501;
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            final CommandOutcomeDecoder cancelled = only3(r.send(cancel(h, 1, 100, leg)));
            assertEquals(DurableCommandOutcome.APPLIED, cancelled.result());
            assertEquals(2, cancelled.ledgerSize());

            final CommandReleaseDecoder rel = only4(r.send(releaseFrame(1, order(h))));
            final List<int[]> all = entries(rel);
            assertEquals(1, all.size());
            final int[] e = all.get(0);
            assertEquals("ORDER(h) APPLIED", R_APPLIED, e[0]);
            assertEquals("both ids of h removed", 2, e[1]);
            assertEquals(0, rel.ledgerSize());
            assertEquals(1, rel.tombstoneRanges());
            assertEquals(1_000, rel.ledgerCapacity());

            assertDuplicateCreateRefused(r, h);
            assertEquals(1, S4Probe.released(r.engine()));
        }
        // Negative control: a release that removes WITHOUT a tombstone lets the duplicate apply as a
        // second order, and the same assertions must catch it.
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            appliedOrderId(r.send(cancel(h, 1, 100, leg)));
            assertEquals(2, S4Probe.forgetWithoutTombstone(r.engine(), h));
            try {
                assertDuplicateCreateRefused(r, h);
                fail("the negative control must fail: without the tombstone the duplicate is a new order");
            } catch (AssertionError expected) {
                assertEquals("the duplicate became a second order", 1, resting(r.engine()));
            }
        }
    }

    @Test
    public void upToFloorRefusesLateAmendDuplicate() {
        final long h = 502;
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            final List<byte[]> replaced = r.send(amend(h, 1, 100, leg, 0, 58_000, 1, LIMIT));
            final CommandOutcomeDecoder a = only3(replaced);
            assertEquals(DurableCommandOutcome.APPLIED, a.result());
            assertEquals("the old leg was cancelled", 1, a.oldCancelled());
            final long newLeg = a.orderId();

            final CommandReleaseDecoder rel = only4(r.send(releaseFrame(2, upTo(h, (1 << 2) | UPDATE))));
            assertArrayEquals(new int[]{R_APPLIED, 2}, entries(rel).get(0));
            assertEquals(0, rel.ledgerSize());
            assertEquals(1, S4Probe.floorCount(r.engine()));

            final long gen = r.engine().getOrderIdGenerator();
            final List<byte[]> dup = r.send(amend(h, 1, 100, leg, 0, 58_000, 1, LIMIT));
            assertEquals("the late amend duplicate is RELEASED", RELEASED, only3(dup).result());
            assertEquals("one replace only: no new order", gen, r.engine().getOrderIdGenerator());
            final long[] bids = r.engine().getEngine(BehaviourTwoHarness.MARKET).getBidOrders();
            assertEquals("one resting leg", 4, bids.length);
            assertEquals("the replacement rests", newLeg, bids[0]);
        }
    }

    @Test
    public void upToKeepsEntriesAboveTheFloor() {
        final long h = 503;
        try (Run r = fresh(1_000)) {
            final byte[] create = create(h, 0, 100, 0, 59_000, 1);
            final long leg = appliedOrderId(r.send(create));
            final byte[] cancel = cancel(h, 1, 100, leg); // idLow 5
            appliedOrderId(r.send(cancel));

            final CommandReleaseDecoder rel = only4(r.send(releaseFrame(3, upTo(h, 0))));
            assertArrayEquals("only (h, 0) is at or below the floor", new int[]{R_APPLIED, 1}, entries(rel).get(0));
            assertEquals("(h, 5) stays inflight", 1, rel.ledgerSize());

            final long suppressed = r.engine().suppressedDuplicateCount();
            assertTrue("an equal copy of (h, 5) is still suppressed (S3 X4)", r.send(cancel).isEmpty());
            assertEquals(suppressed + 1, r.engine().suppressedDuplicateCount());
            assertEquals("a copy of (h, 0) is RELEASED", RELEASED, only3(r.send(create)).result());
        }
    }

    @Test
    public void releaseIsIdempotentAndMonotone() {
        try (Run r = fresh(1_000)) {
            final long h = 701, o = 702, p = 703;
            assertArrayEquals(new int[]{R_APPLIED, 0}, entries(only4(r.send(releaseFrame(1, upTo(h, 8))))).get(0));
            final long seq = r.engine().meIdentity().nextJournalSeq();
            assertTrue("lower floor: NOOP, all-NOOP frame journals nothing", r.send(releaseFrame(2, upTo(h, 4))).isEmpty());
            assertTrue("equal floor: NOOP", r.send(releaseFrame(3, upTo(h, 8))).isEmpty());
            assertTrue("0-entry frame", r.send(releaseFrame(4)).isEmpty());
            assertEquals("all-NOOP frames consume no journalSeq", seq, r.engine().meIdentity().nextJournalSeq());

            assertArrayEquals(new int[]{R_APPLIED, 0}, entries(only4(r.send(releaseFrame(5, order(o))))).get(0));
            final long seq2 = r.engine().meIdentity().nextJournalSeq();
            assertTrue("ORDER twice: the second is NOOP", r.send(releaseFrame(6, order(o))).isEmpty());
            assertTrue("UP_TO after ORDER: NOOP", r.send(releaseFrame(7, upTo(o, 9))).isEmpty());
            assertEquals(seq2, r.engine().meIdentity().nextJournalSeq());

            final List<byte[]> mixedFrames = r.send(releaseFrame(8, upTo(h, 4), upTo(p, 0), order(o)));
            final CommandReleaseDecoder mixed = only4(mixedFrames);
            assertEquals("one journalSeq for the mixed frame", seq2, BehaviourTwoHarness.journalSeqOf(mixedFrames.get(0)));
            assertEquals(seq2 + 1, r.engine().meIdentity().nextJournalSeq());
            final List<int[]> e = entries(mixed);
            assertEquals("a mixed frame lists its NOOP entries too", 3, e.size());
            assertArrayEquals(new int[]{R_NOOP, 0}, e.get(0));
            assertArrayEquals(new int[]{R_APPLIED, 0}, e.get(1));
            assertArrayEquals(new int[]{R_NOOP, 0}, e.get(2));
            assertEquals(8L, mixed.releaseSeq());
            assertEquals("five all-NOOP frames counted", 5, S4Probe.releaseNoopFrames(r.engine()));
            assertEquals(2, S4Probe.floorCount(r.engine()));
            assertArrayEquals(new long[]{o, o}, S4Probe.ranges(r.engine()));
        }
    }

    @Test
    public void orderReleaseWithRestingLegIsRefused() {
        try (Run r = fresh(1_000)) {
            final long h = 504;
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            final CommandReleaseDecoder live = only4(r.send(releaseFrame(1, order(h))));
            assertArrayEquals("a resting leg names h: LEG_LIVE, nothing removed", new int[]{R_LEG_LIVE, 0}, entries(live).get(0));
            assertEquals("no tombstone", 0, live.tombstoneRanges());
            assertEquals(1, live.ledgerSize());
            assertEquals("journaled again on a repeated copy", 1, entries(only4(r.send(releaseFrame(2, order(h))))).size());
            assertEquals(2, S4Probe.releaseLegLive(r.engine()));

            // Two slices under h2, one still resting.
            final long h2 = 505;
            final long a = appliedOrderId(r.send(create(h2, 0, 100, 0, 59_000, 1)));
            final long b = appliedOrderId(r.send(create(h2, 1, 100, 0, 59_001, 1)));
            appliedOrderId(r.send(cancel(h2, 2, 100, a)));
            assertEquals(1, S4Probe.legCount(r.engine(), h2));
            assertArrayEquals(new int[]{R_LEG_LIVE, 0}, entries(only4(r.send(releaseFrame(3, order(h2))))).get(0));
            appliedOrderId(r.send(cancel(h2, 3, 100, b)));
            assertEquals(0, S4Probe.legCount(r.engine(), h2));
            assertArrayEquals(new int[]{R_APPLIED, 4}, entries(only4(r.send(releaseFrame(4, order(h2))))).get(0));

            // After the cancel ORDER(h) applies.
            appliedOrderId(r.send(cancel(h, 1, 100, leg)));
            final CommandReleaseDecoder done = only4(r.send(releaseFrame(5, order(h))));
            assertArrayEquals(new int[]{R_APPLIED, 2}, entries(done).get(0));
            assertEquals(0, done.ledgerSize());
            assertEquals("504 and 505 merge into one range", 1, done.tombstoneRanges());
        }
    }

    @Test
    public void orderReleaseAfterReplace() {
        try (Run r = fresh(1_000)) {
            final long h = 506;
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            final long replacement = appliedOrderId(r.send(amend(h, 1, 100, leg, 0, 58_000, 1, LIMIT)));
            assertEquals("the replacement is keyed under h", 1, S4Probe.legCount(r.engine(), h));
            assertArrayEquals(new int[]{R_LEG_LIVE, 0}, entries(only4(r.send(releaseFrame(1, order(h))))).get(0));
            appliedOrderId(r.send(cancel(h, 2, 100, replacement)));
            assertArrayEquals(new int[]{R_APPLIED, 3}, entries(only4(r.send(releaseFrame(2, order(h))))).get(0));
        }
        // LIMIT_MAKER amend whose replacement can not rest (direct book, level of 2 already full).
        try (Run r = freshWith(engineConfigWith(1, 2, G, 12, 1_000, true, 0, 100, 2, 1))) {
            appliedOrderId(r.send(create(601, 0, 300, 0, 58_000, 1)));
            appliedOrderId(r.send(create(602, 0, 300, 0, 58_000, 1)));
            final long h = 603;
            final long leg = appliedOrderId(r.send(createOfType(h, 0, 100, 0, 57_000, 1, LIMIT_MAKER)));
            final CommandOutcomeDecoder a = only3(r.send(amend(h, 1, 100, leg, 0, 58_000, 1, LIMIT_MAKER)));
            assertEquals("the replacement could not rest: REJECTED", DurableCommandOutcome.REJECTED, a.result());
            assertEquals("the old leg is gone", 1, a.oldCancelled());
            assertEquals("no leg names h", 0, S4Probe.legCount(r.engine(), h));
            assertArrayEquals(new int[]{R_APPLIED, 2}, entries(only4(r.send(releaseFrame(1, order(h))))).get(0));
        }
    }

    @Test
    public void recordedRejectionsAreReleasable() {
        try (Run r = fresh(1_000)) {
            assertEquals(DurableCommandOutcome.UNKNOWN_MARKET, only3(r.send(createOn(2, 511, 0, 100, 0, 59_000, 1))).result());
            assertEquals(DurableCommandOutcome.UNKNOWN_LEG, only3(r.send(cancel(512, 0, 100, 777))).result());
            final long leg = appliedOrderId(r.send(create(513, 0, 100, 0, 59_000, 1)));
            final CommandOutcomeDecoder wrong = only3(r.send(cancel(513, 1, 999, leg)));
            assertEquals(DurableCommandOutcome.WRONG_OWNER, wrong.result());
            assertEquals("B5 and B6 are recorded", 4, wrong.ledgerSize());

            assertEquals(1, entries(only4(r.send(releaseFrame(1, upTo(511, 0))))).get(0)[1]);
            assertEquals(1, entries(only4(r.send(releaseFrame(2, upTo(512, 1))))).get(0)[1]);
            final CommandReleaseDecoder last = only4(r.send(releaseFrame(3, upTo(513, 5))));
            assertEquals(2, entries(last).get(0)[1]);
            assertEquals(0, last.ledgerSize());
        }
    }

    @Test
    public void createIdReleasedWhileLegRestsStillAllowsOwnerCancel() {
        final long h = 514;
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(r.send(create(h, 0, 100, 0, 59_000, 1)));
            final CommandReleaseDecoder rel = only4(r.send(releaseFrame(1, upTo(h, 0))));
            assertArrayEquals(new int[]{R_APPLIED, 1}, entries(rel).get(0));
            assertEquals("the CREATE id is gone while its leg rests", 0, rel.ledgerSize());

            final List<byte[]> f = r.send(cancel(h, 1, 100, leg));
            assertEquals("ownership is read from the book (F3)", DurableCommandOutcome.APPLIED, only3(f).result());
            final List<byte[]> terminals = of(f, 2);
            assertEquals(1, terminals.size());
            final var t = new com.match.infrastructure.mejournal.generated.TerminalDecoder().wrapAndApplyHeader(
                    new UnsafeBuffer(terminals.get(0)), 0, new com.match.infrastructure.mejournal.generated.MessageHeaderDecoder());
            assertEquals(com.match.infrastructure.mejournal.generated.TerminalStatus.CANCELLED, t.status());
            assertEquals(leg, t.orderId());
            assertEquals(h, t.omsOrderId());
        }
    }

    @Test
    public void ownershipSplitMatchesBehaviourOne() {
        try (Run r = freshWith(engineConfigWith(1, 2, G, 12, 1_000, false, 4096, 100, 0, 1, 2))) {
            final long a = appliedOrderId(r.send(create(801, 0, 100, 0, 59_000, 1)));
            appliedOrderId(r.send(create(802, 0, 200, 0, 59_001, 1)));
            assertEquals("wrong user", DurableCommandOutcome.WRONG_OWNER, only3(r.send(cancel(801, 1, 200, a))).result());
            assertEquals("a leg of another order", DurableCommandOutcome.WRONG_OWNER,
                    only3(r.send(cancel(803, 0, 100, a))).result());
            assertEquals("other market", DurableCommandOutcome.WRONG_OWNER,
                    only3(r.send(cancelOn(2, 801, 2, 100, a))).result());
            assertEquals("UPDATE on the wrong side", DurableCommandOutcome.WRONG_OWNER,
                    only3(r.send(amend(801, 3, 100, a, 1, 60_000, 1, LIMIT))).result());

            final long c = appliedOrderId(r.send(create(804, 0, 300, 0, 59_500, 1)));
            appliedOrderId(r.send(create(805, 0, 400, 1, 59_500, 1))); // fills c (the best bid)
            assertEquals("filled leg", DurableCommandOutcome.UNKNOWN_LEG, only3(r.send(cancel(804, 1, 300, c))).result());
            assertEquals("unknown leg", DurableCommandOutcome.UNKNOWN_LEG, only3(r.send(cancel(806, 0, 300, 99_999))).result());
            assertEquals("control: the owner passes", DurableCommandOutcome.APPLIED,
                    only3(r.send(cancel(801, 4, 100, a))).result());
        }
    }

    // ==================== B0 / B1 and the base rules ====================

    @Test
    public void behaviourTwoIdentityRowsAreJournaledInvalid() {
        final long h = 901;
        final byte[][] frames = {
                withHeaderVersion(create(h, 0, 100, 0, 59_000, 1), 11),                            // B0
                durableRaw(0, 4, CREATE, 100, 9, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),         // idHigh 0
                durableRaw(h, -4, CREATE, 100, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),        // idLow < 0
                durableRaw(h, 1, CREATE, 100, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),         // kind bits
                durableRaw(h, 0, CREATE, 100, h + 1, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),     // omsOrderId != idHigh
        };
        try (Run r = fresh(1_000)) {
            for (int i = 0; i < frames.length; i++) {
                for (int copy = 0; copy < 2; copy++) {
                    final CommandOutcomeDecoder o = only3(r.send(frames[i]));
                    assertEquals("case " + i + " copy " + copy + ": INVALID", INVALID, o.result());
                    assertEquals("reason 0 for every cause", 0, o.reason());
                    assertEquals(0L, o.orderId());
                    assertEquals(-1, o.status());
                    assertEquals("not recorded", 0, o.ledgerSize());
                    assertEquals(1_000, o.ledgerCapacity());
                    assertTrue("reader-valid echo", o.userId() > 0 && o.omsOrderId() > 0 && o.marketId() > 0
                            && o.commandKind() <= 2 && o.timeInForce() <= 2);
                }
            }
            assertEquals(0, S4Probe.ledgerSize(r.engine()));
            assertEquals(0, resting(r.engine()));
            assertEquals("B0 twice", 2, S4Probe.invalidWire(r.engine()));
            assertEquals("sign: idHigh 0 and idLow < 0, twice each", 4, S4Probe.invalidSign(r.engine()));
            assertEquals(2, S4Probe.invalidKindBits(r.engine()));
            assertEquals(2, S4Probe.invalidOwnerId(r.engine()));
        }
    }

    @Test
    public void baseRuleViolationsStayDecodeDrops() {
        final long h = 902;
        final byte[][] frames = {
                durableRaw(h, 0, CREATE, 0, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),       // userId 0
                durableRaw(h, 3, 3, 100, h, 1, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0),          // kind 3
                durableRaw(h, 0, CREATE, 100, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 3, 0),     // tif 3
                durableRaw(h, 0, CREATE, 100, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 5),     // F1
                withHeaderVersion(durableRaw(h, 0, CREATE, 100, h, 0, 0, fp(59_000), fp(1), 0, LIMIT, 3, 0), 13),
        };
        try (Run r = fresh(1_000)) {
            for (int i = 0; i < frames.length; i++) {
                assertTrue("case " + i + ": nothing journaled", r.send(frames[i]).isEmpty());
                assertEquals("case " + i + ": a counted decode drop", i + 1, r.r.demuxer.decodeRejectCount());
            }
            assertEquals(0, r.r.demuxer.aboveCeilingInvalidCount());
            assertEquals(0, r.r.demuxer.applyErrorCount());
        }
    }

    // ==================== release semantics ====================

    @Test
    public void releaseAheadOfCommandRefusesTheCommand() {
        try (Run r = fresh(1_000)) {
            final long h = 903, h2 = 904;
            assertArrayEquals(new int[]{R_APPLIED, 0}, entries(only4(r.send(releaseFrame(1, order(h))))).get(0));
            assertArrayEquals(new int[]{R_APPLIED, 0}, entries(only4(r.send(releaseFrame(2, upTo(h2, 4))))).get(0));
            assertEquals(RELEASED, only3(r.send(create(h, 0, 100, 0, 59_000, 1))).result());
            assertEquals(RELEASED, only3(r.send(create(h2, 0, 100, 0, 59_000, 1))).result());
            assertEquals("no leg", 0, resting(r.engine()));
        }
    }

    @Test
    public void rangesAtTheTopOfTheIdSpace() {
        try (Run r = fresh(1_000)) {
            final long far = 1_000_000_000_000_000L, max = Long.MAX_VALUE;
            assertEquals(1, only4(r.send(releaseFrame(1, order(10)))).tombstoneRanges());
            assertEquals(2, only4(r.send(releaseFrame(2, order(12)))).tombstoneRanges());
            assertEquals("far above the top range: the tail path", 3, only4(r.send(releaseFrame(3, order(far)))).tombstoneRanges());
            assertEquals("h - 1 and h + 1 tombstoned: both sides merge", 2,
                    only4(r.send(releaseFrame(4, order(11)))).tombstoneRanges());
            assertEquals("ORDER(Long.MAX_VALUE) without overflow", 3, only4(r.send(releaseFrame(5, order(max)))).tombstoneRanges());
            assertEquals("MAX - 1 merges with the MAX range", 3, only4(r.send(releaseFrame(6, order(max - 1)))).tombstoneRanges());
            assertArrayEquals(new long[]{10, 12, far, far, max - 1, max}, S4Probe.ranges(r.engine()));
            assertEquals(RELEASED, only3(r.send(create(11, 0, 100, 0, 59_000, 1))).result());
            assertEquals(RELEASED, only3(r.send(create(max, 0, 100, 0, 59_000, 1))).result());
            assertEquals("a gap id is not released", DurableCommandOutcome.APPLIED,
                    only3(r.send(create(13, 0, 100, 0, 59_000, 1))).result());
        }
    }

    // ==================== capacity ====================

    @Test
    public void lifetimeMillionCommandsWithReleaseStaysBelowCapacity() {
        final int orders = Integer.getInteger("me.ledger.lifetime.orders", 1_000_000);
        try (Run r = fresh(1_000, Shape.SINKLESS)) {
            final List<long[]> pending = new ArrayList<>();
            long maxInflight = 0;
            for (long h = 1; h <= orders; h++) {
                final long leg = r.engine().getOrderIdGenerator();
                r.send(create(h, 0, 100, 0, 59_000, 1));
                maxInflight = Math.max(maxInflight, S4Probe.ledgerSize(r.engine()));
                r.send(cancel(h, 1, 100, leg));
                maxInflight = Math.max(maxInflight, S4Probe.ledgerSize(r.engine()));
                if (S4Probe.ledgerSize(r.engine()) > 64) {
                    fail("inflight <= 64 at order " + h + ", was " + S4Probe.ledgerSize(r.engine()));
                }
                pending.add(order(h));
                if (pending.size() == 16) {
                    r.send(releaseFrame(h, pending.toArray(new long[0][])));
                    pending.clear();
                }
            }
            assertEquals("no CAPACITY", 0, S4Probe.capacity(r.engine()));
            assertEquals(0, resting(r.engine()));
            assertArrayEquals("one tombstone range [1, n]", new long[]{1, orders}, S4Probe.ranges(r.engine()));
            assertEquals(0, S4Probe.floorCount(r.engine()));
            assertTrue("non-vacuous", maxInflight >= 32);
        }
        // Control without releases: CAPACITY exactly at command 1001.
        try (Run r = fresh(1_000)) {
            int command = 0;
            for (long h = 1; command < 1_002; h++) {
                final long leg = r.engine().getOrderIdGenerator();
                for (byte[] frame : new byte[][]{create(h, 0, 100, 0, 59_000, 1), cancel(h, 1, 100, leg)}) {
                    command++;
                    final int result = only3(r.send(frame)).result();
                    if (command <= 1_000) {
                        assertNotEquals("command " + command, DurableCommandOutcome.CAPACITY, result);
                    } else {
                        assertEquals("command " + command, DurableCommandOutcome.CAPACITY, result);
                    }
                }
            }
        }
    }

    @Test
    public void capacityIsRecordedAndReported() {
        try (Run r = fresh(3)) {
            for (long h = 1; h <= 3; h++) {
                final CommandOutcomeDecoder o = only3(r.send(create(h, 0, 100, 0, 59_000 + h, 1)));
                assertEquals(h, o.ledgerSize());
                assertEquals(3, o.ledgerCapacity());
            }
            final CommandOutcomeDecoder full = only3(r.send(create(4, 0, 100, 0, 59_010, 1)));
            assertEquals(DurableCommandOutcome.CAPACITY, full.result());
            assertEquals(3, full.ledgerSize());
            assertEquals(3, full.ledgerCapacity());

            final CommandReleaseDecoder ahead = only4(r.send(releaseFrame(1, order(50))));
            assertEquals("record 4 reports the size", 3, ahead.ledgerSize());
            assertEquals(3, ahead.ledgerCapacity());
            assertEquals("RELEASED consumes nothing", 3, only3(r.send(create(50, 0, 100, 0, 59_000, 1))).ledgerSize());
            assertEquals("INVALID consumes nothing", 3,
                    only3(r.send(durableRaw(5, 1, CREATE, 100, 5, 0, 0, fp(59_000), fp(1), 0, LIMIT, 0, 0))).ledgerSize());

            final CommandReleaseDecoder room = only4(r.send(releaseFrame(2, upTo(1, 0))));
            assertEquals(2, room.ledgerSize());
            final CommandOutcomeDecoder again = only3(r.send(create(4, 0, 100, 0, 59_010, 1)));
            assertEquals("room after UP_TO", DurableCommandOutcome.APPLIED, again.result());
            assertEquals(3, again.ledgerSize());
            assertEquals(1, S4Probe.capacity(r.engine()));
        }
        // Behaviour 1 still reports the ledger v1 cap.
        try (Replica one = new Replica()) {
            one.apply(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            final List<byte[]> f = one.apply(new Entry(create(1, 0, 100, 0, 59_000, 1), 192L, 1_001L));
            assertEquals(100_000, only3(f).ledgerCapacity());
        }
    }

    /**
     * I7 with an independent reference model of the ledger (inflight ids, floors, tombstones,
     * resting legs per idHigh): after every log entry the last journaled {@code ledgerSize} equals
     * the model's size, and every record's result equals the model's decision.
     */
    @Test
    public void everyLedgerSizeChangeIsJournaled() {
        for (long seed = 1; seed <= 3; seed++) {
            ledgerModelRun(seed);
        }
    }

    private static final class Model {
        final int capacity;
        final Map<Long, Map<Long, byte[]>> inflight = new HashMap<>(); // h -> idLow -> frame
        int size;
        final Map<Long, Long> floors = new HashMap<>();
        final Set<Long> tombstones = new HashSet<>();
        final Map<Long, Set<Long>> legs = new HashMap<>(); // h -> resting leg orderIds

        Model(int capacity) {
            this.capacity = capacity;
        }

        boolean covered(long h, long l) {
            return tombstones.contains(h) || (floors.containsKey(h) && l <= floors.get(h));
        }

        /** -1 suppressed, else the result (record 3). Mutates the inflight set for recorded ones. */
        int decide(long h, long l, byte[] frame) {
            if (covered(h, l)) {
                return RELEASED;
            }
            final Map<Long, byte[]> ids = inflight.get(h);
            if (ids != null && ids.containsKey(l)) {
                return java.util.Arrays.equals(ids.get(l), frame) ? -1 : DurableCommandOutcome.CONFLICT;
            }
            if (size >= capacity) {
                return DurableCommandOutcome.CAPACITY;
            }
            inflight.computeIfAbsent(h, k -> new TreeMap<>()).put(l, frame);
            size++;
            return Integer.MIN_VALUE; // recorded: the result comes from the apply
        }

        int[] upTo(long h, long f) {
            if (tombstones.contains(h) || (floors.containsKey(h) && f <= floors.get(h))) {
                return new int[]{R_NOOP, 0};
            }
            int removed = 0;
            final Map<Long, byte[]> ids = inflight.get(h);
            if (ids != null) {
                final List<Long> drop = new ArrayList<>();
                for (Long l : ids.keySet()) {
                    if (l <= f) {
                        drop.add(l);
                    }
                }
                for (Long l : drop) {
                    ids.remove(l);
                }
                removed = drop.size();
            }
            size -= removed;
            floors.put(h, f);
            return new int[]{R_APPLIED, removed};
        }

        int[] order(long h) {
            if (tombstones.contains(h)) {
                return new int[]{R_NOOP, 0};
            }
            if (!legs.getOrDefault(h, Set.of()).isEmpty()) {
                return new int[]{R_LEG_LIVE, 0};
            }
            final Map<Long, byte[]> ids = inflight.remove(h);
            final int removed = ids == null ? 0 : ids.size();
            size -= removed;
            floors.remove(h);
            tombstones.add(h);
            return new int[]{R_APPLIED, removed};
        }
    }

    private static void ledgerModelRun(long seed) {
        final Random rnd = new Random(seed);
        final Model m = new Model(40);
        final List<byte[]> sent = new ArrayList<>();
        final Map<Long, Long> legOwner = new HashMap<>(); // leg orderId -> h
        int lastJournaledSize = 0;
        int releases = 0, changes = 0;
        try (Run r = fresh(40)) {
            for (int i = 0; i < 3_000; i++) {
                final int pick = rnd.nextInt(10);
                final List<byte[]> f;
                final int before = m.size;
                if (pick < 1 && !sent.isEmpty()) {
                    final byte[] frame = sent.get(rnd.nextInt(sent.size()));
                    f = replay(r, m, frame, legOwner);
                } else if (pick < 5) {
                    // A sliding window of idHighs, so new orders keep arriving as old ones are tombstoned.
                    final long h = 1 + i / 20 + rnd.nextInt(30);
                    final byte[] frame = create(h, rnd.nextInt(6), 100, 0, 59_000 + rnd.nextInt(2), 1);
                    sent.add(frame);
                    f = replay(r, m, frame, legOwner);
                } else if (pick < 8 && !legOwner.isEmpty()) {
                    final List<Long> legs = new ArrayList<>(legOwner.keySet());
                    final long leg = legs.get(rnd.nextInt(legs.size()));
                    final byte[] frame = cancel(legOwner.get(leg), 6 + rnd.nextInt(6), 100, leg);
                    sent.add(frame);
                    f = replay(r, m, frame, legOwner);
                } else {
                    final int n = 1 + rnd.nextInt(4);
                    final long[][] entries = new long[n][];
                    final int[][] expected = new int[n][];
                    for (int k = 0; k < n; k++) {
                        final long h = 1 + i / 20 + rnd.nextInt(30);
                        if (rnd.nextBoolean()) {
                            final long fl = rnd.nextInt(24);
                            entries[k] = upTo(h, fl);
                            expected[k] = m.upTo(h, fl);
                        } else {
                            entries[k] = order(h);
                            expected[k] = m.order(h);
                        }
                    }
                    f = r.send(releaseFrame(i, entries));
                    boolean journal = false;
                    for (int[] e : expected) {
                        journal |= e[0] != R_NOOP;
                    }
                    if (journal) {
                        releases++;
                        final List<int[]> got = entries(only4(f));
                        for (int k = 0; k < n; k++) {
                            assertArrayEquals("seed " + seed + " entry " + i + " release " + k, expected[k], got.get(k));
                        }
                    } else {
                        assertTrue("seed " + seed + " entry " + i + ": all-NOOP frame journals nothing", f.isEmpty());
                    }
                }
                for (byte[] fr : f) {
                    if (templateId(fr) == 3) {
                        lastJournaledSize = record3(fr).ledgerSize();
                    } else if (templateId(fr) == 4) {
                        lastJournaledSize = record4(fr).ledgerSize();
                    }
                }
                if (m.size != before) {
                    changes++;
                    assertFalse("seed " + seed + " entry " + i + ": a size change with no record", of(f, 3).isEmpty() && of(f, 4).isEmpty());
                }
                assertEquals("seed " + seed + " entry " + i + ": the last journaled ledgerSize is the model's",
                        m.size, lastJournaledSize);
            }
            System.out.println("[S4] ledger model seed " + seed + ": " + releases + " journaled release frames, "
                    + changes + " size changes, final size " + m.size);
            assertTrue("non-vacuous: releases", releases > 50);
            assertTrue("non-vacuous: size changes", changes > 150);
            assertEquals(m.size, S4Probe.ledgerSize(r.engine()));
        }
    }

    /** Send one template-9 frame, check the model's decision against the journal, update legs. */
    private static List<byte[]> replay(Run r, Model m, byte[] frame, Map<Long, Long> legOwner) {
        final UnsafeBuffer b = new UnsafeBuffer(frame);
        final var d = new com.match.infrastructure.generated.DurableOrderCommandDecoder().wrapAndApplyHeader(
                b, 0, new com.match.infrastructure.generated.MessageHeaderDecoder());
        final long h = d.commandIdHigh(), l = d.commandIdLow(), oldLeg = d.oldOrderId();
        final int kind = d.commandKind();
        final int decision = m.decide(h, l, frame);
        final List<byte[]> f = r.send(frame);
        if (decision == -1) {
            assertTrue("suppressed duplicate journals nothing", f.isEmpty());
            return f;
        }
        final CommandOutcomeDecoder o = only3(f);
        if (decision != Integer.MIN_VALUE) {
            assertEquals("model decision for (" + h + ", " + l + ")", decision, o.result());
            return f;
        }
        // Recorded: a CREATE rests (bids never cross); a CANCEL by the owner applies while its leg
        // rests and is UNKNOWN_LEG once it is gone (a late retry of a CAPACITY-refused cancel).
        final boolean legRests = kind == CANCEL && m.legs.getOrDefault(h, Set.of()).contains(oldLeg);
        if (kind == CANCEL && !legRests) {
            assertEquals("recorded cancel of a gone leg (" + h + ", " + l + ")", DurableCommandOutcome.UNKNOWN_LEG, o.result());
            return f;
        }
        assertEquals("recorded apply (" + h + ", " + l + ")", DurableCommandOutcome.APPLIED, o.result());
        if (kind == CREATE) {
            legOwner.put(o.orderId(), h);
            m.legs.computeIfAbsent(h, k -> new HashSet<>()).add(o.orderId());
        } else {
            legOwner.remove(oldLeg);
            final Set<Long> legs = m.legs.get(h);
            if (legs != null) {
                legs.remove(oldLeg);
            }
        }
        return f;
    }

    @Test
    public void capacityIsABoundNotAnAllocation() {
        final com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        try (Run r = freshWith(engineConfigWith(1, 2, G, 12, Integer.MAX_VALUE, false, 1_024, 100, 0, 1))) {
            assertEquals(1, r.r.h.decisions.size());
            final UnsafeBuffer[] frames = new UnsafeBuffer[1_000];
            for (int i = 0; i < frames.length; i++) {
                frames[i] = new UnsafeBuffer(create(i + 1, 0, 100, 0, 51_000 + i, 1));
            }
            final long before = mx.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < frames.length; i++) {
                r.engine().setCurrentLogPosition(192L + 128L * i);
                r.r.demuxer.dispatch(frames[i], 0, frames[i].capacity(), 1_001L + i);
            }
            final long allocated = mx.getCurrentThreadAllocatedBytes() - before;
            r.r.h.drain();
            assertEquals(1_000, S4Probe.ledgerSize(r.engine()));
            assertTrue("C = 2^31 - 1 is a bound, not an allocation: " + allocated + " B for 1000 commands",
                    allocated < 16L * 1024 * 1024);
        }
    }

    @Test
    public void releaseFrameValidationIsAllOrNothing() {
        try (Run r = fresh(1_000)) {
            final long[][] fortyNine = new long[49][];
            for (int i = 0; i < 49; i++) {
                fortyNine[i] = order(100 + i);
            }
            assertTrue(r.send(releaseFrame(1, fortyNine)).isEmpty());
            assertEquals("49 entries: frame-bounds reject", 1, r.r.demuxer.decodeRejectCount());
            assertEquals("nothing applied", DurableCommandOutcome.APPLIED,
                    only3(r.send(create(100, 0, 100, 0, 59_000, 1))).result());

            final long[][][] bad = {
                    {order(201), order(202), {0, 3, 1}},           // idHigh 0
                    {upTo(211, 0), upTo(212, 0), {213, -1, 0}},    // idLowFloor -1
                    {order(221), order(222), {223, 0, 2}},         // scope 2
            };
            for (int c = 0; c < bad.length; c++) {
                assertTrue(r.send(releaseFrame(2, bad[c])).isEmpty());
                assertEquals("case " + c + ": decode reject", 2 + c, r.r.demuxer.decodeRejectCount());
                for (int k = 0; k < 2; k++) {
                    assertEquals("case " + c + ": entry " + (k + 1) + " was not applied", DurableCommandOutcome.APPLIED,
                            only3(r.send(create(bad[c][k][0], 0, 100, 0, 59_000, 1))).result());
                }
            }
            assertTrue(r.send(releaseFrame(-1, order(231))).isEmpty());
            assertEquals("releaseSeq < 0: decode reject", 5, r.r.demuxer.decodeRejectCount());
            assertEquals(DurableCommandOutcome.APPLIED, only3(r.send(create(231, 0, 100, 0, 59_000, 1))).result());
            assertEquals(0, r.r.demuxer.applyErrorCount());
        }
    }
}
