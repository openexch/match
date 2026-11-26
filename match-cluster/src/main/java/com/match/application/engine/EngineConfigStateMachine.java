// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.infrastructure.Logger;

/**
 * The {@code EngineConfig} state-machine handler: the decision table applied to every logged
 * EngineConfig command (extended with behaviour versioning and the genesis
 * identity). Runs on the cluster service (agent) thread only, live and during replay alike, and
 * every DECISION reads ONLY the command's fields plus replicated state, so all replicas decide
 * identically. The jar (supported behaviours, wire ceiling) never decides; it feeds the node-local
 * check, which either passes or removes THIS node via fail-fast (exit 78: a restart cannot fix a jar).
 * Engines are created only here, from the replicated config: there is no launch mode.
 *
 * <p><b>Decision table</b> (first match wins after row 0):</p>
 * <ol start="0">
 *   <li>structurally invalid (S-a..S-c, {@link EngineConfigState#invalidReason()}), every state:
 *       REJECTED_INVALID.</li>
 *   <li>none recorded, t == 1: FRESH_LEGACY (engines from config, four normalized).</li>
 *   <li>none recorded, t &gt;= 2: FRESH (engines from config, four recorded, identity
 *       bound, journalSeq 1 consumed, record 5 FRESH).</li>
 *   <li>(rows 3 and 4, ADOPT and REJECTED_ADOPT_AT_2, existed only for the removed legacy launch
 *       mode; the numbering of the rows below is kept.)</li>
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
 * check passed: the replicated effects (config, identity, consumed journalSeq)
 * stand on every replica, and skipping this node's side output is the same as a crash before the
 * append, so no core can bind an epoch whose producer could not run it.</p>
 *
 * <p>Counters are plain longs written on the service thread and read by the /metrics scraper —
 * the same discipline as {@code SbeDemuxer}'s ingress counters. The fail-fast hook is injected
 * (production: the service's {@code failFastIncompatible}, a hookless {@code Runtime.halt(78)} —
 * {@code System.exit} from the service thread deadlocks in shutdown hooks awaiting that very thread,
 * see #223) so the exit path is unit-testable.</p>
 */
public final class EngineConfigStateMachine {
    private static final Logger logger = Logger.getLogger(EngineConfigStateMachine.class);

    /** Service hook: engines were just created from a fresh-accepted config (bring up egress). */
    public interface EnginesCreatedListener {
        void onEnginesCreatedFromConfig(EngineConfigState config);
    }

    /**
     * The side output of a FRESH or BUMP decision, ME journal record 5. {@code recorded}
     * is the config as recorded after the decision; meEpoch is stamped by the journal from the
     * bound {@link MeIdentity}. Node-local output, never replicated state.
     */
    public interface DecisionJournal {
        void journalEngineConfig(long egressSeq, long journalSeq, EngineConfigState recorded, int decision);
    }

    /** The returned decision: closed, NOT replicated. */
    public enum Decision {
        FRESH_LEGACY, FRESH, DUPLICATE, STALE, BUMP,
        REJECTED_INVALID, REJECTED_SWITCH, REJECTED_CHANGE,
        REJECTED_DOWNGRADE, REJECTED_GENESIS, REJECTED_CEILING_LOWERED
    }

    /** Record 5 {@code ConfigDecision} values this ME writes (ADOPT 1 and SWITCH 2 unused). */
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
     * {@code incoming} and replicated state; the node-local check that follows FRESH and BUMP
     * never changes replicated state.
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
            // Rows 1 and 2: the first config creates the engines, on every member at this position.
            return t == 1 ? freshLegacy(incoming.normalizedForBehaviourOne()) : fresh(incoming);
        }

        if (recorded.behaviorVersion == 1) {
            if (t == 1 && recorded.equalsConfig(incoming)) {
                return duplicate(recorded);
            }
            if (t >= 2) {
                return reject(Decision.REJECTED_SWITCH,
                        "no live behaviour 1 -> " + t + " switch: behaviour >= 2 needs a new genesis",
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
        if (com.openexchange.cluster.WireCeiling.lowers(incoming.maxWireVersion, recorded.maxWireVersion)) {
            return reject(Decision.REJECTED_CEILING_LOWERED, "maxWireVersion " + incoming.maxWireVersion
                    + " below the recorded " + recorded.maxWireVersion + " (the ceiling only rises)",
                    recorded, incoming);
        }
        return bump(recorded, incoming);
    }

    // Row 1: behaviour 1 FRESH, bit-identical to the pre-versioning engine (no identity, no journal).
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
        // Bound through the engine so dense allocation (records 1-4) starts here too.
        // And the empty ledger v2 with the recorded capacity C is installed.
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
        if (nodeLocalCheck("fresh", incoming)) {
            decisionJournal.journalEngineConfig(engine.getCurrentLogPosition(), journalSeq, incoming,
                    JOURNAL_DECISION_FRESH);
        }
        return Decision.FRESH;
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
        if (nodeLocalCheck("bump", bumped)) {
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
     * Node-local check after a snapshot restore that brought a recorded config. Never touches
     * replicated state.
     *
     * @return true when every applicable check passed; false after fail-fast was invoked
     */
    public boolean checkAfterRestore() {
        final EngineConfigState recorded = engine.getEngineConfig();
        if (recorded == null) {
            return true;
        }
        return nodeLocalCheck("snapshot-restore", recorded);
    }

    /**
     * The node-local checks (fail-fast: never a reject from node-local truth): (a) the recorded
     * behaviour is one this jar runs, (b) the jar is not below the recorded wire ceiling. Every
     * failing one is logged CRITICAL naming the recorded value and this node's value, then the
     * fail-fast (exit 78) runs AT MOST ONCE.
     *
     * @return true when every check passed
     */
    private boolean nodeLocalCheck(String context, EngineConfigState recorded) {
        final java.util.List<String> failures = new java.util.ArrayList<>(2);
        // (a) the recorded behaviour is one this jar runs.
        if (!supportsBehaviour(recorded.behaviorVersion)) {
            failures.add("(a) recorded behaviorVersion=" + recorded.behaviorVersion
                    + " is not supported by this jar (supported bitmask=" + SUPPORTED_BEHAVIOR_VERSIONS_MASK + ")");
        }
        // (b) a jar below the recorded wire ceiling cannot follow the log.
        final String jarBelow = recorded.behaviorVersion >= 2 ? com.openexchange.cluster.WireCeiling.jarBelow(
                recorded.maxWireVersion, SUPPORTED_MAX_WIRE_VERSION, "order-schema") : null;
        if (jarBelow != null) {
            failures.add("(b) " + jarBelow);
        }
        if (failures.isEmpty()) {
            System.out.println("[ENGINE-CONFIG] node-local check OK (" + context + ")");
            return true;
        }
        for (final String failure : failures) {
            final String msg = "[ENGINE-CONFIG] CRITICAL (" + context + "): " + failure
                    + "\n  A node-local reject would fork the cluster, so this node exits instead"
                    + " (fail-fast); the other nodes keep quorum.";
            logger.error(msg);
            System.err.println(msg);
        }
        failFast.run();
        return false;
    }

    // ---- #224: effective engine-creation gauges (match_engine_effective_*) ----
    //
    // The recorded replicated config, the only source of engine-creation values. Null before the
    // first EngineConfig (no engines): the numeric gauges publish the -1 sentinel and the hash
    // publishes 0 until then.
    private EngineConfigState effectiveConfig() {
        return engine.getEngineConfig();
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

    /** Configs accepted: FRESH_LEGACY, FRESH, BUMP. */
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
