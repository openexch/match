// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The settlement journal's head: the {@code (meEpoch, journalSeq)} of a frame the writer has offered to the recorded
 * publication, and that publication's recording id. 32 bytes rewritten in place:
 * {@code meEpoch:int64, journalSeq:int64, recordingId:int64, crc32c:int32 (over the 24 bytes before it),
 * pad:int32 = 0}.
 *
 * <p>Retention uses it as an upper bound for plausibility only: no reader can have applied a record that was never
 * written, so an "applied" number above the head is a number in the wrong unit. The head may lag behind the journal
 * (it is written at every checkpoint and at most once a second in between); it is never ahead of what was offered.
 * A head that fails its CRC (a crash in the middle of a rewrite) reads as missing until the writer rewrites it.</p>
 */
public final class JournalHeadFile implements AutoCloseable {

    public static final String FILE_NAME = "journal-head.dat";
    public static final int LENGTH = 32;
    private static final int COVERED_LENGTH = 24;

    /** The content of a good head file. */
    public record Head(long meEpoch, long journalSeq, long recordingId) { }

    private final FileChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(LENGTH).order(ByteOrder.LITTLE_ENDIAN);

    public JournalHeadFile(final Path journalDir) {
        try {
            Files.createDirectories(journalDir);
            this.channel = FileChannel.open(journalDir.resolve(FILE_NAME), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot open journal head file in " + journalDir, e);
        }
    }

    /** Rewrite the head in place and force it. */
    public void write(final long meEpoch, final long journalSeq, final long recordingId) {
        buffer.clear();
        buffer.putLong(meEpoch).putLong(journalSeq).putLong(recordingId);
        buffer.putInt(JournalSidecarV2.crc(buffer, 0, COVERED_LENGTH)).putInt(0).flip();
        try {
            int at = 0;
            while (buffer.hasRemaining()) {
                at += channel.write(buffer, at);
            }
            channel.force(false);
        } catch (final IOException e) {
            throw new UncheckedIOException("journal head write failed", e);
        }
    }

    /** The head of {@code journalDir}, or null when the file is missing, short, or fails its CRC. */
    public static Head read(final Path journalDir) {
        final Path file = journalDir.resolve(FILE_NAME);
        try {
            if (!Files.exists(file)) {
                return null;
            }
            final byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != LENGTH) {
                return null;
            }
            final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (buf.getInt(COVERED_LENGTH) != JournalSidecarV2.crc(buf, 0, COVERED_LENGTH)
                    || buf.getInt(COVERED_LENGTH + 4) != 0) {
                return null;
            }
            return new Head(buf.getLong(0), buf.getLong(8), buf.getLong(16));
        } catch (final IOException e) {
            return null;
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (final IOException ignored) {
            // closing on shutdown; nothing actionable
        }
    }
}
