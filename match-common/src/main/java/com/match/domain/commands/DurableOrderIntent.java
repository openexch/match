// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/**
 * Immutable command identity and full payload binding, independent of transport retries.
 *
 * <p>Order-schema v12 adds {@code timeInForce} (0 GTC, 1 IOC, 2 FOK) and
 * {@code expectedOldRemaining} (UPDATE only; a non-zero value on CREATE or CANCEL is refused here,
 * RC2 S2 F1, so the demuxer counts it as decode poison). A v9..v11 frame carries neither and maps
 * to 0/0. In behaviour 1 the ledger's equal-intent comparison is
 * {@link #sameCommandBehaviour1} (the original 12 fields): the ledger snapshot does not carry the
 * two new fields, so comparing them would make a retry's DUPLICATE/CONFLICT depend on whether the
 * node restored from a snapshot.</p>
 */
public record DurableOrderIntent(long idHigh, long idLow, long userId, long omsOrderId,
        long oldOrderId, long price, long quantity, long budget, int marketId,
        int kind, int type, int side, int timeInForce, long expectedOldRemaining) implements DurableIntentView {
    public DurableOrderIntent {
        if (!isValid(idHigh, idLow, userId, omsOrderId, oldOrderId, marketId, kind, type, side,
                timeInForce, expectedOldRemaining))
            throw new IllegalArgumentException("Invalid durable command identity");
    }

    /**
     * The base rules every reader enforces (the record 3 echo is reader-valid iff these hold, RC2
     * S4 contract 3.1). Static and allocation-free so the behaviour-2 demuxer checks a pooled
     * view without building the record; the constructor uses the same expression.
     */
    public static boolean isValid(long idHigh, long idLow, long userId, long omsOrderId, long oldOrderId,
                                  int marketId, int kind, int type, int side, int timeInForce,
                                  long expectedOldRemaining) {
        return !((idHigh == 0 && idLow == 0) || userId <= 0 || omsOrderId <= 0 || marketId <= 0
                || kind < 0 || kind > 2 || type < 0 || type > 2 || side < 0 || side > 1
                || (kind == 0 ? oldOrderId != 0 : oldOrderId <= 0)
                || timeInForce < 0 || timeInForce > 2 || expectedOldRemaining < 0
                // F1 (RC2 S2 6.1): expectedOldRemaining is an UPDATE-only field.
                || (kind != 2 && expectedOldRemaining != 0));
    }

    /** RC2 S4 B1: a behaviour-2 identity is valid. */
    public static final int B2_OK = 0;
    /** RC2 S4 B1: {@code idHigh < 1} or {@code idLow < 0}. */
    public static final int B2_INVALID_SIGN = 1;
    /** RC2 S4 B1: {@code (idLow & 3) != kind}. */
    public static final int B2_INVALID_KIND_BITS = 2;
    /** RC2 S4 B1 (decision A3): {@code omsOrderId != idHigh}. */
    public static final int B2_INVALID_OWNER_ID = 3;

    /**
     * RC2 S4 B1, the behaviour-2 identity rules on top of {@link #isValid}: the core mints
     * {@code idHigh = omsOrderId = orderId >= 1} and {@code idLow = attempt << 2 | kind}. Returns
     * {@link #B2_OK} or the first failing cause in the contract's order (sign, kind bits, owner).
     */
    public static int behaviourTwoCause(long idHigh, long idLow, long omsOrderId, int kind) {
        if (idHigh < 1 || idLow < 0) {
            return B2_INVALID_SIGN;
        }
        if ((idLow & 3) != kind) {
            return B2_INVALID_KIND_BITS;
        }
        if (omsOrderId != idHigh) {
            return B2_INVALID_OWNER_ID;
        }
        return B2_OK;
    }

    /**
     * RC2 S5 D-a: a TIF outside the allow-list (CREATE LIMIT {GTC, IOC, FOK}, CREATE MARKET {GTC,
     * IOC}, CREATE LIMIT_MAKER {GTC}, CANCEL and UPDATE {GTC}).
     */
    public static final int B2_INVALID_TIF = 4;
    /** RC2 S5 D-a: an UPDATE with {@code expectedOldRemaining == 0}. */
    public static final int B2_INVALID_AMEND_REFERENCE = 5;
    /** RC2 S5 D-e: an UPDATE with {@code orderType MARKET}. */
    public static final int B2_INVALID_AMEND_TYPE = 6;

    /**
     * RC2 S5 (contract 3.1): the S4 causes of {@link #behaviourTwoCause(long, long, long, int)}, then
     * the three S5 causes in this order: TIF, amend reference, amend type. Behaviour-2 rules only:
     * {@link #isValid} (the reader rule of both behaviours) is unchanged, so a behaviour-1 frame
     * carrying any of these combinations still decodes and applies as before.
     */
    public static int behaviourTwoCause(long idHigh, long idLow, long omsOrderId, int kind, int type,
                                        int timeInForce, long expectedOldRemaining) {
        final int s4 = behaviourTwoCause(idHigh, idLow, omsOrderId, kind);
        if (s4 != B2_OK) {
            return s4;
        }
        if ((timeInForce != 0 && kind != 0) || (type == 2 && timeInForce != 0) || (type == 1 && timeInForce == 2)) {
            return B2_INVALID_TIF;
        }
        if (kind == 2 && expectedOldRemaining == 0) {
            return B2_INVALID_AMEND_REFERENCE;
        }
        if (kind == 2 && type == 1) {
            return B2_INVALID_AMEND_TYPE;
        }
        return B2_OK;
    }

    /** A pre-v12 intent: {@code timeInForce} GTC (0), {@code expectedOldRemaining} 0. */
    public DurableOrderIntent(long idHigh, long idLow, long userId, long omsOrderId,
            long oldOrderId, long price, long quantity, long budget, int marketId,
            int kind, int type, int side) {
        this(idHigh, idLow, userId, omsOrderId, oldOrderId, price, quantity, budget, marketId,
                kind, type, side, 0, 0L);
    }

    public java.util.UUID id() { return new java.util.UUID(idHigh, idLow); }

    /** Behaviour-1 equal-intent comparison: the 12 pre-v12 fields, never the v12 additions. */
    public boolean sameCommandBehaviour1(DurableOrderIntent o) {
        return idHigh == o.idHigh && idLow == o.idLow && userId == o.userId
                && omsOrderId == o.omsOrderId && oldOrderId == o.oldOrderId && price == o.price
                && quantity == o.quantity && budget == o.budget && marketId == o.marketId
                && kind == o.kind && type == o.type && side == o.side;
    }
}
