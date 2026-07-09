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
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only sidecar mapping the settlement journal's logical position (egressSeq) to the
 * recorded publication's byte position, one fixed 16-byte little-endian record per archive
 * segment boundary. Retention ({@code JournalRetention}) uses it to translate "safe to purge
 * below egressSeq W" into a byte position that {@code purgeSegments} understands.
 *
 * Records are (egressSeq:int64, publicationPosition:int64). Appends are forced to disk:
 * they happen at most once per 64MB segment, so the fsync cost is negligible, and a stale
 * checkpoint could let retention purge journal bytes a rebuild still needs.
 */
public final class JournalCheckpointFile implements AutoCloseable {

    public static final String FILE_NAME = "journal-checkpoints.dat";
    private static final int RECORD_LENGTH = 16;

    private final FileChannel channel;
    private final ByteBuffer record = ByteBuffer.allocateDirect(RECORD_LENGTH).order(ByteOrder.LITTLE_ENDIAN);

    public JournalCheckpointFile(final Path journalDir) {
        try {
            Files.createDirectories(journalDir);
            this.channel = FileChannel.open(journalDir.resolve(FILE_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot open journal checkpoint file in " + journalDir, e);
        }
    }

    public void append(final long egressSeq, final long publicationPosition) {
        record.clear();
        record.putLong(egressSeq).putLong(publicationPosition).flip();
        try {
            while (record.hasRemaining()) {
                channel.write(record);
            }
            channel.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("journal checkpoint append failed", e);
        }
    }

    /** All checkpoints as {egressSeq, position} pairs, oldest first. Tolerates a torn tail record. */
    public static List<long[]> readAll(final Path journalDir) {
        final Path file = journalDir.resolve(FILE_NAME);
        final List<long[]> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            final byte[] bytes = Files.readAllBytes(file);
            final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.remaining() >= RECORD_LENGTH) {
                out.add(new long[] {buf.getLong(), buf.getLong()});
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read journal checkpoint file " + file, e);
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // closing on shutdown; nothing actionable
        }
    }
}
