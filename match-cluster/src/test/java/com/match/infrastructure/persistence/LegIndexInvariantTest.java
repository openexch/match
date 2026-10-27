// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.application.orderbook.S5Probe;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 invariant I6 (contract 2.2, 5.2): after every behaviour-2 command the omsMap keys are in
 * bijection with the resting legs of all books, and {@code LegIndex.count(o)} equals the number of
 * resting legs whose omsOrderId is {@code o}; again after a serialize/restore round trip. Seeded
 * fuzz on the array book (small capacity: BOOK_FULL) and the direct book (2 per level:
 * LEVEL_FULL), with fills, partial fills, amends, cancels, market orders and maker rejects, on a
 * journaled member and on a sink-less member side by side. RC2 S5: the mix also carries native IOC
 * and FOK creates, bounded market buys (quantity and budget) and amends whose reference includes
 * in-window fills (and an occasional wrong reference), and no IOC/FOK id ever keeps an omsMap key
 * or a resting leg after its command.
 */
public class LegIndexInvariantTest {

    private record Leg(long orderId, long h, long user, int side) { }

    /** The I6 check against a scan of the books. */
    static void check(Engine e, Set<Long> omsIds, String where) {
        final Set<Long> resting = new TreeSet<>();
        final Int2ObjectHashMap<MatchingEngine>.ValueIterator it = e.getEngines().values().iterator();
        while (it.hasNext()) {
            final MatchingEngine me = it.next();
            for (long[] side : new long[][]{me.getBidOrders(), me.getAskOrders()}) {
                for (int i = 0; i < side.length; i += 4) {
                    assertTrue(where + ": orderId " + side[i] + " rests twice", resting.add(side[i]));
                }
            }
        }
        final Long2LongHashMap map = e.getOrderIdToOmsOrderId();
        final Set<Long> keys = new TreeSet<>();
        final Map<Long, Long> perOms = new HashMap<>();
        final Long2LongHashMap.KeyIterator k = map.keySet().iterator();
        while (k.hasNext()) {
            final long id = k.nextValue();
            keys.add(id);
            perOms.merge(map.get(id), 1L, Long::sum);
        }
        assertEquals(where + ": omsMap keys == resting legs", resting, keys);
        for (long o : omsIds) {
            assertEquals(where + ": LegIndex.count(" + o + ")", (long) perOms.getOrDefault(o, 0L), S4Probe.legCount(e, o));
        }
        assertEquals(where + ": distinct omsOrderIds", perOms.size(), S4Probe.legDistinct(e));
    }

    private static void fuzz(byte[] config, long seed, String name) {
        final Random rnd = new Random(seed);
        final Set<Long> omsIds = new HashSet<>();
        final List<Leg> legs = new ArrayList<>();
        final Set<Long> immediateIds = new HashSet<>();
        int immediate = 0, reduced = 0;
        try (Replica journaled = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica sinkless = new Replica(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
            long position = 64, ts = 1_000;
            journaled.apply(new Entry(config, position, ts));
            sinkless.apply(new Entry(config, position, ts));
            long nextH = 1;
            int applied = 0;
            for (int i = 0; i < 1_500; i++) {
                final byte[] frame;
                final int pick = rnd.nextInt(10);
                final int side = rnd.nextInt(2);
                final double price = 59_990 + rnd.nextInt(21);
                final double qty = 1 + rnd.nextInt(3);
                if (pick < 5 || legs.isEmpty()) {
                    final long h = nextH++;
                    omsIds.add(h);
                    final int kindOfCreate = rnd.nextInt(12);
                    if (kindOfCreate < 7) {
                        frame = create(h, 0, 100 + rnd.nextInt(4), side, price, qty);
                    } else if (kindOfCreate >= 10) {
                        // RC2 S5: native IOC (10) / FOK (11).
                        frame = durableRaw(h, 0, CREATE, 100 + rnd.nextInt(4), h, 0, side, fp(price), fp(qty), 0,
                                LIMIT, kindOfCreate - 9, 0);
                    } else if (kindOfCreate < 9) {
                        frame = createOfType(h, 0, 100 + rnd.nextInt(4), side, price, qty, LIMIT_MAKER);
                    } else {
                        // MARKET: a buy spends a budget bounded by its quantity (RC2 S5 9.1), a sell
                        // sizes by quantity.
                        frame = durableRaw(h, 0, CREATE, 100, h, 0, side, 0, fp(qty),
                                side == 0 ? fp(qty * 60_000) : 0, MARKET, 0, 0);
                    }
                } else {
                    final Leg leg = legs.get(rnd.nextInt(legs.size()));
                    final long attempt = 1 + rnd.nextInt(1_000_000);
                    if (rnd.nextBoolean()) {
                        frame = cancel(leg.h, attempt, leg.user, leg.orderId);
                    } else {
                        // RC2 S5 9.1: the leg's current remaining (test access), plus in-window fills
                        // the core has not seen yet; >= 1 when the leg no longer rests (B6 answers first).
                        final MatchingEngine me = journaled.h.engine.getEngine(1);
                        final long remaining = S5Probe.restingRemaining(me, leg.orderId, leg.side == 0);
                        final int r = rnd.nextInt(8);
                        final long expected = remaining <= 0 ? fp(1)
                                : r < 3 ? remaining + fp(1 + rnd.nextInt(2)) : r == 3 && remaining > 1 ? remaining - 1
                                : remaining;
                        if (remaining > 0 && expected > remaining) {
                            reduced++;
                        }
                        frame = amend(leg.h, attempt, leg.user, leg.orderId, leg.side, price, qty,
                                rnd.nextInt(4) == 0 ? LIMIT_MAKER : LIMIT, expected);
                    }
                }
                position += 128;
                ts++;
                final List<byte[]> f = journaled.apply(new Entry(frame, position, ts));
                assertTrue(sinkless.apply(new Entry(frame, position, ts)).isEmpty());
                for (byte[] fr : of(f, 3)) {
                    final CommandOutcomeDecoder o = record3(fr);
                    if (o.commandKind() == 0 && o.timeInForce() != 0 && o.result() == DurableCommandOutcome.APPLIED) {
                        immediateIds.add(o.orderId());
                        immediate++;
                    }
                    if (o.result() == DurableCommandOutcome.APPLIED && o.orderId() > 0 && o.status() <= 1) {
                        applied++;
                        legs.add(new Leg(o.orderId(), o.commandIdHigh(), o.userId(), o.commandKind() == 2
                                ? o.orderSide() : o.orderSide()));
                    }
                }
                check(journaled.h.engine, omsIds, name + " seed " + seed + " entry " + i + " (journaled)");
                for (long id : immediateIds) {
                    final MatchingEngine me = journaled.h.engine.getEngine(1);
                    assertFalse(name + ": IOC/FOK " + id + " has an omsMap key",
                            journaled.h.engine.getOrderIdToOmsOrderId().containsKey(id));
                    assertEquals(name + ": IOC/FOK " + id + " rests", -1L,
                            S5Probe.restingRemaining(me, id, true) & S5Probe.restingRemaining(me, id, false));
                }
                check(sinkless.h.engine, omsIds, name + " seed " + seed + " entry " + i + " (sink-less)");
            }
            assertTrue(name + ": non-vacuous applies", applied > 200);
            System.out.println("[S5] LegIndexInvariantTest " + name + " seed " + seed + ": IOC/FOK applied " + immediate
                    + ", amends with in-window fills " + reduced);
            assertTrue(name + ": IOC/FOK applied", immediate > 50);
            assertTrue(name + ": amends with in-window fills", reduced > 5);
            assertTrue(name + ": trades happened", journaled.h.engine.getTradeIdGenerator() > 100);
            final byte[] image = journaled.snapshot();
            assertArrayEquals(name + ": sink-less snapshot", image, sinkless.snapshot());
            try (Replica restored = Replica.restoredFrom(image)) {
                check(restored.h.engine, omsIds, name + " seed " + seed + " after a serialize/restore round trip");
                assertArrayEquals(image, restored.snapshot());
            }
        }
    }

    @Test
    public void legIndexMatchesBooksAfterEveryCommand() {
        for (long seed = 1; seed <= 2; seed++) {
            fuzz(engineConfigWith(1, 2, G, 12, 1_000_000, false, 24, 3, 0, 1), seed, "array");
            fuzz(engineConfigWith(1, 2, G, 12, 1_000_000, true, 0, 3, 2, 1), seed, "direct");
        }
    }
}
