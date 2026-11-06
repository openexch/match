// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Immutable, replicated engine-creation config (slice C of behaviour versioning).
 *
 * <p>This is the in-memory form of a logged {@code EngineConfig} command (SBE v10+, template 8):
 * the set of values a replica needs to create its matching engines — implementation, capacity,
 * match caps, and the market table. It is REPLICATED STATE: it only ever comes from the cluster
 * log (or from a snapshot that recorded it), never from a node's env, and it is part of the
 * snapshot from the moment it is recorded.</p>
 *
 * <p><b>Normalization:</b> {@link #of} sorts the markets by {@code marketId} at construction, so
 * equality ({@link #equalsConfig}) and the snapshot bytes are independent of the wire order the
 * producer happened to use. Two configs that differ only in market order are the SAME config.</p>
 *
 * <p><b>Wire-range fields:</b> {@code configVersion}, {@code bookCapacity},
 * {@code maxMatchesPerOrder} and {@code maxOrdersPerLevel} are uint32 on the wire and carried
 * here as longs so a decoded command can ALWAYS be represented without throwing; range/semantic
 * validation is a separate, deterministic step ({@link #invalidReason()}) so a structurally
 * garbage config is a counted loud REJECT in the state machine, never a decode crash and never a
 * half-applied engine.</p>
 */
public final class EngineConfigState {

    /** Engine implementation names — the exact vocabulary {@link Engine} already selects by. */
    public static final String IMPL_ARRAY = "array";
    public static final String IMPL_DIRECT = "direct";

    /** Max symbol length: the wire's char[16] Symbol type (order-schema.xml). */
    public static final int MAX_SYMBOL_LENGTH = 16;

    public final long configVersion;      // uint32 on the wire
    /** {@link #IMPL_ARRAY} or {@link #IMPL_DIRECT}; null when the wire carried no valid impl. */
    public final String impl;
    public final long bookCapacity;       // uint32 on the wire; array-impl only (0 for direct)
    public final long maxMatchesPerOrder; // uint32 on the wire
    public final long maxOrdersPerLevel;  // uint32 on the wire; direct-impl only (0 for array)
    /** Sorted ascending by marketId (normalized in {@link #of}). Never null; may be empty (invalid). */
    public final MarketDef[] markets;

    // ---- order-schema v12 (RC2 S1 contract 3.3, RC2 S2 contract 2.1 and 3.1) ----
    // Carried as decoded (pre-12 frames map to the defaults below). They stay EXCLUDED from
    // equalsConfig, equalsIgnoringVersion, canonicalString/canonicalHash and toString (the five S1
    // methods are unchanged, so the #224 canonical hash is unchanged by construction). S2 adds:
    // the structural rules S-b/S-c in invalidReason, the behaviour-1 normalization
    // (normalizedForBehaviourOne), the behaviour-2 equality sameBehaviourTwoConfig (row 8 only),
    // describe() for log lines, and the snapshot identity block (SnapshotCodec marker 3).
    /** Pre-12 default of {@link #behaviorVersion}. */
    public static final long DEFAULT_BEHAVIOR_VERSION = 1L;
    /** Pre-12 default of {@link #commandLedgerCapacity}. */
    public static final long DEFAULT_COMMAND_LEDGER_CAPACITY = 100_000L;
    /** Pre-12 default of {@link #genesisId}. */
    public static final long DEFAULT_GENESIS_ID = 0L;
    /** Pre-12 default of {@link #maxWireVersion}. */
    public static final long DEFAULT_MAX_WIRE_VERSION = 0L;

    public final long behaviorVersion;       // uint32 on the wire
    public final long commandLedgerCapacity; // uint32 on the wire
    public final long genesisId;             // int64 on the wire
    public final long maxWireVersion;        // uint16 on the wire

    /** Largest legal uint32 value (4294967295 is the SBE uint32 null). */
    public static final long UINT32_MAX_VALUE = 4_294_967_294L;
    /** Smallest recordable wire ceiling at behaviour >= 2: template 9 needs order-schema v12. */
    public static final long MIN_MAX_WIRE_VERSION = 12L;
    /** Largest legal uint16 value (65535 is the SBE uint16 null). */
    public static final long UINT16_MAX_VALUE = 65_534L;

    /** One market's engine-creation values. Immutable. */
    public static final class MarketDef {
        public final int marketId;
        public final String symbol;
        public final long minPrice;  // fixed-point 8dp; the engine's basePrice
        public final long maxPrice;  // fixed-point 8dp
        public final long tickSize;  // fixed-point 8dp

        public MarketDef(int marketId, String symbol, long minPrice, long maxPrice, long tickSize) {
            this.marketId = marketId;
            this.symbol = symbol;
            this.minPrice = minPrice;
            this.maxPrice = maxPrice;
            this.tickSize = tickSize;
        }

        boolean sameAs(MarketDef o) {
            return marketId == o.marketId
                    && java.util.Objects.equals(symbol, o.symbol)
                    && minPrice == o.minPrice
                    && maxPrice == o.maxPrice
                    && tickSize == o.tickSize;
        }

        @Override
        public String toString() {
            return "{marketId=" + marketId + " symbol=" + symbol + " minPrice=" + minPrice
                    + " maxPrice=" + maxPrice + " tickSize=" + tickSize + "}";
        }
    }

    private EngineConfigState(long configVersion, String impl, long bookCapacity,
                              long maxMatchesPerOrder, long maxOrdersPerLevel, MarketDef[] markets,
                              long behaviorVersion, long commandLedgerCapacity, long genesisId,
                              long maxWireVersion) {
        this.configVersion = configVersion;
        this.impl = impl;
        this.bookCapacity = bookCapacity;
        this.maxMatchesPerOrder = maxMatchesPerOrder;
        this.maxOrdersPerLevel = maxOrdersPerLevel;
        this.markets = markets;
        this.behaviorVersion = behaviorVersion;
        this.commandLedgerCapacity = commandLedgerCapacity;
        this.genesisId = genesisId;
        this.maxWireVersion = maxWireVersion;
    }

    /**
     * Build a normalized config: the markets array is defensively copied and sorted ascending by
     * {@code marketId}. Never throws for garbage values — validity is {@link #invalidReason()}.
     */
    public static EngineConfigState of(long configVersion, String impl, long bookCapacity,
                                       long maxMatchesPerOrder, long maxOrdersPerLevel,
                                       MarketDef[] markets) {
        return of(configVersion, impl, bookCapacity, maxMatchesPerOrder, maxOrdersPerLevel, markets,
                DEFAULT_BEHAVIOR_VERSION, DEFAULT_COMMAND_LEDGER_CAPACITY, DEFAULT_GENESIS_ID,
                DEFAULT_MAX_WIRE_VERSION);
    }

    /** {@link #of} with the four v12 values (carried, excluded from every comparison in S1). */
    public static EngineConfigState of(long configVersion, String impl, long bookCapacity,
                                       long maxMatchesPerOrder, long maxOrdersPerLevel,
                                       MarketDef[] markets, long behaviorVersion,
                                       long commandLedgerCapacity, long genesisId,
                                       long maxWireVersion) {
        final MarketDef[] sorted = markets == null ? new MarketDef[0] : markets.clone();
        Arrays.sort(sorted, Comparator.comparingInt(m -> m.marketId));
        return new EngineConfigState(configVersion, impl, bookCapacity,
                maxMatchesPerOrder, maxOrdersPerLevel, sorted, behaviorVersion,
                commandLedgerCapacity, genesisId, maxWireVersion);
    }

    /**
     * Deterministic structural validation — a pure function of this object's fields (which came
     * only from the command bytes), so every replica computes the same answer.
     *
     * @return a human-readable reason this config can NOT be used to create engines, or
     *         {@code null} if it is structurally valid. Invalid configs are loud, counted rejects
     *         in the state machine; they are never recorded and never half-applied.
     */
    public String invalidReason() {
        if (!IMPL_ARRAY.equals(impl) && !IMPL_DIRECT.equals(impl)) {
            return "impl is missing or unknown (wire byte did not decode to ARRAY or DIRECT)";
        }
        if (maxMatchesPerOrder < 1 || maxMatchesPerOrder > Integer.MAX_VALUE) {
            return "maxMatchesPerOrder=" + maxMatchesPerOrder + " must be in [1, " + Integer.MAX_VALUE + "]";
        }
        if (IMPL_ARRAY.equals(impl)) {
            if (bookCapacity < 1 || bookCapacity > Integer.MAX_VALUE) {
                return "bookCapacity=" + bookCapacity + " must be in [1, " + Integer.MAX_VALUE
                        + "] for impl=array (its single capacity bound)";
            }
            if (maxOrdersPerLevel != 0) {
                return "maxOrdersPerLevel=" + maxOrdersPerLevel
                        + " must be 0 for impl=array (the array book has no per-level cap)";
            }
        } else { // direct
            if (bookCapacity != 0) {
                return "bookCapacity=" + bookCapacity
                        + " must be 0 for impl=direct (the direct book has no book-wide capacity knob)";
            }
            if (maxOrdersPerLevel < 1 || maxOrdersPerLevel > Integer.MAX_VALUE) {
                return "maxOrdersPerLevel=" + maxOrdersPerLevel + " must be in [1, " + Integer.MAX_VALUE
                        + "] for impl=direct";
            }
        }
        // S-b (RC2 S2 3.1): 0 is a v12 producer that did not set the field; 4294967295 the null.
        if (behaviorVersion < 1 || behaviorVersion > UINT32_MAX_VALUE) {
            return "behaviorVersion=" + behaviorVersion + " must be in [1, " + UINT32_MAX_VALUE + "]";
        }
        // S-c: behaviour >= 2 only. S-d: at behaviour 1 the three v12 values are not checked (the
        // state machine normalizes them) and configVersion keeps today's acceptance, so every
        // behaviour-1 log replays unchanged.
        if (behaviorVersion >= 2) {
            if (configVersion < 1 || configVersion > UINT32_MAX_VALUE) {
                return "configVersion=" + configVersion + " must be in [1, " + UINT32_MAX_VALUE
                        + "] at behaviorVersion " + behaviorVersion + " (journal record 5 carries it)";
            }
            if (genesisId < 1) {
                return "genesisId=" + genesisId + " must be in [1, " + Long.MAX_VALUE
                        + "] at behaviorVersion " + behaviorVersion + " (it becomes meEpoch)";
            }
            if (maxWireVersion < MIN_MAX_WIRE_VERSION || maxWireVersion > UINT16_MAX_VALUE) {
                return "maxWireVersion=" + maxWireVersion + " must be in [" + MIN_MAX_WIRE_VERSION + ", "
                        + UINT16_MAX_VALUE + "] at behaviorVersion " + behaviorVersion;
            }
            if (commandLedgerCapacity < 1 || commandLedgerCapacity > Integer.MAX_VALUE) {
                return "commandLedgerCapacity=" + commandLedgerCapacity + " must be in [1, "
                        + Integer.MAX_VALUE + "] at behaviorVersion " + behaviorVersion;
            }
        }
        if (markets.length == 0) {
            return "markets is empty — a cluster with no markets cannot create engines";
        }
        for (int i = 0; i < markets.length; i++) {
            final MarketDef m = markets[i];
            final String at = "markets[" + i + "] (marketId=" + m.marketId + ")";
            if (m.marketId <= 0) {
                return at + ": marketId must be positive";
            }
            if (i > 0 && markets[i - 1].marketId == m.marketId) {
                return at + ": duplicate marketId";
            }
            if (m.symbol == null || m.symbol.isEmpty() || m.symbol.length() > MAX_SYMBOL_LENGTH) {
                return at + ": symbol must be 1.." + MAX_SYMBOL_LENGTH + " ASCII chars (was "
                        + (m.symbol == null ? "null" : "\"" + m.symbol + "\"") + ")";
            }
            for (int c = 0; c < m.symbol.length(); c++) {
                final char ch = m.symbol.charAt(c);
                if (ch < 0x21 || ch > 0x7E) {
                    // The wire Symbol type is ASCII and the snapshot writes single bytes; anything
                    // else would mangle silently. NUL specifically is the padding byte.
                    return at + ": symbol contains a non-printable or non-ASCII char at index " + c;
                }
            }
            if (m.tickSize <= 0) {
                return at + ": tickSize=" + m.tickSize + " must be positive";
            }
            if (m.minPrice <= 0) {
                return at + ": minPrice=" + m.minPrice + " must be positive";
            }
            if (m.maxPrice <= m.minPrice) {
                return at + ": maxPrice=" + m.maxPrice + " must exceed minPrice=" + m.minPrice;
            }
            final long levels = (m.maxPrice - m.minPrice) / m.tickSize + 1;
            if (levels > Integer.MAX_VALUE) {
                return at + ": (maxPrice-minPrice)/tickSize+1 = " + levels + " price levels does not fit an int";
            }
        }
        return null;
    }

    /**
     * Field-by-field equality, markets compared in sorted order (both sides are normalized by
     * {@link #of}). This is the decision-table comparison: equal = idempotent duplicate,
     * different = deterministic reject.
     */
    public boolean equalsConfig(EngineConfigState o) {
        return configVersion == o.configVersion && equalsIgnoringVersion(o);
    }

    /**
     * {@link #equalsConfig} minus {@code configVersion} — the adopt-path cross-check comparison:
     * a node has no local notion of a config GENERATION, only of the effective values, so the
     * declared version is recorded but not cross-checked.
     */
    public boolean equalsIgnoringVersion(EngineConfigState o) {
        if (!java.util.Objects.equals(impl, o.impl)
                || bookCapacity != o.bookCapacity
                || maxMatchesPerOrder != o.maxMatchesPerOrder
                || maxOrdersPerLevel != o.maxOrdersPerLevel
                || markets.length != o.markets.length) {
            return false;
        }
        for (int i = 0; i < markets.length; i++) {
            if (!markets[i].sameAs(o.markets[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * RC2 S2 row 8: {@link #equalsConfig} AND the four v12 values equal. Used only by the
     * behaviour >= 2 DUPLICATE row; the five S1 comparison/format methods stay as they are.
     */
    public boolean sameBehaviourTwoConfig(EngineConfigState o) {
        return equalsConfig(o)
                && behaviorVersion == o.behaviorVersion
                && commandLedgerCapacity == o.commandLedgerCapacity
                && genesisId == o.genesisId
                && maxWireVersion == o.maxWireVersion;
    }

    /**
     * RC2 S2 2.1: the form a behaviour-1 decision records. The four v12 values are replaced by
     * the pre-12 defaults whatever a v12 frame carried, so replay and restore of a behaviour-1
     * cluster give the same object (a behaviour-1 snapshot has no identity block).
     */
    public EngineConfigState normalizedForBehaviourOne() {
        if (behaviorVersion == DEFAULT_BEHAVIOR_VERSION
                && commandLedgerCapacity == DEFAULT_COMMAND_LEDGER_CAPACITY
                && genesisId == DEFAULT_GENESIS_ID
                && maxWireVersion == DEFAULT_MAX_WIRE_VERSION) {
            return this;
        }
        return new EngineConfigState(configVersion, impl, bookCapacity, maxMatchesPerOrder,
                maxOrdersPerLevel, markets, DEFAULT_BEHAVIOR_VERSION, DEFAULT_COMMAND_LEDGER_CAPACITY,
                DEFAULT_GENESIS_ID, DEFAULT_MAX_WIRE_VERSION);
    }

    /**
     * RC2 S2 row 15 (BUMP): this config with {@code configVersion} and {@code maxWireVersion}
     * replaced; every other field (the other two v12 values and the markets) is kept.
     */
    public EngineConfigState withConfigVersionAndCeiling(long newConfigVersion, long newMaxWireVersion) {
        return new EngineConfigState(newConfigVersion, impl, bookCapacity, maxMatchesPerOrder,
                maxOrdersPerLevel, markets, behaviorVersion, commandLedgerCapacity, genesisId,
                newMaxWireVersion);
    }

    /** {@link #toString} plus the four v12 values, for decision log lines (RC2 S2). */
    public String describe() {
        return toString() + "{behaviorVersion=" + behaviorVersion
                + " commandLedgerCapacity=" + commandLedgerCapacity + " genesisId=" + genesisId
                + " maxWireVersion=" + maxWireVersion + "}";
    }

    @Override
    public String toString() {
        return "EngineConfigState{configVersion=" + configVersion + " impl=" + impl
                + " bookCapacity=" + bookCapacity + " maxMatchesPerOrder=" + maxMatchesPerOrder
                + " maxOrdersPerLevel=" + maxOrdersPerLevel
                + " markets=" + Arrays.toString(markets) + "}";
    }

    // ---- #224: canonical hash — the declared-vs-effective precheck fingerprint ----

    /**
     * The impl as the SBE {@code EngineImpl} wire value: 0 = array, 1 = direct, -1 = unknown/null
     * (unreachable for a validated config). Exported as {@code match_engine_effective_impl}.
     */
    public int implWireValue() {
        if (IMPL_ARRAY.equals(impl)) {
            return 0;
        }
        if (IMPL_DIRECT.equals(impl)) {
            return 1;
        }
        return -1;
    }

    /**
     * The CANONICAL STRING this config's {@link #canonicalHash()} is computed over. This format
     * is a cross-language contract: cloud-console (Go) computes the DECLARED side of the
     * declared-vs-effective precheck (#224) from the exact same format, so any change here is a
     * breaking protocol change — bump the {@code engine-config-v1} prefix if the format ever has
     * to evolve, and keep {@code EngineConfigCanonicalHashTest.canonicalStringFormatIsPinned}
     * in sync (it pins this format literally).
     *
     * <p><b>EXACT FORMAT</b> (authoritative; field order fixed):</p>
     * <pre>
     * engine-config-v1|impl=&lt;impl&gt;|bookCapacity=&lt;n&gt;|maxMatchesPerOrder=&lt;n&gt;|maxOrdersPerLevel=&lt;n&gt;|markets=&lt;count&gt;|&lt;marketId&gt;,&lt;symbol&gt;,&lt;minPrice&gt;,&lt;maxPrice&gt;,&lt;tickSize&gt;[|&lt;marketId&gt;,...]
     * </pre>
     * <ul>
     *   <li>{@code configVersion} is EXCLUDED — the precheck compares VALUES, exactly like the
     *       adopt cross-check ({@link #equalsIgnoringVersion}); a node's effective values have no
     *       local config generation.</li>
     *   <li>{@code impl} is the literal string {@code array} or {@code direct}.</li>
     *   <li>Every number is base-10 ASCII, no sign prefix, no leading zeros (Java
     *       {@code Long.toString} / Go {@code strconv.FormatInt}); the uint32-ranged fields and
     *       the fixed-point-8dp prices are printed as their integer values.</li>
     *   <li>Markets in ascending {@code marketId} order (this class normalizes in {@link #of}),
     *       one {@code |}-segment per market, fields comma-separated in the order
     *       marketId, symbol, minPrice, maxPrice, tickSize. No trailing separator.</li>
     * </ul>
     *
     * <p><b>Hash</b>: UTF-8 bytes of this string → SHA-256 → first 8 bytes as an UNSIGNED 64-bit
     * big-endian integer. Go reference (copy-paste; markets pre-sorted ascending by marketId):</p>
     * <pre>
     * s := fmt.Sprintf("engine-config-v1|impl=%s|bookCapacity=%d|maxMatchesPerOrder=%d|maxOrdersPerLevel=%d|markets=%d",
     *         impl, bookCapacity, maxMatchesPerOrder, maxOrdersPerLevel, len(markets))
     * for _, m := range markets {
     *         s += fmt.Sprintf("|%d,%s,%d,%d,%d", m.MarketID, m.Symbol, m.MinPrice, m.MaxPrice, m.TickSize)
     * }
     * sum := sha256.Sum256([]byte(s))
     * hash := binary.BigEndian.Uint64(sum[:8])
     * </pre>
     *
     * <p><b>Test vector</b> (also pinned in EngineConfigCanonicalHashTest):</p>
     * <pre>
     * engine-config-v1|impl=array|bookCapacity=4096|maxMatchesPerOrder=100|maxOrdersPerLevel=0|markets=2|7,AAA-USD,500000000,1500000000,500000|42,ZZZ-USD,1000000000,2000000000,1000000
     * → hash 251824785580910312
     * </pre>
     */
    public String canonicalString() {
        final StringBuilder sb = new StringBuilder(128)
                .append("engine-config-v1")
                .append("|impl=").append(impl)
                .append("|bookCapacity=").append(bookCapacity)
                .append("|maxMatchesPerOrder=").append(maxMatchesPerOrder)
                .append("|maxOrdersPerLevel=").append(maxOrdersPerLevel)
                .append("|markets=").append(markets.length);
        for (MarketDef m : markets) {
            sb.append('|').append(m.marketId)
              .append(',').append(m.symbol)
              .append(',').append(m.minPrice)
              .append(',').append(m.maxPrice)
              .append(',').append(m.tickSize);
        }
        return sb.toString();
    }

    /**
     * SHA-256 of {@link #canonicalString()} (UTF-8), first 8 bytes as an unsigned 64-bit
     * big-endian integer, carried in a Java {@code long} (same bits). Exported as the
     * {@code match_engine_effective_config_hash} gauge — rendered UNSIGNED on /metrics so the Go
     * side parses it with {@code strconv.ParseUint}. Identity only, not magnitude: values above
     * 2^53 are lossy in a float64 TSDB, so comparisons must use the scraped text (cloud-console
     * scrapes /metrics directly). Allocates; scrape/decision path only, never the order path.
     */
    public long canonicalHash() {
        final byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonicalString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is JDK-mandated and missing", e);
        }
        long h = 0;
        for (int i = 0; i < 8; i++) {
            h = (h << 8) | (digest[i] & 0xFFL);
        }
        return h;
    }
}
