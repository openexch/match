// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * RC2 S1 review (independent verifier, check 2): the behaviour-free rule says every journal byte
 * of an existing scenario changes ONLY through the schema 4 header and the fields the contract
 * adds. This pins it directly: the same inputs encoded with the frozen schema 3 codecs
 * ({@code JournalTrade} 1, {@code JournalTerminal} 2) and the schema 1 template 28 codec are
 * byte-identical to the schema 4 frame once the 16 B common head ({@code journalSeq},
 * {@code meEpoch}) is skipped and the appended tail ({@code rejectReason}; the record 3 tail) is
 * excluded. The common {@code egressSeq} sits at frame offset 8 on schema 3/1 and at 24 on
 * schema 4, which is exactly the {@link JournalWriterAgent#EGRESS_SEQ_OFFSET} move.
 */
public class SchemaFourPreservesSchemaThreeBytesTest {

    private static final int HEADER = 8;
    private static final int COMMON_HEAD = 16; // journalSeq @0, meEpoch @8 (new); egressSeq @16 (moved)

    @Test
    public void tradeBodyBytesAreTheSchemaThreeBodyBytes() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendTrade(0L, 1000L, 42L, 3, 11L, 100001L, 22L, 100002L,
                6_012_345_000L, 25_000_000L, true, 1_752_100_000_123L, 9_011L, 9_022L);
        final byte[] v4 = drain(journal).get(0);

        final UnsafeBuffer v3 = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        final com.match.infrastructure.journal.generated.JournalTradeEncoder old =
                new com.match.infrastructure.journal.generated.JournalTradeEncoder();
        old.wrapAndApplyHeader(v3, 0, new com.match.infrastructure.journal.generated.MessageHeaderEncoder())
                .egressSeq(1000L).tradeId(42L).marketId(3).takerOrderId(11L).takerUserId(100001L)
                .makerOrderId(22L).makerUserId(100002L).price(6_012_345_000L).quantity(25_000_000L)
                .takerIsBuy(com.match.infrastructure.journal.generated.BooleanType.TRUE)
                .timestamp(1_752_100_000_123L).takerOmsOrderId(9_011L).makerOmsOrderId(9_022L);
        final int oldBlock = com.match.infrastructure.journal.generated.JournalTradeEncoder.BLOCK_LENGTH;
        assertEquals(93, oldBlock);
        assertEquals(HEADER + COMMON_HEAD + oldBlock, v4.length);
        assertArrayEquals(slice(v3, HEADER, oldBlock), Arrays.copyOfRange(v4, HEADER + COMMON_HEAD, v4.length));
        assertEquals(0L, new UnsafeBuffer(v4).getLong(HEADER));      // journalSeq placeholder
        assertEquals(0L, new UnsafeBuffer(v4).getLong(HEADER + 8));  // meEpoch placeholder
        assertEquals(1000L, new UnsafeBuffer(v4).getLong(JournalWriterAgent.EGRESS_SEQ_OFFSET));
    }

    @Test
    public void terminalBodyBytesAreTheSchemaThreeBodyBytesPlusRejectReason() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendTerminal(0L, 1001L, 22L, 100002L, 3, 4 /* REJECTED */, 1_752_100_000_124L, 9_022L, 7);
        final byte[] v4 = drain(journal).get(0);

        final UnsafeBuffer v3 = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        new com.match.infrastructure.journal.generated.JournalTerminalEncoder()
                .wrapAndApplyHeader(v3, 0, new com.match.infrastructure.journal.generated.MessageHeaderEncoder())
                .egressSeq(1001L).orderId(22L).userId(100002L).marketId(3)
                .status(com.match.infrastructure.journal.generated.TerminalStatus.REJECTED)
                .timestamp(1_752_100_000_124L).omsOrderId(9_022L);
        final int oldBlock = com.match.infrastructure.journal.generated.JournalTerminalEncoder.BLOCK_LENGTH;
        assertEquals(45, oldBlock);
        assertEquals(HEADER + COMMON_HEAD + oldBlock + 1, v4.length);
        assertArrayEquals(slice(v3, HEADER, oldBlock),
                Arrays.copyOfRange(v4, HEADER + COMMON_HEAD, HEADER + COMMON_HEAD + oldBlock));
        assertEquals("rejectReason is the one appended byte", 7, v4[v4.length - 1] & 0xFF);
        assertEquals(1001L, new UnsafeBuffer(v4).getLong(JournalWriterAgent.EGRESS_SEQ_OFFSET));
    }

    @Test
    public void commandOutcomeBodyBytesAreTheTemplate28BodyBytesPlusTheTail() {
        final DurableOrderIntent intent = new DurableOrderIntent(17L, 99L, 100L, 9001L, 55L,
                123_000L, 7_000L, 0L, 1, 2 /* UPDATE */, 0, 1);
        final DurableCommandOutcome outcome = new DurableCommandOutcome(intent, 4_096L, 1_700L,
                56L, 1, 0, true, DurableCommandOutcome.APPLIED);
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendCommandOutcome(0L, 8_192L, outcome, 3);
        final byte[] v4 = drain(journal).get(0);

        final UnsafeBuffer v1 = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        new com.match.infrastructure.generated.JournalCommandOutcomeEncoder()
                .wrapAndApplyHeader(v1, 0, new com.match.infrastructure.generated.MessageHeaderEncoder())
                .egressSeq(8_192L)
                .commandIdHigh(17L).commandIdLow(99L).userId(100L).omsOrderId(9001L).oldOrderId(55L)
                .price(123_000L).quantity(7_000L).budget(0L).marketId(1)
                .commandKind((short) 2).orderType((short) 0).orderSide((short) 1)
                .appliedPosition(4_096L).timestamp(1_700L).orderId(56L).status(1).reason(0)
                .oldCancelled((short) 1).result(DurableCommandOutcome.APPLIED);
        final int oldBlock = com.match.infrastructure.generated.JournalCommandOutcomeEncoder.BLOCK_LENGTH;
        assertEquals(116, oldBlock);
        final int tail = 1 + 8 + 4 + 4 + 4; // timeInForce, expectedOldRemaining, ledgerSize, ledgerCapacity, behaviorVersion
        assertEquals(HEADER + COMMON_HEAD + oldBlock + tail, v4.length);
        assertArrayEquals(slice(v1, HEADER, oldBlock),
                Arrays.copyOfRange(v4, HEADER + COMMON_HEAD, HEADER + COMMON_HEAD + oldBlock));
        assertEquals(8_192L, new UnsafeBuffer(v4).getLong(JournalWriterAgent.EGRESS_SEQ_OFFSET));
    }

    private static byte[] slice(final UnsafeBuffer buffer, final int offset, final int length) {
        final byte[] out = new byte[length];
        buffer.getBytes(offset, out);
        return out;
    }

    private static List<byte[]> drain(final SettlementJournal journal) {
        final List<byte[]> frames = new ArrayList<>();
        journal.ringBuffer().read((msgTypeId, buffer, index, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(index, copy);
            frames.add(copy);
        }, Integer.MAX_VALUE);
        return frames;
    }
}
