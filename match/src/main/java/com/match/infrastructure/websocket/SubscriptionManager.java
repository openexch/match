package com.match.infrastructure.websocket;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket client subscriptions to markets and admin operations.
 * Thread-safe for concurrent access from Netty I/O threads and publisher threads.
 */
public class SubscriptionManager {

    // Market ID -> Subscribed channels
    private final Int2ObjectHashMap<ChannelGroup> marketSubscriptions;

    // Channel -> Subscribed market IDs (for cleanup on disconnect)
    private final ConcurrentHashMap<Channel, Set<Integer>> channelMarkets;

    // All connected channels
    private final ChannelGroup allChannels;

    // Admin operation subscribers (for rolling update progress, etc.)
    private final ChannelGroup adminSubscribers;

    public SubscriptionManager() {
        this.marketSubscriptions = new Int2ObjectHashMap<>();
        this.channelMarkets = new ConcurrentHashMap<>();
        this.allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        this.adminSubscribers = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }

    /**
     * Register a new channel connection.
     */
    public void onConnect(Channel channel) {
        allChannels.add(channel);
        channelMarkets.put(channel, ConcurrentHashMap.newKeySet());
    }

    /**
     * Handle channel disconnect - clean up all subscriptions.
     */
    public synchronized void onDisconnect(Channel channel) {
        Set<Integer> markets = channelMarkets.remove(channel);
        if (markets != null) {
            for (int marketId : markets) {
                ChannelGroup group = marketSubscriptions.get(marketId);
                if (group != null) {
                    group.remove(channel);
                }
            }
        }
        allChannels.remove(channel);
        adminSubscribers.remove(channel);
    }

    /**
     * Subscribe channel to a market.
     */
    public synchronized void subscribe(Channel channel, int marketId) {
        ChannelGroup group = marketSubscriptions.get(marketId);
        if (group == null) {
            group = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
            marketSubscriptions.put(marketId, group);
        }
        group.add(channel);

        Set<Integer> markets = channelMarkets.get(channel);
        if (markets != null) {
            markets.add(marketId);
        }
    }

    /**
     * Unsubscribe channel from a market.
     */
    public synchronized void unsubscribe(Channel channel, int marketId) {
        ChannelGroup group = marketSubscriptions.get(marketId);
        if (group != null) {
            group.remove(channel);
        }

        Set<Integer> markets = channelMarkets.get(channel);
        if (markets != null) {
            markets.remove(marketId);
        }
    }

    /**
     * Get all channels subscribed to a market.
     * Called by publisher thread.
     */
    public synchronized ChannelGroup getSubscribers(int marketId) {
        return marketSubscriptions.get(marketId);
    }

    /**
     * Get total subscriber count for a market.
     */
    public int getSubscriberCount(int marketId) {
        ChannelGroup group = marketSubscriptions.get(marketId);
        return group != null ? group.size() : 0;
    }

    /**
     * Get total connected clients.
     */
    public int getTotalConnections() {
        return allChannels.size();
    }

    /**
     * Get all connected channels.
     */
    public ChannelGroup getAllChannels() {
        return allChannels;
    }

    // ==================== Admin Subscription Methods ====================

    /**
     * Subscribe channel to admin operation updates.
     */
    public void subscribeAdmin(Channel channel) {
        adminSubscribers.add(channel);
    }

    /**
     * Unsubscribe channel from admin operation updates.
     */
    public void unsubscribeAdmin(Channel channel) {
        adminSubscribers.remove(channel);
    }

    /**
     * Broadcast admin progress to all subscribed clients.
     */
    public void broadcastAdminProgress(String jsonMessage) {
        if (!adminSubscribers.isEmpty()) {
            adminSubscribers.writeAndFlush(new TextWebSocketFrame(jsonMessage));
        }
    }

    /**
     * Get admin subscriber count.
     */
    public int getAdminSubscriberCount() {
        return adminSubscribers.size();
    }
}
