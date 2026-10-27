// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 independent review (S4/review.md, check 5): {@link ZeroAllocDurablePathTest} measures
 * rest / cancel / amend / duplicate / RELEASED / INVALID(owner) / CAPACITY and ORDER-only release
 * frames on a book that never trades. This window adds the rest of the behaviour-2 durable path:
 * crossing takers (trades, maker and taker FILLED terminals, record 1/2), CONFLICT, B0 (v11 header),
 * UNKNOWN_LEG, WRONG_OWNER, UNKNOWN_MARKET, a LEG_LIVE release entry, UP_TO entries (the floor map:
 * put and the ORDER that drops it), mixed frames with NOOP entries and an all-NOOP frame. Same
 * harness and the same 20x warm-up as the implementer's test. Every cycle ends with an empty
 * ledger, no floor, one tombstone range and an empty book.
 */
public class S4ReviewZeroAllocFullMixTest {

    private static final int C = 64;
    private static final int H_PER_CYCLE = 17;
    private static final int CYCLES = 346; // 29 commands + 4 release frames per cycle: 10,034 + 1,384
    private static final int WARMUP_ROUNDS = 20;
    private static final double P = 60_000;

    private static final class Plan {
        final List<UnsafeBuffer> frames = new ArrayList<>();
        int commands;
        int releaseFrames;
        long nextOrderId = 1;
        long nextH = 1;
    }

    private static void cycle(Plan p) {
        final long h0 = p.nextH;
        final long id0 = p.nextOrderId;
        final List<byte[]> f = new ArrayList<>();
        // 8 ask makers qty 2 (ids id0..id0+7).
        for (int j = 0; j < 8; j++) {
            f.add(create(h0 + j, 0, 100, 1, P, 2));
        }
        // 8 bid takers qty 1 (ids id0+8..id0+15): 8 trades, makers id0..id0+3 FILLED, takers FILLED.
        for (int j = 8; j < 16; j++) {
            f.add(create(h0 + j, 0, 200, 0, P, 1));
        }
        f.add(create(h0 + 8, 0, 200, 0, P + 5, 1));                                   // CONFLICT
        f.add(withHeaderVersion(create(h0 + 9, 7, 200, 0, P, 1), 11));                // B0 INVALID (v11)
        f.add(cancel(h0, 1, 100, id0));                                                // UNKNOWN_LEG (filled)
        f.add(cancel(h0 + 4, 1, 999, id0 + 4));                                        // WRONG_OWNER
        f.add(create(h0 + 4, 0, 100, 1, P, 2));                                        // suppressed duplicate
        f.add(createOn(2, h0 + 16, 0, 100, 1, P, 1));                                  // UNKNOWN_MARKET
        f.add(amend(h0 + 5, 3, 100, id0 + 5, 1, P + 1, 2, LIMIT));                    // replace -> id0+16
        p.commands += 16 + 7;
        f.add(releaseFrame(h0, order(h0 + 4)));                                        // LEG_LIVE
        f.add(cancel(h0 + 4, 2, 100, id0 + 4));
        f.add(cancel(h0 + 5, 4, 100, id0 + 16));
        f.add(cancel(h0 + 6, 2, 100, id0 + 6));
        f.add(cancel(h0 + 7, 2, 100, id0 + 7));
        p.commands += 4;
        // Mixed frame: 3 UP_TO APPLIED (floors put) + 1 NOOP (the previous cycle's tombstone).
        f.add(releaseFrame(h0 + 1, upTo(h0, 5), upTo(h0 + 4, 9), upTo(h0 + 5, 17),
                order(h0 > 1 ? h0 - 1 : h0 + 16)));
        // ORDER for the whole cycle (drops the floors, one merged range).
        final long[][] all = new long[H_PER_CYCLE][];
        for (int j = 0; j < H_PER_CYCLE; j++) {
            all[j] = order(h0 + j);
        }
        f.add(releaseFrame(h0 + 2, all));
        f.add(releaseFrame(h0 + 3, upTo(h0, 100)));                                    // all-NOOP frame
        p.releaseFrames += 4;
        f.add(create(h0, 0, 100, 1, P, 2));                                            // RELEASED
        f.add(cancel(h0 + 4, 2, 100, id0 + 4));                                        // RELEASED
        p.commands += 2;
        for (byte[] b : f) {
            p.frames.add(new UnsafeBuffer(b));
        }
        p.nextH += H_PER_CYCLE;
        p.nextOrderId += 17;
    }

    private static long[] counters(Engine e, SbeDemuxer d) {
        return new long[]{S4Probe.releaseLegLive(e), S4Probe.releaseApplied(e), S4Probe.releaseNoop(e),
                S4Probe.releaseNoopFrames(e), S4Probe.released(e), S4Probe.invalidWire(e),
                e.suppressedDuplicateCount(), e.getTradeIdGenerator(), d.decodeRejectCount(),
                d.schemaRejectCount(), d.applyErrorCount()};
    }

    private static void run(byte[] freshConfig, String name) throws Exception {
        final com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(mx.isThreadAllocatedMemorySupported());
        mx.setThreadAllocatedMemoryEnabled(true);
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            final SbeDemuxer demuxer = new SbeDemuxer(h.engine);
            demuxer.setEngineConfigHandler(config -> h.decisions.add(h.sm.onEngineConfig(config)));
            final AtomicBoolean stop = new AtomicBoolean();
            final AtomicLong drained = new AtomicLong();
            final OneToOneRingBuffer ring = h.journal.ringBuffer();
            final Thread drainer = new Thread(() -> {
                final org.agrona.concurrent.MessageHandler handler = (t, b, i, n) -> drained.incrementAndGet();
                while (!stop.get() || ring.size() > 0) {
                    if (ring.read(handler) == 0) {
                        Thread.onSpinWait();
                    }
                }
            }, "s4-review-drainer");
            drainer.setDaemon(true);
            drainer.start();
            try {
                long position = 64L;
                h.engine.setCurrentLogPosition(position);
                final UnsafeBuffer fresh = new UnsafeBuffer(freshConfig);
                demuxer.dispatch(fresh, 0, fresh.capacity(), 1_000L);
                assertEquals(name + ": FRESH", 1, h.decisions.size());
                final Plan warm = new Plan();
                for (int c = 0; c < CYCLES * WARMUP_ROUNDS; c++) {
                    cycle(warm);
                }
                final Plan measured = new Plan();
                measured.nextH = warm.nextH;
                measured.nextOrderId = warm.nextOrderId;
                for (int c = 0; c < CYCLES; c++) {
                    cycle(measured);
                }
                long ts = 1_001L;
                for (UnsafeBuffer b : warm.frames) {
                    position += 128;
                    h.engine.setCurrentLogPosition(position);
                    demuxer.dispatch(b, 0, b.capacity(), ts++);
                }
                assertEquals(name + ": warm-up applies cleanly", 0, demuxer.applyErrorCount());
                assertEquals(name + ": warm-up ends with an empty ledger", 0, S4Probe.ledgerSize(h.engine));
                assertEquals(name + ": warm-up ends with no floor", 0, S4Probe.floorCount(h.engine));
                assertEquals(name + ": warm-up ends with one range", 2, S4Probe.ranges(h.engine).length);
                assertEquals(name + ": warm-up ends with an empty book", 0, resting(h.engine));
                final long[] before = counters(h.engine, demuxer);
                final List<UnsafeBuffer> frames = measured.frames;
                final int n = frames.size();
                final long c0 = mx.getCurrentThreadAllocatedBytes();
                final long calibration = mx.getCurrentThreadAllocatedBytes() - c0;
                final long a0 = mx.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < n; i++) {
                    final UnsafeBuffer b = frames.get(i);
                    position += 128;
                    h.engine.setCurrentLogPosition(position);
                    demuxer.dispatch(b, 0, b.capacity(), ts++);
                }
                final long allocated = mx.getCurrentThreadAllocatedBytes() - a0 - calibration;
                final long[] after = counters(h.engine, demuxer);
                System.out.println("[S4-REVIEW] " + name + ": " + measured.commands + " durable commands + "
                        + measured.releaseFrames + " release frames (trades, UP_TO, LEG_LIVE, CONFLICT, B0,"
                        + " B5, B6), allocated " + allocated + " B");
                assertTrue(name + ": the window is >= 10k commands", measured.commands >= 10_000);
                assertTrue(name + ": the window has >= 1k release frames", measured.releaseFrames >= 1_000);
                assertEquals(name + ": LEG_LIVE per cycle", CYCLES, after[0] - before[0]);
                assertEquals(name + ": APPLIED entries per cycle (3 UP_TO + 17 ORDER)", 20L * CYCLES, after[1] - before[1]);
                assertEquals(name + ": NOOP entries per cycle", 2L * CYCLES, after[2] - before[2]);
                assertEquals(name + ": all-NOOP frames per cycle", CYCLES, after[3] - before[3]);
                assertEquals(name + ": RELEASED per cycle", 2L * CYCLES, after[4] - before[4]);
                assertEquals(name + ": B0 per cycle", CYCLES, after[5] - before[5]);
                assertEquals(name + ": suppressed per cycle", CYCLES, after[6] - before[6]);
                assertEquals(name + ": 8 trades per cycle", 8L * CYCLES, after[7] - before[7]);
                assertEquals(name + ": no decode, schema or apply fault", 0,
                        (after[8] - before[8]) + (after[9] - before[9]) + (after[10] - before[10]));
                assertEquals(name + ": the window ends with an empty ledger", 0, S4Probe.ledgerSize(h.engine));
                assertEquals(name + ": the window ends with an empty book", 0, resting(h.engine));
                assertEquals(name + ": zero allocation on the service thread", 0L, allocated);
            } finally {
                stop.set(true);
                drainer.join(10_000);
            }
            assertTrue(name + ": the journal was written", drained.get() > 0);
        }
    }

    @Test
    public void zeroAllocFullBehaviourTwoDurableMix() throws Exception {
        run(engineConfigWith(1, 2, G, 12, C, false, 4_096, 100, 0, 1), "array");
        run(engineConfigWith(1, 2, G, 12, C, true, 0, 100, 64, 1), "direct");
    }
}
