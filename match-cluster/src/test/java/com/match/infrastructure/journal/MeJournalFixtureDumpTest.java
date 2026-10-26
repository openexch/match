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
        assertFrame(f.get(MeJournalFixtureDump.TRADE), 1, 117);
        assertFrame(f.get(MeJournalFixtureDump.TERMINAL), 2, 70);
        assertFrame(f.get(MeJournalFixtureDump.COMMAND_FIRST), 3, 161);
        assertFrame(f.get(MeJournalFixtureDump.COMMAND_RETRY), 3, 161);
        assertFrame(f.get(MeJournalFixtureDump.RELEASE), 4, 56 + 22 * 2);
        assertFrame(f.get(MeJournalFixtureDump.CONFIG), 5, 47);
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
