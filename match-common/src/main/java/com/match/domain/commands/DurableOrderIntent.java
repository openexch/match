// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/** Immutable command identity and full payload binding, independent of transport retries. */
public record DurableOrderIntent(long idHigh, long idLow, long userId, long omsOrderId,
        long oldOrderId, long price, long quantity, long budget, int marketId,
        int kind, int type, int side) {
    public DurableOrderIntent {
        if ((idHigh == 0 && idLow == 0) || userId <= 0 || omsOrderId <= 0 || marketId <= 0
                || kind < 0 || kind > 2 || type < 0 || type > 2 || side < 0 || side > 1
                || (kind == 0 ? oldOrderId != 0 : oldOrderId <= 0))
            throw new IllegalArgumentException("Invalid durable command identity");
    }
    public java.util.UUID id() { return new java.util.UUID(idHigh, idLow); }
}
