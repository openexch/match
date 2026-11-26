// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * S4 T2 (match): the build's capability manifest equals the committed file of its level, and against the previous
 * level's file it keeps the level rule (runs N-1's highest behaviour, reads the layout N-1 writes, same wire floor,
 * wire top not lower). At the genesis level (g1.1) there is no previous file and those checks are skipped by name.
 */
public class MemberCompatManifestTest {

    private static final MemberCompat OWN = MatchMemberCompat.MEMBER_COMPAT;

    static String committed(final MemberCompat version) throws IOException {
        try (InputStream in = MemberCompatManifestTest.class.getResourceAsStream(
                "/compat/" + MemberCompat.manifestFileName(version))) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void theBuildsManifestEqualsTheCommittedFileOfItsLevel() throws IOException {
        final String own = committed(OWN);
        assertEquals(List.of(), MemberCompat.manifestFailures(OWN, MatchMemberCompat.manifest(), own, null));
    }

    @Test
    public void againstThePreviousLevelTheBuildKeepsTheLevelRule() throws IOException {
        final MemberCompat previous = OWN.previousLevel();
        final String prev = previous == null ? null : committed(previous);
        Assume.assumeTrue("previous-level checks skipped: " + OWN + " is the genesis level (no manifest-"
                + previous + ".txt)", prev != null);
        assertEquals(List.of(), MemberCompat.manifestFailures(OWN, MatchMemberCompat.manifest(), committed(OWN), prev));
    }

    @Test
    public void theManifestNamesTheJarConstants() {
        final MemberCompat.Manifest m = MatchMemberCompat.manifest();
        assertEquals(SbeDemuxer.MIN_SUPPORTED_SCHEMA_VERSION, m.wireFloor());
        assertEquals(com.match.infrastructure.generated.MessageHeaderDecoder.SCHEMA_VERSION, m.wireTop());
        assertEquals(SnapshotCodec.LAYOUTS.current().version(), m.layoutWrittenAt(m.maxBehavior()));
    }

    /** Negative control: one extra behaviour at the old level fails the level rule by its text. */
    @Test
    public void negativeControlAnExtraBehaviourWithoutALevelBumpFails() throws IOException {
        final MemberCompat.Manifest b = MatchMemberCompat.manifest();
        final int[] more = java.util.Arrays.copyOf(b.behaviors(), b.behaviors().length + 1);
        more[more.length - 1] = b.maxBehavior() + 1;
        final MemberCompat.Manifest extra = rebuild(b, b.wireFloor(), more);
        final List<String> failures = MemberCompat.manifestFailures(OWN, extra, committed(OWN), null);
        assertEquals(failures.toString(), 1, failures.size());
        assertTrue(failures.get(0), failures.get(0).startsWith("capability manifest changed without a level bump"));
    }

    /** Negative control: a build whose wire floor moved fails the floor rule against the previous level. */
    @Test
    public void negativeControlAMovedWireFloorFailsTheFloorRule() throws IOException {
        final MemberCompat.Manifest b = MatchMemberCompat.manifest();
        final MemberCompat.Manifest moved = rebuild(b, b.wireFloor() + 1, b.behaviors());
        final MemberCompat next = MemberCompat.of(OWN.generation(), OWN.level() + 1);
        // the current file plays the previous level, the moved build the next one
        final List<String> failures = MemberCompat.manifestFailures(next, moved, moved.text(), committed(OWN));
        assertEquals(List.of("level rule: the wire floor moved from " + b.wireFloor() + " to " + (b.wireFloor() + 1)
                + " (the floor never moves within a generation)"), failures);
    }

    private static MemberCompat.Manifest rebuild(final MemberCompat.Manifest b, final int floor, final int[] behaviors) {
        final MemberCompat.Manifest.Builder r = MemberCompat.Manifest.builder().wire(floor, b.wireTop())
                .behaviors(behaviors);
        b.layoutSince().forEach(r::layout);
        b.journals().forEach((name, v) -> {
            final String[] parts = v.split(" ");
            r.journal(name, Integer.parseInt(parts[0].substring("schema=".length())),
                    Integer.parseInt(parts[1].substring("version=".length())));
        });
        return r.build();
    }
}
