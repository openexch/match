// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/**
 * RC2 S4 (contract section 8): the 14 fields of a durable command intent as primitive getters, so
 * the behaviour-2 durable path reads an intent without allocating a record per frame. The demuxer
 * fills one reusable mutable implementation per frame ({@link MutableDurableIntent});
 * {@link DurableOrderIntent} implements it too (its accessors already carry these names), so tests
 * and the behaviour-1 path can hand a record to the same code.
 *
 * <p>Implementations are read only during the call they are passed to; a callee that needs the
 * values later copies them (never retains the view).</p>
 */
public interface DurableIntentView {
    long idHigh();

    long idLow();

    long userId();

    long omsOrderId();

    long oldOrderId();

    long price();

    long quantity();

    long budget();

    int marketId();

    int kind();

    int type();

    int side();

    int timeInForce();

    long expectedOldRemaining();
}
