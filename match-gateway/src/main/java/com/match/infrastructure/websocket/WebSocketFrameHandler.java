package com.match.infrastructure.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.match.infrastructure.Logger;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;

/**
 * Handles WebSocket frames for subscription management.
 * Supports subscribe/unsubscribe commands from clients.
 */
@ChannelHandler.Sharable
public class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = Logger.getLogger(WebSocketFrameHandler.class);
    private static final Gson gson = new Gson();

    private final SubscriptionManager subscriptionManager;

    public WebSocketFrameHandler(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        subscriptionManager.onConnect(ctx.channel());
        logger.info("WebSocket client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        subscriptionManager.onDisconnect(ctx.channel());
        logger.info("WebSocket client disconnected: " + ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof BinaryWebSocketFrame) {
            handleBinaryFrame(ctx, (BinaryWebSocketFrame) frame);
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof CloseWebSocketFrame) {
            ctx.close();
        }
    }

    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();
        try {
            JsonObject json = gson.fromJson(text, JsonObject.class);
            String action = json.get("action").getAsString();

            switch (action) {
                case "subscribe":
                    int marketId = json.get("marketId").getAsInt();
                    subscriptionManager.subscribe(ctx.channel(), marketId);
                    sendConfirmation(ctx, "subscribed", marketId);
                    logger.info("Client subscribed to market " + marketId);
                    break;

                case "unsubscribe":
                    marketId = json.get("marketId").getAsInt();
                    subscriptionManager.unsubscribe(ctx.channel(), marketId);
                    sendConfirmation(ctx, "unsubscribed", marketId);
                    logger.info("Client unsubscribed from market " + marketId);
                    break;

                case "subscribe-admin":
                    subscriptionManager.subscribeAdmin(ctx.channel());
                    sendAdminConfirmation(ctx, "subscribed");
                    logger.info("Client subscribed to admin updates");
                    break;

                case "unsubscribe-admin":
                    subscriptionManager.unsubscribeAdmin(ctx.channel());
                    sendAdminConfirmation(ctx, "unsubscribed");
                    logger.info("Client unsubscribed from admin updates");
                    break;

                case "ping":
                    sendPong(ctx);
                    break;

                default:
                    sendError(ctx, "Unknown action: " + action);
            }
        } catch (Exception e) {
            sendError(ctx, "Invalid message format: " + e.getMessage());
        }
    }

    private void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        // Reserved for SBE-encoded messages from low-latency clients
        // Can be extended to support binary subscription commands
    }

    private void sendConfirmation(ChannelHandlerContext ctx, String status, int marketId) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "SUBSCRIPTION_CONFIRMED");
        response.addProperty("status", status);
        response.addProperty("marketId", marketId);
        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }

    private void sendAdminConfirmation(ChannelHandlerContext ctx, String status) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "ADMIN_SUBSCRIPTION_CONFIRMED");
        response.addProperty("status", status);
        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }

    private void sendPong(ChannelHandlerContext ctx) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "PONG");
        response.addProperty("timestamp", System.currentTimeMillis());
        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "ERROR");
        response.addProperty("message", message);
        ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(response)));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("WebSocket error: " + cause.getMessage());
        ctx.close();
    }
}
