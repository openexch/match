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
 * </pre>
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

        Decoded(long orderIdGenerator, long tradeIdGenerator, long timerCorrelationId,
                boolean timerCorrelationIdPresent, int rejectedOrders, int bytesConsumed) {
            this.orderIdGenerator = orderIdGenerator;
            this.tradeIdGenerator = tradeIdGenerator;
            this.timerCorrelationId = timerCorrelationId;
            this.timerCorrelationIdPresent = timerCorrelationIdPresent;
            this.rejectedOrders = rejectedOrders;
            this.bytesConsumed = bytesConsumed;
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

        return pos;
    }

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

        int rejected = 0;
        for (int m = 0; m < numMarkets; m++) {
            final int marketId = src.getInt(pos);
            pos += 4;

            final MatchingEngine matchingEngine = engine.getEngine(marketId);
            if (matchingEngine == null) {
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

        return new Decoded(orderIdGen, tradeIdGen, timerCorrelationId, timerPresent,
                rejected, pos - offset);
    }
}
