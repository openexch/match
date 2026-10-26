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
        int kind, int type, int side, int timeInForce, long expectedOldRemaining) {
    public DurableOrderIntent {
        if ((idHigh == 0 && idLow == 0) || userId <= 0 || omsOrderId <= 0 || marketId <= 0
                || kind < 0 || kind > 2 || type < 0 || type > 2 || side < 0 || side > 1
                || (kind == 0 ? oldOrderId != 0 : oldOrderId <= 0)
                || timeInForce < 0 || timeInForce > 2 || expectedOldRemaining < 0
                // F1 (RC2 S2 6.1): expectedOldRemaining is an UPDATE-only field.
                || (kind != 2 && expectedOldRemaining != 0))
            throw new IllegalArgumentException("Invalid durable command identity");
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
