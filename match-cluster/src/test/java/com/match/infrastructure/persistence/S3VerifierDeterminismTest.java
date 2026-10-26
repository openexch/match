// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.orderbook.OrderRejectReason;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.generated.CancelOrderEncoder;
import com.match.infrastructure.generated.CreateOrderEncoder;
import com.match.infrastructure.generated.DurableOrderCommandEncoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;
import com.match.infrastructure.generated.UpdateOrderEncoder;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.journalSeqOf;
import static com.match.application.engine.BehaviourTwoHarness.meEpochOf;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.application.engine.BehaviourTwoHarness.templateId;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 independent verification (review.md): cases the S3 suite does not cover.
 * <ol>
 *   <li>A SINK-LESS replica on the full parity log (CONFLICT, suppressed duplicate, INVALID,
 *       BUMP) ends with the journaled replica's snapshot bytes; JournalDarkNodeParityTest covers
 *       DARK and RINGLESS only.</li>
 *   <li>A sink-less replica's snapshot, restored into a journaled replica, re-journals the rest of
 *       the log byte for byte at every cut (replay after restore from a sink-less member).</li>
 *   <li>Equal retries of a recorded REJECTED (result 1) and NOT_OWNER (result 5) outcome are
 *       suppressed (contract 5.3 "covered" list; DuplicateSuppressionTest covers APPLIED,
 *       UNKNOWN_MARKET and UNKNOWN_LEG).</li>
 *   <li>NOTE-9: the engine's terminal rejectReason guard (0..254) is unreachable from input:
 *       every OrderRejectReason constant is in range, and a seeded randomized log of legacy and
 *       durable frames with hostile prices, quantities, budgets, types, ids and legs never
 *       reaches it, on a DIRECT book (LEVEL_FULL) and an ARRAY book (BOOK_FULL), while the
 *       journaled and sink-less replicas stay identical, and on the ARRAY book so does a replica
 *       restored mid-run from the sink-less member's snapshot.</li>
 * </ol>
 */
public class S3VerifierDeterminismTest {

    // ---- 1. sink-less replica on the parity log ----

    @Test
    public void sinklessReplicaMatchesTheJournaledReplicaOnTheParityLog() {
        final List<Entry> log = JournalDarkNodeParityTest.log();
        try (Replica on = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica sinkless = new Replica(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
            final List<byte[]> frames = on.apply(log, 0, log.size());
            assertTrue("sink-less: no frame", sinkless.apply(log, 0, log.size()).isEmpty());
            assertEquals(on.h.decisions, sinkless.h.decisions);
            assertEquals(frames.size() + 1L, on.h.engine.meIdentity().nextJournalSeq());
            assertEquals(on.h.engine.meIdentity().nextJournalSeq(), sinkless.h.engine.meIdentity().nextJournalSeq());
            assertEquals(on.h.engine.getTradeIdGenerator(), sinkless.h.engine.getTradeIdGenerator());
            assertEquals(1L, sinkless.h.engine.suppressedDuplicateCount());
            assertTrue(sinkless.h.engine.isJournalSeqDense());
            assertEquals(0, sinkless.demuxer.applyErrorCount());
            assertArrayEquals("sink-less snapshot bytes incl. identity block and omsMap", on.snapshot(),
                    sinkless.snapshot());
        }
    }

    // ---- 2. restore a sink-less member's snapshot, replay the rest ----

    @Test
    public void sinklessSnapshotRestoredIntoAJournaledReplicaReJournalsByteForByte() {
        final List<Entry> log = JournalDarkNodeParityTest.log();
        final List<List<byte[]>> straight = new ArrayList<>();
        try (Replica on = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED)) {
            for (Entry e : log) {
                straight.add(on.apply(e));
            }
        }
        for (int cut = 1; cut < log.size(); cut++) {
            try (Replica sinkless = new Replica(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
                sinkless.apply(log, 0, cut);
                try (Replica b = Replica.restoredFrom(sinkless.snapshot())) {
                    for (int i = cut; i < log.size(); i++) {
                        assertFramesEqual("cut " + cut + " entry " + i, straight.get(i), b.apply(log.get(i)));
                    }
                }
            }
        }
    }

    // ---- 3. suppression of recorded REJECTED and NOT_OWNER outcomes ----

    @Test
    public void equalRetriesOfRecordedRejectedAndNotOwnerAreSuppressed() {
        try (Replica r = new Replica()) {
            r.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
            // REJECTED (result 1): price below the market minimum, a terminal plus the outcome.
            final byte[] rejected = durableCreate(1, 100, 9001, 0, 40_000, 1);
            List<byte[]> f = r.apply(new Entry(rejected, 192L, 1_001L));
            assertEquals(List.of(2, 3), List.of(templateId(f.get(0)), templateId(f.get(1))));
            assertEquals(DurableCommandOutcome.REJECTED, record3(f.get(1)).result());
            assertEquals(List.of(2L, 3L), List.of(journalSeqOf(f.get(0)), journalSeqOf(f.get(1))));
            // NOT_OWNER (result 5): a resting leg, then a cancel of it by another user.
            r.apply(new Entry(durableCreate(2, 100, 9002, 0, 59_000, 1), 320L, 1_002L));
            final byte[] notOwner = durable(3, 1, 200, 9003, 2, 0, 0, 0, 0, 0); // order 1 was the rejected create
            f = r.apply(new Entry(notOwner, 448L, 1_003L));
            assertEquals(1, f.size());
            assertEquals(DurableCommandOutcome.WRONG_OWNER, record3(f.get(0)).result());
            assertEquals(5L, journalSeqOf(f.get(0)));
            final int ledger = r.h.engine.commandLedger().size();

            assertTrue("REJECTED retry: no frame", r.apply(new Entry(rejected, 576L, 1_004L)).isEmpty());
            assertTrue("NOT_OWNER retry: no frame", r.apply(new Entry(notOwner, 704L, 1_005L)).isEmpty());
            assertEquals(2L, r.h.engine.suppressedDuplicateCount());
            assertEquals(ledger, r.h.engine.commandLedger().size());
            assertEquals(6L, r.h.engine.meIdentity().nextJournalSeq());
            f = r.apply(new Entry(durableCreate(4, 300, 9004, 0, 58_000, 1), 832L, 1_006L));
            assertEquals("the next record is 6", 6L, journalSeqOf(f.get(0)));
        }
    }

    // ---- 4. NOTE-9: the rejectReason guard is unreachable ----

    @Test
    public void everyOrderRejectReasonConstantIsAJournalCode() throws IllegalAccessException {
        int n = 0;
        for (Field field : OrderRejectReason.class.getDeclaredFields()) {
            final int m = field.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m) && field.getType() == int.class) {
                final int v = field.getInt(null);
                assertTrue(field.getName() + "=" + v, v >= 0 && v <= 254);
                n++;
            }
        }
        assertTrue("non-vacuous", n >= 12);
    }

    private static final long FP = FixedPoint.fromDouble(1.0);

    private static EngineConfigState.MarketDef[] narrowMarket() {
        return new EngineConfigState.MarketDef[]{new EngineConfigState.MarketDef(BehaviourTwoHarness.MARKET,
                "BTC-USD", FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(50_200.0),
                FixedPoint.fromDouble(1.0))};
    }

    /** DIRECT, 2 orders per level (LEVEL_FULL), match cap 3 (MATCH_LIMIT). */
    private static EngineConfigState direct() {
        return EngineConfigState.of(1, EngineConfigState.IMPL_DIRECT, 0, 3, 2, narrowMarket(), 2, 50_000, G, 12);
    }

    /** ARRAY, book capacity 6 (BOOK_FULL), match cap 3. */
    private static EngineConfigState array() {
        return EngineConfigState.of(1, EngineConfigState.IMPL_ARRAY, 6, 3, 0, narrowMarket(), 2, 50_000, G, 12);
    }

    private static long hostilePrice(Random rnd) {
        switch (rnd.nextInt(10)) {
            case 0: return -rnd.nextInt(1_000) * FP;
            case 1: return 0L;
            case 2: return Long.MAX_VALUE - rnd.nextInt(1_000);
            case 3: return FixedPoint.fromDouble(49_990 + rnd.nextInt(230)) + FP / 2;   // off tick
            case 4: return FixedPoint.fromDouble(49_900 + rnd.nextInt(100));          // below min
            case 5: return FixedPoint.fromDouble(50_201 + rnd.nextInt(100));          // above max
            default: return FixedPoint.fromDouble(50_090 + rnd.nextInt(21));          // a crowded band
        }
    }

    private static long hostileQty(Random rnd) {
        switch (rnd.nextInt(10)) {
            case 0: return 0L;
            case 1: return -FP;
            case 2: return Long.MAX_VALUE / 3;                                        // notional overflow
            case 3: return 1L;                                                         // dust
            default: return (1 + rnd.nextInt(4)) * FP;
        }
    }

    private static byte[] anyDurable(long idLow, int kind, int type, long userId, long omsOrderId,
                                     long oldOrderId, int side, long price, long qty, long budget,
                                     int tif, long expectedOldRemaining) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        new DurableOrderCommandEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .commandIdHigh(17).commandIdLow(idLow).userId(userId).omsOrderId(omsOrderId)
                .oldOrderId(oldOrderId).price(price).quantity(qty).budget(budget)
                .marketId(BehaviourTwoHarness.MARKET).commandKind((short) kind).orderType((short) type)
                .orderSide((short) side).timeInForce((short) tif).expectedOldRemaining(expectedOldRemaining);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + DurableOrderCommandEncoder.BLOCK_LENGTH);
    }

    private static byte[] legacyCreate(long userId, long omsOrderId, int type, int side, long price, long qty) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final CreateOrderEncoder e = new CreateOrderEncoder();
        e.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).userId(userId).price(price).quantity(qty)
                .marketId(BehaviourTwoHarness.MARKET)
                .orderType(type == 0 ? OrderType.LIMIT : type == 1 ? OrderType.MARKET : OrderType.LIMIT_MAKER)
                .orderSide(side == 0 ? OrderSide.BID : OrderSide.ASK).omsOrderId(omsOrderId);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    private static byte[] legacyUpdate(long userId, long orderId, int type, int side, long price, long qty) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final UpdateOrderEncoder e = new UpdateOrderEncoder();
        e.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).userId(userId).orderId(orderId).price(price)
                .quantity(qty).marketId(BehaviourTwoHarness.MARKET)
                .orderType(type == 2 ? OrderType.LIMIT_MAKER : OrderType.LIMIT)
                .orderSide(side == 0 ? OrderSide.BID : OrderSide.ASK);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    private static byte[] legacyCancel(long userId, long orderId) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final CancelOrderEncoder e = new CancelOrderEncoder();
        e.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder()).userId(userId).orderId(orderId)
                .marketId(BehaviourTwoHarness.MARKET);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    /** A durable leg the journaled replica reported, for owner-correct cancels and replaces. */
    private record Leg(long orderId, long userId, long omsOrderId, int side) { }

    /**
     * @param restoreMidRun also restore a replica from the SINK-LESS member's snapshot at a random cut
     *                      and require it to re-journal the rest byte for byte. Off for the DIRECT
     *                      book: its snapshot export drops the last order of a full level
     *                      (pre-existing, DirectBookFullLevelSnapshotVerifierTest), which a restore
     *                      on that book exposes independently of S3.
     */
    private static void fuzz(EngineConfigState config, long seed, boolean restoreMidRun) {
        final Random rnd = new Random(seed);
        final int n = 2_500;
        final int cut = 700 + rnd.nextInt(1_100);
        try (Replica on = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica sinkless = new Replica(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
            assertEquals(com.match.application.engine.EngineConfigStateMachine.Decision.FRESH, on.h.apply(config, 64L));
            final List<byte[]> all = new ArrayList<>(on.h.drain());
            assertEquals(1, all.size());
            sinkless.h.apply(config, 64L);
            Replica restored = null;
            final List<byte[]> sent = new ArrayList<>();
            final List<Leg> legs = new ArrayList<>();
            final TerminalDecoder terminal = new TerminalDecoder();
            final CommandOutcomeDecoder outcome = new CommandOutcomeDecoder();
            final MessageHeaderDecoder header = new MessageHeaderDecoder();
            final int[] results = new int[9];
            final int[] reasons = new int[12];
            try {
                for (int i = 0; i < n; i++) {
                    final long position = 192L + 128L * i;
                    final byte[] frame;
                    final int pick = rnd.nextInt(20);
                    final long user = 100 + rnd.nextInt(6);
                    final long oms = 1 + rnd.nextInt(5_000);
                    final int side = rnd.nextInt(2);
                    if (pick < 2 && !sent.isEmpty()) {
                        frame = sent.get(rnd.nextInt(sent.size()));                      // verbatim retry
                    } else if (pick < 9) {
                        final int type = rnd.nextInt(3);
                        frame = anyDurable(1 + rnd.nextInt(1_500), 0, type, user, oms, 0, side,
                                hostilePrice(rnd), hostileQty(rnd), type == 1 ? hostileQty(rnd) * 50_000 : 0,
                                rnd.nextInt(3), 0);
                    } else if (pick < 13 && !legs.isEmpty()) {
                        final Leg leg = legs.get(rnd.nextInt(legs.size()));
                        final boolean owner = rnd.nextInt(4) != 0;
                        final int kind = 1 + rnd.nextInt(2);
                        frame = anyDurable(1 + rnd.nextInt(1_500), kind, rnd.nextInt(2) == 0 ? 0 : 2,
                                owner ? leg.userId : user, owner ? leg.omsOrderId : oms, leg.orderId,
                                rnd.nextInt(5) == 0 ? 1 - leg.side : leg.side,
                                kind == 2 ? hostilePrice(rnd) : 0, kind == 2 ? hostileQty(rnd) : 0, 0,
                                rnd.nextInt(3), kind == 2 ? Math.max(0, hostileQty(rnd)) : 0);
                    } else if (pick < 16) {
                        frame = legacyCreate(user, rnd.nextInt(3) == 0 ? 0 : oms, rnd.nextInt(3), side,
                                hostilePrice(rnd), hostileQty(rnd));
                    } else if (pick < 18) {
                        frame = legacyUpdate(user, rnd.nextInt(80), rnd.nextInt(3), side, hostilePrice(rnd),
                                hostileQty(rnd));
                    } else {
                        frame = legacyCancel(user, rnd.nextInt(80));
                    }
                    sent.add(frame);
                    final Entry e = new Entry(frame, position, 1_000L + i);
                    final List<byte[]> f = on.apply(e);
                    assertTrue("sink-less journals nothing", sinkless.apply(e).isEmpty());
                    if (restoreMidRun && i == cut) {
                        restored = Replica.restoredFrom(sinkless.snapshot());
                    } else if (restored != null) {
                        assertFramesEqual("restored replica, entry " + i, f, restored.apply(e));
                    }
                    for (byte[] fr : f) {
                        final int t = templateId(fr);
                        if (t == 2) {
                            terminal.wrapAndApplyHeader(new UnsafeBuffer(fr), 0, header);
                            final int reason = terminal.rejectReason();
                            assertTrue("journaled rejectReason " + reason, reason >= 0 && reason <= 11);
                            reasons[reason]++;
                        } else if (t == 3) {
                            outcome.wrapAndApplyHeader(new UnsafeBuffer(fr), 0, header);
                            results[outcome.result()]++;
                            if (outcome.result() == 0 && outcome.orderId() > 0 && outcome.status() < 2) {
                                final var d = record3(fr);
                                legs.add(new Leg(d.orderId(), d.userId(), d.omsOrderId(), (int) d.orderSide()));
                            }
                        }
                    }
                    all.addAll(f);
                }
                assertEquals(0, on.demuxer.applyErrorCount());
                assertEquals(0, sinkless.demuxer.applyErrorCount());
                for (int i = 0; i < all.size(); i++) {
                    assertEquals("dense in ring order", i + 1L, journalSeqOf(all.get(i)));
                    assertEquals(G, meEpochOf(all.get(i)));
                }
                assertEquals(all.size() + 1L, on.h.engine.meIdentity().nextJournalSeq());
                assertEquals(on.h.engine.meIdentity().nextJournalSeq(), sinkless.h.engine.meIdentity().nextJournalSeq());
                assertEquals(on.h.engine.getTradeIdGenerator(), sinkless.h.engine.getTradeIdGenerator());
                assertEquals(on.h.engine.suppressedDuplicateCount(), sinkless.h.engine.suppressedDuplicateCount());
                assertArrayEquals("sink-less snapshot", on.snapshot(), sinkless.snapshot());
                if (restoreMidRun) {
                    assertArrayEquals("restored snapshot", on.snapshot(), restored.snapshot());
                }
                // Non-vacuous: trades, suppressions, conflicts, several reject reasons.
                assertTrue("trades", on.h.engine.getTradeIdGenerator() > 50);
                assertTrue("suppressed duplicates", on.h.engine.suppressedDuplicateCount() > 20);
                assertTrue("conflicts", results[DurableCommandOutcome.CONFLICT] > 20);
                assertTrue("applied", results[0] > 100);
                int distinct = 0;
                for (int c : reasons) {
                    distinct += c > 0 ? 1 : 0;
                }
                assertTrue("distinct journaled reject reasons " + Arrays.toString(reasons), distinct >= 6);
            } finally {
                if (restored != null) {
                    restored.close();
                }
            }
        }
    }

    @Test
    public void hostileFramesNeverReachTheRejectReasonGuardOnADirectBook() {
        for (long seed = 1; seed <= 4; seed++) {
            fuzz(direct(), seed, false);
        }
    }

    @Test
    public void hostileFramesNeverReachTheRejectReasonGuardOnAnArrayBook() {
        for (long seed = 11; seed <= 14; seed++) {
            fuzz(array(), seed, true);
        }
    }
}
