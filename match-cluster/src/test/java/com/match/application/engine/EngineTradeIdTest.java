// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TradeDecoder;
import com.match.infrastructure.persistence.SnapshotCodec;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * RC2 S3 contract section 3: the tradeId generator is engine-owned (moved from
 * {@code MatchEventPublisherTest}): it starts at 1, advances by one per trade in match order
 * whether or not a sink is attached, is written at snapshot payload offset 8 and restored into
 * the engine in place.
 */
public class EngineTradeIdTest {

    private static void create(Engine engine, long userId, OrderSide side, double price, double qty) {
        final CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOmsOrderId(0L);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0L);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        engine.acceptOrder(BehaviourTwoHarness.MARKET, Engine.CMD_CREATE, cmd, 1_000L);
    }

    /** Three resting asks, then one bid crossing all three: three trades. */
    private static void threeTrades(Engine engine) {
        create(engine, 1, OrderSide.ASK, 60_000, 1);
        create(engine, 2, OrderSide.ASK, 60_001, 1);
        create(engine, 3, OrderSide.ASK, 60_002, 1);
        create(engine, 4, OrderSide.BID, 60_002, 3);
    }

    private static long tradeIdAtPayloadOffset8(Engine engine) {
        final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(engine, 0L, buf);
        final Engine probe = new Engine("array");
        try {
            return SnapshotCodec.deserialize(buf, 0, len, probe).tradeIdGenerator;
        } finally {
            probe.close();
        }
    }

    @Test
    public void sinklessEngineCountsItsTrades() {
        final Engine engine = new Engine("array");
        try {
            assertNull(engine.getEventPublisher());
            assertEquals("starts at 1", 1L, engine.getTradeIdGenerator());
            threeTrades(engine);
            assertEquals("1 + trades, with no sink attached", 4L, engine.getTradeIdGenerator());
            assertEquals("payload offset 8", 4L, tradeIdAtPayloadOffset8(engine));
        } finally {
            engine.close();
        }
    }

    @Test
    public void tradeIdsAdvanceByOnePerTradeInMatchOrder() {
        try (BehaviourTwoHarness h = new BehaviourTwoHarness(new Engine("array"))) {
            threeTrades(h.engine);
            threeTrades(h.engine);
            final List<Long> ids = new ArrayList<>();
            final List<Long> makers = new ArrayList<>();
            for (byte[] f : BehaviourTwoHarness.ofTemplate(h.drain(), TradeDecoder.TEMPLATE_ID)) {
                final TradeDecoder d = new TradeDecoder().wrapAndApplyHeader(new UnsafeBuffer(f), 0,
                        new MessageHeaderDecoder());
                ids.add(d.tradeId());
                makers.add(d.makerOrderId());
            }
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), ids);
            assertEquals("match order: best ask first", List.of(1L, 2L, 3L, 5L, 6L, 7L), makers);
            assertEquals(7L, h.engine.getTradeIdGenerator());
            assertEquals(7L, tradeIdAtPayloadOffset8(h.engine));
        }
    }

    @Test
    public void restoreAppliesTheGeneratorInPlace() {
        final Engine source = new Engine("array");
        final Engine restored = new Engine("array");
        try {
            threeTrades(source);
            final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(source, 0L, buf);
            final SnapshotCodec.Decoded decoded = SnapshotCodec.deserialize(buf, 0, len, restored);
            assertEquals(4L, decoded.tradeIdGenerator);
            assertEquals("restored into the engine, no caller step", 4L, restored.getTradeIdGenerator());
            create(restored, 5, OrderSide.ASK, 60_000, 1);
            create(restored, 6, OrderSide.BID, 60_000, 1);
            assertEquals("the next trade continues the sequence", 5L, restored.getTradeIdGenerator());
        } finally {
            source.close();
            restored.close();
        }
    }
}
