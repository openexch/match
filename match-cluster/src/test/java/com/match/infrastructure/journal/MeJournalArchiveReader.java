// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import io.aeron.Subscription;
import io.aeron.archive.client.AeronArchive;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.concurrent.UnsafeBuffer;

import java.net.DatagramSocket;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RC2 S3 test-scope reader of one node's ME journal archive (schema 4): every recording on the
 * settlement journal stream, replayed from its start to its current end over UDP into the
 * caller's media driver, through the journal archive's control port. One frame per Aeron
 * message, exactly as the writer agent offered it. Reusable by S14's {@code JournalProbe}.
 */
public final class MeJournalArchiveReader {

    /** One journal frame: its recording, the schema-4 head and the raw bytes. */
    public record Frame(long recordingId, long journalSeq, long meEpoch, long egressSeq, byte[] bytes) {
    }

    /** One journal recording, its frames in recording order. */
    public record Recording(long recordingId, long startPosition, long endPosition, List<Frame> frames) {
    }

    private static final int REPLAY_STREAM_ID = 4_099;
    private static final long REPLAY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(20);

    private final String aeronDirectoryName;

    /** @param aeronDirectoryName the caller's (test JVM's) media driver */
    public MeJournalArchiveReader(final String aeronDirectoryName) {
        this.aeronDirectoryName = aeronDirectoryName;
    }

    /** Every journal recording of the archive at {@code localhost:controlPort}, in recording-id order. */
    public List<Recording> read(final int controlPort) {
        try (AeronArchive archive = AeronArchive.connect(new AeronArchive.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .controlRequestChannel("aeron:udp?endpoint=localhost:" + controlPort)
                .controlRequestStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_STREAM_ID)
                .controlResponseChannel("aeron:udp?endpoint=localhost:0")
                .messageTimeoutNs(TimeUnit.SECONDS.toNanos(10)))) {
            final List<long[]> descriptors = new ArrayList<>(); // {recordingId, start, stop}
            archive.listRecordingsForUri(0, 1_000, "aeron:ipc", InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID,
                    (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp, startPosition,
                     stopPosition, initialTermId, segmentFileLength, termBufferLength, mtuLength, sessionId,
                     streamId, strippedChannel, originalChannel, sourceIdentity) ->
                            descriptors.add(new long[] {recordingId, startPosition, stopPosition}));
            descriptors.sort((x, y) -> Long.compare(x[0], y[0]));
            final List<Recording> out = new ArrayList<>();
            for (final long[] d : descriptors) {
                long end = d[2];
                if (end == AeronArchive.NULL_POSITION) {
                    end = archive.getRecordingPosition(d[0]);
                    if (end == AeronArchive.NULL_POSITION) {
                        end = archive.getStopPosition(d[0]);
                    }
                }
                out.add(new Recording(d[0], d[1], end, replay(archive, d[0], d[1], end)));
            }
            return out;
        }
    }

    private static List<Frame> replay(final AeronArchive archive, final long recordingId, final long start,
                                      final long end) {
        final List<Frame> frames = new ArrayList<>();
        if (end <= start) {
            return frames;
        }
        final long[] reached = {start};
        final FragmentHandler handler = (buffer, offset, length, header) -> {
            final byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            frames.add(frame(recordingId, bytes));
            reached[0] = header.position();
        };
        final String channel = "aeron:udp?endpoint=localhost:" + freeUdpPort();
        try (Subscription replay = archive.replay(recordingId, start, end - start, channel, REPLAY_STREAM_ID)) {
            final long deadline = System.currentTimeMillis() + REPLAY_TIMEOUT_MS;
            while (reached[0] < end) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("journal replay of recording " + recordingId + " reached "
                            + reached[0] + " of " + end);
                }
                if (replay.poll(handler, 256) == 0) {
                    try {
                        Thread.sleep(1);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return Collections.unmodifiableList(frames);
    }

    static Frame frame(final long recordingId, final byte[] bytes) {
        final UnsafeBuffer b = new UnsafeBuffer(bytes);
        final MessageHeaderDecoder h = new MessageHeaderDecoder().wrap(b, 0);
        if (h.schemaId() != 4 || h.version() != 1) {
            throw new IllegalStateException("journal frame is not schema 4 v1: schemaId=" + h.schemaId()
                    + " version=" + h.version());
        }
        final int head = MessageHeaderDecoder.ENCODED_LENGTH;
        return new Frame(recordingId, b.getLong(head, ByteOrder.LITTLE_ENDIAN),
                b.getLong(head + 8, ByteOrder.LITTLE_ENDIAN), b.getLong(head + 16, ByteOrder.LITTLE_ENDIAN), bytes);
    }

    private static int freeUdpPort() {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The highest journalSeq across every recording, 0 when there is none. */
    public static long maxSeq(final List<Recording> recordings) {
        long max = 0;
        for (final Recording r : recordings) {
            for (final Frame f : r.frames()) {
                max = Math.max(max, f.journalSeq());
            }
        }
        return max;
    }
}
