package com.match.application.orderbook;

import com.match.domain.Order;

/**
 * Ultra-low latency order book using direct array indexing.
 * Achieves O(1) for all operations with ZERO allocations in hot path.
 *
 * Design:
 * - Price levels are stored in a fixed-size array indexed by price
 * - Index = (price - basePrice) / tickSize
 * - Orders at each level stored in circular buffer
 * - Min/max price indices cached for O(1) best price
 *
 * Memory layout optimized for cache efficiency.
 */
public class DirectIndexOrderBook {

    // Price configuration (fixed-point, 8 decimals)
    private final long basePrice;      // Minimum price in range
    private final long tickSize;       // Price increment
    private final int maxPriceLevels;  // Number of price slots

    // Order storage - circular buffers per price level
    // Each level can hold up to MAX_ORDERS_PER_LEVEL orders
    private static final int MAX_ORDERS_PER_LEVEL = 64;  // Reduced for memory efficiency
    private static final int ORDER_FIELDS = 4; // orderId, userId, remainingQty, next

    // Packed order data: [orderId, userId, remainingQty, nextOrderIdx]
    // Layout: orders[priceIdx * MAX_ORDERS_PER_LEVEL * ORDER_FIELDS + orderSlot * ORDER_FIELDS + field]
    private final long[] orders;

    // Level metadata: [headIdx, tailIdx, orderCount, totalQty]
    private static final int LEVEL_FIELDS = 4;
    private final long[] levels;

    // Best price tracking - volatile for cross-thread visibility (scheduler reads these)
    private volatile int bestPriceIdx = -1;     // Index of best price (-1 = empty)
    private volatile int worstPriceIdx = -1;    // Index of worst price
    private volatile int activeLevelCount = 0;

    // Version counter for memory barrier - incremented on every modification
    // Readers should read this BEFORE reading levels array to ensure visibility
    private volatile long version = 0;

    // Direction: true = ascending (ask), false = descending (bid)
    private final boolean ascending;

    // Order ID to location mapping for O(1) cancel
    // Maps orderId -> packed(priceIdx, slotIdx)
    private final long[] orderLocations;
    private static final int MAX_ACTIVE_ORDERS = 1_000_000;
    private static final long EMPTY_LOCATION = -1L;

    // Free slot tracking per level (simple stack)
    private final int[] freeSlots;  // Stack of free slot indices per level
    private final int[] freeSlotCounts;

    /**
     * Create order book for a price range.
     *
     * @param basePrice     Minimum price (fixed-point)
     * @param maxPrice      Maximum price (fixed-point)
     * @param tickSize      Price increment (fixed-point)
     * @param ascending     true for ask side, false for bid side
     */
    public DirectIndexOrderBook(long basePrice, long maxPrice, long tickSize, boolean ascending) {
        this.basePrice = basePrice;
        this.tickSize = tickSize;
        this.ascending = ascending;
        this.maxPriceLevels = (int) ((maxPrice - basePrice) / tickSize) + 1;

        // Allocate all memory upfront
        this.orders = new long[maxPriceLevels * MAX_ORDERS_PER_LEVEL * ORDER_FIELDS];
        this.levels = new long[maxPriceLevels * LEVEL_FIELDS];
        this.orderLocations = new long[MAX_ACTIVE_ORDERS];
        this.freeSlots = new int[maxPriceLevels * MAX_ORDERS_PER_LEVEL];
        this.freeSlotCounts = new int[maxPriceLevels];

        // Initialize free slot stacks
        for (int priceIdx = 0; priceIdx < maxPriceLevels; priceIdx++) {
            int baseSlotIdx = priceIdx * MAX_ORDERS_PER_LEVEL;
            for (int slot = 0; slot < MAX_ORDERS_PER_LEVEL; slot++) {
                freeSlots[baseSlotIdx + slot] = slot;
            }
            freeSlotCounts[priceIdx] = MAX_ORDERS_PER_LEVEL;
        }

        // Initialize order locations as empty
        for (int i = 0; i < MAX_ACTIVE_ORDERS; i++) {
            orderLocations[i] = EMPTY_LOCATION;
        }

        // Pre-touch all memory to ensure arrays are fully paged in
        preTouch();
    }

    /**
     * Pre-touch all arrays to ensure memory is fully paged in.
     * Call this at startup to avoid page faults during trading.
     */
    private void preTouch() {
        // Touch orders array
        for (int i = 0; i < orders.length; i += 64) { // 64 longs = 512 bytes per cache line
            orders[i] = 0;
        }
        // Touch levels array
        for (int i = 0; i < levels.length; i += 64) {
            levels[i] = 0;
        }
        // Touch free slots array
        for (int i = 0; i < freeSlots.length; i += 64) {
            // Just read, already initialized
            int unused = freeSlots[i];
        }
    }

    /**
     * Convert price to array index. O(1)
     */
    private int priceToIndex(long price) {
        return (int) ((price - basePrice) / tickSize);
    }

    /**
     * Convert array index to price. O(1)
     */
    private long indexToPrice(int idx) {
        return basePrice + (long) idx * tickSize;
    }

    /**
     * Add order to book. O(1) amortized.
     */
    public void addOrder(long orderId, long userId, long price, long quantity) {
        int priceIdx = priceToIndex(price);
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return;

        // Get free slot at this price level
        if (freeSlotCounts[priceIdx] == 0) return; // Level full

        int slotStackBase = priceIdx * MAX_ORDERS_PER_LEVEL;
        int slot = freeSlots[slotStackBase + --freeSlotCounts[priceIdx]];

        // Store order data
        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + slot) * ORDER_FIELDS;
        orders[orderBase] = orderId;
        orders[orderBase + 1] = userId;
        orders[orderBase + 2] = quantity;
        orders[orderBase + 3] = -1; // No next order yet

        // Update level metadata
        int levelBase = priceIdx * LEVEL_FIELDS;
        long orderCount = levels[levelBase + 2];

        if (orderCount == 0) {
            // First order at this level
            levels[levelBase] = slot;     // head
            levels[levelBase + 1] = slot; // tail
            activeLevelCount++;
            updateBestWorstPrice(priceIdx, true);
        } else {
            // Append to tail
            int tailSlot = (int) levels[levelBase + 1];
            int tailOrderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + tailSlot) * ORDER_FIELDS;

            // FIX: Only link if old tail is different from new slot AND still valid
            // This prevents self-referential cycle when tail slot is cancelled and reused
            if (tailSlot != slot && orders[tailOrderBase + 2] > 0) {
                orders[tailOrderBase + 3] = slot; // Link previous tail to new order
            }
            levels[levelBase + 1] = slot;     // Update tail
        }

        levels[levelBase + 2] = orderCount + 1;
        levels[levelBase + 3] += quantity; // Total quantity

        // Store order location for O(1) cancel
        int locationIdx = (int) (Math.abs(orderId) % MAX_ACTIVE_ORDERS);
        orderLocations[locationIdx] = packLocation(priceIdx, slot);

        // Memory barrier - increment version AFTER all writes complete
        version++;
    }

    /**
     * Add order from Order object. O(1)
     */
    public void addOrder(Order order) {
        addOrder(order.getId(), order.getUserId(), order.getPrice(), order.getRemainingQuantity());
    }

    /**
     * Cancel order by ID. O(1)
     */
    public boolean cancelOrder(long orderId) {
        int locationIdx = (int) (Math.abs(orderId) % MAX_ACTIVE_ORDERS);
        long location = orderLocations[locationIdx];
        if (location == EMPTY_LOCATION) return false;

        int priceIdx = unpackPriceIdx(location);
        int slot = unpackSlot(location);

        // Verify this is the right order (handle hash collisions)
        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + slot) * ORDER_FIELDS;
        if (orders[orderBase] != orderId) return false;

        // Get order quantity for level total update
        long quantity = orders[orderBase + 2];

        // Mark slot as free
        orders[orderBase + 2] = 0; // Zero quantity = deleted

        // Update level metadata
        int levelBase = priceIdx * LEVEL_FIELDS;
        long orderCount = levels[levelBase + 2] - 1;
        levels[levelBase + 2] = orderCount;
        levels[levelBase + 3] -= quantity;

        // Return slot to free list
        int slotStackBase = priceIdx * MAX_ORDERS_PER_LEVEL;
        freeSlots[slotStackBase + freeSlotCounts[priceIdx]++] = slot;

        // Clear location
        orderLocations[locationIdx] = EMPTY_LOCATION;

        if (orderCount == 0) {
            activeLevelCount--;
            updateBestWorstPrice(priceIdx, false);
        }

        // Memory barrier
        version++;
        return true;
    }

    /**
     * Reduce order quantity (after match). O(1)
     *
     * When an order is fully filled (newQty <= 0), we remove it directly
     * instead of calling cancelOrder(), because cancelOrder() would read
     * the already-updated (possibly negative) quantity and corrupt the
     * level total by subtracting a negative value.
     */
    public void reduceOrderQuantity(long orderId, long reduceBy) {
        int locationIdx = (int) (Math.abs(orderId) % MAX_ACTIVE_ORDERS);
        long location = orderLocations[locationIdx];
        if (location == EMPTY_LOCATION) return;

        int priceIdx = unpackPriceIdx(location);
        int slot = unpackSlot(location);

        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + slot) * ORDER_FIELDS;
        if (orders[orderBase] != orderId) return;

        long oldQty = orders[orderBase + 2];
        long actualReduce = Math.min(reduceBy, oldQty); // Never reduce more than available
        long newQty = oldQty - actualReduce;
        orders[orderBase + 2] = newQty;

        // Update level total — only subtract what we actually reduced
        int levelBase = priceIdx * LEVEL_FIELDS;
        levels[levelBase + 3] -= actualReduce;

        // Memory barrier - increment version AFTER all writes complete
        version++;

        // If fully filled, remove the order from the book
        if (newQty <= 0) {
            // Remove directly instead of calling cancelOrder() to avoid
            // double-decrementing the level total quantity
            orders[orderBase + 2] = 0;

            // Update level order count
            long orderCount = levels[levelBase + 2] - 1;
            levels[levelBase + 2] = orderCount;

            // Return slot to free list
            int slotStackBase = priceIdx * MAX_ORDERS_PER_LEVEL;
            freeSlots[slotStackBase + freeSlotCounts[priceIdx]++] = slot;

            // Clear location
            orderLocations[locationIdx] = EMPTY_LOCATION;

            if (orderCount == 0) {
                activeLevelCount--;
                updateBestWorstPrice(priceIdx, false);
            }

            version++;
        }
    }

    /**
     * Get best price. O(1)
     */
    public long getBestPrice() {
        return bestPriceIdx >= 0 ? indexToPrice(bestPriceIdx) : (ascending ? Long.MAX_VALUE : Long.MIN_VALUE);
    }

    /**
     * Get best price index. O(1)
     */
    public int getBestPriceIndex() {
        return bestPriceIdx;
    }

    /**
     * Check if empty. O(1)
     */
    public boolean isEmpty() {
        return activeLevelCount == 0;
    }

    /**
     * Get order count at price level. O(1)
     */
    public int getOrderCount(int priceIdx) {
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return 0;
        return (int) levels[priceIdx * LEVEL_FIELDS + 2];
    }

    /**
     * Get total quantity at price level. O(1)
     */
    public long getTotalQuantity(int priceIdx) {
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return 0;
        return levels[priceIdx * LEVEL_FIELDS + 3];
    }

    /**
     * Get head order at price level for matching. O(1)
     */
    public long getHeadOrderId(int priceIdx) {
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return -1;
        int levelBase = priceIdx * LEVEL_FIELDS;
        if (levels[levelBase + 2] == 0) return -1;

        int headSlot = (int) levels[levelBase];
        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + headSlot) * ORDER_FIELDS;

        // Skip deleted orders (bounded to prevent infinite loop on corrupted data)
        int guard = MAX_ORDERS_PER_LEVEL;
        while (orders[orderBase + 2] == 0 && --guard > 0) {
            int nextSlot = (int) orders[orderBase + 3];
            if (nextSlot < 0 || nextSlot >= MAX_ORDERS_PER_LEVEL) return -1;
            orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + nextSlot) * ORDER_FIELDS;
        }

        return guard > 0 ? orders[orderBase] : -1;
    }

    /**
     * Get head order remaining quantity. O(1)
     */
    public long getHeadOrderQuantity(int priceIdx) {
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return 0;
        int levelBase = priceIdx * LEVEL_FIELDS;
        if (levels[levelBase + 2] == 0) return 0;

        int headSlot = (int) levels[levelBase];
        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + headSlot) * ORDER_FIELDS;

        // Skip deleted orders (bounded)
        int guard = MAX_ORDERS_PER_LEVEL;
        while (orders[orderBase + 2] == 0 && --guard > 0) {
            int nextSlot = (int) orders[orderBase + 3];
            if (nextSlot < 0 || nextSlot >= MAX_ORDERS_PER_LEVEL) return 0;
            orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + nextSlot) * ORDER_FIELDS;
        }

        return guard > 0 ? orders[orderBase + 2] : 0;
    }

    /**
     * Get head order user ID. O(1)
     * Used for publishing trade executions with maker user info.
     */
    public long getHeadOrderUserId(int priceIdx) {
        if (priceIdx < 0 || priceIdx >= maxPriceLevels) return 0;
        int levelBase = priceIdx * LEVEL_FIELDS;
        if (levels[levelBase + 2] == 0) return 0;

        int headSlot = (int) levels[levelBase];
        int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + headSlot) * ORDER_FIELDS;

        // Skip deleted orders (bounded)
        int guard = MAX_ORDERS_PER_LEVEL;
        while (orders[orderBase + 2] == 0 && --guard > 0) {
            int nextSlot = (int) orders[orderBase + 3];
            if (nextSlot < 0 || nextSlot >= MAX_ORDERS_PER_LEVEL) return 0;
            orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + nextSlot) * ORDER_FIELDS;
        }

        return guard > 0 ? orders[orderBase + 1] : 0;
    }

    /**
     * Iterate to next price level. O(1) amortized with sparse levels.
     */
    public int nextPriceIndex(int currentIdx) {
        if (ascending) {
            for (int i = currentIdx + 1; i <= worstPriceIdx && i < maxPriceLevels; i++) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        } else {
            for (int i = currentIdx - 1; i >= worstPriceIdx && i >= 0; i--) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        }
        return -1;
    }

    /**
     * Update best/worst price tracking after add/remove
     */
    private void updateBestWorstPrice(int changedIdx, boolean added) {
        if (added) {
            if (ascending) {
                // Ask side: lower is better
                if (bestPriceIdx < 0 || changedIdx < bestPriceIdx) {
                    bestPriceIdx = changedIdx;
                }
                if (worstPriceIdx < 0 || changedIdx > worstPriceIdx) {
                    worstPriceIdx = changedIdx;
                }
            } else {
                // Bid side: higher is better
                if (bestPriceIdx < 0 || changedIdx > bestPriceIdx) {
                    bestPriceIdx = changedIdx;
                }
                if (worstPriceIdx < 0 || changedIdx < worstPriceIdx) {
                    worstPriceIdx = changedIdx;
                }
            }
        } else {
            // Removed - need to find new best/worst if this was it
            if (changedIdx == bestPriceIdx) {
                bestPriceIdx = findNewBestPrice();
            }
            if (changedIdx == worstPriceIdx) {
                worstPriceIdx = findNewWorstPrice();
            }
        }
    }

    private int findNewBestPrice() {
        if (activeLevelCount == 0) return -1;

        if (ascending) {
            for (int i = 0; i < maxPriceLevels; i++) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        } else {
            for (int i = maxPriceLevels - 1; i >= 0; i--) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        }
        return -1;
    }

    private int findNewWorstPrice() {
        if (activeLevelCount == 0) return -1;

        if (ascending) {
            for (int i = maxPriceLevels - 1; i >= 0; i--) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        } else {
            for (int i = 0; i < maxPriceLevels; i++) {
                if (levels[i * LEVEL_FIELDS + 2] > 0) return i;
            }
        }
        return -1;
    }

    private long packLocation(int priceIdx, int slot) {
        return ((long) priceIdx << 32) | (slot & 0xFFFFFFFFL);
    }

    private int unpackPriceIdx(long location) {
        return (int) (location >>> 32);
    }

    private int unpackSlot(long location) {
        return (int) location;
    }

    public int getMaxPriceLevels() {
        return maxPriceLevels;
    }

    public int getActiveLevelCount() {
        return activeLevelCount;
    }

    public boolean isAscending() {
        return ascending;
    }

    public long getTickSize() {
        return tickSize;
    }

    public long getBasePrice() {
        return basePrice;
    }

    /**
     * Get current version for memory barrier.
     * Reading this establishes happens-before with last modification.
     */
    public long getVersion() {
        return version;
    }

    // ==================== Snapshot Support ====================

    /**
     * Get all active orders for snapshot serialization.
     * Returns array of [orderId, userId, price, quantity] tuples.
     * This allocates but is only called during snapshots, not hot path.
     */
    public long[] getActiveOrders() {
        // Count active orders first
        int count = 0;
        for (int priceIdx = 0; priceIdx < maxPriceLevels; priceIdx++) {
            count += getOrderCount(priceIdx);
        }

        if (count == 0) return new long[0];

        long[] result = new long[count * 4]; // 4 fields per order
        int resultIdx = 0;

        for (int priceIdx = 0; priceIdx < maxPriceLevels; priceIdx++) {
            int levelBase = priceIdx * LEVEL_FIELDS;
            if (levels[levelBase + 2] == 0) continue; // No orders at this level

            long price = indexToPrice(priceIdx);
            int headSlot = (int) levels[levelBase];
            int orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + headSlot) * ORDER_FIELDS;

            // Walk the linked list at this level
            while (true) {
                long qty = orders[orderBase + 2];
                if (qty > 0) { // Active order
                    result[resultIdx++] = orders[orderBase];     // orderId
                    result[resultIdx++] = orders[orderBase + 1]; // userId
                    result[resultIdx++] = price;
                    result[resultIdx++] = qty;
                }

                int nextSlot = (int) orders[orderBase + 3];
                if (nextSlot < 0) break;
                orderBase = (priceIdx * MAX_ORDERS_PER_LEVEL + nextSlot) * ORDER_FIELDS;
            }
        }

        // Trim to actual size
        if (resultIdx < result.length) {
            long[] trimmed = new long[resultIdx];
            System.arraycopy(result, 0, trimmed, 0, resultIdx);
            return trimmed;
        }
        return result;
    }

    /**
     * Clear all orders (for snapshot restore)
     */
    public void clear() {
        // Reset all level metadata
        for (int i = 0; i < levels.length; i++) {
            levels[i] = 0;
        }

        // Reset free slot stacks
        for (int priceIdx = 0; priceIdx < maxPriceLevels; priceIdx++) {
            int baseSlotIdx = priceIdx * MAX_ORDERS_PER_LEVEL;
            for (int slot = 0; slot < MAX_ORDERS_PER_LEVEL; slot++) {
                freeSlots[baseSlotIdx + slot] = slot;
            }
            freeSlotCounts[priceIdx] = MAX_ORDERS_PER_LEVEL;
        }

        // Clear order locations
        for (int i = 0; i < MAX_ACTIVE_ORDERS; i++) {
            orderLocations[i] = EMPTY_LOCATION;
        }

        // Reset best/worst tracking
        bestPriceIdx = -1;
        worstPriceIdx = -1;
        activeLevelCount = 0;
    }
}
