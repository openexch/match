// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

/**
 * Reject reason codes for order book admission.
 * Using int constants instead of enum for zero-allocation dispatch.
 *
 * Loud-limits principle: every capacity or validity boundary must be
 * reported to the caller with a reason — never silently dropped.
 */
public final class OrderRejectReason {
    public static final int NONE = 0;
    public static final int PRICE_OUT_OF_RANGE = 1;
    public static final int PRICE_OFF_TICK = 2;
    public static final int LEVEL_FULL = 3;
    /**
     * The book's shared order pool is exhausted (array-backed book). This is the
     * array-backed equivalent of {@link #LEVEL_FULL}: a single, book-wide, runtime-
     * adjustable capacity bound rather than a per-level 64-order cap.
     */
    public static final int BOOK_FULL = 4;
    /**
     * A notional/cost computation would overflow 64-bit fixed-point. Rejecting is
     * the only correct answer: wrong money must never enter the book (P1.1,
     * match#30). Reserved here so the diag counter and harness can report it;
     * detection is wired when FixedPoint.multiply learns to throw.
     */
    public static final int OVERFLOW = 5;
    /**
     * The order's quantity (or a market buy's total budget) is not strictly positive
     * (match#91). {@code quantity} is signed int64 on the wire, so 0/negative are
     * representable; admitting them silently loses a LIMIT (no status ever fires) and
     * rests a qty=0 LIMIT_MAKER as a head-of-level poison pill that abandons the whole
     * price level. Rejecting at admission is the only correct answer. A distinct code
     * (not a reused one) because reject codes become user-visible wire values.
     */
    public static final int INVALID_QUANTITY = 6;
    /**
     * The per-order match cap ({@code MAX_MATCHES_PER_ORDER}) fired mid-sweep while crossing
     * liquidity still remained, so the taker was terminated at the cap rather than continuing
     * (match#93). The cap exists to bound per-command work on the single consensus thread; when
     * it truncates a sweep the leftover taker quantity must NOT rest on the book — doing so would
     * cross the book (a bid resting above unreached asks) and, for a market order, mis-report a
     * partial as FILLED. This reason routes the remainder through the loud terminal-status path
     * (CANCELLED with the true filled quantity) instead of {@code addOrder}.
     */
    public static final int MATCH_LIMIT = 7;
    /**
     * A post-only order (LIMIT_MAKER) would have crossed the opposite best and executed as a taker,
     * violating its maker-only guarantee (match#92). The engine already had this reason "in hand" as
     * a would-cross REJECTED status but carried no code for it; assigned here so the wire (SBE v6,
     * match#75) can surface WHY a post-only order was rejected. Fires on both the LIMIT_MAKER create
     * branch and the LIMIT_MAKER amend branch.
     */
    public static final int WOULD_CROSS = 8;
    /**
     * A MARKET order found no crossing liquidity (empty opposite book): it matched nothing, so there
     * is nothing to fill and nothing to rest (market orders never rest). Distinct from
     * INVALID_QUANTITY (which is a garbage-size reject before the sweep even starts); NO_LIQUIDITY is
     * a well-formed market order that simply had no counterparties. Carried on the REJECTED egress
     * (SBE v6, match#75) so a user sees "no liquidity" rather than a bare, reasonless reject.
     */
    public static final int NO_LIQUIDITY = 9;
    /**
     * A CANCEL/UPDATE referenced an order that is not resting on the book (already filled/cancelled,
     * or never existed). For an UPDATE (cancel-and-replace) this rejects the amend and leaves nothing
     * changed. A distinct code (SBE v6, match#75) so the wire distinguishes "your order is gone" from
     * a validity or crossing reject.
     */
    public static final int ORDER_NOT_FOUND = 10;
    /**
     * The order arrived on a config-mode cluster BEFORE any EngineConfig was logged, so no
     * matching engine exists yet (slice C fresh-cluster guard). The command is rejected loudly
     * and deterministically — orderId=0 + the command's omsOrderId on the REJECTED egress so the
     * OMS releases its hold — never silently dropped (a silent drop leaves the hold stuck
     * forever, the oms#21 failure class). Legacy (env-mode) clusters can never emit this: their
     * engines exist from boot. A distinct code because reject codes are user-visible wire values.
     */
    public static final int ENGINE_NOT_CONFIGURED = 11;
    /**
     * RC2 S5 (behaviour 2): the unfilled remainder of an IOC limit order, or of a market order that
     * exhausted the opposite book, was cancelled (never rested). Carried on the CANCELLED terminal.
     */
    public static final int IOC_REMAINDER = 12;
    /** RC2 S5 (behaviour 2): a FOK order could not fill completely within the match cap; nothing traded. */
    public static final int FOK_UNFILLABLE = 13;
    /**
     * RC2 S5 (behaviour 2): a bounded market buy ran out of budget before its quantity (CANCELLED
     * with the true filled quantity), or could not pay one unit at the best ask (REJECTED). Also the
     * report of the budget-divide overflow stop, unreachable from the core (notional caps).
     */
    public static final int BUDGET_EXHAUSTED = 14;
    /**
     * RC2 S5 (behaviour 2): an amend whose old leg's in-window fills reached or passed the new
     * target: the old leg is cancelled and nothing is placed (record 3 REJECTED, oldCancelled 1).
     */
    public static final int AMEND_NO_REMAINDER = 15;
    /**
     * RC2 S5 (behaviour 2): an amend whose old leg has MORE remaining than the core's
     * expectedOldRemaining (a wrong reference): REJECTED, the old leg is left as it is.
     */
    public static final int AMEND_REMAINING_MISMATCH = 16;

    private OrderRejectReason() {} // Constants only

    public static String describe(int reason) {
        switch (reason) {
            case NONE: return "NONE";
            case PRICE_OUT_OF_RANGE: return "PRICE_OUT_OF_RANGE";
            case PRICE_OFF_TICK: return "PRICE_OFF_TICK";
            case LEVEL_FULL: return "LEVEL_FULL";
            case BOOK_FULL: return "BOOK_FULL";
            case OVERFLOW: return "OVERFLOW";
            case INVALID_QUANTITY: return "INVALID_QUANTITY";
            case MATCH_LIMIT: return "MATCH_LIMIT";
            case WOULD_CROSS: return "WOULD_CROSS";
            case NO_LIQUIDITY: return "NO_LIQUIDITY";
            case ORDER_NOT_FOUND: return "ORDER_NOT_FOUND";
            case ENGINE_NOT_CONFIGURED: return "ENGINE_NOT_CONFIGURED";
            case IOC_REMAINDER: return "IOC_REMAINDER";
            case FOK_UNFILLABLE: return "FOK_UNFILLABLE";
            case BUDGET_EXHAUSTED: return "BUDGET_EXHAUSTED";
            case AMEND_NO_REMAINDER: return "AMEND_NO_REMAINDER";
            case AMEND_REMAINING_MISMATCH: return "AMEND_REMAINING_MISMATCH";
            default: return "UNKNOWN(" + reason + ")";
        }
    }
}
