// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

/**
 * RC2 S3 review FIX-2: test-scope factory for a STANDALONE bound {@link MeIdentity} (a journal
 * stamped without an engine). {@link MeIdentity#restore} is package-private so that an engine's
 * identity can only be bound through {@link Engine#restoreIdentity}, which keeps
 * {@code dense == meIdentity.isBound()}.
 */
public final class MeIdentityTestAccess {

    private MeIdentityTestAccess() {
    }

    public static MeIdentity restored(final long meEpoch, final long nextJournalSeq, final long behaviorVersion) {
        final MeIdentity identity = new MeIdentity();
        identity.restore(meEpoch, nextJournalSeq, behaviorVersion);
        return identity;
    }
}
