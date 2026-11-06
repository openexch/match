// SPDX-License-Identifier: Apache-2.0
package com.match.domain.commands;

/**
 * RC2 S4 (contract section 8, delta 12.14): a durable command outcome as primitive getters, the
 * 14 intent fields plus the 7 outcome fields of journal record 3. The engine's single mutable
 * scratch implements it on the behaviour-2 path, so the outcome reaches the sink without a record
 * allocation; {@link DurableCommandOutcome} implements it for the behaviour-1 path.
 *
 * <p>Implementations must not retain the view after the call they receive it in (a recorder
 * copies).</p>
 */
public interface DurableOutcomeView extends DurableIntentView {
    long appliedPosition();

    long timestamp();

    long orderId();

    int status();

    int reason();

    boolean oldCancelled();

    int result();
}
