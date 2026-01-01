package com.match.integration;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration test for WebSocket functionality.
 * Run this after starting the cluster and gateway.
 */
public class WebSocketIntegrationTest {

    private static final String WS_HOST = "localhost";
    private static final int WS_PORT = 8081;
    private static final String HTTP_BASE = "http://localhost:8080";

    private static final CopyOnWriteArrayList<String> receivedMessages = new CopyOnWriteArrayList<>();
    private static Channel wsChannel;
    private static CountDownLatch connectLatch = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        System.out.println("=== WebSocket Integration Test ===\n");

        // Connect to WebSocket
        System.out.println("1. Connecting to WebSocket at ws://" + WS_HOST + ":" + WS_PORT + "/ws");
        connectWebSocket();

        if (!connectLatch.await(5, TimeUnit.SECONDS)) {
            System.err.println("FAILED: Could not connect to WebSocket");
            System.exit(1);
        }
        System.out.println("   Connected successfully!\n");

        // Subscribe to market
        System.out.println("2. Subscribing to market 1 (BTC-USD)");
        sendWsMessage("{\"action\":\"subscribe\",\"marketId\":1}");
        Thread.sleep(500);

        // Check subscription confirmation
        boolean subscribed = receivedMessages.stream()
            .anyMatch(m -> m.contains("SUBSCRIPTION_CONFIRMED"));
        System.out.println("   Subscription confirmed: " + subscribed + "\n");

        // Clear messages for fresh test
        receivedMessages.clear();

        // Send a limit order via HTTP
        System.out.println("3. Sending limit BID order via HTTP (price=100000, qty=1.0)");
        sendOrder("BID", "LIMIT", 100000.0, 1.0);
        Thread.sleep(1000);

        // Check for NEW order status
        System.out.println("   Received messages:");
        for (String msg : receivedMessages) {
            if (msg.contains("ORDER_STATUS") || msg.contains("BOOK_SNAPSHOT")) {
                System.out.println("   - " + truncate(msg, 120));
            }
        }

        boolean hasNewStatus = receivedMessages.stream()
            .anyMatch(m -> m.contains("ORDER_STATUS") && m.contains("NEW"));
        System.out.println("\n   NEW order status received: " + hasNewStatus);

        boolean hasBookSnapshot = receivedMessages.stream()
            .anyMatch(m -> m.contains("BOOK_SNAPSHOT"));
        System.out.println("   Book snapshot received: " + hasBookSnapshot + "\n");

        // Send a matching sell order
        receivedMessages.clear();
        System.out.println("4. Sending matching ASK order via HTTP (price=100000, qty=0.5)");
        sendOrder("ASK", "LIMIT", 100000.0, 0.5);
        Thread.sleep(1000);

        System.out.println("   Received messages:");
        for (String msg : receivedMessages) {
            if (msg.contains("ORDER_STATUS") || msg.contains("TRADES_BATCH") || msg.contains("BOOK_SNAPSHOT")) {
                System.out.println("   - " + truncate(msg, 120));
            }
        }

        boolean hasFilledStatus = receivedMessages.stream()
            .anyMatch(m -> m.contains("ORDER_STATUS") && m.contains("FILLED"));
        System.out.println("\n   FILLED order status received: " + hasFilledStatus);

        boolean hasTrades = receivedMessages.stream()
            .anyMatch(m -> m.contains("TRADES_BATCH"));
        System.out.println("   Trades batch received: " + hasTrades + "\n");

        // Test PARTIALLY_FILLED status
        // Use a price higher than existing bids so ASK goes to book first
        receivedMessages.clear();
        System.out.println("5. Testing PARTIALLY_FILLED status");
        System.out.println("   First, placing small ASK order (price=101000, qty=0.3)");
        sendOrder("ASK", "LIMIT", 101000.0, 0.3);
        Thread.sleep(500);

        System.out.println("   Now placing larger BID that will partially fill (price=101000, qty=1.0)");
        sendOrder("BID", "LIMIT", 101000.0, 1.0);
        Thread.sleep(1000);

        System.out.println("   Received messages:");
        for (String msg : receivedMessages) {
            if (msg.contains("ORDER_STATUS")) {
                System.out.println("   - " + truncate(msg, 150));
            }
        }

        boolean hasPartiallyFilled = receivedMessages.stream()
            .anyMatch(m -> m.contains("ORDER_STATUS") && m.contains("PARTIALLY_FILLED"));
        System.out.println("\n   PARTIALLY_FILLED status received: " + hasPartiallyFilled + "\n");

        // Summary
        System.out.println("=== Test Summary ===");
        System.out.println("WebSocket connection: PASS");
        System.out.println("Subscription: " + (subscribed ? "PASS" : "FAIL"));
        System.out.println("NEW order status: " + (hasNewStatus ? "PASS" : "FAIL"));
        System.out.println("FILLED order status: " + (hasFilledStatus ? "PASS" : "FAIL"));
        System.out.println("PARTIALLY_FILLED status: " + (hasPartiallyFilled ? "PASS" : "FAIL"));
        System.out.println("Trades broadcast: " + (hasTrades ? "PASS" : "FAIL"));
        System.out.println("Book snapshots: " + (hasBookSnapshot ? "PASS" : "FAIL"));

        boolean allPassed = subscribed && hasNewStatus && hasFilledStatus &&
                           hasPartiallyFilled && hasTrades && hasBookSnapshot;
        System.out.println("\n" + (allPassed ? "✓ ALL TESTS PASSED" : "✗ SOME TESTS FAILED"));

        // Cleanup
        if (wsChannel != null) {
            wsChannel.close();
        }
        System.exit(allPassed ? 0 : 1);
    }

    private static void connectWebSocket() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup(1);

        URI uri = new URI("ws://" + WS_HOST + ":" + WS_PORT + "/ws");
        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(8192),
                        new WebSocketClientHandler(handshaker)
                    );
                }
            });

        wsChannel = bootstrap.connect(WS_HOST, WS_PORT).sync().channel();
    }

    private static void sendWsMessage(String message) {
        if (wsChannel != null && wsChannel.isActive()) {
            wsChannel.writeAndFlush(new TextWebSocketFrame(message));
        }
    }

    private static void sendOrder(String side, String type, double price, double quantity) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        String json = String.format(
            "{\"userId\":\"1\",\"market\":\"BTC-USD\",\"orderSide\":\"%s\",\"orderType\":\"%s\",\"price\":%.2f,\"quantity\":%.8f,\"timestamp\":%d}",
            side, type, price, quantity, System.currentTimeMillis());

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(HTTP_BASE + "/order"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("   HTTP Response: " + response.statusCode() + " - " + response.body());
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    static class WebSocketClientHandler extends SimpleChannelInboundHandler<Object> {
        private final WebSocketClientHandshaker handshaker;

        WebSocketClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                    connectLatch.countDown();
                } catch (WebSocketHandshakeException e) {
                    System.err.println("Handshake failed: " + e.getMessage());
                }
                return;
            }

            if (msg instanceof TextWebSocketFrame) {
                String text = ((TextWebSocketFrame) msg).text();
                receivedMessages.add(text);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
