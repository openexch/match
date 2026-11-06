// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The schema 4 cross-language fixtures (RC2 S1 FIX-7): the dump is byte-stable run to run, and
 * the committed copy under {@code src/test/resources/mejournal/} is exactly what the dump
 * produces (regenerate with {@code MeJournalFixtureDump match-cluster/src/test/resources/mejournal}).
 */
public class MeJournalFixtureDumpTest {

    @Test
    public void dumpIsByteStableRunToRun() {
        final Map<String, byte[]> first = MeJournalFixtureDump.fixtures();
        final Map<String, byte[]> second = MeJournalFixtureDump.fixtures();
        assertEquals(first.keySet(), second.keySet());
        for (final String name : first.keySet()) {
            assertArrayEquals(name, first.get(name), second.get(name));
        }
        assertEquals(MeJournalFixtureDump.sha256Sums(first), MeJournalFixtureDump.sha256Sums(second));
    }

    @Test
    public void dumpHasTheSixRecordsAtTheirFrameLengths() {
        final Map<String, byte[]> f = MeJournalFixtureDump.fixtures();
        assertEquals(6, f.size());
        assertFrame(f.get(TRADE), 1, 117);
        assertFrame(f.get(TERMINAL), 2, 70);
        assertFrame(f.get(COMMAND_FIRST), 3, 161);
        assertFrame(f.get(COMMAND_CONFLICT), 3, 161);
        assertFrame(f.get(RELEASE), 4, 56 + 22 * 2);
        assertFrame(f.get(CONFIG), 5, 47);
    }

    // RC2 S3 (W5a): the fixture names, as string literals so a stale dump fails by value.
    private static final String TRADE = "java-trade.bin";
    private static final String TERMINAL = "java-terminal.bin";
    private static final String COMMAND_FIRST = "java-command.first.bin";
    private static final String COMMAND_CONFLICT = "java-command.conflict.bin";
    private static final String RELEASE = "java-release.bin";
    private static final String CONFIG = "java-config.bin";

    /** The positive genesis nonce every fixture is stamped with (BehaviourTwoHarness.G). */
    private static final long G = 0x1234_5678_9ABC_DEF1L;

    private static long at(byte[] frame, int bodyOffset) {
        return new UnsafeBuffer(frame).getLong(MessageHeaderDecoder.ENCODED_LENGTH + bodyOffset,
                java.nio.ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * RC2 S3 contract section 11 (MeJournalFixtureDumpTest row, review NOTE-10): one
     * identity-bound producer sequence: journalSeq 1..6 in the order config, trade, terminal,
     * command.first, command.conflict, release; meEpoch G on every frame; egressSeq
     * non-decreasing in seq order; behaviour 2 on records 3 and 5; ceiling 12; the conflict in
     * the commandRejection shape.
     */
    @Test
    public void fixturesAreOneDenseIdentityBoundSequence() {
        final Map<String, byte[]> f = MeJournalFixtureDump.fixtures();
        final String[] order = {CONFIG, TRADE, TERMINAL, COMMAND_FIRST, COMMAND_CONFLICT, RELEASE};
        final long[] egress = {64, 128, 128, 192, 256, 640};
        for (int i = 0; i < order.length; i++) {
            final byte[] frame = f.get(order[i]);
            assertNotNull("missing fixture " + order[i], frame);
            assertEquals(order[i] + " journalSeq", i + 1L, at(frame, 0));
            assertEquals(order[i] + " meEpoch", G, at(frame, 8));
            assertEquals(order[i] + " egressSeq", egress[i], at(frame, 16));
        }
        final MessageHeaderDecoder h = new MessageHeaderDecoder();
        final var config = new com.match.infrastructure.mejournal.generated.EngineConfigDecoder()
                .wrapAndApplyHeader(new UnsafeBuffer(f.get(CONFIG)), 0, h);
        assertEquals(2L, config.behaviorVersion());
        assertEquals(12, config.maxWireVersion());
        assertEquals(com.match.infrastructure.mejournal.generated.ConfigDecision.FRESH, config.decision());
        final var first = new com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder()
                .wrapAndApplyHeader(new UnsafeBuffer(f.get(COMMAND_FIRST)), 0, h);
        assertEquals(2L, first.behaviorVersion());
        assertEquals(0, first.result());
        assertEquals(192L, first.appliedPosition());
        assertEquals(1, first.ledgerSize());
        final var conflict = new com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder()
                .wrapAndApplyHeader(new UnsafeBuffer(f.get(COMMAND_CONFLICT)), 0, h);
        assertEquals(2L, conflict.behaviorVersion());
        assertEquals("CONFLICT", 2, conflict.result());
        assertEquals("commandRejection: orderId 0", 0L, conflict.orderId());
        assertEquals("commandRejection: status -1", -1, conflict.status());
        assertEquals("commandRejection: reason 0", 0, conflict.reason());
        assertEquals("commandRejection: appliedPosition = egressSeq", 256L, conflict.appliedPosition());
        assertEquals("not recorded: ledger size unchanged", 1, conflict.ledgerSize());
        assertEquals("same command id as the first", first.commandIdLow(), conflict.commandIdLow());
        assertEquals(first.commandIdHigh(), conflict.commandIdHigh());
        assertTrue("a different payload", first.price() != conflict.price());
        assertNull("java-command.retry.bin no longer exists", f.get("java-command.retry.bin"));
    }

    @Test
    public void committedFixturesMatchTheDump() throws IOException {
        final Map<String, byte[]> f = MeJournalFixtureDump.fixtures();
        for (final Map.Entry<String, byte[]> e : f.entrySet()) {
            assertArrayEquals("committed " + e.getKey() + " drifted from MeJournalFixtureDump",
                    e.getValue(), resource(e.getKey()));
        }
        assertEquals("committed SHA256SUMS drifted",
                MeJournalFixtureDump.sha256Sums(f),
                new String(resource(MeJournalFixtureDump.SUMS), StandardCharsets.US_ASCII));
    }

    private static void assertFrame(final byte[] frame, final int templateId, final int length) {
        assertNotNull(frame);
        assertEquals(length, frame.length);
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(new UnsafeBuffer(frame), 0);
        assertEquals(4, h.schemaId());
        assertEquals(1, h.version());
        assertEquals(templateId, h.templateId());
    }

    private static byte[] resource(final String name) throws IOException {
        try (InputStream in = MeJournalFixtureDumpTest.class.getResourceAsStream("/mejournal/" + name)) {
            assertNotNull("missing committed fixture mejournal/" + name, in);
            return in.readAllBytes();
        }
    }
}
