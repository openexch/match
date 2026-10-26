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

    // RC2 S3 (contract section 3, G2/K7): the trade-id generator, engine-owned REPLICATED state
    // (snapshot payload offset 8). One getAndIncrement per match in publishTradeExecutions, before
    // any sink call and outside every sink guard, so a ring-less, journal-dark or sink-less member
    // allocates exactly what a production member does. AtomicLong like orderIdGenerator: the
    // /metrics scraper reads it (match_trades_total).
    private final AtomicLong tradeIdGenerator = new AtomicLong(1);

    // RC2 S3 (contract section 2, review NOTE-2): true iff the MeIdentity is bound (recorded
    // behaviour >= 2). A plain field set exactly where the identity is bound (FRESH and the
    // snapshot restore of the identity block), so the hot path reads no volatile. Gates dense
    // journalSeq allocation and the X4 duplicate suppression.
    private boolean dense;

    // RC2 S3 X4: equal-intent duplicates of a recorded outcome suppressed under behaviour 2. Not
    // replicated (never snapshotted); single writer (service thread), volatile for the scraper.
    private volatile long suppressedDuplicateCount;

    // Aeron log position of the session message currently being processed
    // (set by the clustered service before demux; single-threaded).
    private long currentLogPosition;

    /** Called by the clustered service with Header.position() before dispatching each message. */
    public void setCurrentLogPosition(long position) {
        this.currentLogPosition = position;
    }

    /** Log position of the command being processed (egressSeq source; slice C reject emission). */
    public long getCurrentLogPosition() {
        return currentLogPosition;
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
    private final DurableCommandLedger commandLedger = new DurableCommandLedger();
    private com.match.domain.commands.DurableOrderIntent applyingIntent;
    private long outcomeOrder, expectedNewOrder;
    private int outcomeStatus, outcomeReason;
    private boolean outcomeOldCancelled;
    public DurableCommandLedger commandLedger() { return commandLedger; }

    public com.match.domain.commands.DurableCommandOutcome acceptDurable(
            com.match.domain.commands.DurableOrderIntent c, long timestamp) {
        var prior = commandLedger.get(c);
        if (prior != null) {
            // Behaviour 1: the 12-field compare. The v12 fields (timeInForce, expectedOldRemaining)
            // are not in the ledger snapshot, so they can not decide DUPLICATE vs CONFLICT.
            if (prior.intent().sameCommandBehaviour1(c)) {
                if (dense) {
                    // RC2 S3 X4 (contract section 5): behaviour 2 journals nothing for an equal
                    // duplicate of a recorded outcome and consumes no journalSeq. The original is
                    // durable at its own seq on every journaling node; a re-journal would get a
                    // new seq and could never fill another node's hole. The decision reads only
                    // the ledger (replicated, snapshotted). Also at full capacity: this check
                    // precedes the capacity check.
                    suppressedDuplicateCount = suppressedDuplicateCount + 1;
                    return prior;
                }
                // Behaviour 1: re-journal the prior outcome at journalSeq 0. The journaled record 3
                // echoes THIS frame's intent (12 fields equal to the prior's) so its v12 tail is a
                // function of the log entry, not of whether this node restored the prior from a
                // snapshot.
                if (eventPublisher != null) {
                    eventPublisher.publishCommandOutcome(currentLogPosition, 0L,
                        prior.intent().equals(c) ? prior : new com.match.domain.commands.DurableCommandOutcome(
                            c, prior.appliedPosition(), prior.timestamp(), prior.orderId(), prior.status(),
                            prior.reason(), prior.oldCancelled(), prior.result()),
                        commandLedger.size());
                }
                return prior;
            }
            var result = commandRejection(c, timestamp, 2);
            final long seq = journalSeqFor();
            if (eventPublisher != null) {
                eventPublisher.publishCommandOutcome(currentLogPosition, seq, result, commandLedger.size());
            }
            return result;
        }
        if (commandLedger.size() >= DurableCommandLedger.MAX_ENTRIES) {
            var result = commandRejection(c, timestamp, 3);
            final long seq = journalSeqFor();
            if (eventPublisher != null) {
                eventPublisher.publishCommandOutcome(currentLogPosition, seq, result, commandLedger.size());
            }
            return result;
        }
        int reject = 0;
        if (engines.get(c.marketId()) == null) reject = 6;
        else if (c.kind() != CMD_CREATE) {
            var owner = commandLedger.owner(c.oldOrderId());
            if (owner == null || orderIdToOmsOrderId.get(c.oldOrderId()) == -1) reject = 4;
            else if (owner.userId()!=c.userId() || owner.omsOrderId()!=c.omsOrderId()
                    || owner.marketId()!=c.marketId() || (c.kind()==CMD_UPDATE && owner.side()!=c.side())) reject = 5;
        }
        com.match.domain.commands.DurableCommandOutcome result;
        if (reject != 0) result = commandRejection(c, timestamp, reject);
        else {
            applyingIntent=c; expectedNewOrder=orderIdGenerator.get();
            outcomeOrder=0; outcomeStatus=-1; outcomeReason=0; outcomeOldCancelled=false;
            try {
                var side = c.side()==0 ? OrderSide.BID : OrderSide.ASK;
                var type = c.type()==0 ? OrderType.LIMIT : c.type()==1 ? OrderType.MARKET : OrderType.LIMIT_MAKER;
                if (c.kind()==CMD_CREATE) {
                    var cmd = new CreateOrderCommand(); cmd.setUserId(c.userId()); cmd.setOmsOrderId(c.omsOrderId());
                    cmd.setPrice(c.price()); cmd.setQuantity(c.quantity()); cmd.setTotalPrice(c.budget());
                    cmd.setOrderSide(side); cmd.setOrderType(type); acceptOrder(c.marketId(),CMD_CREATE,cmd,timestamp);
                } else if (c.kind()==CMD_CANCEL) {
                    var cmd = new CancelOrderCommand(); cmd.setUserId(c.userId()); cmd.setOrderId(c.oldOrderId());
                    acceptOrder(c.marketId(),CMD_CANCEL,cmd,timestamp);
                } else {
                    var cmd = new UpdateOrderCommand(); cmd.setUserId(c.userId()); cmd.setOrderId(c.oldOrderId());
                    cmd.setPrice(c.price()); cmd.setQuantity(c.quantity()); cmd.setOrderSide(side); cmd.setOrderType(type);
                    acceptOrder(c.marketId(),CMD_UPDATE,cmd,timestamp);
                }
                if (outcomeStatus<0) throw new IllegalStateException("Command applied without an outcome");
                result = new com.match.domain.commands.DurableCommandOutcome(c,currentLogPosition,timestamp,
                    outcomeOrder,outcomeStatus,outcomeReason,outcomeOldCancelled,outcomeStatus==4?1:0);
            } finally { applyingIntent=null; }
        }
        // Publisher failure is an application failure: service halts, replay restores the whole command.
        // ledgerSize on record 3 is the size AFTER this outcome is recorded (the next line). The
        // outcome's journalSeq follows the trades and terminals of the command (ring order).
        final long seq = journalSeqFor();
        if (eventPublisher != null) {
            eventPublisher.publishCommandOutcome(currentLogPosition, seq, result, commandLedger.size() + 1);
        }
        commandLedger.record(result);
        return result;
    }
    /**
     * RC2 S2 (contract section 6): a template-9 frame above the recorded wire ceiling whose v12
     * prefix decoded to a valid intent. Journals record 3 with {@code result 8} (INVALID) and
     * every other field pinned by {@link #commandRejection}: appliedPosition = egressSeq = the
     * entry's log position, the cluster timestamp, orderId 0, status -1, reason 0, oldCancelled
     * false, the frame's intent echoed, ledgerSize unchanged. Never records in the ledger (ledger
     * restore refuses result > 6, and a recorded INVALID would become a canonical outcome); no
     * capacity check, no apply, no trade or terminal.
     */
    public void journalInvalid(com.match.domain.commands.DurableOrderIntent c, long timestamp) {
        final var result = commandRejection(c, timestamp, com.match.domain.commands.DurableCommandOutcome.INVALID);
        final long seq = journalSeqFor();
        if (eventPublisher != null) {
            eventPublisher.publishCommandOutcome(currentLogPosition, seq, result, commandLedger.size());
        }
    }

    /**
     * RC2 S3 (contract section 2): the engine's journalSeq allocation point, called exactly when
     * the engine decides a record will be journaled, immediately before the sink call that
     * carries it. Behaviour 2 (identity bound): the next dense seq from the one allocator,
     * {@link MeIdentity#allocateJournalSeq()}; behaviour 1: 0. Never guarded by the sink, the
     * ring, the journal or leadership. S4's record 4 and B0-B2 records call it too.
     */
    long journalSeqFor() {
        return dense ? meIdentity.allocateJournalSeq() : 0L;
    }

    /** RC2 S3: FRESH at behaviour >= 2 binds the identity and turns dense allocation on. */
    void bindFreshIdentity(long genesisId, long recordedBehaviorVersion) {
        meIdentity.bindFresh(genesisId, recordedBehaviorVersion);
        dense = true;
    }

    /**
     * RC2 S3: the snapshot restore of the identity block (already range-checked by the codec)
     * binds the identity and turns dense allocation on, as FRESH did on the original members.
     */
    public void restoreIdentity(long meEpoch, long nextJournalSeq, long behaviorVersion) {
        meIdentity.restore(meEpoch, nextJournalSeq, behaviorVersion);
        dense = true;
    }

    /** RC2 S3: true once journalSeq allocation is dense; always equals {@code meIdentity().isBound()}. */
    public boolean isJournalSeqDense() {
        return dense;
    }

    private com.match.domain.commands.DurableCommandOutcome commandRejection(
            com.match.domain.commands.DurableOrderIntent c,long timestamp,int result) {
        return new com.match.domain.commands.DurableCommandOutcome(c,currentLogPosition,timestamp,0,-1,0,false,result);
    }
    private void captureCommandStatus(long order,int status,int reason) {
        if (applyingIntent!=null && (order==expectedNewOrder || order==applyingIntent.oldOrderId())) {
            outcomeOrder=order; outcomeStatus=status; outcomeReason=reason;
        }
    }

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

    // ---- Slice C (EngineConfig): engine-creation config replicated via the cluster log ----

    // The recorded EngineConfig — REPLICATED STATE (arrives only as a logged cluster command or
    // from a snapshot that recorded it; never from this node's env). null = legacy mode / not yet
    // configured. Once non-null it is the cluster's declared engine-creation truth and is written
    // into every snapshot (SnapshotCodec trailing block).
    private EngineConfigState engineConfig;

    // RC2 S2: the genesis identity (engine-owned, contract 2.1). Unbound at construction; bound by
    // a FRESH at behaviour >= 2 or filled by a snapshot restore. The journal and the heartbeat
    // hold this reference.
    private final MeIdentity meIdentity = new MeIdentity();

    // RC2 S2: the LAUNCH mode — true only for deferredUntilConfig(). Node-local, never replicated:
    // read by the node-local check (d) only, never by a decision.
    private final boolean configMode;

    // Node-EFFECTIVE creation values, kept for the adopt-path cross-check: what THIS node actually
    // built its engines from. Legacy mode: env-resolved impl + env-resolved capacity (array only).
    // Config mode: copied from the accepted config (cross-check then holds by construction).
    // null/0 on a deferred engine until a config is accepted.
    private String implName;
    private int effectiveBookCapacity;

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
        this.configMode = false;

        logger.info("Engine matching implementation: {}", impl);

        // Initialize all markets from MarketConfig
        for (MarketConfig config : MarketConfig.ALL_MARKETS) {
            MatchingEngine engine = createMatchingEngine(impl, config);
            engines.put(config.marketId, engine);
            logger.info("Initialized {} engine: {} price levels",
                config.symbol, config.getPriceLevels());
        }

        // Slice C: remember the node-effective creation values for the adopt-path cross-check.
        // Reading resolveBookCapacity() again returns exactly what createMatchingEngine used
        // (pure re-read of the same env/prop); for "direct" the capacity knob is unused -> 0.
        this.implName = impl;
        this.effectiveBookCapacity = "array".equals(impl) ? resolveBookCapacity() : 0;

        logger.info("Engine started with {} markets.", MarketConfig.ALL_MARKETS.length);
    }

    // Deferred (config-mode) construction: builds NO engines. Distinct private signature; the
    // boolean is just a disambiguator from Engine(String).
    private Engine(boolean deferred) {
        this.engines = new Int2ObjectHashMap<>();
        this.configMode = true;
        logger.info("Engine deferred: config mode — no engines until an EngineConfig is logged"
                + " (orders before that are loudly REJECTED, never silently dropped)");
    }

    /**
     * Slice C: a config-mode engine — created EMPTY, with engine creation deferred until an
     * {@code EngineConfig} command arrives from the cluster log (or a config-bearing snapshot is
     * restored). Production legacy mode and every existing test keep using {@link #Engine()} —
     * that path is bit-for-bit unchanged.
     */
    public static Engine deferredUntilConfig() {
        return new Engine(true);
    }

    /** RC2 S2: the engine-owned genesis identity (one instance for the engine's lifetime). */
    public MeIdentity meIdentity() {
        return meIdentity;
    }

    /** RC2 S2: true when launched in config mode ({@link #deferredUntilConfig()}); node-local. */
    public boolean isConfigMode() {
        return configMode;
    }

    /** True once matching engines exist (legacy: always from boot; config mode: after accept). */
    public boolean hasEngines() {
        return !engines.isEmpty();
    }

    /** The recorded replicated engine config, or null in legacy mode / before the first config. */
    public EngineConfigState getEngineConfig() {
        return engineConfig;
    }

    /**
     * Record the replicated config WITHOUT touching engines — the adopt path (engines already
     * exist, env/compiled-built) and snapshot restore into a legacy-mode engine. The config
     * becomes the declared truth; whether this node MATCHES it is the caller's cross-check.
     */
    public void setEngineConfig(EngineConfigState config) {
        this.engineConfig = config;
    }

    /**
     * Slice C fresh path: create every matching engine FROM the (already validated) replicated
     * config — impl, capacity, match caps, markets — and record it. Never touches
     * {@code MarketConfig.ALL_MARKETS} and never reads env. Markets are inserted in the config's
     * normalized (marketId-ascending) order, so the engines-map iteration order — which the
     * snapshot byte layout depends on — is identical on every replica and on every
     * fresh-from-snapshot rebuild.
     *
     * @throws IllegalStateException if engines already exist (the adopt path must never recreate)
     */
    public void createEnginesFromConfig(EngineConfigState config) {
        if (!engines.isEmpty()) {
            throw new IllegalStateException(
                    "createEnginesFromConfig on an engine that already has " + engines.size()
                    + " engines — the adopt path records, it never recreates");
        }
        final boolean array = EngineConfigState.IMPL_ARRAY.equals(config.impl);
        for (EngineConfigState.MarketDef m : config.markets) {
            final MatchingEngine engine = array
                    ? new ArrayMatchingEngine(m.minPrice, m.maxPrice, m.tickSize,
                            (int) config.bookCapacity, (int) config.maxMatchesPerOrder)
                    : new DirectMatchingEngine(m.minPrice, m.maxPrice, m.tickSize,
                            (int) config.maxMatchesPerOrder, (int) config.maxOrdersPerLevel);
            engines.put(m.marketId, engine);
            logger.info("Initialized {} engine from EngineConfig (marketId={})", m.symbol, m.marketId);
        }
        this.engineConfig = config;
        this.implName = config.impl;
        this.effectiveBookCapacity = (int) config.bookCapacity;
        logger.info("Engine created {} markets from replicated EngineConfig (configVersion={}, impl={})",
                config.markets.length, config.configVersion, config.impl);
    }

    /** Node-effective implementation name ("array"|"direct") — adopt-path cross-check input. */
    public String getImplName() {
        return implName;
    }

    /** Node-effective book capacity (0 when the impl has no capacity knob) — cross-check input. */
    public int getEffectiveBookCapacity() {
        return effectiveBookCapacity;
    }

    /**
     * Resolve the matching-engine implementation flag.
     * Precedence: -Dmatch.engine.impl, then MATCH_ENGINE_IMPL env var, default "array".
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
        // RC2 S3 (contract 3.2, F-1): the loop runs whatever the sink. tradeId and journalSeq are
        // allocated per match before any sink call, and the replicated maker bookkeeping below runs
        // regardless of the sink, so a sink-less engine keeps the same orderIdToOmsOrderId (an
        // input of the durable owner check, snapshotted) as a sink-attached one.
        if (matchCount == 0) {
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

            final long tradeId = tradeIdGenerator.getAndIncrement();
            final long seq = journalSeqFor();
            if (eventPublisher != null) {
                eventPublisher.publishTradeExecution(
                    marketId, timestamp, tradeId, seq,
                    takerOrderId, takerUserId,
                    makerOrderId, makerUserId,
                    matchPrice, matchQty, takerIsBuy,
                    takerOmsOrderId, makerOmsOrderId,
                    currentLogPosition
                );
            }

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
            publishAmendRejection(marketId, timestamp, oldOrderId, userId,
                newPrice, isBuy, omsOrderId, validity);
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
            publishAmendRejection(marketId, timestamp, oldOrderId, userId,
                newPrice, isBuy, omsOrderId, OrderRejectReason.WOULD_CROSS);
            return;
        }

        // 1. Cancel existing order
        boolean cancelled = engine.cancelOrder(oldOrderId, isBuy);
        if (!cancelled) {
            // Order not found on expected side — reject the update
            publishAmendRejection(marketId, timestamp, oldOrderId, userId,
                newPrice, isBuy, omsOrderId, OrderRejectReason.ORDER_NOT_FOUND);
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
        captureCommandStatus(orderId, orderStatus, rejectReason);
        // RC2 S3: the terminal journal decision lives here. Every FILLED / CANCELLED / REJECTED of
        // this path is journaled (the same expression the publisher used before S3, so behaviour 1
        // is byte-identical) and consumes one journalSeq; NEW and PARTIALLY_FILLED consume none.
        final boolean journalTerminal = orderStatus >= OrderStatusType.FILLED;
        long seq = 0L;
        if (journalTerminal) {
            // Review NOTE-9: guarantee what SettlementJournal.appendTerminal checks BEFORE the
            // allocation, on every member, so a journal-side throw can never halt only the
            // journaling nodes (a dark or sink-less member would continue on another value).
            if (rejectReason < 0 || rejectReason > 254) {
                throw new IllegalStateException("Terminal rejectReason " + rejectReason
                        + " is not a uint8 journal code (0..254)");
            }
            seq = journalSeqFor();
        }
        if (eventPublisher == null) {
            return;
        }
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            orderStatus, remainingQty, filledQty, orderPrice, isBuy, omsOrderId, rejectReason,
            currentLogPosition, journalTerminal, seq
        );
    }

    /**
     * A rejected update is a command outcome, not proof that the referenced order closed.
     * Validation/post-only rejection leaves the old order live. ORDER_NOT_FOUND can also mean
     * the command named the wrong side; even when the order is gone, this command did not close
     * it. Preserve the OMS rejection wire response but never manufacture a settlement terminal.
     * Failures after a successful cancel use publishOrderStatus for the new leg instead.
     */
    private void publishAmendRejection(int marketId, long timestamp, long orderId, long userId,
            long price, boolean isBuy, long omsOrderId, int rejectReason) {
        captureCommandStatus(orderId, OrderStatusType.REJECTED, rejectReason);
        if (eventPublisher == null) {
            return;
        }
        // Not journaled, no journalSeq (RC2 S3 section 4).
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            OrderStatusType.REJECTED, 0, 0, price, isBuy, omsOrderId, rejectReason,
            currentLogPosition, false, 0L
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
        if (applyingIntent != null && orderId == applyingIntent.oldOrderId()) outcomeOldCancelled = true;
        if (eventPublisher == null) {
            return;
        }
        // Not journaled, no journalSeq (RC2 S3 section 4).
        eventPublisher.publishOrderStatusUpdate(
            marketId, timestamp, orderId, userId,
            OrderStatusType.CANCELLED, 0, 0, 0, isBuy, omsOrderId, OrderRejectReason.NONE,
            currentLogPosition, false, 0L
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

    /** RC2 S3: the engine-owned trade-id generator (the next tradeId; snapshot payload offset 8). */
    public long getTradeIdGenerator() {
        return tradeIdGenerator.get();
    }

    /** RC2 S3: snapshot restore only (applied in place by {@code SnapshotCodec.deserialize}). */
    public void setTradeIdGenerator(long value) {
        tradeIdGenerator.set(value);
    }

    /** RC2 S3 X4: equal-intent duplicates suppressed under behaviour 2 (node-local counter). */
    public long suppressedDuplicateCount() {
        return suppressedDuplicateCount;
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
