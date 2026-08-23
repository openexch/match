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
}
