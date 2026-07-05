// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

/**
 * A per-market matching engine: holds a bid book and an ask book, matches incoming
 * orders against the opposing book, and exposes match results, top-of-book market
 * data, and snapshot serialization.
 *
 * <p>This interface is the seam that lets two implementations coexist behind a flag
 * (see {@code Engine}): the preallocated direct-index {@link DirectMatchingEngine}
 * and the array-backed {@code ArrayMatchingEngine}. Output is captured at the
 * {@code Engine.acceptOrder -> MatchEventSink} boundary, so the determinism corpus
 * can assert the two implementations are behaviorally identical.
 *
 * <p><b>Threading:</b> all order-processing and snapshot methods run on the single
 * cluster/matching thread. The {@code getTop*} / {@code getCollected*Version}
 * accessors are read by the market-data flush thread; implementations must make that
 * read safe (the array-backed engine publishes a seqlock-protected snapshot rather
 * than letting the reader walk a mutating structure).
 */
public interface MatchingEngine {

    // ==================== Order processing (hot path) ====================

    /** Process a limit order against the opposing book, resting any remainder. Returns match count. */
    int processLimitOrder(long orderId, long userId, boolean isBuy, long price, long quantity);

    /** Process a market order (buy uses budget, sell uses quantity). Returns match count. */
    int processMarketOrder(long orderId, long userId, boolean isBuy, long quantity, long budget);

    /** Add an order directly without matching (LIMIT_MAKER). Returns an OrderRejectReason code. */
    int addOrderNoMatch(long orderId, long userId, boolean isBuy, long price, long quantity);

    /** Cancel a resting order on the given side. Returns true if it was present and removed. */
    boolean cancelOrder(long orderId, boolean isBuy);

    // ==================== Validation ====================

    /** Validate a limit price against this market's rules. Returns an OrderRejectReason code. */
    int validateLimitPrice(long price);

    /** Reason the most recent processLimitOrder remainder failed to rest (NONE if it rested/filled). */
    int getLastRestRejectReason();

    // ==================== Match results ====================

    int getMatchCount();
    long getMatchMakerOrderId(int matchIndex);
    long getMatchMakerUserId(int matchIndex);
    long getMatchPrice(int matchIndex);
    long getMatchQuantity(int matchIndex);
    long getTakerRemainingQuantity();
    long getTakerRemainingBudget();

    // ==================== Top of book ====================

    long getBestAsk();
    long getBestBid();
    boolean isAskEmpty();
    boolean isBidEmpty();

    /** Monotonic version of the bid book, bumped on every modification (cross-thread change detection). */
    long getBidVersion();

    /** Monotonic version of the ask book, bumped on every modification (cross-thread change detection). */
    long getAskVersion();

    // ==================== Top-N market data ====================

    /**
     * Refresh this engine's published top-of-book snapshot. Called on the cluster/writer thread
     * after each command. Implementations whose {@link #collectTopLevels} reader walks a structure
     * that mutates concurrently (e.g. the array-backed tree) publish a stable snapshot here for the
     * market-data flush thread to read; implementations with a positionally-stable book (the
     * preallocated direct-index book) may leave this a no-op.
     */
    void publishTopOfBook();

    /** Collect the top N levels of both books into the pre-allocated top-* buffers. */
    void collectTopLevels(int maxLevels);

    int getTopBidCount();
    int getTopAskCount();
    long[] getTopBidPrices();
    long[] getTopBidQuantities();
    int[] getTopBidOrderCounts();
    long[] getTopAskPrices();
    long[] getTopAskQuantities();
    int[] getTopAskOrderCounts();
    long getCollectedBidVersion();
    long getCollectedAskVersion();
    long getCollectedVersion();

    // ==================== Book version (market-data chain) ====================

    /**
     * Set the single monotonic per-book version. Written on the cluster/writer thread before each
     * mutating command, derived from the Aeron log position ({@code max(current+1, logPosition)}),
     * so it is deterministic across replicas and never regresses across restarts or failovers.
     * Published together with the top-of-book snapshot; chains market-data snapshots and deltas
     * for client-side stitching (a client can buffer deltas, fetch a snapshot, and replay the
     * deltas whose {@code fromVersion} continues the snapshot's version).
     */
    void setBookVersion(long version);

    /** Current book version (writer thread). */
    long getBookVersion();

    /** Book version captured by the last {@link #collectTopLevels} (flush thread). */
    long getCollectedBookVersion();

    // ==================== Snapshot ====================

    /** All resting bid orders as flat [orderId, userId, price, qty] 4-tuples. */
    long[] getBidOrders();

    /** All resting ask orders as flat [orderId, userId, price, qty] 4-tuples. */
    long[] getAskOrders();

    /**
     * Clear both books and restore from snapshot 4-tuples.
     *
     * @return number of orders that could not be restored (loud state loss; callers must report).
     */
    int restoreFromSnapshot(long[] bidOrders, long[] askOrders);

    /** Clear all state in both books. */
    void clear();
}
