// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.orderbook.MatchingEngine;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.assertEquals;

/**
 * RC2 S5 test support: core-shaped behaviour-2 frames carrying timeInForce, budgets and the amend
 * reference, the reason codes as wire literals (so the sources compile against the S4 base, where
 * the constants do not exist yet) and journal readers. Every S5 frame goes through the real
 * {@link SbeDemuxer} of a {@link BehaviourTwoLog.Replica}.
 */
final class S5Frames {

    private S5Frames() {
    }

    static final int GTC = 0, IOC = 1, FOK = 2;
    // Record 2 / record 3 codes (OrderRejectReason; 12-16 are S5's).
    static final int NONE = 0, INVALID_QUANTITY = 6, MATCH_LIMIT = 7, NO_LIQUIDITY = 9;
    static final int IOC_REMAINDER = 12, FOK_UNFILLABLE = 13, BUDGET_EXHAUSTED = 14,
            AMEND_NO_REMAINDER = 15, AMEND_REMAINING_MISMATCH = 16;
    // Terminal statuses.
    static final int NEW = 0, PARTIALLY_FILLED = 1, FILLED = 2, CANCELLED = 3, REJECTED = 4;
    static final int BID = 0, ASK = 1;

    /** A behaviour-2 FRESH on market 1 with an explicit impl, match cap and direct level size. */
    static byte[] config(boolean direct, int cap, int maxOrdersPerLevel) {
        return engineConfigWith(1, 2, G, 12, 1_000_000, direct, direct ? 0 : 4_096, cap,
                direct ? maxOrdersPerLevel : 0, 1);
    }

    static byte[] limit(long h, long attempt, long user, int side, double price, double qty, int tif) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, fp(price), fp(qty), 0L, LIMIT, tif, 0L);
    }

    static byte[] limitMaker(long h, long attempt, long user, int side, double price, double qty, int tif) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, fp(price), fp(qty), 0L, LIMIT_MAKER,
                tif, 0L);
    }

    /** A MARKET create: quantity and budget as given (a buy needs both at behaviour 2). */
    static byte[] market(long h, long attempt, long user, int side, double qty, double budget, int tif) {
        return durableRaw(h, (attempt << 2) | CREATE, CREATE, user, h, 0L, side, 0L, fp(qty), fp(budget), MARKET, tif, 0L);
    }

    static byte[] cancelTif(long h, long attempt, long user, long leg, int tif) {
        return durableRaw(h, (attempt << 2) | CANCEL, CANCEL, user, h, leg, 0, 0L, 0L, 0L, LIMIT, tif, 0L);
    }

    /** An UPDATE with the amend reference {@code expected} (fixed-point raw) and an explicit tif. */
    static byte[] amendRaw(long h, long attempt, long user, long leg, int side, double price, double qty, int type,
                           int tif, long expected) {
        return durableRaw(h, (attempt << 2) | UPDATE, UPDATE, user, h, leg, side, fp(price), fp(qty), 0L, type, tif,
                expected);
    }

    // ---- readers ----

    static TerminalDecoder terminal(byte[] frame) {
        return new TerminalDecoder().wrapAndApplyHeader(new UnsafeBuffer(frame), 0, new MessageHeaderDecoder());
    }

    static TradeDecoder trade(byte[] frame) {
        return new TradeDecoder().wrapAndApplyHeader(new UnsafeBuffer(frame), 0, new MessageHeaderDecoder());
    }

    static List<Integer> templates(List<byte[]> frames) {
        final List<Integer> out = new ArrayList<>();
        for (byte[] f : frames) {
            out.add(BehaviourTwoHarness.templateId(f));
        }
        return out;
    }

    /** The record 2 terminal of {@code orderId} in an entry's frames (exactly one). */
    static TerminalDecoder terminalOf(List<byte[]> frames, long orderId) {
        TerminalDecoder found = null;
        for (byte[] f : of(frames, 2)) {
            final TerminalDecoder t = terminal(f);
            if (t.orderId() == orderId) {
                assertEquals("one terminal for order " + orderId, null, found);
                found = t;
            }
        }
        if (found == null) {
            throw new AssertionError("no terminal for order " + orderId + " in " + templates(frames));
        }
        return found;
    }

    /** Sum of the trade quantities of an entry (fixed-point raw). */
    static long tradedQuantity(List<byte[]> frames) {
        long q = 0;
        for (byte[] f : of(frames, 1)) {
            q += trade(f).quantity();
        }
        return q;
    }

    /** The resting remaining of {@code orderId} read from the book export (-1 when absent). */
    static long remainingOf(MatchingEngine me, long orderId) {
        for (long[] side : new long[][]{me.getBidOrders(), me.getAskOrders()}) {
            for (int i = 0; i < side.length; i += 4) {
                if (side[i] == orderId) {
                    return side[i + 3];
                }
            }
        }
        return -1L;
    }

    static MatchingEngine book(Run r) {
        return r.engine().getEngine(BehaviourTwoHarness.MARKET);
    }

    static CommandOutcomeDecoder outcome(List<byte[]> frames) {
        return only3(frames);
    }
}
