package com.match.infrastructure.websocket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for SubscriptionManager using EmbeddedChannel.
 * Covers connect/disconnect, subscribe/unsubscribe, admin subscriptions, and broadcasting.
 *
 * IMPORTANT: Must use DefaultChannelId.newInstance() for each EmbeddedChannel,
 * because the default EmbeddedChannelId is shared across all instances (all have id "embedded"),
 * causing DefaultChannelGroup to treat them as the same channel.
 */
public class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @Before
    public void setUp() {
        manager = new SubscriptionManager();
    }

    private EmbeddedChannel createAndConnect() {
        // Use unique channel ID so DefaultChannelGroup treats each as distinct
        EmbeddedChannel channel = new EmbeddedChannel(
            DefaultChannelId.newInstance(), new ChannelInboundHandlerAdapter());
        manager.onConnect(channel);
        return channel;
    }

    // ==================== Connect/Disconnect ====================

    @Test
    public void testOnConnect_IncrementsTotalConnections() {
        assertEquals(0, manager.getTotalConnections());
        EmbeddedChannel ch = createAndConnect();
        assertEquals(1, manager.getTotalConnections());
        ch.finish();
    }

    @Test
    public void testOnDisconnect_DecrementsTotalConnections() {
        EmbeddedChannel ch = createAndConnect();
        assertEquals(1, manager.getTotalConnections());
        manager.onDisconnect(ch);
        assertEquals(0, manager.getTotalConnections());
        ch.finish();
    }

    @Test
    public void testMultipleConnections() {
        EmbeddedChannel ch1 = createAndConnect();
        EmbeddedChannel ch2 = createAndConnect();
        EmbeddedChannel ch3 = createAndConnect();
        assertEquals(3, manager.getTotalConnections());
        manager.onDisconnect(ch2);
        assertEquals(2, manager.getTotalConnections());
        ch1.finish();
        ch2.finish();
        ch3.finish();
    }

    // ==================== Subscribe/Unsubscribe ====================

    @Test
    public void testSubscribe_IncreasesSubscriberCount() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribe(ch, 1);
        assertEquals(1, manager.getSubscriberCount(1));
        ch.finish();
    }

    @Test
    public void testSubscribeMultipleChannelsToSameMarket() {
        EmbeddedChannel ch1 = createAndConnect();
        EmbeddedChannel ch2 = createAndConnect();
        manager.subscribe(ch1, 1);
        manager.subscribe(ch2, 1);
        assertEquals(2, manager.getSubscriberCount(1));
        ch1.finish();
        ch2.finish();
    }

    @Test
    public void testSubscribeToMultipleMarkets() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribe(ch, 1);
        manager.subscribe(ch, 2);
        assertEquals(1, manager.getSubscriberCount(1));
        assertEquals(1, manager.getSubscriberCount(2));
        ch.finish();
    }

    @Test
    public void testUnsubscribe_DecreasesSubscriberCount() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribe(ch, 1);
        assertEquals(1, manager.getSubscriberCount(1));
        manager.unsubscribe(ch, 1);
        assertEquals(0, manager.getSubscriberCount(1));
        ch.finish();
    }

    @Test
    public void testUnsubscribeFromNonSubscribedMarket() {
        EmbeddedChannel ch = createAndConnect();
        manager.unsubscribe(ch, 99);
        assertEquals(0, manager.getSubscriberCount(99));
        ch.finish();
    }

    @Test
    public void testGetSubscriberCount_NoSubscriptions() {
        assertEquals(0, manager.getSubscriberCount(1));
        assertEquals(0, manager.getSubscriberCount(999));
    }

    // ==================== Get Subscribers ====================

    @Test
    public void testGetSubscribers_ReturnsNullWhenNone() {
        assertNull(manager.getSubscribers(1));
    }

    @Test
    public void testGetSubscribers_ReturnsGroup() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribe(ch, 1);
        ChannelGroup group = manager.getSubscribers(1);
        assertNotNull(group);
        assertTrue(group.size() > 0);
        ch.finish();
    }

    // ==================== Disconnect Cleanup ====================

    @Test
    public void testDisconnectCleansUpSubscriptions() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribe(ch, 1);
        manager.subscribe(ch, 2);
        assertEquals(1, manager.getSubscriberCount(1));
        assertEquals(1, manager.getSubscriberCount(2));

        manager.onDisconnect(ch);

        assertEquals(0, manager.getSubscriberCount(1));
        assertEquals(0, manager.getSubscriberCount(2));
        assertEquals(0, manager.getTotalConnections());
        ch.finish();
    }

    @Test
    public void testDisconnectCleansUpAdminSubscription() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribeAdmin(ch);
        assertEquals(1, manager.getAdminSubscriberCount());

        manager.onDisconnect(ch);
        assertEquals(0, manager.getAdminSubscriberCount());
        ch.finish();
    }

    @Test
    public void testDisconnectUnknownChannel_NoCrash() {
        EmbeddedChannel ch = new EmbeddedChannel(
            DefaultChannelId.newInstance(), new ChannelInboundHandlerAdapter());
        manager.onDisconnect(ch); // Never connected — should not crash
        ch.finish();
    }

    // ==================== Admin Subscriptions ====================

    @Test
    public void testSubscribeAdmin() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribeAdmin(ch);
        assertEquals(1, manager.getAdminSubscriberCount());
        ch.finish();
    }

    @Test
    public void testUnsubscribeAdmin() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribeAdmin(ch);
        assertEquals(1, manager.getAdminSubscriberCount());
        manager.unsubscribeAdmin(ch);
        assertEquals(0, manager.getAdminSubscriberCount());
        ch.finish();
    }

    @Test
    public void testMultipleAdminSubscribers() {
        EmbeddedChannel ch1 = createAndConnect();
        EmbeddedChannel ch2 = createAndConnect();
        manager.subscribeAdmin(ch1);
        manager.subscribeAdmin(ch2);
        assertEquals(2, manager.getAdminSubscriberCount());
        ch1.finish();
        ch2.finish();
    }

    // ==================== Broadcast Admin ====================

    @Test
    public void testBroadcastAdminProgress_NoSubscribers_NoCrash() {
        manager.broadcastAdminProgress("{\"type\":\"ADMIN_PROGRESS\"}");
        // No crash = pass
    }

    @Test
    public void testBroadcastAdminProgress_WithSubscribers() {
        EmbeddedChannel ch = createAndConnect();
        manager.subscribeAdmin(ch);
        manager.broadcastAdminProgress("{\"type\":\"ADMIN_PROGRESS\",\"step\":1}");

        // Check the channel received the broadcast
        TextWebSocketFrame frame = ch.readOutbound();
        assertNotNull(frame);
        assertTrue(frame.text().contains("ADMIN_PROGRESS"));
        frame.release();
        ch.finish();
    }

    // ==================== GetAllChannels ====================

    @Test
    public void testGetAllChannels_Empty() {
        ChannelGroup all = manager.getAllChannels();
        assertNotNull(all);
        assertEquals(0, all.size());
    }

    @Test
    public void testGetAllChannels_WithConnections() {
        EmbeddedChannel ch1 = createAndConnect();
        EmbeddedChannel ch2 = createAndConnect();
        ChannelGroup all = manager.getAllChannels();
        assertEquals(2, all.size());
        ch1.finish();
        ch2.finish();
    }
}
