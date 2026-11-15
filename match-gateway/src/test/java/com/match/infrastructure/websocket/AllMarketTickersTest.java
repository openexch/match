// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.match.domain.FixedPoint;
import com.match.infrastructure.gateway.state.GatewayStateManager;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.TradesBatchDecoder;
import com.match.infrastructure.generated.TradesBatchEncoder;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.agrona.ExpandableDirectByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * The all-market TICKERS frame: shape, routing to every client, protection against
 * nested-marketId misrouting, throttle + coalescing, and send-on-connect/subscribe.
 */
public class AllMarketTickersTest {

    private MarketDataWebSocket ws;
    private GatewayStateManager stateManager;
    private final List<EmbeddedChannel> open = new ArrayList<>();

    @Before
    public void setUp() {
        ws = new MarketDataWebSocket();
        stateManager = new GatewayStateManager();
        ws.setStateManager(stateManager);
    }

    @After
    public void tearDown() {
        for (EmbeddedChannel ch : open) {
            ch.finishAndReleaseAll();
        }
        ws.close();
    }

    // ==================== helpers ====================

    private void trade(int marketId, double price, double qty) {
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(4096);
        TradesBatchEncoder encoder = new TradesBatchEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, new MessageHeaderEncoder());
        encoder.marketId(marketId);
        encoder.timestamp(System.currentTimeMillis());
        encoder.tradesCount(1).next()
            .price(FixedPoint.fromDouble(price))
            .quantity(FixedPoint.fromDouble(qty))
            .tradeCount(1)
            .timestamp(System.currentTimeMillis());
        MessageHeaderDecoder headerDec = new MessageHeaderDecoder();
        headerDec.wrap(buffer, 0);
        TradesBatchDecoder decoder = new TradesBatchDecoder();
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        stateManager.onTradesBatch(decoder);
    }

    private EmbeddedChannel newChannel() {
        EmbeddedChannel ch = new EmbeddedChannel(DefaultChannelId.newInstance(), ws.newHandlerForTest());
        open.add(ch);
        return ch;
    }

    private static void subscribe(EmbeddedChannel ch, int marketId) {
        ch.writeInbound(new TextWebSocketFrame("{\"action\":\"subscribe\",\"marketId\":" + marketId + "}"));
    }

    private static List<String> drain(EmbeddedChannel ch) {
        List<String> out = new ArrayList<>();
        Object o;
        while ((o = ch.readOutbound()) != null) {
            if (o instanceof TextWebSocketFrame f) {
                out.add(f.text());
                f.release();
            }
        }
        return out;
    }

    private static long countTickers(List<String> frames) {
        return frames.stream().filter(MarketDataWebSocket::isAllMarketFrame).count();
    }

    // ==================== shape ====================

    @Test
    public void frameShape_noTopLevelMarketId_entriesReuseTickerStatsFields() {
        trade(2, 3000.0, 2.0);
        trade(1, 50000.0, 1.0);

        String frame = stateManager.buildTickersFrameJson();
        assertNotNull(frame);
        assertTrue(frame.startsWith("{\"type\":\"TICKERS\""));
        JsonObject obj = JsonParser.parseString(frame).getAsJsonObject();
        assertFalse("must not carry a top-level marketId", obj.has("marketId"));
        assertTrue(obj.has("timestamp"));
        JsonArray tickers = obj.getAsJsonArray("tickers");
        assertEquals(2, tickers.size());
        JsonObject first = tickers.get(0).getAsJsonObject();
        assertEquals("ordered by marketId", 1, first.get("marketId").getAsInt());
        assertEquals("BTC-USD", first.get("market").getAsString());
        assertEquals(50000.0, first.get("lastPrice").getAsDouble(), 1e-9);
        for (String f : new String[] {"priceChange", "priceChangePercent", "high24h", "low24h", "volume24h", "timestamp"}) {
            assertTrue("entry has " + f, first.has(f));
        }
        assertFalse("entries carry no type", first.has("type"));

        // Byte-identical to the per-market TICKER_STATS minus its type key.
        String stats = stateManager.getTickerStats(1);
        assertTrue(frame.contains(stats.replace("{\"type\":\"TICKER_STATS\",", "{")));
    }

    @Test
    public void noPricedMarket_noFrame() {
        assertNull(stateManager.buildTickersFrameJson());
        assertEquals("[]", stateManager.buildTickersArrayJson());
    }

    @Test
    public void marketCountIsNotAssumed() {
        // Ids beyond today's five are carried like any other.
        for (int id = 1; id <= 9; id++) {
            trade(id, 10.0 * id, 1.0);
        }
        JsonArray tickers = JsonParser.parseString(stateManager.buildTickersFrameJson())
            .getAsJsonObject().getAsJsonArray("tickers");
        assertEquals(9, tickers.size());
        assertEquals(9, tickers.get(8).getAsJsonObject().get("marketId").getAsInt());
    }

    // ==================== routing ====================

    @Test
    public void broadcastToAll_reachesEveryClientWhateverItsMarket() {
        trade(1, 50000.0, 1.0);
        EmbeddedChannel m1 = newChannel();
        EmbeddedChannel m2 = newChannel();
        EmbeddedChannel unsubscribed = newChannel();
        subscribe(m1, 1);
        subscribe(m2, 2);
        drain(m1);
        drain(m2);
        drain(unsubscribed);

        ws.broadcastToAll(stateManager.buildTickersFrameJson());

        assertEquals(1, countTickers(drain(m1)));
        assertEquals(1, countTickers(drain(m2)));
        assertEquals(1, countTickers(drain(unsubscribed)));
    }

    @Test
    public void nestedMarketIdDoesNotMisrouteThroughBroadcastMarketData() {
        // First nested entry is market 2: the old prefix scan would have sent the
        // frame to market 2's group only. Market 1 and unsubscribed clients must get it.
        trade(2, 3000.0, 1.0);
        trade(3, 150.0, 1.0);
        String frame = stateManager.buildTickersFrameJson();
        assertTrue(frame.indexOf("\"marketId\":2") < frame.indexOf("\"marketId\":3"));

        EmbeddedChannel m1 = newChannel();
        EmbeddedChannel m2 = newChannel();
        EmbeddedChannel unsubscribed = newChannel();
        subscribe(m1, 1);
        subscribe(m2, 2);
        drain(m1);
        drain(m2);

        ws.broadcastMarketData(frame);

        assertEquals(1, countTickers(drain(m1)));
        assertEquals(1, countTickers(drain(m2)));
        assertEquals(1, countTickers(drain(unsubscribed)));
    }

    @Test
    public void perMarketFramesStillRouteByMarket() {
        EmbeddedChannel m1 = newChannel();
        EmbeddedChannel m2 = newChannel();
        subscribe(m1, 1);
        subscribe(m2, 2);
        drain(m1);
        drain(m2);

        ws.broadcastMarketData("{\"type\":\"TICKER_STATS\",\"marketId\":2,\"lastPrice\":1}");

        assertTrue(drain(m1).isEmpty());
        assertEquals(1, drain(m2).size());
    }

    // ==================== send on connect / subscribe ====================

    @Test
    public void sentOnHandshakeComplete() {
        trade(1, 50000.0, 1.0);
        EmbeddedChannel ch = newChannel();
        ch.pipeline().fireUserEventTriggered(
            new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null));
        assertEquals(1, countTickers(drain(ch)));
    }

    @Test
    public void sentOnSubscribe() {
        trade(1, 50000.0, 1.0);
        trade(2, 3000.0, 1.0);
        EmbeddedChannel ch = newChannel();
        subscribe(ch, 2);
        List<String> frames = drain(ch);
        assertEquals(1, countTickers(frames));
        String tickers = frames.stream().filter(MarketDataWebSocket::isAllMarketFrame).findFirst().orElseThrow();
        assertTrue("carries the unsubscribed market too", tickers.contains("\"market\":\"BTC-USD\""));
    }

    @Test
    public void nothingSentOnConnectWhileNoMarketHasAPrice() {
        EmbeddedChannel ch = newChannel();
        ch.pipeline().fireUserEventTriggered(
            new WebSocketServerProtocolHandler.HandshakeComplete("/ws", new DefaultHttpHeaders(), null));
        assertEquals(0, countTickers(drain(ch)));
    }

    // ==================== throttle + coalescing ====================

    @Test
    public void tick_coalescesManyUpdatesIntoOneFrame_andSkipsWhenUnchanged() {
        List<String> sent = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1000);
        AllMarketTickersPublisher pub = new AllMarketTickersPublisher(stateManager, sent::add, 1000, clock::get);

        assertNull("no priced market: nothing", pub.tick());

        trade(1, 50000.0, 1.0);
        trade(1, 50010.0, 1.0);
        trade(2, 3000.0, 1.0);
        trade(1, 50020.0, 1.0);
        String frame = pub.tick();
        assertNotNull(frame);
        assertEquals("one frame for the whole window", 1, sent.size());
        JsonArray tickers = JsonParser.parseString(frame).getAsJsonObject().getAsJsonArray("tickers");
        assertEquals("latest value wins", 50020.0, tickers.get(0).getAsJsonObject().get("lastPrice").getAsDouble(), 1e-9);
        assertEquals(1000L, JsonParser.parseString(frame).getAsJsonObject().get("timestamp").getAsLong());

        clock.set(2000);
        assertNull("unchanged: nothing sent", pub.tick());
        assertEquals(1, sent.size());

        trade(2, 3001.0, 1.0);
        assertNotNull(pub.tick());
        assertEquals(2, sent.size());
    }

    @Test
    public void scheduledPublisher_sendsAtMostOncePerInterval() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        long interval = 100;
        AllMarketTickersPublisher pub = new AllMarketTickersPublisher(stateManager, sent::add, interval, System::currentTimeMillis);
        pub.start();
        try {
            long start = System.currentTimeMillis();
            double p = 50000;
            while (System.currentTimeMillis() - start < 600) {
                trade(1, p++, 1.0); // a change on every iteration
                Thread.sleep(2);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertFalse("sent something", sent.isEmpty());
            // fixed-delay scheduling: at most one frame per elapsed interval (+1 slack)
            assertTrue("sent " + sent.size() + " in " + elapsed + "ms",
                sent.size() <= elapsed / interval + 1);
        } finally {
            pub.close();
        }
    }

    @Test
    public void intervalSetting() {
        assertEquals(1000, AllMarketTickersPublisher.parseInterval(null));
        assertEquals(1000, AllMarketTickersPublisher.parseInterval(" "));
        assertEquals(250, AllMarketTickersPublisher.parseInterval("250"));
        for (String bad : new String[] {"0", "-5", "1s", "abc"}) {
            try {
                AllMarketTickersPublisher.parseInterval(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // strict: no silent default
            }
        }
    }
}
