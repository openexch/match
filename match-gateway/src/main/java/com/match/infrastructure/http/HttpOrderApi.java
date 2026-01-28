package com.match.infrastructure.http;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.generated.CreateOrderEncoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP handler for order submission.
 * Handles POST /order endpoint for submitting orders to the cluster.
 *
 * Thread-safe: each HTTP thread uses thread-local encoders and submits
 * via the gateway's MPSC queue (no shared mutable state).
 */
public class HttpOrderApi implements HttpHandler {

    private static final Gson gson = new Gson();
    private final AeronGateway gateway;

    /**
     * Thread-local encoder context for safe concurrent order encoding.
     * Each HTTP thread gets its own buffer + encoders to avoid shared mutable state.
     */
    private static final ThreadLocal<EncoderContext> encoderContext =
        ThreadLocal.withInitial(EncoderContext::new);

    private static class EncoderContext {
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[128]);
        final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
        final CreateOrderEncoder createOrderEncoder = new CreateOrderEncoder();
    }

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

                if (order == null) {
                    responseText = "Error: Invalid or empty JSON body.";
                    statusCode = 400;
                } else {
                    // Validate at the gateway — reject before it reaches the engine
                    String validationError = order.validate();
                    if (validationError != null) {
                        responseText = "Error: " + validationError;
                        statusCode = 400;
                    } else if (!gateway.isConnected()) {
                        if (gateway.isTransitioning()) {
                            responseText = "Error: Leader transition in progress, retry shortly.";
                        } else {
                            responseText = "Error: Cluster is not connected.";
                        }
                        statusCode = 503;
                    } else {
                        sendOrder(order);
                    }
                }
            } catch (JsonSyntaxException e) {
                responseText = "Error: Malformed JSON.";
                statusCode = 400;
            } catch (IllegalStateException e) {
                // Transient cluster disconnects (leader transition, reconnecting, queue full)
                responseText = "Error: " + e.getMessage();
                statusCode = 503;
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
        // Allow requests from public networks to private/local network
        headers.set("Access-Control-Allow-Private-Network", "true");
    }

    private void sendOrder(Order order) {
        var ctx = encoderContext.get();
        ctx.createOrderEncoder.wrapAndApplyHeader(ctx.buffer, 0, ctx.headerEncoder);

        // Use primitive types for zero-allocation encoding
        ctx.createOrderEncoder.userId(order.getUserIdAsLong());
        ctx.createOrderEncoder.price(order.getPriceAsLong());
        ctx.createOrderEncoder.quantity(order.getQuantityAsLong());
        ctx.createOrderEncoder.totalPrice(order.getTotalPriceAsLong());
        ctx.createOrderEncoder.marketId(order.getMarketId());
        ctx.createOrderEncoder.orderType(order.toOrderType());
        ctx.createOrderEncoder.orderSide(order.toOrderSide());

        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ctx.createOrderEncoder.encodedLength();

        if (!gateway.submitOrder(ctx.buffer, 0, length)) {
            throw new IllegalStateException("Order queue is full, try again");
        }
    }
}
