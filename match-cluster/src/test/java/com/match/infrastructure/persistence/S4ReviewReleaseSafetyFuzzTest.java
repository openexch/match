// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness.Shape;
import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import org.agrona.collections.Int2ObjectHashMap;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.application.engine.BehaviourTwoHarness.record4;
import static com.match.application.engine.BehaviourTwoHarness.templateId;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 independent review (S4/review.md): one seeded run that combines what the S4 tests cover
 * separately. Crossing books (fills, partial fills, maker FILLED), several legs under one idHigh
 * (iceberg slices / stop children: CREATE {@code (h, attempt k)}), amends that re-key the leg,
 * cancels by the owner and by a wrong user, verbatim resends of ANY earlier frame (another
 * forwarder, a resend), and release frames with UP_TO and ORDER over a sliding window of idHighs,
 * on a journaled member, a sink-less member and a member restored (twice, chained) from a snapshot
 * mid-run.
 *
 * <p>Oracle, checked after every log entry from the journal and the books only:</p>
 * <ul>
 *   <li>an id {@code (h, l)} gets at most ONE canonical record 3 (results 0, 1, 4, 5, 6) in the
 *       whole run: a released id is never re-opened, a late duplicate never applies twice;</li>
 *   <li>a canonical record 3 never names a covered id; RELEASED only names a covered id;</li>
 *   <li>each release entry's result equals the model (tombstones, floors, and LEG_LIVE iff a leg
 *       of {@code h} rests per a scan of the books and the omsMap); an all-NOOP frame journals
 *       nothing;</li>
 *   <li>no leg ever rests under a tombstoned idHigh;</li>
 *   <li>I6 (keys == resting legs, LegIndex counts) on all members;</li>
 *   <li>the restored members journal byte-identical frames and end with identical snapshots.</li>
 * </ul>
 */
public class S4ReviewReleaseSafetyFuzzTest {

    private record RestingLeg(long orderId, long h, long user, int side) { }

    private static List<RestingLeg> restingLegs(Engine e) {
        final List<RestingLeg> out = new ArrayList<>();
        final Int2ObjectHashMap<MatchingEngine>.ValueIterator it = e.getEngines().values().iterator();
        while (it.hasNext()) {
            final MatchingEngine me = it.next();
            final long[][] sides = {me.getBidOrders(), me.getAskOrders()};
            for (int s = 0; s < 2; s++) {
                for (int i = 0; i < sides[s].length; i += 4) {
                    final long id = sides[s][i];
                    out.add(new RestingLeg(id, e.getOrderIdToOmsOrderId().get(id),
                            S4Probe.restingUserId(me, id, s == 0), s));
                }
            }
        }
        return out;
    }

    private static Map<Long, Integer> legsByH(Engine e) {
        final Map<Long, Integer> out = new HashMap<>();
        for (RestingLeg l : restingLegs(e)) {
            out.merge(l.h, 1, Integer::sum);
        }
        return out;
    }

    private static String key(long h, long l) {
        return h + "/" + l;
    }

    private static void fuzz(byte[] config, long seed, String name) {
        final Random rnd = new Random(seed);
        final int n = 4_000;
        final int cut1 = 900 + rnd.nextInt(600);
        final int cut2 = 2_200 + rnd.nextInt(600);
        final Set<Long> tombstoned = new HashSet<>();
        final Map<Long, Long> floors = new HashMap<>();
        final Map<String, Integer> canonical = new HashMap<>();
        final Map<Long, Long> nextAttempt = new HashMap<>();
        final Set<Long> omsIds = new HashSet<>();
        final List<byte[]> sent = new ArrayList<>();
        int legLive = 0, orderApplied = 0, upToApplied = 0, releasedSeen = 0, partial = 0, noopFrames = 0;
        int maxLegsUnderOneH = 0, lateCanonicalBlocked = 0;
        long nextH = 1;
        try (Replica a = new Replica(Engine.deferredUntilConfig(), Shape.JOURNALED);
             Replica s = new Replica(Engine.deferredUntilConfig(), Shape.SINKLESS)) {
            a.apply(new Entry(config, 64L, 1_000L));
            s.apply(new Entry(config, 64L, 1_000L));
            Replica restored = null;
            try {
                for (int i = 0; i < n; i++) {
                    final long position = 192L + 128L * i;
                    final int pick = rnd.nextInt(20);
                    final double price = 59_995 + rnd.nextInt(11);
                    final double qty = 1 + rnd.nextInt(3);
                    final List<RestingLeg> legs = restingLegs(a.h.engine);
                    byte[] frame;
                    long[][] release = null;
                    if (pick < 2 && !sent.isEmpty()) {
                        frame = sent.get(rnd.nextInt(sent.size()));                    // verbatim resend
                    } else if (pick < 7) {
                        final long h = nextH++;                                        // new order
                        omsIds.add(h);
                        nextAttempt.put(h, 1L);
                        final int t = rnd.nextInt(10);
                        frame = t < 8 ? createOfType(h, 0, 100 + rnd.nextInt(3), rnd.nextInt(2), price, qty,
                                t < 6 ? LIMIT : LIMIT_MAKER)
                                : durableRaw(h, 0, CREATE, 100, h, 0, t == 8 ? 0 : 1, 0, t == 9 ? fp(qty) : 0,
                                t == 8 ? fp(qty * 60_000) : 0, MARKET, 0, 0);
                    } else if (pick < 9) {
                        // Iceberg slice / stop child: another CREATE under an existing idHigh.
                        // Mostly an idHigh whose leg still rests (a second live leg), else a recent one.
                        final long h = !legs.isEmpty() && rnd.nextInt(3) != 0
                                ? legs.get(rnd.nextInt(legs.size())).h
                                : Math.max(1, nextH - 1 - rnd.nextInt(30));
                        final long att = nextAttempt.merge(h, 1L, Long::sum) - 1;
                        omsIds.add(h);
                        frame = create(h, att, 100 + rnd.nextInt(3), rnd.nextInt(2), price, qty);
                    } else if (pick < 14 && !legs.isEmpty()) {
                        final RestingLeg leg = legs.get(rnd.nextInt(legs.size()));
                        final long att = nextAttempt.merge(leg.h, 1L, Long::sum) - 1;
                        final long user = rnd.nextInt(6) == 0 ? 999 : leg.user;      // some wrong owners
                        if (rnd.nextBoolean()) {
                            frame = cancel(leg.h, att, user, leg.orderId);
                        } else {
                            frame = amend(leg.h, att, user, leg.orderId, leg.side, price, qty,
                                    rnd.nextInt(4) == 0 ? LIMIT_MAKER : LIMIT);
                        }
                    } else {
                        final int k = 1 + rnd.nextInt(4);
                        release = new long[k][];
                        final long lo = Math.max(1, nextH - 60);
                        for (int j = 0; j < k; j++) {
                            final long h = lo + rnd.nextInt((int) Math.max(1, nextH - lo));
                            if (rnd.nextInt(3) == 0) {
                                release[j] = upTo(h, rnd.nextInt(24));
                            } else {
                                release[j] = order(h);
                            }
                        }
                        frame = releaseFrame(i, release);
                    }
                    if (templateId(frame) == 10 && release == null) {
                        // A resent release frame: recover its entries for the model.
                        release = decodeRelease(frame);
                    }
                    // Model of each release entry, against the state BEFORE the frame (no command
                    // runs between the entries of one frame, so the books are fixed).
                    int[] expected = null;
                    if (release != null) {
                        final Map<Long, Integer> live = legsByH(a.h.engine);
                        final Set<Long> tomb = new HashSet<>(tombstoned);
                        final Map<Long, Long> fl = new HashMap<>(floors);
                        expected = new int[release.length];
                        for (int j = 0; j < release.length; j++) {
                            final long h = release[j][0], f = release[j][1];
                            if (release[j][2] == 0) {
                                if (tomb.contains(h) || (fl.containsKey(h) && f <= fl.get(h))) {
                                    expected[j] = R_NOOP;
                                } else {
                                    expected[j] = R_APPLIED;
                                    fl.put(h, f);
                                }
                            } else if (tomb.contains(h)) {
                                expected[j] = R_NOOP;
                            } else if (live.getOrDefault(h, 0) > 0) {
                                expected[j] = R_LEG_LIVE;
                            } else {
                                expected[j] = R_APPLIED;
                                tomb.add(h);
                                fl.remove(h);
                            }
                        }
                    }
                    sent.add(frame);
                    final Entry e = new Entry(frame, position, 1_001L + i);
                    final List<byte[]> f = a.apply(e);
                    assertTrue(name + " entry " + i + ": sink-less journals nothing", s.apply(e).isEmpty());
                    if (i == cut1) {
                        restored = Replica.restoredFrom(s.snapshot());
                    } else if (i == cut2) {
                        assertFramesEqual(name + " restored, entry " + i, f, restored.apply(e));
                        final Replica again = Replica.restoredFrom(restored.snapshot());
                        restored.close();
                        restored = again;
                    } else if (restored != null) {
                        assertFramesEqual(name + " restored, entry " + i, f, restored.apply(e));
                    }

                    // ---- oracle ----
                    if (release != null) {
                        boolean journal = false;
                        for (int r : expected) {
                            journal |= r != R_NOOP;
                        }
                        final List<byte[]> r4 = of(f, 4);
                        if (!journal) {
                            assertTrue(name + " entry " + i + ": all-NOOP frame journals nothing", f.isEmpty());
                            noopFrames++;
                        } else {
                            assertEquals(name + " entry " + i + ": one record 4", 1, r4.size());
                            final CommandReleaseDecoder d = record4(r4.get(0));
                            final CommandReleaseDecoder.ReleasesDecoder g = d.releases();
                            int j = 0;
                            while (g.hasNext()) {
                                g.next();
                                final long h = g.commandIdHigh();
                                assertEquals(name + " entry " + i + " release " + j + " h " + h, expected[j], g.resultRaw());
                                if (g.resultRaw() == R_APPLIED) {
                                    if (g.scopeRaw() == 0) {
                                        floors.put(h, g.idLowFloor());
                                        upToApplied++;
                                    } else {
                                        tombstoned.add(h);
                                        floors.remove(h);
                                        orderApplied++;
                                    }
                                } else if (g.resultRaw() == R_LEG_LIVE) {
                                    legLive++;
                                }
                                j++;
                            }
                            assertEquals(release.length, j);
                        }
                    }
                    for (byte[] fr : of(f, 3)) {
                        final CommandOutcomeDecoder o = record3(fr);
                        final long h = o.commandIdHigh(), l = o.commandIdLow();
                        final boolean covered = tombstoned.contains(h) || (floors.containsKey(h) && l <= floors.get(h));
                        final int res = o.result();
                        if (res == 0 || res == 1 || res == 4 || res == 5 || res == 6) {
                            assertNull(name + " entry " + i + ": SECOND canonical outcome for id (" + h + ", " + l + ")",
                                    canonical.putIfAbsent(key(h, l), res));
                            assertFalse(name + " entry " + i + ": canonical outcome for a released id (" + h + ", " + l + ")",
                                    covered);
                            if (res == 0 && o.status() == 1) {
                                partial++;
                            }
                        } else if (res == DurableCommandOutcome.RELEASED) {
                            assertTrue(name + " entry " + i + ": RELEASED for an uncovered id (" + h + ", " + l + ")", covered);
                            releasedSeen++;
                            if (canonical.containsKey(key(h, l))) {
                                lateCanonicalBlocked++;
                            }
                        }
                    }
                    final Map<Long, Integer> live = legsByH(a.h.engine);
                    for (Map.Entry<Long, Integer> x : live.entrySet()) {
                        assertFalse(name + " entry " + i + ": a leg rests under tombstoned idHigh " + x.getKey(),
                                tombstoned.contains(x.getKey()));
                        maxLegsUnderOneH = Math.max(maxLegsUnderOneH, x.getValue());
                    }
                    LegIndexInvariantTest.check(a.h.engine, omsIds, name + " seed " + seed + " entry " + i);
                    LegIndexInvariantTest.check(s.h.engine, omsIds, name + " seed " + seed + " entry " + i + " (sink-less)");
                    if (restored != null) {
                        LegIndexInvariantTest.check(restored.h.engine, omsIds, name + " seed " + seed + " entry " + i
                                + " (restored)");
                    }
                }
                assertEquals(0, a.demuxer.applyErrorCount());
                assertEquals(0, s.demuxer.applyErrorCount());
                assertEquals(0, restored.demuxer.applyErrorCount());
                final byte[] image = a.snapshot();
                assertArrayEquals(name + ": sink-less snapshot", image, s.snapshot());
                assertArrayEquals(name + ": restored snapshot", image, restored.snapshot());
                System.out.println("[S4-REVIEW] " + name + " seed " + seed + ": legLive=" + legLive
                        + " orderApplied=" + orderApplied + " upToApplied=" + upToApplied + " released=" + releasedSeen
                        + " lateDuplicateOfCanonicalBlocked=" + lateCanonicalBlocked + " partial=" + partial
                        + " noopFrames=" + noopFrames + " maxLegsUnderOneH=" + maxLegsUnderOneH
                        + " trades=" + a.h.engine.getTradeIdGenerator()
                        + " suppressed=" + a.h.engine.suppressedDuplicateCount());
                // Non-vacuous.
                assertTrue("LEG_LIVE", legLive > 20);
                assertTrue("ORDER applied", orderApplied > 100);
                assertTrue("UP_TO applied", upToApplied > 30);
                assertTrue("RELEASED", releasedSeen > 50);
                assertTrue("late duplicates of applied ids refused", lateCanonicalBlocked > 20);
                assertTrue("partial fills", partial > 10);
                assertTrue("all-NOOP frames", noopFrames > 5);
                assertTrue("several legs under one idHigh", maxLegsUnderOneH >= 2);
                assertTrue("trades", a.h.engine.getTradeIdGenerator() > 200);
                assertTrue("suppressed duplicates", a.h.engine.suppressedDuplicateCount() > 20);
            } finally {
                if (restored != null) {
                    restored.close();
                }
            }
        }
    }

    private static long[][] decodeRelease(byte[] frame) {
        final com.match.infrastructure.generated.ReleaseCommandIdsDecoder d =
                new com.match.infrastructure.generated.ReleaseCommandIdsDecoder().wrapAndApplyHeader(
                        new org.agrona.concurrent.UnsafeBuffer(frame), 0,
                        new com.match.infrastructure.generated.MessageHeaderDecoder());
        final com.match.infrastructure.generated.ReleaseCommandIdsDecoder.ReleasesDecoder g = d.releases();
        final long[][] out = new long[g.count()][];
        int j = 0;
        while (g.hasNext()) {
            g.next();
            out[j++] = new long[]{g.commandIdHigh(), g.idLowFloor(), g.scopeRaw()};
        }
        return out;
    }

    @Test
    public void releasedIdsStayClosedAcrossFillsSlicesResendsAndRestore() {
        for (long seed = 1; seed <= 2; seed++) {
            fuzz(engineConfigWith(1, 2, G, 12, 1_000_000, false, 64, 4, 0, 1), seed, "array");
            fuzz(engineConfigWith(1, 2, G, 12, 1_000_000, true, 0, 4, 3, 1), 10 + seed, "direct");
        }
    }
}
