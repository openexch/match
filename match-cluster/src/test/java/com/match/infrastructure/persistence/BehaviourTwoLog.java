// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RC2 S2 test support: order-schema frames for behaviour-2 logs, and a {@link Replica} that
 * applies them through the real {@link SbeDemuxer} the way {@code AppClusteredService} does
 * (log position set from the entry before dispatch, cluster timestamp passed through).
 */
final class BehaviourTwoLog {

    private BehaviourTwoLog() {
    }

    /** One logged entry: the frame, its log position and its cluster timestamp. */
    static final class Entry {
        final byte[] frame;
        final long position;
        final long timestamp;

        Entry(byte[] frame, long position, long timestamp) {
            this.frame = frame;
            this.position = position;
            this.timestamp = timestamp;
        }
    }

    /** A list of frames positioned deterministically: entry i at 64 + 128 * i, timestamp 1000 + i. */
    static List<Entry> positioned(List<byte[]> frames) {
        final List<Entry> out = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            out.add(new Entry(frames.get(i), 64L + 128L * i, 1_000L + i));
        }
        return out;
    }

    static byte[] engineConfig(long configVersion, long behaviorVersion, long genesisId,
                               long maxWireVersion, long commandLedgerCapacity) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
        final EngineConfigEncoder encoder = new EngineConfigEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .configVersion(configVersion)
                .impl(EngineImpl.ARRAY)
                .bookCapacity(4096)
                .maxMatchesPerOrder(100)
                .maxOrdersPerLevel(0)
                .behaviorVersion(behaviorVersion)
                .commandLedgerCapacity(commandLedgerCapacity)
                .genesisId(genesisId)
                .maxWireVersion((int) maxWireVersion);
        encoder.marketsCount(1).next()
                .marketId(BehaviourTwoHarness.MARKET)
                .symbol("BTC-USD")
                .minPrice(FixedPoint.fromDouble(50_000.0))
                .maxPrice(FixedPoint.fromDouble(150_000.0))
                .tickSize(FixedPoint.fromDouble(1.0));
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    /** A v12 DurableOrderCommand (template 9, block 80). */
    static byte[] durable(long idLow, int kind, long userId, long omsOrderId, long oldOrderId, int side,
                          double price, double qty, int tif, long expectedOldRemaining) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        new DurableOrderCommandEncoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .commandIdHigh(17).commandIdLow(idLow).userId(userId).omsOrderId(omsOrderId)
                .oldOrderId(oldOrderId).price(FixedPoint.fromDouble(price)).quantity(FixedPoint.fromDouble(qty))
                .budget(0).marketId(BehaviourTwoHarness.MARKET)
                .commandKind((short) kind).orderType((short) 0).orderSide((short) side)
                .timeInForce((short) tif).expectedOldRemaining(expectedOldRemaining);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + DurableOrderCommandEncoder.BLOCK_LENGTH);
    }

    static byte[] durableCreate(long idLow, long userId, long omsOrderId, int side, double price, double qty) {
        return durable(idLow, 0, userId, omsOrderId, 0L, side, price, qty, 0, 0L);
    }

    /** A legacy-lane CreateOrder (template 1), LIMIT. */
    static byte[] createOrder(long userId, long omsOrderId, int side, double price, double qty) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder())
                .userId(userId)
                .price(FixedPoint.fromDouble(price))
                .quantity(FixedPoint.fromDouble(qty))
                .marketId(BehaviourTwoHarness.MARKET)
                .orderType(OrderType.LIMIT)
                .orderSide(side == 0 ? OrderSide.BID : OrderSide.ASK)
                .omsOrderId(omsOrderId);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    static byte[] cancelOrder(long userId, long orderId) {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[256]);
        final CancelOrderEncoder encoder = new CancelOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder());
        encoder.userId(userId);
        encoder.orderId(orderId);
        encoder.marketId(BehaviourTwoHarness.MARKET);
        return Arrays.copyOf(buffer.byteArray(), MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength());
    }

    /**
     * The same frame under another header version, root block and bytes unchanged — what a
     * v{@code version} producer sends for these fields (SBE is append-only).
     */
    static byte[] withHeaderVersion(byte[] frame, int version) {
        final byte[] copy = frame.clone();
        new MessageHeaderEncoder().wrap(new UnsafeBuffer(copy), 0).version(version);
        return copy;
    }

    /** Byte-for-byte equality of two frame lists, with the frame index in the message. */
    static void assertFramesEqual(String what, List<byte[]> expected, List<byte[]> actual) {
        org.junit.Assert.assertEquals(what + ": frame count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            org.junit.Assert.assertArrayEquals(what + ": frame " + i, expected.get(i), actual.get(i));
        }
    }

    /** RC2 S3 frame invariant (contract 7.2): journalSeq 0 iff meEpoch 0, on every frame. */
    static void assertSeqEpochInvariant(List<byte[]> frames) {
        for (int i = 0; i < frames.size(); i++) {
            final byte[] f = frames.get(i);
            org.junit.Assert.assertEquals("frame " + i + ": (journalSeq == 0) == (meEpoch == 0)",
                    BehaviourTwoHarness.journalSeqOf(f) == 0, BehaviourTwoHarness.meEpochOf(f) == 0);
        }
    }

    /** One replica: harness (engine, publisher, journal, state machine) + the real demuxer. */
    static final class Replica implements AutoCloseable {
        final BehaviourTwoHarness h;
        final SbeDemuxer demuxer;

        Replica() {
            this(Engine.deferredUntilConfig());
        }

        Replica(Engine engine) {
            this(engine, com.match.infrastructure.generated.MessageHeaderDecoder.SCHEMA_VERSION);
        }

        /** RC2 S3: a replica of the given shape (journaled, dark, ring-less or sink-less). */
        Replica(Engine engine, BehaviourTwoHarness.Shape shape) {
            this(engine, com.match.infrastructure.generated.MessageHeaderDecoder.SCHEMA_VERSION, shape);
        }

        /** A replica whose jar accepts header versions up to {@code jarVersion} (a later jar). */
        Replica(Engine engine, int jarVersion) {
            this(engine, jarVersion, BehaviourTwoHarness.Shape.JOURNALED);
        }

        Replica(Engine engine, int jarVersion, BehaviourTwoHarness.Shape shape) {
            h = new BehaviourTwoHarness(engine, shape);
            demuxer = new SbeDemuxer(engine, jarVersion);
            demuxer.setEngineConfigHandler(config -> h.decisions.add(h.sm.onEngineConfig(config)));
            demuxer.setPreConfigOrderRejectHandler((m, u, o, b, t) -> { });
        }

        /** Dispatch one entry; return the journal frames it produced. */
        List<byte[]> apply(Entry e) {
            h.engine.setCurrentLogPosition(e.position);
            demuxer.dispatch(new UnsafeBuffer(e.frame), 0, e.frame.length, e.timestamp);
            return h.drain();
        }

        /** Dispatch entries [from, to); return every journal frame they produced, in order. */
        List<byte[]> apply(List<Entry> log, int from, int to) {
            final List<byte[]> out = new ArrayList<>();
            for (int i = from; i < to; i++) {
                out.addAll(apply(log.get(i)));
            }
            return out;
        }

        byte[] snapshot() {
            final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(h.engine, 0L, buf);
            return Arrays.copyOf(buf.byteArray(), len);
        }

        /** A new config-mode replica restored from this one's snapshot bytes. */
        static Replica restoredFrom(byte[] image) {
            final Replica r = new Replica();
            // RC2 S3: the codec restores the tradeId generator into the engine in place.
            SnapshotCodec.deserialize(new UnsafeBuffer(image), 0, image.length, r.h.engine);
            return r;
        }

        @Override
        public void close() {
            h.close();
        }
    }
}
