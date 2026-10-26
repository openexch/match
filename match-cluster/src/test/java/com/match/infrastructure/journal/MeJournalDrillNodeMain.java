// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import java.io.File;

/**
 * RC2 S3 drill child (test scope, contract section 11 step 6): installs a file-existence drain
 * gate on the settlement journal writer through the package-private hook of
 * {@link SettlementJournalRuntime}, then runs the production bootstrap unchanged. While the hold
 * file exists the writer drains nothing, so entries pile up in the in-memory journal ring exactly
 * as a slow or stalled writer leaves them; a kill -9 then loses them.
 *
 * <p>Usage: {@code MeJournalDrillNodeMain <holdFile>}; everything else is the production env.</p>
 */
public final class MeJournalDrillNodeMain {

    private MeJournalDrillNodeMain() {
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.err.println("usage: MeJournalDrillNodeMain <holdFile>");
            System.exit(2);
        }
        final File hold = new File(args[0]);
        SettlementJournalRuntime.installDrainGateForDrill(() -> !hold.exists());
        System.out.println("[DRILL] drain gate installed: hold file " + hold);
        new com.match.infrastructure.persistence.AeronCluster();
    }
}
