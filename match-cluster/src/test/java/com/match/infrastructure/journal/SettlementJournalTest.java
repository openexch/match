// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import com.match.infrastructure.mejournal.generated.BooleanType;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.ConfigDecision;
import com.match.infrastructure.mejournal.generated.EngineConfigDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.ReleaseResult;
import com.match.infrastructure.mejournal.generated.ReleaseScope;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import com.match.infrastructure.mejournal.generated.TerminalStatus;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
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
                6_012_345_000L, 25_000_000L, true, 1_752_100_000_123L, 9_011L, 9_022L);
        journal.appendTerminal(1001L, 22L, 100002L, 3, 2 /* FILLED */, 1_752_100_000_124L, 9_022L, 0);

        final Drained drained = drainAll(journal);
        assertEquals(2, drained.msgTypes.size());
        assertEquals(SettlementJournal.MSG_TYPE_TRADE, (int) drained.msgTypes.get(0));
        assertEquals(SettlementJournal.MSG_TYPE_TERMINAL, (int) drained.msgTypes.get(1));

        final MessageHeaderDecoder header = new MessageHeaderDecoder();
        final UnsafeBuffer buf0 = new UnsafeBuffer(drained.payloads.get(0));
        header.wrap(buf0, 0);
        assertEquals(4, header.schemaId());
        assertEquals(1, header.version());
        assertEquals(TradeDecoder.TEMPLATE_ID, header.templateId());
        assertEquals(117, drained.payloads.get(0).length);
        final TradeDecoder trade = new TradeDecoder()
                .wrap(buf0, header.encodedLength(), header.blockLength(), header.version());
        assertEquals(0L, trade.journalSeq());
        assertEquals(0L, trade.meEpoch());
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
        assertEquals(9_011L, trade.takerOmsOrderId());
        assertEquals(9_022L, trade.makerOmsOrderId());

        final UnsafeBuffer buf1 = new UnsafeBuffer(drained.payloads.get(1));
        header.wrap(buf1, 0);
        assertEquals(4, header.schemaId());
        assertEquals(TerminalDecoder.TEMPLATE_ID, header.templateId());
        assertEquals(70, drained.payloads.get(1).length);
        final TerminalDecoder terminal = new TerminalDecoder()
                .wrap(buf1, header.encodedLength(), header.blockLength(), header.version());
        assertEquals(0L, terminal.journalSeq());
        assertEquals(0L, terminal.meEpoch());
        assertEquals(1001L, terminal.egressSeq());
        assertEquals(22L, terminal.orderId());
        assertEquals(100002L, terminal.userId());
        assertEquals(3, terminal.marketId());
        assertEquals(TerminalStatus.FILLED, terminal.status());
        assertEquals(1_752_100_000_124L, terminal.timestamp());
        assertEquals(9_022L, terminal.omsOrderId());
        assertEquals(0, terminal.rejectReason());

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
                    20 + i, 900_100 + i, 1_000_000L * (1 + i), 50_000_000L, (i & 1) == 0, 1_000_000L + i,
                    5_000L + i, 6_000L + i);
            if (i % 5 == 0) {
                journal.appendTerminal(100 + i, 20 + i, 900_100 + i, 1 + (i % 5), 2 + (i % 3), 1_000_001L + i, 6_000L + i,
                        (i % 3) == 2 ? 6 : 0);
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
    public void rejectedTerminalCarriesTheMeReasonVerbatim() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendTerminal(7L, 8L, 9L, 1, 4 /* REJECTED */, 10L, 11L, 7 /* MATCH_LIMIT */);
        final byte[] frame = drainAll(journal).payloads.get(0);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(new UnsafeBuffer(frame), 0);
        final TerminalDecoder t = new TerminalDecoder()
                .wrap(new UnsafeBuffer(frame), 8, header.blockLength(), header.version());
        assertEquals(TerminalStatus.REJECTED, t.status());
        assertEquals(7, t.rejectReason());
    }

    @Test(expected = IllegalArgumentException.class)
    public void terminalRejectReason255IsNeverWritten() {
        new SettlementJournal(1 << 16).appendTerminal(7L, 8L, 9L, 1, 4, 10L, 11L, 255);
    }

    @Test
    public void commandOutcomeRecordThreeRoundTripsWithItsTail() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        final DurableOrderIntent intent = new DurableOrderIntent(17L, 99L, 100L, 9001L, 55L,
                123_000L, 7_000L, 0L, 1, 2 /* UPDATE */, 0, 1, 2 /* FOK */, 5_000L);
        final DurableCommandOutcome outcome = new DurableCommandOutcome(intent, 4_096L, 1_700L,
                56L, 1, 0, true, DurableCommandOutcome.APPLIED);
        journal.appendCommandOutcome(8_192L, outcome, 3);

        final Drained drained = drainAll(journal);
        assertEquals(SettlementJournal.MSG_TYPE_OUTCOME, (int) drained.msgTypes.get(0));
        final byte[] frame = drained.payloads.get(0);
        assertEquals(161, frame.length);
        final UnsafeBuffer buf = new UnsafeBuffer(frame);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(4, header.schemaId());
        assertEquals(CommandOutcomeDecoder.TEMPLATE_ID, header.templateId());
        assertEquals(CommandOutcomeDecoder.BLOCK_LENGTH, header.blockLength());
        final CommandOutcomeDecoder o = new CommandOutcomeDecoder()
                .wrap(buf, 8, header.blockLength(), header.version());
        assertEquals(0L, o.journalSeq());
        assertEquals(0L, o.meEpoch());
        assertEquals(8_192L, o.egressSeq());
        assertEquals(17L, o.commandIdHigh());
        assertEquals(99L, o.commandIdLow());
        assertEquals(100L, o.userId());
        assertEquals(9001L, o.omsOrderId());
        assertEquals(55L, o.oldOrderId());
        assertEquals(123_000L, o.price());
        assertEquals(7_000L, o.quantity());
        assertEquals(0L, o.budget());
        assertEquals(1, o.marketId());
        assertEquals(2, o.commandKind());
        assertEquals(0, o.orderType());
        assertEquals(1, o.orderSide());
        assertEquals(4_096L, o.appliedPosition());
        assertEquals(1_700L, o.timestamp());
        assertEquals(56L, o.orderId());
        assertEquals(1, o.status());
        assertEquals(0, o.reason());
        assertEquals(1, o.oldCancelled());
        assertEquals(0, o.result());
        assertEquals(2, o.timeInForce());
        assertEquals(5_000L, o.expectedOldRemaining());
        assertEquals(3, o.ledgerSize());
        assertEquals(100_000, o.ledgerCapacity());
        assertEquals(1L, o.behaviorVersion());
    }

    @Test
    public void commandReleaseRecordFourRoundTripsAtTheCap() {
        // 64 KiB ring: a 48-entry record 4 (1112 B) exceeds the 4096 B ring's 512 B max message.
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        final int n = SettlementJournal.MAX_RELEASE_ENTRIES;
        final long[] high = new long[n];
        final long[] floor = new long[n];
        final int[] scope = new int[n];
        final int[] result = new int[n];
        final int[] removed = new int[n];
        for (int i = 0; i < n; i++) {
            high[i] = 1_000L + i;
            floor[i] = 2_000L + i;
            scope[i] = i & 1;
            result[i] = i % 3;
            removed[i] = i * 2;
        }
        journal.appendCommandRelease(77L, 5L, 10, 100_000, 4, n, high, floor, scope, result, removed);

        final Drained drained = drainAll(journal);
        assertEquals(SettlementJournal.MSG_TYPE_RELEASE, (int) drained.msgTypes.get(0));
        final byte[] frame = drained.payloads.get(0);
        assertEquals(1112, frame.length);
        assertEquals(SettlementJournal.MAX_FRAME_LENGTH, frame.length);
        final UnsafeBuffer buf = new UnsafeBuffer(frame);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(CommandReleaseDecoder.TEMPLATE_ID, header.templateId());
        assertEquals(44, header.blockLength());
        final CommandReleaseDecoder r = new CommandReleaseDecoder()
                .wrap(buf, 8, header.blockLength(), header.version());
        assertEquals(0L, r.journalSeq());
        assertEquals(0L, r.meEpoch());
        assertEquals(77L, r.egressSeq());
        assertEquals(5L, r.releaseSeq());
        assertEquals(10, r.ledgerSize());
        assertEquals(100_000, r.ledgerCapacity());
        assertEquals(4, r.tombstoneRanges());
        final CommandReleaseDecoder.ReleasesDecoder g = r.releases();
        assertEquals(n, g.count());
        int i = 0;
        for (final CommandReleaseDecoder.ReleasesDecoder e : g) {
            assertEquals(1_000L + i, e.commandIdHigh());
            assertEquals(2_000L + i, e.idLowFloor());
            assertEquals((i & 1) == 0 ? ReleaseScope.UP_TO : ReleaseScope.ORDER, e.scope());
            assertEquals(ReleaseResult.get((short) (i % 3)), e.result());
            assertEquals(i * 2, e.removed());
            i++;
        }
        assertEquals(n, i);
    }

    @Test
    public void commandReleaseWithNoEntriesRoundTrips() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendCommandRelease(1L, 2L, 0, 100_000, 0, 0,
                new long[0], new long[0], new int[0], new int[0], new int[0]);
        assertEquals(56, drainAll(journal).payloads.get(0).length);
    }

    @Test
    public void aFortyNineEntryReleaseIsRefusedByTheWriter() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        final int n = SettlementJournal.MAX_RELEASE_ENTRIES + 1;
        try {
            journal.appendCommandRelease(1L, 2L, 0, 100_000, 0, n,
                    new long[n], new long[n], new int[n], new int[n], new int[n]);
            org.junit.Assert.fail("a 49-entry CommandRelease must be refused");
        } catch (final IllegalArgumentException expected) {
            // refused before anything reached the ring
        }
        assertEquals(0, drainAll(journal).payloads.size());
    }

    @Test
    public void engineConfigRecordFiveRoundTrips() {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        journal.appendEngineConfig(0L, 64L, 3L, 1L, 100_000L, 12, 1 /* ADOPT */);
        final Drained drained = drainAll(journal);
        assertEquals(SettlementJournal.MSG_TYPE_CONFIG, (int) drained.msgTypes.get(0));
        final byte[] frame = drained.payloads.get(0);
        assertEquals(47, frame.length);
        final UnsafeBuffer buf = new UnsafeBuffer(frame);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buf, 0);
        assertEquals(EngineConfigDecoder.TEMPLATE_ID, header.templateId());
        final EngineConfigDecoder c = new EngineConfigDecoder()
                .wrap(buf, 8, header.blockLength(), header.version());
        assertEquals(0L, c.journalSeq());
        assertEquals(0L, c.meEpoch());
        assertEquals(64L, c.egressSeq());
        assertEquals(3L, c.configVersion());
        assertEquals(1L, c.behaviorVersion());
        assertEquals(100_000L, c.ledgerCapacity());
        assertEquals(12, c.maxWireVersion());
        assertEquals(ConfigDecision.ADOPT, c.decision());
    }

    @Test
    public void fullRingBlocksUntilDrainedAndCountsOneBackpressureEvent() throws Exception {
        // Smallest legal ring: fill it, prove the next append BLOCKS (never sheds), then
        // prove it completes once a consumer drains, with exactly one backpressure event.
        final SettlementJournal journal = new SettlementJournal(4096);

        // Fill by raw ring writes (same record size a trade encodes to) until a write is
        // REFUSED — the ring is then deterministically too full for one more trade record.
        final UnsafeBuffer filler = new UnsafeBuffer(new byte[109]);
        while (journal.ringBuffer().write(SettlementJournal.MSG_TYPE_TRADE, filler, 0, filler.capacity())) {
            // keep filling
        }
        assertEquals(0L, journal.backpressureEvents());

        final CountDownLatch blockedAppendDone = new CountDownLatch(1);
        final Thread appender = new Thread(() -> {
            journal.appendTrade(9999, 9999, 1, 1, 1, 2, 2, 100, 100, false, 9999, 1, 2);
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
