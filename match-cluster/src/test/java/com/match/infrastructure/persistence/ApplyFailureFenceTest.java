// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.publisher.MatchEventSink;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import com.openexchange.cluster.NodeReadiness;
import io.aeron.cluster.service.Cluster;
import io.aeron.logbuffer.Header;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** An exception after a book mutation must never become a successful applied prefix. */
public class ApplyFailureFenceTest {
    private static UnsafeBuffer create() {
        var b = new UnsafeBuffer(new byte[CreateOrderEncoder.BLOCK_LENGTH + 8]);
        new CreateOrderEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
            .userId(7).omsOrderId(1001).marketId(1).orderSide(OrderSide.BID)
            .orderType(OrderType.LIMIT).price(FixedPoint.fromDouble(60000))
            .quantity(FixedPoint.fromDouble(1));
        return b;
    }

    private static MatchEventSink failingSink(Throwable failure) {
        return new MatchEventSink() {
            private boolean fail() {
                if (failure instanceof RuntimeException e) throw e;
                throw (Error) failure;
            }
            public boolean publishTradeExecution(int m, long ts, long to, long tu, long mo,
                    long mu, long p, long q, boolean buy, long toms, long moms, long seq) {
                return fail();
            }
            public boolean publishOrderStatusUpdate(int m, long ts, long order, long user,
                    int status, long remaining, long filled, long price, boolean buy,
                    long oms, int reason, long seq) {
                return fail();
            }
        };
    }

    private static void assertApplyFailure(Throwable failure) {
        Engine engine = new Engine("array");
        engine.setEventPublisher(failingSink(failure));
        var demux = new SbeDemuxer(engine);
        var b = create();
        assertSame(failure, assertThrows(failure.getClass(), () -> demux.dispatch(b, 0, b.capacity(), 10)));
        assertFalse("failure occurs after the real book mutation", engine.getEngine(1).isBidEmpty());
        assertEquals(1, demux.applyErrorCount());
        assertEquals("apply exceptions are not poison input", 0, demux.decodeRejectCount());
        long nextOrderId = engine.getOrderIdGenerator();
        engine.setEventPublisher(null);
        assertThrows(IllegalStateException.class, () -> demux.dispatch(b, 0, b.capacity(), 20));
        assertEquals("failed instance must never apply another order", nextOrderId, engine.getOrderIdGenerator());
        assertEquals("one underlying failure, not one per blocked retry", 1, demux.applyErrorCount());
    }

    @Test public void publisherRuntimeFailureEscapes() { assertApplyFailure(new IllegalStateException("injected")); }
    @Test public void applyIllegalArgumentIsNotDecodePoison() { assertApplyFailure(new IllegalArgumentException("injected")); }
    @Test public void applyBoundsFailureIsNotDecodePoison() { assertApplyFailure(new IndexOutOfBoundsException("injected")); }
    @Test public void applyErrorAlsoLatches() { assertApplyFailure(new AssertionError("injected")); }

    @Test public void tradePublicationFailureCannotBeAcknowledgedAsApplied() {
        var engine = new Engine("array"); var demux = new SbeDemuxer(engine); var b = create();
        demux.dispatch(b, 0, b.capacity(), 10);
        var failure = new IllegalStateException("injected trade publication");
        engine.setEventPublisher(failingSink(failure));
        new CreateOrderEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
            .userId(8).omsOrderId(1002).marketId(1).orderSide(OrderSide.ASK)
            .orderType(OrderType.LIMIT).price(FixedPoint.fromDouble(60000)).quantity(FixedPoint.fromDouble(1));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> demux.dispatch(b, 0, b.capacity(), 20)));
        assertTrue("matching has already consumed the maker", engine.getEngine(1).isBidEmpty());
        assertTrue(engine.getEngine(1).isAskEmpty());
        assertEquals(1, demux.applyErrorCount()); assertEquals(0, demux.decodeRejectCount());
        engine.setEventPublisher(null);
        assertThrows(IllegalStateException.class, () -> demux.dispatch(b, 0, b.capacity(), 30));
    }

    @Test public void poisonInputDoesNotFenceHealthyEngine() {
        Engine engine = new Engine("array");
        var demux = new SbeDemuxer(engine); var b = create();
        b.putByte(8 + CreateOrderEncoder.orderSideEncodingOffset(), (byte) 99);
        demux.dispatch(b, 0, b.capacity(), 10);
        assertTrue(engine.getEngine(1).isBidEmpty());
        assertEquals(1, demux.decodeRejectCount());
        assertEquals(0, demux.applyErrorCount());
        b = create(); demux.dispatch(b, 0, b.capacity(), 20);
        assertFalse(engine.getEngine(1).isBidEmpty());
    }

    @Test public void invalidPhysicalBoundsAreDroppedBeforeHeaderAccess() {
        var engine = new Engine("array"); var demux = new SbeDemuxer(engine);
        var shortBuffer = new UnsafeBuffer(new byte[4]);
        demux.dispatch(shortBuffer, 0, 8, 10);
        demux.dispatch(shortBuffer, Integer.MAX_VALUE, 8, 10);
        assertEquals(2, demux.decodeRejectCount());
        assertEquals(0, demux.applyErrorCount());
        var valid = create(); demux.dispatch(valid, 0, valid.capacity(), 20);
        assertFalse(engine.getEngine(1).isBidEmpty());
    }

    @Test public void truncatedLogicalFrameCannotReadStaleBackingBytes() {
        var engine = new Engine("array"); var demux = new SbeDemuxer(engine); var b = create();
        // Backing memory contains a complete prior command, but this frame does not.
        demux.dispatch(b, 0, 10, 10);
        assertTrue("truncated frame must not place a stale order", engine.getEngine(1).isBidEmpty());
        assertEquals(1, demux.decodeRejectCount()); assertEquals(0, demux.applyErrorCount());
    }

    @Test public void shortDeclaredBlockCannotHideFullStalePayload() {
        var engine = new Engine("array"); var demux = new SbeDemuxer(engine); var b = create();
        b.putShort(0, (short) 1, java.nio.ByteOrder.LITTLE_ENDIAN);
        demux.dispatch(b, 0, b.capacity(), 10);
        assertTrue(engine.getEngine(1).isBidEmpty());
        assertEquals(1, demux.decodeRejectCount()); assertEquals(0, demux.applyErrorCount());
    }

    @Test public void validFrameAtNonzeroOffsetStillApplies() {
        var engine = new Engine("array"); var demux = new SbeDemuxer(engine); var command = create();
        var b = new UnsafeBuffer(new byte[256]); b.putBytes(17, command, 0, command.capacity());
        demux.dispatch(b, 17, command.capacity(), 10);
        assertFalse(engine.getEngine(1).isBidEmpty());
        assertEquals(0, demux.decodeRejectCount()); assertEquals(0, demux.applyErrorCount());
    }

    @Test public void truncatedConfigGroupCannotReachApplicationCallback() {
        var demux = new SbeDemuxer(Engine.deferredUntilConfig()); int[] calls = {0};
        demux.setEngineConfigHandler(config -> calls[0]++);
        var b = new UnsafeBuffer(new byte[256]);
        var e = new EngineConfigEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
            .configVersion(1).impl(EngineImpl.ARRAY).bookCapacity(4096).maxMatchesPerOrder(100).maxOrdersPerLevel(0);
        e.marketsCount(1).next().marketId(1).symbol("BTC-USD").minPrice(1).maxPrice(100).tickSize(1);
        demux.dispatch(b, 0, 8 + e.encodedLength() - 1, 10);
        assertEquals(0, calls[0]); assertEquals(1, demux.decodeRejectCount());
        assertEquals(0, demux.applyErrorCount());
    }

    @Test public void snapshotRequestHandlerFailureIsApplyFailure() {
        var demux = new SbeDemuxer(new Engine("array"));
        var failure = new IllegalArgumentException("injected callback");
        demux.setOpenOrdersSnapshotRequestHandler(id -> { throw failure; });
        var b = new UnsafeBuffer(new byte[128]);
        var e = new RequestOpenOrdersSnapshotEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder()).requestId(123);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> demux.dispatch(b, 0, 8 + e.encodedLength(), 10)));
        assertEquals(1, demux.applyErrorCount()); assertEquals(0, demux.decodeRejectCount());
    }

    @Test public void configurationHandlerFailureIsApplyFailure() {
        var demux = new SbeDemuxer(Engine.deferredUntilConfig());
        var failure = new IllegalArgumentException("injected config apply");
        demux.setEngineConfigHandler(config -> { throw failure; });
        var b = new UnsafeBuffer(new byte[256]);
        var e = new EngineConfigEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
            .configVersion(1).impl(EngineImpl.ARRAY).bookCapacity(4096).maxMatchesPerOrder(100).maxOrdersPerLevel(0);
        e.marketsCount(0);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> demux.dispatch(b, 0, 8 + e.encodedLength(), 10)));
        assertEquals(1, demux.applyErrorCount()); assertEquals(0, demux.decodeRejectCount());
    }

    @Test public void preConfigReplyFailureIsApplyFailure() {
        var demux = new SbeDemuxer(Engine.deferredUntilConfig());
        var failure = new IllegalArgumentException("injected reply");
        demux.setPreConfigOrderRejectHandler((m,u,o,b,t) -> { throw failure; });
        var b = create();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> demux.dispatch(b, 0, b.capacity(), 10)));
        assertEquals(1, demux.applyErrorCount()); assertEquals(0, demux.decodeRejectCount());
    }

    private static Object field(AppClusteredService service, String name) throws Exception {
        Field f = AppClusteredService.class.getDeclaredField(name); f.setAccessible(true); return f.get(service);
    }
    private static AppClusteredService failingService(Throwable failure) throws Exception {
        var service = new AppClusteredService();
        ((Engine) field(service, "engine")).setEventPublisher(failingSink(failure));
        Field timer = AppClusteredService.class.getDeclaredField("flushTimerScheduled");
        timer.setAccessible(true); timer.setBoolean(service, true);
        return service;
    }
    private static void dispatch(AppClusteredService service) {
        var b = create(); var header = new Header(0, 16);
        header.buffer(new UnsafeBuffer(new byte[32]));
        service.onSessionMessage(null, 10, b, 0, b.capacity(), header);
    }

    @Test public void serviceFailureStopsAdmissionAndSnapshotsImmediately() throws Exception {
        var failure = new IllegalStateException("injected publisher");
        var service = failingService(failure); int[] stops = {0};
        service.failFast = () -> stops[0]++;
        var readiness = (NodeReadiness) field(service, "readiness"); readiness.started();
        readiness.observe(1, 1, 1, Cluster.Role.LEADER, 100, 100, true, true, 0);
        assertTrue("control: verified healthy node starts ready", readiness.ready());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> dispatch(service)));
        assertEquals("must stop on first apply fault", 1, stops[0]);
        assertTrue(readiness.describe(), readiness.describe().contains("application-callback-failed"));
        assertFalse(readiness.ready());
        Engine engine = (Engine) field(service, "engine"); long nextId = engine.getOrderIdGenerator();
        engine.setEventPublisher(null);
        assertThrows(IllegalStateException.class, () -> dispatch(service));
        assertEquals(nextId, engine.getOrderIdGenerator());
        assertThrows("must refuse before touching a snapshot publication", IllegalStateException.class,
            () -> service.onTakeSnapshot(null));
        assertThrows(IllegalStateException.class, () -> service.onTimerEvent(1, 20));
        assertThrows(IllegalStateException.class, () -> service.onRoleChange(Cluster.Role.LEADER));
        service.doBackgroundWork(System.nanoTime());
        assertFalse(readiness.ready());
        assertEquals(0, field(service, "applicationCallbackDepth"));
    }

    @Test public void actualApplyFaultUsesHooklessProcessExit() throws Exception {
        Path dir = Files.createTempDirectory("apply-failure-exit-"); Path marker = dir.resolve("hook");
        Path log = dir.resolve("child.log");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"), getClass().getName(), marker.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("child must exit promptly", child.waitFor(15, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 1, child.exitValue());
            assertFalse("financial apply failure must not deadlock in shutdown hooks", Files.exists(marker));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
            System.out.print(Files.readString(log));
            Files.deleteIfExists(marker); Files.deleteIfExists(log); Files.deleteIfExists(dir);
        }
    }
    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(Path.of(args[0]), "hook ran"); } catch (Exception e) { throw new RuntimeException(e); }
        }));
        dispatch(failingService(new IllegalStateException("injected publisher")));
    }
}
