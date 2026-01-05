package com.match.infrastructure.http;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler for order submission.
 * Handles POST /order endpoint for submitting orders to the cluster.
 */
public class HttpOrderApi implements HttpHandler {

    private static final Gson gson = new Gson();
    private final AeronGateway gateway;

    public HttpOrderApi(AeronGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Set CORS headers for all responses
        setCorsHeaders(exchange);

        // Handle CORS preflight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String responseText = "Order accepted and forwarded to cluster.";
        int statusCode = 202;

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                Order order = gson.fromJson(reader, Order.class);

                if (order == null || order.market == null) {
                    responseText = "Error: Invalid or empty JSON body.";
                    statusCode = 400;
                } else {
                    sendOrder(order);
                }
            } catch (JsonSyntaxException e) {
                responseText = "Error: Malformed JSON.";
                statusCode = 400;
            } catch (Exception e) {
                responseText = "Error processing request: " + e.getMessage();
                statusCode = 500;
                e.printStackTrace();
            }
        } else {
            responseText = "Error: Only POST method is supported.";
            statusCode = 405;
        }

        exchange.sendResponseHeaders(statusCode, responseText.getBytes().length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseText.getBytes());
        }
    }

    private void setCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendOrder(Order order) {
        var buffer = gateway.getBuffer();
        var headerEncoder = gateway.getHeaderEncoder();
        var createOrderEncoder = gateway.getCreateOrderEncoder();

        createOrderEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder);

        // Use primitive types for zero-allocation encoding
        createOrderEncoder.userId(order.getUserIdAsLong());
        createOrderEncoder.price(order.getPriceAsLong());
        createOrderEncoder.quantity(order.getQuantityAsLong());
        createOrderEncoder.totalPrice(order.getTotalPriceAsLong());
        createOrderEncoder.marketId(order.getMarketId());
        createOrderEncoder.orderType(order.toOrderType());
        createOrderEncoder.orderSide(order.toOrderSide());

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + createOrderEncoder.encodedLength();
        gateway.offer(buffer, 0, length);
    }
}
