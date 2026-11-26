// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import io.aeron.archive.client.AeronArchive;

/**
 * Positive control for {@code NoPurgeCallSiteInMatchTest} (test scope only): a journal segment purge in the match
 * tree, outside the kit's one purge owner for journal recordings ({@code JournalRetention}).
 */
public final class ForbiddenMatchSegmentPurgeProbe {
    private ForbiddenMatchSegmentPurgeProbe() {
    }

    public static void purge(final AeronArchive archive, final long recordingId, final long newStartPosition) {
        archive.purgeSegments(recordingId, newStartPosition);
    }

    /** Also a class that switches a retention seam, as a negative control does and main code never may. */
    public static void clampOff(final boolean off) {
        com.openexchange.cluster.journal.JournalRetention.Seams.clampOff = off;
    }
}
