// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigStateMachine;
import com.match.infrastructure.persistence.BehaviourTwoLog.Replica;
import com.match.infrastructure.persistence.DurableLedgerV2Test.Run;
import com.openexchange.cluster.SnapshotLayouts;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static com.match.infrastructure.persistence.BehaviourTwoLog.cancelOrder;
import static com.match.infrastructure.persistence.BehaviourTwoLog.createOrder;
import static com.match.infrastructure.persistence.BehaviourTwoLog.durableCreateIdHigh17;
import static com.match.infrastructure.persistence.BehaviourTwoLog.engineConfig;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.appliedOrderId;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.cancel;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.create;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.order;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.releaseFrame;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.upTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Snapshot layouts across builds. Committed images of both layouts this build carries (envelope 1 at behaviour 1,
 * envelope 2 at behaviour 2) are the fixtures: each equals a replay of its log, restores under this build, and the
 * restored state is the state a replay reaches. The envelope-2 image also restores under a build that already knows
 * a next layout. The layout a build writes follows the recorded behaviour version, so two builds on one cluster
 * write the same bytes. A layout older than the previous one and an unknown envelope version are refused by name.
 *
 * <p>The fixtures are in the machine's native byte order, as every snapshot is (little-endian on every supported
 * platform). Rewrite them only for a deliberate layout change: {@code -Dsnapshot.layout.golden.write=true}.</p>
 */
public class SnapshotLayoutRegistryTest {

    private static final Path DIR = Path.of("src", "test", "resources", "snapshot-layouts");
    private static final Path GOLDEN_1 = DIR.resolve("match-envelope1-behaviour1.image");
    private static final Path GOLDEN_2 = DIR.resolve("match-envelope2-behaviour2.image");
    private static final int BID = 0, ASK = 1;
    private static final long CAPACITY = 64;
    private static final int ENVELOPE_3 = 3;
    private static final int ENVELOPE_4 = 4;

    /** A stand-in next layout: the envelope-2 image under envelope version 3. Not a design, only a second layout. */
    private static final SnapshotCodec.Layout V2 = SnapshotCodec.LAYOUTS.current().codec();
    private static final SnapshotCodec.Layout V3 = new SnapshotCodec.Layout() {
        @Override
        public int write(final Engine engine, final long timerCorrelationId, final MutableDirectBuffer dst) {
            final ExpandableArrayBuffer inner = new ExpandableArrayBuffer();
            final int length = SnapshotCodec.LAYOUTS.readerFor(innerVersion(engine)).codec().write(engine, timerCorrelationId, inner);
            dst.putBytes(0, inner, 0, length);
            dst.putInt(SnapshotCodec.ENVELOPE_VERSION_OFFSET, ENVELOPE_3);
            dst.putLong(length, innerVersion(engine)); // a trailer naming the body it wraps
            return length + 8;
        }

        @Override
        public SnapshotCodec.Decoded read(final DirectBuffer src, final int offset, final int length, final Engine engine) {
            if (src.getInt(offset + SnapshotCodec.ENVELOPE_VERSION_OFFSET) != ENVELOPE_3) {
                throw new IllegalStateException("matching snapshot: not an envelope-3 image");
            }
            final byte[] body = new byte[length - 8];
            src.getBytes(offset, body);
            final int inner = (int) src.getLong(offset + length - 8);
            new UnsafeBuffer(body).putInt(SnapshotCodec.ENVELOPE_VERSION_OFFSET, inner);
            return SnapshotCodec.LAYOUTS.readerFor(inner).codec().read(new UnsafeBuffer(body), 0, body.length, engine);
        }

        private int innerVersion(final Engine engine) {
            return SnapshotCodec.recordedBehavior(engine) >= 2 ? SnapshotCodec.ENVELOPE_V2 : SnapshotCodec.ENVELOPE_V1;
        }
    };

    /** The build after this one: envelope 3 from behaviour 3, envelope 2 still carried, envelope 1 retired. */
    private static final SnapshotLayouts<SnapshotCodec.Layout> NEXT_BUILD = SnapshotLayouts.<SnapshotCodec.Layout>of("matching snapshot")
            .current(ENVELOPE_3, ENVELOPE_3, 3L, V3)
            .previous(SnapshotCodec.ENVELOPE_V2, SnapshotCodec.ENVELOPE_V2, 2L, V2)
            .retired(SnapshotCodec.ENVELOPE_V1, SnapshotCodec.ENVELOPE_V1)
            .build();

    /** Two builds on: envelope 2 is retired. */
    private static final SnapshotLayouts<SnapshotCodec.Layout> BUILD_AFTER_NEXT = SnapshotLayouts.<SnapshotCodec.Layout>of("matching snapshot")
            .current(ENVELOPE_4, ENVELOPE_4, 4L, V3)
            .previous(ENVELOPE_3, ENVELOPE_3, 3L, V3)
            .retired(SnapshotCodec.ENVELOPE_V2, SnapshotCodec.ENVELOPE_V2)
            .retired(SnapshotCodec.ENVELOPE_V1, SnapshotCodec.ENVELOPE_V1)
            .build();

    @Before
    public void configMode() {
        System.setProperty("match.engine.from.config", "true");
    }

    @After
    public void clear() {
        System.clearProperty("match.engine.from.config");
    }

    // ------------------------------------------------------------------------------------------------- the logs

    /** The behaviour-2 log: resting legs on both sides, a partial fill, a cancel, released ids and ranges. */
    private static Run behaviourTwo() {
        final Run r = DurableLedgerV2Test.fresh(CAPACITY);
        appliedOrderId(r.send(create(11, 0, 100, BID, 59_000, 1)));
        final long leg12 = appliedOrderId(r.send(create(12, 0, 100, BID, 59_001, 1)));
        appliedOrderId(r.send(cancel(12, 1, 100, leg12)));
        r.send(releaseFrame(1, upTo(12, 0)));
        appliedOrderId(r.send(create(13, 0, 101, ASK, 60_500, 2)));
        r.send(releaseFrame(2, upTo(13, 0)));
        appliedOrderId(r.send(create(14, 0, 102, BID, 60_500, 0.5))); // crosses 13: a partial fill
        appliedOrderId(r.send(create(15, 0, 100, ASK, 61_000, 3)));
        r.send(releaseFrame(3, order(20), order(30)));
        return r;
    }

    /** What the behaviour-2 log goes on to say after the snapshot; returns the journal frames it produced. */
    private static List<byte[]> behaviourTwoTail(final Run r) {
        final List<byte[]> out = new ArrayList<>();
        out.addAll(r.send(create(16, 0, 103, BID, 60_500, 1)));       // takes more of 13
        out.addAll(r.send(create(17, 0, 104, ASK, 62_000, 1)));
        out.addAll(r.send(create(11, 0, 100, BID, 59_000, 1)));       // a resend of an id the image holds
        out.addAll(r.send(releaseFrame(4, upTo(14, 0))));
        return out;
    }

    /** The behaviour-1 log: legacy-lane orders and one durable create, so the v1 ledger block has an entry. */
    private static Run behaviourOne() {
        final Run r = DurableLedgerV2Test.freshWith(engineConfig(1, 1, 0, 0, 100_000));
        for (int i = 0; i < 6; i++) {
            r.send(createOrder(100 + i, 9_001 + i, i % 2, 59_000 + (i % 2) * 2_000 + i * 10, 1 + i));
        }
        r.send(durableCreateIdHigh17(1, 200, 9_100, BID, 58_000, 1));
        r.send(createOrder(300, 9_200, BID, 61_000, 0.5)); // crosses the best ask: a partial fill
        return r;
    }

    private static List<byte[]> behaviourOneTail(final Run r) {
        final List<byte[]> out = new ArrayList<>();
        out.addAll(r.send(createOrder(301, 9_201, ASK, 59_000, 0.25)));
        out.addAll(r.send(durableCreateIdHigh17(1, 200, 9_100, BID, 58_000, 1))); // a resend
        out.addAll(r.send(cancelOrder(100, 1)));
        return out;
    }

    // --------------------------------------------------------------------------------------------- the fixtures

    @Test
    public void theCommittedImagesAreTheSnapshotsOfTheirLogs() throws IOException {
        try (Run two = behaviourTwo(); Run one = behaviourOne()) {
            final byte[] image2 = image(SnapshotCodec.LAYOUTS, two.engine());
            final byte[] image1 = image(SnapshotCodec.LAYOUTS, one.engine());
            if (Boolean.getBoolean("snapshot.layout.golden.write")) {
                Files.createDirectories(DIR);
                Files.write(GOLDEN_2, image2);
                Files.write(GOLDEN_1, image1);
            }
            final byte[] golden2 = Files.readAllBytes(GOLDEN_2);
            final byte[] golden1 = Files.readAllBytes(GOLDEN_1);
            assertSameImage("envelope 2 moved: a replay of the fixture's log no longer writes the committed image", golden2, image2);
            assertSameImage("envelope 1 moved: a replay of the fixture's log no longer writes the committed image", golden1, image1);
            assertEquals(2, version(golden2));
            assertEquals(1, version(golden1));
            assertTrue("the behaviour-2 image holds resting legs", DurableLedgerV2Test.resting(two.engine()) >= 3);
            assertTrue("the behaviour-1 image holds a v1 ledger entry", one.engine().commandLedger().size() >= 1);
            // the comparison can fail: one changed byte in the expected image is named
            final byte[] drifted = golden2.clone();
            drifted[40] ^= 0x01;
            final AssertionError drift = assertThrows(AssertionError.class,
                    () -> assertSameImage("envelope 2 moved", drifted, image2));
            assertTrue(drift.getMessage(), drift.getMessage().contains("envelope 2 moved: first difference at byte 40"));
        }
    }

    @Test
    public void theEnvelope2ImageRestoresUnderThisBuild() throws IOException {
        assertRestoresLikeAReplay(SnapshotCodec.LAYOUTS, GOLDEN_2, true);
    }

    @Test
    public void theEnvelope1ImageRestoresUnderThisBuild() throws IOException {
        assertRestoresLikeAReplay(SnapshotCodec.LAYOUTS, GOLDEN_1, false);
    }

    @Test
    public void theEnvelope2ImageRestoresUnderABuildThatKnowsTheNextLayout() throws IOException {
        assertRestoresLikeAReplay(NEXT_BUILD, GOLDEN_2, true);
    }

    // ----------------------------------------------------------------------------------- two builds, one cluster

    @Test
    public void twoBuildsOnOneClusterWriteEqualBytes() {
        try (Run older = behaviourTwo(); Run newer = behaviourTwo()) {
            assertMixedBuildsWriteEqualBytes(image(SnapshotCodec.LAYOUTS, older.engine()), image(NEXT_BUILD, newer.engine()));
            behaviourTwoTail(older);
            behaviourTwoTail(newer);
            assertMixedBuildsWriteEqualBytes(image(SnapshotCodec.LAYOUTS, older.engine()), image(NEXT_BUILD, newer.engine()));
        }
    }

    /** The control: a build that writes its newest layout whatever the log says breaks the mixed cluster. */
    @Test
    public void aBuildThatPicksTheLayoutByItsOwnVersionFailsTheEqualBytesCheck() {
        try (Run older = behaviourTwo(); Run newer = behaviourTwo()) {
            final byte[] byJarVersion = written(NEXT_BUILD.current().codec(), newer.engine());
            final AssertionError broken = assertThrows(AssertionError.class,
                    () -> assertMixedBuildsWriteEqualBytes(image(SnapshotCodec.LAYOUTS, older.engine()), byJarVersion));
            assertTrue(broken.getMessage(), broken.getMessage().contains(
                    "two builds wrote different snapshot bytes at one log position: envelope version 2 against 3"));
            // the same engine through the rule writes the older build's bytes
            assertMixedBuildsWriteEqualBytes(image(SnapshotCodec.LAYOUTS, older.engine()), image(NEXT_BUILD, newer.engine()));
        }
    }

    // ------------------------------------------------------------------------------------------------- refusals

    @Test
    public void aLayoutOlderThanThePreviousOneIsRefused() throws IOException {
        final byte[] golden1 = Files.readAllBytes(GOLDEN_1);
        final byte[] golden2 = Files.readAllBytes(GOLDEN_2);
        // on the next build envelope 1 is two layouts back; two builds on, envelope 2 is
        final String one = refusal(NEXT_BUILD, golden1);
        assertTrue(one, one.contains("matching snapshot layout v1 (tag 1) refused: older than the previous layout;"
                + " this build reads only v3 and v2"));
        final String two = refusal(BUILD_AFTER_NEXT, golden2);
        assertTrue(two, two.contains("matching snapshot layout v2 (tag 2) refused: older than the previous layout;"
                + " this build reads only v4 and v3"));
        // the images are fine: this build restores both
        assertSameImage("envelope 1 intact", golden1, image(SnapshotCodec.LAYOUTS, restored(SnapshotCodec.LAYOUTS, golden1).h.engine));
        assertSameImage("envelope 2 intact", golden2, image(SnapshotCodec.LAYOUTS, restored(SnapshotCodec.LAYOUTS, golden2).h.engine));
    }

    @Test
    public void anUnknownEnvelopeVersionIsRefused() throws IOException {
        final byte[] golden2 = Files.readAllBytes(GOLDEN_2);
        for (final int unknown : new int[] {ENVELOPE_3, 0, -1, 99, Integer.MIN_VALUE}) {
            final String text = refusal(SnapshotCodec.LAYOUTS, reversioned(golden2, unknown));
            assertTrue(text, text.contains("matching snapshot layout tag " + unknown
                    + " refused: unknown to this build, which reads only v2 and v1"));
        }
        // a real envelope-3 image, as a next build would write it, reaches a member still on this build
        try (Run r = behaviourTwo()) {
            final byte[] v3 = written(V3, r.engine());
            assertEquals(3, version(v3));
            final String text = refusal(SnapshotCodec.LAYOUTS, v3);
            assertTrue(text, text.contains("layout tag 3 refused: unknown to this build"));
        }
        // a bare or foreign image keeps its own refusal, before any layout question
        final byte[] bare = Arrays.copyOfRange(golden2, SnapshotCodec.ENVELOPE_HEADER_LENGTH, golden2.length);
        final String foreign = refusal(SnapshotCodec.LAYOUTS, bare);
        assertTrue(foreign, foreign.contains("snapshot envelope magic missing"));
    }

    @Test
    public void anImageWhoseLayoutIsNotTheOneItsBehaviourWritesIsRefused() throws IOException {
        try (Run r = behaviourTwo()) {
            // a readable envelope-3 image that records behaviour 2, which writes envelope 2
            final String text = refusal(NEXT_BUILD, written(V3, r.engine()));
            assertTrue(text, text.contains("matching snapshot layout v3 does not belong to behaviour 2, which writes v2"));
            // the rule is what refuses it: the engine's own image restores on that build
            restored(NEXT_BUILD, image(NEXT_BUILD, r.engine())).close();
        }
        // this build: the envelope version and the body it claims must agree (the existing structural rules)
        final byte[] golden2 = Files.readAllBytes(GOLDEN_2);
        final byte[] golden1 = Files.readAllBytes(GOLDEN_1);
        final String twoAsOne = refusal(SnapshotCodec.LAYOUTS, reversioned(golden2, 1));
        assertTrue(twoAsOne, twoAsOne.contains("envelope-1"));
        final String oneAsTwo = refusal(SnapshotCodec.LAYOUTS, reversioned(golden1, 2));
        assertTrue(oneAsTwo, oneAsTwo.contains("envelope 2"));
        // and a writer is never handed an engine of another behaviour
        try (Run r = behaviourTwo()) {
            final String writer = assertThrows(IllegalStateException.class, () -> written(
                    SnapshotCodec.LAYOUTS.previous().codec(), r.engine())).getMessage();
            assertTrue(writer, writer.contains("the envelope-1 writer was handed a behaviour-2 engine"));
        }
    }

    @Test
    public void everyBehaviourThisBuildRunsHasALayoutThisBuildWrites() {
        int supported = 0;
        for (long behavior = 1; behavior <= 63; behavior++) {
            if (EngineConfigStateMachine.supportsBehaviour(behavior)) {
                supported++;
                assertTrue("behaviour " + behavior, SnapshotCodec.LAYOUTS.writes(behavior));
                assertEquals(behavior == 1 ? 1 : 2, SnapshotCodec.LAYOUTS.writtenBy(behavior).version());
            }
        }
        assertEquals("behaviours 1 and 2", 2, supported);
        // no recorded config counts as behaviour 1
        assertEquals(1L, SnapshotCodec.recordedBehavior(Engine.deferredUntilConfig()));
    }

    // -------------------------------------------------------------------------------------------------- helpers

    /**
     * The committed image restored on {@code build} is the state a replay of its log reaches on that build: equal
     * image bytes, equal state hash, and the same journal frames and image after more log.
     */
    private static void assertRestoresLikeAReplay(final SnapshotLayouts<SnapshotCodec.Layout> build, final Path fixture,
                                                  final boolean two) throws IOException {
        final byte[] golden = Files.readAllBytes(fixture);
        try (Run replayed = two ? behaviourTwo() : behaviourOne(); Replica restoredReplica = restored(build, golden)) {
            final Run restored = new Run(restoredReplica);
            restored.position = replayed.position;
            restored.timestamp = replayed.timestamp;
            final byte[] fromRestore = image(build, restored.engine());
            final byte[] fromReplay = image(build, replayed.engine());
            assertSameImage("the restored engine writes the committed image again", golden, fromRestore);
            assertSameImage("the replayed engine writes the committed image", golden, fromReplay);
            assertEquals("state hash (the production gauge: the envelope's payload CRC32C)", stateHash(fromReplay),
                    stateHash(fromRestore));
            assertEquals("SHA-256 of the image", sha256(fromReplay), sha256(fromRestore));

            final List<byte[]> expected = two ? behaviourTwoTail(replayed) : behaviourOneTail(replayed);
            final List<byte[]> actual = two ? behaviourTwoTail(restored) : behaviourOneTail(restored);
            assertTrue("the continuation produced journal frames", expected.size() >= 3);
            BehaviourTwoLog.assertFramesEqual("the log continues identically after the restore", expected, actual);
            assertSameImage("and reaches the same image", image(build, replayed.engine()), image(build, restored.engine()));
            assertNotEquals("the continuation changed the state", sha256(golden), sha256(image(build, restored.engine())));
        }
    }

    /** The image {@code build} writes for {@code engine}: the layout comes from the recorded behaviour. */
    private static byte[] image(final SnapshotLayouts<SnapshotCodec.Layout> build, final Engine engine) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int length = SnapshotCodec.serialize(build, engine, 0L, buf);
        return Arrays.copyOf(buf.byteArray(), length);
    }

    /** An image written by one layout's codec directly, whatever the engine's behaviour. */
    private static byte[] written(final SnapshotCodec.Layout codec, final Engine engine) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int length = codec.write(engine, 0L, buf);
        return Arrays.copyOf(buf.byteArray(), length);
    }

    private static Replica restored(final SnapshotLayouts<SnapshotCodec.Layout> build, final byte[] image) {
        final Replica r = new Replica();
        SnapshotCodec.deserialize(build, new UnsafeBuffer(image), 0, image.length, r.h.engine);
        return r;
    }

    private static String refusal(final SnapshotLayouts<SnapshotCodec.Layout> build, final byte[] image) {
        try (Replica r = new Replica()) {
            return assertThrows(IllegalStateException.class,
                    () -> SnapshotCodec.deserialize(build, new UnsafeBuffer(image), 0, image.length, r.h.engine)).getMessage();
        }
    }

    /** {@code image} under another envelope version; the payload and its CRC are untouched. */
    private static byte[] reversioned(final byte[] image, final int version) {
        final byte[] copy = image.clone();
        new UnsafeBuffer(copy).putInt(SnapshotCodec.ENVELOPE_VERSION_OFFSET, version);
        return copy;
    }

    private static int version(final byte[] image) {
        return new UnsafeBuffer(image).getInt(SnapshotCodec.ENVELOPE_VERSION_OFFSET);
    }

    /** The rule a cluster on two builds lives by: at one log position both write the same image. */
    private static void assertMixedBuildsWriteEqualBytes(final byte[] older, final byte[] newer) {
        if (!Arrays.equals(older, newer)) {
            throw new AssertionError("two builds wrote different snapshot bytes at one log position: envelope version "
                    + version(older) + " against " + version(newer) + ", " + older.length + " against " + newer.length
                    + " bytes");
        }
    }

    private static void assertSameImage(final String what, final byte[] expected, final byte[] actual) {
        final int at = Arrays.mismatch(expected, actual);
        if (at >= 0) {
            throw new AssertionError(what + ": first difference at byte " + at + " (" + expected.length + " against "
                    + actual.length + " bytes)");
        }
    }

    private static long stateHash(final byte[] image) {
        return SnapshotCodec.stateHash(new UnsafeBuffer(image), 0, image.length);
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
