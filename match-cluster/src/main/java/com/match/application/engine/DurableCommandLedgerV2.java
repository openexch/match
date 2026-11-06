// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableIntentView;
import com.match.domain.commands.DurableOrderIntent;
import com.match.domain.commands.DurableOutcomeView;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2LongHashMap;

import java.util.Arrays;

/**
 * RC2 S4 (contract sections 2, 4.2, 7.2): the behaviour-2 durable command ledger. Bounded by the
 * recorded {@code commandLedgerCapacity} C and emptied only by committed {@code ReleaseCommandIds}
 * entries: {@code UP_TO} floors and {@code ORDER} tombstones. Never evicts, never times out, never
 * shrinks under capacity pressure (retention principles 2 and 4).
 *
 * <p>Replicated state (snapshotted, section 7.2): the inflight outcomes keyed by
 * {@code (idHigh, idLow)}, the per-idHigh {@code UP_TO} floors and the sorted, disjoint, merged
 * tombstone ranges. Node-local and derived (never serialized): the slab layout, the 128-bit open
 * addressing index (linear probing, backward-shift deletion, so churn never accumulates tombstone
 * slots), the per-idHigh entry chain, and the gauges.</p>
 *
 * <p>Allocation: none on the per-command or per-release path after warm-up. Growth of the slab and
 * index (doubling from 1024 up to C), of the floor map and of the range arrays allocates; the heap
 * therefore follows the peak inflight, not C (section 6.1). Snapshot write and read allocate
 * (sorting), which is outside the guarantee (section 7.4).</p>
 *
 * <p>Single-threaded (cluster service thread); the three gauges are volatile for the scraper.</p>
 */
public final class DurableCommandLedgerV2 {

    /** Snapshot block marker (S2 reserved 4 for this slice). */
    public static final byte MARKER = 4;
    /** The only block version. */
    public static final int BLOCK_VERSION = 2;
    /** marker 1 + blockVersion 4 + capacity 4 + three counts 12. */
    public static final int HEADER_BYTES = 21;
    /** 12 longs + 9 ints. */
    public static final int ENTRY_BYTES = 12 * 8 + 9 * 4;
    /** Floor and range pairs: two longs. */
    public static final int PAIR_BYTES = 16;

    /** Release scopes and results (wire values of ReleaseScope / ReleaseResult). */
    public static final int SCOPE_UP_TO = 0, SCOPE_ORDER = 1;
    public static final int RELEASE_APPLIED = 0, RELEASE_NOOP = 1, RELEASE_LEG_LIVE = 2;

    static final int INITIAL_SLAB = 1024;
    private static final int LONGS = 12;
    private static final int INTS = 9;
    private static final int L_ID_HIGH = 0, L_ID_LOW = 1, L_USER = 2, L_OMS = 3, L_OLD = 4, L_PRICE = 5,
            L_QTY = 6, L_BUDGET = 7, L_EOR = 8, L_APPLIED = 9, L_TS = 10, L_ORDER = 11;
    private static final int I_MARKET = 0, I_KIND = 1, I_TYPE = 2, I_SIDE = 3, I_TIF = 4, I_STATUS = 5,
            I_REASON = 6, I_OLD_CANCELLED = 7, I_RESULT = 8;
    private static final int NIL = -1;

    private final int capacity;

    // ---- slab (derived layout) ----
    private int slabCapacity;
    private long[] longs;
    private int[] ints;
    private int[] chainNext;   // per-idHigh chain while live; free list while free
    private int freeHead = NIL;
    private int highWater;
    private int size;

    // ---- 128-bit index: open addressing, linear probing, backward-shift deletion ----
    private long[] keyHigh;
    private long[] keyLow;
    private int[] keySlot;     // slot + 1; 0 = empty
    private int mask;

    // ---- per-idHigh chain heads (derived) ----
    private final Long2LongHashMap chainHead = new Long2LongHashMap(NIL);

    // ---- replicated: floors and tombstone ranges ----
    private final Long2LongHashMap floors = new Long2LongHashMap(-1L);
    private long[] rangeLo = new long[16];
    private long[] rangeHi = new long[16];
    private int rangeCount;

    // ---- gauges (scraper) ----
    private volatile int gaugeSize;
    private volatile int gaugeFloors;
    private volatile int gaugeRanges;

    public DurableCommandLedgerV2(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("commandLedgerCapacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
        allocateSlab(Math.min(INITIAL_SLAB, capacity));
    }

    private void allocateSlab(final int n) {
        slabCapacity = n;
        longs = new long[n * LONGS];
        ints = new int[n * INTS];
        chainNext = new int[n];
        // Power of two >= 2n (load factor <= 0.5).
        final int table = (int) Math.min(1L << 30, Long.highestOneBit(Math.max(4L, n) * 2 - 1) << 1);
        keyHigh = new long[table];
        keyLow = new long[table];
        keySlot = new int[table];
        mask = table - 1;
    }

    /** The recorded capacity C. */
    public int capacity() {
        return capacity;
    }

    /** Inflight entry count (record 3/4 {@code ledgerSize}). */
    public int size() {
        return size;
    }

    /** Tombstone range count (record 4 {@code tombstoneRanges}). */
    public int rangeCount() {
        return rangeCount;
    }

    /** Floor count. */
    public int floorCount() {
        return floors.size();
    }

    /** Scraper reads (volatile). */
    public int gaugeSize() { return gaugeSize; }
    public int gaugeFloors() { return gaugeFloors; }
    public int gaugeRanges() { return gaugeRanges; }

    private void publishGauges() {
        gaugeSize = size;
        gaugeFloors = floors.size();
        gaugeRanges = rangeCount;
    }

    // ==================== lookups ====================

    private static int hash(final long h, final long l) {
        long x = h * 0x9E3779B97F4A7C15L ^ l;
        x ^= x >>> 33;
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        return (int) x;
    }

    /** The slot of {@code (h, l)}, or -1. */
    public int find(final long h, final long l) {
        int i = hash(h, l) & mask;
        while (true) {
            final int s = keySlot[i];
            if (s == 0) {
                return NIL;
            }
            if (keyHigh[i] == h && keyLow[i] == l) {
                return s - 1;
            }
            i = (i + 1) & mask;
        }
    }

    /** True iff {@code h} lies in a tombstone range. O(1) above the top range, else O(log R). */
    public boolean tombstoned(final long h) {
        final int n = rangeCount;
        if (n == 0 || h > rangeHi[n - 1] || h < rangeLo[0]) {
            return false;
        }
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (rangeHi[mid] < h) {
                lo = mid + 1;
            } else if (rangeLo[mid] > h) {
                hi = mid - 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** The {@code UP_TO} floor of {@code h}, or -1 when none (floors are {@code >= 0}). */
    public long floor(final long h) {
        return floors.get(h);
    }

    /** B2: {@code h} tombstoned, or a floor exists and {@code l <= floor[h]}. */
    public boolean covered(final long h, final long l) {
        if (tombstoned(h)) {
            return true;
        }
        final long f = floors.get(h);
        return f >= 0 && l <= f;
    }

    /** B3: all 14 intent fields of slot {@code s} equal {@code c}'s. */
    public boolean sameIntent(final int s, final DurableIntentView c) {
        final int lb = s * LONGS, ib = s * INTS;
        return longs[lb + L_ID_HIGH] == c.idHigh() && longs[lb + L_ID_LOW] == c.idLow()
                && longs[lb + L_USER] == c.userId() && longs[lb + L_OMS] == c.omsOrderId()
                && longs[lb + L_OLD] == c.oldOrderId() && longs[lb + L_PRICE] == c.price()
                && longs[lb + L_QTY] == c.quantity() && longs[lb + L_BUDGET] == c.budget()
                && longs[lb + L_EOR] == c.expectedOldRemaining()
                && ints[ib + I_MARKET] == c.marketId() && ints[ib + I_KIND] == c.kind()
                && ints[ib + I_TYPE] == c.type() && ints[ib + I_SIDE] == c.side()
                && ints[ib + I_TIF] == c.timeInForce();
    }

    /** The recorded result code of slot {@code s}. */
    public int result(final int s) {
        return ints[s * INTS + I_RESULT];
    }

    /** A record copy of slot {@code s} (allocates; tests and the behaviour-1-shaped entry only). */
    public DurableCommandOutcome outcomeAt(final int s) {
        final int lb = s * LONGS, ib = s * INTS;
        return new DurableCommandOutcome(new DurableOrderIntent(longs[lb + L_ID_HIGH], longs[lb + L_ID_LOW],
                longs[lb + L_USER], longs[lb + L_OMS], longs[lb + L_OLD], longs[lb + L_PRICE], longs[lb + L_QTY],
                longs[lb + L_BUDGET], ints[ib + I_MARKET], ints[ib + I_KIND], ints[ib + I_TYPE], ints[ib + I_SIDE],
                ints[ib + I_TIF], longs[lb + L_EOR]), longs[lb + L_APPLIED], longs[lb + L_TS], longs[lb + L_ORDER],
                ints[ib + I_STATUS], ints[ib + I_REASON], ints[ib + I_OLD_CANCELLED] == 1, ints[ib + I_RESULT]);
    }

    /** The recorded outcome of {@code (h, l)}, or null (allocates; tests). */
    public DurableCommandOutcome get(final long h, final long l) {
        final int s = find(h, l);
        return s < 0 ? null : outcomeAt(s);
    }

    // ==================== record (B5-B7) ====================

    /**
     * Record a canonical outcome (results 0, 1, 4, 5, 6). The caller checked B2-B4: the key is new,
     * not covered, and {@code size < C}. An entry is never replaced (I5).
     */
    public void record(final DurableOutcomeView o) {
        final int r = o.result();
        if (r != 0 && r != 1 && r != 4 && r != 5 && r != 6) {
            throw new IllegalArgumentException("ledger v2 records results 0, 1, 4, 5, 6 only, got " + r);
        }
        if (size >= capacity) {
            throw new IllegalStateException("ledger v2 full: size " + size + " >= capacity " + capacity);
        }
        if (find(o.idHigh(), o.idLow()) >= 0) {
            throw new IllegalStateException("ledger v2 entry (" + o.idHigh() + ", " + o.idLow() + ") already recorded");
        }
        if (covered(o.idHigh(), o.idLow())) {
            throw new IllegalStateException("ledger v2 entry (" + o.idHigh() + ", " + o.idLow() + ") is released");
        }
        final int s = allocateSlot();
        final int lb = s * LONGS, ib = s * INTS;
        longs[lb + L_ID_HIGH] = o.idHigh();
        longs[lb + L_ID_LOW] = o.idLow();
        longs[lb + L_USER] = o.userId();
        longs[lb + L_OMS] = o.omsOrderId();
        longs[lb + L_OLD] = o.oldOrderId();
        longs[lb + L_PRICE] = o.price();
        longs[lb + L_QTY] = o.quantity();
        longs[lb + L_BUDGET] = o.budget();
        longs[lb + L_EOR] = o.expectedOldRemaining();
        longs[lb + L_APPLIED] = o.appliedPosition();
        longs[lb + L_TS] = o.timestamp();
        longs[lb + L_ORDER] = o.orderId();
        ints[ib + I_MARKET] = o.marketId();
        ints[ib + I_KIND] = o.kind();
        ints[ib + I_TYPE] = o.type();
        ints[ib + I_SIDE] = o.side();
        ints[ib + I_TIF] = o.timeInForce();
        ints[ib + I_STATUS] = o.status();
        ints[ib + I_REASON] = o.reason();
        ints[ib + I_OLD_CANCELLED] = o.oldCancelled() ? 1 : 0;
        ints[ib + I_RESULT] = r;
        link(s);
        publishGauges();
    }

    private int allocateSlot() {
        if (freeHead != NIL) {
            final int s = freeHead;
            freeHead = chainNext[s];
            return s;
        }
        if (highWater == slabCapacity) {
            grow();
        }
        return highWater++;
    }

    /** Double the slab (up to C) and rebuild the index (warm-up allocation). */
    private void grow() {
        final int n = (int) Math.min((long) capacity, (long) slabCapacity * 2);
        if (n <= slabCapacity) {
            throw new IllegalStateException("ledger v2 slab cannot grow past capacity " + capacity);
        }
        final long[] oldLongs = longs;
        final int[] oldInts = ints;
        final int[] oldNext = chainNext;
        final int oldHigh = highWater;
        final long[] oldKeyHigh = keyHigh;
        final long[] oldKeyLow = keyLow;
        final int[] oldKeySlot = keySlot;
        allocateSlab(n);
        System.arraycopy(oldLongs, 0, longs, 0, oldHigh * LONGS);
        System.arraycopy(oldInts, 0, ints, 0, oldHigh * INTS);
        System.arraycopy(oldNext, 0, chainNext, 0, oldHigh);
        for (int i = 0; i < oldKeySlot.length; i++) {
            if (oldKeySlot[i] != 0) {
                indexInsert(oldKeyHigh[i], oldKeyLow[i], oldKeySlot[i] - 1);
            }
        }
    }

    private void link(final int s) {
        final long h = longs[s * LONGS + L_ID_HIGH];
        indexInsert(h, longs[s * LONGS + L_ID_LOW], s);
        chainNext[s] = (int) chainHead.get(h);
        chainHead.put(h, s);
        size++;
    }

    private void indexInsert(final long h, final long l, final int s) {
        int i = hash(h, l) & mask;
        while (keySlot[i] != 0) {
            i = (i + 1) & mask;
        }
        keyHigh[i] = h;
        keyLow[i] = l;
        keySlot[i] = s + 1;
    }

    private void indexDelete(final long h, final long l) {
        int i = hash(h, l) & mask;
        while (true) {
            if (keySlot[i] == 0) {
                throw new IllegalStateException("ledger v2 index lost (" + h + ", " + l + ")");
            }
            if (keyHigh[i] == h && keyLow[i] == l) {
                break;
            }
            i = (i + 1) & mask;
        }
        // Backward-shift deletion: pull every later member of the probe run whose home is not in (i, j].
        keySlot[i] = 0;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (keySlot[j] == 0) {
                return;
            }
            final int home = hash(keyHigh[j], keyLow[j]) & mask;
            final boolean stays = i <= j ? (i < home && home <= j) : (i < home || home <= j);
            if (stays) {
                continue;
            }
            keyHigh[i] = keyHigh[j];
            keyLow[i] = keyLow[j];
            keySlot[i] = keySlot[j];
            keySlot[j] = 0;
            i = j;
        }
    }

    private void free(final int s) {
        indexDelete(longs[s * LONGS + L_ID_HIGH], longs[s * LONGS + L_ID_LOW]);
        chainNext[s] = freeHead;
        freeHead = s;
        size--;
    }

    // ==================== release (4.2) ====================

    /**
     * {@code UP_TO(h, f)}: NOOP (returns -1) when h is tombstoned or {@code f <= floor[h]}; else
     * removes every inflight {@code (h, l <= f)}, sets {@code floor[h] = f} and returns the removed
     * count (APPLIED, possibly 0). Walks the chain of h only.
     */
    public int releaseUpTo(final long h, final long f) {
        if (tombstoned(h)) {
            return -1;
        }
        final long current = floors.get(h);
        if (current >= 0 && f <= current) {
            return -1;
        }
        int removed = 0;
        int prev = NIL;
        int s = (int) chainHead.get(h);
        while (s != NIL) {
            final int next = chainNext[s];
            if (longs[s * LONGS + L_ID_LOW] <= f) {
                if (prev == NIL) {
                    if (next == NIL) {
                        chainHead.remove(h);
                    } else {
                        chainHead.put(h, next);
                    }
                } else {
                    chainNext[prev] = next;
                }
                free(s);
                removed++;
            } else {
                prev = s;
            }
            s = next;
        }
        floors.put(h, f);
        publishGauges();
        return removed;
    }

    /**
     * {@code ORDER(h)} once the caller checked h is not tombstoned and no leg names it: removes every
     * inflight entry of h, drops its floor, tombstones h (merging). Returns the removed count.
     */
    public int releaseOrder(final long h) {
        if (tombstoned(h)) {
            throw new IllegalStateException("ORDER(" + h + ") on a tombstoned idHigh: the caller decides NOOP");
        }
        int removed = 0;
        int s = (int) chainHead.get(h);
        if (s != NIL) {
            chainHead.remove(h);
        }
        while (s != NIL) {
            final int next = chainNext[s];
            free(s);
            removed++;
            s = next;
        }
        floors.remove(h);
        tombstone(h);
        publishGauges();
        return removed;
    }

    /**
     * TEST SEAM ONLY (contract 9, {@code releasedCreateDuplicateIsRefusedNotReapplied} negative
     * control): drop every inflight entry of {@code h} WITHOUT a floor or a tombstone, i.e. the
     * broken release the tombstone exists to prevent. Package-private; never called by main code.
     */
    int forgetWithoutTombstoneForTest(final long h) {
        int removed = 0;
        int s = (int) chainHead.get(h);
        if (s != NIL) {
            chainHead.remove(h);
        }
        while (s != NIL) {
            final int next = chainNext[s];
            free(s);
            removed++;
            s = next;
        }
        publishGauges();
        return removed;
    }

    /** Insert {@code h} (not tombstoned) into the ranges, merging with neighbours. */
    private void tombstone(final long h) {
        final int n = rangeCount;
        // Insertion point: the first range whose lo > h. O(1) for h above the top range.
        int at;
        if (n == 0 || h > rangeHi[n - 1]) {
            at = n;
        } else {
            int lo = 0, hi = n - 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (rangeLo[mid] < h) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            at = lo;
        }
        final boolean mergePrev = at > 0 && rangeHi[at - 1] == h - 1;
        final boolean mergeNext = at < n && h != Long.MAX_VALUE && rangeLo[at] == h + 1;
        if (mergePrev && mergeNext) {
            rangeHi[at - 1] = rangeHi[at];
            System.arraycopy(rangeLo, at + 1, rangeLo, at, n - at - 1);
            System.arraycopy(rangeHi, at + 1, rangeHi, at, n - at - 1);
            rangeCount = n - 1;
        } else if (mergePrev) {
            rangeHi[at - 1] = h;
        } else if (mergeNext) {
            rangeLo[at] = h;
        } else {
            if (n == rangeLo.length) {
                rangeLo = Arrays.copyOf(rangeLo, n * 2);
                rangeHi = Arrays.copyOf(rangeHi, n * 2);
            }
            System.arraycopy(rangeLo, at, rangeLo, at + 1, n - at);
            System.arraycopy(rangeHi, at, rangeHi, at + 1, n - at);
            rangeLo[at] = h;
            rangeHi[at] = h;
            rangeCount = n + 1;
        }
    }

    /** The ranges as {@code [lo0, hi0, lo1, hi1, ...]} (allocates; tests and diagnostics). */
    public long[] ranges() {
        final long[] out = new long[rangeCount * 2];
        for (int i = 0; i < rangeCount; i++) {
            out[2 * i] = rangeLo[i];
            out[2 * i + 1] = rangeHi[i];
        }
        return out;
    }

    // ==================== snapshot (7.2, 7.3) ====================

    private int[] sortedSlots() {
        final Integer[] slots = new Integer[size];
        int k = 0;
        for (int i = 0; i < keySlot.length; i++) {
            if (keySlot[i] != 0) {
                slots[k++] = keySlot[i] - 1;
            }
        }
        Arrays.sort(slots, (a, b) -> {
            final int c = Long.compare(longs[a * LONGS + L_ID_HIGH], longs[b * LONGS + L_ID_HIGH]);
            return c != 0 ? c : Long.compare(longs[a * LONGS + L_ID_LOW], longs[b * LONGS + L_ID_LOW]);
        });
        final int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = slots[i];
        }
        return out;
    }

    private long[] sortedFloorKeys() {
        final long[] keys = new long[floors.size()];
        int k = 0;
        final Long2LongHashMap.KeyIterator it = floors.keySet().iterator();
        while (it.hasNext()) {
            keys[k++] = it.nextValue();
        }
        Arrays.sort(keys);
        return keys;
    }

    /** Bytes of the block {@link #write} produces. */
    public long encodedLength() {
        return HEADER_BYTES + (long) ENTRY_BYTES * size + (long) PAIR_BYTES * floors.size()
                + (long) PAIR_BYTES * rangeCount;
    }

    /** Write the marker-4 block at {@code p} (native byte order, as the codec); returns the end. */
    public int write(final MutableDirectBuffer b, int p) {
        b.putByte(p, MARKER);
        p += 1;
        b.putInt(p, BLOCK_VERSION);
        p += 4;
        b.putInt(p, capacity);
        p += 4;
        b.putInt(p, size);
        p += 4;
        b.putInt(p, floors.size());
        p += 4;
        b.putInt(p, rangeCount);
        p += 4;
        for (final int s : sortedSlots()) {
            final int lb = s * LONGS, ib = s * INTS;
            for (int j = 0; j < LONGS; j++) {
                b.putLong(p, longs[lb + j]);
                p += 8;
            }
            for (int j = 0; j < INTS; j++) {
                b.putInt(p, ints[ib + j]);
                p += 4;
            }
        }
        for (final long h : sortedFloorKeys()) {
            b.putLong(p, h);
            p += 8;
            b.putLong(p, floors.get(h));
            p += 8;
        }
        for (int i = 0; i < rangeCount; i++) {
            b.putLong(p, rangeLo[i]);
            p += 8;
            b.putLong(p, rangeHi[i]);
            p += 8;
        }
        return p;
    }

    private static IllegalStateException corrupt(final String rule, final String detail) {
        return new IllegalStateException("[SNAPSHOT] ledger v2 " + rule + ": " + detail + " (corrupt snapshot)");
    }

    /**
     * Read and validate a marker-4 block (rules R1-R10 of contract 7.3) that must end exactly at
     * {@code end}. Returns the restored ledger; throws {@link IllegalStateException} naming the rule.
     */
    public static DurableCommandLedgerV2 read(final DirectBuffer b, int p, final int end, final long recordedCapacity) {
        if (end - p < 1 || b.getByte(p) != MARKER) {
            throw corrupt("R1", "marker 4 must follow the identity block, found "
                    + (end - p < 1 ? "the payload end" : "byte " + b.getByte(p)));
        }
        if (end - p < HEADER_BYTES) {
            throw corrupt("R5", "truncated header: " + (end - p) + " of " + HEADER_BYTES + " bytes");
        }
        p += 1;
        final int blockVersion = b.getInt(p);
        p += 4;
        if (blockVersion != BLOCK_VERSION) {
            throw corrupt("R2", "blockVersion " + blockVersion + " != " + BLOCK_VERSION);
        }
        final int capacity = b.getInt(p);
        p += 4;
        if (capacity != recordedCapacity) {
            throw corrupt("R3", "capacity " + capacity + " != recorded commandLedgerCapacity " + recordedCapacity);
        }
        final int nInflight = b.getInt(p);
        p += 4;
        final int nFloor = b.getInt(p);
        p += 4;
        final int nRanges = b.getInt(p);
        p += 4;
        if (nInflight < 0 || nFloor < 0 || nRanges < 0) {
            throw corrupt("R4", "negative count (" + nInflight + ", " + nFloor + ", " + nRanges + ")");
        }
        if (nInflight > capacity) {
            throw corrupt("R4", "nInflight " + nInflight + " > capacity " + capacity);
        }
        final long expected = (long) ENTRY_BYTES * nInflight + (long) PAIR_BYTES * nFloor + (long) PAIR_BYTES * nRanges;
        if (expected != (long) end - p) {
            throw corrupt("R5", "block body is " + ((long) end - p) + " bytes, the counts need " + expected);
        }
        final DurableCommandLedgerV2 ledger = new DurableCommandLedgerV2(capacity);
        final long[] l = new long[LONGS];
        final int[] z = new int[INTS];
        long prevHigh = 0, prevLow = 0;
        final long[] entryHigh = new long[nInflight];
        final long[] entryLow = new long[nInflight];
        for (int i = 0; i < nInflight; i++) {
            for (int j = 0; j < LONGS; j++) {
                l[j] = b.getLong(p);
                p += 8;
            }
            for (int j = 0; j < INTS; j++) {
                z[j] = b.getInt(p);
                p += 4;
            }
            if (i > 0) {
                final int c = l[L_ID_HIGH] != prevHigh ? Long.compare(l[L_ID_HIGH], prevHigh)
                        : Long.compare(l[L_ID_LOW], prevLow);
                if (c <= 0) {
                    throw corrupt("R6", "inflight not strictly ascending at entry " + i);
                }
            }
            prevHigh = l[L_ID_HIGH];
            prevLow = l[L_ID_LOW];
            final String bad = invalidEntry(l, z);
            if (bad != null) {
                throw corrupt("R7", "entry " + i + " (" + l[L_ID_HIGH] + ", " + l[L_ID_LOW] + "): " + bad);
            }
            entryHigh[i] = l[L_ID_HIGH];
            entryLow[i] = l[L_ID_LOW];
            ledger.recordRaw(l, z);
        }
        long prevFloor = 0;
        for (int i = 0; i < nFloor; i++) {
            final long h = b.getLong(p);
            p += 8;
            final long f = b.getLong(p);
            p += 8;
            if (h < 1 || f < 0) {
                throw corrupt("R9", "floor " + i + " (" + h + ", " + f + ") out of range");
            }
            if (i > 0 && h <= prevFloor) {
                throw corrupt("R9", "floors not strictly ascending at " + i);
            }
            prevFloor = h;
            ledger.floors.put(h, f);
        }
        long prevHi = 0;
        for (int i = 0; i < nRanges; i++) {
            final long lo = b.getLong(p);
            p += 8;
            final long hi = b.getLong(p);
            p += 8;
            if (lo < 1 || lo > hi) {
                throw corrupt("R10", "range " + i + " [" + lo + ", " + hi + "] out of order");
            }
            if (i > 0 && !(prevHi != Long.MAX_VALUE && prevHi + 1 < lo)) {
                throw corrupt("R10", "range " + i + " [" + lo + ", " + hi + "] overlaps or touches the previous one");
            }
            prevHi = hi;
            if (ledger.rangeCount == ledger.rangeLo.length) {
                ledger.rangeLo = Arrays.copyOf(ledger.rangeLo, ledger.rangeCount * 2);
                ledger.rangeHi = Arrays.copyOf(ledger.rangeHi, ledger.rangeCount * 2);
            }
            ledger.rangeLo[ledger.rangeCount] = lo;
            ledger.rangeHi[ledger.rangeCount] = hi;
            ledger.rangeCount++;
        }
        final Long2LongHashMap.KeyIterator fit = ledger.floors.keySet().iterator();
        while (fit.hasNext()) {
            final long h = fit.nextValue();
            if (ledger.tombstoned(h)) {
                throw corrupt("R9", "floor on tombstoned idHigh " + h);
            }
        }
        for (int i = 0; i < nInflight; i++) {
            if (ledger.covered(entryHigh[i], entryLow[i])) {
                throw corrupt("R8", "entry (" + entryHigh[i] + ", " + entryLow[i] + ") is covered by "
                        + (ledger.tombstoned(entryHigh[i]) ? "a tombstone" : "its floor"));
            }
        }
        ledger.publishGauges();
        return ledger;
    }

    /** R7: the intent rules plus B1, and the writer's outcome shapes. null when valid. */
    private static String invalidEntry(final long[] l, final int[] z) {
        if (!DurableOrderIntent.isValid(l[L_ID_HIGH], l[L_ID_LOW], l[L_USER], l[L_OMS], l[L_OLD], z[I_MARKET],
                z[I_KIND], z[I_TYPE], z[I_SIDE], z[I_TIF], l[L_EOR])) {
            return "intent rules";
        }
        if (DurableOrderIntent.behaviourTwoCause(l[L_ID_HIGH], l[L_ID_LOW], l[L_OMS], z[I_KIND]) != DurableOrderIntent.B2_OK) {
            return "behaviour-2 identity (B1)";
        }
        final int result = z[I_RESULT];
        final int status = z[I_STATUS];
        final long orderId = l[L_ORDER];
        if (result != 0 && result != 1 && result != 4 && result != 5 && result != 6) {
            return "result " + result;
        }
        if (status < -1 || status > 4) {
            return "status " + status;
        }
        if (z[I_REASON] < 0) {
            return "reason " + z[I_REASON];
        }
        if (z[I_OLD_CANCELLED] < 0 || z[I_OLD_CANCELLED] > 1) {
            return "oldCancelled " + z[I_OLD_CANCELLED];
        }
        if (z[I_OLD_CANCELLED] == 1 && z[I_KIND] != 2) {
            return "oldCancelled on kind " + z[I_KIND];
        }
        if (l[L_APPLIED] < 0 || l[L_TS] < 0 || orderId < 0) {
            return "negative appliedPosition/timestamp/orderId";
        }
        if (result == 0 && !(orderId > 0 && status >= 0 && status <= 3)) {
            return "APPLIED needs orderId > 0 and status 0..3";
        }
        if ((result == 1) != (status == 4)) {
            return "REJECTED iff status 4";
        }
        if (result == 1 && orderId <= 0) {
            return "REJECTED needs orderId > 0";
        }
        if (result >= 4 && !(orderId == 0 && status == -1)) {
            return "rejection shape needs orderId 0 and status -1";
        }
        return null;
    }

    private void recordRaw(final long[] l, final int[] z) {
        if (size >= capacity) {
            throw corrupt("R4", "more entries than capacity");
        }
        final int s = allocateSlot();
        System.arraycopy(l, 0, longs, s * LONGS, LONGS);
        System.arraycopy(z, 0, ints, s * INTS, INTS);
        link(s);
    }
}
