// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.application.engine.Engine;
import com.match.application.publisher.MarketEventHandler;
import com.match.application.publisher.MatchEventPublisher;
import com.match.application.publisher.PublishEvent;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableOrderIntent;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RC2 S1 (contract 3.5, 8.2): from S1 on the ME ring carries ME journal schema 4 only. After
 * trades, terminals, command outcomes, a release and a config append through the real engine and
 * publisher, every frame has schemaId 4 version 1 and its ring message type equals its template
 * id; no frame carries schema 1 (template 28) or schema 3.
 */
public class SettlementJournalRingCarriesOnlySchema4Test {

    private static final long PRICE = FixedPoint.fromDouble(59000);
    private static final long QTY = FixedPoint.fromDouble(1);

    @Test
    public void everyRingFrameIsSchema4() {
        final Engine engine = new Engine("direct");
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        final MatchEventPublisher publisher = new MatchEventPublisher();
        publisher.setSettlementJournal(journal);
        publisher.initMarket(1, new MarketEventHandler() {
            public int getMarketId() { return 1; }
            public void onEvent(PublishEvent e, long sequence, boolean endOfBatch) { }
        });
        publisher.start();
        engine.setEventPublisher(publisher);
        try {
            engine.setCurrentLogPosition(128);
            // A resting bid, a crossing ask (trade + FILLED terminals), a retry, a cancel.
            engine.acceptDurable(new DurableOrderIntent(17, 1, 100, 9001, 0, PRICE, QTY, 0, 1, 0, 0, 0), 1000);
            engine.setCurrentLogPosition(256);
            engine.acceptDurable(new DurableOrderIntent(17, 2, 200, 9002, 0, PRICE, QTY, 0, 1, 0, 0, 1), 1001);
            engine.setCurrentLogPosition(384);
            engine.acceptDurable(new DurableOrderIntent(17, 2, 200, 9002, 0, PRICE, QTY, 0, 1, 0, 0, 1), 1002);
            engine.setCurrentLogPosition(512);
            final var resting = engine.acceptDurable(
                    new DurableOrderIntent(17, 3, 300, 9003, 0, PRICE, QTY, 0, 1, 0, 0, 0), 1003);
            engine.setCurrentLogPosition(640);
            engine.acceptDurable(new DurableOrderIntent(17, 4, 300, 9003, resting.orderId(), 0, 0, 0, 1, 1, 0, 0), 1004);
            journal.appendCommandRelease(0L, 768, 9, 4, 100_000, 1, 1,
                    new long[] {17}, new long[] {1}, new int[] {0}, new int[] {0}, new int[] {1});
            journal.appendEngineConfig(0, 64, 1, 1, 100_000, 12, 0);

            final List<int[]> frames = new ArrayList<>(); // {msgType, schemaId, version, templateId}
            journal.ringBuffer().read((msgTypeId, buffer, index, length) -> {
                final UnsafeBuffer copy = new UnsafeBuffer(new byte[length]);
                buffer.getBytes(index, copy, 0, length);
                frames.add(new int[] {msgTypeId, copy.getShort(4) & 0xFFFF, copy.getShort(6) & 0xFFFF,
                        copy.getShort(2) & 0xFFFF});
            }, Integer.MAX_VALUE);

            final Set<Integer> types = new HashSet<>();
            for (final int[] f : frames) {
                assertEquals("schemaId", 4, f[1]);
                assertEquals("version", 1, f[2]);
                assertEquals("ring message type equals template id", f[0], f[3]);
                types.add(f[3]);
            }
            assertEquals("all five record kinds were exercised", Set.of(1, 2, 3, 4, 5), types);
            assertTrue(journal.appendedTrades() >= 1);
            assertTrue(journal.appendedTerminals() >= 2);
        } finally {
            publisher.shutdown();
            engine.close();
        }
    }
}
