// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

/**
 * Test scope only: the journal writer's drain gate for tests of other packages. The gate is read when a member's
 * journal runtime starts, so a rig holds it around the start of one member and lets go afterwards: that member's
 * writer then records nothing (its ring keeps the entries) while the others' writers go on.
 */
public final class JournalDrainGate {
    private JournalDrainGate() {
    }

    /** Writers started from now on do nothing while {@code gate} answers false; null restores the default. */
    public static void install(final java.util.function.BooleanSupplier gate) {
        SettlementJournalRuntime.installDrainGateForDrill(gate);
    }
}
