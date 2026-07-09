// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.journal.generated.BooleanType;
import com.match.infrastructure.journal.generated.JournalTerminalDecoder;
import com.match.infrastructure.journal.generated.JournalTradeDecoder;
import com.match.infrastructure.journal.generated.MessageHeaderDecoder;
import com.match.infrastructure.journal.generated.TerminalStatus;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The settlement journal's ring half is a money-loss surface: these tests pin (1) field
 * fidelity through the SBE encode, (2) strict append order, (3) the block-never-shed
 * contract on a full ring, and (4) that journal bytes are a pure function of the append
 * sequence (per-replica determinism reduces to deterministic engine call order, which the
 * cluster guarantees).
 */
public class SettlementJournalTest {

    private static final class Drained {
        final List<Integer> msgTypes = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
    }

    private static Drained drainAll(final SettlementJournal journal) {
        final Drained out = new Drained();
        journal.ringBuffer().read((msgTypeId, buffer, index, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(index, copy);
            out.msgTypes.add(msgTypeId);
            out.payloads.add(copy);
        });
        return out;
    }

    @Test
    public void tradeAndTerminalRoundTripInAppendOrder() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);

        journal.appendTrade(1000L, 42L, 3, 11L, 100001L, 22L, 100002L,
                6_012_345_000L, 25_000_000L, true, 1_752_100_000_123L);
        journal.appendTerminal(1001L, 22L, 100002L, 3, 2 /* FILLED */, 1_752_100_000_124L);

        final Drained drained = drainAll(journal);
        assertEquals(2, drained.msgTypes.size());
        assertEquals(SettlementJournal.MSG_TYPE_TRADE, (int) drained.msgTypes.get(0));
        assertEquals(SettlementJournal.MSG_TYPE_TERMINAL, (int) drained.msgTypes.get(1));

        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final UnsafeBuffer buf0 = new UnsafeBuffer(drained.payloads.get(0));
        header.wrap(buf0, 0);
        assertEquals(JournalTradeDecoder.TEMPLATE_ID, header.templateId());
        final JournalTradeDecoder trade = new JournalTradeDecoder()
                .wrap(buf0, header.encodedLength(), header.blockLength(), header.version());
        assertEquals(1000L, trade.egressSeq());
        assertEquals(42L, trade.tradeId());
        assertEquals(3, trade.marketId());
        assertEquals(11L, trade.takerOrderId());
        assertEquals(100001L, trade.takerUserId());
        assertEquals(22L, trade.makerOrderId());
        assertEquals(100002L, trade.makerUserId());
        assertEquals(6_012_345_000L, trade.price());
        assertEquals(25_000_000L, trade.quantity());
        assertEquals(BooleanType.TRUE, trade.takerIsBuy());
        assertEquals(1_752_100_000_123L, trade.timestamp());

        final UnsafeBuffer buf1 = new UnsafeBuffer(drained.payloads.get(1));
        header.wrap(buf1, 0);
        assertEquals(JournalTerminalDecoder.TEMPLATE_ID, header.templateId());
        final JournalTerminalDecoder terminal = new JournalTerminalDecoder()
                .wrap(buf1, header.encodedLength(), header.blockLength(), header.version());
        assertEquals(1001L, terminal.egressSeq());
        assertEquals(22L, terminal.orderId());
        assertEquals(100002L, terminal.userId());
        assertEquals(3, terminal.marketId());
        assertEquals(TerminalStatus.FILLED, terminal.status());
        assertEquals(1_752_100_000_124L, terminal.timestamp());

        assertEquals(1L, journal.appendedTrades());
        assertEquals(1L, journal.appendedTerminals());
        assertEquals(0L, journal.backpressureEvents());
    }

    @Test
    public void journalBytesAreAPureFunctionOfTheAppendSequence() {
        final byte[] first = appendFixedSequenceAndDrain();
        final byte[] second = appendFixedSequenceAndDrain();
        assertArrayEquals("identical append sequences must journal identical bytes", first, second);
    }

    private static byte[] appendFixedSequenceAndDrain() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        for (int i = 0; i < 50; i++) {
            journal.appendTrade(100 + i, 1 + i, 1 + (i % 5), 10 + i, 900_000 + i,
                    20 + i, 900_100 + i, 1_000_000L * (1 + i), 50_000_000L, (i & 1) == 0, 1_000_000L + i);
            if (i % 5 == 0) {
                journal.appendTerminal(100 + i, 20 + i, 900_100 + i, 1 + (i % 5), 2 + (i % 3), 1_000_001L + i);
            }
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        journal.ringBuffer().read((msgTypeId, buffer, index, length) -> {
            bytes.write(msgTypeId);
            final byte[] copy = new byte[length];
            buffer.getBytes(index, copy);
            bytes.writeBytes(copy);
        }, Integer.MAX_VALUE);
        return bytes.toByteArray();
    }

    @Test
    public void fullRingBlocksUntilDrainedAndCountsOneBackpressureEvent() throws Exception {
        // Smallest legal ring: fill it, prove the next append BLOCKS (never sheds), then
        // prove it completes once a consumer drains, with exactly one backpressure event.
        final SettlementJournal journal = new SettlementJournal(4096);

        // Fill by raw ring writes (same record size a trade encodes to) until a write is
        // REFUSED — the ring is then deterministically too full for one more trade record.
        final UnsafeBuffer filler = new UnsafeBuffer(new byte[93]);
        while (journal.ringBuffer().write(SettlementJournal.MSG_TYPE_TRADE, filler, 0, filler.capacity())) {
            // keep filling
        }
        assertEquals(0L, journal.backpressureEvents());

        final CountDownLatch blockedAppendDone = new CountDownLatch(1);
        final Thread appender = new Thread(() -> {
            journal.appendTrade(9999, 9999, 1, 1, 1, 2, 2, 100, 100, false, 9999);
            blockedAppendDone.countDown();
        }, "test-blocked-appender");
        appender.start();

        // The append must be blocked (ring full), not dropped and not completed.
        assertFalse("append on a full ring must block, not return",
                blockedAppendDone.await(300, TimeUnit.MILLISECONDS));
        assertEquals(1L, journal.backpressureEvents());

        // Drain a few entries -> the blocked append must complete.
        final int[] drained = {0};
        while (drained[0] < 4) {
            journal.ringBuffer().read((int msgTypeId, MutableDirectBuffer b, int i, int l) -> drained[0]++, 4);
        }
        assertTrue("append must complete once the ring drains",
                blockedAppendDone.await(5, TimeUnit.SECONDS));
        appender.join(5_000);
        assertEquals(1L, journal.backpressureEvents());
    }
}
