// SPDX-License-Identifier: Apache-2.0
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

    // Aeron log position of the session message currently being processed
    // (set by the clustered service before demux; single-threaded).
    private long currentLogPosition;

    /** Called by the clustered service with Header.position() before dispatching each message. */
    public void setCurrentLogPosition(long position) {
        this.currentLogPosition = position;
    }

    // Event sink (optional - set via setEventPublisher). Interface, not the concrete
    // MatchEventPublisher, so matching output can be captured synchronously in tests.
    private MatchEventSink eventPublisher;

    // Maps cluster orderId → omsOrderId for maker order correlation in trade executions
    // When a maker order is on the book, we need to look up its omsOrderId when it matches
    private final Long2LongHashMap orderIdToOmsOrderId = new Long2LongHashMap(-1);

    // P1.1 (match#30): orders rejected at admission because price*quantity does not
    // fit 64-bit fixed-point. Plain long: written and read on the service thread only;
    // surfaced on the EGRESS-DIAG line as overflowRej.
    private long overflowRejectCount;

    // match#91: orders rejected at admission for a non-positive quantity (LIMIT / LIMIT_MAKER /
    // UPDATE) or a non-positive market size (MARKET buy budget / sell quantity). Plain long:
    // written and read on the service thread only; surfaced on the EGRESS-DIAG line as invalidQtyRej.
    private long invalidQuantityRejectCount;

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

        // Stamp the market-data book version BEFORE processing so every state
        // this command publishes carries it. max(current+1, logPosition) is
        // deterministic across replicas (same log) and never regresses across
        // restarts/failovers even though the version itself is not part of the
        // engine snapshot (the next command jumps it to the live log position).
        engine.setBookVersion(Math.max(engine.getBookVersion() + 1, currentLogPosition));

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
            // Loud-limits (match#91): a market order with no economic size (buy budget <= 0,
            // sell quantity <= 0) can never match. Reject at admission with INVALID_QUANTITY and
            // skip the matching sweep entirely, rather than silently falling through to the
            // no-liquidity REJECTED branch (which would misattribute the cause).
            long marketSize = isBuy ? totalPrice : quantity;
            if (marketSize <= 0) {
                invalidQuantityRejectCount++;
                if (logger.isWarn()) logger.warn("Market order rejected: market={} userId={} isBuy={} reason={}",
                    marketId, userId, isBuy, OrderRejectReason.describe(OrderRejectReason.INVALID_QUANTITY));
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    0, 0, price, isBuy, omsOrderId, OrderRejectReason.INVALID_QUANTITY);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
                return;
            }
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
                    0, 0, price, isBuy, omsOrderId, OrderRejectReason.NO_LIQUIDITY);
            } else if (engine.wasMatchLimitReached()) {
                // match#93: the per-order match cap truncated the sweep while crossing liquidity
                // remained. This is a genuine PARTIAL, not a full fill — publish CANCELLED with the
                // TRUE filled quantity so OMS never sees a capped partial mis-reported as FILLED.
                // Trust the explicit flag, NOT a leftover-budget heuristic (the budget/price-precision
                // break also leaves budget>0). matchCount==0 with the flag set is impossible (cap>=1).
                if (logger.isWarn()) logger.warn("Market order capped mid-sweep (match#93): market={} orderId={} userId={} filled={}",
                    marketId, orderId, userId, filledQty);
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                    0, filledQty, price, isBuy, omsOrderId, OrderRejectReason.MATCH_LIMIT);
            } else {
                // Market orders otherwise fully execute (natural exhaustion — no remaining on book)
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.FILLED,
                    0, filledQty, price, isBuy, omsOrderId, OrderRejectReason.NONE);
            }
            // Clean up mapping for fully consumed market orders
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(orderId);
            }
        } else if (type == OrderType.LIMIT) {
            // Loud-limits: validate price before matching — reject the whole
            // order upfront rather than partially matching an invalid price
            int validity = engine.validateLimitPrice(price);
            // match#91: a non-positive quantity is rejected BEFORE the overflow check, so
            // INVALID_QUANTITY wins over OVERFLOW for garbage input (both would otherwise fire).
            if (validity == OrderRejectReason.NONE && invalidQuantity(quantity)) {
                validity = OrderRejectReason.INVALID_QUANTITY;
            }
            if (validity == OrderRejectReason.NONE && notionalOverflows(price, quantity)) {
                validity = OrderRejectReason.OVERFLOW;
            }
            if (validity != OrderRejectReason.NONE) {
                if (logger.isWarn()) logger.warn("Order rejected: market={} userId={} price={} reason={}",
                    marketId, userId, price, OrderRejectReason.describe(validity));
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    quantity, 0, price, isBuy, omsOrderId, validity);
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
                    0, filledQty, price, isBuy, omsOrderId, OrderRejectReason.NONE);
                // Clean up mapping for fully filled orders
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            } else if (restReason != OrderRejectReason.NONE) {
                // Remainder could not rest on the book — loud terminal status,
                // never a phantom NEW/PARTIALLY_FILLED for an order that isn't resting
                if (logger.isWarn()) logger.warn("Order remainder could not rest: market={} orderId={} userId={} price={} reason={}",
                    marketId, orderId, userId, price, OrderRejectReason.describe(restReason));
                if (matchCount == 0) {
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                        remainingQty, 0, price, isBuy, omsOrderId, restReason);
                } else {
                    // Filled what it could; remainder cancelled (terminal) — carry the rest-fail reason
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                        0, filledQty, price, isBuy, omsOrderId, restReason);
                }
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            } else if (matchCount > 0 && remainingQty > 0) {
                // Partially filled, rest on book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.PARTIALLY_FILLED,
                    remainingQty, filledQty, price, isBuy, omsOrderId, OrderRejectReason.NONE);
            } else if (matchCount == 0 && remainingQty > 0) {
                // No match, order added to book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.NEW,
                    remainingQty, 0, price, isBuy, omsOrderId, OrderRejectReason.NONE);
            }
        } else if (type == OrderType.LIMIT_MAKER) {
            // Loud-limits: validate price before book checks
            int validity = engine.validateLimitPrice(price);
            // match#91: reject a non-positive quantity before the overflow check (INVALID_QUANTITY
            // wins over OVERFLOW). A qty=0 LIMIT_MAKER would otherwise rest as a head-of-level
            // poison pill that permanently abandons the whole price level.
            if (validity == OrderRejectReason.NONE && invalidQuantity(quantity)) {
                validity = OrderRejectReason.INVALID_QUANTITY;
            }
            if (validity == OrderRejectReason.NONE && notionalOverflows(price, quantity)) {
                validity = OrderRejectReason.OVERFLOW;
            }
            if (validity != OrderRejectReason.NONE) {
                if (logger.isWarn()) logger.warn("LIMIT_MAKER rejected: market={} userId={} price={} reason={}",
                    marketId, userId, price, OrderRejectReason.describe(validity));
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    quantity, 0, price, isBuy, omsOrderId, validity);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
                return;
            }

            // Limit maker - only add if won't match immediately (post-only). Shared with the
            // LIMIT_MAKER amend path (match#92) so crossing is decided identically in both.
            boolean wouldMatch = wouldCrossOpposite(engine, isBuy, price);

            if (!wouldMatch) {
                int addResult = engine.addOrderNoMatch(orderId, userId, isBuy, price, quantity);
                if (addResult != OrderRejectReason.NONE) {
                    // Could not rest (e.g. level full) — loud rejection, not phantom NEW
                    if (logger.isWarn()) logger.warn("LIMIT_MAKER could not rest: market={} orderId={} userId={} price={} reason={}",
                        marketId, orderId, userId, price, OrderRejectReason.describe(addResult));
                    publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                        quantity, 0, price, isBuy, omsOrderId, addResult);
                    if (omsOrderId != 0) {
                        orderIdToOmsOrderId.remove(orderId);
                    }
                    return;
                }
                // Order added to book — keep mapping for future maker matches
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.NEW,
                    quantity, 0, price, isBuy, omsOrderId, OrderRejectReason.NONE);
            } else {
                // Order would cross spread - rejected (post-only guarantee)
                publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.REJECTED,
                    0, 0, price, isBuy, omsOrderId, OrderRejectReason.WOULD_CROSS);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(orderId);
                }
            }
        }
    }

    /**
     * True if price * quantity does not fit 64-bit fixed-point (match#30).
     * Checked at ADMISSION (LIMIT / LIMIT_MAKER / UPDATE) so no order whose
     * notional overflows ever enters the book — every downstream cost product
     * is then bounded by an admitted notional and cannot overflow mid-match.
     */
    private boolean notionalOverflows(long price, long quantity) {
        try {
            FixedPoint.multiply(price, quantity);
            return false;
        } catch (ArithmeticException e) {
            overflowRejectCount++;
            return true;
        }
    }

    /** Orders rejected at admission for notional overflow (diag counter). */
    public long getOverflowRejectCount() {
        return overflowRejectCount;
    }

    /**
     * True if {@code quantity} is not strictly positive (match#91). Bumps the diag counter as a
     * side effect at the point of decision, mirroring {@link #notionalOverflows}. Checked at
     * ADMISSION (LIMIT / LIMIT_MAKER / UPDATE) so no order with a zero/negative quantity ever
     * enters the book — such an order silently loses (no status) or poisons its price level.
     */
    private boolean invalidQuantity(long quantity) {
        if (quantity <= 0) {
            invalidQuantityRejectCount++;
            return true;
        }
        return false;
    }

    /** Orders rejected at admission for a non-positive quantity/market size (diag counter, match#91). */
    public long getInvalidQuantityRejectCount() {
        return invalidQuantityRejectCount;
    }

    /**
     * True if a would-be maker resting at {@code price} on the given side would cross the opposite
     * best and thus execute immediately — violating the post-only (LIMIT_MAKER) guarantee. Shared by
     * the LIMIT_MAKER create branch and the LIMIT_MAKER amend branch (match#92) so post-only is
     * decided by the exact same expression in both. Exact for the single-threaded engine: a maker
     * rests on its OWN side, so placing or cancelling it cannot move the opposite best read here.
     */
    private static boolean wouldCrossOpposite(MatchingEngine engine, boolean isBuy, long price) {
        long bestOpposite = isBuy ? engine.getBestAsk() : engine.getBestBid();
        return isBuy
            ? (!engine.isAskEmpty() && price >= bestOpposite)
            : (!engine.isBidEmpty() && price <= bestOpposite);
    }

    /**
     * omsOrderId for a resting order, or 0 when unknown (match#31: the mapping
     * is not part of the snapshot, so orders restored from a snapshot have no
     * correlation until they trade — OMS reconciles by cluster orderId).
     */
    public long getOmsOrderIdFor(long orderId) {
        final long v = orderIdToOmsOrderId.get(orderId);
        return v == -1 ? 0 : v;
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
                takerOmsOrderId, makerOmsOrderId,
                currentLogPosition
            );

            // A fully-consumed maker gets an explicit terminal FILLED. The OMS derives maker fill
            // state from the TradeExecutions above (late/duplicate statuses for terminal orders are
            // dropped there), but the settlement journal derives the AE's TerminalRelease from
            // terminal statuses — without this the maker's hold is never feed-released. Quantities
            // are zeros like processCancel's ack: trades are the quantity source of record.
            if (engine.isMatchMakerFilled(i)) {
                orderIdToOmsOrderId.remove(makerOrderId);
                publishOrderStatus(marketId, timestamp, makerOrderId, makerUserId,
                    OrderStatusType.FILLED, 0, 0, 0, !takerIsBuy, makerOmsOrderId,
                    OrderRejectReason.NONE);
            }
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
                0, 0, 0, isBuy, omsOrderId, OrderRejectReason.NONE);
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
            // Reconciliation ack (order already gone) is a successful CANCELLED, not a reject: NONE.
            publishOrderStatus(marketId, timestamp, orderId, userId, OrderStatusType.CANCELLED,
                0, 0, 0, isBuy, omsOrderId, OrderRejectReason.NONE);
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
        // match#91: an UPDATE to a non-positive quantity is a REJECT, never an implicit cancel.
        // Checked before the overflow test (INVALID_QUANTITY wins) and before the cancel below,
        // so the OLD order survives a rejected amend — the existing price-validation pattern.
        if (validity == OrderRejectReason.NONE && invalidQuantity(newQuantity)) {
            validity = OrderRejectReason.INVALID_QUANTITY;
        }
        if (validity == OrderRejectReason.NONE && notionalOverflows(newPrice, newQuantity)) {
            validity = OrderRejectReason.OVERFLOW;
        }
        if (validity != OrderRejectReason.NONE) {
            if (logger.isWarn()) logger.warn("Update rejected: market={} orderId={} userId={} newPrice={} reason={}",
                marketId, oldOrderId, userId, newPrice, OrderRejectReason.describe(validity));
            publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.REJECTED,
                0, 0, newPrice, isBuy, omsOrderId, validity);
            return;
        }

        // match#92: honor post-only on amends. processUpdate is cancel-and-replace and used to route
        // EVERY replacement through the MATCHING processLimitOrder path — so a LIMIT_MAKER amended to a
        // now-crossing price would silently execute as a taker, stripping the post-only guarantee.
        // Decide crossing BEFORE cancelling: the old order rests on the SAME side as the replacement,
        // so cancelling it cannot move the opposite best this reads — the pre-cancel test is exact. If
        // it would cross, REJECT the amend and leave the old order resting and tradable (same status
        // semantics as processCreate's post-only reject; OLD orderId+omsOrderId like the rejects above).
        if (cmd.getOrderType() == OrderType.LIMIT_MAKER && wouldCrossOpposite(engine, isBuy, newPrice)) {
            if (logger.isWarn()) logger.warn("LIMIT_MAKER amend rejected (would cross): market={} orderId={} userId={} newPrice={}",
                marketId, oldOrderId, userId, newPrice);
            publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.REJECTED,
                0, 0, newPrice, isBuy, omsOrderId, OrderRejectReason.WOULD_CROSS);
            return;
        }

        // 1. Cancel existing order
        boolean cancelled = engine.cancelOrder(oldOrderId, isBuy);
        if (!cancelled) {
            // Order not found on expected side — reject the update
            publishOrderStatus(marketId, timestamp, oldOrderId, userId, OrderStatusType.REJECTED,
                0, 0, newPrice, isBuy, omsOrderId, OrderRejectReason.ORDER_NOT_FOUND);
            return;
        }

        // Clean up old mapping
        orderIdToOmsOrderId.remove(oldOrderId);

        // Publish cancel for old order (the successful half of an accepted amend, not a reject).
        // Journal-exempt: this CANCELLED shares the live replacement's omsOrderId — see
        // publishReplaceCancelStatus.
        publishReplaceCancelStatus(marketId, timestamp, oldOrderId, userId, isBuy, omsOrderId);

        // 2. Place new order with updated price/quantity
        long newOrderId = orderIdGenerator.getAndIncrement();
        if (omsOrderId != 0) {
            orderIdToOmsOrderId.put(newOrderId, omsOrderId);
        }

        // match#92: a LIMIT_MAKER replacement rests post-only. We already proved it does not cross
        // (pre-cancel check above), so add it via the NO-MATCH path — never processLimitOrder, which
        // would match. The engine is single-threaded, so nothing can cross between that check and here.
        if (cmd.getOrderType() == OrderType.LIMIT_MAKER) {
            int addResult = engine.addOrderNoMatch(newOrderId, userId, isBuy, newPrice, newQuantity);
            if (addResult != OrderRejectReason.NONE) {
                // Replacement could not rest (e.g. LEVEL_FULL/BOOK_FULL). The old order is already
                // cancelled — FIFO position is forfeit on any amend — so this is terminal: a loud
                // REJECTED, mirroring processCreate's could-not-rest path (never a phantom NEW).
                if (logger.isWarn()) logger.warn("LIMIT_MAKER amend could not rest: market={} orderId={} userId={} price={} reason={}",
                    marketId, newOrderId, userId, newPrice, OrderRejectReason.describe(addResult));
                publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.REJECTED,
                    newQuantity, 0, newPrice, isBuy, omsOrderId, addResult);
                if (omsOrderId != 0) {
                    orderIdToOmsOrderId.remove(newOrderId);
                }
                return;
            }
            // Rested post-only — NEW for the full new quantity (nothing filled).
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.NEW,
                newQuantity, 0, newPrice, isBuy, omsOrderId, OrderRejectReason.NONE);
            return;
        }

        int matchCount = engine.processLimitOrder(newOrderId, userId, isBuy, newPrice, newQuantity);
        publishTradeExecutions(engine, marketId, timestamp, newOrderId, userId, isBuy, matchCount, omsOrderId);

        long remainingQty = engine.getTakerRemainingQuantity();
        long filledQty = newQuantity - remainingQty;
        int restReason = engine.getLastRestRejectReason();

        if (remainingQty == 0 && matchCount > 0) {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.FILLED,
                0, filledQty, newPrice, isBuy, omsOrderId, OrderRejectReason.NONE);
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(newOrderId);
            }
        } else if (restReason != OrderRejectReason.NONE) {
            // Remainder could not rest — loud terminal status (see processCreate)
            if (logger.isWarn()) logger.warn("Updated order could not rest: market={} orderId={} userId={} price={} reason={}",
                marketId, newOrderId, userId, newPrice, OrderRejectReason.describe(restReason));
            if (matchCount == 0) {
                publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.REJECTED,
                    remainingQty, 0, newPrice, isBuy, omsOrderId, restReason);
            } else {
                publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.CANCELLED,
                    0, filledQty, newPrice, isBuy, omsOrderId, restReason);
            }
            if (omsOrderId != 0) {
                orderIdToOmsOrderId.remove(newOrderId);
            }
        } else if (matchCount > 0 && remainingQty > 0) {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.PARTIALLY_FILLED,
                remainingQty, filledQty, newPrice, isBuy, omsOrderId, OrderRejectReason.NONE);
        } else {
            publishOrderStatus(marketId, timestamp, newOrderId, userId, OrderStatusType.NEW,
                remainingQty, 0, newPrice, isBuy, omsOrderId, OrderRejectReason.NONE);
        }
    }

    /**
     * Publish order status update.
     *
     * @param rejectReason raw {@link OrderRejectReason} code explaining a REJECTED (or a
     *                     reason-carrying CANCELLED) status; {@link OrderRejectReason#NONE} on
     *                     accepted statuses (NEW / PARTIALLY_FILLED / FILLED / plain CANCELLED).
     *                     Carried on the order-status egress from SBE v6 (match#75).
     */
    private void publishOrderStatus(int marketId, long timestamp, long orderId, long userId,
            int orderStatus, long remainingQty, long filledQty, long orderPrice, boolean isBuy, long omsOrderId,
            int rejectReason) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            orderStatus, remainingQty, filledQty, orderPrice, isBuy, omsOrderId, rejectReason,
            currentLogPosition
        );
    }

    /**
     * Replace-cancel variant: publishes the old leg's CANCELLED on the wire (the OMS's replace
     * leg-routing depends on it) but keeps it OUT of the settlement journal — it shares the live
     * replacement's omsOrderId, and a journaled terminal would feed the AE a TerminalRelease that
     * strips the still-open order's hold.
     */
    private void publishReplaceCancelStatus(int marketId, long timestamp, long orderId, long userId,
            boolean isBuy, long omsOrderId) {
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            OrderStatusType.CANCELLED, 0, 0, 0, isBuy, omsOrderId, OrderRejectReason.NONE,
            currentLogPosition, true
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
