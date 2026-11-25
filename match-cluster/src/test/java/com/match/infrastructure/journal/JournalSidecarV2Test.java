// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import org.agrona.IoUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The checkpoint sidecar and the head file as bytes on disk: the record layout, a torn tail, a bad record before
 * the tail, and a head that is missing, torn or rewritten.
 */
public class JournalSidecarV2Test {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("journal-sidecar-v2-");
    }

    @After
    public void tearDown() {
        IoUtil.delete(dir.toFile(), true);
    }

    private Path sidecar() {
        return dir.resolve(JournalSidecarV2.FILE_NAME);
    }

    private void append(final long... fiveFields) {
        try (JournalSidecarV2 s = new JournalSidecarV2(dir)) {
            for (int i = 0; i < fiveFields.length; i += 5) {
                s.append(fiveFields[i], fiveFields[i + 1], fiveFields[i + 2], fiveFields[i + 3], fiveFields[i + 4]);
            }
        }
    }

    @Test
    public void recordLayoutIsFortyEightLittleEndianBytesWithACrcOverTheFirstForty() throws Exception {
        append(0x1122334455667788L, 42, 9_000_000, 7, 65_664);
        final byte[] bytes = Files.readAllBytes(sidecar());
        assertEquals(48, bytes.length);
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("meEpoch @0", 0x1122334455667788L, b.getLong(0));
        assertEquals("journalSeq @8", 42, b.getLong(8));
        assertEquals("egressSeq @16", 9_000_000, b.getLong(16));
        assertEquals("recordingId @24", 7, b.getLong(24));
        assertEquals("position @32", 65_664, b.getLong(32));
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, 40);
        assertEquals("crc32c @40 over the 40 bytes before it", (int) crc.getValue(), b.getInt(40));
        assertEquals("pad @44", 0, b.getInt(44));
        assertEquals((byte) 0x88, bytes[0]); // little endian

        final JournalSidecarV2.Read read = JournalSidecarV2.readAll(dir);
        assertNull(read.unusable);
        assertEquals(List.of(new JournalSidecarV2.Checkpoint(0x1122334455667788L, 42, 9_000_000, 7, 65_664)),
                read.checkpoints);
    }

    @Test
    public void aMissingFileIsUnusable() {
        final JournalSidecarV2.Read read = JournalSidecarV2.readAll(dir);
        assertNotNull(read.unusable);
        assertTrue(read.checkpoints.isEmpty());
    }

    @Test
    public void aTornTailIsIgnoredAndCutOffByTheNextWriter() throws Exception {
        append(5, 1, 100, 7, 128, 5, 600, 200, 7, 65_600);

        // a crash in the middle of the third append: half a record
        Files.write(sidecar(), new byte[20], StandardOpenOption.APPEND);
        JournalSidecarV2.Read read = JournalSidecarV2.readAll(dir);
        assertNull(read.unusable);
        assertEquals(2, read.checkpoints.size());
        // the next writer starts on a record boundary again
        append(5, 1200, 300, 8, 131_200);
        assertEquals(3 * 48, Files.size(sidecar()));
        read = JournalSidecarV2.readAll(dir);
        assertNull(read.unusable);
        assertEquals(3, read.checkpoints.size());
        assertEquals(1200, read.checkpoints.get(2).journalSeq());
        assertEquals(8, read.checkpoints.get(2).recordingId());

        // a whole-length tail record that never got its content: ignored the same way
        Files.write(sidecar(), new byte[48], StandardOpenOption.APPEND);
        read = JournalSidecarV2.readAll(dir);
        assertNull(read.unusable);
        assertEquals(3, read.checkpoints.size());
        append(5, 1800, 400, 8, 196_800);
        assertEquals(4 * 48, Files.size(sidecar()));
        assertEquals(4, JournalSidecarV2.readAll(dir).checkpoints.size());
    }

    @Test
    public void aBadRecordBeforeTheTailMakesTheFileUnusableAndTheWriterSetsItAside() throws Exception {
        append(5, 1, 100, 7, 128, 5, 600, 200, 7, 65_600, 5, 1200, 300, 7, 131_200);
        final byte[] bytes = Files.readAllBytes(sidecar());
        bytes[48 + 8] ^= 0x01; // one bit of the middle record's journalSeq
        Files.write(sidecar(), bytes);

        final JournalSidecarV2.Read read = JournalSidecarV2.readAll(dir);
        assertNotNull("a flipped bit in the middle must not be read as a checkpoint", read.unusable);
        assertTrue(read.unusable, read.unusable.contains("record 1"));
        assertTrue(read.checkpoints.isEmpty());

        // a bad record followed by a partial tail is not the tail either
        Files.write(sidecar(), java.util.Arrays.copyOf(bytes, 2 * 48 + 10));
        assertNotNull(JournalSidecarV2.readAll(dir).unusable);

        // the next writer keeps the bad file aside and starts a new one
        Files.write(sidecar(), bytes);
        append(5, 1800, 400, 9, 128);
        final JournalSidecarV2.Read fresh = JournalSidecarV2.readAll(dir);
        assertNull(fresh.unusable);
        assertEquals(List.of(new JournalSidecarV2.Checkpoint(5, 1800, 400, 9, 128)), fresh.checkpoints);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals("the unusable file is kept under another name", 1, files.filter(f ->
                    f.getFileName().toString().startsWith(JournalSidecarV2.FILE_NAME + ".unusable-")).count());
        }
    }

    @Test
    public void headFileRoundTripsAndATornOneReadsAsMissing() throws Exception {
        assertNull("no head before the writer wrote one", JournalHeadFile.read(dir));
        try (JournalHeadFile head = new JournalHeadFile(dir)) {
            head.write(5, 100, 7);
            assertEquals(new JournalHeadFile.Head(5, 100, 7), JournalHeadFile.read(dir));
            head.write(5, 250, 7); // rewritten in place
            assertEquals(new JournalHeadFile.Head(5, 250, 7), JournalHeadFile.read(dir));
        }
        final Path file = dir.resolve(JournalHeadFile.FILE_NAME);
        final byte[] bytes = Files.readAllBytes(file);
        assertEquals(32, bytes.length);
        final ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("meEpoch @0", 5, b.getLong(0));
        assertEquals("journalSeq @8", 250, b.getLong(8));
        assertEquals("recordingId @16", 7, b.getLong(16));
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, 24);
        assertEquals("crc32c @24", (int) crc.getValue(), b.getInt(24));
        assertEquals("pad @28", 0, b.getInt(28));

        // a rewrite torn between the journalSeq and the CRC: the new number with the old CRC
        b.putLong(8, 9_999_999);
        Files.write(file, bytes);
        assertNull("a torn head must not be read as a bound", JournalHeadFile.read(dir));
        Files.write(file, java.util.Arrays.copyOf(bytes, 16));
        assertNull(JournalHeadFile.read(dir));
        try (JournalHeadFile head = new JournalHeadFile(dir)) {
            head.write(5, 300, 7);
        }
        assertEquals("the writer's next rewrite repairs it", new JournalHeadFile.Head(5, 300, 7),
                JournalHeadFile.read(dir));
        assertFalse(Files.size(file) != 32);
    }
}
