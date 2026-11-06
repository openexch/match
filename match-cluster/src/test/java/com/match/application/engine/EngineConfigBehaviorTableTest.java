// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.infrastructure.mejournal.generated.ConfigDecision;
import org.junit.Test;

import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.G2;
import static com.match.application.engine.BehaviourTwoHarness.config;
import static com.match.application.engine.BehaviourTwoHarness.fresh2;
import static com.match.application.engine.EngineConfigStateMachine.Decision.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 contract section 3: the EngineConfig decision table at behaviour 1 and 2 (the critic's
 * EngineConfigBehaviorSwitchTest, renamed: RC2 has no switch row). Every row is driven through
 * the real state machine with a real publisher and schema-4 journal, so "record 5" means a frame
 * in the ring and "meEpoch" the stamped bytes.
 */
public class EngineConfigBehaviorTableTest {

    private static final int RECORD_5 = com.match.infrastructure.mejournal.generated.EngineConfigDecoder.TEMPLATE_ID;

    private static void createOrder(Engine engine, long userId, long omsOrderId, OrderSide side,
                                    double price, double qty, long ts) {
        final CreateOrderCommand cmd = new CreateOrderCommand();
        cmd.setUserId(userId);
        cmd.setOmsOrderId(omsOrderId);
        cmd.setPrice(FixedPoint.fromDouble(price));
        cmd.setQuantity(FixedPoint.fromDouble(qty));
        cmd.setTotalPrice(0L);
        cmd.setOrderSide(side);
        cmd.setOrderType(OrderType.LIMIT);
        engine.acceptOrder(BehaviourTwoHarness.MARKET, Engine.CMD_CREATE, cmd, ts);
    }

    // ---- row 2 ----

    @Test
    public void freshAtTwoBindsIdentityAndJournalsSeqOne() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(FRESH, h.apply(fresh2(), 4_096L));

            final List<byte[]> frames = h.drain();
            assertEquals("exactly one record 5", 1, frames.size());
            final var r5 = BehaviourTwoHarness.record5(frames.get(0));
            assertEquals(1L, r5.journalSeq());
            assertEquals(G, r5.meEpoch());
            assertEquals("egressSeq = the entry's log position", 4_096L, r5.egressSeq());
            assertEquals(1L, r5.configVersion());
            assertEquals(2L, r5.behaviorVersion());
            assertEquals(50_000L, r5.ledgerCapacity());
            assertEquals(12, r5.maxWireVersion());
            assertEquals(ConfigDecision.FRESH, r5.decision());

            final MeIdentity id = h.engine.meIdentity();
            assertTrue(id.isBound());
            assertEquals(G, id.meEpoch());
            assertEquals("journalSeq 1 consumed", 2L, id.nextJournalSeq());
            final EngineConfigState recorded = h.engine.getEngineConfig();
            assertEquals("the four are recorded as decoded", 2L, recorded.behaviorVersion);
            assertEquals(G, recorded.genesisId);
            assertEquals(12L, recorded.maxWireVersion);
            assertEquals(50_000L, recorded.commandLedgerCapacity);
            assertEquals(1, h.sm.acceptedCount());
            assertEquals(0, h.failFast.get());

            // A following trade and terminal carry meEpoch G.
            h.engine.setCurrentLogPosition(8_192L);
            createOrder(h.engine, 100L, 9001L, OrderSide.BID, 60_000.0, 1.0, 1_000L);
            h.engine.setCurrentLogPosition(8_320L);
            createOrder(h.engine, 200L, 9002L, OrderSide.ASK, 60_000.0, 1.0, 1_001L);
            final List<byte[]> after = h.drain();
            final List<byte[]> trades = BehaviourTwoHarness.ofTemplate(after, 1);
            final List<byte[]> terminals = BehaviourTwoHarness.ofTemplate(after, 2);
            assertEquals(1, trades.size());
            assertEquals("both legs FILLED", 2, terminals.size());
            assertEquals("legacy lane: trade, taker FILLED, maker FILLED order; no outcome record", 3, after.size());
            for (int i = 0; i < after.size(); i++) {
                final byte[] f = after.get(i);
                assertEquals("every record after FRESH carries meEpoch G", G, BehaviourTwoHarness.meEpochOf(f));
                assertEquals("RC2 S3: dense journalSeq after FRESH = 1, in ring order", 2L + i,
                        BehaviourTwoHarness.journalSeqOf(f));
            }
        }
    }

    // ---- row 0 at FRESH (S-b, S-c) ----

    @Test
    public void freshAtTwoStructuralRules() {
        final EngineConfigState[] invalid = {
                config(0, 2, G, 12, 50_000),                         // cv 0
                config(4_294_967_295L, 2, G, 12, 50_000),            // cv uint32 null
                config(1, 2, 0, 12, 50_000),                         // genesisId 0
                config(1, 2, -1, 12, 50_000),                        // genesisId negative
                config(1, 2, G, 11, 50_000),                         // ceiling below v12
                config(1, 2, G, 65_535, 50_000),                     // ceiling uint16 null
                config(1, 2, G, 12, 0),                              // capacity 0
                config(1, 2, G, 12, 1L << 31),                       // capacity above int32
                config(1, 0, G, 12, 50_000),                         // behaviour unset (0)
                config(1, 4_294_967_295L, G, 12, 50_000),            // behaviour uint32 null
        };
        for (EngineConfigState c : invalid) {
            try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
                assertNotNull("structurally invalid: " + c.describe(), c.invalidReason());
                assertEquals(c.describe(), REJECTED_INVALID, h.apply(c, 64L));
                assertFalse("no engines", h.engine.hasEngines());
                assertNull("nothing recorded", h.engine.getEngineConfig());
                assertFalse("no identity", h.engine.meIdentity().isBound());
                assertTrue("nothing journaled", h.drain().isEmpty());
                assertEquals(1, h.sm.rejectCount());
                assertEquals(0, h.sm.acceptedCount());
                assertEquals(0, h.failFast.get());
            }
        }
        // The boundaries themselves are valid.
        assertNull(config(4_294_967_294L, 2, Long.MAX_VALUE, 65_534, Integer.MAX_VALUE).invalidReason());
        assertNull(config(1, 4_294_967_294L, 1, 12, 1).invalidReason());
    }

    // ---- row 1 ----

    @Test
    public void freshAtOneNormalizesAndJournalsNothing() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            assertEquals(FRESH_LEGACY, h.apply(config(1, 1, 99, 12, 5), 64L));
            final EngineConfigState r = h.engine.getEngineConfig();
            assertEquals(1L, r.behaviorVersion);
            assertEquals("genesisId normalized", 0L, r.genesisId);
            assertEquals("ceiling normalized", 0L, r.maxWireVersion);
            assertEquals("capacity normalized", 100_000L, r.commandLedgerCapacity);
            assertTrue(h.engine.hasEngines());
            assertFalse(h.engine.meIdentity().isBound());
            assertTrue("no record 5 at behaviour 1", h.drain().isEmpty());
            assertEquals(1, h.sm.acceptedCount());
            assertEquals(0, h.failFast.get());
        }
    }

    // ---- row 4 ----

    @Test
    public void adoptAtTwoRefusedAndHalts() {
        try (BehaviourTwoHarness h = new BehaviourTwoHarness(new Engine("direct"))) {
            assertEquals(REJECTED_ADOPT_AT_2, h.apply(fresh2(), 64L));
            assertNull("nothing recorded", h.engine.getEngineConfig());
            assertFalse(h.engine.meIdentity().isBound());
            assertEquals("the legacy-mode node halts (check d)", 1, h.failFast.get());
            assertEquals(1, h.sm.rejectCount());
            assertTrue(h.drain().isEmpty());
        }
    }

    // ---- row 0 against a recorded config (NOTE-15) ----

    @Test
    public void invalidFrameAgainstARecordedConfig() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(config(1, 1, 0, 0, 100_000), 64L);
            final EngineConfigState before = h.engine.getEngineConfig();
            assertEquals(REJECTED_INVALID, h.apply(config(1, 0, 0, 0, 100_000), 128L));
            assertSame(before, h.engine.getEngineConfig());
            assertEquals("not a DUPLICATE any more", 0, h.sm.duplicateCount());
            assertEquals(1, h.sm.rejectCount());
            assertTrue(h.drain().isEmpty());
        }
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(fresh2(), 64L);
            h.drain();
            final EngineConfigState before = h.engine.getEngineConfig();
            assertEquals(REJECTED_INVALID, h.apply(config(1, 0, G, 12, 50_000), 128L));
            assertSame(before, h.engine.getEngineConfig());
            assertEquals(2L, h.engine.meIdentity().nextJournalSeq());
            assertEquals(0, h.sm.duplicateCount());
            assertTrue(h.drain().isEmpty());
        }
    }

    // ---- rows 8, 9, 12 ----

    @Test
    public void duplicateAtTwoIncludesTheFour() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(fresh2(), 64L);
            h.drain();
            assertEquals(DUPLICATE, h.apply(fresh2(), 128L));
            assertEquals(1, h.sm.duplicateCount());
            assertEquals("no seq consumed", 2L, h.engine.meIdentity().nextJournalSeq());
            assertTrue("no record 5", h.drain().isEmpty());

            assertEquals("another genesisId at the same cv", STALE, h.apply(config(1, 2, G2, 12, 50_000), 192L));
            assertEquals("another genesisId at a higher cv", REJECTED_GENESIS,
                    h.apply(config(2, 2, G2, 12, 50_000), 256L));
            assertEquals(G, h.engine.getEngineConfig().genesisId);
            assertEquals(G, h.engine.meIdentity().meEpoch());
            assertEquals(1, h.sm.duplicateCount());
            assertTrue(h.drain().isEmpty());
        }
    }

    @Test
    public void staleAtTwo() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(config(5, 2, G, 12, 50_000), 64L);
            h.drain();
            assertEquals(STALE, h.apply(config(4, 2, G, 12, 50_000), 128L));
            final EngineConfigState changed = EngineConfigState.of(5, EngineConfigState.IMPL_ARRAY, 8192, 100, 0,
                    BehaviourTwoHarness.btcMarket(), 2, 50_000, G, 12);
            assertEquals("equal cv with a changed field", STALE, h.apply(changed, 192L));
            assertEquals("equal cv at t 1 is STALE, never DUPLICATE", STALE, h.apply(config(5, 1, 0, 0, 100_000), 256L));
            assertEquals(3, h.sm.staleCount());
            assertEquals(0, h.sm.duplicateCount());
            assertEquals(0, h.sm.rejectCount());
            assertEquals(5L, h.engine.getEngineConfig().configVersion);
            assertTrue(h.drain().isEmpty());
        }
    }

    // ---- rows 13, 14, 15 ----

    @Test
    public void bumpAtTwo() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(fresh2(), 64L);
            h.drain();
            assertEquals(BUMP, h.apply(config(2, 2, G, 12, 50_000), 128L));
            assertEquals(2L, h.engine.getEngineConfig().configVersion);
            assertEquals(1, h.sm.bumpCount());
            assertEquals(2, h.sm.acceptedCount());
            List<byte[]> frames = h.drain();
            assertEquals(1, frames.size());
            var r5 = BehaviourTwoHarness.record5(frames.get(0));
            assertEquals(2L, r5.journalSeq());
            assertEquals(G, r5.meEpoch());
            assertEquals(128L, r5.egressSeq());
            assertEquals(2L, r5.configVersion());
            assertEquals(ConfigDecision.BUMP, r5.decision());

            // Refusals at a higher cv.
            assertEquals(REJECTED_CHANGE, h.apply(config(3, 2, G, 12, 60_000), 192L));
            final EngineConfigState otherMarket = EngineConfigState.of(3, EngineConfigState.IMPL_ARRAY, 4096, 100, 0,
                    new EngineConfigState.MarketDef[]{new EngineConfigState.MarketDef(1, "BTC-USD",
                            FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(150_000.0),
                            FixedPoint.fromDouble(2.0))}, 2, 50_000, G, 12);
            assertEquals(REJECTED_CHANGE, h.apply(otherMarket, 256L));
            assertEquals(2L, h.engine.getEngineConfig().configVersion);
            assertEquals(3L, h.engine.meIdentity().nextJournalSeq());
            assertTrue(h.drain().isEmpty());

            // Raising the ceiling is a BUMP on every replica (this 12-jar then halts, check b).
            assertEquals(BUMP, h.apply(config(3, 2, G, 13, 50_000), 320L));
            assertEquals(13L, h.engine.getEngineConfig().maxWireVersion);
            assertEquals(3L, h.engine.getEngineConfig().configVersion);
            assertEquals("journalSeq 3 consumed on the replica", 4L, h.engine.meIdentity().nextJournalSeq());
            assertEquals(1, h.failFast.get());
            assertTrue("record 5 not written on the halted node", h.drain().isEmpty());

            // The ceiling never lowers.
            assertEquals(REJECTED_CEILING_LOWERED, h.apply(config(4, 2, G, 12, 50_000), 384L));
            assertEquals(13L, h.engine.getEngineConfig().maxWireVersion);
            assertEquals(3L, h.engine.getEngineConfig().configVersion);
            assertEquals(3, h.sm.rejectCount());
        }
    }

    // ---- rows 6, 7 ----

    @Test
    public void switchOneToTwoRefused() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(config(1, 1, 0, 0, 100_000), 64L);
            assertEquals(REJECTED_SWITCH, h.apply(config(2, 2, G, 12, 50_000), 128L));
            assertEquals("same cv, t 2", REJECTED_SWITCH, h.apply(config(1, 2, G, 12, 50_000), 192L));
            assertEquals("never acknowledged as a DUPLICATE", 0, h.sm.duplicateCount());
            assertEquals(1L, h.engine.getEngineConfig().behaviorVersion);
            assertFalse(h.engine.meIdentity().isBound());
            assertTrue(h.drain().isEmpty());
            assertEquals(0, h.failFast.get());
            // Row 7 is unchanged.
            assertEquals(REJECTED_CHANGE, h.apply(config(2, 1, 0, 0, 100_000), 256L));
            assertEquals(3, h.sm.rejectCount());
        }
    }

    // ---- rows 10, 11 ----

    @Test
    public void downgradeAndSkipRefused() {
        try (BehaviourTwoHarness h = BehaviourTwoHarness.configMode()) {
            h.apply(fresh2(), 64L);
            h.drain();
            assertEquals(REJECTED_DOWNGRADE, h.apply(config(2, 1, 0, 0, 100_000), 128L));
            assertEquals(REJECTED_SWITCH, h.apply(config(2, 3, G, 12, 50_000), 192L));
            assertEquals(2L, h.engine.getEngineConfig().behaviorVersion);
            assertEquals(1L, h.engine.getEngineConfig().configVersion);
            assertEquals(0, h.failFast.get());
            assertTrue(h.drain().isEmpty());
        }
    }
}
