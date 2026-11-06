// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.RequestOpenOrdersSnapshotEncoder;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.Header;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static com.match.infrastructure.persistence.DurableLedgerV2Test.*;
import static org.junit.Assert.*;

/**
 * RC2 S6 contract 3.2 (rows R0-R7) and 9.2: the ME session authority. The class is the replicated principal's
 * code; a refused frame mutates nothing, journals nothing (no INVALID either), consumes no journalSeq and sends
 * no egress. In-process: the real demuxer, engine, journal and state machine (BehaviourTwoLog.Replica).
 */
public class MeSessionAuthorityTest {

    static final int GATEWAY = ClusterSessionClass.GATEWAY.code();
    static final int FORWARDER = ClusterSessionClass.FORWARDER.code();
    static final int ADMIN = ClusterSessionClass.ADMIN.code();
    static final int REPLICA = ClusterSessionClass.REPLICA.code();

    /** Dispatch one entry as a session of {@code classCode}; return the journal frames it produced. */
    static List<byte[]> applyAs(final Replica r, final int classCode, final Entry e) {
        r.h.engine.setCurrentLogPosition(e.position);
        r.demuxer.dispatch(classCode, 9L, new UnsafeBuffer(e.frame), 0, e.frame.length, e.timestamp);
        return r.h.drain();
    }

    /** A Run cursor that sends as a class. */
    static List<byte[]> sendAs(final Run run, final int classCode, final byte[] frame) {
        final List<byte[]> out = applyAs(run.r, classCode, new Entry(frame, run.position, run.timestamp));
        run.position += 128;
        run.timestamp++;
        return out;
    }

    static byte[] openOrdersRequest(final long requestId) {
        final UnsafeBuffer b = new UnsafeBuffer(new byte[64]);
        final RequestOpenOrdersSnapshotEncoder e = new RequestOpenOrdersSnapshotEncoder()
                .wrapAndApplyHeader(b, 0, new MessageHeaderEncoder()).requestId(requestId);
        return Arrays.copyOf(b.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength());
    }

    static long nextJournalSeq(final Engine e) {
        return e.meIdentity().nextJournalSeq();
    }

    @Test
    public void effectiveReleaseFromAdminChangesNothing() {
        final long h = 601;
        try (Run r = fresh(1_000)) {
            final long leg = appliedOrderId(sendAs(r, FORWARDER, create(h, 0, 100, 0, 59_000, 1)));
            assertEquals(DurableCommandOutcome.APPLIED, only3(sendAs(r, FORWARDER, cancel(h, 1, 100, leg))).result());
            final byte[] before = r.r.snapshot();
            final long seq = nextJournalSeq(r.engine());
            final byte[] release = releaseFrame(1, order(h)); // would tombstone h: an effective release
            assertTrue("ADMIN template 10: no record 4", sendAs(r, ADMIN, release).isEmpty());
            assertEquals(1, r.r.demuxer.unauthorizedRejectCount());
            assertArrayEquals("ledger (inflight, tombstones), books and generators unchanged", before, r.r.snapshot());
            assertEquals("no journalSeq consumed", seq, nextJournalSeq(r.engine()));
            assertEquals(0, S4Probe.ranges(r.engine()).length / 2);
            // the same frame from FORWARDER is the effective release
            final var rel = only4(sendAs(r, FORWARDER, release));
            assertEquals(R_APPLIED, entries(rel).get(0)[0]);
            assertEquals(1, rel.tombstoneRanges());
            assertEquals(seq + 1, nextJournalSeq(r.engine()));
        }
    }

    @Test
    public void durableFromAdminOrGatewayChangesNothing() {
        try (Run r = fresh(1_000)) {
            final byte[] before = r.r.snapshot();
            final long seq = nextJournalSeq(r.engine());
            assertTrue(sendAs(r, ADMIN, create(611, 0, 100, 0, 59_000, 1)).isEmpty());
            assertTrue(sendAs(r, GATEWAY, create(612, 0, 100, 0, 59_000, 1)).isEmpty());
            assertEquals(2, r.r.demuxer.unauthorizedRejectCount());
            assertArrayEquals(before, r.r.snapshot());
            assertEquals(seq, nextJournalSeq(r.engine()));
            assertEquals(0, resting(r.engine()));
            appliedOrderId(sendAs(r, FORWARDER, create(611, 0, 100, 0, 59_000, 1)));
            assertEquals(1, resting(r.engine()));
        }
    }

    @Test
    public void aboveCeilingDurableFromNonForwarderJournalsNothing() {
        try (Run r = fresh(1_000)) {
            final byte[] above = withHeaderVersion(create(621, 0, 100, 0, 59_000, 1), 13);
            assertTrue("ADMIN above-ceiling template 9: no INVALID record 3", sendAs(r, ADMIN, above).isEmpty());
            assertTrue(sendAs(r, GATEWAY, above).isEmpty());
            assertEquals(0, r.r.demuxer.aboveCeilingInvalidCount());
            assertEquals(2, r.r.demuxer.unauthorizedRejectCount());
            // the FORWARDER's identical frame is journaled INVALID (S2 section 6)
            assertEquals(DurableCommandOutcome.INVALID, only3(sendAs(r, FORWARDER, above)).result());
            assertEquals(1, r.r.demuxer.aboveCeilingInvalidCount());
        }
    }

    @Test
    public void engineConfigFromForwarderIsRefusedInEveryState() {
        try (Replica r = new Replica()) {
            assertTrue(applyAs(r, FORWARDER, new Entry(engineConfig(1, 2, G, 12, 1_000), 64L, 1_000L)).isEmpty());
            assertTrue(applyAs(r, GATEWAY, new Entry(engineConfig(1, 2, G, 12, 1_000), 128L, 1_001L)).isEmpty());
            assertNull("pre-config: nothing recorded", r.h.engine.getEngineConfig());
            assertEquals(0, r.h.decisions.size());
            assertEquals(2, r.demuxer.unauthorizedRejectCount());
            applyAs(r, ADMIN, new Entry(engineConfig(1, 2, G, 12, 1_000), 192L, 1_002L));
            assertEquals(1, r.h.decisions.size());
            assertEquals(1, r.h.engine.getEngineConfig().configVersion);
            assertTrue("behaviour 2: a FORWARDER BUMP is refused",
                    applyAs(r, FORWARDER, new Entry(engineConfig(2, 2, G, 12, 2_000), 320L, 1_003L)).isEmpty());
            assertEquals(1, r.h.decisions.size());
            assertEquals(1, r.h.engine.getEngineConfig().configVersion);
        }
        try (Replica one = new Replica()) {
            applyAs(one, ADMIN, new Entry(engineConfig(1, 1, 0, 0, 100_000), 64L, 1_000L));
            assertEquals(1, one.h.decisions.size());
            for (int c : new int[] {FORWARDER, GATEWAY}) {
                applyAs(one, c, new Entry(engineConfig(2, 1, 0, 0, 100_000), 192L, 1_001L));
            }
            assertEquals("behaviour 1: template 8 stays ADMIN-only", 1, one.h.decisions.size());
            assertEquals(2, one.demuxer.unauthorizedRejectCount());
        }
    }

    @Test
    public void openOrdersRequestFromGatewayRefusedAtBehaviourTwo() {
        try (Run r = fresh(1_000)) {
            final List<Long> served = new ArrayList<>();
            r.r.demuxer.setOpenOrdersSnapshotRequestHandler(served::add);
            sendAs(r, GATEWAY, openOrdersRequest(1));
            assertEquals("no OpenOrdersSnapshot for GATEWAY", List.of(), served);
            assertEquals(1, r.r.demuxer.unauthorizedRejectCount());
            sendAs(r, FORWARDER, openOrdersRequest(2));
            sendAs(r, ADMIN, openOrdersRequest(3));
            assertEquals(List.of(2L, 3L), served);
        }
    }

    /** Every ingress template, as REPLICA would send it. */
    private static List<byte[]> everyTemplate(final long h) {
        return List.of(createOrder(100, 9001, 0, 59_000, 1), cancelOrder(100, 1), updateOrder(100, 1, 0, 58_000, 1),
                openOrdersRequest(7), engineConfig(2, 2, G, 12, 2_000), create(h, 0, 100, 0, 59_000, 1),
                releaseFrame(1, order(h)));
    }

    @Test
    public void replicaSendsNothingInAnyState() {
        // pre-config
        try (Replica r = new Replica()) {
            final List<Long> served = new ArrayList<>();
            r.demuxer.setOpenOrdersSnapshotRequestHandler(served::add);
            long p = 64;
            for (byte[] f : everyTemplate(631)) {
                assertTrue(applyAs(r, REPLICA, new Entry(f, p += 128, p)).isEmpty());
            }
            assertEquals(7, r.demuxer.unauthorizedRejectCount());
            assertEquals("no pre-config reply either", 0, r.demuxer.preConfigOrderRejectCount());
            assertEquals(0, r.demuxer.unservedTemplateCount());
            assertNull(r.h.engine.getEngineConfig());
            assertEquals(List.of(), served);
        }
        // behaviour 1 and behaviour 2
        for (long behaviour : new long[] {1, 2}) {
            try (Replica r = new Replica()) {
                final List<Long> served = new ArrayList<>();
                r.demuxer.setOpenOrdersSnapshotRequestHandler(served::add);
                applyAs(r, ADMIN, new Entry(behaviour == 1 ? engineConfig(1, 1, 0, 0, 100_000)
                        : engineConfig(1, 2, G, 12, 1_000), 64L, 1_000L));
                final byte[] before = r.snapshot();
                final long seq = nextJournalSeq(r.h.engine);
                long p = 192;
                for (byte[] f : everyTemplate(632)) {
                    assertTrue("behaviour " + behaviour, applyAs(r, REPLICA, new Entry(f, p += 128, p)).isEmpty());
                }
                assertEquals("behaviour " + behaviour, 7, r.demuxer.unauthorizedRejectCount());
                assertArrayEquals(before, r.snapshot());
                assertEquals(seq, nextJournalSeq(r.h.engine));
                assertEquals(1, r.h.decisions.size());
                assertEquals(0, r.demuxer.legacyLaneRejectCount());
                assertEquals(List.of(), served);
            }
        }
    }

    // ---- R0: the service halts on a principal-less session ----

    private static Header header(final long position) {
        final Header header = new Header(0, 16);
        header.buffer(new UnsafeBuffer(new byte[32]));
        header.offset(0);
        return header;
    }

    private static String captureErr(final Runnable r) {
        final PrintStream prior = System.err;
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            r.run();
        } finally {
            System.setErr(prior);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    private static Engine engineOf(final AppClusteredService s) throws Exception {
        final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("engine");
        f.setAccessible(true);
        return (Engine) f.get(s);
    }

    private static SbeDemuxer demuxerOf(final AppClusteredService s) throws Exception {
        final java.lang.reflect.Field f = AppClusteredService.class.getDeclaredField("sbeDemuxer");
        f.setAccessible(true);
        return (SbeDemuxer) f.get(s);
    }

    /**
     * One R0 case: a class-0 frame (or SessionOpen) on a LEADER service with a cluster attached. "Nothing applied"
     * is checked before the exception type, so on a base that applies the frame the RED says so (review FIX-1).
     */
    private static void assertPrincipalLessHalts(final ClientSession session, final boolean sessionOpen) throws Exception {
        final AppClusteredService s = MeStubCluster.attach(new AppClusteredService(), new MeStubCluster());
        final int[] stops = {0};
        s.failFast = () -> stops[0]++;
        final byte[] frame = createOrder(100, 9001, 0, 59_000, 1);
        final long gen0 = engineOf(s).getOrderIdGenerator();
        final Throwable[] thrown = {null};
        final String log = captureErr(() -> {
            try {
                if (sessionOpen) {
                    s.onSessionOpen(session, 10);
                } else {
                    s.onSessionMessage(session, 10, new UnsafeBuffer(frame), 0, frame.length, header(64));
                }
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        assertEquals("nothing applied", 0, demuxerOf(s).createOrderCount());
        assertEquals("no order id assigned", gen0, engineOf(s).getOrderIdGenerator());
        assertNotNull("the node halts: nothing was thrown", thrown[0]);
        assertTrue("halted with the R0 exception, got " + thrown[0], thrown[0] instanceof IllegalStateException
                && thrown[0].getMessage() != null && thrown[0].getMessage().contains("clean genesis required"));
        assertEquals("failFast ran once", 1, stops[0]);
        assertTrue(log, log.contains("principal-less session in log: pre-S6 history, clean genesis required"));
        if (!sessionOpen) {
            // the node is fenced: a valid ADMIN frame is not applied either
            assertThrows(IllegalStateException.class, () -> s.onSessionMessage(TestAuth.session(7, ClusterSessionClass.ADMIN),
                    11, new UnsafeBuffer(frame), 0, frame.length, header(192)));
            assertEquals(0, demuxerOf(s).createOrderCount());
        }
    }

    @Test
    public void principalLessFrameHaltsTheNodeNullSession() throws Exception {
        assertPrincipalLessHalts(null, false);
    }

    @Test
    public void principalLessFrameHaltsTheNodeEmptyPrincipal() throws Exception {
        assertPrincipalLessHalts(TestAuth.session(5, new byte[0]), false);
    }

    @Test
    public void principalLessFrameHaltsTheNodeForeignPrincipal() throws Exception {
        assertPrincipalLessHalts(TestAuth.session(6, new byte[] {9, 4, 1, 0, 0, 0, 0, 0, 0, 0}), false);
    }

    @Test
    public void principalLessSessionOpenHaltsTheNode() throws Exception {
        assertPrincipalLessHalts(TestAuth.session(8, new byte[0]), true);
    }

    @Test
    public void behaviourOneIsOpenForEveryAuthenticatedClass() {
        for (int c : new int[] {GATEWAY, FORWARDER, ADMIN}) {
            // legacy mode (engines from boot, no config) and recorded behaviour 1
            for (boolean configured : new boolean[] {false, true}) {
                try (Replica byClass = new Replica(configured ? Engine.deferredUntilConfig() : new Engine());
                     Replica trusted = new Replica(configured ? Engine.deferredUntilConfig() : new Engine())) {
                    final List<Long> servedA = new ArrayList<>();
                    final List<Long> servedB = new ArrayList<>();
                    byClass.demuxer.setOpenOrdersSnapshotRequestHandler(servedA::add);
                    trusted.demuxer.setOpenOrdersSnapshotRequestHandler(servedB::add);
                    final List<Entry> log = new ArrayList<>();
                    long p = 64;
                    if (configured) {
                        log.add(new Entry(engineConfig(1, 1, 0, 0, 100_000), p, p));
                    }
                    for (byte[] f : List.of(createOrder(100, 9001, 0, 59_000, 1), createOrder(101, 9002, 1, 59_000, 2),
                            updateOrder(101, 2, 1, 59_500, 2), cancelOrder(101, 2), openOrdersRequest(5),
                            create(641, 0, 102, 0, 58_000, 1))) {
                        log.add(new Entry(f, p += 128, p));
                    }
                    final List<byte[]> a = new ArrayList<>();
                    final List<byte[]> b = new ArrayList<>();
                    for (int i = 0; i < log.size(); i++) {
                        // the config is ADMIN's in both runs; every other frame comes from class c
                        a.addAll(applyAs(byClass, configured && i == 0 ? ADMIN : c, log.get(i)));
                        b.addAll(trusted.apply(log.get(i)));
                    }
                    assertFramesEqual("class " + c + " configured=" + configured, b, a);
                    assertArrayEquals(trusted.snapshot(), byClass.snapshot());
                    assertEquals(servedB, servedA);
                    assertEquals(List.of(5L), servedA);
                    assertEquals(0, byClass.demuxer.unauthorizedRejectCount());
                    assertTrue("orders applied", byClass.demuxer.createOrderCount() >= 2);
                }
            }
        }
    }

    @Test
    public void journalEqualsReferenceModelAcrossSplice() {
        // A mixed-class behaviour-2 script; the model drops the rows R1-R4 refuse and applies the rest trusted.
        final List<int[]> classes = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        final int[] cycle = {FORWARDER, ADMIN, GATEWAY, REPLICA, FORWARDER, FORWARDER, ADMIN};
        for (int i = 0; i < 70; i++) {
            final long h = 700 + i;
            final int c = cycle[i % cycle.length];
            switch (i % 5) {
                case 0: case 1: case 2: frames.add(create(h, 0, 100 + (i % 3), i % 2, 59_000 + (i % 4) * 10, 1)); break;
                case 3: frames.add(openOrdersRequest(i)); break;
                default: frames.add(releaseFrame(i, upTo(h - 4, 0))); break;
            }
            classes.add(new int[] {c});
        }
        final List<Entry> log = new ArrayList<>();
        long p = 192;
        for (byte[] f : frames) {
            log.add(new Entry(f, p += 128, p));
        }
        final boolean[] refused = new boolean[log.size()];
        int refusedCount = 0;
        for (int i = 0; i < log.size(); i++) {
            final int c = classes.get(i)[0];
            final int template = BehaviourTwoHarness.templateId(log.get(i).frame);
            refused[i] = c == REPLICA || (template == 7 ? c == GATEWAY : c != FORWARDER);
            refusedCount += refused[i] ? 1 : 0;
        }
        assertTrue(refusedCount > 10 && refusedCount < 60);

        final List<byte[]> model = new ArrayList<>();
        try (Run m = fresh(1_000)) {
            for (int i = 0; i < log.size(); i++) {
                if (!refused[i]) {
                    model.addAll(m.r.apply(log.get(i)));
                }
            }
        }
        final List<byte[]> whole = new ArrayList<>();
        final int split = log.size() / 2;
        final byte[] image;
        try (Run a = fresh(1_000)) {
            for (int i = 0; i < split; i++) {
                whole.addAll(applyAs(a.r, classes.get(i)[0], log.get(i)));
            }
            image = a.r.snapshot();
            for (int i = split; i < log.size(); i++) {
                whole.addAll(applyAs(a.r, classes.get(i)[0], log.get(i)));
            }
            assertEquals(refusedCount, a.r.demuxer.unauthorizedRejectCount());
        }
        assertFramesEqual("journal == model without the refused rows", model, whole);
        final List<byte[]> spliced = new ArrayList<>();
        try (Run a = fresh(1_000)) {
            for (int i = 0; i < split; i++) {
                spliced.addAll(applyAs(a.r, classes.get(i)[0], log.get(i)));
            }
        }
        try (Replica b = Replica.restoredFrom(image)) {
            for (int i = split; i < log.size(); i++) {
                spliced.addAll(applyAs(b, classes.get(i)[0], log.get(i)));
            }
        }
        assertFramesEqual("A+B == A+SNAPSHOT+B with the class column", whole, spliced);
    }
}
