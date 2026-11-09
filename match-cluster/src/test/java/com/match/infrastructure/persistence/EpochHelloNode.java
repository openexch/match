// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.websocket.PublisherRig;
import com.match.infrastructure.websocket.NegativeControlSeams;
import com.openexchange.cluster.SnapshotImageReader;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.logbuffer.Header;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.NoOpIdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test scope: the {@link MeEpochHelloTest} node (an {@link AppClusteredService} on a
 * {@link MeStubCluster}, not started, restored from a snapshot image) plus a {@link PublisherRig} over its
 * engine and its own broadcaster, and one FORWARDER session. Every epoch-hello / resnapshot API the ME tests touch goes through this
 * class (the seam fields, the onStart restore step, the role-change probe).
 */
final class EpochHelloNode {

    /** Markets of the images: 1 holds a resting bid at 59,000 in the image, 2 is never touched in it. */
    static final int[] MARKETS = {1, 2};

    final MeStubCluster cluster = new MeStubCluster();
    final LaunchModeServices.Service svc = new LaunchModeServices.Service(true);
    final AppClusteredService s = svc.service;
    final MeEpochHelloTest.RecordingSession session;
    PublisherRig rig;

    EpochHelloNode(final long sessionId) {
        session = new MeEpochHelloTest.RecordingSession(sessionId);
        s.restoreRepublish = !NegativeControlSeams.off("RESTORE_REPUBLISH");
        s.resnapshotOnLeader = !NegativeControlSeams.off("RESNAPSHOT_ON_LEADER");
        // inverted on purpose: listing this name in -Dmatch.test.disable switches the withdrawn order ON
        s.resnapshotBeforeIsLeader = NegativeControlSeams.off("RESNAPSHOT_BEFORE_IS_LEADER");
    }

    /** The image: EngineConfig (ARRAY, markets 1 and 2) at 64, one resting bid on market 1 at 192. */
    static byte[] image() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfigWith(1, 2, G, 12, 50_000, false, 4096, 100, 0, MARKETS));
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 1));
        final List<Entry> log = positioned(f);
        try (Replica r = new Replica()) {
            r.apply(log, 0, log.size());
            assertEquals(G, r.h.engine.meIdentity().meEpoch());
            return r.snapshot();
        }
    }

    /** Restore through the codec only (the resnapshot-on-leader cases: no restored-book republish involved). */
    EpochHelloNode restoreByCodec(final byte[] image) {
        svc.restore(image);
        assertEquals(0, svc.failFast.get());
        return this;
    }

    /** Restore through the onStart restore step at the stub cluster's log position (the snapshot position P). */
    EpochHelloNode restoreByOnStartStep(final byte[] image) {
        s.restoreSnapshot(new OneShotSource(image), NoOpIdleStrategy.INSTANCE, cluster.logPosition);
        assertEquals(0, svc.failFast.get());
        return this;
    }

    /** Attach to the stub cluster in {@code role} and open term {@code term}; wire the publishers. */
    EpochHelloNode attach(final Cluster.Role role, final long term) {
        cluster.role = role;
        MeStubCluster.attach(s, cluster);
        s.onNewLeadershipTermEvent(term, cluster.logPosition, cluster.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
        rig = new PublisherRig(svc.engine(), PublisherRig.broadcasterOf(s), MARKETS);
        return this;
    }

    void open() {
        cluster.sessions.add(session);
        s.onSessionOpen(session, cluster.time);
    }

    /** The service is promoted: the role change, then the new term's event (as the container orders them). */
    void promote(final long term) {
        cluster.role = Cluster.Role.LEADER;
        s.onRoleChange(Cluster.Role.LEADER);
        s.onNewLeadershipTermEvent(term, cluster.logPosition, cluster.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
    }

    void demote() {
        cluster.role = Cluster.Role.FOLLOWER;
        s.onRoleChange(Cluster.Role.FOLLOWER);
    }

    /** One leader flush with egress having flowed just now (keep-warm suppressed), as MeEpochHelloTest. */
    void busyFlush() {
        set("lastEgressSendMs", System.currentTimeMillis());
        s.onTimerEvent(s.currentFlushTimerId(), cluster.time);
    }

    /** A node that led {@code agoMs} ago: its periodic resnapshot is not due. */
    void ledAgo(final long agoMs) {
        set("lastPeriodicResnapshotMs", System.currentTimeMillis() - agoMs);
    }

    void roleChangeProbe(final Runnable probe) {
        s.roleChangeProbe = probe;
    }

    /** One LIMIT CREATE through Engine.acceptOrder at log position {@code position} (ENG:855). */
    void create(final int market, final long position, final long omsOrderId, final OrderSide side, final double price) {
        final Engine e = svc.engine();
        e.setCurrentLogPosition(position);
        final CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(500 + omsOrderId);
        cmd.setOmsOrderId(omsOrderId);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(1.0));
        cmd.setTotalPrice(0L);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        e.acceptOrder(market, Engine.CMD_CREATE, cmd, cluster.time);
    }

    long bookVersion(final int market) {
        return svc.engine().getEngine(market).getBookVersion();
    }

    byte[] snapshot() {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(svc.engine(), 0L, buf);
        return Arrays.copyOf(buf.byteArray(), len);
    }

    /** Frames waiting in this node's lossy market-data queue (a follower must hold none). */
    int marketDataQueued() {
        try {
            final Field f = AppClusteredService.class.getDeclaredField("marketDataQueue");
            f.setAccessible(true);
            return ((Queue<?>) f.get(s)).size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    List<PublisherRig.BookFrame> received(final int market) {
        return PublisherRig.bookFrames(session.offered, market);
    }

    private void set(final String field, final long value) {
        try {
            final Field f = AppClusteredService.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setLong(s, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Assert every delta after the first snapshot of {@code frames} chains to the frame before it. */
    static void assertChainedAfterFirstSnapshot(final String what, final List<PublisherRig.BookFrame> frames) {
        int first = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).isSnapshot()) {
                first = i;
                break;
            }
        }
        assertTrue(what + ": no BookSnapshot in " + frames, first >= 0);
        for (int i = first + 1; i < frames.size(); i++) {
            final PublisherRig.BookFrame f = frames.get(i);
            if (!f.isSnapshot()) {
                assertEquals(what + ": delta " + f + " does not chain to " + frames.get(i - 1),
                        frames.get(i - 1).bookVersion, f.fromVersion);
            }
        }
    }

    /** A snapshot image delivered as one unfragmented message, then end of stream. */
    static final class OneShotSource implements SnapshotImageReader.Source {
        private final byte[] image;
        private final Header header = new Header(0, 0);
        private boolean delivered;
        private long position;

        OneShotSource(final byte[] image) {
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
            position += image.length;
            return 1;
        }

        public boolean isEndOfStream() { return delivered; }
        public boolean isClosed() { return false; }
        public long position() { return position; }
    }
}
