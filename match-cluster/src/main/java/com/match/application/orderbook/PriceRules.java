// SPDX-License-Identifier: Apache-2.0
package com.match.application.orderbook;

/**
 * Per-market limit-price rules, re-homed out of the (now geometry-free) {@link ArrayOrderBook}.
 *
 * <p>In the preallocated {@link DirectIndexOrderBook} these checks were storage-coupled: the price
 * range and tick were baked into the array geometry, so an out-of-range price was structurally
 * impossible and the ceiling could only change by reallocating the book. Here the array-backed book
 * accepts any price, and these rules become an explicit, adjustable policy layer consulted before
 * admission.
 *
 * <p><b>Tick size</b> stays a hard rule. The <b>range</b> ({@code minPrice}..{@code maxPrice}) is a
 * soft, runtime-adjustable bound — {@link #setMaxPrice} can widen or narrow it on the fly without
 * touching storage (the "adjust when the market runs past its limits" goal).
 *
 * <p>{@link #validate} reproduces {@code DirectIndexOrderBook.validatePrice} exactly (same checks,
 * same order, same reason codes) so existing rejection behavior — and the determinism goldens — are
 * preserved when matching moves to the array-backed book.
 */
public final class PriceRules {

    private final long minPrice;   // inclusive lower bound (was basePrice)
    private final long tickSize;
    private volatile long maxPrice; // inclusive upper bound; adjustable at runtime

    public PriceRules(long minPrice, long maxPrice, long tickSize) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive: " + tickSize);
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
        this.tickSize = tickSize;
    }

    /**
     * @return {@link OrderRejectReason#NONE} if the price is in range and on-tick, otherwise
     *         {@link OrderRejectReason#PRICE_OUT_OF_RANGE} or {@link OrderRejectReason#PRICE_OFF_TICK}
     */
    public int validate(long price) {
        long offset = price - minPrice;
        if (offset < 0) return OrderRejectReason.PRICE_OUT_OF_RANGE;
        if (offset % tickSize != 0) return OrderRejectReason.PRICE_OFF_TICK;
        if (price > maxPrice) return OrderRejectReason.PRICE_OUT_OF_RANGE;
        return OrderRejectReason.NONE;
    }

    /** Adjust the soft upper price bound at runtime (no storage impact). */
    public void setMaxPrice(long maxPrice) {
        this.maxPrice = maxPrice;
    }

    public long getMinPrice() { return minPrice; }
    public long getMaxPrice() { return maxPrice; }
    public long getTickSize() { return tickSize; }
}
