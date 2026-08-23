// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Immutable, replicated engine-creation config (slice C of behaviour versioning).
 *
 * <p>This is the in-memory form of a logged {@code EngineConfig} command (SBE v10, template 8):
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
                              long maxMatchesPerOrder, long maxOrdersPerLevel, MarketDef[] markets) {
        this.configVersion = configVersion;
        this.impl = impl;
        this.bookCapacity = bookCapacity;
        this.maxMatchesPerOrder = maxMatchesPerOrder;
        this.maxOrdersPerLevel = maxOrdersPerLevel;
        this.markets = markets;
    }

    /**
     * Build a normalized config: the markets array is defensively copied and sorted ascending by
     * {@code marketId}. Never throws for garbage values — validity is {@link #invalidReason()}.
     */
    public static EngineConfigState of(long configVersion, String impl, long bookCapacity,
                                       long maxMatchesPerOrder, long maxOrdersPerLevel,
                                       MarketDef[] markets) {
        final MarketDef[] sorted = markets == null ? new MarketDef[0] : markets.clone();
        Arrays.sort(sorted, Comparator.comparingInt(m -> m.marketId));
        return new EngineConfigState(configVersion, impl, bookCapacity,
                maxMatchesPerOrder, maxOrdersPerLevel, sorted);
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
