// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/** Command outcome is NOT financial terminal authority. The journal's trade/terminal lane is separate. */
public record DurableCommandOutcome(DurableOrderIntent intent, long appliedPosition, long timestamp,
        long orderId, int status, int reason, boolean oldCancelled, int result) {
    public static final int APPLIED = 0, REJECTED = 1, CONFLICT = 2, CAPACITY = 3,
            UNKNOWN_LEG = 4, WRONG_OWNER = 5, UNKNOWN_MARKET = 6,
            /** Schema 4 record 3 value 7; written from S4 (ReleaseCommandIds). */
            RELEASED = 7,
            /** Schema 4 record 3 value 8: a frame above the recorded wire ceiling (RC2 S2 section 6). */
            INVALID = 8;
}
