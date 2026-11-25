// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import org.junit.Test;

import java.util.HexFormat;

import static org.junit.Assert.assertEquals;

/**
 * The corpus's one foreign frame keeps its bytes: the hex below is what the retired settlement journal codec
 * wrote for the same values (and what the checked-in E1 fixtures carry), so the hand-written frame cannot drift.
 */
public class MeJournalCorpusSchema3FrameTest {

    static final String CODEC_BYTES = "5d00010003000100e8030000000000002a00000000000000010000000b00000000000000c80000000000"
            + "0000160000000000000064000000000000000060defb7405000000e1f50500000000017b514cf197010000020000000000000001000"
            + "00000000000";

    @Test
    public void theSchema3FrameHasTheCodecBytes() {
        assertEquals(CODEC_BYTES, HexFormat.of().formatHex(MeJournalCorpusCapture.schema3Trade()));
    }
}
