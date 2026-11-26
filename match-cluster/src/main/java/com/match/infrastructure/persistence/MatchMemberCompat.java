// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.EngineConfigStateMachine;
import com.openexchange.cluster.MemberCompat;

/**
 * The matching engine's member version and capability manifest (S4). {@link #MEMBER_COMPAT} is the Aeron app version
 * every match member, and every process that replays the match log, carries; the kit's validator lets one generation
 * run levels N and N-1 together and stops any other member with exit 78.
 *
 * <p><b>When to bump.</b> The level rises by exactly one in every release whose {@link #manifest()} changes
 * ({@code MemberCompatManifestTest} fails with "capability manifest changed without a level bump" otherwise; commit
 * {@code src/test/resources/compat/manifest-g<g>.<level>.txt} and keep the previous level's file). The generation
 * changes only with a clean genesis. Core and match levels are independent numbers.</p>
 */
public final class MatchMemberCompat {

    /** The first build after the S4 genesis. */
    public static final MemberCompat MEMBER_COMPAT = MemberCompat.of(1, 1);

    /** The lowest order-schema version this build decodes (the wire floor). */
    public static final int WIRE_FLOOR = SbeDemuxer.MIN_SUPPORTED_SCHEMA_VERSION;
    /** The highest order-schema version this build decodes (the wire top). */
    public static final int WIRE_TOP = EngineConfigStateMachine.SUPPORTED_MAX_WIRE_VERSION;

    private MatchMemberCompat() {
    }

    /** The version this process runs: {@link #MEMBER_COMPAT} outside the process tests (the kit's test seam). */
    static MemberCompat current() {
        return MemberCompat.effective(MEMBER_COMPAT);
    }

    /** The behaviour versions this jar runs, ascending, from {@link EngineConfigStateMachine#SUPPORTED_BEHAVIOR_VERSIONS_MASK}. */
    public static int[] behaviors() {
        return java.util.stream.IntStream.rangeClosed(1, 63)
                .filter(v -> EngineConfigStateMachine.supportsBehaviour(v)).toArray();
    }

    /** The snapshot layout versions this build reads, ascending. */
    public static int[] layouts() {
        final var l = SnapshotCodec.LAYOUTS;
        return l.previous() == null ? new int[] {l.current().version()}
                : new int[] {l.previous().version(), l.current().version()};
    }

    /** The canonical capability manifest of this build. */
    public static MemberCompat.Manifest manifest() {
        return MemberCompat.Manifest.builder()
                .wire(WIRE_FLOOR, WIRE_TOP)
                .behaviors(behaviors())
                .layouts(SnapshotCodec.LAYOUTS)
                .journal("me-journal", com.match.infrastructure.mejournal.generated.MessageHeaderEncoder.SCHEMA_ID,
                        com.match.infrastructure.mejournal.generated.MessageHeaderEncoder.SCHEMA_VERSION)
                .build();
    }

    /** The /health and /ready capability tokens for a member at {@code compat}. */
    public static MemberCompat.Capabilities capabilities(final MemberCompat compat, final String driver) {
        return new MemberCompat.Capabilities(compat, WIRE_FLOOR, WIRE_TOP, behaviors(), layouts(), driver);
    }
}
