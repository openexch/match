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

    public GatewayHttpHandler(GatewayStateManager stateManager) {
        this.stateManager = stateManager;
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

    private void handleTrades(ChannelHandlerContext ctx, String uri) {
        int limit = parseQueryParam(uri, "limit", 50);
        limit = Math.min(limit, 500); // Cap at max buffer size

        String json = stateManager.getTrades().toJson(limit);
        sendJson(ctx, json);
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
