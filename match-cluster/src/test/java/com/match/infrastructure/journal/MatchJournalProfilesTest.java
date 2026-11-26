// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.EngineConfigDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
import com.openexchange.cluster.journal.JournalProfile;
import com.openexchange.cluster.journal.JournalReaders;
import com.openexchange.cluster.journal.JournalRetention;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The settlement journal's profile against the codec: the kit reads {@code journalSeq}, {@code meEpoch} and
 * {@code egressSeq} of a real record of every template where the generated decoder reads them, and the boot check
 * refuses a mode that could never act.
 */
public class MatchJournalProfilesTest {

    private static final JournalProfile P = MatchJournalProfiles.SETTLEMENT;

    @Test
    public void theProfileIsTheSettlementJournalOfSchemaFour() {
        assertEquals("settlement", P.journal());
        assertEquals(JournalReaders.MATCH_SETTLEMENT, MatchJournalProfiles.SETTLEMENT_NAME);
        assertEquals(java.util.Set.of("settlement"), JournalReaders.journalsOf(JournalReaders.ENGINE_MATCH));
        assertEquals(InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID, P.streamId());
        assertEquals(4001, P.streamId());
        assertEquals("aeron:ipc", P.channelFragment());
        assertEquals(4, P.schemaId());
        assertTrue("every record carries the ME epoch", P.hasEpoch());
        assertTrue("the engine never journals before its genesis: epoch 0 at a recording's start is another epoch",
                P.startEpochStrict());
        // the largest record (a 48-entry CommandRelease, 1112 bytes) plus the 32-byte data header, frame aligned
        assertEquals(1152, P.maxFrameLength());
        assertEquals(List.of(P), MatchJournalProfiles.ALL);
    }

    /** The common head of every template is where the profile reads: a schema change moves both or fails here. */
    @Test
    public void everyTemplateCarriesTheKeyWhereTheProfileReadsIt() {
        final int[][] heads = {
            {TradeDecoder.journalSeqEncodingOffset(), TradeDecoder.meEpochEncodingOffset(), TradeDecoder.egressSeqEncodingOffset()},
            {TerminalDecoder.journalSeqEncodingOffset(), TerminalDecoder.meEpochEncodingOffset(), TerminalDecoder.egressSeqEncodingOffset()},
            {CommandOutcomeDecoder.journalSeqEncodingOffset(), CommandOutcomeDecoder.meEpochEncodingOffset(), CommandOutcomeDecoder.egressSeqEncodingOffset()},
            {CommandReleaseDecoder.journalSeqEncodingOffset(), CommandReleaseDecoder.meEpochEncodingOffset(), CommandReleaseDecoder.egressSeqEncodingOffset()},
            {EngineConfigDecoder.journalSeqEncodingOffset(), EngineConfigDecoder.meEpochEncodingOffset(), EngineConfigDecoder.egressSeqEncodingOffset()},
        };
        for (final int[] head : heads) {
            assertEquals(TradeDecoder.journalSeqEncodingOffset(), head[0]);
            assertEquals(TradeDecoder.meEpochEncodingOffset(), head[1]);
            assertEquals(TradeDecoder.egressSeqEncodingOffset(), head[2]);
        }
        assertEquals(5, heads.length);
    }

    /** Records written by the production encoder, read back by the kit's profile, template by template. */
    @Test
    public void theKitReadsTheKeyOfARealRecordOfEveryTemplate() throws Exception {
        final long epoch = 0x5EED_0000_0011L;
        final com.match.application.engine.MeIdentity identity =
                com.match.application.engine.MeIdentityTestAccess.restored(epoch, 20L, 2L);
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.bindIdentity(identity);
        journal.appendTrade(11L, 5_000L, 1L, 1, 11L, 900_001L, 22L, 900_002L, 1_000L, 2_000L, true, 43L, 7_011L, 7_022L);
        journal.appendTerminal(12L, 5_100L, 11L, 900_001L, 1, 2, 42L, 7_011L, 0);
        final Map<Integer, long[]> seen = new HashMap<>();
        final List<Integer> lengths = new ArrayList<>();
        journal.ringBuffer().read((type, buffer, index, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(index, copy);
            try {
                final JournalProfile.Key key = P.key(new UnsafeBuffer(copy), 0, length, length + 32);
                seen.put(key.templateId(), new long[] {key.journalSeq(), key.epoch(), key.logPosition()});
                lengths.add(length);
            } catch (final JournalProfile.NotARecord notARecord) {
                throw new AssertionError(notARecord.getMessage());
            }
        });
        assertEquals(2, seen.size());
        final long[] trade = seen.get(TradeDecoder.TEMPLATE_ID);
        assertEquals(11L, trade[0]);
        assertEquals(epoch, trade[1]);
        assertEquals("the log position is the record's egressSeq", 5_000L, trade[2]);
        final long[] terminal = seen.get(TerminalDecoder.TEMPLATE_ID);
        assertEquals(12L, terminal[0]);
        assertEquals(epoch, terminal[1]);
        assertEquals(5_100L, terminal[2]);
        // the other three templates: the same head by the layout row above, and the profile lists each for the
        // log position (a template it did not list would read as "no log position" and never pass an off-box floor)
        for (final int template : new int[] {CommandOutcomeDecoder.TEMPLATE_ID, CommandReleaseDecoder.TEMPLATE_ID,
            EngineConfigDecoder.TEMPLATE_ID}) {
            final UnsafeBuffer b = new UnsafeBuffer(new byte[64]);
            new com.match.infrastructure.mejournal.generated.MessageHeaderEncoder().wrap(b, 0).blockLength(44)
                    .templateId(template).schemaId(4).version(1);
            b.putLong(MessageHeaderDecoder.ENCODED_LENGTH, 7L, java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putLong(MessageHeaderDecoder.ENCODED_LENGTH + 8, epoch, java.nio.ByteOrder.LITTLE_ENDIAN);
            b.putLong(MessageHeaderDecoder.ENCODED_LENGTH + 16, 9_000L, java.nio.ByteOrder.LITTLE_ENDIAN);
            final JournalProfile.Key key = P.key(b, 0, 52, 84);
            assertEquals(7L, key.journalSeq());
            assertEquals(epoch, key.epoch());
            assertEquals("template " + template + " carries its log position", 9_000L, key.logPosition());
        }
        // a frame of another schema on the stream is not a record
        final UnsafeBuffer other = new UnsafeBuffer(new byte[64]);
        new com.match.infrastructure.mejournal.generated.MessageHeaderEncoder().wrap(other, 0).blockLength(44)
                .templateId(1).schemaId(3).version(1);
        try {
            P.key(other, 0, 52, 84);
            fail("schema 3 was read as a settlement journal record");
        } catch (final JournalProfile.NotARecord expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("schema id 3, the journal's is 4"));
        }
    }

    private static JournalRetention.Settings settings(final String mode) {
        return JournalRetention.Settings.parse((env, prop) -> "JOURNAL_RETENTION_SETTLEMENT".equals(env) ? mode : null,
                MatchJournalProfiles.SETTLEMENT_NAME);
    }

    /** One rule for every mode: a mode other than off needs a journal; a value that is no mode refuses the start. */
    @Test
    public void theBootCheckRefusesAModeThatCouldNeverAct() {
        assertEquals(JournalRetention.Mode.OFF, MatchJournalProfiles.check(settings(null), false).mode("settlement"));
        assertEquals(JournalRetention.Mode.OFF, MatchJournalProfiles.check(settings("off"), false).mode("settlement"));
        for (final String mode : new String[] {"report", "readers"}) {
            assertFalse(MatchJournalProfiles.check(settings(mode), true).mode("settlement") == JournalRetention.Mode.OFF);
            try {
                MatchJournalProfiles.check(settings(mode), false);
                fail(mode + " without a journal was accepted");
            } catch (final IllegalArgumentException refused) {
                assertTrue(refused.getMessage(), refused.getMessage().contains("JOURNAL_RETENTION_SETTLEMENT=" + mode
                        + " on a member that writes no settlement journal"));
            }
        }
        for (final boolean journal : new boolean[] {true, false}) {
            try {
                MatchJournalProfiles.check(settings("purge"), journal);
                fail("'purge' was accepted");
            } catch (final IllegalArgumentException refused) {
                assertTrue(refused.getMessage(), refused.getMessage().contains(
                        "JOURNAL_RETENTION_SETTLEMENT must be off, report or readers, got 'purge'"));
            }
        }
    }
}
