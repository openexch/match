// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.openexchange.cluster.SnapshotImageReader;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S6 contract 6 (M Q12, FIX-9): the ME snapshot loader waits for end of stream. Script E (an empty start)
 * and script T (a pause between chunks) restore the whole engine for a behaviour-2 and a behaviour-1 image; a
 * closed image, a zero-byte image and a codec refusal each fence the service and run failFast.
 */
public class MeLoaderWaitsForEndOfStreamTest {

    @Before
    public void configMode() {
        System.setProperty("match.engine.from.config", "true");
    }

    @After
    public void clear() {
        System.clearProperty("match.engine.from.config");
    }

    /** One poll of a scripted snapshot image: messages delivered, then whether EOS / closed hold. */
    private record Step(byte[][] messages, boolean eos, boolean closed) {
    }

    private static Step empty() {
        return new Step(new byte[0][], false, false);
    }

    private static Step chunk(final byte[] m) {
        return new Step(new byte[][] {m}, false, false);
    }

    private static Step chunkEos(final byte[] m) {
        return new Step(m == null ? new byte[0][] : new byte[][] {m}, true, false);
    }

    private static Step closed() {
        return new Step(new byte[0][], false, true);
    }

    private static final class Script implements SnapshotImageReader.Source {
        final Deque<Step> steps = new ArrayDeque<>();
        final Header header = new Header(0, 0);
        boolean eos;
        boolean closed;
        long position;

        Script(final Step... s) {
            steps.addAll(Arrays.asList(s));
            final UnsafeBuffer h = new UnsafeBuffer(new byte[64]);
            h.putByte(FrameDescriptor.flagsOffset(0), (byte) FrameDescriptor.UNFRAGMENTED);
            header.buffer(h);
            header.offset(0);
        }

        public int poll(final FragmentHandler handler, final int limit) {
            final Step s = steps.poll();
            if (s == null) {
                return 0;
            }
            for (byte[] m : s.messages) {
                handler.onFragment(new UnsafeBuffer(m), 0, m.length, header);
                position += m.length;
            }
            eos |= s.eos;
            closed |= s.closed;
            return s.messages.length;
        }

        public boolean isEndOfStream() { return eos; }
        public boolean isClosed() { return closed; }
        public long position() { return position; }
    }

    private static byte[] behaviourTwoImage() {
        try (Run r = fresh(1_000)) {
            appliedOrderId(r.send(create(801, 0, 100, 0, 59_000, 1)));
            appliedOrderId(r.send(create(802, 0, 101, 1, 61_000, 2)));
            appliedOrderId(r.send(create(803, 0, 102, 0, 58_000, 3)));
            return r.r.snapshot();
        }
    }

    private static byte[] behaviourOneImage() {
        try (Replica one = new Replica()) {
            one.apply(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            long p = 192;
            for (int i = 0; i < 6; i++) {
                one.apply(new Entry(createOrder(100 + i, 9001 + i, i % 2, 59_000 + (i % 2) * 2_000 + i * 10, 1 + i), p += 128, p));
            }
            assertTrue("behaviour-1 image has resting orders", resting(one.h.engine) >= 6);
            return one.snapshot();
        }
    }

    private static Engine engineOf(final AppClusteredService s) throws Exception {
        final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (Engine) f.get(s);
    }

    private static byte[] reserialize(final AppClusteredService s) throws Exception {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(engineOf(s), 0L, buf);
        return Arrays.copyOf(buf.byteArray(), len);
    }

    private static boolean callbackFailed(final AppClusteredService s) throws Exception {
        final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("applicationCallbackFailed");
        f.setAccessible(true);
        return f.getBoolean(s);
    }

    private static AppClusteredService service(final int[] stops, final boolean[] fencedFirst) {
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> {
            stops[0]++;
            try {
                fencedFirst[0] = callbackFailed(s);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        return s;
    }

    private static void restoresWholeEngine(final String what, final byte[] image, final Script script) throws Exception {
        final int[] stops = {0};
        final boolean[] fenced = {false};
        final AppClusteredService s = service(stops, fenced);
        s.loadSnapshot(script, NoOpIdleStrategy.INSTANCE);
        assertEquals(what + ": no failFast", 0, stops[0]);
        assertArrayEquals(what + ": books, ledger and identity equal to the original", image, reserialize(s));
    }

    @Test
    public void scriptEAndScriptTRestoreTheWholeEngineAtBothBehaviours() throws Exception {
        final byte[][] images = {behaviourTwoImage(), behaviourOneImage()};
        for (int k = 0; k < images.length; k++) {
            final byte[] image = images[k];
            final String b = k == 0 ? " (behaviour 2, enveloped)" : " (behaviour 1)";
            final int cut = image.length / 2;
            final byte[] c1 = Arrays.copyOfRange(image, 0, cut);
            final byte[] c2 = Arrays.copyOfRange(image, cut, image.length);
            restoresWholeEngine("script E" + b, image, new Script(empty(), chunk(c1), empty(), chunkEos(c2)));
            restoresWholeEngine("script T" + b, image, new Script(chunk(c1), empty(), chunkEos(c2)));
        }
    }

    /** One refusal case (review FIX-1: one method per row, so each has its own RED line). */
    private static void assertRefusalStopsTheNode(final String name, final Script script) throws Exception {
        final int[] stops = {0};
        final boolean[] fencedFirst = {false};
        final AppClusteredService s = service(stops, fencedFirst);
        assertThrows(name + ": refused", IllegalStateException.class, () -> s.loadSnapshot(script, NoOpIdleStrategy.INSTANCE));
        assertEquals(name + ": failFast ran once", 1, stops[0]);
        assertTrue(name + ": failApplicationCallback ran before failFast", fencedFirst[0]);
    }

    @Test
    public void restoreRefusalStopsTheNodeClosedWithoutEndOfStream() throws Exception {
        final byte[] image = behaviourTwoImage();
        assertRefusalStopsTheNode("closed without EOS",
                new Script(chunk(Arrays.copyOfRange(image, 0, image.length / 2)), empty(), closed()));
    }

    @Test
    public void restoreRefusalStopsTheNodeZeroBytes() throws Exception {
        assertRefusalStopsTheNode("zero bytes", new Script(empty(), chunkEos(null)));
    }

    @Test
    public void restoreRefusalStopsTheNodeCodecRefusal() throws Exception {
        final byte[] corrupt = behaviourTwoImage();
        corrupt[corrupt.length - 3] ^= 0x5A; // payload byte: the envelope CRC refuses it
        assertRefusalStopsTheNode("codec refusal", new Script(chunkEos(corrupt)));
    }
}
