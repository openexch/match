// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import org.agrona.collections.Long2LongHashMap;

/**
 * Array-backed, pooled, geometry-free order book for one side (bid or ask).
 *
 * <p>Replaces the preallocated {@link DirectIndexOrderBook}'s fixed price→index geometry
 * (and its 64-orders-per-level cap and memory-∝-price-range footprint) with:
 * <ul>
 *   <li><b>Price levels</b> = an AA balanced BST stored in parallel primitive arrays,
 *       node ids drawn from a free-list (algorithm ported from the object-based
 *       {@code AATree}). Keyed by the actual price; {@code ascending} only flips which
 *       end is "best" and which direction the taker sweep walks.</li>
 *   <li><b>Orders</b> = a single shared slot pool; each price level owns an intrusive
 *       <b>doubly-linked FIFO</b> through that pool, so per-level depth is bounded only by
 *       the pool (no 64-cap) and memory scales with resting orders, not price range.</li>
 * </ul>
 *
 * <p><b>Zero hot-path allocation, zero GC references</b> — all state is primitive arrays
 * (the order-id index map is Agrona's open-addressed {@link Long2LongHashMap}).
 *
 * <p><b>Why orders reference their level by price, not by node id:</b> AA deletion rebalances
 * by copying a successor/predecessor's level payload into the deleted node's slot (node
 * <i>identity</i> is not stable across deletes). Orders therefore key on the immutable
 * {@code price} (resolved to the current owning node via an O(log n) {@code findLevel}),
 * which keeps cancel/reduce correct regardless of how the tree reshuffles node slots.
 *
 * <p><b>Complexity:</b> best price and per-fill {@code reduceHead} are O(1)/O(log n) cheap;
 * add / cancel / nextLevel are O(log n) (~tree height, cache-friendly array reads). This is
 * the intended trade for unbounded, dynamic, memory-proportional levels.
 *
 * <p><b>Threading:</b> single-writer (cluster/matching thread). {@code version} is volatile
 * for cross-thread change detection; structural reads by other threads must go through the
 * matching engine's published snapshot, not this book directly.
 */
public final class ArrayOrderBook {

    /** Sentinel "null" node. Real nodes are 1..capacity; node 0 is never a real level. */
    private static final int NIL = 0;

    // Direction: true = ask (ascending; best = lowest price), false = bid (best = highest price).
    private final boolean ascending;
    private final int capacity;

    // ---- Order slot pool (shared across all levels). Slot ids: 0..capacity-1. ----
    private final long[] sOrderId;   // order id
    private final long[] sUserId;    // owning user
    private final long[] sQty;       // remaining quantity
    private final long[] sPrice;     // level price (so cancel/reduce can resolve the level by value)
    private final int[]  sNext;      // next slot in the level's FIFO (-1 = tail)
    private final int[]  sPrev;      // prev slot in the level's FIFO (-1 = head)
    private final int[]  sFree;      // free-slot stack
    private int sFreeTop;            // number of free slots (all free initially)

    // orderId -> slot, O(1) cancel/reduce lookup. missing = -1.
    private static final long NO_SLOT = -1L;
    private final Long2LongHashMap orderLocations;

    // ---- Price-level node pool (AA tree). Node ids: 1..capacity (0 = NIL). ----
    private final long[] nPrice;     // level price (BST key)
    private final long[] nTotalQty;  // sum of remaining qty at this level
    private final int[]  nLeft;      // left child node
    private final int[]  nRight;     // right child node
    private final int[]  nAA;        // AA level (NIL has level 0)
    private final int[]  nHead;      // first order slot at this level (-1 = none)
    private final int[]  nTail;      // last order slot at this level (-1 = none)
    private final int[]  nCount;     // live order count at this level
    private final int[]  nFree;      // free-node stack
    private int nFreeTop;            // number of free nodes

    private int root = NIL;
    private int activeLevels = 0;

    // Monotonic modification counter. Volatile: cross-thread readers use it for change
    // detection (a happens-before fence after each mutation). Not a correctness lock.
    private volatile long version = 0;

    // Reusable in-order traversal cursor for getActiveOrders (snapshot path; single-threaded).
    private long[] snapBuf;
    private int snapIdx;

    /**
     * @param ascending true for the ask side (best = lowest price), false for the bid side
     * @param capacity  maximum simultaneous resting orders (and, worst case, levels) for this side
     */
    public ArrayOrderBook(boolean ascending, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.ascending = ascending;
        this.capacity = capacity;

        this.sOrderId = new long[capacity];
        this.sUserId = new long[capacity];
        this.sQty = new long[capacity];
        this.sPrice = new long[capacity];
        this.sNext = new int[capacity];
        this.sPrev = new int[capacity];
        this.sFree = new int[capacity];

        // Node arrays sized capacity+1 so index 0 can be the NIL sentinel.
        this.nPrice = new long[capacity + 1];
        this.nTotalQty = new long[capacity + 1];
        this.nLeft = new int[capacity + 1];
        this.nRight = new int[capacity + 1];
        this.nAA = new int[capacity + 1];
        this.nHead = new int[capacity + 1];
        this.nTail = new int[capacity + 1];
        this.nCount = new int[capacity + 1];
        this.nFree = new int[capacity];

        this.orderLocations = new Long2LongHashMap(NO_SLOT);

        initPools();
        preTouch();
    }

    private void initPools() {
        // Order slots 0..capacity-1 all free.
        for (int i = 0; i < capacity; i++) {
            sFree[i] = i;
        }
        sFreeTop = capacity;

        // Nodes 1..capacity all free (0 reserved for NIL).
        for (int i = 0; i < capacity; i++) {
            nFree[i] = i + 1;
        }
        nFreeTop = capacity;

        nAA[NIL] = 0;
        nLeft[NIL] = NIL;
        nRight[NIL] = NIL;

        root = NIL;
        activeLevels = 0;
    }

    /** Fault arrays in once at startup (pairs with -XX:+AlwaysPreTouch) to avoid trading-time page faults. */
    private void preTouch() {
        for (int i = 0; i < capacity; i += 8) {
            sOrderId[i] = 0; sQty[i] = 0; sPrice[i] = 0; sNext[i] = 0; sPrev[i] = 0;
        }
        for (int i = 0; i <= capacity; i += 8) {
            nPrice[i] = 0; nTotalQty[i] = 0; nLeft[i] = 0; nRight[i] = 0; nHead[i] = 0;
        }
    }

    // ==================== Order admission / removal ====================

    /**
     * Add a resting order. Price validity (tick/range) is enforced upstream by the market
     * rules layer; this method only fails when the shared pool is exhausted.
     *
     * @return {@link OrderRejectReason#NONE} on success, {@link OrderRejectReason#BOOK_FULL} if full
     */
    public int addOrder(long orderId, long userId, long price, long quantity) {
        if (sFreeTop == 0) {
            return OrderRejectReason.BOOK_FULL;
        }

        int node = findLevel(price);
        if (node == NIL) {
            // New price level — need a free tree node too (guaranteed available: levels <= orders).
            if (nFreeTop == 0) {
                return OrderRejectReason.BOOK_FULL;
            }
            root = insertRec(root, price);
            node = touchedNode;
            activeLevels++;
        }

        // Allocate and populate the order slot.
        int slot = sFree[--sFreeTop];
        sOrderId[slot] = orderId;
        sUserId[slot] = userId;
        sQty[slot] = quantity;
        sPrice[slot] = price;
        sNext[slot] = -1;
        sPrev[slot] = -1;

        // Append to the level's FIFO tail.
        int tail = nTail[node];
        if (tail == -1) {
            nHead[node] = slot;
            nTail[node] = slot;
        } else {
            sPrev[slot] = tail;
            sNext[tail] = slot;
            nTail[node] = slot;
        }
        nCount[node]++;
        nTotalQty[node] += quantity;

        orderLocations.put(orderId, slot);
        version++;
        return OrderRejectReason.NONE;
    }

    /**
     * Cancel a resting order by id. O(log n) to resolve the level + O(1) unlink.
     *
     * @return true if the order was present and removed
     */
    public boolean cancelOrder(long orderId) {
        long slotL = orderLocations.get(orderId);
        if (slotL == NO_SLOT) return false;
        int slot = (int) slotL;
        if (sOrderId[slot] != orderId) return false; // stale / collision guard

        long price = sPrice[slot];
        int node = findLevel(price);
        if (node == NIL) return false; // inconsistent; nothing to do

        unlinkSlot(node, slot);
        nTotalQty[node] -= sQty[slot];
        nCount[node]--;
        orderLocations.remove(orderId);
        freeSlot(slot);

        if (nCount[node] == 0) {
            root = deleteRec(root, price);
            activeLevels--;
        }
        version++;
        return true;
    }

    /**
     * Reduce the head order of {@code node} by {@code matchQty} (matching path, O(1)).
     * When the head is fully filled it is unlinked and freed; when that empties the level the
     * level is removed from the tree.
     *
     * @return true if this reduction emptied (and deleted) the level — callers matching against
     *         this node MUST stop touching it and navigate onward by price
     */
    public boolean reduceHead(int node, long matchQty) {
        int slot = nHead[node];
        long newQty = sQty[slot] - matchQty;
        sQty[slot] = newQty;
        nTotalQty[node] -= matchQty;

        boolean emptied = false;
        if (newQty <= 0) {
            // Fully filled — unlink head and free it.
            long orderId = sOrderId[slot];
            int next = sNext[slot];
            nHead[node] = next;
            if (next == -1) {
                nTail[node] = -1;
            } else {
                sPrev[next] = -1;
            }
            orderLocations.remove(orderId);
            freeSlot(slot);
            nCount[node]--;

            if (nCount[node] == 0) {
                long price = nPrice[node];
                root = deleteRec(root, price);
                activeLevels--;
                emptied = true;
            }
        }
        version++;
        return emptied;
    }

    private void unlinkSlot(int node, int slot) {
        int prev = sPrev[slot];
        int next = sNext[slot];
        if (prev == -1) {
            nHead[node] = next;
        } else {
            sNext[prev] = next;
        }
        if (next == -1) {
            nTail[node] = prev;
        } else {
            sPrev[next] = prev;
        }
    }

    private void freeSlot(int slot) {
        sFree[sFreeTop++] = slot;
    }

    // ==================== Matching-sweep support ====================

    /** Best level node (lowest-price for ask, highest-price for bid), or {@link #NIL} if empty. */
    public int getBestLevel() {
        if (root == NIL) return NIL;
        return ascending ? minNodeIn(root) : maxNodeIn(root);
    }

    /**
     * Next level after {@code afterPrice} in sweep order: the smallest price &gt; afterPrice (ask)
     * or the largest price &lt; afterPrice (bid). Resolved by value, so it is robust even if the
     * level at {@code afterPrice} was just deleted. Returns {@link #NIL} when none remains.
     */
    public int nextLevel(long afterPrice) {
        return ascending ? successorNode(afterPrice) : predecessorNode(afterPrice);
    }

    public long getLevelPrice(int node) {
        return nPrice[node];
    }

    public int getOrderCount(int node) {
        return node == NIL ? 0 : nCount[node];
    }

    public long getTotalQuantity(int node) {
        return node == NIL ? 0 : nTotalQty[node];
    }

    public long headOrderId(int node) {
        int slot = nHead[node];
        return slot == -1 ? -1 : sOrderId[slot];
    }

    public long headOrderUserId(int node) {
        int slot = nHead[node];
        return slot == -1 ? 0 : sUserId[slot];
    }

    public long headOrderQuantity(int node) {
        int slot = nHead[node];
        return slot == -1 ? 0 : sQty[slot];
    }

    // ==================== Queries ====================

    /** Best price, or a side-appropriate sentinel when empty (matches DirectIndexOrderBook). */
    public long getBestPrice() {
        int best = getBestLevel();
        if (best == NIL) return ascending ? Long.MAX_VALUE : Long.MIN_VALUE;
        return nPrice[best];
    }

    public boolean isEmpty() {
        return root == NIL;
    }

    public boolean isAscending() {
        return ascending;
    }

    public int getActiveLevelCount() {
        return activeLevels;
    }

    /** Number of resting orders currently in the book. */
    public int getOrderCount() {
        return capacity - sFreeTop;
    }

    public int getCapacity() {
        return capacity;
    }

    /** Monotonic modification counter (volatile read establishes happens-before with last write). */
    public long getVersion() {
        return version;
    }

    // ==================== Snapshot support ====================

    /**
     * All resting orders as flat {@code [orderId, userId, price, qty]} 4-tuples, in deterministic
     * order: levels in ascending price (in-order tree walk), orders FIFO (head→tail) within a level.
     * Allocates; snapshot path only, never the hot path.
     */
    public long[] getActiveOrders() {
        int live = getOrderCount();
        if (live == 0) return new long[0];

        snapBuf = new long[live * 4];
        snapIdx = 0;
        inorderCollect(root);
        long[] out = snapBuf;
        snapBuf = null;
        // inorderCollect fills exactly `live` orders; out is already the right size.
        return out;
    }

    private void inorderCollect(int node) {
        if (node == NIL) return;
        inorderCollect(nLeft[node]);
        long price = nPrice[node];
        for (int slot = nHead[node]; slot != -1; slot = sNext[slot]) {
            snapBuf[snapIdx++] = sOrderId[slot];
            snapBuf[snapIdx++] = sUserId[slot];
            snapBuf[snapIdx++] = price;
            snapBuf[snapIdx++] = sQty[slot];
        }
        inorderCollect(nRight[node]);
    }

    /** Reset to empty (snapshot restore). */
    public void clear() {
        orderLocations.clear();
        initPools();
        version++;
    }

    // ==================== AA tree internals ====================

    // findLevel/insert share this to return the touched (found or created) node from a single pass.
    private int touchedNode = NIL;

    /** Find the node holding {@code price}, or {@link #NIL}. O(log n) by value. */
    private int findLevel(long price) {
        int t = root;
        while (t != NIL) {
            long k = nPrice[t];
            if (price < k) t = nLeft[t];
            else if (price > k) t = nRight[t];
            else return t;
        }
        return NIL;
    }

    /** Insert a new level for {@code price}, returning the new subtree root; sets {@link #touchedNode}. */
    private int insertRec(int t, long price) {
        if (t == NIL) {
            int n = allocNode(price);
            touchedNode = n;
            return n;
        }
        if (price < nPrice[t]) {
            nLeft[t] = insertRec(nLeft[t], price);
        } else if (price > nPrice[t]) {
            nRight[t] = insertRec(nRight[t], price);
        } else {
            touchedNode = t; // already present (should not happen: caller checked findLevel)
            return t;
        }
        t = skew(t);
        t = split(t);
        return t;
    }

    /** Delete the node holding {@code price}, returning the new subtree root. Standard AA delete. */
    private int deleteRec(int t, long price) {
        if (t == NIL) return NIL;

        if (price < nPrice[t]) {
            nLeft[t] = deleteRec(nLeft[t], price);
        } else if (price > nPrice[t]) {
            nRight[t] = deleteRec(nRight[t], price);
        } else {
            if (nLeft[t] == NIL && nRight[t] == NIL) {
                freeNode(t);
                return NIL;
            } else if (nLeft[t] == NIL) {
                int succ = minNodeIn(nRight[t]);
                copyLevel(t, succ);                       // t now represents succ's price level
                nRight[t] = deleteRec(nRight[t], nPrice[t]);
            } else {
                int pred = maxNodeIn(nLeft[t]);
                copyLevel(t, pred);
                nLeft[t] = deleteRec(nLeft[t], nPrice[t]);
            }
        }

        t = decreaseLevel(t);
        t = skew(t);
        nRight[t] = skew(nRight[t]);
        if (nRight[t] != NIL) {
            nRight[nRight[t]] = skew(nRight[nRight[t]]);
        }
        t = split(t);
        nRight[t] = split(nRight[t]);
        return t;
    }

    /** Move a level's payload (price + order list + aggregates) from src into dst's node slot. */
    private void copyLevel(int dst, int src) {
        nPrice[dst] = nPrice[src];
        nTotalQty[dst] = nTotalQty[src];
        nHead[dst] = nHead[src];
        nTail[dst] = nTail[src];
        nCount[dst] = nCount[src];
    }

    private int skew(int t) {
        if (t == NIL) return NIL;
        // left horizontal link (NIL has level 0, so NIL left can't match a real level)
        if (nAA[nLeft[t]] == nAA[t]) {
            int l = nLeft[t];
            nLeft[t] = nRight[l];
            nRight[l] = t;
            return l;
        }
        return t;
    }

    private int split(int t) {
        if (t == NIL) return NIL;
        int r = nRight[t];
        // two consecutive right horizontal links → rotate left and bump level
        if (nAA[nRight[r]] == nAA[t]) {
            nRight[t] = nLeft[r];
            nLeft[r] = t;
            nAA[r] = nAA[r] + 1;
            return r;
        }
        return t;
    }

    private int decreaseLevel(int t) {
        int shouldBe = Math.min(nAA[nLeft[t]], nAA[nRight[t]]) + 1;
        if (shouldBe < nAA[t]) {
            nAA[t] = shouldBe;
            if (shouldBe < nAA[nRight[t]]) {
                nAA[nRight[t]] = shouldBe;
            }
        }
        return t;
    }

    private int minNodeIn(int t) {
        while (nLeft[t] != NIL) t = nLeft[t];
        return t;
    }

    private int maxNodeIn(int t) {
        while (nRight[t] != NIL) t = nRight[t];
        return t;
    }

    /** Smallest key strictly greater than {@code price}, or {@link #NIL}. */
    private int successorNode(long price) {
        int t = root;
        int cand = NIL;
        while (t != NIL) {
            if (nPrice[t] > price) {
                cand = t;
                t = nLeft[t];
            } else {
                t = nRight[t];
            }
        }
        return cand;
    }

    /** Largest key strictly less than {@code price}, or {@link #NIL}. */
    private int predecessorNode(long price) {
        int t = root;
        int cand = NIL;
        while (t != NIL) {
            if (nPrice[t] < price) {
                cand = t;
                t = nRight[t];
            } else {
                t = nLeft[t];
            }
        }
        return cand;
    }

    private int allocNode(long price) {
        int n = nFree[--nFreeTop];
        nPrice[n] = price;
        nTotalQty[n] = 0;
        nLeft[n] = NIL;
        nRight[n] = NIL;
        nAA[n] = 1;
        nHead[n] = -1;
        nTail[n] = -1;
        nCount[n] = 0;
        return n;
    }

    private void freeNode(int n) {
        nCount[n] = 0; // keep stale reads (e.g. a just-broken match loop) safe
        nHead[n] = -1;
        nTail[n] = -1;
        nFree[nFreeTop++] = n;
    }
}
