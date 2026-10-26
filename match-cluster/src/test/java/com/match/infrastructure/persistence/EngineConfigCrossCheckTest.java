// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.match.application.engine.EngineConfigState;
import com.match.application.engine.EngineConfigStateMachine.Decision;
import com.match.application.engine.MarketConfig;
import com.match.application.orderbook.ArrayMatchingEngine;
import com.match.application.orderbook.DirectIndexOrderBook;
import com.match.application.orderbook.DirectMatchingEngine;
import org.agrona.ExpandableArrayBuffer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.config;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract section 4: the node-local checks (a) supported behaviour, (b) wire ceiling
 * within the jar, (c) legacy-mode creation values, (d) launch mode vs the record. A failure
 * halts the node and never rejects; record 5 is not written by a node that failed the check.
 */
public class EngineConfigCrossCheckTest {

    private static byte[] imageAt(EngineConfigState config) {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(config, 64L);
            final ExpandableArrayBuffer buf = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(h.engine, 0L, buf);
            return Arrays.copyOf(buf.byteArray(), len);
        }
    }

    /** A behaviour-2 config equal to {@code engine}'s legacy-mode effective values (check c passes). */
    private static EngineConfigState nodeMatching(Engine engine) {
        final boolean array = EngineConfigState.IMPL_ARRAY.equals(engine.getImplName());
        final EngineConfigState.MarketDef[] m = new EngineConfigState.MarketDef[MarketConfig.ALL_MARKETS.length];
        for (int i = 0; i < m.length; i++) {
            final MarketConfig c = MarketConfig.ALL_MARKETS[i];
            m[i] = new EngineConfigState.MarketDef(c.marketId, c.symbol, c.basePrice, c.maxPrice, c.tickSize);
        }
        return EngineConfigState.of(1, engine.getImplName(), engine.getEffectiveBookCapacity(),
                array ? ArrayMatchingEngine.MAX_MATCHES_PER_ORDER : DirectMatchingEngine.MAX_MATCHES_PER_ORDER,
                array ? 0 : DirectIndexOrderBook.DEFAULT_MAX_ORDERS_PER_LEVEL, m, 2, 50_000, G, 12);
    }

    /** Run {@code r} with System.err captured; return what was printed. */
    private static String stderrOf(Runnable r) {
        final PrintStream prior = System.err;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(out, true));
        try {
            r.run();
        } finally {
            System.setErr(prior);
        }
        return out.toString();
    }

    @Test
    public void freshAtUnsupportedBehaviourHalts() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(Decision.FRESH, h.apply(config(1, 3, G, 12, 50_000), 64L));
            assertEquals("recorded on the replica", 3L, h.engine.getEngineConfig().behaviorVersion);
            assertTrue("identity bound on the replica", h.engine.meIdentity().isBound());
            assertEquals(G, h.engine.meIdentity().meEpoch());
            assertEquals(1, h.failFast.get());
            assertTrue("record 5 NOT written", h.drain().isEmpty());
        }
    }

    @Test
    public void ceilingAboveJarHalts() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(Decision.FRESH, h.apply(config(1, 2, G, 13, 50_000), 64L));
            assertEquals(1, h.failFast.get());
            assertTrue("record 5 NOT written for the FRESH", h.drain().isEmpty());
        }
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(config(1, 2, G, 12, 50_000), 64L);
            assertEquals(1, h.drain().size());
            assertEquals(Decision.BUMP, h.apply(config(2, 2, G, 13, 50_000), 128L));
            assertEquals(1, h.failFast.get());
            assertTrue("record 5 NOT written for the BUMP", h.drain().isEmpty());
            assertEquals("the replicated BUMP consumed journalSeq 2", 3L, h.engine.meIdentity().nextJournalSeq());
        }
    }

    @Test
    public void restoreOfUnsupportedBehaviourHaltsInBothModes() {
        final byte[] behaviourThree = imageAt(config(1, 3, G, 12, 50_000));
        final byte[] ceilingThirteen = imageAt(config(1, 2, G, 13, 50_000));
        for (boolean configMode : new boolean[]{true, false}) {
            for (byte[] img : new byte[][]{behaviourThree, ceilingThirteen}) {
                final S2Services.Service svc = new S2Services.Service(configMode);
                svc.restore(img);
                assertEquals("configMode=" + configMode, 1, svc.failFast.get());
            }
        }

        // A behaviour-2 image with supported values: a config-mode node serves it...
        final S2Services.Service legacy = new S2Services.Service(false);
        final byte[] supported = imageAt(nodeMatching(legacy.engine()));
        final S2Services.Service configNode = new S2Services.Service(true);
        configNode.restore(supported);
        assertEquals("config mode serves a supported behaviour-2 image", 0, configNode.failFast.get());
        // ...a legacy-mode node halts on check (d) alone.
        final String err = stderrOf(() -> legacy.restore(supported));
        assertEquals(1, legacy.failFast.get());
        assertTrue(err, err.contains("(d)"));
        assertFalse("creation values match, so (c) passes: " + err, err.contains("(c)"));
    }

    @Test
    public void severalFailingChecksHaltOnce() {
        final byte[] img = imageAt(config(1, 3, G, 13, 50_000)); // (a), (b); BTC-only market table: (c); legacy: (d)
        final S2Services.Service legacy = new S2Services.Service(false);
        final String err = stderrOf(() -> legacy.restore(img));
        assertEquals("fail-fast at most once per entry point", 1, legacy.failFast.get());
        for (String check : new String[]{"(a)", "(b)", "(c)", "(d)"}) {
            assertTrue(check + " logged: " + err, err.contains("CRITICAL (snapshot-restore): " + check));
        }
    }
}
