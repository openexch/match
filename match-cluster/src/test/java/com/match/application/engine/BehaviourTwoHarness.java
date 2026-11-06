// SPDX-License-Identifier: Apache-2.0
package com.match.application.engine;

import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.MatchEventPublisher;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.infrastructure.journal.SettlementJournal;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RC2 S2 test harness: one replica's engine wired the way the cluster wires it for behaviour 2 —
 * a real {@link MatchEventPublisher} with a schema-4 {@link SettlementJournal} bound to the
 * engine's {@link MeIdentity}, and an {@link EngineConfigStateMachine} whose record-5 sink is
 * the publisher and whose fail-fast hook records instead of halting. Drain the ring to read the
 * journal frames exactly as the writer agent would publish them.
 */
public final class BehaviourTwoHarness implements AutoCloseable {

    /** A positive genesis nonce (sign bit clear, S11 generator rule). */
    public static final long G = 0x1234_5678_9ABC_DEF1L;
    public static final long G2 = 0x0FED_CBA9_8765_4321L;
    public static final int MARKET = 1;

    public final Engine engine;
    public final MatchEventPublisher publisher = new MatchEventPublisher();
    public final SettlementJournal journal = new SettlementJournal(1 << 20);
    public final AtomicInteger failFast = new AtomicInteger();
    public final EngineConfigStateMachine sm;
    public final List<EngineConfigStateMachine.Decision> decisions = new ArrayList<>();

    /**
     * RC2 S3: the replica shapes a member can have (contract section 4: they must allocate the
     * same tradeIds and journalSeqs at the same log position).
     */
    public enum Shape {
        /** Publisher with an egress ring for every market and the journal armed (production). */
        JOURNALED,
        /** Publisher with rings, journal off (SETTLEMENT_JOURNAL_ENABLED unset). */
        DARK,
        /** Publisher with the journal armed and no egress ring (no initMarket). */
        RINGLESS,
        /** No sink attached to the engine at all (the RCS replica shape); record 5 unjournaled. */
        SINKLESS
    }

    public final Shape shape;

    public BehaviourTwoHarness(Engine engine) {
        this(engine, Shape.JOURNALED);
    }

    public BehaviourTwoHarness(Engine engine, Shape shape) {
        this.engine = engine;
        this.shape = shape;
        journal.bindIdentity(engine.meIdentity());
        if (shape == Shape.JOURNALED || shape == Shape.RINGLESS) {
            publisher.setSettlementJournal(journal);
        }
        if (shape == Shape.JOURNALED || shape == Shape.DARK) {
            for (int m = 1; m <= 5; m++) {
                final int marketId = m;
                publisher.initMarket(marketId, new MarketEventHandler() {
                    public int getMarketId() { return marketId; }
                    public void onEvent(PublishEvent e, long seq, boolean end) { }
                });
            }
        }
        publisher.start();
        if (shape != Shape.SINKLESS) {
            engine.setEventPublisher(publisher);
        }
        sm = shape == Shape.SINKLESS
                ? new EngineConfigStateMachine(engine, failFast::incrementAndGet, null)
                : new EngineConfigStateMachine(engine, failFast::incrementAndGet, null,
                        publisher::publishEngineConfig);
    }

    /** A config-mode replica. */
    public static BehaviourTwoHarness configMode() {
        return new BehaviourTwoHarness(Engine.deferredUntilConfig());
    }

    /** Apply one EngineConfig at {@code logPosition} (the entry's Header.position()). */
    public EngineConfigStateMachine.Decision apply(EngineConfigState config, long logPosition) {
        engine.setCurrentLogPosition(logPosition);
        final EngineConfigStateMachine.Decision d = sm.onEngineConfig(config);
        decisions.add(d);
        return d;
    }

    /** Every frame in the ring, in append order, consumed. */
    public List<byte[]> drain() {
        final List<byte[]> out = new ArrayList<>();
        journal.ringBuffer().read((t, b, i, n) -> {
            final byte[] v = new byte[n];
            b.getBytes(i, v);
            out.add(v);
        }, Integer.MAX_VALUE);
        return out;
    }

    @Override
    public void close() {
        publisher.shutdown();
        engine.close();
    }

    // ---- configs ----

    /** One BTC-like market (id 1), array impl, capacity 4096, caps 100/0. */
    public static EngineConfigState.MarketDef[] btcMarket() {
        return new EngineConfigState.MarketDef[]{new EngineConfigState.MarketDef(MARKET, "BTC-USD",
                FixedPoint.fromDouble(50_000.0), FixedPoint.fromDouble(150_000.0), FixedPoint.fromDouble(1.0))};
    }

    public static EngineConfigState config(long configVersion, long behaviorVersion, long genesisId,
                                           long maxWireVersion, long commandLedgerCapacity) {
        return EngineConfigState.of(configVersion, EngineConfigState.IMPL_ARRAY, 4096, 100, 0, btcMarket(),
                behaviorVersion, commandLedgerCapacity, genesisId, maxWireVersion);
    }

    /**
     * RC2 S5 (W1, FIX-12): {@link #config} with an explicit impl and match cap. Array: capacity 4096,
     * {@code maxOrdersPerLevel} 0; direct: {@code bookCapacity} 0 and the given
     * {@code maxOrdersPerLevel} (64 when 0 is passed).
     */
    public static EngineConfigState config(long configVersion, long behaviorVersion, long genesisId,
                                           long maxWireVersion, long commandLedgerCapacity, String impl,
                                           long maxMatchesPerOrder, long maxOrdersPerLevel) {
        final boolean direct = EngineConfigState.IMPL_DIRECT.equals(impl);
        return EngineConfigState.of(configVersion, impl, direct ? 0 : 4096, maxMatchesPerOrder,
                direct ? (maxOrdersPerLevel == 0 ? 64 : maxOrdersPerLevel) : 0, btcMarket(),
                behaviorVersion, commandLedgerCapacity, genesisId, maxWireVersion);
    }

    /** The standard behaviour-2 genesis config: cv 1, t 2, genesis G, ceiling 12, capacity 50000. */
    public static EngineConfigState fresh2() {
        return config(1, 2, G, 12, 50_000);
    }

    // ---- journal frame helpers ----

    public static int templateId(byte[] frame) {
        return new UnsafeBuffer(frame).getShort(2, ByteOrder.LITTLE_ENDIAN) & 0xFFFF;
    }

    public static List<byte[]> ofTemplate(List<byte[]> frames, int templateId) {
        final List<byte[]> out = new ArrayList<>();
        for (byte[] f : frames) {
            if (templateId(f) == templateId) {
                out.add(f);
            }
        }
        return out;
    }

    /** Every record's meEpoch sits at body offset 8 (journalSeq 0, meEpoch 8) on all five templates. */
    public static long meEpochOf(byte[] frame) {
        return new UnsafeBuffer(frame).getLong(8 + 8, ByteOrder.LITTLE_ENDIAN);
    }

    public static long journalSeqOf(byte[] frame) {
        return new UnsafeBuffer(frame).getLong(8, ByteOrder.LITTLE_ENDIAN);
    }

    public static com.match.infrastructure.mejournal.generated.EngineConfigDecoder record5(byte[] frame) {
        return new com.match.infrastructure.mejournal.generated.EngineConfigDecoder().wrapAndApplyHeader(
                new UnsafeBuffer(frame), 0, new com.match.infrastructure.mejournal.generated.MessageHeaderDecoder());
    }

    /** RC2 S4: decode a record 4 (CommandRelease) frame. */
    public static com.match.infrastructure.mejournal.generated.CommandReleaseDecoder record4(byte[] frame) {
        return new com.match.infrastructure.mejournal.generated.CommandReleaseDecoder().wrapAndApplyHeader(
                new UnsafeBuffer(frame), 0, new com.match.infrastructure.mejournal.generated.MessageHeaderDecoder());
    }

    public static com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder record3(byte[] frame) {
        return new com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder().wrapAndApplyHeader(
                new UnsafeBuffer(frame), 0, new com.match.infrastructure.mejournal.generated.MessageHeaderDecoder());
    }
}
