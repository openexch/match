// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

import com.match.domain.FixedPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Found by the RC2 S3 independent verification (S3/review.md, pre-existing at 3b95c56, NOT an S3
 * change): {@link DirectIndexOrderBook} walks a level's chain with {@code guard = maxOrdersPerLevel}
 * and {@code --guard > 0}, so it visits at most {@code maxOrdersPerLevel - 1} nodes. A level whose
 * chain holds {@code maxOrdersPerLevel} nodes therefore (a) loses its last live order from
 * {@code getActiveOrders()}, i.e. from the snapshot (SnapshotCodec writes getAskOrders/getBidOrders),
 * and (b) leaks one slot in {@code freeAllSlotsAtLevel}. Every member serializes the same lossy
 * way, so member snapshots stay byte-equal while a member restored from one has lost the order.
 * Pinned RED until the guards become {@code maxOrdersPerLevel + 1} (as {@code compactLevel} has).
 */
public class DirectBookFullLevelSnapshotVerifierTest {

    private static final long BASE = FixedPoint.fromDouble(50_000.0);
    private static final long MAX = FixedPoint.fromDouble(50_200.0);
    private static final long TICK = FixedPoint.fromDouble(1.0);
    private static final long PRICE = FixedPoint.fromDouble(50_100.0);
    private static final long QTY = FixedPoint.fromDouble(1.0);

    @Test
    public void aFullLevelExportsEveryLiveOrder() {
        for (int perLevel : new int[]{64, 2}) {
            final DirectIndexOrderBook book = new DirectIndexOrderBook(BASE, MAX, TICK, true, perLevel);
            for (int i = 0; i < perLevel; i++) {
                assertEquals(OrderRejectReason.NONE, book.addOrder(1 + i, 100, PRICE, QTY));
            }
            assertEquals("live count", perLevel, book.getOrderCount(book.getBestPriceIndex()));
            assertEquals("perLevel " + perLevel + ": every live order is in the snapshot export",
                    perLevel * 4, book.getActiveOrders().length);
        }
    }

    @Test
    public void emptyingAFullLevelFreesEverySlot() {
        final int perLevel = 2;
        final DirectIndexOrderBook book = new DirectIndexOrderBook(BASE, MAX, TICK, true, perLevel);
        assertEquals(OrderRejectReason.NONE, book.addOrder(1, 100, PRICE, QTY));
        assertEquals(OrderRejectReason.NONE, book.addOrder(2, 100, PRICE, QTY));
        book.cancelOrder(2);   // tombstone at the tail
        book.cancelOrder(1);   // level empty: both slots must return to the free stack
        assertEquals(OrderRejectReason.NONE, book.addOrder(3, 100, PRICE, QTY));
        assertEquals("a fresh book accepts a second order at this level",
                OrderRejectReason.NONE, book.addOrder(4, 100, PRICE, QTY));
    }
}
