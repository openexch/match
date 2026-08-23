// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;

import java.util.Arrays;

/**
 * Pure serialize / deserialize of matching-engine snapshot state to and from a byte buffer,
 * with NO Aeron dependency.
 *
 * <p>Extracted verbatim from {@code AppClusteredService} so the snapshot format — the thing
 * every restart and failover recovers from — has a single source of truth that can be
 * unit-tested for exact round-trip fidelity and byte-level determinism. The byte layout is
 * unchanged from the inline version, so snapshots written before this extraction still
 * recover.</p>
 *
 * <p><b>BYTE FORMAT (must remain stable — live recovery depends on it):</b></p>
 * <pre>
 *   [orderIdGen   : long]
 *   [tradeIdGen   : long]
 *   [numMarkets   : int]
 *   repeat numMarkets:
 *     [marketId      : int]
 *     [numBidOrders  : int][bidOrders : numBidOrders * 4 longs]   // orderId, userId, price, qty
 *     [numAskOrders  : int][askOrders : numAskOrders * 4 longs]
 *   [timerCorrelationId : long]   // trailing; pre-match#25 snapshots may omit it
 *   [omsMapCount : int]           // v9 (A-1); orderId -> omsOrderId correlation map
 *   repeat omsMapCount:           // written in ASCENDING orderId order for cross-replica determinism
 *     [orderId : long][omsOrderId : long]
 *   [engineConfigPresent : byte = 1]   // slice C; the WHOLE block below is written ONLY when a
 *     [configVersion : long]           // config is recorded. With no config NOTHING is appended —
 *     [impl : byte]                    // a config-less snapshot stays bit-for-bit identical to
 *     [bookCapacity : int]             // today's (the slice C red line). Deserialize is trailing-
 *     [maxMatchesPerOrder : int]       // tolerant: clean EOF where this block would start = no
 *     [maxOrdersPerLevel : int]        // config (legacy snapshot), NOT an error.
 *     [numConfigMarkets : int]         // impl byte: 0=array, 1=direct (EngineImpl wire values).
 *     repeat numConfigMarkets:         // markets in ASCENDING marketId order (the normalized form).
 *       [marketId : int][symbol : 16 bytes ASCII, NUL-padded]
 *       [minPrice : long][maxPrice : long][tickSize : long]
 * </pre>
 *
 * <p><b>Slice C note for future tails:</b> because the engineConfig block is written only when
 * present, any FUTURE trailing block must begin with a marker distinguishable from this block's
 * present byte (1) or make the config block unconditional first.</p>
 *
 * <p>All scalars use the buffer's native byte order (Agrona default), identical to the
 * historical inline encode/decode.</p>
 */
public final class SnapshotCodec {

    private SnapshotCodec() {
    }

    /**
     * Scalars recovered from a snapshot that the caller must apply to its own collaborators
     * (the {@code Engine}'s order-id generator is applied in-place by {@link #deserialize};
     * {@code tradeIdGenerator} goes to the event publisher and {@code timerCorrelationId} to
     * the timer manager).
     */
    public static final class Decoded {
        /** Order-id generator value (already applied to the engine by {@link #deserialize}). */
        public final long orderIdGenerator;
        /** Trade-id generator value — caller must apply to the event publisher. */
        public final long tradeIdGenerator;
        /** Legacy timer correlation counter — caller applies only if {@link #timerCorrelationIdPresent}. */
        public final long timerCorrelationId;
        /** False for pre-match#25 snapshots that don't carry the trailing timer counter. */
        public final boolean timerCorrelationIdPresent;
        /** Orders that could NOT be restored (geometry mismatch / unknown market) — state loss; callers must log. */
        public final int rejectedOrders;
        /** Bytes consumed from the payload (for diagnostics). */
        public final int bytesConsumed;
        /** Slice C: true when the snapshot carried an engineConfig block (already restored into
         *  the engine — {@code engine.getEngineConfig()}). False for legacy/config-less snapshots. */
        public final boolean engineConfigPresent;

        Decoded(long orderIdGenerator, long tradeIdGenerator, long timerCorrelationId,
                boolean timerCorrelationIdPresent, int rejectedOrders, int bytesConsumed,
                boolean engineConfigPresent) {
            this.orderIdGenerator = orderIdGenerator;
            this.tradeIdGenerator = tradeIdGenerator;
            this.timerCorrelationId = timerCorrelationId;
            this.timerCorrelationIdPresent = timerCorrelationIdPresent;
            this.rejectedOrders = rejectedOrders;
            this.bytesConsumed = bytesConsumed;
            this.engineConfigPresent = engineConfigPresent;
        }
    }

    /**
     * Serialize engine state plus the two externally-held scalars into {@code dst}.
     *
     * @param engine             matching engine whose order books + order-id generator are written
     * @param tradeIdGenerator   current trade-id generator value (held by the event publisher)
     * @param timerCorrelationId current timer correlation counter (held by the timer manager)
     * @param dst                destination buffer (an {@code ExpandableArrayBuffer} grows as needed)
     * @return number of bytes written — offer {@code dst, 0, length}
     */
    public static int serialize(Engine engine, long tradeIdGenerator, long timerCorrelationId,
                                MutableDirectBuffer dst) {
        int pos = 0;

        dst.putLong(pos, engine.getOrderIdGenerator());
        pos += 8;

        dst.putLong(pos, tradeIdGenerator);
        pos += 8;

        final Int2ObjectHashMap<MatchingEngine> engines = engine.getEngines();
        dst.putInt(pos, engines.size());
        pos += 4;

        // Iterate exactly as the historical inline encoder did — market order is part of the
        // byte format and must stay stable for byte-identical snapshots.
        //
        // This is safe only because Engine's constructor puts every MarketConfig.ALL_MARKETS entry in,
        // in that fixed order, and nothing is ever removed: the table layout is therefore the same on a
        // node that replayed from genesis and one that restored a snapshot. Make markets lazily created,
        // dynamically listed, or removable and that stops holding — the snapshot bytes would start
        // depending on the order markets were first traded, and comparing two nodes' snapshots would
        // report divergence on books that agree. Sort by marketId here if that day comes. (The assets
        // engine had exactly this defect in its account/hold maps; see BalanceSnapshotCodec.)
        final Int2ObjectHashMap<MatchingEngine>.KeyIterator keyIt = engines.keySet().iterator();
        while (keyIt.hasNext()) {
            final int marketId = keyIt.nextInt();
            final MatchingEngine matchingEngine = engines.get(marketId);

            dst.putInt(pos, marketId);
            pos += 4;

            final long[] bidOrders = matchingEngine.getBidOrders();
            dst.putInt(pos, bidOrders.length / 4);
            pos += 4;
            for (long value : bidOrders) {
                dst.putLong(pos, value);
                pos += 8;
            }

            final long[] askOrders = matchingEngine.getAskOrders();
            dst.putInt(pos, askOrders.length / 4);
            pos += 4;
            for (long value : askOrders) {
                dst.putLong(pos, value);
                pos += 8;
            }
        }

        dst.putLong(pos, timerCorrelationId);
        pos += 8;

        // v9 (A-1): the orderId -> omsOrderId correlation map (maker omsOrderId lookup on a fill).
        // Long2LongHashMap iteration order is NOT deterministic across nodes that inserted in a
        // different sequence, so writing it in native iteration order would fork snapshot bytes
        // between replicas and break byte-determinism / cross-impl tests. Write it in ASCENDING
        // orderId order: collect keys, Arrays.sort, emit (orderId, omsOrderId) pairs. The snapshot
        // is not a hot path, so the sort cost is acceptable.
        final Long2LongHashMap omsMap = engine.getOrderIdToOmsOrderId();
        final int omsMapCount = omsMap.size();
        final long[] sortedOrderIds = new long[omsMapCount];
        int k = 0;
        final Long2LongHashMap.KeyIterator keyIter = omsMap.keySet().iterator();
        while (keyIter.hasNext()) {
            sortedOrderIds[k++] = keyIter.nextValue();
        }
        Arrays.sort(sortedOrderIds);
        dst.putInt(pos, omsMapCount);
        pos += 4;
        for (int i = 0; i < omsMapCount; i++) {
            final long orderId = sortedOrderIds[i];
            dst.putLong(pos, orderId);
            pos += 8;
            dst.putLong(pos, omsMap.get(orderId));
            pos += 8;
        }

        // Slice C: the engineConfig block — appended ONLY when a config is recorded. With no
        // config, NOTHING is written here, so a config-less snapshot is bit-for-bit identical to
        // the pre-slice-C format (the red line: no EngineConfig in the log = identical behavior).
        // Markets are already normalized (marketId-ascending) in EngineConfigState, so these
        // bytes are deterministic across replicas and across serialize passes.
        final com.match.application.engine.EngineConfigState config = engine.getEngineConfig();
        if (config != null) {
            dst.putByte(pos, (byte) 1);
            pos += 1;
            dst.putLong(pos, config.configVersion);
            pos += 8;
            dst.putByte(pos, (byte) (com.match.application.engine.EngineConfigState.IMPL_DIRECT
                    .equals(config.impl) ? 1 : 0)); // EngineImpl wire values: 0=array, 1=direct
            pos += 1;
            dst.putInt(pos, (int) config.bookCapacity);
            pos += 4;
            dst.putInt(pos, (int) config.maxMatchesPerOrder);
            pos += 4;
            dst.putInt(pos, (int) config.maxOrdersPerLevel);
            pos += 4;
            dst.putInt(pos, config.markets.length);
            pos += 4;
            for (final com.match.application.engine.EngineConfigState.MarketDef m : config.markets) {
                dst.putInt(pos, m.marketId);
                pos += 4;
                pos = putPaddedSymbol(dst, pos, m.symbol);
                dst.putLong(pos, m.minPrice);
                pos += 8;
                dst.putLong(pos, m.maxPrice);
                pos += 8;
                dst.putLong(pos, m.tickSize);
                pos += 8;
            }
        }

        return pos;
    }

    /** Slice C: fixed 16-byte ASCII symbol, NUL-padded (mirrors the wire's char[16] Symbol type). */
    private static int putPaddedSymbol(MutableDirectBuffer dst, int pos, String symbol) {
        for (int i = 0; i < SYMBOL_LENGTH; i++) {
            dst.putByte(pos + i, i < symbol.length() ? (byte) symbol.charAt(i) : (byte) 0);
        }
        return pos + SYMBOL_LENGTH;
    }

    private static final int SYMBOL_LENGTH =
            com.match.application.engine.EngineConfigState.MAX_SYMBOL_LENGTH;

    /**
     * Decode a complete (reassembled) snapshot payload into {@code engine}, restoring its order
     * books and order-id generator. The trade-id generator and timer correlation counter are
     * returned in {@link Decoded} for the caller to apply to its publisher / timer manager.
     *
     * @param src    source buffer
     * @param offset start of the payload within {@code src}
     * @param length payload length in bytes
     * @param engine engine to restore into (its books are cleared and repopulated)
     */
    public static Decoded deserialize(DirectBuffer src, int offset, int length, Engine engine) {
        int pos = offset;
        final int end = offset + length;

        final long orderIdGen = src.getLong(pos);
        pos += 8;
        engine.setOrderIdGenerator(orderIdGen);

        final long tradeIdGen = src.getLong(pos);
        pos += 8;

        final int numMarkets = src.getInt(pos);
        pos += 4;

        // Slice C: on a config-mode node the engine map is EMPTY here — its engines can only be
        // built from the engineConfig block, which sits AFTER the market books in the byte
        // layout. Park each market's book arrays and restore them once the config has rebuilt
        // the engines. On a legacy node (engines exist) parking never happens and an unknown
        // market keeps today's exact skip behavior.
        final boolean enginesWereEmpty = !engine.hasEngines();
        final java.util.ArrayList<long[][]> parkedBooks = new java.util.ArrayList<>(); // {bid, ask}
        final java.util.ArrayList<Integer> parkedMarketIds = new java.util.ArrayList<>();

        int rejected = 0;
        for (int m = 0; m < numMarkets; m++) {
            final int marketId = src.getInt(pos);
            pos += 4;

            final MatchingEngine matchingEngine = engine.getEngine(marketId);
            if (matchingEngine == null && !enginesWereEmpty) {
                // Unknown market in this build — skip its bytes to keep parsing aligned.
                final int numBidOrders = src.getInt(pos);
                pos += 4;
                pos += numBidOrders * 4 * 8;
                final int numAskOrders = src.getInt(pos);
                pos += 4;
                pos += numAskOrders * 4 * 8;
                continue;
            }

            final int numBidOrders = src.getInt(pos);
            pos += 4;
            final long[] bidOrders = new long[numBidOrders * 4];
            for (int i = 0; i < bidOrders.length; i++) {
                bidOrders[i] = src.getLong(pos);
                pos += 8;
            }

            final int numAskOrders = src.getInt(pos);
            pos += 4;
            final long[] askOrders = new long[numAskOrders * 4];
            for (int i = 0; i < askOrders.length; i++) {
                askOrders[i] = src.getLong(pos);
                pos += 8;
            }

            if (matchingEngine == null) {
                // Config-mode fresh restore: engines don't exist yet — park until the config
                // block (below) has rebuilt them.
                parkedMarketIds.add(marketId);
                parkedBooks.add(new long[][]{bidOrders, askOrders});
                continue;
            }
            rejected += matchingEngine.restoreFromSnapshot(bidOrders, askOrders);
        }

        long timerCorrelationId = 0;
        boolean timerPresent = false;
        if (pos + 8 <= end) {
            timerCorrelationId = src.getLong(pos);
            pos += 8;
            timerPresent = true;
        }

        // v9 (A-1): restore the orderId -> omsOrderId map, written ascending by serialize(). Cleared
        // first so a reused engine can't retain stale entries. Guarded like the trailing timer field
        // so a hand-built partial buffer (unit tests) or a pre-v9 snapshot lacking this section
        // decodes cleanly; a real v9 snapshot always carries at least the 4-byte count. Restored
        // directly into the engine — no Decoded field is needed.
        final Long2LongHashMap omsMap = engine.getOrderIdToOmsOrderId();
        omsMap.clear();
        if (pos + 4 <= end) {
            final int omsMapCount = src.getInt(pos);
            pos += 4;
            for (int i = 0; i < omsMapCount; i++) {
                final long orderId = src.getLong(pos);
                pos += 8;
                final long omsOrderId = src.getLong(pos);
                pos += 8;
                omsMap.put(orderId, omsOrderId);
            }
        }

        // Slice C: the trailing engineConfig block. TRAILING-TOLERANT: clean EOF here means the
        // snapshot predates slice C or was taken with no config recorded — NOT an error. A
        // present byte of 0 is likewise "explicitly no config". Only present byte 1 carries the
        // block; a truncated block then fails loudly (buffer bounds), which is correct for a
        // corrupt snapshot.
        boolean engineConfigPresent = false;
        if (pos + 1 <= end && src.getByte(pos) == 1) {
            pos += 1;
            final long configVersion = src.getLong(pos);
            pos += 8;
            final byte implByte = src.getByte(pos);
            pos += 1;
            final long bookCapacity = src.getInt(pos);
            pos += 4;
            final long maxMatchesPerOrder = src.getInt(pos);
            pos += 4;
            final long maxOrdersPerLevel = src.getInt(pos);
            pos += 4;
            final int numConfigMarkets = src.getInt(pos);
            pos += 4;
            final com.match.application.engine.EngineConfigState.MarketDef[] defs =
                    new com.match.application.engine.EngineConfigState.MarketDef[numConfigMarkets];
            for (int i = 0; i < numConfigMarkets; i++) {
                final int marketId = src.getInt(pos);
                pos += 4;
                final String symbol = getPaddedSymbol(src, pos);
                pos += SYMBOL_LENGTH;
                final long minPrice = src.getLong(pos);
                pos += 8;
                final long maxPrice = src.getLong(pos);
                pos += 8;
                final long tickSize = src.getLong(pos);
                pos += 8;
                defs[i] = new com.match.application.engine.EngineConfigState.MarketDef(
                        marketId, symbol, minPrice, maxPrice, tickSize);
            }
            final com.match.application.engine.EngineConfigState config =
                    com.match.application.engine.EngineConfigState.of(configVersion,
                            implByte == 1 ? com.match.application.engine.EngineConfigState.IMPL_DIRECT
                                          : com.match.application.engine.EngineConfigState.IMPL_ARRAY,
                            bookCapacity, maxMatchesPerOrder, maxOrdersPerLevel, defs);
            engineConfigPresent = true;

            if (enginesWereEmpty) {
                // Config-mode fresh-from-snapshot: rebuild the engines from the recorded config —
                // identically to the original creation (same normalized market order) — then
                // restore the parked books. A recorded config was validated at accept time, so an
                // invalid one here means snapshot corruption: fail the boot loudly rather than
                // build a half-wrong engine.
                final String invalid = config.invalidReason();
                if (invalid != null) {
                    throw new IllegalStateException(
                            "[SNAPSHOT] engineConfig block is invalid (corrupt snapshot?): " + invalid);
                }
                engine.createEnginesFromConfig(config);
                for (int i = 0; i < parkedMarketIds.size(); i++) {
                    final int marketId = parkedMarketIds.get(i);
                    final MatchingEngine me = engine.getEngine(marketId);
                    final long[][] books = parkedBooks.get(i);
                    if (me == null) {
                        // A market with books in the snapshot but absent from the recorded config
                        // — state loss; count every order loudly, never drop silently.
                        final int lost = books[0].length / 4 + books[1].length / 4;
                        rejected += lost;
                        System.err.println("[SNAPSHOT] ERROR: market " + marketId + " has " + lost
                                + " order(s) in the snapshot but is NOT in the recorded engineConfig"
                                + " — orders dropped (state loss)");
                        continue;
                    }
                    rejected += me.restoreFromSnapshot(books[0], books[1]);
                }
            } else {
                // Legacy-mode (env-built) engines restoring a config-bearing snapshot: record the
                // declared truth without recreating anything (the adopt shape). Whether THIS node
                // matches it is the caller's cross-check (AppClusteredService fail-fast).
                engine.setEngineConfig(config);
            }
        } else if (enginesWereEmpty && !parkedMarketIds.isEmpty()) {
            // Config-mode node restoring a snapshot that has market books but NO config block:
            // nothing to build engines from — this is state loss and must be loud, never silent.
            int lost = 0;
            for (final long[][] books : parkedBooks) {
                lost += books[0].length / 4 + books[1].length / 4;
            }
            rejected += lost;
            System.err.println("[SNAPSHOT] ERROR: snapshot carries " + lost + " order(s) across "
                    + parkedMarketIds.size() + " market(s) but no engineConfig block, and this"
                    + " config-mode node has no engines to restore into — orders dropped (state"
                    + " loss). Was this node switched to MATCH_ENGINE_FROM_CONFIG with a legacy"
                    + " snapshot?");
        }

        return new Decoded(orderIdGen, tradeIdGen, timerCorrelationId, timerPresent,
                rejected, pos - offset, engineConfigPresent);
    }

    /** Slice C: read the fixed 16-byte NUL-padded ASCII symbol written by {@link #putPaddedSymbol}. */
    private static String getPaddedSymbol(DirectBuffer src, int pos) {
        final byte[] raw = new byte[SYMBOL_LENGTH];
        src.getBytes(pos, raw);
        int len = 0;
        while (len < SYMBOL_LENGTH && raw[len] != 0) {
            len++;
        }
        return new String(raw, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
