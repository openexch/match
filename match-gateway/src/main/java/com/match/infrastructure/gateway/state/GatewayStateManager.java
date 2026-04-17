package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.match.infrastructure.Logger;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.generated.BookDeltaDecoder;
import com.match.infrastructure.generated.BookSnapshotDecoder;
import com.match.domain.MarketInfo;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;
import com.match.infrastructure.generated.TradesBatchDecoder;
import com.match.infrastructure.websocket.MarketDataWebSocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.match.domain.FixedPoint.SCALE_FACTOR;

/**
 * Centralized state manager for Market Gateway.
 * Coordinates order book, trades, and orders state.
 * Implements EgressMessageListener to receive cluster messages.
 */
public class GatewayStateManager implements AeronGateway.EgressMessageListener {
    private static final Logger logger = Logger.getLogger(GatewayStateManager.class);

    // Per-market order book cache
    private final ConcurrentHashMap<Integer, GatewayOrderBook> orderBooksByMarket = new ConcurrentHashMap<>();
    private final TradeRingBuffer trades = new TradeRingBuffer();

    // Per-market ticker stats (accumulated from trades)
    private final ConcurrentHashMap<Integer, TickerStats> tickerStatsByMarket = new ConcurrentHashMap<>();

    // Candle aggregation
    private final CandleProvider candleProvider = new InMemoryCandleProvider();

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

            // Update local state (per-market order book)
            String marketName = getMarketName(marketId);
            long version = Math.max(bidVersion, askVersion);
            GatewayOrderBook orderBook = getOrCreateOrderBook(marketId);
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
    public void onBookDelta(BookDeltaDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();
            long bidVersion = decoder.bidVersion();
            long askVersion = decoder.askVersion();
            String marketName = getMarketName(marketId);

            // Get the order book for this market
            GatewayOrderBook orderBook = getOrCreateOrderBook(marketId);

            // Convert changes to JSON array
            JsonArray changesArray = new JsonArray();
            for (BookDeltaDecoder.ChangesDecoder change : decoder.changes()) {
                JsonObject c = new JsonObject();
                c.addProperty("price", (double) change.price() / SCALE_FACTOR);
                c.addProperty("quantity", (double) change.quantity() / SCALE_FACTOR);
                c.addProperty("orderCount", change.orderCount());
                c.addProperty("side", change.side().name());
                c.addProperty("updateType", change.updateType().name());
                changesArray.add(c);

                // Apply delta to this market's order book
                orderBook.applyDelta(
                    change.side().name(),
                    (double) change.price() / SCALE_FACTOR,
                    (double) change.quantity() / SCALE_FACTOR,
                    change.orderCount(),
                    change.updateType().name()
                );
            }

            // Update versions on this market's order book
            orderBook.updateVersions(marketId, marketName, bidVersion, askVersion, timestamp);

            // Build JSON and broadcast to WebSocket
            if (webSocket != null && !changesArray.isEmpty()) {
                String json = buildBookDeltaJson(marketId, marketName, timestamp, bidVersion, askVersion, changesArray);
                webSocket.broadcastMarketData(json);
            }
        } catch (Exception e) {
            logger.error("Error processing BOOK_DELTA: " + e.getMessage());
        }
    }

    @Override
    public void onTradesBatch(TradesBatchDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();
            String marketName = getMarketName(marketId);

            // Get or create ticker stats for this market
            TickerStats tickerStats = getOrCreateTickerStats(marketId, marketName);

            // Convert trades to JSON array (convert fixed-point to decimal)
            JsonArray tradesArray = new JsonArray();
            for (TradesBatchDecoder.TradesDecoder trade : decoder.trades()) {
                double price = (double) trade.price() / SCALE_FACTOR;
                double quantity = (double) trade.quantity() / SCALE_FACTOR;

                // Update ticker stats from each trade
                tickerStats.updateFromTrade(price, quantity);

                JsonObject t = new JsonObject();
                t.addProperty("price", price);
                t.addProperty("quantity", quantity);
                t.addProperty("tradeCount", trade.tradeCount());
                t.addProperty("timestamp", trade.timestamp());
                tradesArray.add(t);
            }

            // Update local state
            trades.addBatch(marketId, marketName, tradesArray);

            // Update candles from each trade
            for (int i = 0; i < tradesArray.size(); i++) {
                JsonObject t = tradesArray.get(i).getAsJsonObject();
                candleProvider.onTrade(
                    marketId,
                    t.get("price").getAsDouble(),
                    t.get("quantity").getAsDouble(),
                    t.get("timestamp").getAsLong()
                );
            }

            // Build JSON and broadcast to WebSocket
            if (webSocket != null && !tradesArray.isEmpty()) {
                String json = buildTradesBatchJson(marketId, marketName, timestamp, tradesArray);
                webSocket.broadcastMarketData(json);

                // Also broadcast updated ticker stats
                String tickerJson = tickerStats.toJson();
                webSocket.broadcastMarketData(tickerJson);

                // Broadcast current 1m candle update
                Candle currentCandle = candleProvider.getCurrentCandle(marketId, "1m");
                if (currentCandle != null) {
                    String candleJson = buildCandleUpdateJson(marketId, marketName, currentCandle);
                    webSocket.broadcastMarketData(candleJson);
                }
            }
        } catch (Exception e) {
            logger.error("Error processing TRADES_BATCH: " + e.getMessage());
        }
    }

    /**
     * Get or create ticker stats for a market.
     */
    private TickerStats getOrCreateTickerStats(int marketId, String marketName) {
        return tickerStatsByMarket.computeIfAbsent(marketId, k -> new TickerStats(marketId, marketName));
    }

    /**
     * Get or create order book for a market.
     */
    private GatewayOrderBook getOrCreateOrderBook(int marketId) {
        return orderBooksByMarket.computeIfAbsent(marketId, k -> new GatewayOrderBook());
    }

    @Override
    public void onOrderStatusBatch(OrderStatusBatchDecoder decoder) {
        try {
            int marketId = decoder.marketId();
            long timestamp = decoder.timestamp();

            JsonArray ordersArray = new JsonArray();
            for (OrderStatusBatchDecoder.OrdersDecoder order : decoder.orders()) {
                JsonObject o = new JsonObject();
                o.addProperty("orderId", order.orderId());
                o.addProperty("omsOrderId", order.omsOrderId());
                o.addProperty("userId", order.userId());
                o.addProperty("status", order.status().name());
                o.addProperty("price", (double) order.price() / SCALE_FACTOR);
                o.addProperty("remainingQuantity", (double) order.remainingQty() / SCALE_FACTOR);
                o.addProperty("filledQuantity", (double) order.filledQty() / SCALE_FACTOR);
                o.addProperty("side", order.side() == OrderSide.BID ? "BID" : "ASK");
                o.addProperty("timestamp", order.timestamp());
                ordersArray.add(o);
            }

            if (webSocket != null && ordersArray.size() > 0) {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "ORDER_STATUS_BATCH");
                msg.addProperty("marketId", marketId);
                msg.addProperty("timestamp", timestamp);
                msg.add("orders", ordersArray);
                webSocket.broadcastMarketData(msg.toString());
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

    private String getMarketName(int marketId) {
        MarketInfo info = MarketInfo.fromId(marketId);
        return info != null ? info.symbol() : "UNKNOWN";
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

    // Build JSON for WebSocket broadcast - book delta
    private String buildBookDeltaJson(int marketId, String market, long timestamp,
            long bidVersion, long askVersion, JsonArray changes) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "BOOK_DELTA");
        obj.addProperty("marketId", marketId);
        obj.addProperty("market", market);
        obj.addProperty("timestamp", timestamp);
        obj.addProperty("bidVersion", bidVersion);
        obj.addProperty("askVersion", askVersion);
        obj.add("changes", changes);
        return obj.toString();
    }

    // Build JSON for WebSocket broadcast - candle update
    private String buildCandleUpdateJson(int marketId, String market, Candle candle) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CANDLE_UPDATE");
        obj.addProperty("marketId", marketId);
        obj.addProperty("market", market);
        obj.addProperty("interval", "1m");
        obj.add("candle", candleToJson(candle));
        return obj.toString();
    }

    /**
     * Build candle history JSON for initial WebSocket state or REST API.
     */
    public String buildCandleHistoryJson(int marketId, String interval, int limit) {
        String marketName = getMarketName(marketId);
        java.util.List<Candle> candles = candleProvider.getCandles(marketId, interval, limit);

        JsonObject obj = new JsonObject();
        obj.addProperty("type", "CANDLE_HISTORY");
        obj.addProperty("marketId", marketId);
        obj.addProperty("market", marketName);
        obj.addProperty("interval", interval);

        JsonArray arr = new JsonArray();
        for (Candle c : candles) {
            arr.add(candleToJson(c));
        }
        obj.add("candles", arr);
        return obj.toString();
    }

    private JsonObject candleToJson(Candle c) {
        JsonObject obj = new JsonObject();
        obj.addProperty("time", c.time);
        obj.addProperty("open", c.open);
        obj.addProperty("high", c.high);
        obj.addProperty("low", c.low);
        obj.addProperty("close", c.close);
        obj.addProperty("volume", c.volume);
        obj.addProperty("tradeCount", c.tradeCount);
        return obj;
    }

    // State accessors for HTTP and WebSocket handlers

    /**
     * Get order book for a specific market.
     * Returns null if no data exists for this market.
     */
    public GatewayOrderBook getOrderBook(int marketId) {
        return orderBooksByMarket.get(marketId);
    }

    public TradeRingBuffer getTrades() {
        return trades;
    }

    /**
     * Get initial state JSON for new WebSocket subscribers.
     * Returns the order book snapshot for the specified market.
     */
    public String getInitialBookSnapshot(int marketId) {
        GatewayOrderBook orderBook = orderBooksByMarket.get(marketId);
        return orderBook != null ? orderBook.toJson() : null;
    }

    /**
     * Get recent trades JSON for new WebSocket subscribers.
     */
    public String getRecentTradesJson(int limit) {
        return trades.toJson(limit);
    }

    /**
     * Get ticker stats for a specific market.
     * Returns null if no trades have been received for this market.
     */
    public String getTickerStats(int marketId) {
        TickerStats stats = tickerStatsByMarket.get(marketId);
        return stats != null ? stats.toJson() : null;
    }

    /**
     * Get the candle provider for REST/WebSocket queries.
     */
    public CandleProvider getCandleProvider() {
        return candleProvider;
    }
}
