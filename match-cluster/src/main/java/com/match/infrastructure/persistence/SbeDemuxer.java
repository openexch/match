// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright 2023 Adaptive Financial Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.domain.commands.CancelOrderCommand;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.commands.UpdateOrderCommand;
import com.match.infrastructure.generated.CancelOrderDecoder;
import com.match.infrastructure.generated.CreateOrderDecoder;
import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.UpdateOrderDecoder;
import org.agrona.DirectBuffer;

/**
 * Ultra-low latency SBE demultiplexer.
 * ZERO allocations, ZERO string parsing in hot path.
 */
public class SbeDemuxer {
    private final Engine engine;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    // SBE decoders (reused)
    private final CancelOrderDecoder cancelOrderDecoder = new CancelOrderDecoder();
    private final CreateOrderDecoder createOrderDecoder = new CreateOrderDecoder();
    private final UpdateOrderDecoder updateOrderDecoder = new UpdateOrderDecoder();

    // Pooled command objects (reused, never allocated per message)
    private final CancelOrderCommand cancelCommand = new CancelOrderCommand();
    private final CreateOrderCommand createCommand = new CreateOrderCommand();
    private final UpdateOrderCommand updateCommand = new UpdateOrderCommand();

    // P1.5 diag counter (match#32): CreateOrder commands accepted off ingress.
    // Plain long: written and read on the cluster service thread only.
    private long createOrderCount;

    // A-4/G-2 ingress-reject counters. Plain longs, same discipline as createOrderCount:
    // written only on the cluster service (agent) thread, read by the /metrics scraper via
    // NodeMetrics.acquire(). NOT replicated state and NOT snapshotted — pure local
    // observability. The DROP they record is deterministic: identical bytes are dropped
    // identically on every replica, so consensus is unaffected.
    private long schemaRejectCount;  // G-2: header schemaId/version outside this build's supported range
    private long decodeRejectCount;  // A-4: a body decode threw (e.g. out-of-range enum byte) — poison input, expected
    private long applyErrorCount;    // A-4: an UNEXPECTED exception from the engine/apply path — a bug, not poison input

    // The wire identity every ingress frame must carry. All order-schema messages share the
    // schema id, so a single header gate covers every template. The version gate is a RANGE
    // [MIN_SUPPORTED_SCHEMA_VERSION, EXPECTED_SCHEMA_VERSION], not an exact match: additive
    // schema evolution must let an older-but-supported producer keep flowing during a rolling
    // upgrade instead of forcing a lockstep multi-repo deploy.
    private static final int EXPECTED_SCHEMA_ID = MessageHeaderDecoder.SCHEMA_ID;
    private static final int EXPECTED_SCHEMA_VERSION = MessageHeaderDecoder.SCHEMA_VERSION;

    // Floor of the supported ingress version range. Advances ONLY when pre-v9 producers and
    // pre-v9 frames in any replayable log are provably gone — never as a side effect of a
    // routine schema bump. Package-private so the gate tests track the real constant.
    static final int MIN_SUPPORTED_SCHEMA_VERSION = 9;

    // Loud-log the first reject of each kind, then one in every REJECT_LOG_INTERVAL, so a
    // storm of poison frames cannot flood the log. Mirrors EgressSessionMetrics.shouldLog.
    private static final long REJECT_LOG_INTERVAL = 1000L;

    private static boolean shouldLogReject(final long tally) {
        return tally == 1 || tally % REJECT_LOG_INTERVAL == 0;
    }

    /** P1.2 (match#31): receives logged RequestOpenOrdersSnapshot commands. */
    public interface OpenOrdersSnapshotRequestHandler {
        void onOpenOrdersSnapshotRequest(long requestId);
    }

    private final com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder
            requestOpenOrdersDecoder = new com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder();
    private OpenOrdersSnapshotRequestHandler openOrdersSnapshotRequestHandler;

    public void setOpenOrdersSnapshotRequestHandler(OpenOrdersSnapshotRequestHandler handler) {
        this.openOrdersSnapshotRequestHandler = handler;
    }

    // ---- Slice C: EngineConfig ingress + fresh-cluster order guard ----

    /** Slice C: receives logged EngineConfig commands, decoded and normalized (markets sorted). */
    public interface EngineConfigHandler {
        void onEngineConfig(com.match.application.engine.EngineConfigState config);
    }

    /**
     * Slice C fresh-cluster guard: a CreateOrder arrived BEFORE any EngineConfig (config-mode
     * cluster, no engines yet). The service emits the loud deterministic REJECTED egress —
     * orderId=0 + the command's omsOrderId so the OMS hold releases.
     */
    public interface PreConfigOrderRejectHandler {
        void onPreConfigOrderReject(int marketId, long userId, long omsOrderId, boolean isBuy,
                                    long timestamp);
    }

    private final com.match.infrastructure.generated.EngineConfigDecoder engineConfigDecoder =
            new com.match.infrastructure.generated.EngineConfigDecoder();
    private EngineConfigHandler engineConfigHandler;
    private PreConfigOrderRejectHandler preConfigOrderRejectHandler;

    // Slice C: order commands dropped by the fresh-cluster guard (no engines yet — config-mode
    // cluster before its EngineConfig). Deterministic: every replica sees the same empty engine
    // set at the same log position. Legacy mode can never bump this (engines exist from boot).
    private long preConfigOrderRejectCount;

    public void setEngineConfigHandler(EngineConfigHandler handler) {
        this.engineConfigHandler = handler;
    }

    public void setPreConfigOrderRejectHandler(PreConfigOrderRejectHandler handler) {
        this.preConfigOrderRejectHandler = handler;
    }

    public SbeDemuxer(Engine engine) {
        this.engine = engine;
    }

    /**
     * Dispatch a message to the appropriate handler.
     * ZERO allocations, direct primitive access.
     */
    public void dispatch(final DirectBuffer buffer, final int offset, final int length, final long timestamp) {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH) {
            return;
        }

        headerDecoder.wrap(buffer, offset);

        // G-2: schema/version gate. A frame whose header is outside the supported range is
        // dropped BEFORE any body decode — no generated accessor runs, so a stale or skewed
        // frame can never reach an out-of-range enum. The version check is a RANGE, not an
        // exact match: additive schema evolution must let an older-but-supported producer keep
        // flowing during a rolling upgrade; anything below the floor (pre-range history) or
        // above this build's ceiling (fields this build cannot decode) is still dropped here.
        final int schemaId = headerDecoder.schemaId();
        final int version = headerDecoder.version();
        if (schemaId != EXPECTED_SCHEMA_ID
                || version < MIN_SUPPORTED_SCHEMA_VERSION
                || version > EXPECTED_SCHEMA_VERSION) {
            final long n = ++schemaRejectCount;
            if (shouldLogReject(n)) {
                System.err.println("INGRESS DROP (schema): dropping frame schemaId=" + schemaId
                        + " version=" + version + " templateId=" + headerDecoder.templateId()
                        + " — this build speaks schemaId=" + EXPECTED_SCHEMA_ID + " versions "
                        + MIN_SUPPORTED_SCHEMA_VERSION + ".." + EXPECTED_SCHEMA_VERSION
                        + "; schemaRejects=" + n);
            }
            return;
        }

        // A-4: non-throwing body decode. The generated enum accessors OrderSide.get()/
        // OrderType.get() throw IllegalArgumentException on an out-of-range byte; that
        // exception used to escape to Aeron and, on repeat, trip match's
        // IDENTICAL_ERROR_EXIT_THRESHOLD halt(2), crash-looping the node on replay. Now ANY
        // decode failure is counted, loud-logged, and the frame is dropped deterministically.
        // Never rethrown: nothing reaches Aeron. No egress here — a REJECTED reply to OMS is a
        // separate follow-up; this step only drops+counts+logs. Zero-alloc on the happy path
        // (try/catch is free when nothing throws; the catch body is cold).
        try {
            switch (headerDecoder.templateId()) {
                case CreateOrderDecoder.TEMPLATE_ID:
                    // Slice C fresh-cluster guard: no engines yet (config-mode cluster before its
                    // EngineConfig) -> loud deterministic REJECT, never a silent drop / NPE.
                    // Legacy mode never takes this branch — its engines exist from boot.
                    if (!engine.hasEngines()) {
                        handlePreConfigCreateOrder(buffer, offset, timestamp);
                        break;
                    }
                    handleCreateOrder(buffer, offset, timestamp);
                    break;

                case CancelOrderDecoder.TEMPLATE_ID:
                    if (!engine.hasEngines()) {
                        handlePreConfigCancelOrUpdate("CancelOrder");
                        break;
                    }
                    handleCancelOrder(buffer, offset, timestamp);
                    break;

                case UpdateOrderDecoder.TEMPLATE_ID:
                    if (!engine.hasEngines()) {
                        handlePreConfigCancelOrUpdate("UpdateOrder");
                        break;
                    }
                    handleUpdateOrder(buffer, offset, timestamp);
                    break;

                case com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.TEMPLATE_ID:
                    // P1.2 (match#31): logged command; deterministic across replicas
                    // (no state mutation — only the leader emits the egress reply).
                    requestOpenOrdersDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                    if (openOrdersSnapshotRequestHandler != null) {
                        openOrdersSnapshotRequestHandler.onOpenOrdersSnapshotRequest(
                                requestOpenOrdersDecoder.requestId());
                    }
                    break;

                case com.match.infrastructure.generated.EngineConfigDecoder.TEMPLATE_ID:
                    // Slice C: the engine-creation config as a logged command. Cold path —
                    // allocation is fine. Decode failures (poison enum byte, truncation) fall
                    // into the SAME non-throwing catch blocks below as every other template
                    // (the #202 pattern): counted, loud-logged, dropped — never rethrown.
                    handleEngineConfig(buffer, offset);
                    break;

                default:
                    // Unknown message - ignore in hot path
                    break;
            }
        } catch (final IllegalArgumentException | IndexOutOfBoundsException e) {
            // Expected DECODE failure: a poison/truncated frame. IllegalArgumentException is what
            // the generated enum accessors throw on an out-of-range byte; IndexOutOfBounds is a
            // short/garbled frame read past its bounds. Rate-limited — a poison storm must not
            // flood the log.
            final long n = ++decodeRejectCount;
            if (shouldLogReject(n)) {
                System.err.println("INGRESS DROP (decode): dropping templateId="
                        + headerDecoder.templateId() + " — " + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + "; decodeRejects=" + n);
            }
            // Deterministic drop: no rethrow, no egress. The consensus thread survives.
        } catch (final Exception e) {
            // UNEXPECTED: the engine/apply path threw, not the SBE decode. This is a bug (or a
            // corrupt-state symptom), not poison input, so it is counted APART from decode drops —
            // a real engine fault must never hide in poison-frame noise — and logged EVERY time
            // (never rate-limited; this should be ~always zero). Still not rethrown (A-4: a rethrow
            // feeds match's 200-identical-error halt and crash-loops the node on replay). The
            // deterministic drop keeps replicas consistent; the loud, distinct signal is how an
            // operator learns the engine faulted. A controlled halt-vs-continue policy for engine
            // faults is the separate C-6 work (Faz 2).
            final long n = ++applyErrorCount;
            System.err.println("INGRESS APPLY ERROR (engine threw, frame dropped): templateId="
                    + headerDecoder.templateId() + " — " + e.getClass().getName()
                    + ": " + e.getMessage() + "; applyErrors=" + n);
        }
    }

    public long createOrderCount() {
        return createOrderCount;
    }

    /** Scrapeable (match_ingress_schema_rejects_total): frames dropped for a schemaId mismatch or
     *  a version outside the supported range, before any body decode (G-2). */
    public long schemaRejectCount() {
        return schemaRejectCount;
    }

    /** Scrapeable (match_ingress_decode_rejects_total): frames dropped for a body-decode failure,
     *  e.g. an out-of-range enum byte (A-4). Expected under poison/skewed input. */
    public long decodeRejectCount() {
        return decodeRejectCount;
    }

    /** Scrapeable (match_ingress_apply_errors_total): frames dropped because the engine/apply path
     *  threw an UNEXPECTED exception (a bug, not poison input). Should be ~always zero; any increment
     *  warrants investigation. Also bumped by {@link #recordDispatchEscape()}. */
    public long applyErrorCount() {
        return applyErrorCount;
    }

    /**
     * Defensive hook for {@code AppClusteredService.onSessionMessage}: called only if an Exception
     * somehow escapes {@link #dispatch} (it should not — dispatch drops decode failures internally).
     * An escape is by definition unexpected, so it counts as an apply-error (not a decode drop) to
     * stay scrapeable and distinct. Agent-thread only.
     */
    public void recordDispatchEscape() {
        applyErrorCount++;
    }

    /** Scrapeable (match_preconfig_order_rejects_total): order commands dropped by the slice C
     *  fresh-cluster guard — arrived before any EngineConfig, so no engine existed to serve them.
     *  Always 0 in legacy mode. */
    public long preConfigOrderRejectCount() {
        return preConfigOrderRejectCount;
    }

    /**
     * Slice C fresh-cluster guard, CreateOrder leg: decode just enough of the command to emit the
     * deterministic REJECTED egress (orderId=0 + the command's omsOrderId so the OMS hold
     * releases), count it, log loudly (rate-limited like every ingress drop). The engine is never
     * touched — there is no engine.
     */
    private void handlePreConfigCreateOrder(DirectBuffer buffer, int offset, long timestamp) {
        createOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        final int marketId = createOrderDecoder.marketId();
        final long userId = createOrderDecoder.userId();
        final long omsOrderId = createOrderDecoder.omsOrderId();
        final boolean isBuy =
                createOrderDecoder.orderSide() == com.match.infrastructure.generated.OrderSide.BID;

        final long n = ++preConfigOrderRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS REJECT (pre-config): CreateOrder before any EngineConfig —"
                    + " no engine exists yet on this config-mode cluster. REJECTED egress emitted"
                    + " (orderId=0, omsOrderId=" + omsOrderId + ", marketId=" + marketId
                    + ", userId=" + userId + "); preConfigOrderRejects=" + n);
        }
        if (preConfigOrderRejectHandler != null) {
            preConfigOrderRejectHandler.onPreConfigOrderReject(marketId, userId, omsOrderId, isBuy,
                    timestamp);
        }
    }

    /**
     * Slice C fresh-cluster guard, Cancel/Update leg: with no engines there is nothing to cancel
     * or amend and — unlike CreateOrder — the wire carries no omsOrderId, so there is no hold to
     * release and no meaningful status to address. Count + loud log; deterministic drop.
     */
    private void handlePreConfigCancelOrUpdate(String what) {
        final long n = ++preConfigOrderRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS REJECT (pre-config): " + what + " before any EngineConfig —"
                    + " no engine exists yet, nothing to cancel/amend (the wire carries no omsOrderId"
                    + " for this template, so no status egress is addressable); preConfigOrderRejects=" + n);
        }
    }

    /**
     * Slice C: decode a logged EngineConfig command into a normalized {@link
     * com.match.application.engine.EngineConfigState} (markets sorted by marketId) and hand it to
     * the state machine. Wire-level poison (an out-of-range EngineImpl byte, truncation) throws
     * out of the generated accessors and is caught by dispatch's existing A-4/#202 catch blocks;
     * SEMANTIC validity (capacity ranges, duplicate markets, price bands) is the state machine's
     * deterministic reject, so it is counted apart from decode drops.
     */
    private void handleEngineConfig(DirectBuffer buffer, int offset) {
        engineConfigDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        final long configVersion = engineConfigDecoder.configVersion();
        final String impl = toEngineImplName(engineConfigDecoder.impl());
        final long bookCapacity = engineConfigDecoder.bookCapacity();
        final long maxMatchesPerOrder = engineConfigDecoder.maxMatchesPerOrder();
        final long maxOrdersPerLevel = engineConfigDecoder.maxOrdersPerLevel();

        final java.util.ArrayList<com.match.application.engine.EngineConfigState.MarketDef> defs =
                new java.util.ArrayList<>();
        for (final com.match.infrastructure.generated.EngineConfigDecoder.MarketsDecoder m
                : engineConfigDecoder.markets()) {
            defs.add(new com.match.application.engine.EngineConfigState.MarketDef(
                    m.marketId(), m.symbol(), m.minPrice(), m.maxPrice(), m.tickSize()));
        }

        if (engineConfigHandler != null) {
            engineConfigHandler.onEngineConfig(com.match.application.engine.EngineConfigState.of(
                    configVersion, impl, bookCapacity, maxMatchesPerOrder, maxOrdersPerLevel,
                    defs.toArray(new com.match.application.engine.EngineConfigState.MarketDef[0])));
        }
    }

    /** SBE EngineImpl -> the engine's impl vocabulary; null for NULL_VAL (validation rejects it). */
    private static String toEngineImplName(com.match.infrastructure.generated.EngineImpl impl) {
        switch (impl) {
            case ARRAY:
                return com.match.application.engine.EngineConfigState.IMPL_ARRAY;
            case DIRECT:
                return com.match.application.engine.EngineConfigState.IMPL_DIRECT;
            default:
                return null;
        }
    }

    private void handleCreateOrder(DirectBuffer buffer, int offset, long timestamp) {
        createOrderCount++;
        createOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing, NO allocations
        createCommand.reset();
        createCommand.setUserId(createOrderDecoder.userId());
        createCommand.setQuantity(createOrderDecoder.quantity());
        createCommand.setOrderSide(toDomainOrderSide(createOrderDecoder.orderSide()));
        final com.match.domain.enums.OrderType orderType = toDomainOrderType(createOrderDecoder.orderType());
        createCommand.setOrderType(orderType);
        // SBE v8: the wire has no totalPrice — a MARKET buy's spend budget rides in the
        // price field. Internally the budget stays on the command's totalPrice field
        // (Engine reads cmd.getTotalPrice() for MARKET buys), and the command's price
        // stays 0 for MARKET so status egress publishes price=0 exactly as before v8.
        final long price = createOrderDecoder.price();
        if (orderType == com.match.domain.enums.OrderType.MARKET) {
            createCommand.setPrice(0L);
            createCommand.setTotalPrice(price);
        } else {
            createCommand.setPrice(price);
            createCommand.setTotalPrice(0L);
        }
        createCommand.setOmsOrderId(createOrderDecoder.omsOrderId());

        int marketId = createOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_CREATE, createCommand, timestamp);
    }

    private void handleCancelOrder(DirectBuffer buffer, int offset, long timestamp) {
        cancelOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing
        cancelCommand.reset();
        cancelCommand.setUserId(cancelOrderDecoder.userId());
        cancelCommand.setOrderId(cancelOrderDecoder.orderId());

        int marketId = cancelOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_CANCEL, cancelCommand, timestamp);
    }

    private void handleUpdateOrder(DirectBuffer buffer, int offset, long timestamp) {
        updateOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing
        updateCommand.reset();
        updateCommand.setUserId(updateOrderDecoder.userId());
        updateCommand.setOrderId(updateOrderDecoder.orderId());
        updateCommand.setPrice(updateOrderDecoder.price());
        updateCommand.setQuantity(updateOrderDecoder.quantity());
        updateCommand.setOrderSide(toDomainOrderSide(updateOrderDecoder.orderSide()));
        updateCommand.setOrderType(toDomainOrderType(updateOrderDecoder.orderType()));

        int marketId = updateOrderDecoder.marketId();
        engine.acceptOrder(marketId, Engine.CMD_UPDATE, updateCommand, timestamp);
    }

    private com.match.domain.enums.OrderType toDomainOrderType(com.match.infrastructure.generated.OrderType sbeOrderType) {
        switch (sbeOrderType) {
            case LIMIT:
                return com.match.domain.enums.OrderType.LIMIT;
            case MARKET:
                return com.match.domain.enums.OrderType.MARKET;
            case LIMIT_MAKER:
                return com.match.domain.enums.OrderType.LIMIT_MAKER;
            default:
                return com.match.domain.enums.OrderType.LIMIT;
        }
    }

    private com.match.domain.enums.OrderSide toDomainOrderSide(com.match.infrastructure.generated.OrderSide sbeOrderSide) {
        switch (sbeOrderSide) {
            case BID:
                return com.match.domain.enums.OrderSide.BID;
            case ASK:
                return com.match.domain.enums.OrderSide.ASK;
            default:
                return com.match.domain.enums.OrderSide.BID;
        }
    }
}
