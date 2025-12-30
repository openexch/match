package com.match.application.orderbook;

import com.match.domain.Level;

/**
 * AA Tree implementation optimized for ultra-low latency order book price levels.
 *
 * Features:
 * - O(log n) insert, delete, search
 * - O(1) access to min/max (cached)
 * - Node pooling to eliminate GC pressure
 * - Primitive long keys (prices)
 *
 * AA Trees are simplified Red-Black trees with fewer rotation cases,
 * making them faster in practice for small to medium datasets.
 */
public class AATree {

    // Sentinel node (replaces null, simplifies code)
    private static final Node NIL = new Node();
    static {
        NIL.level = 0;
        NIL.left = NIL;
        NIL.right = NIL;
        NIL.price = Long.MIN_VALUE;
        NIL.value = null;
    }

    private Node root = NIL;
    private Node minNode = NIL;  // Cached min for O(1) access
    private Node maxNode = NIL;  // Cached max for O(1) access
    private int size = 0;

    // Node pool for zero-allocation operations
    private Node[] nodePool;
    private int poolIndex = 0;
    private static final int INITIAL_POOL_SIZE = 1024;

    // Reusable stack for iteration (avoid allocation)
    private final Node[] iterStack;
    private int iterStackTop;
    private Node iterCurrent;
    private final boolean ascending;

    public AATree(boolean ascending) {
        this.ascending = ascending;
        this.nodePool = new Node[INITIAL_POOL_SIZE];
        this.iterStack = new Node[64]; // Max tree depth ~64 for practical sizes

        // Pre-allocate node pool
        for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
            nodePool[i] = new Node();
        }
    }

    /**
     * Tree node with level for AA tree balancing
     */
    static final class Node {
        long price;
        Level value;
        int level = 1;
        Node left = NIL;
        Node right = NIL;

        void reset() {
            this.price = 0;
            this.value = null;
            this.level = 1;
            this.left = NIL;
            this.right = NIL;
        }
    }

    /**
     * Get node from pool (zero allocation)
     */
    private Node allocateNode() {
        if (poolIndex > 0) {
            return nodePool[--poolIndex];
        }
        // Pool exhausted, expand it
        expandPool();
        return nodePool[--poolIndex];
    }

    /**
     * Return node to pool
     */
    private void releaseNode(Node node) {
        node.reset();
        if (poolIndex < nodePool.length) {
            nodePool[poolIndex++] = node;
        }
        // If pool is full, let it be GC'd (rare case)
    }

    /**
     * Expand node pool when exhausted
     */
    private void expandPool() {
        int newSize = nodePool.length * 2;
        Node[] newPool = new Node[newSize];
        System.arraycopy(nodePool, 0, newPool, 0, nodePool.length);
        for (int i = nodePool.length; i < newSize; i++) {
            newPool[i] = new Node();
        }
        poolIndex = nodePool.length;
        nodePool = newPool;
    }

    /**
     * Insert a price level. Returns existing level if price exists.
     * O(log n)
     */
    public Level put(long price, Level level) {
        // Check if exists first
        Node existing = findNode(price);
        if (existing != NIL) {
            Level old = existing.value;
            existing.value = level;
            return old;
        }

        // Insert new node
        Node node = allocateNode();
        node.price = price;
        node.value = level;
        node.level = 1;
        node.left = NIL;
        node.right = NIL;

        root = insert(root, node);
        size++;

        // Update min/max cache
        if (minNode == NIL || price < minNode.price) {
            minNode = node;
        }
        if (maxNode == NIL || price > maxNode.price) {
            maxNode = node;
        }

        return null;
    }

    /**
     * Get level by price. O(log n)
     */
    public Level get(long price) {
        Node node = findNode(price);
        return node != NIL ? node.value : null;
    }

    /**
     * Check if price exists. O(log n)
     */
    public boolean containsKey(long price) {
        return findNode(price) != NIL;
    }

    /**
     * Remove price level. O(log n)
     */
    public Level remove(long price) {
        Node toRemove = findNode(price);
        if (toRemove == NIL) {
            return null;
        }

        Level value = toRemove.value;
        root = delete(root, price);
        size--;

        // Update min/max cache
        if (toRemove == minNode) {
            minNode = findMin(root);
        }
        if (toRemove == maxNode) {
            maxNode = findMax(root);
        }

        releaseNode(toRemove);
        return value;
    }

    /**
     * Get minimum price level. O(1) due to caching
     */
    public Level getMin() {
        return minNode != NIL ? minNode.value : null;
    }

    /**
     * Get minimum price. O(1) due to caching
     */
    public long getMinPrice() {
        return minNode != NIL ? minNode.price : Long.MAX_VALUE;
    }

    /**
     * Get maximum price level. O(1) due to caching
     */
    public Level getMax() {
        return maxNode != NIL ? maxNode.value : null;
    }

    /**
     * Get maximum price. O(1) due to caching
     */
    public long getMaxPrice() {
        return maxNode != NIL ? maxNode.price : Long.MIN_VALUE;
    }

    /**
     * Get best price level based on side (ascending = ask, descending = bid)
     * O(1) due to caching
     */
    public Level getBest() {
        return ascending ? getMin() : getMax();
    }

    /**
     * Get best price based on side
     * O(1) due to caching
     */
    public long getBestPrice() {
        return ascending ? getMinPrice() : getMaxPrice();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Clear all entries, return nodes to pool
     */
    public void clear() {
        clearRecursive(root);
        root = NIL;
        minNode = NIL;
        maxNode = NIL;
        size = 0;
    }

    private void clearRecursive(Node node) {
        if (node != NIL) {
            clearRecursive(node.left);
            clearRecursive(node.right);
            releaseNode(node);
        }
    }

    // ==================== Iterator Support ====================

    /**
     * Start iteration from best price (min for ask, max for bid)
     * Call next() to get each level in order
     */
    public void startIteration() {
        iterStackTop = 0;
        if (ascending) {
            // In-order traversal (ascending)
            iterCurrent = root;
            while (iterCurrent != NIL) {
                iterStack[iterStackTop++] = iterCurrent;
                iterCurrent = iterCurrent.left;
            }
        } else {
            // Reverse in-order traversal (descending)
            iterCurrent = root;
            while (iterCurrent != NIL) {
                iterStack[iterStackTop++] = iterCurrent;
                iterCurrent = iterCurrent.right;
            }
        }
    }

    /**
     * Check if there are more levels to iterate
     */
    public boolean hasNext() {
        return iterStackTop > 0;
    }

    /**
     * Get next level in iteration order. Must call startIteration() first.
     */
    public Level next() {
        if (iterStackTop == 0) {
            return null;
        }

        Node node = iterStack[--iterStackTop];
        Level result = node.value;

        if (ascending) {
            // Move to right subtree, then all the way left
            iterCurrent = node.right;
            while (iterCurrent != NIL) {
                iterStack[iterStackTop++] = iterCurrent;
                iterCurrent = iterCurrent.left;
            }
        } else {
            // Move to left subtree, then all the way right
            iterCurrent = node.left;
            while (iterCurrent != NIL) {
                iterStack[iterStackTop++] = iterCurrent;
                iterCurrent = iterCurrent.right;
            }
        }

        return result;
    }

    /**
     * Get next price in iteration order
     */
    public long nextPrice() {
        if (iterStackTop == 0) {
            return ascending ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return iterStack[iterStackTop - 1].price;
    }

    // ==================== AA Tree Core Operations ====================

    private Node findNode(long price) {
        Node current = root;
        while (current != NIL) {
            if (price < current.price) {
                current = current.left;
            } else if (price > current.price) {
                current = current.right;
            } else {
                return current;
            }
        }
        return NIL;
    }

    private Node findMin(Node node) {
        if (node == NIL) return NIL;
        while (node.left != NIL) {
            node = node.left;
        }
        return node;
    }

    private Node findMax(Node node) {
        if (node == NIL) return NIL;
        while (node.right != NIL) {
            node = node.right;
        }
        return node;
    }

    /**
     * Skew: Right rotation when left child has same level
     */
    private Node skew(Node node) {
        if (node == NIL) return NIL;
        if (node.left.level == node.level) {
            // Rotate right
            Node left = node.left;
            node.left = left.right;
            left.right = node;
            return left;
        }
        return node;
    }

    /**
     * Split: Left rotation when right-right grandchild has same level
     */
    private Node split(Node node) {
        if (node == NIL) return NIL;
        if (node.right.right.level == node.level) {
            // Rotate left
            Node right = node.right;
            node.right = right.left;
            right.left = node;
            right.level++;
            return right;
        }
        return node;
    }

    private Node insert(Node node, Node newNode) {
        if (node == NIL) {
            return newNode;
        }

        if (newNode.price < node.price) {
            node.left = insert(node.left, newNode);
        } else if (newNode.price > node.price) {
            node.right = insert(node.right, newNode);
        } else {
            // Duplicate key - shouldn't happen with our usage
            return node;
        }

        // Balance
        node = skew(node);
        node = split(node);

        return node;
    }

    private Node delete(Node node, long price) {
        if (node == NIL) {
            return NIL;
        }

        if (price < node.price) {
            node.left = delete(node.left, price);
        } else if (price > node.price) {
            node.right = delete(node.right, price);
        } else {
            // Found node to delete
            if (node.left == NIL && node.right == NIL) {
                return NIL;
            } else if (node.left == NIL) {
                // Find successor
                Node successor = findMin(node.right);
                node.price = successor.price;
                node.value = successor.value;
                node.right = delete(node.right, successor.price);
            } else {
                // Find predecessor
                Node predecessor = findMax(node.left);
                node.price = predecessor.price;
                node.value = predecessor.value;
                node.left = delete(node.left, predecessor.price);
            }
        }

        // Rebalance
        node = decreaseLevel(node);
        node = skew(node);
        node.right = skew(node.right);
        if (node.right != NIL) {
            node.right.right = skew(node.right.right);
        }
        node = split(node);
        node.right = split(node.right);

        return node;
    }

    private Node decreaseLevel(Node node) {
        int shouldBe = Math.min(node.left.level, node.right.level) + 1;
        if (shouldBe < node.level) {
            node.level = shouldBe;
            if (shouldBe < node.right.level) {
                node.right.level = shouldBe;
            }
        }
        return node;
    }
}
