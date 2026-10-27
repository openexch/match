// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/**
 * RC2 S4 (contract section 8): the reusable, mutable {@link DurableIntentView} the demuxer fills
 * once per template-9 frame instead of allocating a {@link DurableOrderIntent}. Single-threaded
 * (service thread); never retained across frames.
 */
public final class MutableDurableIntent implements DurableIntentView {
    private long idHigh;
    private long idLow;
    private long userId;
    private long omsOrderId;
    private long oldOrderId;
    private long price;
    private long quantity;
    private long budget;
    private int marketId;
    private int kind;
    private int type;
    private int side;
    private int timeInForce;
    private long expectedOldRemaining;

    /** Overwrite every field. */
    public MutableDurableIntent set(long idHigh, long idLow, long userId, long omsOrderId, long oldOrderId,
                                    long price, long quantity, long budget, int marketId, int kind, int type,
                                    int side, int timeInForce, long expectedOldRemaining) {
        this.idHigh = idHigh;
        this.idLow = idLow;
        this.userId = userId;
        this.omsOrderId = omsOrderId;
        this.oldOrderId = oldOrderId;
        this.price = price;
        this.quantity = quantity;
        this.budget = budget;
        this.marketId = marketId;
        this.kind = kind;
        this.type = type;
        this.side = side;
        this.timeInForce = timeInForce;
        this.expectedOldRemaining = expectedOldRemaining;
        return this;
    }

    /** A copy as the immutable record (allocates; behaviour 1 and tests only). */
    public DurableOrderIntent toRecord() {
        return new DurableOrderIntent(idHigh, idLow, userId, omsOrderId, oldOrderId, price, quantity, budget,
                marketId, kind, type, side, timeInForce, expectedOldRemaining);
    }

    @Override public long idHigh() { return idHigh; }
    @Override public long idLow() { return idLow; }
    @Override public long userId() { return userId; }
    @Override public long omsOrderId() { return omsOrderId; }
    @Override public long oldOrderId() { return oldOrderId; }
    @Override public long price() { return price; }
    @Override public long quantity() { return quantity; }
    @Override public long budget() { return budget; }
    @Override public int marketId() { return marketId; }
    @Override public int kind() { return kind; }
    @Override public int type() { return type; }
    @Override public int side() { return side; }
    @Override public int timeInForce() { return timeInForce; }
    @Override public long expectedOldRemaining() { return expectedOldRemaining; }
}
