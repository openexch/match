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
import org.agrona.concurrent.UnsafeBuffer;
import java.nio.ByteOrder;

/**
 * Ultra-low latency SBE demultiplexer.
 * ZERO allocations, ZERO string parsing in hot path.
 */
public class SbeDemuxer {
    private final Engine engine;
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final UnsafeBuffer frame = new UnsafeBuffer();

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
    private long applyErrorCount;
    // RC2 S1: frames of a template this build decodes for bounds and class but does not serve yet
    // (order-schema v12 ReleaseCommandIds 10, served from S4). Counted, never applied. Same
    // discipline as the reject counters: service thread only, not replicated, not snapshotted.
    private long unservedTemplateCount;
    // RC2 S2 section 6 (W10): frames above the RECORDED wire ceiling (behaviour >= 2 only). A
    // template 9 whose v12 prefix is a valid intent is journaled INVALID (record 3 result 8);
    // every other template is a counted drop. Same discipline as the reject counters.
    private long aboveCeilingInvalidCount;
    private long aboveCeilingDropCount;
    // RC2 S4 (contract 5.3, decision A2): legacy templates 1/2/3 refused at behaviour 2. Same
    // discipline as the reject counters (service thread only, not replicated).
    private long legacyLaneRejectCount;
    // RC2 S6 (contract 3.2): frames refused by the session-class rows R1-R4. Same discipline.
    private long unauthorizedRejectCount;

    // RC2 S4 section 8: pooled template-9 and template-10 decoders, the reusable intent view and
    // the release entry scratch (S1 cap 48), so the behaviour-2 durable path allocates nothing.
    private final com.match.infrastructure.generated.DurableOrderCommandDecoder durableDecoder =
            new com.match.infrastructure.generated.DurableOrderCommandDecoder();
    private final com.match.infrastructure.generated.ReleaseCommandIdsDecoder releaseDecoder =
            new com.match.infrastructure.generated.ReleaseCommandIdsDecoder();
    private final com.match.domain.commands.MutableDurableIntent durableView =
            new com.match.domain.commands.MutableDurableIntent();
    /** RC2 S4 (S1 3.2 cap): a template-10 frame with more entries is a frame-bounds reject. */
    static final int MAX_RELEASE_ENTRIES = Engine.MAX_RELEASE_ENTRIES;
    private final long[] releaseHigh = new long[MAX_RELEASE_ENTRIES];
    private final long[] releaseFloor = new long[MAX_RELEASE_ENTRIES];
    private final int[] releaseScope = new int[MAX_RELEASE_ENTRIES];
    // Service-thread only. Once a command may have partially applied, this instance cannot
    // establish another applied prefix. Decode-only poison never enters this state.
    private boolean applying;
    private boolean applyFailed;

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

    // First header version whose root blocks carry the v12 appended fields (RC2 S1 contract 3).
    static final int V12 = 12;
    // Pre-12 root blocks of the two templates that grew in v12. The generated BLOCK_LENGTH
    // constants are the v12 values (DurableOrderCommand 80, EngineConfig 35).
    static final int DURABLE_ORDER_COMMAND_PRE_V12_BLOCK_LENGTH = 71;
    static final int ENGINE_CONFIG_PRE_V12_BLOCK_LENGTH = 17;

    /**
     * Minimum root block per (template, header version): the frame-bounds rule of the RC2 S1
     * contract section 3. Templates 9 and 8 grew in v12, so their minimum depends on the header
     * version (9: 71 for v9..v11, 80 for v12; 8: 17 for v9..v11, 35 for v12): a v12 header
     * carrying a pre-12 block is a frame-bounds reject, and a pre-12 frame is never held to the
     * v12 length. Every other template's fixed prefix is identical across the supported range.
     * 0 for a template this build does not decode.
     */
    static int minimumBlockLength(final int templateId, final int version) {
        switch (templateId) {
            case com.match.infrastructure.generated.DurableOrderCommandDecoder.TEMPLATE_ID:
                return version >= V12
                        ? com.match.infrastructure.generated.DurableOrderCommandDecoder.BLOCK_LENGTH
                        : DURABLE_ORDER_COMMAND_PRE_V12_BLOCK_LENGTH;
            case com.match.infrastructure.generated.EngineConfigDecoder.TEMPLATE_ID:
                return version >= V12
                        ? com.match.infrastructure.generated.EngineConfigDecoder.BLOCK_LENGTH
                        : ENGINE_CONFIG_PRE_V12_BLOCK_LENGTH;
            case com.match.infrastructure.generated.ReleaseCommandIdsDecoder.TEMPLATE_ID:
                return com.match.infrastructure.generated.ReleaseCommandIdsDecoder.BLOCK_LENGTH;
            case CreateOrderDecoder.TEMPLATE_ID:
                return CreateOrderDecoder.BLOCK_LENGTH;
            case CancelOrderDecoder.TEMPLATE_ID:
                return CancelOrderDecoder.BLOCK_LENGTH;
            case UpdateOrderDecoder.TEMPLATE_ID:
                return UpdateOrderDecoder.BLOCK_LENGTH;
            case com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.TEMPLATE_ID:
                return com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.BLOCK_LENGTH;
            default:
                return 0;
        }
    }

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

    // The highest header version this jar accepts: EXPECTED_SCHEMA_VERSION in production. A
    // field, not the constant, only so a test can stand in for a later jar (RC2 S2 review FIX-1:
    // the ceiling rule must not depend on it).
    private final int maxSupportedVersion;

    public SbeDemuxer(Engine engine) {
        this(engine, EXPECTED_SCHEMA_VERSION);
    }

    /** Test seam: a demuxer that accepts header versions up to {@code maxSupportedVersion}. */
    SbeDemuxer(Engine engine, int maxSupportedVersion) {
        this.engine = engine;
        this.maxSupportedVersion = maxSupportedVersion;
    }

    /**
     * RC2 S6 (contract 3.1): the session-class code of a trusted in-process caller. Passes every class row
     * (R1-R4); never a wire class. For in-process tests and tools only, as the AE's {@code dispatchTrusted}.
     */
    static final int TRUSTED = -1;

    /**
     * Dispatch a frame from a session of class {@code classCode} (a {@code ClusterSessionClass} code read from
     * the session's replicated principal). The service halts on class 0 before it calls this (R0, contract
     * 3.2); a class-0 frame that reaches the demuxer anyway is refused like an unauthorized one.
     */
    public void dispatch(final int classCode, final DirectBuffer buffer, final int offset, final int length,
                         final long timestamp) {
        dispatch(classCode, -1L, buffer, offset, length, timestamp);
    }

    /** As {@link #dispatch(int, DirectBuffer, int, int, long)}; {@code clusterSessionId} only names the session in logs. */
    public void dispatch(final int classCode, final long clusterSessionId, final DirectBuffer buffer,
                         final int offset, final int length, final long timestamp) {
        if (classCode == TRUSTED) {
            throw new IllegalArgumentException("TRUSTED is not a wire class; use dispatchTrusted");
        }
        dispatchAs(classCode, clusterSessionId, buffer, offset, length, timestamp);
    }

    /**
     * In-process dispatch that bypasses the class rows R1-R4 (every other gate unchanged). For tests and tools
     * that hold no ClientSession; never reached from the log.
     */
    public void dispatchTrusted(final DirectBuffer buffer, final int offset, final int length, final long timestamp) {
        dispatchAs(TRUSTED, -1L, buffer, offset, length, timestamp);
    }

    /**
     * Dispatch a message to the appropriate handler.
     * ZERO allocations, direct primitive access.
     */
    private void dispatchAs(final int classCode, final long clusterSessionId, final DirectBuffer buffer,
                            final int offset, final int length, final long timestamp) {
        if (applyFailed) {
            throw new IllegalStateException("Ingress application already failed; restart required");
        }
        if (offset < 0 || length < MessageHeaderDecoder.ENCODED_LENGTH
                || offset > buffer.capacity() || length > buffer.capacity() - offset) {
            rejectFrameBounds();
            return;
        }

        frame.wrap(buffer, offset, length);
        headerDecoder.wrap(frame, 0);

        // G-2: schema/version gate. A frame whose header is outside the supported range is
        // dropped BEFORE any body decode — no generated accessor runs, so a stale or skewed
        // frame can never reach an out-of-range enum. The version check is a RANGE, not an
        // exact match: additive schema evolution must let an older-but-supported producer keep
        // flowing during a rolling upgrade; anything below the floor (pre-range history) or
        // above this build's ceiling (fields this build cannot decode) is still dropped here.
        final int schemaId = headerDecoder.schemaId();
        final int version = headerDecoder.version();
        // RC2 S2 section 6: with a recorded behaviour >= 2 config, a frame above the RECORDED
        // ceiling is decided from the header and the replicated ceiling only, never by this
        // jar's supported version, so every member journals the same record at the same
        // position whatever its jar. It runs ahead of the version drop below: a later jar whose
        // supported version exceeds the recorded ceiling must still journal INVALID, exactly
        // like the members still at the ceiling (review FIX-1). Cost on the hot path: one field
        // read and a null check (no config recorded) or a behaviour compare.
        boolean aboveCeiling = false;
        if (schemaId == EXPECTED_SCHEMA_ID && aboveRecordedCeiling(version)) {
            if (headerDecoder.templateId()
                    != com.match.infrastructure.generated.DurableOrderCommandDecoder.TEMPLATE_ID) {
                final long n = ++aboveCeilingDropCount;
                if (shouldLogReject(n)) {
                    System.err.println("INGRESS DROP (above ceiling): templateId=" + headerDecoder.templateId()
                            + " version=" + version + " is above the recorded maxWireVersion="
                            + engine.getEngineConfig().maxWireVersion + "; aboveCeilingDrops=" + n);
                }
                return;
            }
            aboveCeiling = true;
        }
        if (!aboveCeiling && (schemaId != EXPECTED_SCHEMA_ID
                || version < MIN_SUPPORTED_SCHEMA_VERSION
                || version > maxSupportedVersion)) {
            final long n = ++schemaRejectCount;
            if (shouldLogReject(n)) {
                System.err.println("INGRESS DROP (schema): dropping frame schemaId=" + schemaId
                        + " version=" + version + " templateId=" + headerDecoder.templateId()
                        + " — this build speaks schemaId=" + EXPECTED_SCHEMA_ID + " versions "
                        + MIN_SUPPORTED_SCHEMA_VERSION + ".." + maxSupportedVersion
                        + "; schemaRejects=" + n);
            }
            return;
        }

        // Check the declared frame, not the backing receive buffer (which can contain old
        // commands beyond length). Explicit checks remain effective with Agrona bounds checks
        // disabled. The minimum root block is a function of (template, header version): templates
        // 9 and 8 grew in v12, every other known prefix is identical across versions 9..12
        // (minimumBlockLength, RC2 S1 contract section 3 frame-bounds rule).
        final int block = headerDecoder.blockLength();
        // An above-ceiling template 9 is held to the v12 minimum (80): SBE is append-only, so the
        // first 80 root bytes keep their v12 meaning in every later version.
        final int minimum = minimumBlockLength(headerDecoder.templateId(), aboveCeiling ? V12 : version);
        if (block < minimum || block > length - MessageHeaderDecoder.ENCODED_LENGTH) {
            rejectFrameBounds();
            return;
        }
        // Group bounds. The group header sits after the frame's OWN root block, so a v11
        // EngineConfig's group is found at 8 + 17 and a v12 one's at 8 + 35.
        final int groupEntryMinimum = switch (headerDecoder.templateId()) {
            case com.match.infrastructure.generated.EngineConfigDecoder.TEMPLATE_ID ->
                    com.match.infrastructure.generated.EngineConfigDecoder.MarketsDecoder.sbeBlockLength();
            case com.match.infrastructure.generated.ReleaseCommandIdsDecoder.TEMPLATE_ID ->
                    com.match.infrastructure.generated.ReleaseCommandIdsDecoder.ReleasesDecoder.sbeBlockLength();
            default -> 0;
        };
        if (groupEntryMinimum > 0) {
            final int groupOffset = MessageHeaderDecoder.ENCODED_LENGTH + block;
            if (length - groupOffset < 4) {
                rejectFrameBounds();
                return;
            }
            final int groupBlock = Short.toUnsignedInt(frame.getShort(groupOffset, ByteOrder.LITTLE_ENDIAN));
            final int count = Short.toUnsignedInt(frame.getShort(groupOffset + 2, ByteOrder.LITTLE_ENDIAN));
            if (groupBlock < groupEntryMinimum
                    || (long) groupBlock * count > length - groupOffset - 4) {
                rejectFrameBounds();
                return;
            }
        }

        // RC2 S6 (contract 3.2 R1-R4): the session class decides, after the header, schema, ceiling-drop
        // and frame/group-bounds gates and before any body decode, so a refused frame mutates nothing,
        // journals nothing (no above-ceiling INVALID either), consumes no journalSeq and sends no egress.
        // Reads the replicated principal and the recorded behaviour only: every member decides alike.
        if (classCode != TRUSTED && !authorised(classCode, headerDecoder.templateId())) {
            refuseUnauthorized(classCode, clusterSessionId);
            return;
        }

        // A-4: non-throwing body decode. The generated enum accessors OrderSide.get()/
        // OrderType.get() throw IllegalArgumentException on an out-of-range byte; that
        // exception used to escape to Aeron and, on repeat, trip match's
        // IDENTICAL_ERROR_EXIT_THRESHOLD halt(2), crash-looping the node on replay. Now ANY
        // decode failure is counted, loud-logged, and the frame is dropped deterministically.
        // Decode failures before application are dropped. Unexpected apply failures escape to
        // the service's immediate failure/exit fence; they must not be counted as poison input.
        // No allocations are introduced on the normal dispatch path.
        try {
            switch (headerDecoder.templateId()) {
                case com.match.infrastructure.generated.DurableOrderCommandDecoder.TEMPLATE_ID:
                    if (version < 11) { rejectFrameBounds(); break; }
                    // RC2 S2 section 13 addition: the fresh-cluster guard covers template 9 like
                    // the other order commands. Before any EngineConfig (config mode, no engines)
                    // there is no epoch and no publisher: counted drop on every replica, nothing
                    // journaled, nothing applied, never an apply failure.
                    if (!engine.hasEngines()) {
                        handlePreConfigDrop("DurableOrderCommand",
                                "no epoch is bound yet, so nothing is journaled; the core command stays pending");
                        break;
                    }
                    // The generated decoder reads with the frame's own blockLength and version;
                    // an above-ceiling frame (version > 12) decodes its v12 prefix exactly like a
                    // v12 frame, with the same identity and range checks (F1 included).
                    // RC2 S4 section 8: one pooled decoder (wrap resets every cursor).
                    final var d = durableDecoder.wrapAndApplyHeader(frame,0,headerDecoder);
                    // Null before range (RC2 S1 contract section 3): a pre-12 header maps the two v12
                    // fields to 0/0 and runs NO range check (the decoder would return the SBE null);
                    // a v12 header reads them and range-checks (poison = decode reject below).
                    final int timeInForce;
                    final long expectedOldRemaining;
                    if (version < V12) {
                        timeInForce = 0;
                        expectedOldRemaining = 0L;
                    } else {
                        timeInForce = d.timeInForce();
                        if (timeInForce > 2) {
                            throw new IllegalArgumentException("timeInForce " + timeInForce);
                        }
                        expectedOldRemaining = d.expectedOldRemaining();
                        if (expectedOldRemaining < 0) {
                            throw new IllegalArgumentException("expectedOldRemaining " + expectedOldRemaining);
                        }
                    }
                    if (engine.isJournalSeqDense()) {
                        dispatchDurableBehaviourTwo(d, version, timeInForce, expectedOldRemaining,
                                aboveCeiling, timestamp);
                        break;
                    }
                    var intent = new com.match.domain.commands.DurableOrderIntent(d.commandIdHigh(),d.commandIdLow(),
                        d.userId(),d.omsOrderId(),d.oldOrderId(),d.price(),d.quantity(),d.budget(),d.marketId(),
                        d.commandKind(),d.orderType(),d.orderSide(),timeInForce,expectedOldRemaining);
                    applying = true;
                    if (aboveCeiling) {
                        // Record 3 result 8: no capacity, no ledger, no apply (contract 6).
                        engine.journalInvalid(intent, timestamp);
                        final long n = ++aboveCeilingInvalidCount;
                        if (shouldLogReject(n)) {
                            System.err.println("INGRESS INVALID (above ceiling): DurableOrderCommand version="
                                    + version + " above the recorded maxWireVersion="
                                    + engine.getEngineConfig().maxWireVersion
                                    + " journaled as INVALID; aboveCeilingInvalid=" + n);
                        }
                        break;
                    }
                    engine.acceptDurable(intent,timestamp);
                    break;
                case CreateOrderDecoder.TEMPLATE_ID:
                    // Slice C fresh-cluster guard: no engines yet (config-mode cluster before its
                    // EngineConfig) -> loud deterministic REJECT, never a silent drop / NPE.
                    // Legacy mode never takes this branch — its engines exist from boot.
                    if (!engine.hasEngines()) {
                        handlePreConfigCreateOrder(frame, 0, timestamp);
                        break;
                    }
                    if (engine.isJournalSeqDense()) {
                        refuseLegacyLane();
                        break;
                    }
                    handleCreateOrder(frame, 0, timestamp);
                    break;

                case CancelOrderDecoder.TEMPLATE_ID:
                    if (!engine.hasEngines()) {
                        handlePreConfigCancelOrUpdate("CancelOrder");
                        break;
                    }
                    if (engine.isJournalSeqDense()) {
                        refuseLegacyLane();
                        break;
                    }
                    handleCancelOrder(frame, 0, timestamp);
                    break;

                case UpdateOrderDecoder.TEMPLATE_ID:
                    if (!engine.hasEngines()) {
                        handlePreConfigCancelOrUpdate("UpdateOrder");
                        break;
                    }
                    if (engine.isJournalSeqDense()) {
                        refuseLegacyLane();
                        break;
                    }
                    handleUpdateOrder(frame, 0, timestamp);
                    break;

                case com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.TEMPLATE_ID:
                    // P1.2 (match#31): logged command; deterministic across replicas
                    // (no state mutation — only the leader emits the egress reply).
                    requestOpenOrdersDecoder.wrapAndApplyHeader(frame, 0, headerDecoder);
                    if (openOrdersSnapshotRequestHandler != null) {
                        final long requestId = requestOpenOrdersDecoder.requestId();
                        applying = true;
                        openOrdersSnapshotRequestHandler.onOpenOrdersSnapshotRequest(requestId);
                    }
                    break;

                case com.match.infrastructure.generated.EngineConfigDecoder.TEMPLATE_ID:
                    // Slice C: the engine-creation config as a logged command. Cold path —
                    // allocation is fine. Decode failures (poison enum byte, truncation) fall
                    // into the SAME non-throwing catch blocks below as every other template
                    // (the #202 pattern): counted, loud-logged, dropped — never rethrown.
                    handleEngineConfig(frame, 0, version);
                    break;

                case com.match.infrastructure.generated.ReleaseCommandIdsDecoder.TEMPLATE_ID:
                    // RC2 S1: defined and bounds-checked above. RC2 S4 (contract 4.1): served only
                    // with engines and a recorded behaviour >= 2 (identity bound); otherwise the S1
                    // counted unserved drop (ledger untouched, no egress, no journal).
                    if (!engine.hasEngines() || !engine.isJournalSeqDense()) {
                        countUnserved();
                        break;
                    }
                    dispatchRelease(timestamp);
                    break;

                default:
                    // Unknown message - ignore in hot path
                    break;
            }
        } catch (final RuntimeException | Error failure) {
            if (!applying && (failure instanceof IllegalArgumentException
                    || failure instanceof IndexOutOfBoundsException)) {
                // Malformed input was rejected BEFORE any application callback. Dropping it is
                // deterministic and safe; replay must not enter a poison-message crash loop.
                final long n = ++decodeRejectCount;
                if (shouldLogReject(n)) {
                    System.err.println("INGRESS DROP (decode): templateId=" + headerDecoder.templateId()
                            + " type=" + failure.getClass().getSimpleName() + "; decodeRejects=" + n);
                }
            } else {
                // Even IllegalArgumentException/IndexOutOfBoundsException can come from a
                // publisher or mutated engine. Never relabel those as malformed wire input.
                applyFailed = true;
                final long n = ++applyErrorCount;
                System.err.println("INGRESS APPLY FAILED: templateId=" + headerDecoder.templateId()
                        + " type=" + failure.getClass().getName() + "; applyErrors=" + n
                        + "; restart required before any further application");
                throw failure;
            }
        } finally {
            applying = false;
        }
    }

    public long createOrderCount() {
        return createOrderCount;
    }

    /**
     * RC2 S4 (contract 3.1, 3.2 B0): a behaviour-2 template-9 frame. The base rules (the record 3
     * echo is reader-valid) are checked on the pooled view without building a record; a violation
     * stays the counted decode drop (S2 normalized-echo request declined, delta 12.12). Then the
     * S2 above-ceiling INVALID, then B0 (header version 11), then the engine's B1-B7.
     */
    private void dispatchDurableBehaviourTwo(final com.match.infrastructure.generated.DurableOrderCommandDecoder d,
                                             final int version, final int timeInForce,
                                             final long expectedOldRemaining, final boolean aboveCeiling,
                                             final long timestamp) {
        final long idHigh = d.commandIdHigh();
        final long idLow = d.commandIdLow();
        final long userId = d.userId();
        final long omsOrderId = d.omsOrderId();
        final long oldOrderId = d.oldOrderId();
        final int marketId = d.marketId();
        final int kind = d.commandKind();
        final int type = d.orderType();
        final int side = d.orderSide();
        if (!com.match.domain.commands.DurableOrderIntent.isValid(idHigh, idLow, userId, omsOrderId, oldOrderId,
                marketId, kind, type, side, timeInForce, expectedOldRemaining)) {
            rejectDecode();
            return;
        }
        durableView.set(idHigh, idLow, userId, omsOrderId, oldOrderId, d.price(), d.quantity(), d.budget(),
                marketId, kind, type, side, timeInForce, expectedOldRemaining);
        applying = true;
        if (aboveCeiling) {
            // Record 3 result 8: no capacity, no ledger, no apply (S2 contract 6).
            engine.journalInvalid(durableView, timestamp);
            final long n = ++aboveCeilingInvalidCount;
            if (shouldLogReject(n)) {
                System.err.println("INGRESS INVALID (above ceiling): DurableOrderCommand version="
                        + version + " above the recorded maxWireVersion="
                        + engine.getEngineConfig().maxWireVersion
                        + " journaled as INVALID; aboveCeilingInvalid=" + n);
            }
            return;
        }
        if (version < V12) {
            // B0: a v11 header carries no tif and no expectedOldRemaining (echoed 0/0).
            engine.journalInvalidWire(durableView, timestamp);
            return;
        }
        engine.acceptDurableV2(durableView, timestamp);
    }

    /**
     * RC2 S4 (contract 4.1): template 10 at behaviour 2. All-or-nothing validation before any
     * mutation: more than 48 entries is a frame-bounds reject; {@code releaseSeq < 0} or any entry
     * with {@code commandIdHigh < 1}, {@code idLowFloor < 0} or a raw scope outside 0..1 is a
     * counted decode reject (no entry applied, nothing journaled). 0 entries is a legal all-NOOP
     * frame.
     */
    private void dispatchRelease(final long timestamp) {
        releaseDecoder.wrapAndApplyHeader(frame, 0, headerDecoder);
        final long releaseSeq = releaseDecoder.releaseSeq();
        final com.match.infrastructure.generated.ReleaseCommandIdsDecoder.ReleasesDecoder group =
                releaseDecoder.releases();
        final int count = group.count();
        if (count > MAX_RELEASE_ENTRIES) {
            rejectFrameBounds();
            return;
        }
        boolean valid = releaseSeq >= 0;
        for (int i = 0; i < count; i++) {
            group.next();
            final long high = group.commandIdHigh();
            final long floor = group.idLowFloor();
            final int scope = group.scopeRaw();
            if (high < 1 || floor < 0 || scope > 1) {
                valid = false;
            }
            releaseHigh[i] = high;
            releaseFloor[i] = floor;
            releaseScope[i] = scope;
        }
        if (!valid) {
            rejectDecode();
            return;
        }
        applying = true;
        engine.applyRelease(releaseSeq, count, releaseHigh, releaseFloor, releaseScope);
    }

    /**
     * RC2 S6 contract 3.2, rows R1-R4 (R0 is the service's halt; R5 and R7 fall through to the existing paths;
     * R6, any other template id, is ignored by the switch as before). Codes are {@code ClusterSessionClass}
     * values: 1 GATEWAY, 2 FORWARDER, 4 ADMIN, 7 REPLICA; 3, 5 and 6 are never minted by the ME key table and
     * authorise nothing under R2-R4. Allocation-free.
     */
    private boolean authorised(final int classCode, final int templateId) {
        switch (templateId) {
            case CreateOrderDecoder.TEMPLATE_ID:
            case CancelOrderDecoder.TEMPLATE_ID:
            case UpdateOrderDecoder.TEMPLATE_ID:
                return classCode > 0 && classCode != CLASS_REPLICA; // R1; R5/R7 below
            case com.match.infrastructure.generated.EngineConfigDecoder.TEMPLATE_ID:
                return classCode == CLASS_ADMIN; // R1, R2: every state
            case com.match.infrastructure.generated.DurableOrderCommandDecoder.TEMPLATE_ID:
            case com.match.infrastructure.generated.ReleaseCommandIdsDecoder.TEMPLATE_ID:
                if (classCode <= 0 || classCode == CLASS_REPLICA) {
                    return false; // R1
                }
                return !engine.isJournalSeqDense() || classCode == CLASS_FORWARDER; // R3
            case com.match.infrastructure.generated.RequestOpenOrdersSnapshotDecoder.TEMPLATE_ID:
                if (classCode <= 0 || classCode == CLASS_REPLICA) {
                    return false; // R1
                }
                return !engine.isJournalSeqDense() || classCode == CLASS_FORWARDER || classCode == CLASS_ADMIN; // R4
            default:
                return true; // R6: not served, ignored by the switch, every class
        }
    }

    static final int CLASS_GATEWAY = 1;
    static final int CLASS_FORWARDER = 2;
    static final int CLASS_ADMIN = 4;
    static final int CLASS_REPLICA = 7;

    /** RC2 S6 (3.2 UNAUTHORIZED): counted, rate-limited log; no mutation, journal, journalSeq, egress or close. */
    private void refuseUnauthorized(final int classCode, final long clusterSessionId) {
        final long n = ++unauthorizedRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS UNAUTHORIZED: templateId=" + headerDecoder.templateId()
                    + " sessionClass=" + classCode + " clusterSessionId=" + clusterSessionId
                    + " behaviour2=" + engine.isJournalSeqDense() + "; unauthorizedRejects=" + n);
        }
    }

    /** Scrapeable (match_ingress_unauthorized_total): frames refused by the session-class rows (RC2 S6). */
    public long unauthorizedRejectCount() {
        return unauthorizedRejectCount;
    }

    /** RC2 S4 (5.3): counted, rate-limited log, no mutation, no egress, no journal. */
    private void refuseLegacyLane() {
        final long n = ++legacyLaneRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS REFUSED (legacy lane closed at behaviour 2): templateId="
                    + headerDecoder.templateId() + "; legacyLaneRejects=" + n);
        }
    }

    /** Scrapeable (match_legacy_lane_rejects_total): legacy templates 1/2/3 refused at behaviour 2 (RC2 S4). */
    public long legacyLaneRejectCount() {
        return legacyLaneRejectCount;
    }

    /** A counted decode drop decided without an exception (same counter and log as the catch path). */
    private void rejectDecode() {
        final long n = ++decodeRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS DROP (decode): templateId=" + headerDecoder.templateId()
                    + " type=validation; decodeRejects=" + n);
        }
    }

    /** RC2 S2: a recorded behaviour >= 2 config exists and {@code version} is above its ceiling. */
    private boolean aboveRecordedCeiling(final int version) {
        final com.match.application.engine.EngineConfigState recorded = engine.getEngineConfig();
        return recorded != null && recorded.behaviorVersion >= 2 && version > recorded.maxWireVersion;
    }

    /** Scrapeable (match_ingress_above_ceiling_invalid_total): template-9 frames above the
     *  recorded ceiling journaled as INVALID (RC2 S2 section 6). */
    public long aboveCeilingInvalidCount() {
        return aboveCeilingInvalidCount;
    }

    /** Scrapeable (match_ingress_above_ceiling_drops_total): frames of any other template above
     *  the recorded ceiling, dropped without mutation or journal (RC2 S2 section 6). */
    public long aboveCeilingDropCount() {
        return aboveCeilingDropCount;
    }

    private void countUnserved() {
        final long n = ++unservedTemplateCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS UNSERVED: templateId=" + headerDecoder.templateId()
                    + " is defined in this schema but not served by this build; unservedTemplates=" + n);
        }
    }

    /** Scrapeable (match_ingress_unserved_templates_total): frames of a defined but not yet served
     *  template (v12 ReleaseCommandIds 10 in S1), counted and never applied. */
    public long unservedTemplateCount() {
        return unservedTemplateCount;
    }

    private void rejectFrameBounds() {
        final long n = ++decodeRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS DROP (frame bounds); decodeRejects=" + n);
        }
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

    /** Scrapeable apply faults. A nonzero count fences dispatch until process restart. */
    public long applyErrorCount() {
        return applyErrorCount;
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
            applying = true;
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
        handlePreConfigDrop(what, "nothing to cancel/amend (the wire carries no omsOrderId"
                + " for this template, so no status egress is addressable)");
    }

    /** The fresh-cluster guard's counted, logged, deterministic drop (no egress, no journal). */
    private void handlePreConfigDrop(String what, String why) {
        final long n = ++preConfigOrderRejectCount;
        if (shouldLogReject(n)) {
            System.err.println("INGRESS REJECT (pre-config): " + what + " before any EngineConfig —"
                    + " no engine exists yet, " + why + "; preConfigOrderRejects=" + n);
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
    private void handleEngineConfig(DirectBuffer buffer, int offset, int version) {
        engineConfigDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        final long configVersion = engineConfigDecoder.configVersion();
        final String impl = toEngineImplName(engineConfigDecoder.impl());
        final long bookCapacity = engineConfigDecoder.bookCapacity();
        final long maxMatchesPerOrder = engineConfigDecoder.maxMatchesPerOrder();
        final long maxOrdersPerLevel = engineConfigDecoder.maxOrdersPerLevel();
        // v12 root fields (RC2 S1 contract 3.3), null before range: a pre-12 header maps the
        // defaults without reading; a v12 header reads them as encoded (no S1 range rule).
        final long behaviorVersion;
        final long commandLedgerCapacity;
        final long genesisId;
        final long maxWireVersion;
        if (version < V12) {
            behaviorVersion = com.match.application.engine.EngineConfigState.DEFAULT_BEHAVIOR_VERSION;
            commandLedgerCapacity = com.match.application.engine.EngineConfigState.DEFAULT_COMMAND_LEDGER_CAPACITY;
            genesisId = com.match.application.engine.EngineConfigState.DEFAULT_GENESIS_ID;
            maxWireVersion = com.match.application.engine.EngineConfigState.DEFAULT_MAX_WIRE_VERSION;
        } else {
            behaviorVersion = engineConfigDecoder.behaviorVersion();
            commandLedgerCapacity = engineConfigDecoder.commandLedgerCapacity();
            genesisId = engineConfigDecoder.genesisId();
            maxWireVersion = engineConfigDecoder.maxWireVersion();
        }

        final java.util.ArrayList<com.match.application.engine.EngineConfigState.MarketDef> defs =
                new java.util.ArrayList<>();
        for (final com.match.infrastructure.generated.EngineConfigDecoder.MarketsDecoder m
                : engineConfigDecoder.markets()) {
            defs.add(new com.match.application.engine.EngineConfigState.MarketDef(
                    m.marketId(), m.symbol(), m.minPrice(), m.maxPrice(), m.tickSize()));
        }

        if (engineConfigHandler != null) {
            final var config = com.match.application.engine.EngineConfigState.of(
                    configVersion, impl, bookCapacity, maxMatchesPerOrder, maxOrdersPerLevel,
                    defs.toArray(new com.match.application.engine.EngineConfigState.MarketDef[0]),
                    behaviorVersion, commandLedgerCapacity, genesisId, maxWireVersion);
            applying = true;
            engineConfigHandler.onEngineConfig(config);
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
        applying = true;
        engine.acceptOrder(marketId, Engine.CMD_CREATE, createCommand, timestamp);
    }

    private void handleCancelOrder(DirectBuffer buffer, int offset, long timestamp) {
        cancelOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

        // Direct primitive access - NO string parsing
        cancelCommand.reset();
        cancelCommand.setUserId(cancelOrderDecoder.userId());
        cancelCommand.setOrderId(cancelOrderDecoder.orderId());

        int marketId = cancelOrderDecoder.marketId();
        applying = true;
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
        applying = true;
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
