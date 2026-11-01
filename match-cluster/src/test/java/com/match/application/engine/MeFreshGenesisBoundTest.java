// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.config;
import static com.match.application.engine.EngineConfigStateMachine.Decision.FRESH;
import static com.match.application.engine.EngineConfigStateMachine.Decision.REJECTED_INVALID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RCS S3 amendment A7 (ME half): an EngineConfig FRESH at behaviour {@code >= 2} binds {@code meEpoch := genesisId},
 * which becomes the shadow identity (RCS S3 H10, alias {@code g<genesisId>}, RC2 S1 BLOCKER-3), so the nonce must lie
 * in {@code [1, 2^63 - 1]}. Row 0 ({@code EngineConfigState.invalidReason}) refuses anything else before an engine is
 * created, in every state; {@code MeIdentity.bindFresh} would only throw after the engines exist (a latch, not a
 * refusal). Every value is driven through the real state machine; a refusal must be REJECTED_INVALID with nothing
 * created, recorded, bound or journaled, and no exception.
 */
public class MeFreshGenesisBoundTest {

    static final long[] REFUSED = {0L, -1L, -42L, Long.MIN_VALUE, Long.MIN_VALUE + 1};
    static final long[] ACCEPTED = {1L, 2L, 0x5EEDL, Long.MAX_VALUE};

    /** The decisions a FRESH at each value gets, with any violation of the refusal contract named. */
    static List<String> violations() {
        final List<String> out = new ArrayList<>();
        for (final long g : REFUSED) {
            try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
                final EngineConfigStateMachine.Decision d;
                try {
                    d = h.apply(config(1, 2, g, 12, 50_000), 64L);
                } catch (final RuntimeException e) {
                    out.add("FRESH with genesisId " + g + " was not refused at row 0: " + e);
                    continue;
                }
                if (d != REJECTED_INVALID || h.engine.hasEngines() || h.engine.getEngineConfig() != null
                        || h.engine.meIdentity().isBound() || !h.drain().isEmpty()) {
                    out.add("FRESH with genesisId " + g + " was not refused at row 0: " + d + " engines="
                            + h.engine.hasEngines() + " bound=" + h.engine.meIdentity().isBound());
                }
            }
        }
        for (final long g : ACCEPTED) {
            try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
                final EngineConfigStateMachine.Decision d = h.apply(config(1, 2, g, 12, 50_000), 64L);
                if (d != FRESH || h.engine.meIdentity().meEpoch() != g) {
                    out.add("FRESH with genesisId " + g + " should bind it: " + d);
                }
            }
        }
        return out;
    }

    @Test
    public void freshAtBehaviourTwoAcceptsOnlyAPositiveNonce() {
        final List<String> v = violations();
        assertTrue(String.join("\n", v), v.isEmpty());
        assertEquals(0, v.size());
        System.out.println("GREEN A7 ME: FRESH refuses genesisId " + java.util.Arrays.toString(REFUSED)
                + " at row 0, binds " + java.util.Arrays.toString(ACCEPTED));
    }
}
