// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.orderbook.S5Probe;
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
 * RC2 S4 contract section 8: no allocation per template-9 or template-10 frame on the service
 * thread at behaviour 2, after warm-up. The frames go through {@link SbeDemuxer#dispatch} into an
 * engine with a real {@code MatchEventPublisher} (egress rings with no-op handlers) and a real
 * {@code SettlementJournal} whose ring another thread drains, on the array and the direct book.
 * The measured window mixes resting creates, cancels, amends, suppressed duplicates, RELEASED,
 * INVALID, CAPACITY and release frames (APPLIED and all-NOOP); the warm-up runs the same mix, so
 * every counted cause fires there first, and each cause advances fewer than 999 times in the window
 * (no rate-limited log line is due, section 8). RC2 S5 (section 7): each cycle also runs
 * {@link #S5_PER_CYCLE} S5 sub-cycles (IOC filled and no match, FOK infeasible and filled, a bounded
 * market buy, a reduced amend and a no-remainder amend, then their ORDER release), about 1k of
 * each in the window.
 */
public class ZeroAllocDurablePathTest {

    private static final int C = 48;
    private static final int ORDERS_PER_CYCLE = 16;
    private static final int CYCLES = 175; // ~58 commands + 6 release frames per cycle: ~10k + ~1k
    private static final int WARMUP_ROUNDS = 20;
    private static final int S5_PER_CYCLE = 6;

    /** Pre-built frames of the whole run, with the counts the run must produce. */
    private static final class Plan {
        final List<UnsafeBuffer> frames = new ArrayList<>();
        int commands;
        int releaseFrames;
        long nextOrderId = 1;
        long nextH = 1;
        long capId = 50_000_000L;
        long s5H = 80_000_000L;
    }

    /**
     * RC2 S5: one sub-cycle on an empty book (8 recorded commands, then one ORDER release frame that
     * empties the ledger again). {@code x} is the predicted orderId of its first create.
     */
    private static void s5SubCycle(Plan p, List<byte[]> f, long x) {
        final long m = p.s5H;
        f.add(S5Frames.limit(m, 0, 100, 1, 60_000, 10, S5Frames.GTC));                 // x rests 10
        f.add(S5Frames.limit(m + 1, 0, 200, 0, 60_000, 1, S5Frames.IOC));              // x+1 IOC filled
        f.add(S5Frames.limit(m + 2, 0, 200, 0, 59_000, 2, S5Frames.IOC));              // x+2 IOC no match
        f.add(S5Frames.limit(m + 3, 0, 200, 0, 60_000, 100, S5Frames.FOK));            // x+3 FOK infeasible
        f.add(S5Frames.limit(m + 4, 0, 200, 0, 60_000, 1, S5Frames.FOK));              // x+4 FOK filled
        f.add(S5Frames.market(m + 5, 0, 200, 0, 1, 120_000, S5Frames.GTC));            // x+5 bounded market buy
        f.add(S5Frames.amendRaw(m, 1, 100, x, 1, 60_000, 8, LIMIT, S5Frames.GTC, fp(10)));     // x+6 rests 5
        f.add(S5Frames.amendRaw(m, 2, 100, x + 6, 1, 60_000, 5, LIMIT, S5Frames.GTC, fp(10))); // no remainder
        f.add(releaseFrame(m, order(m), order(m + 1), order(m + 2), order(m + 3), order(m + 4), order(m + 5)));
        p.commands += 8;
        p.releaseFrames += 1;
        p.s5H += 6;
    }

    /** One cycle of 16 orders; orderIds are predicted (each create and each replace takes one). */
    private static void cycle(Plan p) {
        final long h0 = p.nextH;
        final long id0 = p.nextOrderId;
        final List<byte[]> f = new ArrayList<>();
        for (int j = 0; j < ORDERS_PER_CYCLE; j++) {
            f.add(create(h0 + j, 0, 100, 0, 50_000 + j, 1));                         // rests: ledger +1
        }
        for (int j = 0; j < 4; j++) {
            f.add(create(h0 + j, 0, 100, 0, 50_000 + j, 1));                         // suppressed duplicate
        }
        for (int j = 0; j < ORDERS_PER_CYCLE; j++) {
            f.add(amend(h0 + j, 1, 100, id0 + j, 0, 50_100 + j, 1, LIMIT, fp(1)));          // replace: +1
        }
        for (int j = 0; j < ORDERS_PER_CYCLE; j++) {
            f.add(cancel(h0 + j, 2, 100, id0 + ORDERS_PER_CYCLE + j));               // cancel the replacement: +1 (= C)
        }
        f.add(create(p.capId++, 0, 100, 0, 50_000, 1));                              // CAPACITY
        f.add(durableRaw(h0, 3L << 2, 0, 100, h0 + 1, 0, 0, fp(50_000), fp(1), 0, LIMIT, 0, 0)); // INVALID (owner id)
        p.commands += ORDERS_PER_CYCLE * 3 + 4 + 2;
        for (int k = 0; k < 4; k++) {
            f.add(releaseFrame(h0 + 4 * k, order(h0 + 4 * k), order(h0 + 4 * k + 1), order(h0 + 4 * k + 2),
                    order(h0 + 4 * k + 3)));                                         // APPLIED x4, removed 3 each
        }
        for (int k = 0; k < 2; k++) {
            f.add(releaseFrame(h0, order(h0 + k)));                                  // all-NOOP frame
        }
        p.releaseFrames += 6;
        for (int k = 0; k < S5_PER_CYCLE; k++) {
            s5SubCycle(p, f, id0 + 2L * ORDERS_PER_CYCLE + 7L * k);
        }
        for (int j = 0; j < 4; j++) {
            f.add(create(h0 + j, 0, 100, 0, 50_000 + j, 1));                         // RELEASED
        }
        p.commands += 4;
        for (byte[] b : f) {
            p.frames.add(new UnsafeBuffer(b));
        }
        p.nextH += ORDERS_PER_CYCLE;
        p.nextOrderId += 2L * ORDERS_PER_CYCLE + 7L * S5_PER_CYCLE;
    }

    private static long[] causes(Engine e, SbeDemuxer d) {
        return new long[]{S4Probe.capacity(e), S4Probe.invalidOwnerId(e), S4Probe.released(e),
                S4Probe.releaseApplied(e), S4Probe.releaseNoop(e), S4Probe.releaseNoopFrames(e),
                e.suppressedDuplicateCount(), d.decodeRejectCount(), d.schemaRejectCount(), d.applyErrorCount(),
                S5Probe.ioc(e), S5Probe.fok(e), S5Probe.fokUnfillable(e), S5Probe.amendReduced(e),
                S5Probe.amendNoRemainder(e)};
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
            }, "s4-journal-drainer");
            drainer.setDaemon(true);
            drainer.start();
            try {
                long position = 64L;
                h.engine.setCurrentLogPosition(position);
                final UnsafeBuffer fresh = new UnsafeBuffer(freshConfig);
                demuxer.dispatch(fresh, 0, fresh.capacity(), 1_000L);
                assertEquals(name + ": FRESH", 1, h.decisions.size());

                // Warm-up: the same mix, WARMUP_ROUNDS x the window (well above the contract's 10k
                // commands), so every counted cause fires first and every method of the path is
                // C2-compiled before the window. HotSpot resolves a method's string constants on
                // the REQUESTING thread when it queues that method for C2 (CompileBroker), which
                // shows up as a String + byte[] at the method's entry; with a 10k warm-up the
                // once-per-cycle methods (the release apply, the rejection journal) crossed the C2
                // threshold inside the window (seen with -XX:-UseTLAB and JFR; never our code).
                final Plan warm = new Plan();
                for (int c = 0; c < CYCLES * WARMUP_ROUNDS; c++) {
                    cycle(warm);
                }
                final Plan measured = new Plan();
                measured.nextH = warm.nextH;
                measured.nextOrderId = warm.nextOrderId;
                measured.capId = warm.capId;
                measured.s5H = warm.s5H;
                for (int c = 0; c < CYCLES; c++) {
                    cycle(measured);
                }
                long ts = 1_001L;
                for (UnsafeBuffer b : warm.frames) {
                    position += 128;
                    h.engine.setCurrentLogPosition(position);
                    demuxer.dispatch(b, 0, b.capacity(), ts++);
                }
                final long warmApplyErrors = demuxer.applyErrorCount();
                final int warmLedger = S4Probe.ledgerSize(h.engine);
                final long[] before = causes(h.engine, demuxer);

                final List<UnsafeBuffer> frames = measured.frames;
                final int n = frames.size();
                // Calibration: an empty window (the MXBean read itself).
                final long c0 = mx.getCurrentThreadAllocatedBytes();
                final long calibration = mx.getCurrentThreadAllocatedBytes() - c0;
                if (System.getenv("S4_ALLOC_BISECT") != null) {
                    bisect(mx, h, demuxer, frames, position, ts);
                    return;
                }
                final long a0 = mx.getCurrentThreadAllocatedBytes();
                for (int i = 0; i < n; i++) {
                    final UnsafeBuffer b = frames.get(i);
                    position += 128;
                    h.engine.setCurrentLogPosition(position);
                    demuxer.dispatch(b, 0, b.capacity(), ts++);
                }
                final long allocated = mx.getCurrentThreadAllocatedBytes() - a0 - calibration;
                final long[] after = causes(h.engine, demuxer);

                System.out.println("[S4] " + name + ": " + measured.commands + " durable commands + "
                        + measured.releaseFrames + " release frames, allocated " + allocated + " B");
                assertEquals(name + ": zero allocation on the service thread", 0L, allocated);
                assertEquals(name + ": warm-up applies cleanly", 0, warmApplyErrors);
                assertEquals(name + ": warm-up ends with an empty ledger", 0, warmLedger);
                assertTrue(name + ": the window is ~10k commands", measured.commands >= 10_000);
                assertTrue(name + ": the window has ~1k release frames", measured.releaseFrames >= 1_000);
                assertEquals(name + ": CAPACITY per cycle", CYCLES, after[0] - before[0]);
                assertEquals(name + ": INVALID per cycle", CYCLES, after[1] - before[1]);
                assertEquals(name + ": RELEASED", 4L * CYCLES, after[2] - before[2]);
                assertEquals(name + ": APPLIED release entries", (16L + 6L * S5_PER_CYCLE) * CYCLES, after[3] - before[3]);
                final long s5 = (long) S5_PER_CYCLE * CYCLES;
                assertEquals(name + ": IOC (filled and no match)", 2 * s5, after[10] - before[10]);
                assertEquals(name + ": FOK (both outcomes)", 2 * s5, after[11] - before[11]);
                assertEquals(name + ": FOK infeasible", s5, after[12] - before[12]);
                assertEquals(name + ": reduced amends", s5, after[13] - before[13]);
                assertEquals(name + ": no-remainder amends", s5, after[14] - before[14]);
                assertEquals(name + ": NOOP release entries", 2L * CYCLES, after[4] - before[4]);
                assertEquals(name + ": all-NOOP frames", 2L * CYCLES, after[5] - before[5]);
                assertEquals(name + ": suppressed duplicates", 4L * CYCLES, after[6] - before[6]);
                assertEquals(name + ": no decode, schema or apply fault", 0,
                        (after[7] - before[7]) + (after[8] - before[8]) + (after[9] - before[9]));
                // The reject causes (the counters of section 6.4 that a refusal bumps, and the
                // demuxer's logged drops): fewer than 999 each in the window, so no rate-limited log
                // line (first and every 1000th) is due inside it. Indices 3-5 count release
                // outcomes, not causes, and log nothing.
                for (int k : new int[]{0, 1, 2, 6, 7, 8, 9}) {
                    assertTrue(name + ": cause " + k + " advanced fewer than 999 times (no log line due)",
                            after[k] - before[k] < 999);
                }
                assertEquals(name + ": the window ends with an empty ledger", 0, S4Probe.ledgerSize(h.engine));
            } finally {
                stop.set(true);
                drainer.join(10_000);
            }
            assertTrue(name + ": the journal was written", drained.get() > 0);
        }
    }

    /** Diagnostics only (-Ds4.alloc.bisect): allocation per frame of the first measured cycle, on a copy. */
    private static void bisect(com.sun.management.ThreadMXBean mx, BehaviourTwoHarness h, SbeDemuxer demuxer,
                               List<UnsafeBuffer> frames, long position, long ts) {
        for (int i = 0; i < frames.size(); i++) {
            final UnsafeBuffer b = frames.get(i);
            final long a = mx.getCurrentThreadAllocatedBytes();
            h.engine.setCurrentLogPosition(position + 128L * (i + 1));
            demuxer.dispatch(b, 0, b.capacity(), ts + i);
            final long d = mx.getCurrentThreadAllocatedBytes() - a;
            if (d != 0) {
                System.out.println("[S4-BISECT] frame " + i + " template " + b.getShort(2) + " allocated " + d
);
            }
        }
    }

    @Test
    public void zeroAllocDurablePath() throws Exception {
        run(engineConfigWith(1, 2, G, 12, C, false, 4_096, 100, 0, 1), "array");
        run(engineConfigWith(1, 2, G, 12, C, true, 0, 100, 64, 1), "direct");
    }
}
