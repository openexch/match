// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S4 contract section 7: the snapshot v2 (envelope 2, ledger v2 block marker 4) layout, its
 * round trip, the reader rules R1-R11 (each broken once and refused naming the rule) and the
 * cross-version refusals.
 */
public class SnapshotLedgerV2Test {

    private static final int C = 50;
    private static final int ENTRY = 132, PAIR = 16, HEADER = 21;
    private static final int N_INFLIGHT = 3, N_FLOOR = 2, N_RANGE = 2;
    private static final int BLOCK = HEADER + N_INFLIGHT * ENTRY + N_FLOOR * PAIR + N_RANGE * PAIR;

    /**
     * The reference state: inflight (11, 0), (12, 5), (14, 0); floors 12 -> 0 and 13 -> 0; ranges
     * [20, 20] and [30, 30]; resting legs of 11, 13 and 14 (13's CREATE id released by its floor).
     */
    private static Run reference() {
        final Run r = fresh(C);
        appliedOrderId(r.send(create(11, 0, 100, 0, 59_000, 1)));
        final long l12 = appliedOrderId(r.send(create(12, 0, 100, 0, 59_001, 1)));
        appliedOrderId(r.send(cancel(12, 1, 100, l12)));
        r.send(releaseFrame(1, upTo(12, 0)));
        appliedOrderId(r.send(create(13, 0, 100, 0, 59_002, 1)));
        r.send(releaseFrame(2, upTo(13, 0)));
        appliedOrderId(r.send(create(14, 0, 100, 0, 59_003, 1)));
        r.send(releaseFrame(3, order(20), order(30)));
        return r;
    }

    static int envelopeVersion(byte[] image) {
        return new UnsafeBuffer(image).getInt(8);
    }

    static byte[] payload(byte[] image) {
        final UnsafeBuffer b = new UnsafeBuffer(image);
        assertTrue("a durable envelope", image.length >= 20 && b.getLong(0) < 0 && b.getInt(12) == image.length - 20);
        return Arrays.copyOfRange(image, 20, image.length);
    }

    static byte[] envelope(byte[] magicSource, byte[] payload, int version) {
        final byte[] out = new byte[payload.length + 20];
        final UnsafeBuffer b = new UnsafeBuffer(out);
        b.putLong(0, new UnsafeBuffer(magicSource).getLong(0));
        b.putInt(8, version);
        b.putInt(12, payload.length);
        final java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        b.putInt(16, (int) crc.getValue());
        b.putBytes(20, payload);
        return out;
    }

    /** Offset of the omsMap count in a payload (after the books and the timer counter). */
    static int omsMapOffset(byte[] payload) {
        final UnsafeBuffer b = new UnsafeBuffer(payload);
        int p = 16;
        final int markets = b.getInt(p);
        p += 4;
        for (int m = 0; m < markets; m++) {
            p += 4;
            p += 4 + b.getInt(p) * 32;
            p += 4 + b.getInt(p) * 32;
        }
        return p + 8;
    }

    /** Offset of the first resting order's price in the market section. */
    static int firstOrderPriceOffset(byte[] payload) {
        final UnsafeBuffer b = new UnsafeBuffer(payload);
        assertTrue(b.getInt(16) >= 1);
        assertTrue("a resting bid", b.getInt(24) >= 1);
        return 28 + 16; // marketId 20, nBid 24, then orderId, userId, price
    }

    private static Engine restore(byte[] image) {
        final Engine e = Engine.deferredUntilConfig();
        SnapshotCodec.deserialize(new UnsafeBuffer(image), 0, image.length, e);
        return e;
    }

    // ==================== layout and round trip ====================

    @Test
    public void roundTripCarriesInflightFloorsAndRanges() {
        try (Run r = reference(); Run twin = reference()) {
            final byte[] image = r.r.snapshot();
            assertEquals("behaviour 2: envelope 2", 2, envelopeVersion(image));
            final byte[] p = payload(image);
            final UnsafeBuffer b = new UnsafeBuffer(p, p.length - BLOCK, BLOCK);
            assertEquals("marker 4", 4, b.getByte(0));
            assertEquals("block version 2", 2, b.getInt(1));
            assertEquals("capacity", C, b.getInt(5));
            assertEquals("nInflight", N_INFLIGHT, b.getInt(9));
            assertEquals("nFloor", N_FLOOR, b.getInt(13));
            assertEquals("nRanges", N_RANGE, b.getInt(17));
            final long[][] keys = {{11, 0}, {12, 5}, {14, 0}};
            for (int i = 0; i < N_INFLIGHT; i++) {
                final int e = HEADER + i * ENTRY;
                assertEquals("entry " + i + " idHigh", keys[i][0], b.getLong(e));
                assertEquals("entry " + i + " idLow", keys[i][1], b.getLong(e + 8));
                assertEquals("entry " + i + " omsOrderId", keys[i][0], b.getLong(e + 24));
                assertEquals("entry " + i + " marketId", 1, b.getInt(e + 96));
                assertEquals("entry " + i + " result APPLIED", 0, b.getInt(e + 128));
            }
            assertEquals("(12, 5) is a CANCEL", 1, b.getInt(HEADER + ENTRY + 100));
            assertEquals("(12, 5) status CANCELLED", 3, b.getInt(HEADER + ENTRY + 116));
            final int floors = HEADER + N_INFLIGHT * ENTRY;
            assertEquals(12L, b.getLong(floors));
            assertEquals(0L, b.getLong(floors + 8));
            assertEquals(13L, b.getLong(floors + 16));
            assertEquals(0L, b.getLong(floors + 24));
            final int ranges = floors + N_FLOOR * PAIR;
            assertArrayEquals(new long[]{20, 20, 30, 30},
                    new long[]{b.getLong(ranges), b.getLong(ranges + 8), b.getLong(ranges + 16), b.getLong(ranges + 24)});

            assertArrayEquals("two replicas byte-equal", image, twin.r.snapshot());
            try (Replica restored = Replica.restoredFrom(image)) {
                assertArrayEquals("serialize, restore, serialize", image, restored.snapshot());
                assertEquals(N_INFLIGHT, S4Probe.ledgerSize(restored.h.engine));
                assertArrayEquals(new long[]{20, 20, 30, 30}, S4Probe.ranges(restored.h.engine));
                assertEquals(N_FLOOR, S4Probe.floorCount(restored.h.engine));
            }
        }
        try (Run empty = fresh(C)) {
            final byte[] p = payload(empty.r.snapshot());
            final UnsafeBuffer b = new UnsafeBuffer(p, p.length - HEADER, HEADER);
            assertEquals("an empty ledger still writes the block", 4, b.getByte(0));
            assertEquals(2, b.getInt(1));
            assertEquals(C, b.getInt(5));
            assertEquals(0, b.getInt(9));
            assertEquals(0, b.getInt(13));
            assertEquals(0, b.getInt(17));
        }
    }

    // ==================== R1-R11 ====================

    @Test
    public void corruptionCases() {
        final byte[] good;
        final byte[] behaviourOne;
        try (Run r = reference(); Replica one = new Replica()) {
            good = r.r.snapshot();
            one.apply(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            behaviourOne = one.snapshot();
        }
        assertEquals("the reference image is envelope 2", 2, envelopeVersion(good));
        final byte[] p = payload(good);
        final int blk = p.length - BLOCK;
        final int e0 = blk + HEADER, e1 = e0 + ENTRY;
        final int f0 = blk + HEADER + N_INFLIGHT * ENTRY, f1 = f0 + PAIR;
        final int r0 = f0 + N_FLOOR * PAIR, r1 = r0 + PAIR;
        final int oms = omsMapOffset(p);
        assertEquals("three omsMap pairs", 3, new UnsafeBuffer(p).getInt(oms));
        final byte[] emptyV2 = new byte[HEADER];
        new UnsafeBuffer(emptyV2).putByte(0, (byte) 4);
        new UnsafeBuffer(emptyV2).putInt(1, 2);
        new UnsafeBuffer(emptyV2).putInt(5, C);

        final Map<String, String> expectedRule = new LinkedHashMap<>();
        final Map<String, byte[]> cases = new LinkedHashMap<>();
        final java.util.function.BiConsumer<String, UnaryOperator<byte[]>> payloadCase = (name, f) -> {
            final String rule = name.substring(0, name.indexOf(' '));
            expectedRule.put(name, rule);
            cases.put(name, envelope(good, f.apply(p.clone()), 2));
        };
        payloadCase.accept("R1 marker 2 in envelope 2", x -> put8(x, blk, 2));
        payloadCase.accept("R2 block version 1", x -> putInt(x, blk + 1, 1));
        payloadCase.accept("R3 capacity mismatch", x -> putInt(x, blk + 5, C - 1));
        payloadCase.accept("R4 nInflight > C", x -> putInt(x, blk + 9, C + 1));
        payloadCase.accept("R4 negative count", x -> putInt(x, blk + 13, -1));
        payloadCase.accept("R5 truncated block", x -> Arrays.copyOf(x, blk + 10));
        payloadCase.accept("R5 one byte extra", x -> Arrays.copyOf(x, x.length + 1));
        payloadCase.accept("R5 one byte missing", x -> Arrays.copyOf(x, x.length - 1));
        payloadCase.accept("R6 unsorted inflight", x -> swap(x, e0, e1, ENTRY));
        payloadCase.accept("R6 duplicate inflight", x -> { System.arraycopy(x, e0, x, e1, ENTRY); return x; });
        for (int result : new int[]{2, 3, 7, 8}) {
            payloadCase.accept("R7 result " + result, x -> putInt(x, e0 + 128, result));
        }
        payloadCase.accept("R8 entry under its floor", x -> putLong(x, f0 + 8, 5));
        payloadCase.accept("R8 entry under a tombstone", x -> putLong(x, r0, 14));
        payloadCase.accept("R9 unsorted floors", x -> swap(x, f0, f1, PAIR));
        payloadCase.accept("R9 floor on a tombstone", x -> putLong(x, f1, 20));
        payloadCase.accept("R10 overlapping ranges", x -> putLong(x, r0 + 8, 30));
        payloadCase.accept("R10 adjacent ranges", x -> putLong(x, r0 + 8, 29));
        payloadCase.accept("R10 lo > hi", x -> putLong(x, r0 + 8, 19));
        payloadCase.accept("R10 lo 0", x -> putLong(x, r0, 0));
        payloadCase.accept("R11 orphan omsMap key", x -> putLong(x, oms + 4 + 2 * PAIR, readLong(x, oms + 4 + 2 * PAIR) + 1_000));
        payloadCase.accept("R11 resting leg without an omsMap key", x -> {
            putInt(x, oms, 2);
            final byte[] y = new byte[x.length - PAIR];
            System.arraycopy(x, 0, y, 0, oms + 4 + 2 * PAIR);
            System.arraycopy(x, oms + 4 + 3 * PAIR, y, oms + 4 + 2 * PAIR, x.length - (oms + 4 + 3 * PAIR));
            return y;
        });
        payloadCase.accept("R11 duplicate omsMap key", x -> putLong(x, oms + 4 + PAIR, readLong(x, oms + 4)));
        payloadCase.accept("R11 omsMap value 0", x -> putLong(x, oms + 4 + 8, 0));
        payloadCase.accept("R11 an order the book refused at restore", x -> putLong(x, firstOrderPriceOffset(x), 1));
        // Envelope rules (7.1).
        expectedRule.put("marker 4 in envelope 1", "marker 4");
        cases.put("marker 4 in envelope 1", envelope(good, concat(behaviourOne, emptyV2), 1));
        expectedRule.put("envelope 2 without identity", "without the identity block");
        cases.put("envelope 2 without identity", envelope(good, concat(behaviourOne, emptyV2), 2));
        expectedRule.put("CRC flip", "Corrupt durable snapshot");
        final byte[] flipped = good.clone();
        flipped[16] ^= 0x01;
        cases.put("CRC flip", flipped);

        // Control: the reference image restores.
        restore(good);
        final List<String> failures = new ArrayList<>();
        for (Map.Entry<String, byte[]> c : cases.entrySet()) {
            try {
                restore(c.getValue());
                failures.add(c.getKey() + ": accepted");
            } catch (IllegalStateException refused) {
                final String rule = expectedRule.get(c.getKey());
                if (refused.getMessage() == null || !refused.getMessage().contains(rule)) {
                    failures.add(c.getKey() + ": refused for another reason: " + refused.getMessage());
                }
            } catch (RuntimeException other) {
                failures.add(c.getKey() + ": " + other);
            }
        }
        assertEquals("every corruption refused naming its rule", List.of(), failures);
        assertEquals("all cases ran", 30, cases.size());
    }

    // ==================== cross-version ====================

    @Test
    public void crossVersionImagesFailClosed() {
        try (Run r = reference()) {
            final byte[] s4 = r.r.snapshot();
            final byte[] p = payload(s4);
            // The S2/S3 shape: envelope 1, identity block, no ledger v2 block.
            final byte[] s2Shape = envelope(s4, Arrays.copyOf(p, p.length - BLOCK), 1);
            try {
                restore(s2Shape);
                fail("an S2-shape image (envelope 1 with the identity block) must be refused by S4");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("envelope-1"));
            }
            // An S2 reader refuses every envelope version but 1 (SnapshotCodec.java:308 at 3b95c56:
            // "src.getInt(offset+8)!=1 -> Unsupported/truncated durable snapshot").
            assertEquals("the S4 image is envelope 2, which the S2 reader refuses", 2, envelopeVersion(s4));
        }
    }

    // ---- byte helpers ----

    private static byte[] put8(byte[] x, int at, int v) {
        x[at] = (byte) v;
        return x;
    }

    private static byte[] putInt(byte[] x, int at, int v) {
        new UnsafeBuffer(x).putInt(at, v);
        return x;
    }

    private static byte[] putLong(byte[] x, int at, long v) {
        new UnsafeBuffer(x).putLong(at, v);
        return x;
    }

    private static long readLong(byte[] x, int at) {
        return new UnsafeBuffer(x).getLong(at);
    }

    private static byte[] swap(byte[] x, int a, int b, int n) {
        final byte[] t = Arrays.copyOfRange(x, a, a + n);
        System.arraycopy(x, b, x, a, n);
        System.arraycopy(t, 0, x, b, n);
        return x;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
