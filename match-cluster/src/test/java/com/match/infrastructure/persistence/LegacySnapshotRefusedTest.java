// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.engine.TestEngines;
import org.agrona.ExpandableArrayBuffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/**
 * S4 T10 (R-C6): an image that carries order books and no engineConfig block (a legacy-mode image) is refused by
 * this build, which creates engines only from a recorded EngineConfig; it used to restore with an ERROR and the
 * orders dropped. An image without books (a pre-genesis image) still restores.
 */
public class LegacySnapshotRefusedTest {

    private static byte[] legacyImageWithOneOrder() {
        final Engine legacy = TestEngines.withCompiledMarkets();
        legacy.getEngine(Engine.MARKET_BTC_USD).addOrderNoMatch(1, 100, true, 6_000_000_000_000L, 100_000_000L);
        legacy.getOrderIdToOmsOrderId().put(1, 9001);
        legacy.setTradeIdGenerator(1L);
        final ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(legacy, 0L, b);
        return java.util.Arrays.copyOf(b.byteArray(), len);
    }

    @Test
    public void anImageWithBooksAndNoConfigBlockIsRefusedWithTheGenesisText() {
        final byte[] image = legacyImageWithOneOrder();
        final Engine node = Engine.deferredUntilConfig();
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SnapshotCodec.deserialize(new org.agrona.concurrent.UnsafeBuffer(image), 0, image.length, node));
        assertEquals("[SNAPSHOT] refused: the image carries 1 order(s) in " + com.match.application.engine.MarketConfig
                        .ALL_MARKETS.length + " market(s) and no engineConfig block; this build creates engines only"
                        + " from a recorded EngineConfig (a clean genesis is required)", refused.getMessage());
    }

    @Test
    public void theServiceStopsOnItAndNeverServes() {
        final LaunchModeServices.Service node = new LaunchModeServices.Service(true);
        final String err = LaunchModeServices.stderrOf(() -> assertFalse("refused", node.load(legacyImageWithOneOrder())));
        assertEquals("one stop", 1, node.failFast.get());
        assertEquals("not the exit-78 seam: the recovery is a genesis, not a build", 0, node.failFastIncompatible.get());
        assertFalse(err.contains("orders dropped"));
        assertFalse(node.engine().hasEngines());
    }

    @Test
    public void anEmptyImageWithoutAConfigBlockStillRestores() {
        final Engine source = Engine.deferredUntilConfig();
        final ExpandableArrayBuffer b = new ExpandableArrayBuffer();
        final int len = SnapshotCodec.serialize(source, 0L, b);
        final LaunchModeServices.Service node = new LaunchModeServices.Service(true);
        assertEquals(true, node.load(java.util.Arrays.copyOf(b.byteArray(), len)));
        assertEquals(0, node.failFast.get());
        assertFalse(node.engine().hasEngines());
    }
}
