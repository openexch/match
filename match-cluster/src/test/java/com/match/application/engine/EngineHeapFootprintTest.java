package com.match.application.engine;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Retained-heap comparison of the two matching implementations across all markets. This is the
 * primary motivation of the array-backed book — memory ∝ orders, not ∝ price range — measured
 * safely in-process (the live cluster runs -Xms2g -AlwaysPreTouch, so process RSS can't show it).
 *
 * <p>Not a strict assertion of exact bytes (GC timing is approximate); it prints both footprints and
 * sanity-checks that the array engine is dramatically smaller than the direct engine's preallocation.
 */
public class EngineHeapFootprintTest {

    @Test
    public void reportRetainedHeapFootprint() {
        long base = usedAfterGc();
        Engine direct = new Engine("direct");
        long afterDirect = usedAfterGc();
        Engine array = new Engine("array");
        long afterArray = usedAfterGc();

        long directBytes = afterDirect - base;
        long arrayBytes = afterArray - afterDirect;

        System.out.println("==== Engine retained-heap footprint (all 5 markets, bid+ask) ====");
        System.out.println("  direct (preallocated DirectIndexOrderBook): " + mb(directBytes));
        System.out.println("  array  (ArrayOrderBook, cap=131072/side):   " + mb(arrayBytes));
        if (arrayBytes > 0) {
            System.out.printf("  reduction: %.1fx smaller%n", (double) directBytes / arrayBytes);
        }

        // Keep both alive so neither is collected before measurement.
        assertNotNull(direct);
        assertNotNull(array);
        // The array engine must be materially smaller than the direct preallocation.
        // Loose margin (measured ~3x) so GC-timing noise across JVMs can't flake the gate.
        assertTrue("array engine should be at least 2x smaller than direct preallocation",
                arrayBytes * 2 < directBytes);
    }

    private static long usedAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try { Thread.sleep(40); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String mb(long bytes) {
        return String.format("%,d bytes (%.1f MB)", bytes, bytes / (1024.0 * 1024.0));
    }
}
