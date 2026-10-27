// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.infrastructure.generated.ClusterHeartbeatDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.Publication;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S12 W9 (D-S12-1, F-ME-1): the epoch hello. The keep-warm ClusterHeartbeat, the only egress frame that carries
 * meEpoch, is sent only when no egress reached any session for 1 s, so a forwarder that connects to a busy ME never
 * learns the epoch it must bind before it registers with the core. The leader now sends every new session (and
 * every session present at its first flush of a term) one heartbeat from the flush branch; it is node-local egress
 * only.
 */
public class MeEpochHelloTest {

    private static final long SESSION = 77L;

    /** A bound (epoch G) service restored from a G1 snapshot, attached to a stub cluster of the given role. */
    private static final class Node {
        final MeStubCluster cluster = new MeStubCluster();
        final S2Services.Service svc = new S2Services.Service(true);
        final AppClusteredService s = svc.service;
        final RecordingSession session = new RecordingSession(SESSION);

        Node(final byte[] image, final Cluster.Role role) {
            svc.restore(image);
            assertEquals(0, svc.failFast.get());
            cluster.role = role;
            MeStubCluster.attach(s, cluster);
            s.onNewLeadershipTermEvent(5, cluster.logPosition, cluster.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
        }

        void open() {
            cluster.sessions.add(session);
            s.onSessionOpen(session, cluster.time);
        }

        /** One flush with egress having flowed just now (the busy ME: the keep-warm is suppressed). */
        void busyFlush() {
            setLastEgressSendMs(s, System.currentTimeMillis());
            s.onTimerEvent(s.currentFlushTimerId(), cluster.time);
        }

        byte[] snapshot() {
            final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(svc.engine(), 0L, buf);
            return Arrays.copyOf(buf.byteArray(), len);
        }
    }

    private static void setLastEgressSendMs(final AppClusteredService s, final long ms) {
        try {
            final Field f = AppClusteredService.class.getDeclaredField("lastEgressSendMs");
            f.setAccessible(true);
            f.setLong(s, ms);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A service-side session with a FORWARDER principal recording every offer; scripted offer results. */
    static final class RecordingSession implements ClientSession {
        final long id;
        final byte[] principal = TestAuth.principal(ClusterSessionClass.FORWARDER);
        final List<byte[]> offered = new ArrayList<>();
        final List<Long> scripted = new ArrayList<>();

        RecordingSession(final long id) {
            this.id = id;
        }

        public long id() { return id; }
        public int responseStreamId() { return 0; }
        public String responseChannel() { return "aeron:ipc"; }
        public byte[] encodedPrincipal() { return principal; }
        public void close() { }
        public boolean isClosing() { return false; }

        public long offer(final DirectBuffer buffer, final int offset, final int length) {
            if (!scripted.isEmpty()) {
                final long r = scripted.remove(0);
                if (r <= 0) {
                    return r;
                }
            }
            final byte[] b = new byte[length];
            buffer.getBytes(offset, b);
            offered.add(b);
            return 1;
        }

        public long offer(final io.aeron.DirectBufferVector[] vectors) { return 1; }

        public long tryClaim(final int length, final io.aeron.logbuffer.BufferClaim bufferClaim) {
            throw new UnsupportedOperationException();
        }

        /** The meEpoch of every ClusterHeartbeat this session received. */
        List<Long> heartbeatEpochs() {
            final List<Long> epochs = new ArrayList<>();
            final MessageHeaderDecoder h = new MessageHeaderDecoder();
            for (byte[] frame : offered) {
                final UnsafeBuffer b = new UnsafeBuffer(frame);
                h.wrap(b, 0);
                if (h.templateId() == ClusterHeartbeatDecoder.TEMPLATE_ID) {
                    epochs.add(new ClusterHeartbeatDecoder().wrapAndApplyHeader(b, 0, new MessageHeaderDecoder()).meEpoch());
                }
            }
            return epochs;
        }
    }

    private static byte[] g1Image() {
        final List<byte[]> f = new ArrayList<>();
        f.add(engineConfig(1, 2, G, 12, 50_000));
        f.add(durableCreate(1, 100, 9001, 0, 59_000, 1));
        final List<Entry> log = positioned(f);
        try (Replica r = new Replica()) {
            r.apply(log, 0, log.size());
            assertEquals(G, r.h.engine.meIdentity().meEpoch());
            return r.snapshot();
        }
    }

    @Test
    public void newSessionGetsHeartbeatUnderLoad() {
        final Node leader = new Node(g1Image(), Cluster.Role.LEADER);
        leader.open();
        leader.busyFlush();
        leader.busyFlush();
        // On the base no heartbeat reaches the session while egress flows faster than 1/s (ACS keep-warm rule).
        assertEquals("one heartbeat with the recorded epoch within 2 flushes", List.of(G), leader.session.heartbeatEpochs());
        assertEquals(1, leader.s.epochHelloSentCount());
        assertEquals(0, leader.s.epochHelloPendingCount());
    }

    @Test
    public void helloIsLeaderLocalEgressOnly() {
        final byte[] image = g1Image();
        final Node leader = new Node(image, Cluster.Role.LEADER);
        final Node a = new Node(image, Cluster.Role.FOLLOWER);
        final Node b = new Node(image, Cluster.Role.FOLLOWER);
        final byte[] before = leader.snapshot();
        for (Node n : List.of(leader, a, b)) {
            n.open();
        }
        for (int i = 0; i < 3; i++) {
            for (Node n : List.of(leader, a, b)) {
                n.busyFlush();
            }
        }
        assertEquals(1, leader.s.epochHelloSentCount());
        assertEquals(0, a.s.epochHelloSentCount());
        assertEquals(0, b.s.epochHelloSentCount());
        assertEquals("the session received exactly one hello", List.of(G), leader.session.heartbeatEpochs());
        assertTrue(a.session.heartbeatEpochs().isEmpty());
        assertTrue(b.session.heartbeatEpochs().isEmpty());
        // nothing replicated changed: the snapshot (the A+SNAPSHOT+B splice's middle) is the same bytes on every
        // member, before and after the hello
        assertArrayEquals(before, leader.snapshot());
        assertArrayEquals(before, a.snapshot());
        assertArrayEquals(before, b.snapshot());
    }

    @Test
    public void helloIsKeptOnBackPressureAndNotConnectedAndDroppedOnClose() {
        final Node leader = new Node(g1Image(), Cluster.Role.LEADER);
        leader.session.scripted.add((long) Publication.BACK_PRESSURED);
        leader.session.scripted.add((long) Publication.NOT_CONNECTED);
        leader.open();
        leader.busyFlush();
        leader.busyFlush();
        assertTrue(leader.session.heartbeatEpochs().isEmpty());
        assertEquals(1, leader.s.epochHelloPendingCount());
        leader.busyFlush();
        assertEquals(List.of(G), leader.session.heartbeatEpochs());
        assertEquals(0, leader.s.epochHelloPendingCount());

        final Node closing = new Node(g1Image(), Cluster.Role.LEADER);
        closing.session.scripted.add((long) Publication.BACK_PRESSURED);
        closing.open();
        closing.busyFlush();
        closing.s.onSessionClose(closing.session, closing.cluster.time, io.aeron.cluster.codecs.CloseReason.CLIENT_ACTION);
        closing.cluster.sessions.clear();
        assertEquals(0, closing.s.epochHelloPendingCount());
    }

    @Test
    public void aNewTermGreetsEverySessionPresentAtItsFirstLeaderFlush() {
        final Node leader = new Node(g1Image(), Cluster.Role.LEADER);
        leader.open();
        leader.busyFlush();
        assertEquals(1, leader.session.heartbeatEpochs().size());
        // failover back to this member in term 6: the forwarder's session survived; it is greeted again
        leader.s.onNewLeadershipTermEvent(6, leader.cluster.logPosition, leader.cluster.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
        leader.busyFlush();
        assertEquals(List.of(G, G), leader.session.heartbeatEpochs());
        assertEquals(2, leader.s.epochHelloSentCount());
        // a follower greets nobody, and losing leadership forgets any pending hello
        leader.cluster.role = Cluster.Role.FOLLOWER;
        leader.s.onRoleChange(Cluster.Role.FOLLOWER);
        assertEquals(0, leader.s.epochHelloPendingCount());
    }

    @Test
    public void aSessionOpenedBeforeTheFreshGetsItsHelloOnceTheEpochIsBound() {
        final MeStubCluster cluster = new MeStubCluster();
        final S2Services.Service svc = new S2Services.Service(true); // config mode, no EngineConfig yet: meEpoch 0
        final AppClusteredService s = svc.service;
        MeStubCluster.attach(s, cluster);
        s.onNewLeadershipTermEvent(5, cluster.logPosition, cluster.time, 0, 0, 7, TimeUnit.MILLISECONDS, 0);
        final RecordingSession session = new RecordingSession(SESSION);
        cluster.sessions.add(session);
        s.onSessionOpen(session, cluster.time);
        for (int i = 0; i < 3; i++) {
            setLastEgressSendMs(s, System.currentTimeMillis());
            s.onTimerEvent(s.currentFlushTimerId(), cluster.time);
        }
        assertTrue("no hello of epoch 0", session.heartbeatEpochs().isEmpty());
        assertEquals(1, s.epochHelloPendingCount());
        svc.restore(g1Image()); // the ME is bound (G)
        setLastEgressSendMs(s, System.currentTimeMillis());
        s.onTimerEvent(s.currentFlushTimerId(), cluster.time);
        assertEquals(List.of(G), session.heartbeatEpochs());
        assertEquals(0, s.epochHelloPendingCount());
    }
}
