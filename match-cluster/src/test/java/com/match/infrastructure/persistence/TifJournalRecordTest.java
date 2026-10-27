// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static com.match.infrastructure.persistence.S5Frames.*;
import static org.junit.Assert.*;

/**
 * RC2 S5 contract section 6: the journal records of every behaviour-2 TIF, market and amend
 * outcome, in order (trade, maker FILLED when consumed, taker terminal, record 3), the terminal
 * status and reason, record 3 (result, status, orderId, reason, oldCancelled, ledgerSize) and the
 * dense journalSeq (+1 per record). The fence row is {@link FokDivergenceFenceTest}.
 */
public class TifJournalRecordTest {

    /** Expected record shapes: "T" a trade, "M" a maker FILLED, "S:r" the taker terminal status:reason. */
    private static void assertRow(String row, Run r, byte[] frame, List<String> records, int result, int status,
                                  int reason, int oldCancelled, Long orderId, boolean recorded) {
        final long seq0 = r.engine().meIdentity().nextJournalSeq();
        final int ledger0 = S4Probe.ledgerSize(r.engine());
        final List<byte[]> f = r.send(frame);
        final List<String> actual = new ArrayList<>();
        final CommandOutcomeDecoder o = BehaviourTwoHarness.record3(f.get(f.size() - 1));
        for (int i = 0; i < f.size(); i++) {
            final byte[] b = f.get(i);
            assertEquals(row + ": dense journalSeq at frame " + i, seq0 + i, BehaviourTwoHarness.journalSeqOf(b));
            final int t = BehaviourTwoHarness.templateId(b);
            if (t == 1) {
                actual.add("T");
            } else if (t == 2) {
                final TerminalDecoder td = terminal(b);
                actual.add(td.orderId() == o.orderId() || o.orderId() == 0
                        ? td.statusRaw() + ":" + td.rejectReason() : "M" + (td.statusRaw() == FILLED ? "" : td.statusRaw()));
            } else {
                actual.add("O");
            }
        }
        final List<String> expected = new ArrayList<>(records);
        expected.add("O");
        assertEquals(row + ": records", expected, actual);
        assertEquals(row + ": seq consumed per record", seq0 + f.size(), r.engine().meIdentity().nextJournalSeq());
        assertEquals(row + ": result", result, o.result());
        assertEquals(row + ": status", status, o.status());
        assertEquals(row + ": reason", reason, o.reason());
        assertEquals(row + ": oldCancelled", oldCancelled, o.oldCancelled());
        if (orderId != null) {
            assertEquals(row + ": orderId", (long) orderId, o.orderId());
        }
        assertEquals(row + ": ledgerSize", recorded ? ledger0 + 1 : ledger0, o.ledgerSize());
    }

    private static long rest(Run r, long h, long user, int side, double price, double qty) {
        return appliedOrderId(r.send(limit(h, 0, user, side, price, qty, GTC)));
    }

    @Test
    public void everyRowOfSection6() {
        final int A = DurableCommandOutcome.APPLIED, R = DurableCommandOutcome.REJECTED, I = DurableCommandOutcome.INVALID;
        try (Run r = freshWith(S5Frames.config(false, 2, 0))) {
            long h = 1;
            // IOC, full fill (a consumed maker gets its FILLED after its trade).
            rest(r, h++, 100, ASK, 60_000, 1);
            long next = r.engine().getOrderIdGenerator();
            assertRow("IOC full", r, limit(h++, 0, 200, BID, 60_000, 1, IOC), List.of("T", "M", "2:0"), A, FILLED, 0, 0, next, true);
            // IOC, partial.
            rest(r, h++, 100, ASK, 60_000, 1);
            assertRow("IOC partial", r, limit(h++, 0, 200, BID, 60_000, 3, IOC), List.of("T", "M", "3:12"), A, CANCELLED,
                    IOC_REMAINDER, 0, null, true);
            // IOC, no match.
            assertRow("IOC no match", r, limit(h++, 0, 200, BID, 60_000, 3, IOC), List.of("3:12"), A, CANCELLED,
                    IOC_REMAINDER, 0, null, true);
            // IOC, cap fired (cap 2, three makers).
            for (int j = 0; j < 3; j++) rest(r, h++, 100, ASK, 60_000, 1);
            assertRow("IOC cap", r, limit(h++, 0, 200, BID, 60_000, 3, IOC), List.of("T", "M", "T", "M", "3:7"), A,
                    CANCELLED, MATCH_LIMIT, 0, null, true);
            // FOK infeasible by quantity (one maker of 1 left), by cap (three makers, cap 2).
            assertRow("FOK infeasible qty", r, limit(h++, 0, 200, BID, 60_000, 2, FOK), List.of("3:13"), A, CANCELLED,
                    FOK_UNFILLABLE, 0, null, true);
            rest(r, h++, 100, ASK, 60_000, 1);
            rest(r, h++, 100, ASK, 60_000, 1);
            assertRow("FOK infeasible cap", r, limit(h++, 0, 200, BID, 60_000, 3, FOK), List.of("3:13"), A, CANCELLED,
                    FOK_UNFILLABLE, 0, null, true);
            // FOK feasible (two makers of the three).
            assertRow("FOK feasible", r, limit(h++, 0, 200, BID, 60_000, 2, FOK), List.of("T", "M", "T", "M", "2:0"), A,
                    FILLED, 0, 0, null, true);
            // MARKET, bound filled (maker of 2 left with 1 after: no maker FILLED).
            rest(r, h++, 100, ASK, 60_000, 1);   // the third maker of above is still there: asks 1 + 1
            assertRow("MARKET bound", r, market(h++, 0, 200, BID, 1, 1_000_000, GTC), List.of("T", "M", "2:0"), A, FILLED,
                    0, 0, null, true);
            // MARKET BUY, budget first (one maker of 1 left; budget for half of it).
            assertRow("MARKET budget", r, market(h++, 0, 200, BID, 1, 30_000, GTC), List.of("T", "3:14"), A, CANCELLED,
                    BUDGET_EXHAUSTED, 0, null, true);
            // MARKET, book exhausted (the half unit left, then nothing).
            assertRow("MARKET exhausted", r, market(h++, 0, 200, BID, 1, 1_000_000, GTC), List.of("T", "M", "3:12"), A,
                    CANCELLED, IOC_REMAINDER, 0, null, true);
            // MARKET, no match: empty book (9), budget below one unit (14).
            assertRow("MARKET no liquidity", r, market(h++, 0, 200, BID, 1, 1_000_000, GTC), List.of("4:9"), R, REJECTED,
                    NO_LIQUIDITY, 0, null, true);
            rest(r, h++, 100, ASK, 60_000, 1);
            assertRow("MARKET no match budget", r, market(h++, 0, 200, BID, 1, 0.0001, GTC), List.of("4:14"), R,
                    REJECTED, BUDGET_EXHAUSTED, 0, null, true);
            // MARKET, quantity or budget <= 0.
            assertRow("MARKET qty 0", r, market(h++, 0, 200, BID, 0, 1_000, GTC), List.of("4:6"), R, REJECTED,
                    INVALID_QUANTITY, 0, null, true);
            assertRow("MARKET budget 0", r, market(h++, 0, 200, BID, 1, 0, GTC), List.of("4:6"), R, REJECTED,
                    INVALID_QUANTITY, 0, null, true);
            // UPDATE, reduced leg: leg 10, taker 3, amend to 8 with expected 10.
            final long lh = h++;
            final long leg = rest(r, lh, 300, BID, 59_000, 10);
            assertEquals(FILLED, outcome(r.send(market(h++, 0, 200, ASK, 3, 0, GTC))).status());
            assertRow("UPDATE reduced", r, amendRaw(lh, 1, 300, leg, BID, 59_001, 8, LIMIT, GTC, fp(10)), List.of(), A, NEW,
                    0, 1, null, true);
            final long leg2 = S5Frames.book(r).getBidOrders()[0];
            // UPDATE, no remainder: target 8, F0 = 3 at the mint (expected 8 - 3 = 5), the user amends to 4
            // (quantity 4 - 3 = 1); a taker fills 3 more in the window (leg 2): reduced 1 - 3 < 0.
            assertEquals(FILLED, outcome(r.send(market(h++, 0, 200, ASK, 3, 0, GTC))).status());
            assertRow("UPDATE no remainder", r, amendRaw(lh, 2, 300, leg2, BID, 59_001, 1, LIMIT, GTC, fp(5)), List.of(),
                    R, REJECTED, AMEND_NO_REMAINDER, 1, leg2, true);
            // UPDATE, mismatch.
            final long mh = h++;
            final long leg3 = rest(r, mh, 300, BID, 58_000, 10);
            assertRow("UPDATE mismatch", r, amendRaw(mh, 1, 300, leg3, BID, 58_001, 8, LIMIT, GTC, fp(4)), List.of(),
                    R, REJECTED, AMEND_REMAINING_MISMATCH, 0, leg3, true);
            // INVALID: tif, amend reference, amend type.
            assertRow("INVALID tif", r, limitMaker(h++, 0, 300, BID, 57_000, 1, IOC), List.of(), I, -1, 0, 0, 0L, false);
            assertRow("INVALID reference", r, amendRaw(mh, 2, 300, leg3, BID, 58_001, 8, LIMIT, GTC, 0), List.of(), I, -1,
                    0, 0, 0L, false);
            assertRow("INVALID type", r, amendRaw(mh, 3, 300, leg3, BID, 58_001, 8, MARKET, GTC, fp(10)), List.of(), I, -1,
                    0, 0, 0L, false);
        }
        // UPDATE, reduced leg that could not rest (direct, a level of 1 already full): the new
        // leg's REJECTED terminal, then record 3 REJECTED oldCancelled 1.
        try (Run r = freshWith(S5Frames.config(true, 100, 1))) {
            rest(r, 1, 100, BID, 58_000, 1);
            final long leg = rest(r, 2, 101, BID, 57_000, 10);
            assertEquals(FILLED, outcome(r.send(market(3, 0, 200, ASK, 4, 0, GTC))).status()); // 1 @58000, 3 of leg
            rest(r, 4, 102, BID, 56_000, 1);
            final long next = r.engine().getOrderIdGenerator();
            assertRow("UPDATE reduced, could not rest", r, amendRaw(2, 1, 101, leg, BID, 56_000, 8, LIMIT, GTC, fp(10)),
                    List.of("4:3"), R, REJECTED, 3, 1, next, true);
        }
    }
}
