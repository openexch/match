package com.match.application.orderbook;

import com.match.domain.FixedPoint;

import java.lang.invoke.VarHandle;

/**
 * Array-backed matching engine: holds a bid and an ask {@link ArrayOrderBook} and matches against
 * the opposing side. Drop-in replacement for {@link DirectMatchingEngine} behind {@link MatchingEngine}.
 *
 * <p>The match loop is structurally identical to the direct-index engine — only "price index"
 * becomes "level node id", and the level price comes from the node ({@link ArrayOrderBook#getLevelPrice})
 * instead of {@code base + idx*tick}. The sweep navigates by price value ({@link ArrayOrderBook#nextLevel}),
 * so it is robust even when a fully-consumed level is deleted from the tree mid-match.
 *
 * <p><b>Zero hot-path allocation.</b> Match results are written into a preallocated array.
 *
 * <p><b>Cross-thread market data.</b> The market-data flush thread calls {@link #collectTopLevels}
 * concurrently with matching. An AA tree mutates structurally, so the flush thread must not walk it.
 * Instead the writer (cluster thread) refreshes a top-of-book snapshot in {@link #publishTopOfBook}
 * after each command, guarded by a seqlock; {@link #collectTopLevels} is a lock-free seqlock read of
 * that snapshot. Price validity is enforced by {@link PriceRules} (re-homed from the book).
 */
public final class ArrayMatchingEngine implements MatchingEngine {

    private static final int NIL = 0;

    private static final int MAX_MATCHES_PER_ORDER = 10_000;
    private static final int MATCH_FIELDS = 4; // makerOrderId, makerUserId, price, quantity

    private final ArrayOrderBook askBook;
    private final ArrayOrderBook bidBook;
    private final PriceRules priceRules;

    // Match output (preallocated; written on the matching thread)
    private final long[] matchResults;
    private int matchCount;

    // Taker progress
    private long takerRemainingQty;
    private long takerRemainingBudget;
    private int lastRestRejectReason = OrderRejectReason.NONE;

    public ArrayMatchingEngine(long basePrice, long maxPrice, long tickSize, int capacity) {
        this.askBook = new ArrayOrderBook(true, capacity);   // ascending: lowest ask is best
        this.bidBook = new ArrayOrderBook(false, capacity);  // descending: highest bid is best
        this.priceRules = new PriceRules(basePrice, maxPrice, tickSize);
        this.matchResults = new long[MAX_MATCHES_PER_ORDER * MATCH_FIELDS];
    }

    public PriceRules getPriceRules() {
        return priceRules;
    }

    // ==================== Validation ====================

    public int validateLimitPrice(long price) {
        return priceRules.validate(price);
    }

    public int getLastRestRejectReason() {
        return lastRestRejectReason;
    }

    // ==================== Order processing ====================

    public int processLimitOrder(long orderId, long userId, boolean isBuy, long price, long quantity) {
        matchCount = 0;
        takerRemainingQty = quantity;
        lastRestRejectReason = OrderRejectReason.NONE;

        ArrayOrderBook makerBook = isBuy ? askBook : bidBook;
        ArrayOrderBook takerBook = isBuy ? bidBook : askBook;

        if (!makerBook.isEmpty()) {
            int node = makerBook.getBestLevel();
            while (node != NIL && takerRemainingQty > 0) {
                long levelPrice = makerBook.getLevelPrice(node);
                // Price compatibility: buy crosses asks <= price; sell crosses bids >= price.
                if (isBuy ? levelPrice > price : levelPrice < price) break;
                matchAtLevel(makerBook, node, levelPrice);
                node = makerBook.nextLevel(levelPrice);
            }
        }

        if (takerRemainingQty > 0) {
            lastRestRejectReason = takerBook.addOrder(orderId, userId, price, takerRemainingQty);
        }
        return matchCount;
    }

    public int processMarketOrder(long orderId, long userId, boolean isBuy, long quantity, long budget) {
        matchCount = 0;

        ArrayOrderBook makerBook = isBuy ? askBook : bidBook;
        if (makerBook.isEmpty()) {
            return 0;
        }

        if (isBuy) {
            takerRemainingBudget = budget;
            int node = makerBook.getBestLevel();
            while (node != NIL && takerRemainingBudget > 0) {
                long levelPrice = makerBook.getLevelPrice(node);
                matchMarketBuyAtLevel(makerBook, node, levelPrice);
                node = makerBook.nextLevel(levelPrice);
            }
        } else {
            takerRemainingQty = quantity;
            int node = makerBook.getBestLevel();
            while (node != NIL && takerRemainingQty > 0) {
                long levelPrice = makerBook.getLevelPrice(node);
                matchAtLevel(makerBook, node, levelPrice);
                node = makerBook.nextLevel(levelPrice);
            }
        }
        return matchCount;
    }

    /** Match the taker (quantity-based) against orders at one level, FIFO. */
    private void matchAtLevel(ArrayOrderBook book, int node, long price) {
        while (takerRemainingQty > 0 && book.getOrderCount(node) > 0) {
            if (matchCount >= MAX_MATCHES_PER_ORDER) {
                System.err.println("WARNING: Match limit reached (" + MAX_MATCHES_PER_ORDER
                    + ") for order. Remaining qty: " + takerRemainingQty);
                return;
            }

            long makerOrderId = book.headOrderId(node);
            if (makerOrderId < 0) break;
            long makerUserId = book.headOrderUserId(node);
            long makerQty = book.headOrderQuantity(node);
            long matchQty = Math.min(takerRemainingQty, makerQty);
            if (matchQty <= 0) break;

            int idx = matchCount * MATCH_FIELDS;
            matchResults[idx] = makerOrderId;
            matchResults[idx + 1] = makerUserId;
            matchResults[idx + 2] = price;
            matchResults[idx + 3] = matchQty;
            matchCount++;

            takerRemainingQty -= matchQty;
            // reduceHead may delete the level (poisoning `node`); stop touching it if so.
            if (book.reduceHead(node, matchQty)) break;
        }
    }

    /** Match a market buy (budget-based) against orders at one level. */
    private void matchMarketBuyAtLevel(ArrayOrderBook book, int node, long price) {
        while (takerRemainingBudget > 0 && book.getOrderCount(node) > 0) {
            if (matchCount >= MAX_MATCHES_PER_ORDER) {
                System.err.println("WARNING: Match limit reached (" + MAX_MATCHES_PER_ORDER
                    + ") for market buy order. Remaining budget: " + takerRemainingBudget);
                return;
            }

            long makerOrderId = book.headOrderId(node);
            if (makerOrderId < 0) break;
            long makerUserId = book.headOrderUserId(node);
            long makerQty = book.headOrderQuantity(node);

            long maxBuyQty = FixedPoint.divide(takerRemainingBudget, price);
            long matchQty = Math.min(maxBuyQty, makerQty);
            if (matchQty <= 0) break;

            // NOTE: preserved verbatim from DirectMatchingEngine. multiply(qty, price) carries a
            // known latent overflow for very large prices (tracked separately, intentionally NOT
            // fixed here so determinism goldens do not shift).
            long cost = FixedPoint.multiply(matchQty, price);

            int idx = matchCount * MATCH_FIELDS;
            matchResults[idx] = makerOrderId;
            matchResults[idx + 1] = makerUserId;
            matchResults[idx + 2] = price;
            matchResults[idx + 3] = matchQty;
            matchCount++;

            takerRemainingBudget -= cost;
            if (book.reduceHead(node, matchQty)) break;
        }
    }

    public boolean cancelOrder(long orderId, boolean isBuy) {
        return (isBuy ? bidBook : askBook).cancelOrder(orderId);
    }

    public int addOrderNoMatch(long orderId, long userId, boolean isBuy, long price, long quantity) {
        return (isBuy ? bidBook : askBook).addOrder(orderId, userId, price, quantity);
    }

    // ==================== Match result access ====================

    public int getMatchCount() { return matchCount; }
    public long getMatchMakerOrderId(int i) { return matchResults[i * MATCH_FIELDS]; }
    public long getMatchMakerUserId(int i) { return matchResults[i * MATCH_FIELDS + 1]; }
    public long getMatchPrice(int i) { return matchResults[i * MATCH_FIELDS + 2]; }
    public long getMatchQuantity(int i) { return matchResults[i * MATCH_FIELDS + 3]; }
    public long getTakerRemainingQuantity() { return takerRemainingQty; }
    public long getTakerRemainingBudget() { return takerRemainingBudget; }

    // ==================== Top of book ====================

    public long getBestAsk() { return askBook.getBestPrice(); }
    public long getBestBid() { return bidBook.getBestPrice(); }
    public boolean isAskEmpty() { return askBook.isEmpty(); }
    public boolean isBidEmpty() { return bidBook.isEmpty(); }
    public long getBidVersion() { return bidBook.getVersion(); }
    public long getAskVersion() { return askBook.getVersion(); }

    // ==================== Cross-thread top-N (seqlock) ====================

    private static final int MAX_PUBLISH_LEVELS = 20;

    // Published snapshot — written by the writer thread, read by the flush thread under `publishSeq`.
    private final long[] pubBidPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] pubBidQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[]  pubBidOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private final long[] pubAskPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] pubAskQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[]  pubAskOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private int pubBidCount;
    private int pubAskCount;
    private long pubBidVersion;
    private long pubAskVersion;

    // Seqlock sequence: even = stable, odd = write in progress. Single writer.
    private volatile long publishSeq = 0;
    private long lastPubBidVersion = -1;
    private long lastPubAskVersion = -1;

    // Flush-thread-local copy (filled by collectTopLevels, read by getTop*). Single reader.
    private final long[] topBidPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] topBidQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[]  topBidOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private final long[] topAskPrices = new long[MAX_PUBLISH_LEVELS];
    private final long[] topAskQuantities = new long[MAX_PUBLISH_LEVELS];
    private final int[]  topAskOrderCounts = new int[MAX_PUBLISH_LEVELS];
    private volatile int topBidCount;
    private volatile int topAskCount;
    private volatile long collectedBidVersion;
    private volatile long collectedAskVersion;

    /**
     * Refresh the published top-of-book snapshot. Writer (cluster) thread only. Dirty-gated on book
     * versions so an unchanged book costs only two volatile reads.
     */
    public void publishTopOfBook() {
        long bv = bidBook.getVersion();
        long av = askBook.getVersion();
        if (bv == lastPubBidVersion && av == lastPubAskVersion) {
            return; // nothing changed since last publish
        }
        lastPubBidVersion = bv;
        lastPubAskVersion = av;

        long s = publishSeq;
        publishSeq = s + 1;            // odd: write in progress (release)
        VarHandle.storeStoreFence();

        pubBidCount = collectSide(bidBook, pubBidPrices, pubBidQuantities, pubBidOrderCounts);
        pubAskCount = collectSide(askBook, pubAskPrices, pubAskQuantities, pubAskOrderCounts);
        pubBidVersion = bv;
        pubAskVersion = av;

        VarHandle.storeStoreFence();
        publishSeq = s + 2;           // even: done (release)
    }

    /** Walk one book best→worse for up to MAX_PUBLISH_LEVELS levels. Writer thread (safe tree walk). */
    private int collectSide(ArrayOrderBook book, long[] prices, long[] quantities, int[] orderCounts) {
        int count = 0;
        if (!book.isEmpty()) {
            int node = book.getBestLevel();
            while (node != NIL && count < MAX_PUBLISH_LEVELS) {
                long qty = book.getTotalQuantity(node);
                if (qty > 0) {
                    long price = book.getLevelPrice(node);
                    prices[count] = price;
                    quantities[count] = qty;
                    orderCounts[count] = book.getOrderCount(node);
                    count++;
                    node = book.nextLevel(price);
                } else {
                    node = book.nextLevel(book.getLevelPrice(node));
                }
            }
        }
        return count;
    }

    /**
     * Lock-free seqlock read of the published snapshot into the flush-thread-local top-* buffers.
     * Flush thread only. Retries on a torn read; on persistent contention keeps the previous buffers
     * (best-effort — this is advisory market data).
     */
    public void collectTopLevels(int maxLevels) {
        for (int attempt = 0; attempt < 16; attempt++) {
            long s1 = publishSeq;            // acquire
            if ((s1 & 1L) != 0L) {
                continue;                    // writer mid-update
            }
            VarHandle.loadLoadFence();

            int bc = pubBidCount;
            int ac = pubAskCount;
            System.arraycopy(pubBidPrices, 0, topBidPrices, 0, bc);
            System.arraycopy(pubBidQuantities, 0, topBidQuantities, 0, bc);
            System.arraycopy(pubBidOrderCounts, 0, topBidOrderCounts, 0, bc);
            System.arraycopy(pubAskPrices, 0, topAskPrices, 0, ac);
            System.arraycopy(pubAskQuantities, 0, topAskQuantities, 0, ac);
            System.arraycopy(pubAskOrderCounts, 0, topAskOrderCounts, 0, ac);
            long bv = pubBidVersion;
            long av = pubAskVersion;

            VarHandle.loadLoadFence();
            if (publishSeq == s1) {          // clean read
                topBidCount = bc;
                topAskCount = ac;
                collectedBidVersion = bv;
                collectedAskVersion = av;
                return;
            }
        }
        // Persistent contention: leave previous top-* in place.
    }

    public int getTopBidCount() { return topBidCount; }
    public int getTopAskCount() { return topAskCount; }
    public long[] getTopBidPrices() { return topBidPrices; }
    public long[] getTopBidQuantities() { return topBidQuantities; }
    public int[] getTopBidOrderCounts() { return topBidOrderCounts; }
    public long[] getTopAskPrices() { return topAskPrices; }
    public long[] getTopAskQuantities() { return topAskQuantities; }
    public int[] getTopAskOrderCounts() { return topAskOrderCounts; }
    public long getCollectedBidVersion() { return collectedBidVersion; }
    public long getCollectedAskVersion() { return collectedAskVersion; }
    public long getCollectedVersion() { return Math.max(collectedBidVersion, collectedAskVersion); }

    // ==================== Snapshot ====================

    public long[] getBidOrders() { return bidBook.getActiveOrders(); }
    public long[] getAskOrders() { return askBook.getActiveOrders(); }

    /**
     * Clear and restore from snapshot 4-tuples. Each order is re-validated against {@link PriceRules}
     * so a tightened rule set (or a foreign/corrupt price) is loudly dropped and counted — the same
     * "geometry mismatch is loud" guarantee the direct-index book gave via its storage geometry.
     */
    public int restoreFromSnapshot(long[] bidOrders, long[] askOrders) {
        bidBook.clear();
        askBook.clear();
        int rejected = 0;
        rejected += restoreSide(bidBook, bidOrders, "bid");
        rejected += restoreSide(askBook, askOrders, "ask");
        return rejected;
    }

    private int restoreSide(ArrayOrderBook book, long[] orders, String side) {
        int rejected = 0;
        for (int i = 0; i < orders.length; i += 4) {
            long price = orders[i + 2];
            int result = priceRules.validate(price);
            if (result == OrderRejectReason.NONE) {
                result = book.addOrder(orders[i], orders[i + 1], price, orders[i + 3]);
            }
            if (result != OrderRejectReason.NONE) {
                rejected++;
                System.err.println("ERROR: Snapshot restore dropped " + side + " order " + orders[i]
                    + " price=" + price + " reason=" + OrderRejectReason.describe(result));
            }
        }
        return rejected;
    }

    public void clear() {
        bidBook.clear();
        askBook.clear();
        matchCount = 0;
        lastPubBidVersion = -1;
        lastPubAskVersion = -1;
        publishTopOfBook();
    }

    // ==================== Book access (diagnostics / tests) ====================

    public ArrayOrderBook getAskBook() { return askBook; }
    public ArrayOrderBook getBidBook() { return bidBook; }
}
