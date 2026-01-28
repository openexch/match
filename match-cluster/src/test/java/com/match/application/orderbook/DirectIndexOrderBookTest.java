package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for DirectIndexOrderBook — validates bounds safety (C2 fix).
 */
public class DirectIndexOrderBookTest {

    private DirectIndexOrderBook askBook;
    private DirectIndexOrderBook bidBook;

    // Price range: $10 to $1000, tick size $0.01
    private static final long BASE_PRICE = FixedPoint.fromDouble(10.0);
    private static final long MAX_PRICE = FixedPoint.fromDouble(1000.0);
    private static final long TICK_SIZE = FixedPoint.fromDouble(0.01);

    @Before
    public void setUp() {
        askBook = new DirectIndexOrderBook(BASE_PRICE, MAX_PRICE, TICK_SIZE, true);
        bidBook = new DirectIndexOrderBook(BASE_PRICE, MAX_PRICE, TICK_SIZE, false);
    }

    // ==================== Basic Operations ====================

    @Test
    public void testAddAndRetrieveOrder() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        bidBook.addOrder(1L, 100L, price, qty);

        assertFalse(bidBook.isEmpty());
        assertEquals(1, bidBook.getActiveLevelCount());
        assertEquals(price, bidBook.getBestPrice());
    }

    @Test
    public void testCancelOrder() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        bidBook.addOrder(1L, 100L, price, qty);
        assertTrue(bidBook.cancelOrder(1L));
        assertTrue(bidBook.isEmpty());
    }

    @Test
    public void testCancelNonExistentOrder() {
        assertFalse(bidBook.cancelOrder(999L));
    }

    @Test
    public void testReduceQuantity() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.reduceOrderQuantity(1L, FixedPoint.fromDouble(3.0));

        long headQty = bidBook.getHeadOrderQuantity(bidBook.getBestPriceIndex());
        assertEquals(7.0, FixedPoint.toDouble(headQty), 0.01);
    }

    @Test
    public void testReduceQuantityToZero_AutoCancels() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);

        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.reduceOrderQuantity(1L, qty);

        assertTrue(bidBook.isEmpty());
    }

    @Test
    public void testReduceQuantityBeyondAvailable_NoPhantomQuantity() {
        // Regression test: reducing by MORE than available should not create phantom quantity
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(10.0);
        long overReduce = FixedPoint.fromDouble(15.0); // 50% more than available

        bidBook.addOrder(1L, 100L, price, qty);
        int priceIdx = (int)((price - bidBook.getBasePrice()) / bidBook.getTickSize());

        // Reduce by more than available
        bidBook.reduceOrderQuantity(1L, overReduce);

        // Book should be empty — no phantom quantity
        assertTrue(bidBook.isEmpty());
        assertEquals(0, bidBook.getOrderCount(priceIdx));
        assertEquals(0, bidBook.getTotalQuantity(priceIdx));
    }

    @Test
    public void testReduceQuantityMultipleOrders_LevelTotalConsistent() {
        // Test that level total stays consistent after multiple partial fills + full fill
        long price = FixedPoint.fromDouble(100.0);
        long qty1 = FixedPoint.fromDouble(10.0);
        long qty2 = FixedPoint.fromDouble(20.0);

        bidBook.addOrder(1L, 100L, price, qty1);
        bidBook.addOrder(2L, 200L, price, qty2);

        int priceIdx = (int)((price - bidBook.getBasePrice()) / bidBook.getTickSize());

        // Verify initial total
        assertEquals(FixedPoint.fromDouble(30.0), bidBook.getTotalQuantity(priceIdx));
        assertEquals(2, bidBook.getOrderCount(priceIdx));

        // Partial fill order 1
        bidBook.reduceOrderQuantity(1L, FixedPoint.fromDouble(5.0));
        assertEquals(FixedPoint.fromDouble(25.0), bidBook.getTotalQuantity(priceIdx));

        // Full fill order 1
        bidBook.reduceOrderQuantity(1L, FixedPoint.fromDouble(5.0));
        assertEquals(FixedPoint.fromDouble(20.0), bidBook.getTotalQuantity(priceIdx));
        assertEquals(1, bidBook.getOrderCount(priceIdx));

        // Full fill order 2
        bidBook.reduceOrderQuantity(2L, FixedPoint.fromDouble(20.0));
        assertEquals(0, bidBook.getTotalQuantity(priceIdx));
        assertEquals(0, bidBook.getOrderCount(priceIdx));
        assertTrue(bidBook.isEmpty());
    }

    // ==================== C2: Bounds Safety ====================

    @Test
    public void testOutOfRangePrice_Ignored() {
        // Price below base — should be silently ignored (not crash)
        long tooLow = FixedPoint.fromDouble(1.0);
        bidBook.addOrder(1L, 100L, tooLow, FixedPoint.fromDouble(10.0));
        assertTrue("Out-of-range price should be ignored", bidBook.isEmpty());
    }

    @Test
    public void testOutOfRangePrice_TooHigh() {
        // Price above max — should be silently ignored
        long tooHigh = FixedPoint.fromDouble(2000.0);
        bidBook.addOrder(1L, 100L, tooHigh, FixedPoint.fromDouble(10.0));
        assertTrue("Out-of-range price should be ignored", bidBook.isEmpty());
    }

    @Test
    public void testGetOrderCountOutOfBounds() {
        assertEquals(0, bidBook.getOrderCount(-1));
        assertEquals(0, bidBook.getOrderCount(Integer.MAX_VALUE));
    }

    @Test
    public void testGetTotalQuantityOutOfBounds() {
        assertEquals(0, bidBook.getTotalQuantity(-1));
        assertEquals(0, bidBook.getTotalQuantity(Integer.MAX_VALUE));
    }

    @Test
    public void testGetHeadOrderIdOutOfBounds() {
        assertEquals(-1, bidBook.getHeadOrderId(-1));
        assertEquals(-1, bidBook.getHeadOrderId(Integer.MAX_VALUE));
    }

    @Test
    public void testGetHeadOrderIdEmptyLevel() {
        assertEquals(-1, bidBook.getHeadOrderId(0));
    }

    @Test
    public void testGetHeadOrderQuantityOutOfBounds() {
        assertEquals(0, bidBook.getHeadOrderQuantity(-1));
    }

    @Test
    public void testGetHeadOrderUserIdOutOfBounds() {
        assertEquals(0, bidBook.getHeadOrderUserId(-1));
    }

    // ==================== Linked List Traversal Safety ====================

    @Test
    public void testSkipDeletedOrders() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        // Add 3 orders at same price
        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(2L, 101L, price, qty);
        bidBook.addOrder(3L, 102L, price, qty);

        // Cancel the head order
        bidBook.cancelOrder(1L);

        // Head should now be order 2 or 3 (skipping deleted)
        int priceIdx = bidBook.getBestPriceIndex();
        long headId = bidBook.getHeadOrderId(priceIdx);
        assertTrue("Head should be order 2 or 3", headId == 2L || headId == 3L);
    }

    // ==================== Multiple Price Levels ====================

    @Test
    public void testBidBookBestPrice() {
        bidBook.addOrder(1L, 100L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        bidBook.addOrder(2L, 101L, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(10.0));
        bidBook.addOrder(3L, 102L, FixedPoint.fromDouble(150.0), FixedPoint.fromDouble(10.0));

        // Best bid = highest price
        assertEquals(200.0, FixedPoint.toDouble(bidBook.getBestPrice()), 0.01);
    }

    @Test
    public void testAskBookBestPrice() {
        askBook.addOrder(1L, 100L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        askBook.addOrder(2L, 101L, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(10.0));
        askBook.addOrder(3L, 102L, FixedPoint.fromDouble(150.0), FixedPoint.fromDouble(10.0));

        // Best ask = lowest price
        assertEquals(100.0, FixedPoint.toDouble(askBook.getBestPrice()), 0.01);
    }

    @Test
    public void testNextPriceIndex() {
        bidBook.addOrder(1L, 100L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        bidBook.addOrder(2L, 101L, FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(10.0));

        int bestIdx = bidBook.getBestPriceIndex();
        int nextIdx = bidBook.nextPriceIndex(bestIdx);
        assertTrue("Should find a second price level", nextIdx >= 0);
    }

    // ==================== Snapshot ====================

    @Test
    public void testSnapshotAndRestore() {
        long price1 = FixedPoint.fromDouble(100.0);
        long price2 = FixedPoint.fromDouble(200.0);
        long qty = FixedPoint.fromDouble(10.0);

        bidBook.addOrder(1L, 100L, price1, qty);
        bidBook.addOrder(2L, 101L, price2, qty);

        long[] snapshot = bidBook.getActiveOrders();
        assertEquals("Should have 2 orders x 4 fields", 8, snapshot.length);

        // Clear and restore
        bidBook.clear();
        assertTrue(bidBook.isEmpty());

        for (int i = 0; i < snapshot.length; i += 4) {
            bidBook.addOrder(snapshot[i], snapshot[i + 1], snapshot[i + 2], snapshot[i + 3]);
        }

        assertFalse(bidBook.isEmpty());
        assertEquals(2, bidBook.getActiveLevelCount());
    }

    // ==================== Version Tracking ====================

    @Test
    public void testVersionIncrementsOnModification() {
        long v0 = bidBook.getVersion();
        bidBook.addOrder(1L, 100L, FixedPoint.fromDouble(100.0), FixedPoint.fromDouble(10.0));
        long v1 = bidBook.getVersion();
        assertTrue("Version should increment on add", v1 > v0);

        bidBook.cancelOrder(1L);
        long v2 = bidBook.getVersion();
        assertTrue("Version should increment on cancel", v2 > v1);
    }

    // ==================== Hash Collision Tests (Long2LongHashMap) ====================

    @Test
    public void testOrderIdsAboveOneMillionNoCancellationLoss() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(1.0);

        // Add order 1 and order 1_000_001 — would collide under old modulo hashing
        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(1_000_001L, 101L, price, qty);

        // Both should be independently cancellable
        assertTrue("Order 1 should be cancellable", bidBook.cancelOrder(1L));
        assertTrue("Order 1000001 should be cancellable", bidBook.cancelOrder(1_000_001L));
    }

    @Test
    public void testLargeOrderIds() {
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(1.0);

        // Test with very large order IDs
        long largeId1 = 5_000_000L;
        long largeId2 = 10_000_000L;
        bidBook.addOrder(largeId1, 100L, price, qty);
        bidBook.addOrder(largeId2, 101L, price, qty);

        assertTrue("Large order ID should be cancellable", bidBook.cancelOrder(largeId1));
        assertTrue("Large order ID should be cancellable", bidBook.cancelOrder(largeId2));
    }

    // ==================== Linked List Correctness (Fix 2A) ====================

    @Test
    public void testCancelMiddleAddNew_FIFO() {
        // Reproduces the tail-linking bug: cancel middle order, add new,
        // verify all live orders are reachable in FIFO order.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        // Add A, B, C at same price
        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(2L, 101L, price, qty);
        bidBook.addOrder(3L, 102L, price, qty);

        // Cancel B (middle order)
        bidBook.cancelOrder(2L);

        // Add D
        bidBook.addOrder(4L, 103L, price, qty);

        int priceIdx = bidBook.getBestPriceIndex();

        // Verify FIFO: A, then C, then D (B was cancelled)
        assertEquals("Head should be A", 1L, bidBook.getHeadOrderId(priceIdx));
        bidBook.reduceOrderQuantity(1L, qty); // fill A

        assertEquals("After A filled, head should be C", 3L, bidBook.getHeadOrderId(priceIdx));
        bidBook.reduceOrderQuantity(3L, qty); // fill C

        assertEquals("After C filled, head should be D", 4L, bidBook.getHeadOrderId(priceIdx));
        bidBook.reduceOrderQuantity(4L, qty); // fill D

        assertTrue("Book should be empty after all filled", bidBook.isEmpty());
    }

    @Test
    public void testCancelHead_VerifyAdvance() {
        // Cancel head order, verify head advances to next live order.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(2L, 101L, price, qty);
        bidBook.addOrder(3L, 102L, price, qty);

        // Cancel head (A)
        bidBook.cancelOrder(1L);

        int priceIdx = bidBook.getBestPriceIndex();
        assertEquals("Head should advance to B after A cancelled", 2L, bidBook.getHeadOrderId(priceIdx));

        // Verify C is also reachable
        bidBook.reduceOrderQuantity(2L, qty); // fill B
        assertEquals("After B filled, head should be C", 3L, bidBook.getHeadOrderId(priceIdx));
    }

    @Test
    public void testCancelAllReAdd() {
        // Cancel all orders at a level, then re-add. Verify fresh start.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(2L, 101L, price, qty);
        bidBook.addOrder(3L, 102L, price, qty);

        // Cancel all
        bidBook.cancelOrder(1L);
        bidBook.cancelOrder(2L);
        bidBook.cancelOrder(3L);

        assertTrue("Book should be empty after cancelling all", bidBook.isEmpty());

        // Re-add
        bidBook.addOrder(4L, 103L, price, qty);
        bidBook.addOrder(5L, 104L, price, qty);

        int priceIdx = bidBook.getBestPriceIndex();
        assertEquals("Head should be D", 4L, bidBook.getHeadOrderId(priceIdx));

        // Verify E is reachable via getActiveOrders
        long[] active = bidBook.getActiveOrders();
        assertEquals("Should have 2 orders x 4 fields", 8, active.length);

        // Verify FIFO: D then E
        bidBook.reduceOrderQuantity(4L, qty);
        assertEquals("After D filled, head should be E", 5L, bidBook.getHeadOrderId(priceIdx));
    }

    @Test
    public void testStressCancelReuseAllReachable() {
        // Stress test: add many orders, cancel some, add more, verify all reachable.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(1.0);

        // Add 30 orders
        for (long i = 1; i <= 30; i++) {
            bidBook.addOrder(i, 100L + i, price, qty);
        }

        // Cancel odd-numbered (1, 3, 5, ..., 29) — 15 cancellations
        for (long i = 1; i <= 30; i += 2) {
            assertTrue("Should cancel order " + i, bidBook.cancelOrder(i));
        }

        // Add 30 new orders
        for (long i = 31; i <= 60; i++) {
            bidBook.addOrder(i, 100L + i, price, qty);
        }

        // All live orders should be reachable via getActiveOrders
        long[] active = bidBook.getActiveOrders();
        // 15 even-numbered from original + 30 new = 45 orders, 4 fields each
        assertEquals("Should have 45 active orders", 45 * 4, active.length);

        // Verify the order IDs in the active list
        java.util.Set<Long> activeIds = new java.util.HashSet<>();
        for (int i = 0; i < active.length; i += 4) {
            activeIds.add(active[i]);
        }

        // Even-numbered originals should be present
        for (long i = 2; i <= 30; i += 2) {
            assertTrue("Original order " + i + " should be active", activeIds.contains(i));
        }
        // Odd-numbered originals should NOT be present
        for (long i = 1; i <= 30; i += 2) {
            assertFalse("Cancelled order " + i + " should not be active", activeIds.contains(i));
        }
        // New orders should all be present
        for (long i = 31; i <= 60; i++) {
            assertTrue("New order " + i + " should be active", activeIds.contains(i));
        }
    }

    @Test
    public void testCancelTailAddNew_TailLinkingBug() {
        // Specific regression for the tail-linking bug: cancel tail, add new.
        // Old code would fail to link the new order because tail.qty == 0.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(5.0);

        bidBook.addOrder(1L, 100L, price, qty);
        bidBook.addOrder(2L, 101L, price, qty);

        // Cancel tail (B)
        bidBook.cancelOrder(2L);

        // Add C — should be linked properly
        bidBook.addOrder(3L, 102L, price, qty);

        int priceIdx = bidBook.getBestPriceIndex();

        // Verify FIFO: A then C
        assertEquals("Head should be A", 1L, bidBook.getHeadOrderId(priceIdx));
        bidBook.reduceOrderQuantity(1L, qty);
        assertEquals("After A filled, head should be C", 3L, bidBook.getHeadOrderId(priceIdx));

        // Verify C is in active orders
        long[] active = bidBook.getActiveOrders();
        assertEquals("Should have 1 active order x 4 fields", 4, active.length);
        assertEquals("Active order should be C", 3L, active[0]);
    }

    @Test
    public void testRepeatedCancelAndAdd_SlotRecycling() {
        // Verify slots are properly recycled when the level empties and refills.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(1.0);

        for (int round = 0; round < 5; round++) {
            long base = round * 10L;
            // Add orders
            bidBook.addOrder(base + 1, 100L, price, qty);
            bidBook.addOrder(base + 2, 101L, price, qty);
            bidBook.addOrder(base + 3, 102L, price, qty);

            // Cancel all — triggers freeAllSlotsAtLevel
            bidBook.cancelOrder(base + 1);
            bidBook.cancelOrder(base + 2);
            bidBook.cancelOrder(base + 3);
            assertTrue("Book should be empty after round " + round, bidBook.isEmpty());
        }

        // Final add should still work (slots recycled)
        bidBook.addOrder(999L, 100L, price, qty);
        assertFalse("Book should not be empty", bidBook.isEmpty());
        assertEquals(999L, bidBook.getHeadOrderId(bidBook.getBestPriceIndex()));
    }

    @Test
    public void testHeadAdvanceFreesTombstones() {
        // Verify that head-advance during getHeadOrderId frees tombstone slots,
        // making them available for reuse.
        long price = FixedPoint.fromDouble(100.0);
        long qty = FixedPoint.fromDouble(1.0);

        // Fill up many slots
        for (long i = 1; i <= 60; i++) {
            bidBook.addOrder(i, 100L, price, qty);
        }
        // 60 slots used, 4 free

        // Cancel first 50 (head side) — these become tombstones
        for (long i = 1; i <= 50; i++) {
            bidBook.cancelOrder(i);
        }
        // 60 slots still occupied (50 tombstones + 10 live), 4 free

        // Access head — should advance past tombstones and free them
        int priceIdx = bidBook.getBestPriceIndex();
        long headId = bidBook.getHeadOrderId(priceIdx);
        assertEquals("Head should be order 51", 51L, headId);

        // Now tombstone slots should be freed. Adding more orders should work.
        for (long i = 101; i <= 150; i++) {
            bidBook.addOrder(i, 100L, price, qty);
        }
        // We should now have 10 original live + 50 new = 60 live orders
        long[] active = bidBook.getActiveOrders();
        assertEquals("Should have 60 active orders", 60 * 4, active.length);
    }
}
