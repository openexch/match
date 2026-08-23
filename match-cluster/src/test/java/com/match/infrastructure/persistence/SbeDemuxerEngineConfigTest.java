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
                .maxOrdersPerLevel(maxOrdersPerLevel);
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
        demuxer.dispatch(buffer, 0, len, 1000L);

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

        demuxer.dispatch(buffer, 0, len, 1000L);   // must not throw (the #202 pattern)

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
        demuxer.dispatch(buffer, 0, len, 1000L);

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
        demuxer.dispatch(buffer, 0, lenCfg, 1000L);

        int lenOrder = encodeCreateOrder(1001L, 1, 555_002L,
                FixedPoint.fromDouble(60_000.0), FixedPoint.fromDouble(1.0));
        demuxer.dispatch(buffer, 0, lenOrder, 1001L);

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
        demuxer.dispatch(buffer, 0, len, 1000L);

        assertEquals(0, demuxer.preConfigOrderRejectCount());
        assertEquals(1, demuxer.createOrderCount());
        assertFalse(engine.getEngine(1).isBidEmpty());
    }
}
