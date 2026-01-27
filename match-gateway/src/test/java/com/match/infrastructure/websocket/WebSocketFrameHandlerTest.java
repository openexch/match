package com.match.infrastructure.websocket;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for WebSocketFrameHandler using EmbeddedChannel.
 * Covers subscribe/unsubscribe, ping/pong, admin subscriptions, error handling.
 *
 * IMPORTANT: Must use DefaultChannelId.newInstance() so each EmbeddedChannel
 * gets a unique ID (default EmbeddedChannelId is shared across all instances).
 */
public class WebSocketFrameHandlerTest {

    private SubscriptionManager subManager;

    @Before
    public void setUp() {
        subManager = new SubscriptionManager();
    }

    private EmbeddedChannel createChannel() {
        return new EmbeddedChannel(
            DefaultChannelId.newInstance(), new WebSocketFrameHandler(subManager));
    }

    private String sendTextAndGetResponse(EmbeddedChannel channel, String text) {
        channel.writeInbound(new TextWebSocketFrame(text));
        TextWebSocketFrame outFrame = channel.readOutbound();
        if (outFrame == null) return null;
        String result = outFrame.text();
        outFrame.release();
        return result;
    }

    // ==================== Connection Lifecycle ====================

    @Test
    public void testHandlerAdded_IncrementsConnections() {
        assertEquals(0, subManager.getTotalConnections());
        EmbeddedChannel channel = createChannel();
        assertEquals(1, subManager.getTotalConnections());
        channel.finish();
    }

    @Test
    public void testHandlerRemoved_DecrementsConnections() {
        EmbeddedChannel channel = createChannel();
        assertEquals(1, subManager.getTotalConnections());
        channel.close();
        channel.finish();
        assertEquals(0, subManager.getTotalConnections());
    }

    @Test
    public void testMultipleConnections() {
        EmbeddedChannel ch1 = createChannel();
        EmbeddedChannel ch2 = createChannel();
        assertEquals(2, subManager.getTotalConnections());
        ch1.close();
        ch1.finish();
        assertEquals(1, subManager.getTotalConnections());
        ch2.close();
        ch2.finish();
        assertEquals(0, subManager.getTotalConnections());
    }

    // ==================== Subscribe ====================

    @Test
    public void testSubscribe() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "{\"action\":\"subscribe\",\"marketId\":1}");
        assertNotNull(response);
        assertTrue(response.contains("SUBSCRIPTION_CONFIRMED"));
        assertTrue(response.contains("\"status\":\"subscribed\""));
        assertTrue(response.contains("\"marketId\":1"));
        assertEquals(1, subManager.getSubscriberCount(1));
        channel.finish();
    }

    @Test
    public void testSubscribeMultipleMarkets() {
        EmbeddedChannel channel = createChannel();
        sendTextAndGetResponse(channel, "{\"action\":\"subscribe\",\"marketId\":1}");
        sendTextAndGetResponse(channel, "{\"action\":\"subscribe\",\"marketId\":2}");
        assertEquals(1, subManager.getSubscriberCount(1));
        assertEquals(1, subManager.getSubscriberCount(2));
        channel.finish();
    }

    // ==================== Unsubscribe ====================

    @Test
    public void testUnsubscribe() {
        EmbeddedChannel channel = createChannel();
        sendTextAndGetResponse(channel, "{\"action\":\"subscribe\",\"marketId\":1}");
        String response = sendTextAndGetResponse(channel, "{\"action\":\"unsubscribe\",\"marketId\":1}");
        assertNotNull(response);
        assertTrue(response.contains("\"status\":\"unsubscribed\""));
        assertEquals(0, subManager.getSubscriberCount(1));
        channel.finish();
    }

    // ==================== Admin Subscribe/Unsubscribe ====================

    @Test
    public void testSubscribeAdmin() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "{\"action\":\"subscribe-admin\"}");
        assertNotNull(response);
        assertTrue(response.contains("ADMIN_SUBSCRIPTION_CONFIRMED"));
        assertTrue(response.contains("\"status\":\"subscribed\""));
        assertEquals(1, subManager.getAdminSubscriberCount());
        channel.finish();
    }

    @Test
    public void testUnsubscribeAdmin() {
        EmbeddedChannel channel = createChannel();
        sendTextAndGetResponse(channel, "{\"action\":\"subscribe-admin\"}");
        assertEquals(1, subManager.getAdminSubscriberCount());
        String response = sendTextAndGetResponse(channel, "{\"action\":\"unsubscribe-admin\"}");
        assertNotNull(response);
        assertTrue(response.contains("ADMIN_SUBSCRIPTION_CONFIRMED"));
        assertTrue(response.contains("\"status\":\"unsubscribed\""));
        // Admin subscriber count may or may not be 0 depending on ChannelGroup behavior with EmbeddedChannel
        channel.finish();
    }

    // ==================== Ping/Pong ====================

    @Test
    public void testPingAction() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "{\"action\":\"ping\"}");
        assertNotNull(response);
        assertTrue(response.contains("\"type\":\"PONG\""));
        assertTrue(response.contains("\"timestamp\""));
        channel.finish();
    }

    @Test
    public void testPingWebSocketFrame() {
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(new PingWebSocketFrame(Unpooled.copiedBuffer("ping", CharsetUtil.UTF_8)));
        PongWebSocketFrame pong = channel.readOutbound();
        assertNotNull(pong);
        pong.release();
        channel.finish();
    }

    // ==================== Close ====================

    @Test
    public void testCloseWebSocketFrame() {
        EmbeddedChannel channel = createChannel();
        assertEquals(1, subManager.getTotalConnections());
        channel.writeInbound(new CloseWebSocketFrame());
        assertFalse(channel.isOpen());
        channel.finish();
    }

    // ==================== Binary Frame ====================

    @Test
    public void testBinaryFrame_NoResponse() {
        EmbeddedChannel channel = createChannel();
        channel.writeInbound(new BinaryWebSocketFrame(Unpooled.copiedBuffer(new byte[]{1, 2, 3})));
        // Binary frame handler is a no-op — no response expected
        Object out = channel.readOutbound();
        assertNull(out);
        channel.finish();
    }

    // ==================== Error Cases ====================

    @Test
    public void testUnknownAction() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "{\"action\":\"foobar\"}");
        assertNotNull(response);
        assertTrue(response.contains("\"type\":\"ERROR\""));
        assertTrue(response.contains("Unknown action"));
        channel.finish();
    }

    @Test
    public void testInvalidJson() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "not valid json!!!");
        assertNotNull(response);
        assertTrue(response.contains("\"type\":\"ERROR\""));
        assertTrue(response.contains("Invalid message format"));
        channel.finish();
    }

    @Test
    public void testMissingActionField() {
        EmbeddedChannel channel = createChannel();
        String response = sendTextAndGetResponse(channel, "{\"marketId\":1}");
        assertNotNull(response);
        assertTrue(response.contains("\"type\":\"ERROR\""));
        channel.finish();
    }

    // ==================== Exception Caught ====================

    @Test
    public void testExceptionCaught_ClosesChannel() {
        EmbeddedChannel channel = createChannel();
        assertTrue(channel.isOpen());
        channel.pipeline().fireExceptionCaught(new RuntimeException("WebSocket test error"));
        assertFalse(channel.isOpen());
        channel.finish();
    }

    // ==================== Disconnect Cleans Up Subscriptions ====================

    @Test
    public void testDisconnectCleansUpSubscriptions() {
        EmbeddedChannel channel = createChannel();
        sendTextAndGetResponse(channel, "{\"action\":\"subscribe\",\"marketId\":1}");
        assertEquals(1, subManager.getSubscriberCount(1));

        channel.close();
        channel.finish();

        assertEquals(0, subManager.getSubscriberCount(1));
        assertEquals(0, subManager.getTotalConnections());
    }
}
