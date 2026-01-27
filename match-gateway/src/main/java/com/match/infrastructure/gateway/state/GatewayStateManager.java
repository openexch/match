package com.match.infrastructure.gateway.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.match.infrastructure.Logger;
import com.match.infrastructure.gateway.AeronGateway;
import com.match.infrastructure.generated.BookDeltaDecoder;
import com.match.infrastructure.generated.BookSnapshotDecoder;
import com.match.infrastructure.generated.BookUpdateType;
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
            if (webSocket != null && changesArray.size() > 0) {
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

            // Build JSON and broadcast to WebSocket
            if (webSocket != null && tradesArray.size() > 0) {
                String json = buildTradesBatchJson(marketId, marketName, timestamp, tradesArray);
                webSocket.broadcastMarketData(json);

                // Also broadcast updated ticker stats
                String tickerJson = tickerStats.toJson();
                webSocket.broadcastMarketData(tickerJson);
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
        // Order status tracking disabled - not broadcasting to WebSocket
    }

    @Override
    public void onNewLeader(int leaderMemberId, long leadershipTermId) {
        // Leader change - could clear state if needed, but current approach is fine
        // State will be updated from next egress messages
        logger.info("New leader detected: member=" + leaderMemberId + ", term=" + leadershipTermId);
    }

    // Market ID to name mapping
    private static final Map<Integer, String> MARKET_NAMES = Map.of(
        1, "BTC-USD",
        2, "ETH-USD",
        3, "SOL-USD",
        4, "XRP-USD",
        5, "DOGE-USD"
    );

    private String getMarketName(int marketId) {
        return MARKET_NAMES.getOrDefault(marketId, "UNKNOWN");
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
}
