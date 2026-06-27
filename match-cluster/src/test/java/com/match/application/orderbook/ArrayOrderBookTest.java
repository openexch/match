package com.match.application.orderbook;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral + model-based tests for {@link ArrayOrderBook}. The AA tree's delete-by-copy
 * rebalancing is the riskiest logic, so the core of this suite mirrors a sequence of random
 * (deterministically seeded) operations against a {@link TreeMap} reference model and asserts
 * the book's sweep order, snapshot, and counts match it exactly.
 */
public class ArrayOrderBookTest {

    private static final int CAP = 4096;

    private ArrayOrderBook ask() { return new ArrayOrderBook(true, CAP); }
    private ArrayOrderBook bid() { return new ArrayOrderBook(false, CAP); }

    // ---- basics ----

    @Test
    public void addThenReadBestAndHead() {
        ArrayOrderBook b = ask();
        assertTrue(b.isEmpty());
        assertEquals(OrderRejectReason.NONE, b.addOrder(1, 100, 50_000, 7));

        assertFalse(b.isEmpty());
        assertEquals(1, b.getOrderCount());
        assertEquals(50_000, b.getBestPrice());
        int best = b.getBestLevel();
        assertEquals(50_000, b.getLevelPrice(best));
        assertEquals(1, b.getOrderCount(best));
        assertEquals(1, b.headOrderId(best));
        assertEquals(100, b.headOrderUserId(best));
        assertEquals(7, b.headOrderQuantity(best));
        assertEquals(7, b.getTotalQuantity(best));
    }

    @Test
    public void emptyBestPriceSentinels() {
        assertEquals(Long.MAX_VALUE, ask().getBestPrice()); // ask: lowest is best
        assertEquals(Long.MIN_VALUE, bid().getBestPrice()); // bid: highest is best
    }

    @Test
    public void fifoWithinLevel() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 10, 50_000, 1);
        b.addOrder(2, 11, 50_000, 2);
        b.addOrder(3, 12, 50_000, 3);
        int lvl = b.getBestLevel();
        assertEquals(3, b.getOrderCount(lvl));
        assertEquals(6, b.getTotalQuantity(lvl));
        // FIFO head is the first inserted
        assertEquals(1, b.headOrderId(lvl));
        assertArrayEquals(new long[]{
                1, 10, 50_000, 1,
                2, 11, 50_000, 2,
                3, 12, 50_000, 3
        }, b.getActiveOrders());
    }

    @Test
    public void cancelHeadMiddleTail() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 10, 50_000, 1);
        b.addOrder(2, 11, 50_000, 2);
        b.addOrder(3, 12, 50_000, 3);

        assertTrue(b.cancelOrder(2)); // middle
        assertArrayEquals(new long[]{1, 10, 50_000, 1, 3, 12, 50_000, 3}, b.getActiveOrders());

        assertTrue(b.cancelOrder(1)); // head
        assertArrayEquals(new long[]{3, 12, 50_000, 3}, b.getActiveOrders());
        assertEquals(3, b.headOrderId(b.getBestLevel()));

        assertTrue(b.cancelOrder(3)); // tail (last) -> level removed
        assertTrue(b.isEmpty());
        assertEquals(NIL(), b.getBestLevel());
        assertEquals(0, b.getActiveLevelCount());
    }

    @Test
    public void cancelUnknownIsFalse() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 10, 50_000, 1);
        assertFalse(b.cancelOrder(999));
    }

    @Test
    public void reduceHeadPartialThenFullDeletesLevel() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 10, 50_000, 10);
        int lvl = b.getBestLevel();

        assertFalse(b.reduceHead(lvl, 4)); // partial
        assertEquals(6, b.headOrderQuantity(lvl));
        assertEquals(6, b.getTotalQuantity(lvl));

        assertTrue(b.reduceHead(lvl, 6)); // fully fills the only order -> level emptied
        assertTrue(b.isEmpty());
    }

    @Test
    public void reduceHeadAdvancesToNextOrder() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 10, 50_000, 5);
        b.addOrder(2, 11, 50_000, 5);
        int lvl = b.getBestLevel();
        assertFalse(b.reduceHead(lvl, 5)); // fills order 1, order 2 becomes head, level NOT empty
        assertEquals(1, b.getOrderCount(lvl));
        assertEquals(2, b.headOrderId(lvl));
    }

    // ---- multi-level sweep ----

    @Test
    public void askSweepIsAscending() {
        ArrayOrderBook b = ask();
        long[] prices = {50_005, 50_001, 50_003, 50_000, 50_004, 50_002};
        for (int i = 0; i < prices.length; i++) b.addOrder(i + 1, 1, prices[i], 1);
        assertArrayEquals(new long[]{50_000, 50_001, 50_002, 50_003, 50_004, 50_005}, sweep(b));
    }

    @Test
    public void bidSweepIsDescending() {
        ArrayOrderBook b = bid();
        long[] prices = {50_005, 50_001, 50_003, 50_000, 50_004, 50_002};
        for (int i = 0; i < prices.length; i++) b.addOrder(i + 1, 1, prices[i], 1);
        assertArrayEquals(new long[]{50_005, 50_004, 50_003, 50_002, 50_001, 50_000}, sweep(b));
    }

    @Test
    public void nextLevelByValueRobustAfterDeletion() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 1, 100, 1);
        b.addOrder(2, 1, 200, 1);
        b.addOrder(3, 1, 300, 1);
        // consume the whole best level (100) via reduceHead -> it is deleted
        int best = b.getBestLevel();
        assertTrue(b.reduceHead(best, 1));
        // navigation by the just-removed price must still find the successor
        int next = b.nextLevel(100);
        assertEquals(200, b.getLevelPrice(next));
        assertEquals(200, b.getBestPrice());
    }

    // ---- geometry-free: huge / sparse prices ----

    @Test
    public void sparseAndLargePrices() {
        ArrayOrderBook b = ask();
        long[] prices = {1, 1_000_000_000_000L, 5, Long.MAX_VALUE / 2, 999};
        for (int i = 0; i < prices.length; i++) b.addOrder(i + 1, 1, prices[i], 1);
        assertArrayEquals(new long[]{1, 5, 999, 1_000_000_000_000L, Long.MAX_VALUE / 2}, sweep(b));
    }

    // ---- capacity bound ----

    @Test
    public void bookFullIsLoud() {
        ArrayOrderBook b = new ArrayOrderBook(true, 3);
        assertEquals(OrderRejectReason.NONE, b.addOrder(1, 1, 10, 1));
        assertEquals(OrderRejectReason.NONE, b.addOrder(2, 1, 20, 1));
        assertEquals(OrderRejectReason.NONE, b.addOrder(3, 1, 30, 1));
        assertEquals(OrderRejectReason.BOOK_FULL, b.addOrder(4, 1, 40, 1));
        // cancel frees a slot; add succeeds again
        assertTrue(b.cancelOrder(2));
        assertEquals(OrderRejectReason.NONE, b.addOrder(4, 1, 40, 1));
    }

    @Test
    public void manyOrdersSameLevelNoCap() {
        // The old book capped at 64 per level; the array book is bounded only by the pool.
        ArrayOrderBook b = ask();
        for (int i = 1; i <= 1000; i++) b.addOrder(i, 1, 50_000, 1);
        assertEquals(1000, b.getOrderCount(b.getBestLevel()));
        assertEquals(1000, b.getTotalQuantity(b.getBestLevel()));
        assertEquals(1, b.getActiveLevelCount());
    }

    // ---- snapshot round-trip ----

    @Test
    public void snapshotRoundTripPreservesSortedFifo() {
        ArrayOrderBook b = ask();
        b.addOrder(10, 1, 300, 3);
        b.addOrder(11, 1, 100, 1);
        b.addOrder(12, 1, 300, 4); // same level as 10, later -> after it
        b.addOrder(13, 1, 200, 2);
        long[] snap = b.getActiveOrders();

        ArrayOrderBook restored = ask();
        for (int i = 0; i < snap.length; i += 4) {
            restored.addOrder(snap[i], snap[i + 1], snap[i + 2], snap[i + 3]);
        }
        assertArrayEquals(snap, restored.getActiveOrders());
        assertArrayEquals(new long[]{
                11, 1, 100, 1,
                13, 1, 200, 2,
                10, 1, 300, 3,
                12, 1, 300, 4
        }, snap);
    }

    @Test
    public void clearResetsToEmpty() {
        ArrayOrderBook b = ask();
        b.addOrder(1, 1, 50_000, 1);
        b.addOrder(2, 1, 50_001, 1);
        long v = b.getVersion();
        b.clear();
        assertTrue(b.isEmpty());
        assertEquals(0, b.getOrderCount());
        assertEquals(0, b.getActiveLevelCount());
        assertTrue(b.getVersion() > v);
        // usable again
        assertEquals(OrderRejectReason.NONE, b.addOrder(5, 1, 123, 1));
        assertEquals(123, b.getBestPrice());
    }

    @Test
    public void versionIncrementsOnMutation() {
        ArrayOrderBook b = ask();
        long v0 = b.getVersion();
        b.addOrder(1, 1, 50_000, 5);
        long v1 = b.getVersion();
        assertTrue(v1 > v0);
        b.reduceHead(b.getBestLevel(), 2);
        assertTrue(b.getVersion() > v1);
    }

    // ---- model-based stress: the real AA-tree correctness net ----

    @Test
    public void modelMatchesUnderRandomizedOps() {
        for (long seed : new long[]{1L, 7L, 42L, 12345L, 999983L}) {
            runModel(true, seed);   // ask
            runModel(false, seed);  // bid
        }
    }

    /**
     * Drive the book and a TreeMap reference model through the same deterministic op stream
     * (add / cancel / reduce-head), asserting snapshot, sweep order, best price and counts agree
     * at every step. Deterministic PRNG (LCG) — no Math.random (would break determinism goldens).
     */
    private void runModel(boolean ascending, long seed) {
        ArrayOrderBook b = new ArrayOrderBook(ascending, CAP);
        // model: price -> FIFO queue of [orderId, userId, qty]
        TreeMap<Long, Deque<long[]>> model = new TreeMap<>();
        Map<Long, Long> orderPrice = new TreeMap<>();      // orderId -> price (for cancel)
        long rng = seed;
        long nextId = 1;
        int priceSpread = 50; // create collisions (multiple orders per level) and deletions

        for (int step = 0; step < 4000; step++) {
            rng = lcg(rng);
            int op = (int) ((rng >>> 17) % 3);

            if (op == 0 || orderPrice.isEmpty()) {
                // add
                rng = lcg(rng);
                long price = 10_000 + (rng >>> 13) % priceSpread;
                long id = nextId++;
                long user = 100 + (id % 7);
                long qty = 1 + (rng >>> 7) % 9;
                int r = b.addOrder(id, user, price, qty);
                assertEquals(OrderRejectReason.NONE, r);
                model.computeIfAbsent(price, k -> new ArrayDeque<>()).addLast(new long[]{id, user, qty});
                orderPrice.put(id, price);
            } else if (op == 1) {
                // cancel a pseudo-random existing order
                long id = pickExisting(orderPrice, rng);
                long price = orderPrice.remove(id);
                assertTrue(b.cancelOrder(id));
                Deque<long[]> q = model.get(price);
                q.removeIf(e -> e[0] == id);
                if (q.isEmpty()) model.remove(price);
            } else {
                // reduce-head of the best level (mimics a match)
                if (b.isEmpty()) continue;
                long bestPrice = b.getBestPrice();
                int lvl = b.getBestLevel();
                Deque<long[]> q = model.get(bestPrice);
                long[] head = q.peekFirst();
                assertEquals("head id mismatch", head[0], b.headOrderId(lvl));
                assertEquals("head qty mismatch", head[2], b.headOrderQuantity(lvl));
                rng = lcg(rng);
                long red = 1 + (rng >>> 5) % (head[2] + 2); // sometimes fully fills
                boolean fully = red >= head[2];
                boolean emptied = b.reduceHead(lvl, red);
                if (fully) {
                    q.removeFirst();
                    orderPrice.remove(head[0]);
                    if (q.isEmpty()) {
                        model.remove(bestPrice);
                        assertTrue("level should be emptied", emptied);
                    } else {
                        assertFalse(emptied);
                    }
                } else {
                    head[2] -= red;
                    assertFalse(emptied);
                }
            }

            // ---- invariants vs model, every step ----
            assertEquals("order count", orderPrice.size(), b.getOrderCount());
            assertEquals("level count", model.size(), b.getActiveLevelCount());
            if (model.isEmpty()) {
                assertTrue(b.isEmpty());
            } else {
                long expectedBest = ascending ? model.firstKey() : model.lastKey();
                assertEquals("best price", expectedBest, b.getBestPrice());
            }
            assertArrayEquals("snapshot", flatten(model), b.getActiveOrders());
            assertArrayEquals("sweep order", sweepPrices(model, ascending), sweep(b));
        }
    }

    // ---- helpers ----

    private static long lcg(long x) {
        return x * 6364136223846793005L + 1442695040888963407L;
    }

    private static long pickExisting(Map<Long, Long> orderPrice, long rng) {
        int n = orderPrice.size();
        int target = (int) ((rng >>> 11) % n);
        int i = 0;
        for (Long id : orderPrice.keySet()) {
            if (i++ == target) return id;
        }
        throw new IllegalStateException();
    }

    /** Flatten the model in ascending-price, FIFO order to match getActiveOrders. */
    private static long[] flatten(TreeMap<Long, Deque<long[]>> model) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, Deque<long[]>> e : model.entrySet()) {
            for (long[] o : e.getValue()) {
                out.add(o[0]); out.add(o[1]); out.add(e.getKey()); out.add(o[2]);
            }
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    private static long[] sweepPrices(TreeMap<Long, Deque<long[]>> model, boolean ascending) {
        long[] arr = new long[model.size()];
        int i = 0;
        if (ascending) {
            for (Long p : model.keySet()) arr[i++] = p;
        } else {
            for (Long p : model.descendingKeySet()) arr[i++] = p;
        }
        return arr;
    }

    /** Walk the book best→worst via getBestLevel/nextLevel, returning the level prices in order. */
    private static long[] sweep(ArrayOrderBook b) {
        List<Long> prices = new ArrayList<>();
        int node = b.getBestLevel();
        while (node != NIL()) {
            long p = b.getLevelPrice(node);
            prices.add(p);
            node = b.nextLevel(p);
        }
        long[] arr = new long[prices.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = prices.get(i);
        return arr;
    }

    private static int NIL() { return 0; }
}
