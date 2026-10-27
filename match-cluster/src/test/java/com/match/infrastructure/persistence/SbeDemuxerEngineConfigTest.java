// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.engine.EngineConfigStateMachine;
import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.*;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Slice C: SbeDemuxer's EngineConfig ingress case (non-throwing decode, the A-4/#202 pattern) and
 * the fresh-cluster order guard. Real SBE frames through the real dispatch path.
 */
public class SbeDemuxerEngineConfigTest {

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();

    private int encodeEngineConfig(long configVersion, EngineImpl impl, long bookCapacity,
                                   long maxMatchesPerOrder, long maxOrdersPerLevel,
                                   long[][] marketNums, String[] symbols) {
        EngineConfigEncoder encoder = new EngineConfigEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .configVersion(configVersion)
                .impl(impl)
                .bookCapacity(bookCapacity)
                .maxMatchesPerOrder(maxMatchesPerOrder)
                .maxOrdersPerLevel(maxOrdersPerLevel)
                // v12: an unset behaviorVersion is 0, which RC2 S2 refuses (S-b).
                .behaviorVersion(1);
        EngineConfigEncoder.MarketsEncoder group = encoder.marketsCount(marketNums.length);
        for (int i = 0; i < marketNums.length; i++) {
            group.next()
                    .marketId((int) marketNums[i][0])
                    .symbol(symbols[i])
                    .minPrice(marketNums[i][1])
                    .maxPrice(marketNums[i][2])
                    .tickSize(marketNums[i][3]);
        }
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    private int encodeCreateOrder(long userId, int marketId, long omsOrderId, long price, long qty) {
        CreateOrderEncoder encoder = new CreateOrderEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder);
        encoder.userId(userId);
        encoder.price(price);
        encoder.quantity(qty);
        encoder.marketId(marketId);
        encoder.orderType(OrderType.LIMIT);
        encoder.orderSide(OrderSide.BID);
        encoder.omsOrderId(omsOrderId);
        return MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength();
    }

    @Test
    public void engineConfigFrameDecodesNormalizedAndReachesHandler() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<EngineConfigState> received = new ArrayList<>();
        demuxer.setEngineConfigHandler(received::add);

        // Markets deliberately in DESCENDING wire order; the handler must see them sorted.
        int len = encodeEngineConfig(3L, EngineImpl.ARRAY, 4096, 100, 0,
                new long[][]{
                        {42, 1_000_000_000L, 2_000_000_000L, 1_000_000L},
                        {7, 500_000_000L, 1_500_000_000L, 500_000L}},
                new String[]{"ZZZ-USD", "AAA-USD"});
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals(1, received.size());
        EngineConfigState config = received.get(0);
        assertEquals(3L, config.configVersion);
        assertEquals(EngineConfigState.IMPL_ARRAY, config.impl);
        assertEquals(4096L, config.bookCapacity);
        assertEquals(100L, config.maxMatchesPerOrder);
        assertEquals(0L, config.maxOrdersPerLevel);
        assertEquals(2, config.markets.length);
        assertEquals("normalized ascending", 7, config.markets[0].marketId);
        assertEquals("AAA-USD", config.markets[0].symbol);
        assertEquals(500_000_000L, config.markets[0].minPrice);
        assertEquals(1_500_000_000L, config.markets[0].maxPrice);
        assertEquals(500_000L, config.markets[0].tickSize);
        assertEquals(42, config.markets[1].marketId);
        assertEquals("no decode reject for a clean frame", 0, demuxer.decodeRejectCount());
        assertEquals("no apply error for a clean frame", 0, demuxer.applyErrorCount());
    }

    @Test
    public void poisonEngineImplByteIsDroppedNotThrown() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<EngineConfigState> received = new ArrayList<>();
        demuxer.setEngineConfigHandler(received::add);

        int len = encodeEngineConfig(1L, EngineImpl.ARRAY, 4096, 100, 0,
                new long[][]{{7, 500_000_000L, 1_500_000_000L, 500_000L}}, new String[]{"AAA-USD"});
        // Poke an out-of-range EngineImpl byte at its absolute wire offset (header 8 + impl offset).
        buffer.putByte(MessageHeaderEncoder.ENCODED_LENGTH + EngineConfigEncoder.implEncodingOffset(),
                (byte) 9);

        demuxer.dispatchTrusted(buffer, 0, len, 1000L);   // must not throw (the #202 pattern)

        assertEquals("poison byte counted as a decode reject", 1, demuxer.decodeRejectCount());
        assertTrue("handler never sees a frame that failed to decode", received.isEmpty());
    }

    // ---- fresh-cluster guard ----

    @Test
    public void createOrderBeforeConfigIsRejectedLoudlyWithOmsOrderId() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<long[]> rejects = new ArrayList<>(); // {marketId, userId, omsOrderId}
        demuxer.setPreConfigOrderRejectHandler((marketId, userId, omsOrderId, isBuy, timestamp) ->
                rejects.add(new long[]{marketId, userId, omsOrderId}));

        int len = encodeCreateOrder(1001L, 1, 555_001L,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0));
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals("guard counted", 1, demuxer.preConfigOrderRejectCount());
        assertEquals("service reject handler invoked exactly once", 1, rejects.size());
        assertEquals(1L, rejects.get(0)[0]);
        assertEquals(1001L, rejects.get(0)[1]);
        assertEquals("the command's omsOrderId reaches the egress emission", 555_001L, rejects.get(0)[2]);
        assertEquals("the order never counts as submitted", 0, demuxer.createOrderCount());
    }

    @Test
    public void orderAfterAcceptedConfigFlowsIntoTheConfigCreatedEngine() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        EngineConfigStateMachine sm = new EngineConfigStateMachine(engine,
                () -> fail("failFast must not fire on the fresh path"), null);
        demuxer.setEngineConfigHandler(sm::onEngineConfig);
        demuxer.setPreConfigOrderRejectHandler((m, u, o, b, t) -> fail("guard must not fire after config"));

        // Config for market 1 with a BTC-like band, then an order into it.
        int lenCfg = encodeEngineConfig(1L, EngineImpl.ARRAY, 4096, 100, 0,
                new long[][]{{1, FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(150_000.0),
                        FixedPoint.fromDouble(1.0)}},
                new String[]{"BTC-USD"});
        demuxer.dispatchTrusted(buffer, 0, lenCfg, 1000L);

        int lenOrder = encodeCreateOrder(1001L, 1, 555_002L,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0));
        demuxer.dispatchTrusted(buffer, 0, lenOrder, 1001L);

        assertEquals("order admitted", 1, demuxer.createOrderCount());
        assertEquals("guard silent once engines exist", 0, demuxer.preConfigOrderRejectCount());
        assertFalse("the order rests in the config-created engine", engine.getEngine(1).isBidEmpty());
    }

    @Test
    public void legacyEngineNeverTakesTheGuardPath() {
        Engine engine = new Engine(); // legacy: engines exist from boot
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        demuxer.setPreConfigOrderRejectHandler((m, u, o, b, t) ->
                fail("legacy mode must never hit the fresh-cluster guard"));

        int len = encodeCreateOrder(1001L, 1, 555_003L,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0));
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals(0, demuxer.preConfigOrderRejectCount());
        assertEquals(1, demuxer.createOrderCount());
        assertFalse(engine.getEngine(1).isBidEmpty());
    }
    // ---- order-schema v12 (RC2 S1 contract 3.3 / section 7 / 8.2) ----

    private static final long[][] ONE_MARKET = {{7, 500_000_000L, 1_500_000_000L, 500_000L}};
    private static final String[] ONE_SYMBOL = {"AAA-USD"};

    /**
     * Re-shapes the v12 frame encoded at offset 0 into a v11 frame: header version 11, root block
     * 17 (the five v10 fields), the markets group moved up to 8 + 17. Returns the new length.
     */
    private int reshapeToV11(final int v12Length) {
        final int v12Group = MessageHeaderEncoder.ENCODED_LENGTH + EngineConfigEncoder.BLOCK_LENGTH;
        final int v11Group = MessageHeaderEncoder.ENCODED_LENGTH + 17;
        final byte[] group = new byte[v12Length - v12Group];
        buffer.getBytes(v12Group, group);
        buffer.putBytes(v11Group, group);
        headerEncoder.wrap(buffer, 0).blockLength(17).version(11);
        return v11Group + group.length;
    }

    @Test
    public void v11EngineConfigFrameMapsTheFourV12FieldsToTheirDefaults() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<EngineConfigState> received = new ArrayList<>();
        demuxer.setEngineConfigHandler(received::add);

        // Encode as v12 with non-default values, then strip to a v11 frame: the values are gone.
        encodeEngineConfig(4L, EngineImpl.ARRAY, 4096, 100, 0, ONE_MARKET, ONE_SYMBOL);
        new EngineConfigEncoder().wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH)
                .behaviorVersion(2).commandLedgerCapacity(5).genesisId(99).maxWireVersion(12);
        int v12Len = MessageHeaderEncoder.ENCODED_LENGTH + EngineConfigEncoder.BLOCK_LENGTH + 4
                + EngineConfigEncoder.MarketsEncoder.sbeBlockLength();
        int len = reshapeToV11(v12Len);
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals("a v11 block-17 frame is not a bounds reject", 0, demuxer.decodeRejectCount());
        assertEquals(1, received.size());
        EngineConfigState config = received.get(0);
        assertEquals(4L, config.configVersion);
        assertEquals(4096L, config.bookCapacity);
        assertEquals("group found at 8 + 17", 1, config.markets.length);
        assertEquals(7, config.markets[0].marketId);
        assertEquals("AAA-USD", config.markets[0].symbol);
        assertEquals(1L, config.behaviorVersion);
        assertEquals(100_000L, config.commandLedgerCapacity);
        assertEquals(0L, config.genesisId);
        assertEquals(0L, config.maxWireVersion);
    }

    @Test
    public void v12EngineConfigFrameCarriesTheFourValuesAsEncoded() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<EngineConfigState> received = new ArrayList<>();
        demuxer.setEngineConfigHandler(received::add);

        int len = encodeEngineConfig(4L, EngineImpl.ARRAY, 4096, 100, 0, ONE_MARKET, ONE_SYMBOL);
        new EngineConfigEncoder().wrap(buffer, MessageHeaderEncoder.ENCODED_LENGTH)
                .behaviorVersion(2).commandLedgerCapacity(4_294_967_294L)
                .genesisId(-0x1234_5678_9ABC_DEF0L).maxWireVersion(12);
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals(0, demuxer.decodeRejectCount());
        EngineConfigState config = received.get(0);
        assertEquals(2L, config.behaviorVersion);
        assertEquals(4_294_967_294L, config.commandLedgerCapacity);
        assertEquals(-0x1234_5678_9ABC_DEF0L, config.genesisId);
        assertEquals(12L, config.maxWireVersion);
        assertEquals("group found at 8 + 35", 7, config.markets[0].marketId);
    }

    @Test
    public void v12HeaderWithThePre12BlockOnTemplate8IsAFrameBoundsReject() {
        Engine engine = Engine.deferredUntilConfig();
        SbeDemuxer demuxer = new SbeDemuxer(engine);
        List<EngineConfigState> received = new ArrayList<>();
        demuxer.setEngineConfigHandler(received::add);

        int v12Len = encodeEngineConfig(4L, EngineImpl.ARRAY, 4096, 100, 0, ONE_MARKET, ONE_SYMBOL);
        int len = reshapeToV11(v12Len);
        headerEncoder.wrap(buffer, 0).version(12); // block 17 under a v12 header
        demuxer.dispatchTrusted(buffer, 0, len, 1000L);

        assertEquals(1, demuxer.decodeRejectCount());
        assertTrue(received.isEmpty());
    }

    @Test
    public void theFourV12ValuesDoNotTakePartInConfigEqualityOrTheHash() {
        EngineConfigState.MarketDef[] m = {new EngineConfigState.MarketDef(7, "AAA-USD",
                500_000_000L, 1_500_000_000L, 500_000L)};
        EngineConfigState plain = EngineConfigState.of(4L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0, m);
        EngineConfigState v12 = EngineConfigState.of(4L, EngineConfigState.IMPL_ARRAY, 4096, 100, 0, m,
                2L, 5L, 99L, 12L);
        assertTrue(plain.equalsConfig(v12));
        assertTrue(plain.equalsIgnoringVersion(v12));
        assertEquals(plain.canonicalString(), v12.canonicalString());
        assertEquals(plain.canonicalHash(), v12.canonicalHash());
        assertEquals(plain.toString(), v12.toString());
    }
}
