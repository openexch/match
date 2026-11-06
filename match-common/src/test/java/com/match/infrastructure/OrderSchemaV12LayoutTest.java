// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import com.match.infrastructure.generated.ClusterHeartbeatEncoder;
import com.match.infrastructure.generated.DurableOrderCommandDecoder;
import com.match.infrastructure.generated.DurableOrderCommandEncoder;
import com.match.infrastructure.generated.EngineConfigDecoder;
import com.match.infrastructure.generated.EngineConfigEncoder;
import com.match.infrastructure.generated.EngineImpl;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.ReleaseCommandIdsDecoder;
import com.match.infrastructure.generated.ReleaseCommandIdsEncoder;
import com.match.infrastructure.generated.ReleaseScope;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * order-schema v12 layout (RC2 S1 contract 3, 8.1): the grown blocks and their appended offsets,
 * the new template 10, the null mapping of a pre-12 frame read by the v12 decoder, and a v12
 * frame read by the v11 generated decoder (test-source copy) yielding the 12 original fields.
 */
public class OrderSchemaV12LayoutTest {

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[1024]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();

    @Test
    public void schemaVersion() {
        assertEquals(1, MessageHeaderEncoder.SCHEMA_ID);
        assertEquals(12, MessageHeaderEncoder.SCHEMA_VERSION);
    }

    @Test
    public void durableOrderCommandLayout() {
        assertEquals(9, DurableOrderCommandEncoder.TEMPLATE_ID);
        assertEquals(80, DurableOrderCommandEncoder.BLOCK_LENGTH);
        assertEquals(71, DurableOrderCommandEncoder.timeInForceEncodingOffset());
        assertEquals(72, DurableOrderCommandEncoder.expectedOldRemainingEncodingOffset());
        assertEquals(12, DurableOrderCommandEncoder.timeInForceSinceVersion());
        assertEquals(12, DurableOrderCommandEncoder.expectedOldRemainingSinceVersion());
    }

    @Test
    public void releaseCommandIdsLayout() {
        assertEquals(10, ReleaseCommandIdsEncoder.TEMPLATE_ID);
        assertEquals(8, ReleaseCommandIdsEncoder.BLOCK_LENGTH);
        assertEquals(0, ReleaseCommandIdsEncoder.releaseSeqEncodingOffset());
        assertEquals(10, ReleaseCommandIdsEncoder.releasesId());
        assertEquals(17, ReleaseCommandIdsEncoder.ReleasesEncoder.sbeBlockLength());
        assertEquals(0, ReleaseCommandIdsEncoder.ReleasesEncoder.commandIdHighEncodingOffset());
        assertEquals(8, ReleaseCommandIdsEncoder.ReleasesEncoder.idLowFloorEncodingOffset());
        assertEquals(16, ReleaseCommandIdsEncoder.ReleasesEncoder.scopeEncodingOffset());
        assertEquals(0, ReleaseScope.UP_TO.value());
        assertEquals(1, ReleaseScope.ORDER.value());
    }

    @Test
    public void releaseCommandIdsRoundTripsAt48Entries() {
        final ReleaseCommandIdsEncoder.ReleasesEncoder g = new ReleaseCommandIdsEncoder()
                .wrapAndApplyHeader(buffer, 0, header).releaseSeq(42).releasesCount(48);
        for (int i = 0; i < 48; i++) {
            g.next().commandIdHigh(i).idLowFloor(1000 + i).scope(i % 2 == 0 ? ReleaseScope.UP_TO : ReleaseScope.ORDER);
        }
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(buffer, 0);
        final ReleaseCommandIdsDecoder d = new ReleaseCommandIdsDecoder()
                .wrap(buffer, 8, h.blockLength(), h.version());
        assertEquals(42, d.releaseSeq());
        int i = 0;
        for (final ReleaseCommandIdsDecoder.ReleasesDecoder e : d.releases()) {
            assertEquals(i, e.commandIdHigh());
            assertEquals(1000 + i, e.idLowFloor());
            assertEquals(i % 2 == 0 ? ReleaseScope.UP_TO : ReleaseScope.ORDER, e.scope());
            i++;
        }
        assertEquals(48, i);
        assertEquals("frame max of contract 3.2", 836, 8 + d.encodedLength());
    }

    @Test
    public void engineConfigLayout() {
        assertEquals(35, EngineConfigEncoder.BLOCK_LENGTH);
        assertEquals(17, EngineConfigEncoder.behaviorVersionEncodingOffset());
        assertEquals(21, EngineConfigEncoder.commandLedgerCapacityEncodingOffset());
        assertEquals(25, EngineConfigEncoder.genesisIdEncodingOffset());
        assertEquals(33, EngineConfigEncoder.maxWireVersionEncodingOffset());
    }

    @Test
    public void clusterHeartbeatLayout() {
        assertEquals(17, ClusterHeartbeatEncoder.BLOCK_LENGTH);
        assertEquals(9, ClusterHeartbeatEncoder.meEpochEncodingOffset());
        assertEquals(12, ClusterHeartbeatEncoder.meEpochSinceVersion());
    }

    private void encodeV12Command(final int tif, final long expectedOldRemaining) {
        new DurableOrderCommandEncoder().wrapAndApplyHeader(buffer, 0, header)
                .commandIdHigh(17).commandIdLow(2).userId(100).omsOrderId(9001).oldOrderId(55)
                .price(123).quantity(456).budget(789).marketId(3)
                .commandKind((short) 2).orderType((short) 1).orderSide((short) 1)
                .timeInForce((short) tif).expectedOldRemaining(expectedOldRemaining);
    }

    @Test
    public void v11CommandThroughTheV12DecoderYieldsTheSbeNulls() {
        encodeV12Command(2, 5);
        header.wrap(buffer, 0).blockLength(71).version(11);
        final DurableOrderCommandDecoder d = new DurableOrderCommandDecoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
        assertEquals(17, d.commandIdHigh());
        assertEquals(2, d.commandKind());
        assertEquals(1, d.orderSide());
        // The generated decoder returns the SBE null for a pre-12 frame; the demuxer maps 0/0
        // WITHOUT a range check (null before range).
        assertEquals(255, d.timeInForce());
        assertEquals(Long.MIN_VALUE, d.expectedOldRemaining());
        assertEquals(8 + 71, d.limit());
    }

    @Test
    public void v12CommandThroughTheV11DecoderYieldsThe12OriginalFields() {
        encodeV12Command(2, 5);
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(buffer, 0);
        final com.match.infrastructure.generated.v11.DurableOrderCommandDecoder old =
                new com.match.infrastructure.generated.v11.DurableOrderCommandDecoder()
                        .wrap(buffer, 8, h.blockLength(), h.version());
        assertEquals(11, com.match.infrastructure.generated.v11.DurableOrderCommandDecoder.SCHEMA_VERSION);
        assertEquals(17, old.commandIdHigh());
        assertEquals(2, old.commandIdLow());
        assertEquals(100, old.userId());
        assertEquals(9001, old.omsOrderId());
        assertEquals(55, old.oldOrderId());
        assertEquals(123, old.price());
        assertEquals(456, old.quantity());
        assertEquals(789, old.budget());
        assertEquals(3, old.marketId());
        assertEquals(2, old.commandKind());
        assertEquals(1, old.orderType());
        assertEquals(1, old.orderSide());
        assertEquals("the v11 reader skips the v12 tail by the frame's own block", 8 + 80, old.limit());
    }

    @Test
    public void v11EngineConfigThroughTheV12DecoderYieldsTheSbeNulls() {
        // Hand-built v11 frame: root block 17 then the markets group (one entry).
        new EngineConfigEncoder().wrapAndApplyHeader(buffer, 0, header)
                .configVersion(4).impl(EngineImpl.ARRAY).bookCapacity(4096).maxMatchesPerOrder(100)
                .maxOrdersPerLevel(0);
        header.wrap(buffer, 0).blockLength(17).version(11);
        final int group = 8 + 17;
        buffer.putShort(group, (short) EngineConfigEncoder.MarketsEncoder.sbeBlockLength(), java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(group + 2, (short) 1, java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(group + 4, 7, java.nio.ByteOrder.LITTLE_ENDIAN);

        final EngineConfigDecoder d = new EngineConfigDecoder()
                .wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
        assertEquals(4, d.configVersion());
        assertEquals(4_294_967_295L, d.behaviorVersion());
        assertEquals(4_294_967_295L, d.commandLedgerCapacity());
        assertEquals(Long.MIN_VALUE, d.genesisId());
        assertEquals(65_535, d.maxWireVersion());
        final EngineConfigDecoder.MarketsDecoder m = d.markets();
        assertEquals(1, m.count());
        assertEquals(7, m.next().marketId());
    }
}
