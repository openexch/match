// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.mejournal.generated.CommandOutcomeEncoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseEncoder;
import com.match.infrastructure.mejournal.generated.EngineConfigEncoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderEncoder;
import com.match.infrastructure.mejournal.generated.TerminalEncoder;
import com.match.infrastructure.mejournal.generated.TradeEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * ME journal schema 4 layout pinned against the RC2 S1 contract section 2 table: every block
 * length and every field offset, the schema identity, and the 1112 B frame bound that keeps every
 * record inside one unfragmented {@code aeron:ipc} frame (MTU 1408, payload 1376).
 */
public class MeJournalSchemaLayoutTest {

    @Test
    public void schemaIdentity() {
        assertEquals(4, MessageHeaderEncoder.SCHEMA_ID);
        assertEquals(1, MessageHeaderEncoder.SCHEMA_VERSION);
        assertEquals(4, MessageHeaderDecoder.SCHEMA_ID);
        assertEquals(1, MessageHeaderDecoder.SCHEMA_VERSION);
        assertEquals(8, MessageHeaderEncoder.ENCODED_LENGTH);
    }

    @Test
    public void templateIds() {
        assertEquals(1, TradeEncoder.TEMPLATE_ID);
        assertEquals(2, TerminalEncoder.TEMPLATE_ID);
        assertEquals(3, CommandOutcomeEncoder.TEMPLATE_ID);
        assertEquals(4, CommandReleaseEncoder.TEMPLATE_ID);
        assertEquals(5, EngineConfigEncoder.TEMPLATE_ID);
        assertEquals(SettlementJournal.MSG_TYPE_TRADE, TradeEncoder.TEMPLATE_ID);
        assertEquals(SettlementJournal.MSG_TYPE_TERMINAL, TerminalEncoder.TEMPLATE_ID);
        assertEquals(SettlementJournal.MSG_TYPE_OUTCOME, CommandOutcomeEncoder.TEMPLATE_ID);
        assertEquals(SettlementJournal.MSG_TYPE_RELEASE, CommandReleaseEncoder.TEMPLATE_ID);
        assertEquals(SettlementJournal.MSG_TYPE_CONFIG, EngineConfigEncoder.TEMPLATE_ID);
    }

    @Test
    public void commonHeadAtFixedOffsetsOnEveryRecord() {
        final int[][] heads = {
            {TradeEncoder.journalSeqEncodingOffset(), TradeEncoder.meEpochEncodingOffset(), TradeEncoder.egressSeqEncodingOffset()},
            {TerminalEncoder.journalSeqEncodingOffset(), TerminalEncoder.meEpochEncodingOffset(), TerminalEncoder.egressSeqEncodingOffset()},
            {CommandOutcomeEncoder.journalSeqEncodingOffset(), CommandOutcomeEncoder.meEpochEncodingOffset(), CommandOutcomeEncoder.egressSeqEncodingOffset()},
            {CommandReleaseEncoder.journalSeqEncodingOffset(), CommandReleaseEncoder.meEpochEncodingOffset(), CommandReleaseEncoder.egressSeqEncodingOffset()},
            {EngineConfigEncoder.journalSeqEncodingOffset(), EngineConfigEncoder.meEpochEncodingOffset(), EngineConfigEncoder.egressSeqEncodingOffset()},
        };
        for (final int[] h : heads) {
            assertEquals(0, h[0]);
            assertEquals(8, h[1]);
            assertEquals(16, h[2]);
        }
        // The writer's retention checkpoint reads egressSeq at this frame offset on every record.
        assertEquals(8 + 16, JournalWriterAgent.EGRESS_SEQ_OFFSET);
    }

    @Test
    public void tradeLayout() {
        assertEquals(109, TradeEncoder.BLOCK_LENGTH);
        assertEquals(24, TradeEncoder.tradeIdEncodingOffset());
        assertEquals(32, TradeEncoder.marketIdEncodingOffset());
        assertEquals(36, TradeEncoder.takerOrderIdEncodingOffset());
        assertEquals(44, TradeEncoder.takerUserIdEncodingOffset());
        assertEquals(52, TradeEncoder.makerOrderIdEncodingOffset());
        assertEquals(60, TradeEncoder.makerUserIdEncodingOffset());
        assertEquals(68, TradeEncoder.priceEncodingOffset());
        assertEquals(76, TradeEncoder.quantityEncodingOffset());
        assertEquals(84, TradeEncoder.takerIsBuyEncodingOffset());
        assertEquals(85, TradeEncoder.timestampEncodingOffset());
        assertEquals(93, TradeEncoder.takerOmsOrderIdEncodingOffset());
        assertEquals(101, TradeEncoder.makerOmsOrderIdEncodingOffset());
    }

    @Test
    public void terminalLayout() {
        assertEquals(62, TerminalEncoder.BLOCK_LENGTH);
        assertEquals(24, TerminalEncoder.orderIdEncodingOffset());
        assertEquals(32, TerminalEncoder.userIdEncodingOffset());
        assertEquals(40, TerminalEncoder.marketIdEncodingOffset());
        assertEquals(44, TerminalEncoder.statusEncodingOffset());
        assertEquals(45, TerminalEncoder.timestampEncodingOffset());
        assertEquals(53, TerminalEncoder.omsOrderIdEncodingOffset());
        assertEquals(61, TerminalEncoder.rejectReasonEncodingOffset());
        assertEquals(255, TerminalEncoder.rejectReasonNullValue());
    }

    @Test
    public void commandOutcomeLayout() {
        assertEquals(153, CommandOutcomeEncoder.BLOCK_LENGTH);
        assertEquals(24, CommandOutcomeEncoder.commandIdHighEncodingOffset());
        assertEquals(32, CommandOutcomeEncoder.commandIdLowEncodingOffset());
        assertEquals(40, CommandOutcomeEncoder.userIdEncodingOffset());
        assertEquals(48, CommandOutcomeEncoder.omsOrderIdEncodingOffset());
        assertEquals(56, CommandOutcomeEncoder.oldOrderIdEncodingOffset());
        assertEquals(64, CommandOutcomeEncoder.priceEncodingOffset());
        assertEquals(72, CommandOutcomeEncoder.quantityEncodingOffset());
        assertEquals(80, CommandOutcomeEncoder.budgetEncodingOffset());
        assertEquals(88, CommandOutcomeEncoder.marketIdEncodingOffset());
        assertEquals(92, CommandOutcomeEncoder.commandKindEncodingOffset());
        assertEquals(93, CommandOutcomeEncoder.orderTypeEncodingOffset());
        assertEquals(94, CommandOutcomeEncoder.orderSideEncodingOffset());
        assertEquals(95, CommandOutcomeEncoder.appliedPositionEncodingOffset());
        assertEquals(103, CommandOutcomeEncoder.timestampEncodingOffset());
        assertEquals(111, CommandOutcomeEncoder.orderIdEncodingOffset());
        assertEquals(119, CommandOutcomeEncoder.statusEncodingOffset());
        assertEquals(123, CommandOutcomeEncoder.reasonEncodingOffset());
        assertEquals(127, CommandOutcomeEncoder.oldCancelledEncodingOffset());
        assertEquals(128, CommandOutcomeEncoder.resultEncodingOffset());
        assertEquals(132, CommandOutcomeEncoder.timeInForceEncodingOffset());
        assertEquals(133, CommandOutcomeEncoder.expectedOldRemainingEncodingOffset());
        assertEquals(141, CommandOutcomeEncoder.ledgerSizeEncodingOffset());
        assertEquals(145, CommandOutcomeEncoder.ledgerCapacityEncodingOffset());
        assertEquals(149, CommandOutcomeEncoder.behaviorVersionEncodingOffset());
    }

    @Test
    public void commandReleaseLayout() {
        assertEquals(44, CommandReleaseEncoder.BLOCK_LENGTH);
        assertEquals(24, CommandReleaseEncoder.releaseSeqEncodingOffset());
        assertEquals(32, CommandReleaseEncoder.ledgerSizeEncodingOffset());
        assertEquals(36, CommandReleaseEncoder.ledgerCapacityEncodingOffset());
        assertEquals(40, CommandReleaseEncoder.tombstoneRangesEncodingOffset());
        assertEquals(10, CommandReleaseEncoder.releasesId());
        assertEquals(4, CommandReleaseEncoder.ReleasesEncoder.sbeHeaderSize());
        assertEquals(22, CommandReleaseEncoder.ReleasesEncoder.sbeBlockLength());
        assertEquals(0, CommandReleaseEncoder.ReleasesEncoder.commandIdHighEncodingOffset());
        assertEquals(8, CommandReleaseEncoder.ReleasesEncoder.idLowFloorEncodingOffset());
        assertEquals(16, CommandReleaseEncoder.ReleasesEncoder.scopeEncodingOffset());
        assertEquals(17, CommandReleaseEncoder.ReleasesEncoder.resultEncodingOffset());
        assertEquals(18, CommandReleaseEncoder.ReleasesEncoder.removedEncodingOffset());
    }

    @Test
    public void engineConfigLayout() {
        assertEquals(39, EngineConfigEncoder.BLOCK_LENGTH);
        assertEquals(24, EngineConfigEncoder.configVersionEncodingOffset());
        assertEquals(28, EngineConfigEncoder.behaviorVersionEncodingOffset());
        assertEquals(32, EngineConfigEncoder.ledgerCapacityEncodingOffset());
        assertEquals(36, EngineConfigEncoder.maxWireVersionEncodingOffset());
        assertEquals(38, EngineConfigEncoder.decisionEncodingOffset());
    }

    @Test
    public void maxFrameIs1112AndFitsOneIpcFrame() {
        assertEquals(1112, SettlementJournal.MAX_FRAME_LENGTH);
        assertTrue("1408 MTU - 32 B data header = 1376 B payload", SettlementJournal.MAX_FRAME_LENGTH <= 1408 - 32);
    }

    @Test
    public void allFiveRecordsRoundTripThroughTheWriterIncludingA48EntryRelease() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendTrade(0L, 1L, 2L, 3, 4L, 5L, 6L, 7L, 8L, 9L, false, 10L, 11L, 12L);
        journal.appendTerminal(0L, 1L, 4L, 5L, 3, 3, 10L, 11L, 0);
        journal.appendCommandOutcome(0L, 1L, new com.match.domain.commands.DurableCommandOutcome(
                new com.match.domain.commands.DurableOrderIntent(1L, 2L, 3L, 4L, 0L, 5L, 6L, 0L, 7, 0, 0, 0),
                1L, 2L, 3L, 0, 0, false, 0), 1);
        final int n = SettlementJournal.MAX_RELEASE_ENTRIES;
        journal.appendCommandRelease(0L, 1L, 2L, 0, 100_000, 0, n,
                new long[n], new long[n], new int[n], new int[n], new int[n]);
        journal.appendEngineConfig(0L, 1L, 1L, 1L, 100_000L, 12, 0);

        final List<byte[]> frames = new ArrayList<>();
        journal.ringBuffer().read((t, b, i, l) -> {
            final byte[] v = new byte[l];
            b.getBytes(i, v);
            frames.add(v);
        }, 16);
        assertEquals(5, frames.size());
        final int[] lengths = {117, 70, 161, 1112, 47};
        for (int k = 0; k < 5; k++) {
            final UnsafeBuffer buf = new UnsafeBuffer(frames.get(k));
            final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(buf, 0);
            assertEquals(4, h.schemaId());
            assertEquals(1, h.version());
            assertEquals(k + 1, h.templateId());
            assertEquals(lengths[k], frames.get(k).length);
        }
        final UnsafeBuffer release = new UnsafeBuffer(frames.get(3));
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(release, 0);
        assertEquals(n, new CommandReleaseDecoder().wrap(release, 8, h.blockLength(), h.version()).releases().count());
    }

    @Test
    public void aFortyNineEntryReleaseIsRefused() {
        final int n = SettlementJournal.MAX_RELEASE_ENTRIES + 1;
        try {
            new SettlementJournal(1 << 16).appendCommandRelease(0L, 1L, 2L, 0, 100_000, 0, n,
                    new long[n], new long[n], new int[n], new int[n], new int[n]);
            fail("49 entries must be refused");
        } catch (final IllegalArgumentException expected) {
            // the frame would be 1134 B; the cap keeps it at 1112
        }
    }
}
