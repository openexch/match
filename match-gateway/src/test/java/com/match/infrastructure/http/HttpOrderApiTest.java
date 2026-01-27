package com.match.infrastructure.http;

import com.match.infrastructure.gateway.AeronGateway;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.Assert.*;

/**
 * Tests for HttpOrderApi using a real HttpServer on a random port.
 * AeronGateway constructor works without env vars (defaults to 127.0.0.1).
 * isConnected() returns false since we never call connect(), so we test:
 * - POST with valid order → 503 (cluster not connected)
 * - POST with validation errors → 400
 * - POST with malformed JSON → 400
 * - POST with empty/null body → 400
 * - OPTIONS → 204 (CORS preflight)
 * - GET → 405 (method not allowed)
 */
public class HttpOrderApiTest {

    private HttpServer server;
    private AeronGateway gateway;
    private HttpClient client;
    private int port;

    @Before
    public void setUp() throws Exception {
        gateway = new AeronGateway();
        HttpOrderApi api = new HttpOrderApi(gateway);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/order", api);
        server.start();
        port = server.getAddress().getPort();

        client = HttpClient.newHttpClient();
    }

    @After
    public void tearDown() {
        server.stop(0);
        gateway.close();
    }

    private HttpResponse<String> sendPost(String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/order"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ==================== POST: Valid order, cluster not connected → 503 ====================

    @Test
    public void testPost_ValidOrder_ClusterNotConnected_Returns503() throws Exception {
        String validOrder = "{\"userId\":\"123\",\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(validOrder);
        assertEquals(503, resp.statusCode());
        assertTrue(resp.body().contains("Cluster is not connected"));
    }

    @Test
    public void testPost_ValidOrder_SellSide_Returns503() throws Exception {
        String order = "{\"userId\":\"456\",\"market\":\"ETH-USD\",\"orderSide\":\"SELL\",\"orderType\":\"LIMIT\",\"price\":3000.0,\"quantity\":2.5}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(503, resp.statusCode());
    }

    // ==================== POST: Validation errors → 400 ====================

    @Test
    public void testPost_MissingMarket_Returns400() throws Exception {
        String order = "{\"userId\":\"123\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("market is required"));
    }

    @Test
    public void testPost_InvalidMarket_Returns400() throws Exception {
        String order = "{\"userId\":\"123\",\"market\":\"INVALID\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("unknown market"));
    }

    @Test
    public void testPost_MissingOrderSide_Returns400() throws Exception {
        String order = "{\"userId\":\"123\",\"market\":\"BTC-USD\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("orderSide is required"));
    }

    @Test
    public void testPost_ZeroQuantity_Returns400() throws Exception {
        String order = "{\"userId\":\"123\",\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("quantity must be positive"));
    }

    @Test
    public void testPost_MissingUserId_Returns400() throws Exception {
        String order = "{\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("userId is required"));
    }

    @Test
    public void testPost_ZeroPrice_LimitOrder_Returns400() throws Exception {
        String order = "{\"userId\":\"123\",\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(order);
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("limit order requires positive price"));
    }

    // ==================== POST: Malformed JSON → 400 ====================

    @Test
    public void testPost_MalformedJson_Returns400() throws Exception {
        HttpResponse<String> resp = sendPost("{not valid json!!!");
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("Malformed JSON"));
    }

    // ==================== POST: Empty body → 400 ====================

    @Test
    public void testPost_EmptyBody_Returns400() throws Exception {
        HttpResponse<String> resp = sendPost("");
        assertEquals(400, resp.statusCode());
        // Empty string parsed by Gson returns null Order
        assertTrue(resp.body().contains("Invalid or empty JSON body"));
    }

    @Test
    public void testPost_NullJsonBody_Returns400() throws Exception {
        // "null" is valid JSON that Gson parses as null
        HttpResponse<String> resp = sendPost("null");
        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("Invalid or empty JSON body"));
    }

    // ==================== OPTIONS: CORS preflight → 204 ====================

    @Test
    public void testOptions_Returns204() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/order"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, resp.statusCode());
    }

    @Test
    public void testOptions_HasCorsHeaders() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/order"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertNotNull(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertNotNull(resp.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
    }

    // ==================== GET: Method not allowed → 405 ====================

    @Test
    public void testGet_Returns405() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/order"))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
        assertTrue(resp.body().contains("Only POST method is supported"));
    }

    // ==================== CORS headers present on all responses ====================

    @Test
    public void testPost_HasCorsHeaders() throws Exception {
        String validOrder = "{\"userId\":\"123\",\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":100.0,\"quantity\":1.0}";
        HttpResponse<String> resp = sendPost(validOrder);
        String origin = resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
        assertEquals("*", origin);
    }
}
