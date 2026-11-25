// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * The settlement journal's checkpoint sidecar: one record per archive segment boundary that says "the frame with
 * this {@code (meEpoch, journalSeq)} ended at this byte position of this recording". Retention
 * ({@link JournalRetention}) uses it to turn "every reader has applied up to journalSeq S of epoch E" into a byte
 * position that {@code purgeSegments} understands.
 *
 * <p>Append-only, 48-byte little-endian records:
 * {@code meEpoch:int64, journalSeq:int64, egressSeq:int64, recordingId:int64, position:int64, crc32c:int32 (over the
 * 40 bytes before it), pad:int32 = 0}. Every append is forced to disk: it happens once per segment, and a checkpoint
 * that is lost only delays a purge.</p>
 *
 * <p>The CRC guards tearing only; the epoch and the recording id are the identity. A torn tail record (a crash in
 * the middle of an append) is ignored by a reader and cut off by the next writer. A record with a bad CRC before the
 * tail makes the file unusable: a reader refuses it, and the next writer sets it aside and starts a new one.</p>
 */
public final class JournalSidecarV2 implements AutoCloseable {

    public static final String FILE_NAME = "journal-checkpoints-v2.dat";
    public static final int RECORD_LENGTH = 48;
    private static final int COVERED_LENGTH = 40;

    /** One checkpoint. */
    public record Checkpoint(long meEpoch, long journalSeq, long egressSeq, long recordingId, long position) { }

    /** What a reader found: the checkpoints oldest first, or why the file cannot be used. */
    public static final class Read {
        public final List<Checkpoint> checkpoints;
        /** Null when the file is usable. */
        public final String unusable;
        /** Bytes of whole, good records at the start of the file. */
        final long goodLength;

        private Read(final List<Checkpoint> checkpoints, final String unusable, final long goodLength) {
            this.checkpoints = checkpoints;
            this.unusable = unusable;
            this.goodLength = goodLength;
        }
    }

    private final FileChannel channel;
    private final ByteBuffer record = ByteBuffer.allocateDirect(RECORD_LENGTH).order(ByteOrder.LITTLE_ENDIAN);

    /**
     * Open the sidecar of {@code journalDir} for appending. A torn tail is cut off first, so the next record starts
     * on a record boundary; an unusable file is renamed aside and a new one started.
     */
    public JournalSidecarV2(final Path journalDir) {
        try {
            Files.createDirectories(journalDir);
            final Path file = journalDir.resolve(FILE_NAME);
            if (Files.exists(file)) {
                final Read read = readAll(journalDir);
                if (read.unusable != null) {
                    final Path aside = journalDir.resolve(FILE_NAME + ".unusable-" + System.currentTimeMillis());
                    Files.move(file, aside, StandardCopyOption.ATOMIC_MOVE);
                    System.err.println("[JOURNAL] CRITICAL: checkpoint sidecar " + file + " is unusable ("
                            + read.unusable + "); kept as " + aside.getFileName() + ", starting a new one. Segments"
                            + " written before this point are purged only through checkpoints written from now on.");
                } else if (read.goodLength != Files.size(file)) {
                    try (FileChannel repair = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        repair.truncate(read.goodLength);
                        repair.force(true);
                    }
                }
            }
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot open journal checkpoint sidecar in " + journalDir, e);
        }
    }

    public void append(final long meEpoch, final long journalSeq, final long egressSeq, final long recordingId,
                       final long position) {
        encode(record, meEpoch, journalSeq, egressSeq, recordingId, position);
        try {
            while (record.hasRemaining()) {
                channel.write(record);
            }
            channel.force(false);
        } catch (final IOException e) {
            throw new UncheckedIOException("journal checkpoint append failed", e);
        }
    }

    static void encode(final ByteBuffer record, final long meEpoch, final long journalSeq, final long egressSeq,
                       final long recordingId, final long position) {
        record.clear();
        record.putLong(meEpoch).putLong(journalSeq).putLong(egressSeq).putLong(recordingId).putLong(position);
        record.putInt(crc(record, 0, COVERED_LENGTH)).putInt(0).flip();
    }

    static int crc(final ByteBuffer buffer, final int offset, final int length) {
        final ByteBuffer view = buffer.duplicate();
        view.limit(offset + length).position(offset);
        final CRC32C crc = new CRC32C();
        crc.update(view);
        return (int) crc.getValue();
    }

    /**
     * All checkpoints of {@code journalDir}, oldest first. A missing file is unusable (the writer has not written
     * one yet); so is a bad record before the tail. A torn tail record is left out.
     */
    public static Read readAll(final Path journalDir) {
        final Path file = journalDir.resolve(FILE_NAME);
        final byte[] bytes;
        try {
            if (!Files.exists(file)) {
                return new Read(List.of(), "no " + FILE_NAME + " in " + journalDir, 0);
            }
            bytes = Files.readAllBytes(file);
        } catch (final IOException e) {
            return new Read(List.of(), "cannot read " + file + ": " + e, 0);
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        final int whole = bytes.length / RECORD_LENGTH;
        final boolean partialTail = bytes.length % RECORD_LENGTH != 0;
        final List<Checkpoint> out = new ArrayList<>(whole);
        for (int i = 0; i < whole; i++) {
            final int at = i * RECORD_LENGTH;
            final boolean good = buf.getInt(at + COVERED_LENGTH) == crc(buf, at, COVERED_LENGTH)
                    && buf.getInt(at + COVERED_LENGTH + 4) == 0;
            if (!good) {
                if (i == whole - 1 && !partialTail) {
                    break; // the tail record, torn: ignored
                }
                return new Read(List.of(), "record " + i + " of " + file + " fails its CRC and is not the tail", 0);
            }
            out.add(new Checkpoint(buf.getLong(at), buf.getLong(at + 8), buf.getLong(at + 16), buf.getLong(at + 24),
                    buf.getLong(at + 32)));
        }
        return new Read(out, null, (long) out.size() * RECORD_LENGTH);
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
