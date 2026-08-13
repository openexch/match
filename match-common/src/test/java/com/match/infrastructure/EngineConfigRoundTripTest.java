// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import com.match.infrastructure.generated.EngineConfigDecoder;
import com.match.infrastructure.generated.EngineConfigEncoder;
import com.match.infrastructure.generated.EngineImpl;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;

/**
 * Wire round-trip test for the v10 EngineConfig ingress template (order-schema id=1,
 * template id 8).
 *
 * <p>This bump is ONLY the wire schema + codegen (finding A-3 generalized: engine-creation
 * config whose values are replicated via the cluster log); the demuxer deliberately has no
 * handler yet — dispatch drops the template via the unknown-template path until the
 * state-machine handler lands in the next PR. These tests just prove the generated codecs
 * encode/decode every field faithfully, including the schema's first fixed-length ASCII
 * field (char[16] symbol) and a multi-entry markets group, using the real
 * MessageHeaderEncoder/Decoder pair.</p>
 */
public class EngineConfigRoundTripTest {

    @Test
    public void engineConfigRoundTripsAllFieldsWithFiveMarkets() {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        EngineConfigEncoder encoder = new EngineConfigEncoder();

        final long configVersion = 4_000_000_007L; // above Integer.MAX_VALUE: proves the uint32 range survives
        final long bookCapacity = 1_048_576L;
        final long maxMatchesPerOrder = 50_000L;
        final long maxOrdersPerLevel = 4_096L;

        final int[] marketIds = {1, 2, 7, 500, 65_000};
        // Varying lengths incl. one at exactly char[16]; shorter symbols are null-padded on the wire.
        final String[] symbols = {"BTC-USDT", "ETH-USDT", "X", "ABCDEFGHIJKLMNOP", "DOGE-TRY"};
        final long[] minPrices = {1_000L, 25_000L, 1L, 9_000_000_000L, 42L};
        final long[] maxPrices = {9_000_000_000_000L, 8_500_000_000L, 100L, 9_223_372_036_854_770L, 777_777L};
        final long[] tickSizes = {500_000L, 100_000L, 1L, 1_000_000L, 250L};

        EngineConfigEncoder.MarketsEncoder marketsEncoder = encoder
                .wrapAndApplyHeader(buffer, 0, headerEncoder)
                .configVersion(configVersion)
                .impl(EngineImpl.DIRECT)
                .bookCapacity(bookCapacity)
                .maxMatchesPerOrder(maxMatchesPerOrder)
                .maxOrdersPerLevel(maxOrdersPerLevel)
                .marketsCount(marketIds.length);
        for (int i = 0; i < marketIds.length; i++) {
            marketsEncoder.next()
                    .marketId(marketIds[i])
                    .symbol(symbols[i])
                    .minPrice(minPrices[i])
                    .maxPrice(maxPrices[i])
                    .tickSize(tickSizes[i]);
        }

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder().wrap(buffer, 0);
        assertEquals(EngineConfigDecoder.TEMPLATE_ID, headerDecoder.templateId());
        assertEquals(EngineConfigDecoder.SCHEMA_ID, headerDecoder.schemaId());
        assertEquals(EngineConfigDecoder.SCHEMA_VERSION, headerDecoder.version());

        EngineConfigDecoder decoder = new EngineConfigDecoder().wrap(
                buffer, headerDecoder.encodedLength(), headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(configVersion, decoder.configVersion());
        assertEquals(EngineImpl.DIRECT, decoder.impl());
        assertEquals(bookCapacity, decoder.bookCapacity());
        assertEquals(maxMatchesPerOrder, decoder.maxMatchesPerOrder());
        assertEquals(maxOrdersPerLevel, decoder.maxOrdersPerLevel());

        EngineConfigDecoder.MarketsDecoder markets = decoder.markets();
        assertEquals(marketIds.length, markets.count());
        int i = 0;
        while (markets.hasNext()) {
            markets.next();
            assertEquals(marketIds[i], markets.marketId());
            assertEquals(symbols[i], markets.symbol());
            assertEquals(minPrices[i], markets.minPrice());
            assertEquals(maxPrices[i], markets.maxPrice());
            assertEquals(tickSizes[i], markets.tickSize());
            i++;
        }
        assertEquals(marketIds.length, i);
    }

    @Test
    public void engineConfigRoundTripsBothImplValues() {
        assertImplRoundTrips(EngineImpl.ARRAY);
        assertImplRoundTrips(EngineImpl.DIRECT);
    }

    private static void assertImplRoundTrips(EngineImpl impl) {
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
        MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        EngineConfigEncoder encoder = new EngineConfigEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .configVersion(1L)
                .impl(impl)
                .bookCapacity(2L)
                .maxMatchesPerOrder(3L)
                .maxOrdersPerLevel(4L)
                .marketsCount(0);

        MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder().wrap(buffer, 0);
        EngineConfigDecoder decoder = new EngineConfigDecoder().wrap(
                buffer, headerDecoder.encodedLength(), headerDecoder.blockLength(), headerDecoder.version());

        assertEquals(impl, decoder.impl());
        assertEquals(0, decoder.markets().count());
    }
}
