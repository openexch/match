// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import com.match.application.orderbook.MatchingEngine;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;

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

        return new Decoded(orderIdGen, tradeIdGen, timerCorrelationId, timerPresent,
                rejected, pos - offset);
    }
}
