package com.match.application.engine;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.application.orderbook.MatchingEngine;
import com.match.application.orderbook.OrderRejectReason;
import com.match.application.publisher.MatchEventSink;
import com.match.application.publisher.OrderStatusType;
import com.match.domain.FixedPoint;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.Logger;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Ultra-low latency matching engine.
 * Uses direct array indexing for O(1) operations and ZERO allocations in hot path.
 *
 * Target: Sub-microsecond stable latency.
 */
public class Engine {
    private static final Logger logger = Logger.getLogger(Engine.class);

    // Matching engines per market (zero allocation matching). Concrete implementation
    // selected by the match.engine.impl / MATCH_ENGINE_IMPL flag (see createMatchingEngine).
    private final Int2ObjectHashMap<MatchingEngine> engines;

    // Atomic order ID generator
    private final AtomicLong orderIdGenerator = new AtomicLong(1);

    // Event sink (optional - set via setEventPublisher). Interface, not the concrete
    // MatchEventPublisher, so matching output can be captured synchronously in tests.
    private MatchEventSink eventPublisher;

    // Maps cluster orderId → omsOrderId for maker order correlation in trade executions
    // When a maker order is on the book, we need to look up its omsOrderId when it matches
    private final Long2LongHashMap orderIdToOmsOrderId = new Long2LongHashMap(-1);

    // Market ID constants
    public static final int MARKET_BTC_USD = 1;
    public static final int MARKET_ETH_USD = 2;
    public static final int MARKET_SOL_USD = 3;
    public static final int MARKET_XRP_USD = 4;
    public static final int MARKET_DOGE_USD = 5;

    // Command type constants (int for zero-allocation dispatch)
    public static final int CMD_CREATE = 0;
    public static final int CMD_CANCEL = 1;
    public static final int CMD_UPDATE = 2;

    public Engine() {
        this(resolveEngineImpl());
    }

    /**
     * Construct with an explicit matching implementation ("array" | "direct"), bypassing the flag.
     * Used by the A/B determinism test to run the same corpus through both implementations in one
     * process; production uses the no-arg constructor (flag-resolved).
     */
    public Engine(String impl) {
        this.engines = new Int2ObjectHashMap<>();

        logger.info("Engine matching implementation: {}", impl);

        // Initialize all markets from MarketConfig
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            MatchingEngine engine = createMatchingEngine(impl, config);
            engines.put(config.marketId, engine);
            logger.info("Initialized {} engine: {} price levels",
                config.symbol, config.getPriceLevels());
        }

        logger.info("Engine started with {} markets.", MarketConfig.ALL_MARKETS.length);
    }

    /**
     * Resolve the matching-engine implementation flag.
     * Precedence: -Dmatch.engine.impl, then MATCH_ENGINE_IMPL env var, default "direct".
     */
    private static String resolveEngineImpl() {
        String p = System.getProperty("match.engine.impl");
        if (p == null || p.isEmpty()) {
            p = System.getenv("MATCH_ENGINE_IMPL");
        }
        // Default is the array-backed engine (geometry-free, memory ∝ orders, no 64-cap);
        // set match.engine.impl=direct / MATCH_ENGINE_IMPL=direct to fall back to the
        // preallocated direct-index engine.
        return (p == null || p.isEmpty()) ? "array" : p.toLowerCase();
    }

    /**
     * Construct the per-market matching engine for the selected implementation.
     * "array" = array-backed {@link ArrayMatchingEngine} (geometry-free, memory ∝ orders);
     * "direct" = preallocated {@link DirectMatchingEngine}.
     */
    private static MatchingEngine createMatchingEngine(String impl, MarketConfig config) {
        switch (impl) {
            case "array":
                return new ArrayMatchingEngine(config.basePrice, config.maxPrice, config.tickSize,
                    resolveBookCapacity());
            case "direct":
            default:
                return new DirectMatchingEngine(config.basePrice, config.maxPrice, config.tickSize);
        }
    }

    /**
     * Max simultaneous resting orders per book side for the array-backed engine (its single,
     * adjustable capacity bound — replaces the direct-index book's per-level 64-cap and
     * memory-∝-price-range footprint). Override with -Dmatch.engine.book.capacity / env.
     */
    private static int resolveBookCapacity() {
        String p = System.getProperty("match.engine.book.capacity");
        if (p == null || p.isEmpty()) {
            p = System.getenv("MATCH_ENGINE_BOOK_CAPACITY");
        }
        if (p != null && !p.isEmpty()) {
            try {
                int v = Integer.parseInt(p.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignore) {
                // fall through to default
            }
        }
        return 1 << 17; // 131072
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
        MatchingEngine engine = engines.get(marketId);
        if (engine == null) {
            return; // Silently ignore unknown markets in hot path
        }

        // Direct int switch - no string comparison
        switch (commandType) {
            case CMD_CREATE:
                processCreate(engine, (CreateOrderCommand) command, marketId, timestamp);
                break;
            case CMD_CANCEL:
                processCancel(engine, (CancelOrderCommand) command, marketId, timestamp);
                break;
            case CMD_UPDATE:
                processUpdate(engine, (UpdateOrderCommand) command, marketId, timestamp);
                break;
        }

        // Refresh the writer-published top-of-book snapshot for the market-data flush thread.
        // No-op for the direct-index engine; cheap dirty-gated refresh for the array engine.
        engine.publishTopOfBook();
    }

    /**
     * Process create order - ZERO allocations in matching path
     */
    private void processCreate(MatchingEngine engine, CreateOrderCommand cmd, int marketId, long timestamp) {
        long orderId = orderIdGenerator.getAndIncrement();
        long userId = cmd.getUserId();
        long price = cmd.getPrice();
        long quantity = cmd.getQuantity();
        long totalPrice = cmd.getTotalPrice();
        OrderSide side = cmd.getOrderSide();
        OrderType type = cmd.getOrderType();
        long omsOrderId = cmd.getOmsOrderId();

        // Store omsOrderId mapping for maker correlation in trades
        if (omsOrderId != 0) {
            orderIdToOmsOrderId.put(orderId, omsOrderId);
        }

        boolean isBuy = (side == OrderSide.BID);
        int matchCount = 0;

        if (type == OrderType.MARKET) {
            // Market order
            if (isBuy) {
                matchCount = engine.processMarketOrder(orderId, userId, true, 0, totalPrice);
            } else {
                matchCount = engine.processMarketOrder(orderId, userId, false, quantity, 0);
            }
            // Publish trades (order book published at 50ms intervals by MarketPublisher)
            publishTradeExecutions(engine, marketId, timestamp, orderId, userId, isBuy, matchCount, omsOrderId);
            long filledQty = calculateFilledQuantity(engine, matchCount);
            if (matchCount == 0) {
                // Market order with no matches — reject (no liquidity)
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    0, 0, price, isBuy, omsOrderId);
            } else {
                // Market orders are always fully executed (no remaining quantity on book)
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.FILLED,
                    0, filledQty, price, isBuy, omsOrderId);
            }
            // Clean up mapping for fully consumed market orders
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(orderId);
            }
        } else if (type == OrderType.LIMIT) {
            // Loud-limits: validate price before matching — reject the whole
            // order upfront rather than partially matching an invalid price
            int validity = engine.validateLimitPrice(price);
            if (validity != OrderRejectReason.NONE) {
                logger.warn("Order rejected: market={} userId={} price={} reason={}",
                    marketId, userId, price, OrderRejectReason.describe(validity));
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    quantity, 0, price, isBuy, omsOrderId);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
                return;
            }

            matchCount = engine.processLimitOrder(orderId, userId, isBuy, price, quantity);
            publishTradeExecutions(engine, marketId, timestamp, orderId, userId, isBuy, matchCount, omsOrderId);
            // Publish order status
            long remainingQty = engine.getTakerRemainingQuantity();
            long filledQty = quantity - remainingQty;
            int restReason = engine.getLastRestRejectReason();
            if (remainingQty == 0 && matchCount > 0) {
                // Fully filled
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.FILLED,
                    0, filledQty, price, isBuy, omsOrderId);
                // Clean up mapping for fully filled orders
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            } else if (restReason != OrderRejectReason.NONE) {
                // Remainder could not rest on the book — loud terminal status,
                // never a phantom NEW/PARTIALLY_FILLED for an order that isn't resting
                logger.warn("Order remainder could not rest: market={} orderId={} userId={} price={} reason={}",
                    marketId, orderId, userId, price, OrderRejectReason.describe(restReason));
                if (matchCount == 0) {
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                        remainingQty, 0, price, isBuy, omsOrderId);
                } else {
                    // Filled what it could; remainder cancelled (terminal)
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                        0, filledQty, price, isBuy, omsOrderId);
                }
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            } else if (matchCount > 0 && remainingQty > 0) {
                // Partially filled, rest on book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.PARTIALLY_FILLED,
                    remainingQty, filledQty, price, isBuy, omsOrderId);
            } else if (matchCount == 0 && remainingQty > 0) {
                // No match, order added to book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.NEW,
                    remainingQty, 0, price, isBuy, omsOrderId);
            }
        } else if (type == OrderType.LIMIT_MAKER) {
            // Loud-limits: validate price before book checks
            int validity = engine.validateLimitPrice(price);
            if (validity != OrderRejectReason.NONE) {
                logger.warn("LIMIT_MAKER rejected: market={} userId={} price={} reason={}",
                    marketId, userId, price, OrderRejectReason.describe(validity));
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    quantity, 0, price, isBuy, omsOrderId);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
                return;
            }

            // Limit maker - only add if won't match immediately
            long bestOpposite = isBuy ? engine.getBestAsk() : engine.getBestBid();
            boolean wouldMatch = isBuy
                ? (!engine.isAskEmpty() && price >= bestOpposite)
                : (!engine.isBidEmpty() && price <= bestOpposite);

            if (!wouldMatch) {
                int addResult = engine.addOrderNoMatch(orderId, userId, isBuy, price, quantity);
                if (addResult != OrderRejectReason.NONE) {
                    // Could not rest (e.g. level full) — loud rejection, not phantom NEW
                    logger.warn("LIMIT_MAKER could not rest: market={} orderId={} userId={} price={} reason={}",
                        marketId, orderId, userId, price, OrderRejectReason.describe(addResult));
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                        quantity, 0, price, isBuy, omsOrderId);
                    if (omsOrderId != 0) {
                        orderIdToOmsOrderId.remove(orderId);
                    }
                    return;
                }
                // Order added to book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.NEW,
                    quantity, 0, price, isBuy, omsOrderId);
            } else {
                // Order would cross spread - rejected
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    0, 0, price, isBuy, omsOrderId);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            }
        }
    }

    /**
     * Calculate total filled quantity from match results.
     */
    private long calculateFilledQuantity(MatchingEngine engine, int matchCount) {
        long total = 0;
        for (int i = 0; i < matchCount; i++) {
            total += engine.getMatchQuantity(i);
        }
        return total;
    }

    /**
     * Publish trade executions only.
     * Order book is published at 50ms intervals by MarketPublisher.
     */
    private void publishTradeExecutions(MatchingEngine engine, int marketId, long timestamp,
            long takerOrderId, long takerUserId, boolean takerIsBuy, int matchCount, long takerOmsOrderId) {
        if (eventPublisher == null || matchCount == 0) {
            return;
        }

        for (int i = 0; i < matchCount; i++) {
            long makerOrderId = engine.getMatchMakerOrderId(i);
            long makerUserId = engine.getMatchMakerUserId(i);
            long matchPrice = engine.getMatchPrice(i);
            long matchQty = engine.getMatchQuantity(i);

            // Look up maker's omsOrderId from stored mapping
            long makerOmsOrderId = orderIdToOmsOrderId.get(makerOrderId);
            if (makerOmsOrderId == -1) {
                makerOmsOrderId = 0; // Not found (pre-OMS order)
            }

            eventPublisher.publishTradeExecution(
                marketId, timestamp,
                takerOrderId, takerUserId,
                makerOrderId, makerUserId,
                matchPrice, matchQty, takerIsBuy,
                takerOmsOrderId, makerOmsOrderId
            );
        }
    }

    /**
     * Process cancel order - O(1)
     */
    private void processCancel(MatchingEngine engine, CancelOrderCommand cmd, int marketId, long timestamp) {
        long orderId = cmd.getOrderId();
        long userId = cmd.getUserId();

        // Try both sides since we don't store order side
        boolean cancelled = engine.cancelOrder(orderId, true);
        boolean isBuy = true;
        if (!cancelled) {
            cancelled = engine.cancelOrder(orderId, false);
            isBuy = false;
        }

        if (cancelled) {
            // Look up and clean up omsOrderId mapping
            long omsOrderId = orderIdToOmsOrderId.remove(orderId);
            if (omsOrderId == -1) {
                omsOrderId = 0;
            }
            publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                0, 0, 0, isBuy, omsOrderId);
        } else {
            // Order not in book (already cancelled/filled, or unknown). Emit a CANCELLED ack anyway
            // so a reconciling OMS (re-cancelling after a leader-switchover seam where the original
            // cancel or its terminal egress was lost) gets definitive closure and releases the
            // now-unencumbered hold. The OMS releases only the UNFILLED remainder (hold minus
            // trade-derived consumed), so acking an already-filled order is a safe no-op there. (oms#21)
            long omsOrderId = orderIdToOmsOrderId.remove(orderId);
            if (omsOrderId == -1) {
                omsOrderId = 0;
            }
            publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                0, 0, 0, isBuy, omsOrderId);
        }
    }

    /**
     * Process update order - cancel-and-replace. O(1) cancel + O(1) limit placement.
     * Atomic within the single-threaded engine — no external matches can occur between cancel and re-place.
     */
    private void processUpdate(MatchingEngine engine, UpdateOrderCommand cmd, int marketId, long timestamp) {
        long oldOrderId = cmd.getOrderId();
        long userId = cmd.getUserId();
        long newPrice = cmd.getPrice();
        long newQuantity = cmd.getQuantity();
        OrderSide side = cmd.getOrderSide();
        boolean isBuy = (side == OrderSide.BID);

        // Preserve omsOrderId before cancel removes the mapping
        long omsOrderId = orderIdToOmsOrderId.get(oldOrderId);
        if (omsOrderId == -1) {
            omsOrderId = 0;
        }

        // Loud-limits: validate the NEW price BEFORE cancelling the old order.
        // An invalid update must reject the update and leave the resting order intact.
        int validity = engine.validateLimitPrice(newPrice);
        if (validity != OrderRejectReason.NONE) {
            logger.warn("Update rejected: market={} orderId={} userId={} newPrice={} reason={}",
                marketId, oldOrderId, userId, newPrice, OrderRejectReason.describe(validity));
            publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.REJECTED,
                0, 0, newPrice, isBuy, omsOrderId);
            return;
        }

        // 1. Cancel existing order
        boolean cancelled = engine.cancelOrder(oldOrderId, isBuy);
        if (!cancelled) {
            // Order not found on expected side — reject the update
            publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.REJECTED,
                0, 0, newPrice, isBuy, omsOrderId);
            return;
        }

        // Clean up old mapping
        orderIdToOmsOrderId.remove(oldOrderId);

        // Publish cancel for old order
        publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.CANCELLED,
            0, 0, 0, isBuy, omsOrderId);

        // 2. Place new order with updated price/quantity
        long newOrderId = orderIdGenerator.getAndIncrement();
        if (omsOrderId != 0) {
            orderIdToOmsOrderId.put(newOrderId, omsOrderId);
        }

        int matchCount = engine.processLimitOrder(newOrderId, userId, isBuy, newPrice, newQuantity);
        publishTradeExecutions(engine, marketId, timestamp, newOrderId, userId, isBuy, matchCount, omsOrderId);

        long remainingQty = engine.getTakerRemainingQuantity();
        long filledQty = newQuantity - remainingQty;
        int restReason = engine.getLastRestRejectReason();

        if (remainingQty == 0 && matchCount > 0) {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.FILLED,
                0, filledQty, newPrice, isBuy, omsOrderId);
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(newOrderId);
            }
        } else if (restReason != OrderRejectReason.NONE) {
            // Remainder could not rest — loud terminal status (see processCreate)
            logger.warn("Updated order could not rest: market={} orderId={} userId={} price={} reason={}",
                marketId, newOrderId, userId, newPrice, OrderRejectReason.describe(restReason));
            if (matchCount == 0) {
                publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.REJECTED,
                    remainingQty, 0, newPrice, isBuy, omsOrderId);
            } else {
                publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.CANCELLED,
                    0, filledQty, newPrice, isBuy, omsOrderId);
            }
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(newOrderId);
            }
        } else if (matchCount > 0 && remainingQty > 0) {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.PARTIALLY_FILLED,
                remainingQty, filledQty, newPrice, isBuy, omsOrderId);
        } else {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.NEW,
                remainingQty, 0, newPrice, isBuy, omsOrderId);
        }
    }

    /**
     * Publish order status update.
     */
    private void publishOrderStatus(int marketId, long timestamp, long orderId, long userId,
            int orderStatus, long remainingQty, long filledQty, long orderPrice, boolean isBuy, long omsOrderId) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            orderStatus, remainingQty, filledQty, orderPrice, isBuy, omsOrderId
        );
    }

    /**
     * Get matching engine for a market
     */
    public MatchingEngine getEngine(int marketId) {
        return engines.get(marketId);
    }

    public void close() {
        // No resources to close
    }

    /**
     * Set the event publisher for broadcasting matches and order updates.
     * Call this during startup before processing orders.
     */
    public void setEventPublisher(MatchEventSink publisher) {
        this.eventPublisher = publisher;
    }

    /**
     * Get the event sink (for testing/monitoring).
     */
    public MatchEventSink getEventPublisher() {
        return eventPublisher;
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
    public Int2ObjectHashMap<MatchingEngine> getEngines() {
        return engines;
    }

    /**
     * Get the orderId-to-omsOrderId mapping for snapshot.
     */
    public Long2LongHashMap getOrderIdToOmsOrderId() {
        return orderIdToOmsOrderId;
    }
}
