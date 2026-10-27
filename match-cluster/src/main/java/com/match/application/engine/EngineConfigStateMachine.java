// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.infrastructure.Logger;

/**
 * The {@code EngineConfig} state-machine handler: the decision table applied to every logged
 * EngineConfig command (slice C, extended by RC2 S2 with behaviour versioning and the genesis
 * identity). Runs on the cluster service (agent) thread only, live and during replay alike, and
 * every DECISION reads ONLY the command's fields plus replicated state, so all replicas decide
 * identically. The jar (supported behaviours, wire ceiling) and the launch mode never decide; they
 * feed the node-local check, which either passes or removes THIS node via fail-fast (A-9).
 *
 * <p><b>Decision table</b> (RC2 S2 contract section 3; first match wins after row 0):</p>
 * <ol start="0">
 *   <li>structurally invalid (S-a..S-c, {@link EngineConfigState#invalidReason()}), every state:
 *       REJECTED_INVALID.</li>
 *   <li>none recorded, no engines, t == 1: FRESH_LEGACY (engines from config, four normalized).</li>
 *   <li>none recorded, no engines, t &gt;= 2: FRESH (engines from config, four recorded, identity
 *       bound, journalSeq 1 consumed, record 5 FRESH).</li>
 *   <li>none recorded, engines exist, t == 1: ADOPT (record normalized, node-local cross-check).</li>
 *   <li>none recorded, engines exist, t &gt;= 2: REJECTED_ADOPT_AT_2, then this node halts.</li>
 *   <li>R at 1, t == 1 and {@code equalsConfig}: DUPLICATE.</li>
 *   <li>R at 1, t &gt;= 2: REJECTED_SWITCH.</li>
 *   <li>R at 1, any other difference: REJECTED_CHANGE.</li>
 *   <li>R at &gt;= 2, {@code sameBehaviourTwoConfig}: DUPLICATE.</li>
 *   <li>R at &gt;= 2, cv &lt;= R.cv: STALE.</li>
 *   <li>R at &gt;= 2, cv &gt; R.cv, t &lt; R.t: REJECTED_DOWNGRADE.</li>
 *   <li>same, t &gt; R.t: REJECTED_SWITCH.</li>
 *   <li>same, genesisId differs: REJECTED_GENESIS.</li>
 *   <li>same, creation values or commandLedgerCapacity differ: REJECTED_CHANGE.</li>
 *   <li>same, maxWireVersion &lt; R.maxWireVersion: REJECTED_CEILING_LOWERED.</li>
 *   <li>otherwise: BUMP (R.cv and R.maxWireVersion raised, next journalSeq, record 5 BUMP).</li>
 * </ol>
 *
 * <p>Record 5 is appended through the injected {@link DecisionJournal} only AFTER the node-local
 * check passed (contract 3.3): the replicated effects (config, identity, consumed journalSeq)
 * stand on every replica, and skipping this node's side output is the same as a crash before the
 * append, so no core can bind an epoch whose producer could not run it.</p>
 *
 * <p>Counters are plain longs written on the service thread and read by the /metrics scraper —
 * the same discipline as {@code SbeDemuxer}'s ingress counters. The fail-fast hook is injected
 * (production: a hookless {@code Runtime.halt(1)} — {@code System.exit} from the service thread
 * deadlocks in shutdown hooks awaiting that very thread, see #223) so the exit path is
 * unit-testable.</p>
 */
public final class EngineConfigStateMachine {
    private static final Logger logger = Logger.getLogger(EngineConfigStateMachine.class);

    /** Service hook: engines were just created from a fresh-accepted config (bring up egress). */
    public interface EnginesCreatedListener {
        void onEnginesCreatedFromConfig(EngineConfigState config);
    }

    /**
     * RC2 S2: the side output of a FRESH or BUMP decision, ME journal record 5. {@code recorded}
     * is the config as recorded after the decision; meEpoch is stamped by the journal from the
     * bound {@link MeIdentity}. Node-local output, never replicated state.
     */
    public interface DecisionJournal {
        void journalEngineConfig(long egressSeq, long journalSeq, EngineConfigState recorded, int decision);
    }

    /** The returned decision: closed, NOT replicated (contract 3.4). */
    public enum Decision {
        FRESH_LEGACY, FRESH, ADOPT, DUPLICATE, STALE, BUMP,
        REJECTED_INVALID, REJECTED_ADOPT_AT_2, REJECTED_SWITCH, REJECTED_CHANGE,
        REJECTED_DOWNGRADE, REJECTED_GENESIS, REJECTED_CEILING_LOWERED
    }

    /** Record 5 {@code ConfigDecision} values the RC2 ME writes (ADOPT 1 and SWITCH 2 unused). */
    public static final int JOURNAL_DECISION_FRESH = 0;
    public static final int JOURNAL_DECISION_BUMP = 3;

    /** Jar constant: behaviour versions this build runs, as a bitmask (bit v-1): {1, 2} = 3. */
    public static final long SUPPORTED_BEHAVIOR_VERSIONS_MASK = 0b11L;

    /** Jar constant: the highest order-schema version this build decodes (12). */
    public static final int SUPPORTED_MAX_WIRE_VERSION =
            com.match.infrastructure.generated.MessageHeaderDecoder.SCHEMA_VERSION;

    /** True when this jar runs behaviour {@code v} (check a). */
    public static boolean supportsBehaviour(long v) {
        return v >= 1 && v <= 63 && (SUPPORTED_BEHAVIOR_VERSIONS_MASK & (1L << (v - 1))) != 0;
    }

    private static final DecisionJournal NO_JOURNAL = (egressSeq, journalSeq, recorded, decision) -> { };

    private final Engine engine;
    private final Runnable failFast;
    private final EnginesCreatedListener enginesCreatedListener;
    private final DecisionJournal decisionJournal;

    // Plain longs: written on the cluster service thread only, scraped via NodeMetrics.acquire().
    private long acceptedCount;
    private long duplicateCount;
    private long staleCount;
    private long rejectCount;
    private long bumpCount;

    public EngineConfigStateMachine(Engine engine, Runnable failFast,
                                    EnginesCreatedListener enginesCreatedListener) {
        this(engine, failFast, enginesCreatedListener, NO_JOURNAL);
    }

    public EngineConfigStateMachine(Engine engine, Runnable failFast,
                                    EnginesCreatedListener enginesCreatedListener,
                                    DecisionJournal decisionJournal) {
        this.engine = engine;
        this.failFast = failFast;
        this.enginesCreatedListener = enginesCreatedListener;
        this.decisionJournal = decisionJournal == null ? NO_JOURNAL : decisionJournal;
    }

    /**
     * Apply one logged EngineConfig command. Deterministic: the decision reads only
     * {@code incoming} and replicated state; the node-local check that follows FRESH, ADOPT,
     * REJECTED_ADOPT_AT_2 and BUMP never changes replicated state.
     */
    public Decision onEngineConfig(EngineConfigState incoming) {
        // Row 0: structural validity first, in every state.
        final String invalid = incoming.invalidReason();
        if (invalid != null) {
            return reject(Decision.REJECTED_INVALID, invalid, null, incoming);
        }

        final EngineConfigState recorded = engine.getEngineConfig();
        final long t = incoming.behaviorVersion;

        if (recorded == null) {
            if (!engine.hasEngines()) {
                return t == 1 ? freshLegacy(incoming.normalizedForBehaviourOne()) : fresh(incoming);
            }
            if (t == 1) {
                return adopt(incoming.normalizedForBehaviourOne());
            }
            // Row 4: the replicated outcome is "nothing recorded"; this node is in legacy launch
            // mode (engines from env) and must not serve behaviour >= 2 (check d).
            final Decision d = reject(Decision.REJECTED_ADOPT_AT_2,
                    "behaviour >= 2 requires config mode (MATCH_ENGINE_FROM_CONFIG=1); a config-mode"
                            + " member at this position would have FRESHed", null, incoming);
            nodeLocalCheck("adopt-at-" + t, null, false, true);
            return d;
        }

        if (recorded.behaviorVersion == 1) {
            if (t == 1 && recorded.equalsConfig(incoming)) {
                return duplicate(recorded);
            }
            if (t >= 2) {
                return reject(Decision.REJECTED_SWITCH,
                        "no live behaviour 1 -> " + t + " switch (D7): behaviour >= 2 needs a new genesis",
                        recorded, incoming);
            }
            return reject(Decision.REJECTED_CHANGE,
                    "a config is already recorded and the incoming one differs (runtime reconfig is"
                            + " not in this slice)", recorded, incoming);
        }

        // Recorded behaviour >= 2.
        if (recorded.sameBehaviourTwoConfig(incoming)) {
            return duplicate(recorded);
        }
        if (incoming.configVersion <= recorded.configVersion) {
            staleCount++;
            final String msg = "[ENGINE-CONFIG] STALE: configVersion=" + incoming.configVersion
                    + " <= recorded " + recorded.configVersion + "; stale=" + staleCount
                    + "\n  recorded: " + recorded.describe()
                    + "\n  incoming: " + incoming.describe();
            logger.warn(msg);
            System.out.println(msg);
            return Decision.STALE;
        }
        if (t < recorded.behaviorVersion) {
            return reject(Decision.REJECTED_DOWNGRADE, "behaviour " + recorded.behaviorVersion + " -> " + t
                    + " downgrade refused", recorded, incoming);
        }
        if (t > recorded.behaviorVersion) {
            return reject(Decision.REJECTED_SWITCH, "behaviour " + recorded.behaviorVersion + " -> " + t
                    + " refused: no behaviour change after genesis (a new behaviour is a new genesis)",
                    recorded, incoming);
        }
        if (incoming.genesisId != recorded.genesisId) {
            return reject(Decision.REJECTED_GENESIS, "genesisId " + incoming.genesisId
                    + " differs from the recorded " + recorded.genesisId
                    + " (a new nonce is a re-genesis attempt)", recorded, incoming);
        }
        if (!recorded.equalsIgnoringVersion(incoming)
                || incoming.commandLedgerCapacity != recorded.commandLedgerCapacity) {
            return reject(Decision.REJECTED_CHANGE, "engine-creation values or commandLedgerCapacity"
                    + " differ (runtime reconfig is not in this slice)", recorded, incoming);
        }
        if (incoming.maxWireVersion < recorded.maxWireVersion) {
            return reject(Decision.REJECTED_CEILING_LOWERED, "maxWireVersion " + incoming.maxWireVersion
                    + " below the recorded " + recorded.maxWireVersion + " (the ceiling only rises)",
                    recorded, incoming);
        }
        return bump(recorded, incoming);
    }

    // Row 1: behaviour 1 FRESH, bit-identical to S1 (no identity, no journal).
    private Decision freshLegacy(EngineConfigState normalized) {
        engine.createEnginesFromConfig(normalized);
        acceptedCount++;
        final String msg = "[ENGINE-CONFIG] ACCEPTED (fresh): engines created from replicated"
                + " config — configVersion=" + normalized.configVersion + " impl=" + normalized.impl
                + " markets=" + normalized.markets.length + " behaviorVersion=1 accepted=" + acceptedCount;
        logger.info(msg);
        System.out.println(msg);
        if (enginesCreatedListener != null) {
            enginesCreatedListener.onEnginesCreatedFromConfig(normalized);
        }
        return Decision.FRESH_LEGACY;
    }

    // Row 2: validate (done), create engines, bind identity (journalSeq 1 consumed), listener,
    // node-local check, record 5 only if the check passed.
    private Decision fresh(EngineConfigState incoming) {
        engine.createEnginesFromConfig(incoming);
        final MeIdentity identity = engine.meIdentity();
        // RC2 S3: bound through the engine so dense allocation (records 1-4) starts here too.
        // RC2 S4 (row 2 hook): and the empty ledger v2 with the recorded capacity C is installed.
        engine.bindFreshIdentity(incoming.genesisId, incoming.behaviorVersion, incoming.commandLedgerCapacity);
        final long journalSeq = identity.allocateJournalSeq();
        acceptedCount++;
        final String msg = "[ENGINE-CONFIG] ACCEPTED (fresh, behaviour " + incoming.behaviorVersion
                + "): engines created and genesis identity bound meEpoch=" + identity.meEpoch()
                + " journalSeq=" + journalSeq + " accepted=" + acceptedCount
                + "\n  recorded: " + incoming.describe();
        logger.info(msg);
        System.out.println(msg);
        if (enginesCreatedListener != null) {
            enginesCreatedListener.onEnginesCreatedFromConfig(incoming);
        }
        if (nodeLocalCheck("fresh", incoming, false, false)) {
            decisionJournal.journalEngineConfig(engine.getCurrentLogPosition(), journalSeq, incoming,
                    JOURNAL_DECISION_FRESH);
        }
        return Decision.FRESH;
    }

    // Row 3: ADOPT (dogfood path) at behaviour 1. Record first — the config becomes the declared
    // truth on EVERY replica — then cross-check THIS node against it.
    private Decision adopt(EngineConfigState normalized) {
        engine.setEngineConfig(normalized);
        acceptedCount++;
        final String adopted = "[ENGINE-CONFIG] ACCEPTED (adopted mid-life): recorded as declared"
                + " truth without recreating engines — configVersion=" + normalized.configVersion
                + " impl=" + normalized.impl + " markets=" + normalized.markets.length
                + " accepted=" + acceptedCount;
        logger.info(adopted);
        System.out.println(adopted);
        nodeLocalCheck("adopt", normalized, true, false);
        return Decision.ADOPT;
    }

    // Row 15: BUMP. Update the record and consume the next journalSeq, node-local check, then
    // append record 5 if the check passed.
    private Decision bump(EngineConfigState recorded, EngineConfigState incoming) {
        final EngineConfigState bumped =
                recorded.withConfigVersionAndCeiling(incoming.configVersion, incoming.maxWireVersion);
        engine.setEngineConfig(bumped);
        final long journalSeq = engine.meIdentity().allocateJournalSeq();
        acceptedCount++;
        bumpCount++;
        final String msg = "[ENGINE-CONFIG] BUMP: configVersion " + recorded.configVersion + " -> "
                + bumped.configVersion + ", maxWireVersion " + recorded.maxWireVersion + " -> "
                + bumped.maxWireVersion + " journalSeq=" + journalSeq + " bumps=" + bumpCount;
        logger.info(msg);
        System.out.println(msg);
        if (nodeLocalCheck("bump", bumped, false, false)) {
            decisionJournal.journalEngineConfig(engine.getCurrentLogPosition(), journalSeq, bumped,
                    JOURNAL_DECISION_BUMP);
        }
        return Decision.BUMP;
    }

    private Decision duplicate(EngineConfigState recorded) {
        // Idempotent duplicate (e.g. an operator retry) — no-op ACK, counted, no seq consumed.
        duplicateCount++;
        System.out.println("[ENGINE-CONFIG] duplicate ignored (identical to recorded"
                + " configVersion=" + recorded.configVersion + "); duplicates=" + duplicateCount);
        return Decision.DUPLICATE;
    }

    // Deterministic loud reject: replicated state is untouched on every replica, nothing journaled.
    private Decision reject(Decision decision, String reason, EngineConfigState recorded,
                            EngineConfigState incoming) {
        rejectCount++;
        final String msg = "[ENGINE-CONFIG] " + decision + ": " + reason + "; rejects=" + rejectCount
                + (recorded == null ? "" : "\n  recorded: " + recorded.describe())
                + "\n  incoming: " + incoming.describe();
        logger.error(msg);
        System.err.println(msg);
        return decision;
    }

    /**
     * Node-local check after a snapshot restore that brought a recorded config, in BOTH launch
     * modes (contract section 4). Never touches replicated state.
     *
     * @return true when every applicable check passed; false after fail-fast was invoked
     */
    public boolean checkAfterRestore() {
        final EngineConfigState recorded = engine.getEngineConfig();
        if (recorded == null) {
            return true;
        }
        return nodeLocalCheck("snapshot-restore", recorded, !engine.isConfigMode(), false);
    }

    /**
     * The node-local checks of contract section 4 (A-9: never a reject from node-local truth).
     * Every applicable check is evaluated, every failing one is logged CRITICAL naming the
     * recorded value and this node's value, then fail-fast runs AT MOST ONCE.
     *
     * @param recorded          the recorded config after the decision (null for row 4)
     * @param creationValues    check (c): legacy-mode engine-creation values (ADOPT, legacy restore)
     * @param adoptAtTwoTaken   row 4 was taken on this node (check d)
     * @return true when every check passed
     */
    private boolean nodeLocalCheck(String context, EngineConfigState recorded, boolean creationValues,
                                   boolean adoptAtTwoTaken) {
        final java.util.List<String> failures = new java.util.ArrayList<>(4);
        final boolean legacyMode = !engine.isConfigMode();
        if (recorded != null) {
            // (a) the recorded behaviour is one this jar runs.
            if (!supportsBehaviour(recorded.behaviorVersion)) {
                failures.add("(a) recorded behaviorVersion=" + recorded.behaviorVersion
                        + " is not supported by this jar (supported bitmask=" + SUPPORTED_BEHAVIOR_VERSIONS_MASK + ")");
            }
            // (b) a jar below the recorded wire ceiling cannot follow the log (W10).
            if (recorded.behaviorVersion >= 2 && recorded.maxWireVersion > SUPPORTED_MAX_WIRE_VERSION) {
                failures.add("(b) recorded maxWireVersion=" + recorded.maxWireVersion
                        + " is above this jar's order-schema version " + SUPPORTED_MAX_WIRE_VERSION);
            }
            // (c) legacy mode only: today's engine-creation value check.
            if (creationValues && legacyMode) {
                final EngineConfigState effective = nodeEffectiveConfig(recorded.configVersion);
                if (!recorded.equalsIgnoringVersion(effective)) {
                    failures.add("(c) this node's effective engine-creation values DIFFER from the"
                            + " cluster's declared config"
                            + "\n  declared (cluster log):  " + recorded
                            + "\n  effective (this node):   " + effective);
                }
            }
            // (d) launch mode contradicts the record.
            if (recorded.behaviorVersion >= 2 && legacyMode) {
                failures.add("(d) recorded behaviorVersion=" + recorded.behaviorVersion
                        + " requires config mode; this node runs in legacy launch mode");
            }
        }
        if (adoptAtTwoTaken) {
            failures.add("(d) an EngineConfig at behaviour >= 2 reached this legacy-mode node with"
                    + " nothing recorded (REJECTED_ADOPT_AT_2); behaviour >= 2 requires config mode");
        }
        if (failures.isEmpty()) {
            System.out.println("[ENGINE-CONFIG] node-local check OK (" + context + ")");
            return true;
        }
        for (final String failure : failures) {
            final String msg = "[ENGINE-CONFIG] CRITICAL (" + context + "): " + failure
                    + "\n  A node-local reject would fork the cluster, so this node exits instead"
                    + " (fail-fast, the A-9 principle); the other nodes keep quorum.";
            logger.error(msg);
            System.err.println(msg);
        }
        failFast.run();
        return false;
    }

    /**
     * This node's EFFECTIVE engine-creation values as a config: env-derived impl and capacity
     * (whatever the engine actually built with), the compiled-in caps for that impl, and the
     * compiled-in market table — normalized exactly like a wire config so the comparison is
     * field-by-field in sorted market order. {@code configVersion} is taken from the declared
     * config: a node has no local config generation, so the version is recorded, not checked.
     */
    private EngineConfigState nodeEffectiveConfig(long declaredConfigVersion) {
        final String impl = engine.getImplName();
        final boolean array = EngineConfigState.IMPL_ARRAY.equals(impl);
        final EngineConfigState.MarketDef[] markets =
                new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < MarketConfig.ALL_MARKETS.length; i++) {
            final MarketConfig m = MarketConfig.ALL_MARKETS[i];
            markets[i] = new EngineConfigState.MarketDef(
                    m.marketId, m.symbol, m.basePrice, m.maxPrice, m.tickSize);
        }
        return EngineConfigState.of(
                declaredConfigVersion,
                impl,
                engine.getEffectiveBookCapacity(),
                array ? ArrayMatchingEngine.MAX_MATCHES_PER_ORDER : DirectMatchingEngine.MAX_MATCHES_PER_ORDER,
                array ? 0 : DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL,
                markets);
    }

    // ---- #224: effective engine-creation gauges (match_engine_effective_*) ----
    //
    // The node's EFFECTIVE engine-creation config as published on /metrics: the recorded
    // replicated config when one exists (the declared truth this node either was created from or
    // passed/died by the adopt cross-check), else the SAME node-local derivation the adopt
    // cross-check compares against (nodeEffectiveConfig: env-derived impl/capacity + compiled
    // caps + compiled market table) — so the operator's declared-vs-effective precheck sees
    // exactly what node-local check (c) would compare. Null only in config mode before the first
    // EngineConfig (no engines, no effective values yet): the numeric gauges publish the -1
    // sentinel and the hash publishes 0 until then. Read on the /metrics scraper thread with the
    // same publish/acquire piggyback as the counters; allocates per scrape — fine off the order
    // path.
    private EngineConfigState effectiveConfig() {
        final EngineConfigState recorded = engine.getEngineConfig();
        if (recorded != null) {
            return recorded;
        }
        if (!engine.hasEngines()) {
            return null;
        }
        // configVersion 0: a node has no local config generation and the gauges/hash exclude it.
        return nodeEffectiveConfig(0L);
    }

    /** Scrapeable (match_engine_effective_book_capacity): effective per-book capacity; -1 pre-config. */
    public long effectiveBookCapacity() {
        final EngineConfigState c = effectiveConfig();
        return c == null ? -1L : c.bookCapacity;
    }

    /** Scrapeable (match_engine_effective_max_matches_per_order): effective per-order match cap; -1 pre-config. */
    public long effectiveMaxMatchesPerOrder() {
        final EngineConfigState c = effectiveConfig();
        return c == null ? -1L : c.maxMatchesPerOrder;
    }

    /** Scrapeable (match_engine_effective_max_orders_per_level): effective per-level cap (0 for array); -1 pre-config. */
    public long effectiveMaxOrdersPerLevel() {
        final EngineConfigState c = effectiveConfig();
        return c == null ? -1L : c.maxOrdersPerLevel;
    }

    /** Scrapeable (match_engine_effective_impl): EngineImpl wire value, 0=array 1=direct; -1 pre-config. */
    public long effectiveImplWire() {
        final EngineConfigState c = effectiveConfig();
        return c == null ? -1L : c.implWireValue();
    }

    /** Scrapeable (match_engine_effective_config_hash, rendered unsigned): canonical hash; 0 pre-config. */
    public long effectiveConfigHash() {
        final EngineConfigState c = effectiveConfig();
        return c == null ? 0L : c.canonicalHash();
    }

    /** Configs accepted: FRESH_LEGACY, FRESH, ADOPT, BUMP. */
    public long acceptedCount() {
        return acceptedCount;
    }

    /** Scrapeable (match_engine_config_stale_total): behaviour >= 2 frames at cv <= recorded. */
    public long staleCount() {
        return staleCount;
    }

    /** Scrapeable (match_engine_config_bumps_total): BUMP decisions. */
    public long bumpCount() {
        return bumpCount;
    }

    /** Scrapeable (match_engine_behavior_version): recorded behaviour, 0 when none. */
    public long recordedBehaviorVersion() {
        final EngineConfigState c = engine.getEngineConfig();
        return c == null ? 0L : c.behaviorVersion;
    }

    /** Scrapeable (match_engine_max_wire_version): recorded wire ceiling, 0 when none. */
    public long recordedMaxWireVersion() {
        final EngineConfigState c = engine.getEngineConfig();
        return c == null ? 0L : c.maxWireVersion;
    }

    /** Scrapeable (match_engine_config_duplicates_total): identical re-sends, no-op ACKed. */
    public long duplicateCount() {
        return duplicateCount;
    }

    /** Scrapeable (match_engine_config_rejects_total): every REJECTED_* decision, INVALID included. */
    public long rejectCount() {
        return rejectCount;
    }
}
