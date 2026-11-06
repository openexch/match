// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/** Command outcome is NOT financial terminal authority. The journal's trade/terminal lane is separate. */
public record DurableCommandOutcome(DurableOrderIntent intent, long appliedPosition, long timestamp,
        long orderId, int status, int reason, boolean oldCancelled, int result) implements DurableOutcomeView {
    public static final int APPLIED = 0, REJECTED = 1, CONFLICT = 2, CAPACITY = 3,
            UNKNOWN_LEG = 4, WRONG_OWNER = 5, UNKNOWN_MARKET = 6,
            /** Schema 4 record 3 value 7; written from S4 (ReleaseCommandIds). */
            RELEASED = 7,
            /** Schema 4 record 3 value 8: a frame above the recorded wire ceiling (RC2 S2 section 6). */
            INVALID = 8;

    // RC2 S4: the intent half of the view (the record keeps its intent; the journal reads one shape).
    @Override public long idHigh() { return intent.idHigh(); }
    @Override public long idLow() { return intent.idLow(); }
    @Override public long userId() { return intent.userId(); }
    @Override public long omsOrderId() { return intent.omsOrderId(); }
    @Override public long oldOrderId() { return intent.oldOrderId(); }
    @Override public long price() { return intent.price(); }
    @Override public long quantity() { return intent.quantity(); }
    @Override public long budget() { return intent.budget(); }
    @Override public int marketId() { return intent.marketId(); }
    @Override public int kind() { return intent.kind(); }
    @Override public int type() { return intent.type(); }
    @Override public int side() { return intent.side(); }
    @Override public int timeInForce() { return intent.timeInForce(); }
    @Override public long expectedOldRemaining() { return intent.expectedOldRemaining(); }
}
