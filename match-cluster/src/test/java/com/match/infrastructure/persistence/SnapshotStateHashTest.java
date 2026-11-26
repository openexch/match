// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.engine.MarketConfig;
import com.match.infrastructure.metrics.NodeMetrics;
import com.openexchange.cluster.SnapshotImageReader;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * The snapshot state hash: the CRC32C the envelope already carries, read through
 * {@link SnapshotCodec#stateHash}, and the determinism it rests on. Two replicas that applied the same log hash
 * equal; one extra command on one of them makes the hashes differ. Markets iterate in the same order whichever
 * way a node built its engines. The timer counter in the payload is never moved by the flush timer chain. A
 * restore stamps the loaded image's position and hash; a refused restore stamps nothing.
 */
public class SnapshotStateHashTest {

    @Before
    public void configMode() {
        System.setProperty("match.engine.from.config", "true");
    }

    @After
    public void clear() {
        System.clearProperty("match.engine.from.config");
    }

    private static List<Entry> log(final int orders) {
        final List<Entry> out = new ArrayList<>();
        out.add(new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
        long p = 192;
        for (int i = 0; i < orders; i++) {
            out.add(new Entry(createOrder(100 + i, 9_001 + i, i % 2, 59_000 + (i % 2) * 2_000 + i * 10, 1 + i),
                    p += 128, p));
        }
        return out;
    }

    private static Replica replicaOf(final List<Entry> log) {
        final Replica r = new Replica();
        r.apply(log, 0, log.size());
        return r;
    }

    private static long hashOf(final byte[] image) {
        return SnapshotCodec.stateHash(new UnsafeBuffer(image), 0, image.length);
    }

    @Test
    public void theStateHashIsTheEnvelopeCrc32cOfThePayload() {
        try (Replica r = replicaOf(log(6))) {
            final byte[] image = r.snapshot();
            final CRC32C independent = new CRC32C();
            independent.update(image, SnapshotCodec.ENVELOPE_HEADER_LENGTH,
                    image.length - SnapshotCodec.ENVELOPE_HEADER_LENGTH);
            assertEquals("the payload CRC32C, envelope header excluded, as an unsigned value",
                    independent.getValue(), hashOf(image));
            // Read from inside a larger buffer too: the accessor honours the offset.
            final byte[] shifted = new byte[image.length + 13];
            System.arraycopy(image, 0, shifted, 7, image.length);
            assertEquals(independent.getValue(), SnapshotCodec.stateHash(new UnsafeBuffer(shifted), 7, image.length));
        }
    }

    @Test
    public void theStateHashRefusesBytesThatAreNotOneSealedImage() {
        try (Replica r = replicaOf(log(2))) {
            final byte[] image = r.snapshot();
            final UnsafeBuffer b = new UnsafeBuffer(image);
            assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.stateHash(b, 0, image.length - 1));
            assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.stateHash(b, 0, 19));
            assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.stateHash(b, 8, image.length - 8));
            assertThrows(IllegalArgumentException.class,
                    () -> SnapshotCodec.stateHash(new UnsafeBuffer(new byte[64]), 0, 64));
        }
    }

    @Test
    public void theMetricsHashReadNeverThrowsAndReportsUnknownForBytesThatAreNotOneSealedImage() {
        // The service reads the hash for telemetry only, after the snapshot was recorded or the image restored:
        // a bad read must report "unknown", never throw into the callback and fence the node over a metric.
        try (Replica r = replicaOf(log(2))) {
            final byte[] image = r.snapshot();
            final UnsafeBuffer b = new UnsafeBuffer(image);
            assertEquals(hashOf(image), AppClusteredService.telemetryStateHash(b, 0, image.length));
            assertEquals(NodeMetrics.UNKNOWN_HASH, AppClusteredService.telemetryStateHash(b, 0, image.length - 1));
            assertEquals(NodeMetrics.UNKNOWN_HASH, AppClusteredService.telemetryStateHash(b, 0, 19));
            assertEquals(NodeMetrics.UNKNOWN_HASH, AppClusteredService.telemetryStateHash(b, -1, image.length));
            assertEquals(NodeMetrics.UNKNOWN_HASH,
                    AppClusteredService.telemetryStateHash(new UnsafeBuffer(new byte[64]), 0, 64));
        }
    }

    @Test
    public void replicasThatAppliedTheSameLogHashEqual() {
        final List<Entry> log = log(8);
        try (Replica a = replicaOf(log); Replica b = replicaOf(log)) {
            final byte[] ia = a.snapshot();
            final byte[] ib = b.snapshot();
            assertArrayEquals("same log, same bytes", ia, ib);
            assertEquals(hashOf(ia), hashOf(ib));
            // A replica restored from the image hashes what the image carried.
            try (Replica restored = Replica.restoredFrom(ia)) {
                assertEquals("restore then snapshot keeps the hash", hashOf(ia), hashOf(restored.snapshot()));
            }
        }
    }

    @Test
    public void oneExtraAppliedCommandOnOneReplicaChangesTheHash() {
        // The negative control of the equality above: the hash is not blind to a one-command divergence.
        final List<Entry> log = log(8);
        try (Replica a = replicaOf(log); Replica b = replicaOf(log)) {
            b.apply(new Entry(createOrder(999, 19_999, 0, 59_500, 1), 1_000_000L, 1_000_000L));
            assertNotEquals("one extra applied command", hashOf(a.snapshot()), hashOf(b.snapshot()));
        }
    }

    private static int[] marketOrder(final Engine e) {
        final Int2ObjectHashMap<?>.KeyIterator it = e.getEngines().keySet().iterator();
        final List<Integer> ids = new ArrayList<>();
        while (it.hasNext()) {
            ids.add(it.nextInt());
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    private static byte[] image(final Engine e) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(e, 0L, buf);
        return Arrays.copyOf(buf.byteArray(), len);
    }

    @Test
    public void marketsIterateInOneOrderWhicheverWayANodeBuiltItsEngines() {
        // A legacy-launched node builds its engines from MarketConfig.ALL_MARKETS in the constructor; a
        // config-mode node builds them from the normalized (marketId-ascending) config, here declared in reverse;
        // a restored config-mode node builds them from the image's config block. The snapshot writes markets in
        // the engines map's iteration order, so all three must agree for equal states to hash equal.
        final EngineConfigState.MarketDef[] declared = new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < declared.length; i++) {
            final MarketConfig m = MarketConfig.ALL_MARKETS[declared.length - 1 - i];
            declared[i] = new EngineConfigState.MarketDef(m.marketId, m.symbol, m.basePrice, m.maxPrice, m.tickSize);
        }
        final EngineConfigState config = EngineConfigState.of(1, EngineConfigState.IMPL_ARRAY, 4_096, 100, 0, declared);

        final Engine legacy = com.match.application.engine.TestEngines.withCompiledMarkets("array");
        legacy.setEngineConfig(config);
        final Engine configMode = Engine.deferredUntilConfig();
        configMode.createEnginesFromConfig(config);
        final byte[] legacyImage = image(legacy);
        final Engine restored = Engine.deferredUntilConfig();
        SnapshotCodec.deserialize(new UnsafeBuffer(legacyImage), 0, legacyImage.length, restored);

        assertArrayEquals("config mode vs legacy", marketOrder(legacy), marketOrder(configMode));
        assertArrayEquals("restored vs legacy", marketOrder(legacy), marketOrder(restored));
        assertArrayEquals("equal state, equal bytes (config mode)", legacyImage, image(configMode));
        assertArrayEquals("equal state, equal bytes (restored)", legacyImage, image(restored));
        assertEquals(hashOf(legacyImage), hashOf(image(configMode)));
    }

    private static TimerManager timerManagerOf(final AppClusteredService s) throws Exception {
        final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("timerManager");
        f.setAccessible(true);
        return (TimerManager) f.get(s);
    }

    @Test
    public void theFlushTimerChainNeverMovesTheSnapshottedTimerCounter() throws Exception {
        // The payload carries TimerManager's counter. Only TimerManager.scheduleTimer moves it, and main code
        // never calls that: the leader-only flush chain schedules its own term-scoped ids. Were the chain to use
        // the counter, a leader's snapshot would differ from a follower's at the same position.
        final MeStubCluster c = new MeStubCluster();
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> { throw new AssertionError("failFast"); };
        MeStubCluster.attach(s, c);
        final long before = timerManagerOf(s).getCorrelationId();
        for (long term = 1; term <= 3; term++) {
            s.onNewLeadershipTermEvent(term, c.logPosition, c.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
            for (int i = 0; i < 5; i++) {
                c.time += 250;
                s.onTimerEvent(s.lastArmedFlushTimerId(), c.time);
            }
        }
        assertTrue("non-vacuous: the chain scheduled timers", c.schedules.size() >= 15);
        assertEquals("the snapshotted counter is untouched by the flush chain", before,
                timerManagerOf(s).getCorrelationId());
    }

    /** A snapshot image delivered as one message, then end of stream. */
    private static final class OneShot implements SnapshotImageReader.Source {
        final Header header = new Header(0, 0);
        final byte[] image;
        boolean delivered;

        OneShot(final byte[] image) {
            this.image = image;
            final UnsafeBuffer h = new UnsafeBuffer(new byte[64]);
            h.putByte(FrameDescriptor.flagsOffset(0), (byte) FrameDescriptor.UNFRAGMENTED);
            header.buffer(h);
            header.offset(0);
        }

        public int poll(final FragmentHandler handler, final int limit) {
            if (delivered) {
                return 0;
            }
            delivered = true;
            handler.onFragment(new UnsafeBuffer(image), 0, image.length, header);
            return 1;
        }

        public boolean isEndOfStream() { return delivered; }
        public boolean isClosed() { return false; }
        public long position() { return delivered ? image.length : 0; }
    }

    @Test
    public void aRestoreStampsTheLoadedImagesPositionAndHash() {
        final byte[] image;
        try (Replica r = replicaOf(log(6))) {
            image = r.snapshot();
        }
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> { throw new AssertionError("failFast"); };
        s.restoreRepublish = false;
        assertEquals("never snapshotted before the restore", NodeMetrics.NEVER, s.snapshotState().logPosition());
        assertFalse(s.snapshotState().hashKnown());
        s.restoreSnapshot(new OneShot(image), NoOpIdleStrategy.INSTANCE, 123_456L);
        assertEquals("the snapshot's log position", 123_456L, s.snapshotState().logPosition());
        assertEquals("the loaded image's hash", hashOf(image), s.snapshotState().stateHash());
    }

    @Test
    public void aRefusedRestoreStampsNothing() {
        final byte[] corrupt;
        try (Replica r = replicaOf(log(6))) {
            corrupt = r.snapshot();
        }
        corrupt[corrupt.length - 3] ^= 0x5A; // a payload byte: the envelope CRC refuses it
        final AppClusteredService s = new AppClusteredService();
        final int[] stops = {0};
        s.failFast = () -> stops[0]++;
        s.restoreRepublish = false;
        assertThrows(IllegalStateException.class,
                () -> s.restoreSnapshot(new OneShot(corrupt), NoOpIdleStrategy.INSTANCE, 123_456L));
        assertEquals(1, stops[0]);
        assertEquals(NodeMetrics.NEVER, s.snapshotState().logPosition());
        assertFalse(s.snapshotState().hashKnown());
    }
}
