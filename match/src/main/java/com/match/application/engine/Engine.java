package com.match.application.engine;

import com.match.application.orderbook.DirectMatchingEngine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.Logger;
import org.agrona.collections.Int2ObjectHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ultra-low latency matching engine.
 * Uses direct array indexing for O(1) operations and ZERO allocations in hot path.
 *
 * Target: Sub-microsecond stable latency.
 */
public class Engine {
    private static final Logger logger = Logger.getLogger(Engine.class);

    // Direct matching engines per market (zero allocation matching)
    private final Int2ObjectHashMap<DirectMatchingEngine> engines;

    // Atomic order ID generator
    private final AtomicLong orderIdGenerator = new AtomicLong(1);

    // Market ID constants
    public static final int MARKET_BTC_USD = 1;

    // Command type constants (int for zero-allocation dispatch)
    public static final int CMD_CREATE = 0;
    public static final int CMD_CANCEL = 1;
    public static final int CMD_UPDATE = 2;

    // BTC-USD price range configuration (fixed-point, 8 decimals)
    // Range: $90,000 to $130,000 with $1 tick size = 40,000 price levels
    private static final long BTC_BASE_PRICE = FixedPoint.fromDouble(90_000.0);
    private static final long BTC_MAX_PRICE = FixedPoint.fromDouble(130_000.0);
    private static final long BTC_TICK_SIZE = FixedPoint.fromDouble(1.0);  // $1 tick for 40K levels

    public Engine() {
        this.engines = new Int2ObjectHashMap<>();

        // Create ultra-low latency matching engine for BTC-USD
        DirectMatchingEngine btcEngine = new DirectMatchingEngine(
            BTC_BASE_PRICE,
            BTC_MAX_PRICE,
            BTC_TICK_SIZE
        );

        engines.put(MARKET_BTC_USD, btcEngine);

        logger.info("Engine started with DirectMatchingEngine for BTC-USD.");
        logger.info("Price range: $90,000 - $130,000, tick size: $1.00");
    }

    /**
     * Accept an order command with cluster timestamp.
     * ZERO allocations in hot path.
     *
     * @param marketId    the market ID
     * @param commandType the command type (CMD_CREATE, CMD_CANCEL, CMD_UPDATE)
     * @param command     the command object
     * @param timestamp   the cluster timestamp in nanoseconds
     */
    public void acceptOrder(int marketId, int commandType, Object command, long timestamp) {
        DirectMatchingEngine engine = engines.get(marketId);
        if (engine == null) {
            return; // Silently ignore unknown markets in hot path
        }

        // Direct int switch - no string comparison
        switch (commandType) {
            case CMD_CREATE:
                processCreate(engine, (CreateOrderCommand) command);
                break;
            case CMD_CANCEL:
                processCancel(engine, (CancelOrderCommand) command);
                break;
            // Update not implemented for direct engine (would need order lookup)
        }
    }

    /**
     * Process create order - ZERO allocations
     */
    private void processCreate(DirectMatchingEngine engine, CreateOrderCommand cmd) {
        long orderId = orderIdGenerator.getAndIncrement();
        long userId = cmd.getUserId();
        long price = cmd.getPrice();
        long quantity = cmd.getQuantity();
        long totalPrice = cmd.getTotalPrice();
        OrderSide side = cmd.getOrderSide();
        OrderType type = cmd.getOrderType();

        boolean isBuy = (side == OrderSide.BID);

        if (type == OrderType.MARKET) {
            // Market order
            if (isBuy) {
                engine.processMarketOrder(orderId, userId, true, 0, totalPrice);
            } else {
                engine.processMarketOrder(orderId, userId, false, quantity, 0);
            }
        } else if (type == OrderType.LIMIT) {
            // Limit order
            engine.processLimitOrder(orderId, userId, isBuy, price, quantity);
        } else if (type == OrderType.LIMIT_MAKER) {
            // Limit maker - only add if won't match immediately
            long bestOpposite = isBuy ? engine.getBestAsk() : engine.getBestBid();
            boolean wouldMatch = isBuy
                ? (!engine.isAskEmpty() && price >= bestOpposite)
                : (!engine.isBidEmpty() && price <= bestOpposite);

            if (!wouldMatch) {
                engine.addOrderNoMatch(orderId, userId, isBuy, price, quantity);
            }
            // If would match, reject silently (maker-only)
        }
    }

    /**
     * Process cancel order - O(1)
     */
    private void processCancel(DirectMatchingEngine engine, CancelOrderCommand cmd) {
        // Try both sides since we don't store order side
        // Could optimize by including side in cancel command
        if (!engine.cancelOrder(cmd.getOrderId(), true)) {
            engine.cancelOrder(cmd.getOrderId(), false);
        }
    }

    /**
     * Get matching engine for a market
     */
    public DirectMatchingEngine getEngine(int marketId) {
        return engines.get(marketId);
    }

    public void close() {
        // No resources to close
    }

    // ==================== Snapshot Support ====================

    /**
     * Get current order ID for snapshot
     */
    public long getOrderIdGenerator() {
        return orderIdGenerator.get();
    }

    /**
     * Set order ID generator (for snapshot restore)
     */
    public void setOrderIdGenerator(long value) {
        orderIdGenerator.set(value);
    }

    /**
     * Get all engines for snapshot
     */
    public Int2ObjectHashMap<DirectMatchingEngine> getEngines() {
        return engines;
    }
}
