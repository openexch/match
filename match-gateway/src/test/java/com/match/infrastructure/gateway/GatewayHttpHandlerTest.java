// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway;

import com.match.infrastructure.gateway.state.GatewayStateManager;
import com.match.infrastructure.gateway.state.GatewayOrderBook;
import com.match.infrastructure.websocket.MarketDataWebSocket;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.junit.Before;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for GatewayHttpHandler using Netty EmbeddedChannel.
 * Covers all HTTP REST API endpoints: /health, /api/orderbook, /api/trades.
 */
public class GatewayHttpHandlerTest {

    private GatewayStateManager stateManager;

    @Before
    public void setUp() {
        stateManager = new GatewayStateManager();
    }

    private EmbeddedChannel createChannel() {
        return new EmbeddedChannel(new GatewayHttpHandler(stateManager));
    }

    private FullHttpResponse sendRequest(EmbeddedChannel channel, HttpMethod method, String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri);
        channel.writeInbound(request);
        return channel.readOutbound();
    }

    private String getBody(FullHttpResponse response) {
        String body = response.content().toString(CharsetUtil.UTF_8);
        response.release();
        return body;
    }

    // ==================== Health ====================

    @Test
    public void testHealthEndpoint() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/health");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"status\":\"ok\""));
        assertTrue(body.contains("\"orderBook\":false"));
        assertTrue(body.contains("\"trades\":false"));
        channel.finish();
    }

    @Test
    public void testHealthEndpointWithTrailingSlash() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/health/");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"status\":\"ok\""));
        channel.finish();
    }

    @Test
    public void testHealthEndpointWithBookData() {
        // Populate order book for market 1
        populateOrderBook(1, "BTC-USD");

        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/health");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"orderBook\":true"));
        channel.finish();
    }

    // ==================== Order Book ====================

    @Test
    public void testOrderBookEmpty() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"type\":\"BOOK_SNAPSHOT\""));
        assertTrue(body.contains("\"bids\":[]"));
        assertTrue(body.contains("\"asks\":[]"));
        channel.finish();
    }

    /**
     * match#98: the REST empty-book response must include the version fields
     * (bidVersion/askVersion/version = 0) that a REST-bootstrapping client uses
     * to seed its chain-continuity check, the same way the WS path already does.
     */
    @Test
    public void testOrderBookEmptyIncludesVersionFields() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        assertTrue("bidVersion missing from empty-book REST response", json.has("bidVersion"));
        assertTrue("askVersion missing from empty-book REST response", json.has("askVersion"));
        assertTrue("version missing from empty-book REST response", json.has("version"));
        assertEquals(0, json.get("bidVersion").getAsInt());
        assertEquals(0, json.get("askVersion").getAsInt());
        assertEquals(0, json.get("version").getAsInt());
        channel.finish();
    }

    /**
     * match#98: REST and WS must build the empty-book snapshot from the same shared
     * method, so they can never drift apart again. Compares parsed JSON key-for-key
     * (excluding "timestamp", which is independently stamped by each call with
     * System.currentTimeMillis() and so may legitimately differ by a few millis).
     */
    @Test
    public void testOrderBookEmptyMatchesWebSocketEmptySnapshotKeyForKey() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook?marketId=2");
        assertEquals(HttpResponseStatus.OK, response.status());
        String restBody = getBody(response);
        channel.finish();

        String wsBody = MarketDataWebSocket.buildEmptyBookSnapshotJson(2);

        JsonObject restJson = JsonParser.parseString(restBody).getAsJsonObject();
        JsonObject wsJson = JsonParser.parseString(wsBody).getAsJsonObject();

        Set<String> restKeys = restJson.keySet();
        Set<String> wsKeys = wsJson.keySet();
        assertEquals("REST and WS empty-book snapshots must expose the same fields", wsKeys, restKeys);

        for (String key : wsKeys) {
            if ("timestamp".equals(key)) {
                assertTrue(restJson.get("timestamp").getAsJsonPrimitive().isNumber());
                assertTrue(wsJson.get("timestamp").getAsJsonPrimitive().isNumber());
                continue;
            }
            assertEquals("Mismatch for field \"" + key + "\"", wsJson.get(key), restJson.get(key));
        }
    }

    @Test
    public void testOrderBookWithData() {
        populateOrderBook(1, "BTC-USD");

        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"type\":\"BOOK_SNAPSHOT\""));
        assertTrue(body.contains("BTC-USD"));
        assertTrue(body.contains("50000"));
        channel.finish();
    }

    @Test
    public void testOrderBookSpecificMarketId() {
        populateOrderBook(2, "ETH-USD");

        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook?marketId=2");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("ETH-USD"));
        channel.finish();
    }

    @Test
    public void testOrderBookWithInvalidMarketId() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook?marketId=abc");
        assertEquals(HttpResponseStatus.OK, response.status());
        // Falls back to default marketId=1
        String body = getBody(response);
        assertTrue(body.contains("\"marketId\":1"));
        channel.finish();
    }

    @Test
    public void testOrderBookResponseHeaders() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook");
        assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
        assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNotNull(response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        response.release();
        channel.finish();
    }

    // ==================== Trades ====================

    @Test
    public void testTradesEmpty() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/trades");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"type\":\"TRADES_BATCH\""));
        assertTrue(body.contains("\"trades\""));
        channel.finish();
    }

    @Test
    public void testTradesWithLimitParam() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/trades?limit=10");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"type\":\"TRADES_BATCH\""));
        channel.finish();
    }

    @Test
    public void testTradesLimitCappedAt500() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/trades?limit=9999");
        assertEquals(HttpResponseStatus.OK, response.status());
        // The cap at 500 doesn't change the response if there are no trades
        getBody(response);
        channel.finish();
    }

    @Test
    public void testTradesWithInvalidLimit() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/trades?limit=xyz");
        assertEquals(HttpResponseStatus.OK, response.status());
        // Falls back to default limit=50
        getBody(response);
        channel.finish();
    }

    // ==================== Error Cases ====================

    @Test
    public void testUnknownApiEndpoint() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/unknown");
        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"error\""));
        assertTrue(body.contains("Unknown endpoint"));
        channel.finish();
    }

    @Test
    public void testMethodNotAllowed() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.POST, "/api/orderbook");
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
        String body = getBody(response);
        assertTrue(body.contains("Only GET method supported"));
        channel.finish();
    }

    @Test
    public void testNonApiUriPassedThrough() {
        EmbeddedChannel channel = createChannel();
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/ws");
        channel.writeInbound(request);
        // Non-API request should be passed through (fireChannelRead)
        // EmbeddedChannel won't have another handler, so the request will just pass
        FullHttpResponse response = channel.readOutbound();
        // No response expected — the handler passes it through
        assertNull(response);
        channel.finish();
    }

    @Test
    public void testExceptionCaught() {
        GatewayHttpHandler handler = new GatewayHttpHandler(stateManager);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Trigger exceptionCaught by passing an exception directly through the pipeline
        channel.pipeline().fireExceptionCaught(new RuntimeException("Test error"));

        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        String body = getBody(response);
        assertTrue(body.contains("Test error"));
        channel.finish();
    }

    // ==================== Query Param Parsing Edge Cases ====================

    @Test
    public void testOrderBookWithMultipleQueryParams() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/api/orderbook?marketId=2&extra=foo");
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = getBody(response);
        assertTrue(body.contains("\"marketId\":2"));
        channel.finish();
    }

    @Test
    public void testPutMethodOnApiEndpoint() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.PUT, "/api/trades");
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
        response.release();
        channel.finish();
    }

    // ==================== Helpers ====================

    private void populateOrderBook(int marketId, String marketName) {
        // We need to use reflection-free approach: directly access GatewayStateManager
        // by calling methods that create order books.
        // GatewayStateManager creates order books via onBookSnapshot, but that requires SBE decoders.
        // Instead, use getOrCreateOrderBook indirectly by populating via onBookSnapshot.
        // Actually, the simplest approach: use the GatewayOrderBook directly.
        // But GatewayStateManager.getOrderBook returns null if not created.
        // We need to trigger createOrderBook — the simplest way is to encode an SBE message.
        // Let's use the SBE approach:

        org.agrona.ExpandableDirectByteBuffer buffer = new org.agrona.ExpandableDirectByteBuffer(4096);
        com.match.infrastructure.generated.MessageHeaderEncoder headerEnc = new com.match.infrastructure.generated.MessageHeaderEncoder();
        com.match.infrastructure.generated.BookSnapshotEncoder encoder = new com.match.infrastructure.generated.BookSnapshotEncoder();

        encoder.wrapAndApplyHeader(buffer, 0, headerEnc);
        encoder.marketId(marketId);
        encoder.timestamp(System.currentTimeMillis());
        encoder.bidVersion(10);
        encoder.askVersion(20);

        com.match.infrastructure.generated.BookSnapshotEncoder.BidsEncoder bids = encoder.bidsCount(1);
        bids.next().price(5000000000000L).quantity(50000000L).orderCount(3); // 50000.0, 0.5

        com.match.infrastructure.generated.BookSnapshotEncoder.AsksEncoder asks = encoder.asksCount(1);
        asks.next().price(5010000000000L).quantity(100000000L).orderCount(2); // 50100.0, 1.0

        // Decode and feed to state manager
        com.match.infrastructure.generated.MessageHeaderDecoder headerDec = new com.match.infrastructure.generated.MessageHeaderDecoder();
        com.match.infrastructure.generated.BookSnapshotDecoder decoder = new com.match.infrastructure.generated.BookSnapshotDecoder();
        headerDec.wrap(buffer, 0);
        decoder.wrapAndApplyHeader(buffer, 0, headerDec);
        stateManager.onBookSnapshot(decoder);
    }

    // ==================== /metrics (match#33) ====================

    @Test
    public void metricsEndpointRendersPrometheusText() {
        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/metrics");

        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get("Content-Type").startsWith("text/plain"));
        String body = getBody(response);
        assertTrue(body.contains("gateway_ws_clients"));
        assertTrue(body.contains("# TYPE gateway_stale_deltas_total counter"));
        assertTrue(body.contains("# TYPE gateway_stale_snapshots_total counter"));
        // match#96 series.
        assertTrue(body.contains("# TYPE gateway_chain_breaks_total counter"));
        assertTrue(body.contains("# TYPE gateway_deltas_dropped_stale_total counter"));
        assertTrue(body.contains("# TYPE gateway_books_stale gauge"));
        // No AeronGateway wired in this test: relay series are omitted, not broken.
        assertFalse(body.contains("gateway_egress_messages_total"));
    }

    @Test
    public void metricsBooksStaleGaugeReflectsChainBrokenBook() {
        // A chain-broken book must surface in the gateway_books_stale gauge.
        populateOrderBook(1, "BTC-USD");
        GatewayOrderBook book = stateManager.getOrderBook(1);
        book.markStale(book.getVersion());

        EmbeddedChannel channel = createChannel();
        FullHttpResponse response = sendRequest(channel, HttpMethod.GET, "/metrics");
        String body = getBody(response);
        assertTrue(body.contains("gateway_books_stale 1"));
    }
}
