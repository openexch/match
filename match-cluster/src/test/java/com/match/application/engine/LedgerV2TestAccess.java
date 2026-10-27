// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

/** RC2 S4 test scope: reaches the package-private ledger v2 negative-control seam. */
public final class LedgerV2TestAccess {
    private LedgerV2TestAccess() {
    }

    /** Remove every inflight entry of {@code h} without tombstoning it (the broken release). */
    public static int forgetWithoutTombstone(Engine engine, long h) {
        return engine.ledgerV2().forgetWithoutTombstoneForTest(h);
    }
}
