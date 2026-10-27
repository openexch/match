// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.orderbook.MatchingEngine;
import com.match.application.orderbook.S5Probe;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.S5Frames.*;
import static org.junit.Assert.*;

/**
 * RC2 S5 contract section 5 (D6, G27, W2/W3): the behaviour-2 amend correction. The ME subtracts
 * the in-window fills {@code expectedOldRemaining - actual} from the replacement, so a minted amend
 * can be replayed byte for byte and never overfills. Array and direct.
 */
@RunWith(Parameterized.class)
public class AmendCorrectionTest {

    @Parameters(name = "{0}")
    public static Collection<Object[]> impls() {
        return Arrays.asList(new Object[]{"array"}, new Object[]{"direct"});
    }

    private final boolean direct;

    public AmendCorrectionTest(String impl) {
        this.direct = "direct".equals(impl);
    }

    private Run fresh() {
        return freshWith(S5Frames.config(direct, 100, 16));
    }

    private static long fp(double v) {
        return FixedPoint.fromDouble(v);
    }

    /** Leg L: sell 10 @ 100k-band price 60_000 for h, user 100. */
    private static long leg(Run r, long h, double qty) {
        return appliedOrderId(r.send(limit(h, 0, 100, ASK, 60_000, qty, GTC)));
    }

    private static void fill(Run r, long h, double qty) {
        final CommandOutcomeDecoder o = outcome(r.send(limit(h, 0, 200, BID, 60_000, qty, GTC)));
        assertEquals("the taker filled", FILLED, o.status());
    }

    @Test
    public void amendAfterInWindowFillPlacesReducedLeg() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            fill(r, 2, 3);
            // Mint saw 0 fills: quantity 8 = newQty - 0, expected 10 = Q - 0.
            final List<byte[]> f = r.send(amendRaw(1, 1, 100, l, ASK, 60_000, 8, LIMIT, GTC, fp(10)));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            assertEquals(NEW, o.status());
            assertEquals(1, o.oldCancelled());
            assertEquals("the new leg rests newQty - F = 5", fp(5), remainingOf(book(r), o.orderId()));
            assertEquals("filled + resting = 8", fp(8), fp(3) + remainingOf(book(r), o.orderId()));
            assertEquals("old-leg CANCELLED is not journaled", List.of(3), templates(f));
            assertEquals(1L, S5Probe.amendReduced(r.engine()));
        }
    }

    @Test
    public void amendWhoseOldLegFilledMeanwhileIsRejectedOldCancelled() {
        for (double filled : new double[]{7, 4}) {
            try (Run r = fresh()) {
                final long l = leg(r, 1, 10);
                fill(r, 2, filled);
                final long gen = r.engine().getOrderIdGenerator();
                final long seq = r.engine().meIdentity().nextJournalSeq();
                final List<byte[]> f = r.send(amendRaw(1, 1, 100, l, ASK, 60_000, 4, LIMIT, GTC, fp(10)));
                final CommandOutcomeDecoder o = outcome(f);
                assertEquals("filled " + filled + ": REJECTED", DurableCommandOutcome.REJECTED, o.result());
                assertEquals(REJECTED, o.status());
                assertEquals("orderId = the old leg", l, o.orderId());
                assertEquals(AMEND_NO_REMAINDER, o.reason());
                assertEquals(1, o.oldCancelled());
                assertEquals("no record 2: the outcome only", List.of(3), templates(f));
                assertEquals("one journalSeq consumed", seq + 1, r.engine().meIdentity().nextJournalSeq());
                assertEquals("L gone", -1L, remainingOf(book(r), l));
                assertEquals("no leg names h", 0, S4Probe.legCount(r.engine(), 1));
                assertEquals("no new orderId", gen, r.engine().getOrderIdGenerator());
                assertEquals(0, book(r).getAskOrders().length);
                assertEquals(1L, S5Probe.amendNoRemainder(r.engine()));
            }
        }
    }

    @Test
    public void amendWithoutInWindowFillPlacesWireQuantity() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, LIMIT, GTC, fp(10))));
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            assertEquals(fp(8), remainingOf(book(r), o.orderId()));
            if (S5Probe.S5) {
                assertEquals("not counted as reduced", 0L, S5Probe.amendReduced(r.engine()));
            }
        }
    }

    @Test
    public void reducedReplacementThatCrossesFillsOnlyWhatIsLeft() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            fill(r, 2, 3);
            // Opposite crossing depth 8 (bids at 59_990).
            appliedOrderId(r.send(limit(3, 0, 300, BID, 59_990, 8, GTC)));
            final List<byte[]> f = r.send(amendRaw(1, 1, 100, l, ASK, 59_990, 8, LIMIT, GTC, fp(10)));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals("matched as a taker for at most 5", fp(5), tradedQuantity(f));
            assertEquals(FILLED, o.status());
            assertEquals("3 of the bid remain", fp(3), book(r).getBidOrders()[3]);
        }
    }

    @Test
    public void limitMakerAmendRestsReducedLeg() {
        try (Run r = fresh()) {
            final long l = appliedOrderId(r.send(limitMaker(1, 0, 100, ASK, 60_000, 10, GTC)));
            fill(r, 2, 3);
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_002, 8, LIMIT_MAKER, GTC, fp(10))));
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            assertEquals(NEW, o.status());
            assertEquals(fp(5), remainingOf(book(r), o.orderId()));
        }
    }

    @Test
    public void remainingMismatchKeepsOldLeg() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            final long[] asks = book(r).getAskOrders();
            final List<byte[]> f = r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, LIMIT, GTC, fp(4)));
            final CommandOutcomeDecoder o = outcome(f);
            assertEquals(DurableCommandOutcome.REJECTED, o.result());
            assertEquals(REJECTED, o.status());
            assertEquals(l, o.orderId());
            assertEquals(AMEND_REMAINING_MISMATCH, o.reason());
            assertEquals("oldCancelled 0", 0, o.oldCancelled());
            assertEquals(List.of(3), templates(f));
            assertArrayEquals("L rests with 10", asks, book(r).getAskOrders());
            assertEquals(1L, S5Probe.amendRemainingMismatch(r.engine()));
            // L is still tradable.
            fill(r, 2, 1);
        }
    }

    @Test
    public void zeroReferenceIsInvalid() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            final long[] asks = book(r).getAskOrders();
            final int ledger = S4Probe.ledgerSize(r.engine());
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, LIMIT, GTC, 0)));
            assertEquals(DurableCommandOutcome.INVALID, o.result());
            assertEquals(0, o.reason());
            assertEquals(ledger, o.ledgerSize());
            assertEquals(1L, S5Probe.invalidAmendReference(r.engine()));
            assertArrayEquals("L intact", asks, book(r).getAskOrders());
        }
    }

    @Test
    public void amendWithMarketTypeIsInvalid() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            final long[] asks = book(r).getAskOrders();
            final int ledger = S4Probe.ledgerSize(r.engine());
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, MARKET, GTC, fp(10))));
            assertEquals(DurableCommandOutcome.INVALID, o.result());
            assertEquals(0, o.reason());
            assertEquals("not recorded", ledger, o.ledgerSize());
            assertEquals(1L, S5Probe.invalidAmendType(r.engine()));
            assertArrayEquals("L intact", asks, book(r).getAskOrders());
        }
        if (direct) {
            return;
        }
        // Pin: at behaviour 1 the same frame is still applied as a LIMIT replace.
        try (Run r = freshWith(engineConfig(1, 1, 0, 0, 100_000))) {
            final long l = appliedOrderId(r.send(limit(1, 0, 100, ASK, 60_000, 10, GTC)));
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, MARKET, GTC, fp(10))));
            assertEquals(DurableCommandOutcome.APPLIED, o.result());
            assertEquals(fp(8), remainingOf(book(r), o.orderId()));
        }
    }

    @Test
    public void mintedAmendBytesAreSafeToReplay() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            // (a) the original frame is withheld (lost), a fill lands, the minted bytes are applied.
            final byte[] minted = amendRaw(1, 1, 100, l, ASK, 60_001, 8, LIMIT, GTC, fp(10));
            fill(r, 2, 3);
            final CommandOutcomeDecoder a = outcome(r.send(minted));
            assertEquals(DurableCommandOutcome.APPLIED, a.result());
            assertEquals("reduced leg 5", fp(5), remainingOf(book(r), a.orderId()));
            // (b) the same bytes again: suppressed, one replace only.
            final long gen = r.engine().getOrderIdGenerator();
            final List<byte[]> dup = r.send(minted);
            assertTrue("suppressed: nothing journaled", dup.isEmpty());
            assertEquals(gen, r.engine().getOrderIdGenerator());
            assertEquals(4, book(r).getAskOrders().length);
            // (c) after UP_TO of that id: RELEASED.
            r.send(releaseFrame(1, upTo(1, (1 << 2) | UPDATE)));
            assertEquals(DurableCommandOutcome.RELEASED, outcome(r.send(minted)).result());
            assertEquals(4, book(r).getAskOrders().length);
        }
    }

    @Test
    public void oldLegFullyFilledBeforeAmendIsUnknownLeg() {
        try (Run r = fresh()) {
            final long l = leg(r, 1, 10);
            fill(r, 2, 10);
            final CommandOutcomeDecoder o = outcome(r.send(amendRaw(1, 1, 100, l, ASK, 60_001, 8, LIMIT, GTC, fp(10))));
            assertEquals(DurableCommandOutcome.UNKNOWN_LEG, o.result());
        }
    }

    // ==================== fuzz ====================

    /** One core order of the model. */
    private static final class CoreOrder {
        final long h;
        final long user;
        long target;          // the latest target the ME applied (fixed-point raw)
        long leg;             // the ME leg the core knows (0 = none)
        long attempt;
        final List<Long> seenFills = new ArrayList<>(); // F after each command (the core lags)

        CoreOrder(long h, long user, long target) {
            this.h = h;
            this.user = user;
            this.target = target;
        }
    }

    /**
     * Seeded core model: the core mints {@code legQty = newQty - F0} and {@code expected = Q - F0}
     * from a LAGGED view of the fills ({@code F0 <= F}), takers from other users interleave, minted
     * frames are replayed. After every command, per order: resting <= max(0, target - filled), i.e.
     * filled + resting never exceeds the latest target the ME applied. Array and direct journals are
     * byte-equal.
     */
    @Test
    public void amendNeverOverfillsFuzz() {
        if (direct) {
            return; // one run drives both implementations side by side
        }
        int reducedSeen = 0, noRemainderSeen = 0;
        for (long seed = 1; seed <= 40; seed++) {
            final Random rnd = new Random(seed);
            try (Run a = freshWith(S5Frames.config(false, 100, 0));
                 Run d = freshWith(S5Frames.config(true, 100, 32))) {
                final Map<Long, CoreOrder> orders = new HashMap<>();
                final Map<Long, Long> filled = new HashMap<>();
                final List<byte[]> sent = new ArrayList<>();
                long nextH = 1;
                for (int step = 0; step < 150; step++) {
                    byte[] frame;
                    final int pick = rnd.nextInt(10);
                    final List<CoreOrder> live = new ArrayList<>();
                    for (CoreOrder c : orders.values()) {
                        if (c.leg != 0) live.add(c);
                    }
                    live.sort((x, y) -> Long.compare(x.h, y.h));
                    CoreOrder amended = null;
                    long amendTarget = 0;
                    boolean amendInWindow = false;
                    if (pick < 3 || live.isEmpty()) {
                        final long h = nextH++;
                        final double q = 2 + rnd.nextInt(8);
                        final CoreOrder c = new CoreOrder(h, 100 + rnd.nextInt(3), fp(q));
                        orders.put(h, c);
                        frame = limit(h, 0, c.user, ASK, 60_000 + rnd.nextInt(4), q, GTC);
                    } else if (pick < 6) {
                        // A taker from another user: an IOC buy (never rests, keeps the model to asks).
                        frame = limit(1_000_000 + step, 0, 900, BID, 60_000 + rnd.nextInt(4), 1 + rnd.nextInt(3), IOC);
                    } else if (pick < 9) {
                        final CoreOrder c = live.get(rnd.nextInt(live.size()));
                        final int lag = rnd.nextInt(8);
                        final List<Long> seen = c.seenFills;
                        final long f0 = seen.isEmpty() ? 0 : seen.get(Math.max(0, seen.size() - 1 - lag));
                        final long newQty = fp(1 + rnd.nextInt(10));
                        if (newQty - f0 <= 0) {
                            continue; // the core refuses (B7: legQty <= 0)
                        }
                        final long expected = c.target - f0;
                        if (expected <= 0) {
                            continue; // S8 precondition: NOT_OPEN at remainingQty 0
                        }
                        c.attempt++;
                        frame = durableRaw(c.h, (c.attempt << 2) | UPDATE, UPDATE, c.user, c.h, c.leg, ASK,
                                fp(60_000 + rnd.nextInt(4)), newQty - f0, 0L, LIMIT, GTC, expected);
                        amended = c;
                        amendTarget = newQty;
                        amendInWindow = filled.getOrDefault(c.h, 0L) > f0;
                    } else {
                        if (sent.isEmpty()) continue;
                        frame = sent.get(rnd.nextInt(sent.size())); // replay of a minted frame
                    }
                    sent.add(frame);
                    final List<byte[]> fa = a.send(frame);
                    final List<byte[]> fd = d.send(frame);
                    assertFramesEqual("seed " + seed + " step " + step + ": array == direct", fa, fd);
                    for (byte[] t : of(fa, 1)) {
                        final TradeDecoder td = trade(t);
                        filled.merge(td.makerOmsOrderId(), td.quantity(), Long::sum);
                        if (td.takerOmsOrderId() < 1_000_000) {
                            filled.merge(td.takerOmsOrderId(), td.quantity(), Long::sum);
                        }
                    }
                    for (byte[] t3 : of(fa, 3)) {
                        final CommandOutcomeDecoder o = BehaviourTwoHarness.record3(t3);
                        final CoreOrder c = orders.get(o.commandIdHigh());
                        if (c == null) continue;
                        if (o.commandKind() == CREATE && o.result() == DurableCommandOutcome.APPLIED && c.leg == 0
                                && o.status() <= 1) {
                            c.leg = o.orderId();
                        }
                        if (c == amended && o.commandKind() == UPDATE) {
                            if (o.result() == DurableCommandOutcome.APPLIED) {
                                c.target = amendTarget;
                                c.leg = o.status() <= 1 ? o.orderId() : 0;
                                if (amendInWindow) {
                                    reducedSeen++;
                                }
                            } else if (o.result() == DurableCommandOutcome.REJECTED && o.oldCancelled() == 1) {
                                c.target = amendTarget;
                                c.leg = 0;
                                if (o.reason() == AMEND_NO_REMAINDER) noRemainderSeen++;
                            }
                        }
                    }
                    // Model bookkeeping: legs that filled away; the core's fill history.
                    final MatchingEngine me = book(a);
                    for (CoreOrder c : orders.values()) {
                        final long f = filled.getOrDefault(c.h, 0L);
                        c.seenFills.add(f);
                        long resting = 0;
                        for (long[] side : new long[][]{me.getBidOrders(), me.getAskOrders()}) {
                            for (int i = 0; i < side.length; i += 4) {
                                if (r(a).getOmsOrderIdFor(side[i]) == c.h) {
                                    resting += side[i + 3];
                                }
                            }
                        }
                        if (resting == 0) {
                            c.leg = 0;
                        }
                        assertTrue("seed " + seed + " step " + step + " order " + c.h + ": filled " + f + " + resting "
                                        + resting + " exceeds the target " + c.target,
                                resting <= Math.max(0, c.target - f));
                    }
                }
                assertArrayEquals("seed " + seed + ": ledger blocks", S4Probe.ledgerBlock(a.engine()),
                        S4Probe.ledgerBlock(d.engine()));
            }
        }
        System.out.println("[S5] amendNeverOverfillsFuzz: 40 seeds, amends applied with in-window fills "
                + reducedSeen + ", no-remainder amends " + noRemainderSeen);
        assertTrue("non-vacuous: amends applied", reducedSeen > 50);
        assertTrue("non-vacuous: no-remainder amends", noRemainderSeen > 5);
    }

    private static com.match.application.engine.Engine r(Run run) {
        return run.engine();
    }
}
