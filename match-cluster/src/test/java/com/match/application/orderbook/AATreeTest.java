package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import com.match.domain.Level;
import com.match.domain.Order;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for AATree — AA Tree implementation for order book price levels.
 */
public class AATreeTest {

    private AATree ascTree;   // ascending (ask side)
    private AATree descTree;  // descending (bid side)

    @Before
    public void setUp() {
        ascTree = new AATree(true);
        descTree = new AATree(false);
    }

    // ==================== Helper ====================

    private Level createLevel(double price) {
        Order order = new Order();
        order.setId(1L);
        order.setPrice(FixedPoint.fromDouble(price));
        order.setRemainingQuantity(FixedPoint.fromDouble(1.0));
        order.setSide(OrderSide.ASK);
        order.setType(OrderType.LIMIT);
        return new Level(order);
    }

    private Level createLevelAtFixedPrice(long price) {
        Order order = new Order();
        order.setId(1L);
        order.setPrice(price);
        order.setRemainingQuantity(FixedPoint.fromDouble(1.0));
        order.setSide(OrderSide.ASK);
        order.setType(OrderType.LIMIT);
        return new Level(order);
    }

    // ==================== Basic CRUD ====================

    @Test
    public void testPut_NewKey_ReturnsNull() {
        long price = FixedPoint.fromDouble(100.0);
        Level level = createLevel(100.0);
        Level old = ascTree.put(price, level);
        assertNull(old);
        assertEquals(1, ascTree.size());
    }

    @Test
    public void testPut_DuplicateKey_ReturnsOldValue() {
        long price = FixedPoint.fromDouble(100.0);
        Level level1 = createLevel(100.0);
        Level level2 = createLevel(100.0);

        assertNull(ascTree.put(price, level1));
        Level old = ascTree.put(price, level2);
        assertSame(level1, old);
        assertEquals(1, ascTree.size()); // size should not change
    }

    @Test
    public void testGet_ExistingKey_ReturnsValue() {
        long price = FixedPoint.fromDouble(100.0);
        Level level = createLevel(100.0);
        ascTree.put(price, level);
        assertSame(level, ascTree.get(price));
    }

    @Test
    public void testGet_NonExistentKey_ReturnsNull() {
        assertNull(ascTree.get(FixedPoint.fromDouble(100.0)));
    }

    @Test
    public void testContainsKey_ExistingKey_ReturnsTrue() {
        long price = FixedPoint.fromDouble(100.0);
        ascTree.put(price, createLevel(100.0));
        assertTrue(ascTree.containsKey(price));
    }

    @Test
    public void testContainsKey_NonExistentKey_ReturnsFalse() {
        assertFalse(ascTree.containsKey(FixedPoint.fromDouble(100.0)));
    }

    @Test
    public void testRemove_ExistingKey_ReturnsValue() {
        long price = FixedPoint.fromDouble(100.0);
        Level level = createLevel(100.0);
        ascTree.put(price, level);
        Level removed = ascTree.remove(price);
        assertSame(level, removed);
        assertEquals(0, ascTree.size());
        assertFalse(ascTree.containsKey(price));
    }

    @Test
    public void testRemove_NonExistentKey_ReturnsNull() {
        assertNull(ascTree.remove(FixedPoint.fromDouble(100.0)));
    }

    @Test
    public void testPutMultiple_GetAll() {
        for (int i = 1; i <= 10; i++) {
            long price = FixedPoint.fromDouble(i * 10.0);
            ascTree.put(price, createLevel(i * 10.0));
        }
        assertEquals(10, ascTree.size());
        for (int i = 1; i <= 10; i++) {
            long price = FixedPoint.fromDouble(i * 10.0);
            assertNotNull(ascTree.get(price));
        }
    }

    // ==================== Min/Max Cached Extremes ====================

    @Test
    public void testGetMin_EmptyTree_ReturnsNull() {
        assertNull(ascTree.getMin());
    }

    @Test
    public void testGetMax_EmptyTree_ReturnsNull() {
        assertNull(ascTree.getMax());
    }

    @Test
    public void testGetMinPrice_EmptyTree_ReturnsMaxValue() {
        assertEquals(Long.MAX_VALUE, ascTree.getMinPrice());
    }

    @Test
    public void testGetMaxPrice_EmptyTree_ReturnsMinValue() {
        assertEquals(Long.MIN_VALUE, ascTree.getMaxPrice());
    }

    @Test
    public void testGetMin_SingleElement() {
        long price = FixedPoint.fromDouble(50.0);
        Level level = createLevel(50.0);
        ascTree.put(price, level);
        assertSame(level, ascTree.getMin());
        assertEquals(price, ascTree.getMinPrice());
    }

    @Test
    public void testGetMax_SingleElement() {
        long price = FixedPoint.fromDouble(50.0);
        Level level = createLevel(50.0);
        ascTree.put(price, level);
        assertSame(level, ascTree.getMax());
        assertEquals(price, ascTree.getMaxPrice());
    }

    @Test
    public void testGetMinMax_MultipleElements() {
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));

        assertEquals(FixedPoint.fromDouble(50.0), ascTree.getMinPrice());
        assertEquals(FixedPoint.fromDouble(200.0), ascTree.getMaxPrice());
    }

    @Test
    public void testRemoveMin_UpdatesCache() {
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(150.0), createLevel(150.0));

        ascTree.remove(FixedPoint.fromDouble(50.0));
        assertEquals(FixedPoint.fromDouble(100.0), ascTree.getMinPrice());
    }

    @Test
    public void testRemoveMax_UpdatesCache() {
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(150.0), createLevel(150.0));

        ascTree.remove(FixedPoint.fromDouble(150.0));
        assertEquals(FixedPoint.fromDouble(100.0), ascTree.getMaxPrice());
    }

    @Test
    public void testRemoveAll_ViaLeafOrder_MinMaxReset() {
        // Remove leaf nodes (100 is leaf, then 50 becomes leaf) to avoid
        // the copy-on-delete pattern for non-leaf nodes.
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));

        ascTree.remove(FixedPoint.fromDouble(100.0)); // leaf removal
        ascTree.remove(FixedPoint.fromDouble(50.0));  // now also leaf

        assertNull(ascTree.getMin());
        assertNull(ascTree.getMax());
        assertEquals(Long.MAX_VALUE, ascTree.getMinPrice());
        assertEquals(Long.MIN_VALUE, ascTree.getMaxPrice());
    }

    @Test
    public void testClear_MinMaxReset() {
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));

        ascTree.clear();

        assertNull(ascTree.getMin());
        assertNull(ascTree.getMax());
        assertEquals(Long.MAX_VALUE, ascTree.getMinPrice());
        assertEquals(Long.MIN_VALUE, ascTree.getMaxPrice());
        assertEquals(0, ascTree.size());
    }

    // ==================== getBest/getBestPrice (ascending vs descending) ====================

    @Test
    public void testGetBest_Ascending_ReturnsMin() {
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));

        // Ascending tree (ask side): best = min
        assertEquals(FixedPoint.fromDouble(50.0), ascTree.getBestPrice());
        assertNotNull(ascTree.getBest());
    }

    @Test
    public void testGetBest_Descending_ReturnsMax() {
        descTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        descTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        descTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));

        // Descending tree (bid side): best = max
        assertEquals(FixedPoint.fromDouble(200.0), descTree.getBestPrice());
        assertNotNull(descTree.getBest());
    }

    @Test
    public void testGetBestPrice_EmptyAscending_ReturnsMaxValue() {
        assertEquals(Long.MAX_VALUE, ascTree.getBestPrice());
    }

    @Test
    public void testGetBestPrice_EmptyDescending_ReturnsMinValue() {
        assertEquals(Long.MIN_VALUE, descTree.getBestPrice());
    }

    // ==================== size/isEmpty/clear ====================

    @Test
    public void testIsEmpty_NewTree_ReturnsTrue() {
        assertTrue(ascTree.isEmpty());
        assertEquals(0, ascTree.size());
    }

    @Test
    public void testIsEmpty_AfterInsert_ReturnsFalse() {
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        assertFalse(ascTree.isEmpty());
    }

    @Test
    public void testClear_RemovesAll() {
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));
        ascTree.put(FixedPoint.fromDouble(300.0), createLevel(300.0));

        ascTree.clear();
        assertTrue(ascTree.isEmpty());
        assertEquals(0, ascTree.size());
        assertNull(ascTree.getMin());
        assertNull(ascTree.getMax());
        assertNull(ascTree.get(FixedPoint.fromDouble(100.0)));
    }

    // ==================== Iterator ====================

    @Test
    public void testIterator_Ascending_VisitsInOrder() {
        ascTree.put(FixedPoint.fromDouble(300.0), createLevel(300.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));

        ascTree.startIteration();
        List<Long> prices = new ArrayList<>();
        while (ascTree.hasNext()) {
            long price = ascTree.nextPrice();
            ascTree.next(); // advance
            prices.add(price);
        }

        assertEquals(3, prices.size());
        assertEquals(FixedPoint.fromDouble(100.0), (long) prices.get(0));
        assertEquals(FixedPoint.fromDouble(200.0), (long) prices.get(1));
        assertEquals(FixedPoint.fromDouble(300.0), (long) prices.get(2));
    }

    @Test
    public void testIterator_Descending_VisitsInReverseOrder() {
        descTree.put(FixedPoint.fromDouble(300.0), createLevel(300.0));
        descTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        descTree.put(FixedPoint.fromDouble(200.0), createLevel(200.0));

        descTree.startIteration();
        List<Long> prices = new ArrayList<>();
        while (descTree.hasNext()) {
            long price = descTree.nextPrice();
            descTree.next(); // advance
            prices.add(price);
        }

        assertEquals(3, prices.size());
        assertEquals(FixedPoint.fromDouble(300.0), (long) prices.get(0));
        assertEquals(FixedPoint.fromDouble(200.0), (long) prices.get(1));
        assertEquals(FixedPoint.fromDouble(100.0), (long) prices.get(2));
    }

    @Test
    public void testIterator_EmptyTree_HasNextFalse() {
        ascTree.startIteration();
        assertFalse(ascTree.hasNext());
    }

    @Test
    public void testIterator_SingleElement() {
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.startIteration();
        assertTrue(ascTree.hasNext());
        assertNotNull(ascTree.next());
        assertFalse(ascTree.hasNext());
    }

    @Test
    public void testNext_ExhaustedIterator_ReturnsNull() {
        ascTree.startIteration();
        assertNull(ascTree.next());
    }

    @Test
    public void testNextPrice_EmptyAscending_ReturnsMaxValue() {
        ascTree.startIteration();
        assertEquals(Long.MAX_VALUE, ascTree.nextPrice());
    }

    @Test
    public void testNextPrice_EmptyDescending_ReturnsMinValue() {
        descTree.startIteration();
        assertEquals(Long.MIN_VALUE, descTree.nextPrice());
    }

    // ==================== Pool Exhaustion/Expansion ====================

    @Test
    public void testPoolExpansion_InsertMoreThan1024Nodes() {
        // Default pool is 1024 nodes. Insert >1024 to trigger expansion.
        int count = 1100;
        for (int i = 0; i < count; i++) {
            long price = (long) i * FixedPoint.SCALE_FACTOR; // unique prices
            ascTree.put(price, createLevelAtFixedPrice(price));
        }
        assertEquals(count, ascTree.size());

        // Verify all are accessible
        for (int i = 0; i < count; i++) {
            long price = (long) i * FixedPoint.SCALE_FACTOR;
            assertNotNull("Missing key at index " + i, ascTree.get(price));
        }
    }

    // ==================== Edge Cases ====================

    @Test
    public void testEmptyTreeOperations() {
        assertNull(ascTree.get(0));
        assertNull(ascTree.remove(0));
        assertFalse(ascTree.containsKey(0));
        assertNull(ascTree.getMin());
        assertNull(ascTree.getMax());
        assertTrue(ascTree.isEmpty());
        assertEquals(0, ascTree.size());
        // Clear on empty should not throw
        ascTree.clear();
    }

    @Test
    public void testSingleElement_InsertAndRemove() {
        long price = FixedPoint.fromDouble(42.0);
        Level level = createLevel(42.0);
        ascTree.put(price, level);
        assertEquals(1, ascTree.size());
        assertSame(level, ascTree.getBest());

        Level removed = ascTree.remove(price);
        assertSame(level, removed);
        assertTrue(ascTree.isEmpty());
        assertNull(ascTree.getBest());
    }

    @Test
    public void testDuplicateKeyPut_ReplacesValue() {
        long price = FixedPoint.fromDouble(100.0);
        Level level1 = createLevel(100.0);
        Level level2 = createLevel(100.0);

        ascTree.put(price, level1);
        ascTree.put(price, level2);

        assertSame(level2, ascTree.get(price));
        assertEquals(1, ascTree.size());
    }

    // ==================== Large Tree Stress Test ====================

    @Test
    public void testStress_InsertMany_ThenClear() {
        int count = 500;
        // Insert in random order
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices);

        // Insert all
        for (int i : indices) {
            long price = (long) (i + 1) * FixedPoint.SCALE_FACTOR;
            ascTree.put(price, createLevelAtFixedPrice(price));
        }
        assertEquals(count, ascTree.size());
        assertEquals(FixedPoint.SCALE_FACTOR, ascTree.getMinPrice());
        assertEquals((long) count * FixedPoint.SCALE_FACTOR, ascTree.getMaxPrice());

        // Verify iteration is in ascending order
        ascTree.startIteration();
        int iterCount = 0;
        long prevPrice = Long.MIN_VALUE;
        while (ascTree.hasNext()) {
            long price = ascTree.nextPrice();
            ascTree.next();
            assertTrue("Ascending order violated", price > prevPrice);
            prevPrice = price;
            iterCount++;
        }
        assertEquals(count, iterCount);

        // Clear and verify
        ascTree.clear();
        assertTrue(ascTree.isEmpty());
        assertEquals(0, ascTree.size());
    }

    @Test
    public void testStress_RemoveLeafNodes() {
        // Insert 3 nodes: left-child leaf can be removed safely
        ascTree.put(FixedPoint.fromDouble(50.0), createLevel(50.0));
        ascTree.put(FixedPoint.fromDouble(100.0), createLevel(100.0));
        ascTree.put(FixedPoint.fromDouble(150.0), createLevel(150.0));

        // Remove the max (a leaf in AA tree after balancing)
        Level removed = ascTree.remove(FixedPoint.fromDouble(150.0));
        assertNotNull(removed);
        assertEquals(2, ascTree.size());

        // Remove the new max
        removed = ascTree.remove(FixedPoint.fromDouble(100.0));
        assertNotNull(removed);
        assertEquals(1, ascTree.size());

        // Remove the last one
        removed = ascTree.remove(FixedPoint.fromDouble(50.0));
        assertNotNull(removed);
        assertEquals(0, ascTree.size());
        assertTrue(ascTree.isEmpty());
    }

    @Test
    public void testStress_DescendingIteration_Consistency() {
        int count = 200;
        for (int i = 0; i < count; i++) {
            long price = (long) (i + 1) * FixedPoint.SCALE_FACTOR;
            descTree.put(price, createLevelAtFixedPrice(price));
        }

        descTree.startIteration();
        long prevPrice = Long.MAX_VALUE;
        int iterCount = 0;
        while (descTree.hasNext()) {
            long price = descTree.nextPrice();
            descTree.next();
            assertTrue("Descending order violated", price < prevPrice);
            prevPrice = price;
            iterCount++;
        }
        assertEquals(count, iterCount);
    }

    @Test
    public void testInsertSequential_IterationCorrect() {
        // Insert sequential prices and verify in-order iteration
        for (int i = 1; i <= 20; i++) {
            long price = FixedPoint.fromDouble(i * 10.0);
            ascTree.put(price, createLevel(i * 10.0));
        }

        assertEquals(20, ascTree.size());
        assertEquals(FixedPoint.fromDouble(10.0), ascTree.getMinPrice());
        assertEquals(FixedPoint.fromDouble(200.0), ascTree.getMaxPrice());

        // Verify ascending iteration visits all 20
        ascTree.startIteration();
        int count = 0;
        long prev = Long.MIN_VALUE;
        while (ascTree.hasNext()) {
            long price = ascTree.nextPrice();
            ascTree.next();
            assertTrue("Order violated", price > prev);
            prev = price;
            count++;
        }
        assertEquals(20, count);
    }

    @Test
    public void testClear_AfterManyInserts_Reusable() {
        // Insert many, clear, insert again
        for (int i = 1; i <= 50; i++) {
            ascTree.put(FixedPoint.fromDouble(i), createLevel(i));
        }
        assertEquals(50, ascTree.size());

        ascTree.clear();
        assertTrue(ascTree.isEmpty());
        assertEquals(0, ascTree.size());

        // Reinsert
        for (int i = 100; i <= 110; i++) {
            ascTree.put(FixedPoint.fromDouble(i), createLevel(i));
        }
        assertEquals(11, ascTree.size());
        assertEquals(FixedPoint.fromDouble(100.0), ascTree.getMinPrice());
        assertEquals(FixedPoint.fromDouble(110.0), ascTree.getMaxPrice());
    }
}
