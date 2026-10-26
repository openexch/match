// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableOrderIntent;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.Arrays;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.config;
import static com.match.application.engine.BehaviourTwoHarness.fresh2;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract 2.2: the snapshot identity block (marker 3). Layout and position, the forced
 * envelope, the empty-ledger shape, every reader refusal, the unsigned behaviour version and the
 * restore order in both launch modes.
 */
public class SnapshotIdentityBlockTest {

    private static byte[] image(Engine engine, long tradeId) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(engine, tradeId, 0L, buf);
        return Arrays.copyOf(buf.byteArray(), len);
    }

    private static byte[] image(BehaviourTwoHarness h) {
        return image(h.engine, h.publisher.getTradeIdGenerator());
    }

    /** Harness at FRESH {@code config} plus, optionally, one resting durable CREATE. */
    private static BehaviourTwoHarness at(EngineConfigState config, boolean withLedger) {
        final BehaviourTwoHarness h = BehaviourTwoHarness.configMode();
        h.apply(config, 64L);
        if (withLedger) {
            h.engine.setCurrentLogPosition(192L);
            h.engine.acceptDurable(new DurableOrderIntent(17, 1, 100, 9001, 0, FixedPoint.fromDouble(59_000),
                    FixedPoint.fromDouble(1), 0, BehaviourTwoHarness.MARKET, 0, 0, 0), 1_000L);
        }
        h.drain();
        return h;
    }

    private static boolean enveloped(byte[] image) {
        final UnsafeBuffer b = new UnsafeBuffer(image);
        return image.length >= 20 && b.getLong(0) < 0 && b.getInt(8) == 1 && b.getInt(12) == image.length - 20;
    }

    private static byte[] payload(byte[] image) {
        assertTrue(enveloped(image));
        return Arrays.copyOfRange(image, 20, image.length);
    }

    /** Wrap {@code payload} in the durable envelope (magic taken from {@code magicSource}). */
    private static byte[] envelope(byte[] magicSource, byte[] payload) {
        final byte[] out = new byte[payload.length + 20];
        final UnsafeBuffer b = new UnsafeBuffer(out);
        b.putLong(0, new UnsafeBuffer(magicSource).getLong(0));
        b.putInt(8, 1);
        b.putInt(12, payload.length);
        final java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(payload, 0, payload.length);
        b.putInt(16, (int) crc.getValue());
        b.putBytes(20, payload);
        return out;
    }

    private static byte[] identityBlock(int blockVersion, int behaviour, int capacity, int ceiling,
                                        long genesisId, long meEpoch, long nextJournalSeq) {
        final byte[] out = new byte[41];
        final UnsafeBuffer b = new UnsafeBuffer(out);
        b.putByte(0, (byte) 3);
        b.putInt(1, blockVersion);
        b.putInt(5, behaviour);
        b.putInt(9, capacity);
        b.putInt(13, ceiling);
        b.putLong(17, genesisId);
        b.putLong(25, meEpoch);
        b.putLong(33, nextJournalSeq);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void assertRefused(String why, byte[] image) {
        try {
            SnapshotCodec.deserialize(new UnsafeBuffer(image), 0, image.length, Engine.deferredUntilConfig());
            fail("must refuse: " + why);
        } catch (IllegalStateException expected) {
            // boot fails
        }
    }

    // ---- writer ----

    @Test
    public void emptyLedgerImageIsEnvelopedAndEndsWithTheIdentityBlock() {
        try (BehaviourTwoHarness two = at(fresh2(), false);
             BehaviourTwoHarness one = at(config(1, 1, 0, 0, 100_000), false)) {
            final byte[] b1 = image(one);
            assertFalse("behaviour 1, empty ledger: no envelope (S1 shape)", enveloped(b1));
            final byte[] p2 = payload(image(two));
            assertEquals("payload = behaviour-1 image + 41-byte block, no marker-2 block",
                    b1.length + 41, p2.length);
            assertArrayEquals("the bytes before the block are the S1 bytes",
                    b1, Arrays.copyOf(p2, b1.length));
            assertArrayEquals(identityBlock(1, 2, 50_000, 12, G, G, 2),
                    Arrays.copyOfRange(p2, b1.length, p2.length));
        }
    }

    @Test
    public void identityBlockPrecedesTheLedgerBlock() {
        try (BehaviourTwoHarness two = at(fresh2(), true);
             BehaviourTwoHarness one = at(config(1, 1, 0, 0, 100_000), true)) {
            final byte[] p1 = payload(image(one));
            final byte[] p2 = payload(image(two));
            final int ledgerLength = 1 + 4 + 4 + 120;
            final int split = p1.length - ledgerLength;
            assertEquals("ledger marker", 2, p1[split]);
            final byte[] expected = concat(concat(Arrays.copyOf(p1, split),
                    identityBlock(1, 2, 50_000, 12, G, G, 2)), Arrays.copyOfRange(p1, split, p1.length));
            assertArrayEquals(expected, p2);
        }
    }

    @Test
    public void behaviourOneImageIsUnchangedByTheV12Values() {
        try (BehaviourTwoHarness a = at(config(1, 1, 99, 12, 5), false);
             BehaviourTwoHarness b = BehaviourTwoHarness.configMode()) {
            b.apply(EngineConfigState.of(1, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                    BehaviourTwoHarness.btcMarket()), 64L);
            assertArrayEquals("a normalized behaviour-1 record writes the S1 bytes", image(b), image(a));
        }
    }

    // ---- reader refusals ----

    @Test
    public void readerRefusesEveryMalformedIdentityBlock() {
        try (BehaviourTwoHarness two = at(fresh2(), false);
             BehaviourTwoHarness one = at(config(1, 1, 0, 0, 100_000), false)) {
            final byte[] good = image(two);
            final byte[] p = payload(good);
            final byte[] prefix = image(one);

            assertRefused("identity block without the envelope", p);
            assertRefused("truncated block", envelope(good, Arrays.copyOf(p, p.length - 8)));
            assertRefused("block version 2", envelope(good, concat(prefix, identityBlock(2, 2, 50_000, 12, G, G, 2))));
            assertRefused("behaviour 1 in the block", envelope(good, concat(prefix, identityBlock(1, 1, 50_000, 12, G, G, 2))));
            assertRefused("meEpoch != genesisId", envelope(good, concat(prefix, identityBlock(1, 2, 50_000, 12, G, G + 1, 2))));
            assertRefused("nextJournalSeq 1", envelope(good, concat(prefix, identityBlock(1, 2, 50_000, 12, G, G, 1))));
            assertRefused("structurally invalid four (ceiling 11)",
                    envelope(good, concat(prefix, identityBlock(1, 2, 50_000, 11, G, G, 2))));

            // No config block before it: a config-less image followed by the block.
            final Engine legacy = new Engine("direct");
            try {
                final byte[] noConfig = image(legacy, 1L);
                assertFalse(enveloped(noConfig));
                assertRefused("no config block precedes it",
                        envelope(good, concat(noConfig, identityBlock(1, 2, 50_000, 12, G, G, 2))));
            } finally {
                legacy.close();
            }

            // Control: the good image restores.
            final Engine e = Engine.deferredUntilConfig();
            SnapshotCodec.deserialize(new UnsafeBuffer(good), 0, good.length, e);
            assertEquals(G, e.meIdentity().meEpoch());
        }
    }

    // ---- FIX-8: unsigned behaviorVersion ----

    @Test
    public void behaviourAboveTwoToTheThirtyOneRoundTripsUnsignedAndHaltsNotThrows() {
        final long t = 3_000_000_000L;
        try (BehaviourTwoHarness h = at(config(1, t, G, 12, 50_000), false)) {
            assertEquals("the 12-jar halts at FRESH (check a)", 1, h.failFast.get());
            final byte[] img = image(h);
            final Engine e = Engine.deferredUntilConfig();
            final SnapshotCodec.Decoded d = SnapshotCodec.deserialize(new UnsafeBuffer(img), 0, img.length, e);
            assertTrue(d.identityPresent);
            assertEquals(t, e.getEngineConfig().behaviorVersion);
            assertEquals(t, e.meIdentity().behaviorVersion());

            final S2Services.Service svc = new S2Services.Service(true);
            svc.restore(img); // no exception
            assertEquals("restored, then failFast via (a)", 1, svc.failFast.get());
            assertEquals(t, svc.engine().getEngineConfig().behaviorVersion);
        }
    }

    // ---- FIX-6: reader order, both launch modes ----

    @Test
    public void restoreRecordsTheTenFieldConfigAndBindsTheIdentityInBothModes() {
        try (BehaviourTwoHarness h = at(fresh2(), true)) {
            final byte[] img = image(h);
            final String recorded = h.engine.getEngineConfig().describe();
            final String identity = h.engine.meIdentity().toString();

            final Engine configMode = Engine.deferredUntilConfig();
            SnapshotCodec.deserialize(new UnsafeBuffer(img), 0, img.length, configMode);
            assertEquals(recorded, configMode.getEngineConfig().describe());
            assertEquals(identity, configMode.meIdentity().toString());
            assertNotNull("engines rebuilt from the 10-field config", configMode.getEngine(BehaviourTwoHarness.MARKET));
            assertEquals(1, configMode.commandLedger().size());

            final Engine legacyMode = new Engine("array");
            try {
                SnapshotCodec.deserialize(new UnsafeBuffer(img), 0, img.length, legacyMode);
                assertEquals(recorded, legacyMode.getEngineConfig().describe());
                assertEquals(identity, legacyMode.meIdentity().toString());
            } finally {
                legacyMode.close();
            }
            configMode.close();
        }
    }
}
