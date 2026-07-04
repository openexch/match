// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway;

import com.match.infrastructure.gateway.state.GatewayStateManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

/**
 * HTTP handler for REST API endpoints on Market Gateway.
 * Handles requests before WebSocket upgrade.
 * Endpoints:
 * - GET /api/orderbook - Current order book
 * - GET /api/trades?limit=N - Recent trades
 * - GET /health - Gateway health check
 */
@ChannelHandler.Sharable
public class GatewayHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final GatewayStateManager stateManager;
    private final com.match.infrastructure.websocket.MarketDataWebSocket webSocket;
    private final AeronGateway aeronGateway;

    public GatewayHttpHandler(GatewayStateManager stateManager) {
        this(stateManager, null, null);
    }

    public GatewayHttpHandler(GatewayStateManager stateManager,
                              com.match.infrastructure.websocket.MarketDataWebSocket webSocket,
                              AeronGateway aeronGateway) {
        this.stateManager = stateManager;
        this.webSocket = webSocket;
        this.aeronGateway = aeronGateway;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
        String uri = req.uri();

        // Handle API endpoints
        if (uri.startsWith("/api/")) {
            handleApiRequest(ctx, req, uri);
            return;
        }

        // Health check
        if (uri.equals("/health") || uri.equals("/health/")) {
            handleHealth(ctx, req);
            return;
        }

        // Prometheus scrape (match#33)
        if (uri.equals("/metrics")) {
            handleMetrics(ctx);
            return;
        }

        // Pass non-API requests to WebSocket handler
        req.retain();
        ctx.fireChannelRead(req);
    }

    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest req, String uri) {
        if (!HttpMethod.GET.equals(req.method())) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method supported");
            return;
        }

        if (uri.startsWith("/api/orderbook")) {
            handleOrderBook(ctx, uri);
        } else if (uri.startsWith("/api/candles")) {
            handleCandles(ctx, uri);
        } else if (uri.startsWith("/api/trades")) {
            handleTrades(ctx, uri);
        } else {
            sendError(ctx, HttpResponseStatus.NOT_FOUND, "Unknown endpoint: " + uri);
        }
    }

    private void handleOrderBook(ChannelHandlerContext ctx, String uri) {
        int marketId = parseQueryParam(uri, "marketId", 1); // Default to market 1 (BTC-USD)
        var book = stateManager.getOrderBook(marketId);
        if (book == null || !book.hasData()) {
            // Return empty book structure for this market
            String emptyJson = "{\"type\":\"BOOK_SNAPSHOT\",\"marketId\":" + marketId +
                ",\"market\":\"UNKNOWN\",\"bids\":[],\"asks\":[],\"timestamp\":" +
                System.currentTimeMillis() + "}";
            sendJson(ctx, emptyJson);
            return;
        }
        String json = book.toJson();
        sendJson(ctx, json);
    }

    private void handleCandles(ChannelHandlerContext ctx, String uri) {
        int marketId = parseQueryParam(uri, "marketId", 1);
        String interval = parseQueryString(uri, "interval", "1m");
        int limit = parseQueryParam(uri, "limit", 200);
        limit = Math.min(limit, 500); // Cap at ring buffer size

        String json = stateManager.buildCandleHistoryJson(marketId, interval, limit);
        sendJson(ctx, json);
    }

    private void handleTrades(ChannelHandlerContext ctx, String uri) {
        int limit = parseQueryParam(uri, "limit", 50);
        limit = Math.min(limit, 500); // Cap at max buffer size

        String json = stateManager.getTrades().toJson(limit);
        sendJson(ctx, json);
    }

    /** match#33: hand-rendered Prometheus text — reads only volatile/atomic counters. */
    private void handleMetrics(ChannelHandlerContext ctx) {
        StringBuilder sb = new StringBuilder(2048);
        appendSeries(sb, "gateway_ws_clients", "gauge", "Connected market-data WebSocket clients",
                webSocket != null && webSocket.getChannels() != null ? webSocket.getChannels().size() : 0);
        appendSeries(sb, "gateway_ws_dropped_frames_total", "counter", "Frames conflated/dropped for slow WS clients",
                com.match.infrastructure.websocket.MarketDataWebSocket.DROPPED_FRAMES.get());
        appendSeries(sb, "gateway_ws_resyncs_total", "counter", "State resyncs sent after frame drops",
                com.match.infrastructure.websocket.MarketDataWebSocket.RESYNCS_SENT.get());
        appendSeries(sb, "gateway_ws_slow_disconnects_total", "counter", "Slow WS clients disconnected",
                com.match.infrastructure.websocket.MarketDataWebSocket.SLOW_CLIENTS_DISCONNECTED.get());
        appendSeries(sb, "gateway_stale_deltas_total", "counter", "Out-of-order book deltas dropped",
                stateManager != null ? stateManager.getStaleDeltasDropped() : 0);
        if (aeronGateway != null) {
            appendSeries(sb, "gateway_cluster_connected", "gauge", "Cluster egress session up (1) / down (0)",
                    aeronGateway.isConnected() ? 1 : 0);
            appendSeries(sb, "gateway_egress_messages_total", "counter", "Cluster egress messages relayed",
                    aeronGateway.getEgressMessageCount());
            appendSeries(sb, "gateway_egress_age_ms", "gauge", "Milliseconds since the last egress message",
                    aeronGateway.getEgressAgeMs());
            appendSeries(sb, "gateway_aeron_errors_total", "counter", "Aeron error handler invocations",
                    aeronGateway.getAeronErrorCount());
        }

        byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                io.netty.buffer.Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    private static void appendSeries(StringBuilder sb, String name, String type, String help, long value) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        sb.append(name).append(' ').append(value).append('\n');
    }

    private void handleHealth(ChannelHandlerContext ctx, FullHttpRequest req) {
        // Check if any market has order book data
        boolean hasAnyOrderBook = false;
        for (int marketId = 1; marketId <= 5; marketId++) {
            var book = stateManager.getOrderBook(marketId);
            if (book != null && book.hasData()) {
                hasAnyOrderBook = true;
                break;
            }
        }
        String json = "{\"status\":\"ok\",\"orderBook\":" + hasAnyOrderBook +
                      ",\"trades\":" + stateManager.getTrades().hasData() + "}";
        sendJson(ctx, json);
    }

    private String parseQueryString(String uri, String param, String defaultValue) {
        int idx = uri.indexOf(param + "=");
        if (idx < 0) {
            return defaultValue;
        }
        int start = idx + param.length() + 1;
        int end = uri.indexOf('&', start);
        if (end < 0) {
            end = uri.length();
        }
        return uri.substring(start, end);
    }

    private int parseQueryParam(String uri, String param, int defaultValue) {
        int idx = uri.indexOf(param + "=");
        if (idx < 0) {
            return defaultValue;
        }
        int start = idx + param.length() + 1;
        int end = uri.indexOf('&', start);
        if (end < 0) {
            end = uri.length();
        }
        try {
            return Integer.parseInt(uri.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void sendJson(ChannelHandlerContext ctx, String json) {
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        String json = "{\"error\":\"" + message + "\"}";
        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.copiedBuffer(json, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, cause.getMessage());
    }
}
