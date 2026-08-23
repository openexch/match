// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import com.match.infrastructure.Logger;

/**
 * Slice C: the {@code EngineConfig} state-machine handler — the decision table applied to every
 * logged EngineConfig command. Runs on the cluster service (agent) thread only, live and during
 * replay alike, and every decision reads ONLY the command's fields plus replicated state, so all
 * replicas decide identically.
 *
 * <p><b>Decision table</b> (see the slice C design):</p>
 * <ul>
 *   <li><b>No config recorded, cluster FRESH (no engines)</b> — ACCEPT: validate, create the
 *       engines FROM the config (impl, capacity, match caps, markets — not from
 *       {@code MarketConfig.ALL_MARKETS}), record it, notify the service so egress publishers
 *       come up for the config's markets.</li>
 *   <li><b>No config recorded, engines already exist (adopted mid-life — the dogfood path)</b> —
 *       ACCEPT and record WITHOUT recreating anything: the config becomes the declared truth.
 *       Then EACH NODE locally cross-checks the declared values against its own effective values
 *       (env-derived impl/capacity + compiled-in market table + caps). On mismatch the node must
 *       NOT reject — a reject computed from node-local truth would fork the cluster (replicas
 *       with different env would disagree on a logged command). Instead it logs CRITICAL naming
 *       both sides and EXITS via the injected fail-fast hook — the A-9 egress-init principle: a
 *       node that provably diverges from the cluster's declared truth must not serve. Matching
 *       nodes record and continue untouched.</li>
 *   <li><b>Config recorded, incoming == recorded</b> (field-by-field, markets in sorted order) —
 *       idempotent duplicate: no-op ACK, counted.</li>
 *   <li><b>Config recorded, incoming != recorded</b> — deterministic loud REJECT, counted
 *       (runtime reconfig is a later slice).</li>
 * </ul>
 *
 * <p>A structurally invalid config (see {@link EngineConfigState#invalidReason()}) is a
 * deterministic loud REJECT in every state — it is never recorded and never half-applied.</p>
 *
 * <p>Counters are plain longs written on the service thread and read by the /metrics scraper —
 * the same discipline as {@code SbeDemuxer}'s ingress counters. The fail-fast hook is injected
 * (production: a hookless {@code Runtime.halt(1)} — {@code System.exit} from the service thread
 * deadlocks in shutdown hooks awaiting that very thread, see #223) so the exit path is
 * unit-testable; the bare A-9 {@code System.exit} had no seam, this one starts with one.</p>
 */
public final class EngineConfigStateMachine {
    private static final Logger logger = Logger.getLogger(EngineConfigStateMachine.class);

    /** Service hook: engines were just created from a fresh-accepted config (bring up egress). */
    public interface EnginesCreatedListener {
        void onEnginesCreatedFromConfig(EngineConfigState config);
    }

    private final Engine engine;
    private final Runnable failFast;
    private final EnginesCreatedListener enginesCreatedListener;

    // Plain longs: written on the cluster service thread only, scraped via NodeMetrics.acquire().
    private long acceptedCount;
    private long duplicateCount;
    private long rejectCount;

    public EngineConfigStateMachine(Engine engine, Runnable failFast,
                                    EnginesCreatedListener enginesCreatedListener) {
        this.engine = engine;
        this.failFast = failFast;
        this.enginesCreatedListener = enginesCreatedListener;
    }

    /**
     * Apply one logged EngineConfig command. Deterministic: reads only {@code incoming} and
     * replicated state; the only node-local input is the adopt-path cross-check, which never
     * changes replicated state — it either passes or removes THIS node via fail-fast.
     */
    public void onEngineConfig(EngineConfigState incoming) {
        final EngineConfigState recorded = engine.getEngineConfig();

        if (recorded != null) {
            if (recorded.equalsConfig(incoming)) {
                // Idempotent duplicate (e.g. an operator retry) — no-op ACK, counted.
                duplicateCount++;
                System.out.println("[ENGINE-CONFIG] duplicate ignored (identical to recorded"
                        + " configVersion=" + recorded.configVersion + "); duplicates=" + duplicateCount);
                return;
            }
            // Different config while one is recorded: runtime reconfig is a LATER slice.
            // Deterministic loud reject — replicated state is untouched on every replica.
            rejectCount++;
            final String msg = "[ENGINE-CONFIG] REJECTED: a config is already recorded and the"
                    + " incoming one differs (runtime reconfig is not in this slice); rejects=" + rejectCount
                    + "\n  recorded: " + recorded
                    + "\n  incoming: " + incoming;
            logger.error(msg);
            System.err.println(msg);
            return;
        }

        // No config recorded yet. Validate BEFORE anything is applied or recorded.
        final String invalid = incoming.invalidReason();
        if (invalid != null) {
            rejectCount++;
            final String msg = "[ENGINE-CONFIG] REJECTED (invalid): " + invalid
                    + "; rejects=" + rejectCount + "\n  incoming: " + incoming;
            logger.error(msg);
            System.err.println(msg);
            return;
        }

        if (!engine.hasEngines()) {
            // FRESH cluster: the engines are born from the log.
            engine.createEnginesFromConfig(incoming);
            acceptedCount++;
            final String msg = "[ENGINE-CONFIG] ACCEPTED (fresh): engines created from replicated"
                    + " config — configVersion=" + incoming.configVersion + " impl=" + incoming.impl
                    + " markets=" + incoming.markets.length + " accepted=" + acceptedCount;
            logger.info(msg);
            System.out.println(msg);
            if (enginesCreatedListener != null) {
                enginesCreatedListener.onEnginesCreatedFromConfig(incoming);
            }
            return;
        }

        // ADOPT (dogfood path): engines already exist, built from env + the compiled market table.
        // Record first — the config becomes the cluster's declared truth on EVERY replica — then
        // cross-check THIS node against it.
        engine.setEngineConfig(incoming);
        acceptedCount++;
        final String adopted = "[ENGINE-CONFIG] ACCEPTED (adopted mid-life): recorded as declared"
                + " truth without recreating engines — configVersion=" + incoming.configVersion
                + " impl=" + incoming.impl + " markets=" + incoming.markets.length
                + " accepted=" + acceptedCount;
        logger.info(adopted);
        System.out.println(adopted);

        crossCheckOrExit(incoming, "adopt");
    }

    /**
     * Node-local cross-check of the declared config against this node's effective values; on
     * mismatch: CRITICAL naming both sides, then the fail-fast hook (exit — NEVER a reject, which
     * would fork the cluster). Also invoked by the service after restoring a config-bearing
     * snapshot into a legacy-mode (env-built) engine, so a node restarted with divergent env
     * cannot silently serve against the declared truth (HEALTHY != running the right config).
     */
    public void crossCheckOrExit(EngineConfigState declared, String context) {
        final EngineConfigState effective = nodeEffectiveConfig(declared.configVersion);
        if (declared.equalsIgnoringVersion(effective)) {
            System.out.println("[ENGINE-CONFIG] local cross-check OK (" + context + "): this node's"
                    + " effective values match the declared config");
            return;
        }
        final String msg = "[ENGINE-CONFIG] CRITICAL (" + context + "): this node's effective"
                + " engine-creation values DIFFER from the cluster's declared config. A node-local"
                + " reject would fork the cluster, so this node exits instead (fail-fast, the A-9"
                + " principle) — the other nodes keep quorum; fix this node's env/build and restart."
                + "\n  declared (cluster log):  " + declared
                + "\n  effective (this node):   " + effective;
        logger.error(msg);
        System.err.println(msg);
        failFast.run();
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

    /** Configs accepted (fresh creations + adoptions). */
    public long acceptedCount() {
        return acceptedCount;
    }

    /** Scrapeable (match_engine_config_duplicates_total): identical re-sends, no-op ACKed. */
    public long duplicateCount() {
        return duplicateCount;
    }

    /** Scrapeable (match_engine_config_rejects_total): differing or invalid configs rejected. */
    public long rejectCount() {
        return rejectCount;
    }
}
