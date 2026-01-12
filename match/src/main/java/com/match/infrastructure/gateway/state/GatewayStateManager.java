package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.match.infrastructure.Logger;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.generated.BookSnapshotDecoder;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;
import com.match.infrastructure.generated.TradesBatchDecoder;
import com.match.infrastructure.websocket.MarketDataWebSocket;

import static com.match.domain.FixedPoint.SCALE_FACTOR;

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
    public void onBookSnapshot(BookSnapshotDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();
            long bidVersion = decoder.bidVersion();
            long askVersion = decoder.askVersion();

            // Convert bids to JSON array (convert fixed-point to decimal)
            JsonArray bidsArray = new JsonArray();
            for (BookSnapshotDecoder.BidsDecoder bid : decoder.bids()) {
                JsonObject level = new JsonObject();
                level.addProperty("price", (double) bid.price() / SCALE_FACTOR);
                level.addProperty("quantity", (double) bid.quantity() / SCALE_FACTOR);
                level.addProperty("orderCount", bid.orderCount());
                bidsArray.add(level);
            }

            // Convert asks to JSON array (convert fixed-point to decimal)
            JsonArray asksArray = new JsonArray();
            for (BookSnapshotDecoder.AsksDecoder ask : decoder.asks()) {
                JsonObject level = new JsonObject();
                level.addProperty("price", (double) ask.price() / SCALE_FACTOR);
                level.addProperty("quantity", (double) ask.quantity() / SCALE_FACTOR);
                level.addProperty("orderCount", ask.orderCount());
                asksArray.add(level);
            }

            // Update local state
            String marketName = getMarketName(marketId);
            long version = Math.max(bidVersion, askVersion);
            orderBook.update(marketId, marketName, bidsArray, asksArray, bidVersion, askVersion, version, timestamp);

            // Build JSON and broadcast to WebSocket
            if (webSocket != null) {
                String json = buildBookSnapshotJson(marketId, marketName, timestamp, bidVersion, askVersion, bidsArray, asksArray);
                webSocket.broadcastMarketData(json);
            }
        } catch (Exception e) {
            logger.error("Error processing BOOK_SNAPSHOT: " + e.getMessage());
        }
    }

    @Override
    public void onTradesBatch(TradesBatchDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();
            String marketName = getMarketName(marketId);

            // Convert trades to JSON array (convert fixed-point to decimal)
            JsonArray tradesArray = new JsonArray();
            for (TradesBatchDecoder.TradesDecoder trade : decoder.trades()) {
                JsonObject t = new JsonObject();
                t.addProperty("price", (double) trade.price() / SCALE_FACTOR);
                t.addProperty("quantity", (double) trade.quantity() / SCALE_FACTOR);
                t.addProperty("tradeCount", trade.tradeCount());
                t.addProperty("timestamp", trade.timestamp());
                tradesArray.add(t);
            }

            // Update local state
            trades.addBatch(marketId, marketName, tradesArray);

            // Build JSON and broadcast to WebSocket
            if (webSocket != null && tradesArray.size() > 0) {
                String json = buildTradesBatchJson(marketId, marketName, timestamp, tradesArray);
                webSocket.broadcastMarketData(json);
            }
        } catch (Exception e) {
            logger.error("Error processing TRADES_BATCH: " + e.getMessage());
        }
    }

    @Override
    public void onOrderStatusBatch(OrderStatusBatchDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();

            // Convert orders to JSON array (convert fixed-point to decimal)
            // NOTE: Use field names expected by OpenOrderTracker (remainingQuantity, filledQuantity)
            JsonArray ordersArray = new JsonArray();
            for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                JsonObject o = new JsonObject();
                o.addProperty("orderId", order.orderId());
                o.addProperty("userId", order.userId());
                o.addProperty("status", order.status().name());
                o.addProperty("price", (double) order.price() / SCALE_FACTOR);
                o.addProperty("remainingQuantity", (double) order.remainingQty() / SCALE_FACTOR);
                o.addProperty("filledQuantity", (double) order.filledQty() / SCALE_FACTOR);
                o.addProperty("side", order.side().name());
                o.addProperty("timestamp", order.timestamp());
                ordersArray.add(o);
            }

            // Update local state
            openOrders.onOrderStatusBatch(ordersArray);

            // Build JSON and broadcast to WebSocket
            // Note: ordersArray already has the correct field names from above
            if (webSocket != null && ordersArray.size() > 0) {
                String json = buildOrderStatusBatchJson(marketId, timestamp, ordersArray);
                webSocket.broadcastMarketData(json);
            }
        } catch (Exception e) {
            logger.error("Error processing ORDER_STATUS_BATCH: " + e.getMessage());
        }
    }

    @Override
    public void onNewLeader(int leaderMemberId, long leadershipTermId) {
        // Leader change - could clear state if needed, but current approach is fine
        // State will be updated from next egress messages
        logger.info("New leader detected: member=" + leaderMemberId + ", term=" + leadershipTermId);
    }

    // Market ID to name mapping
    private String getMarketName(int marketId) {
        // Market 1 = BTC-USD (from Engine.MARKET_BTC_USD)
        return marketId == 1 ? "BTC-USD" : "UNKNOWN";
    }

    // Build JSON for WebSocket broadcast - book snapshot
    private String buildBookSnapshotJson(int marketId, String market, long timestamp,
            long bidVersion, long askVersion, JsonArray bids, JsonArray asks) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "BOOK_SNAPSHOT");
        obj.addProperty("marketId", marketId);
        obj.addProperty("market", market);
        obj.addProperty("timestamp", timestamp);
        obj.addProperty("bidVersion", bidVersion);
        obj.addProperty("askVersion", askVersion);
        obj.add("bids", bids);
        obj.add("asks", asks);
        return obj.toString();
    }

    // Build JSON for WebSocket broadcast - trades batch
    private String buildTradesBatchJson(int marketId, String market, long timestamp, JsonArray trades) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "TRADES_BATCH");
        obj.addProperty("marketId", marketId);
        obj.addProperty("market", market);
        obj.addProperty("timestamp", timestamp);
        obj.add("trades", trades);
        return obj.toString();
    }

    // Build JSON for WebSocket broadcast - order status batch
    private String buildOrderStatusBatchJson(int marketId, long timestamp, JsonArray orders) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "ORDER_STATUS_BATCH");
        obj.addProperty("marketId", marketId);
        obj.addProperty("timestamp", timestamp);
        obj.add("orders", orders);
        return obj.toString();
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
