package com.match.determinism;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Determinism ∩ durability centerpiece: a snapshot/restore woven into a command stream must be
 * invisible to the output. For each case we run {@code A + B} and {@code A + SNAPSHOT + B} (where
 * {@code SNAPSHOT} serializes the engine and restores it into a brand-new engine — an in-process
 * "restart") and assert the captured event streams are byte-identical. Trade IDs and order IDs
 * must flow continuously across the restart.
 */
public class EngineSnapshotReplayTest {

    @Test
    public void partialFillAcrossRestart() {
        List<String> a = List.of(
                "MARKET BTC_USD", "CLOCK 1000",
                "CREATE u=100 side=ASK type=LIMIT px=60000.0 qty=1.0",
                "CREATE u=200 side=BID type=LIMIT px=60000.0 qty=0.5");
        List<String> b = List.of(
                "CREATE u=201 side=BID type=LIMIT px=60000.0 qty=0.5");
        assertSnapshotTransparent(a, b);
    }

    @Test
    public void multiLevelBookSweptAfterRestart() {
        List<String> a = List.of(
                "MARKET BTC_USD", "CLOCK 5000",
                "CREATE u=100 side=ASK type=LIMIT px=60000.0 qty=1.0",
                "CREATE u=101 side=ASK type=LIMIT px=60500.0 qty=1.0",
                "CREATE u=102 side=ASK type=LIMIT px=61000.0 qty=1.0");
        List<String> b = List.of(
                "CREATE u=200 side=BID type=LIMIT px=61000.0 qty=3.0");
        assertSnapshotTransparent(a, b);
    }

    @Test
    public void cancelOfPreSnapshotOrderAfterRestart() {
        // Order 1 is placed before the snapshot, cancelled after the restart — its cancel must
        // still resolve against the restored book.
        List<String> a = List.of(
                "MARKET BTC_USD", "CLOCK 1000",
                "CREATE u=100 side=BID type=LIMIT px=60000.0 qty=1.0",
                "CREATE u=101 side=BID type=LIMIT px=59000.0 qty=1.0");
        List<String> b = List.of(
                "CANCEL u=100 order=1",
                "CREATE u=200 side=ASK type=LIMIT px=59000.0 qty=1.0");
        assertSnapshotTransparent(a, b);
    }

    @Test
    public void multiMarketAcrossRestart() {
        List<String> a = List.of(
                "MARKET BTC_USD", "CLOCK 1000",
                "CREATE u=100 side=BID type=LIMIT px=60000.0 qty=1.0",
                "MARKET ETH_USD",
                "CREATE u=200 side=ASK type=LIMIT px=3000.0 qty=2.0");
        List<String> b = List.of(
                "CREATE u=201 side=BID type=LIMIT px=3000.0 qty=2.0",
                "MARKET BTC_USD",
                "CREATE u=101 side=ASK type=LIMIT px=60000.0 qty=1.0");
        assertSnapshotTransparent(a, b);
    }

    @Test
    public void doubleSnapshotIsAlsoTransparent() {
        // Two back-to-back restarts must be just as invisible as one.
        List<String> a = List.of(
                "MARKET BTC_USD", "CLOCK 1000",
                "CREATE u=100 side=ASK type=LIMIT px=60000.0 qty=2.0",
                "CREATE u=200 side=BID type=LIMIT px=60000.0 qty=0.5");
        List<String> b = List.of(
                "CREATE u=201 side=BID type=LIMIT px=60000.0 qty=1.5");

        String noSnap = ScenarioRunner.runLines(concat(a, b));
        String withSnaps = ScenarioRunner.runLines(concat(a, Arrays.asList("SNAPSHOT", "SNAPSHOT"), b));
        assertEquals(noSnap, withSnaps);
    }

    @Test
    public void snapshotOnEmptyBookThenTradeIsTransparent() {
        List<String> a = List.of("MARKET BTC_USD", "CLOCK 1000");
        List<String> b = List.of(
                "CREATE u=100 side=ASK type=LIMIT px=60000.0 qty=1.0",
                "CREATE u=200 side=BID type=LIMIT px=60000.0 qty=1.0");
        assertSnapshotTransparent(a, b);
    }

    private static void assertSnapshotTransparent(List<String> a, List<String> b) {
        String noSnap = ScenarioRunner.runLines(concat(a, b));
        String withSnap = ScenarioRunner.runLines(concat(a, List.of("SNAPSHOT"), b));
        assertEquals("snapshot/restore must be invisible to subsequent output", noSnap, withSnap);
    }

    private static List<String> concat(List<String>... parts) {
        List<String> out = new ArrayList<>();
        for (List<String> p : parts) {
            out.addAll(p);
        }
        return out;
    }
}
