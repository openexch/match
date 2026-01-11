package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.match.infrastructure.Logger;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.websocket.MarketDataWebSocket;

/**
 * Centralized state manager for Market Gateway.
 * Coordinates order book, trades, and orders state.
 * Implements EgressMessageListener to receive cluster messages.
 */
public class GatewayStateManager implements AeronGateway.EgressMessageListener {
    private static final Logger logger = Logger.getLogger(GatewayStateManager.class);

    private final GatewayOrderBook orderBook = new GatewayOrderBook();
    private final TradeRingBuffer trades = new TradeRingBuffer();
    private final OpenOrderTracker openOrders = new OpenOrderTracker();

    // Reference to WebSocket for broadcasting to clients
    private volatile MarketDataWebSocket webSocket;

    public void setWebSocket(MarketDataWebSocket webSocket) {
        this.webSocket = webSocket;
    }

    @Override
    public void onMessage(String json) {
        try {
            // Route message to appropriate handler based on type
            if (json.contains("\"type\":\"BOOK_SNAPSHOT\"")) {
                handleBookSnapshot(json);
            } else if (json.contains("\"type\":\"TRADES_BATCH\"")) {
                handleTradesBatch(json);
            } else if (json.contains("\"type\":\"ORDER_STATUS_BATCH\"")) {
                handleOrderStatusBatch(json);
            } else if (json.contains("\"type\":\"ORDER_STATUS\"")) {
                handleOrderStatus(json);
            }

            // Always broadcast to WebSocket clients (pass-through)
            if (webSocket != null) {
                webSocket.broadcastMarketData(json);
            }
        } catch (Exception e) {
            logger.error("Error processing egress message: " + e.getMessage());
        }
    }

    @Override
    public void onNewLeader(int leaderMemberId, long leadershipTermId) {
        // Leader change - could clear state if needed, but current approach is fine
        // State will be updated from next egress messages
        logger.info("New leader detected: member=" + leaderMemberId + ", term=" + leadershipTermId);
    }

    private void handleBookSnapshot(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int marketId = obj.get("marketId").getAsInt();
            String marketName = obj.has("market") ? obj.get("market").getAsString() : "UNKNOWN";
            JsonArray bids = obj.getAsJsonArray("bids");
            JsonArray asks = obj.getAsJsonArray("asks");
            long bidVersion = obj.has("bidVersion") ? obj.get("bidVersion").getAsLong() : 0;
            long askVersion = obj.has("askVersion") ? obj.get("askVersion").getAsLong() : 0;
            long version = obj.has("version") ? obj.get("version").getAsLong() : Math.max(bidVersion, askVersion);
            long timestamp = obj.has("timestamp") ? obj.get("timestamp").getAsLong() : System.currentTimeMillis();

            orderBook.update(marketId, marketName, bids, asks, bidVersion, askVersion, version, timestamp);
        } catch (Exception e) {
            logger.error("Error parsing BOOK_SNAPSHOT: " + e.getMessage());
        }
    }

    private void handleTradesBatch(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            int marketId = obj.get("marketId").getAsInt();
            String marketName = obj.has("market") ? obj.get("market").getAsString() : "UNKNOWN";
            JsonArray tradesArray = obj.getAsJsonArray("trades");

            trades.addBatch(marketId, marketName, tradesArray);
        } catch (Exception e) {
            logger.error("Error parsing TRADES_BATCH: " + e.getMessage());
        }
    }

    private void handleOrderStatusBatch(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonArray orders = obj.getAsJsonArray("orders");

            openOrders.onOrderStatusBatch(orders);
        } catch (Exception e) {
            logger.error("Error parsing ORDER_STATUS_BATCH: " + e.getMessage());
        }
    }

    private void handleOrderStatus(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            openOrders.onOrderStatus(obj);
        } catch (Exception e) {
            logger.error("Error parsing ORDER_STATUS: " + e.getMessage());
        }
    }

    // State accessors for HTTP and WebSocket handlers
    public GatewayOrderBook getOrderBook() {
        return orderBook;
    }

    public TradeRingBuffer getTrades() {
        return trades;
    }

    public OpenOrderTracker getOpenOrders() {
        return openOrders;
    }

    /**
     * Get initial state JSON for new WebSocket subscribers.
     * Returns the current order book snapshot.
     */
    public String getInitialBookSnapshot() {
        return orderBook.toJson();
    }

    /**
     * Get recent trades JSON for new WebSocket subscribers.
     */
    public String getRecentTradesJson(int limit) {
        return trades.toJson(limit);
    }
}
