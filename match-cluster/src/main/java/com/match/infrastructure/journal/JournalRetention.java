// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.infrastructure.InfrastructureConstants;
import io.aeron.archive.client.AeronArchive;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Disk reclamation for the settlement journal's per-node archive, keyed by what its readers have applied.
 *
 * <p>The journal is a per-node SECOND archive (see {@link SettlementJournalRuntime}) recording a publication on
 * {@link InfrastructureConstants#settlementJournalChannel()} stream
 * {@link InfrastructureConstants#SETTLEMENT_JOURNAL_STREAM_ID}. It is the money-settlement record and the core's
 * rebuild input, so deleting a record a reader has not applied is an unrecoverable lost settlement.</p>
 *
 * <h2>The key and its units</h2>
 *
 * <p>The caller says: every reader that needs this journal has durably applied every record of epoch
 * {@code --me-epoch} with {@code journalSeq <= --applied-journal-seq}. Both numbers are named and both are checked.
 * {@code journalSeq} is the dense per-epoch cursor every reader keeps; with several readers the caller passes the
 * lowest. The earlier form of this tool took one bare number in a different unit ({@code egressSeq}, a byte position
 * of the cluster log); a number in the wrong unit then either purged nothing for ever or purged up to the head. That
 * form is refused.</p>
 *
 * <h2>Refusals (nothing is purged)</h2>
 * <ul>
 *   <li>a positional argument, an unknown, missing or repeated flag: exit 64;</li>
 *   <li>{@code --applied-journal-seq} below 1, or {@code --me-epoch} 0: exit 65;</li>
 *   <li>the head file ({@link JournalHeadFile}) is missing or fails its CRC, or the checkpoint sidecar
 *       ({@link JournalSidecarV2}) is unusable: exit 65;</li>
 *   <li>{@code --me-epoch} differs from the head's epoch, or from any checkpoint of the active recording: exit 65;</li>
 *   <li>{@code --applied-journal-seq} is above the head's {@code journalSeq}: exit 65. A reader cannot have applied
 *       what was never written, so every number that is too large stops here. The head may lag; that only delays a
 *       purge to the next run.</li>
 * </ul>
 *
 * <h2>Selection</h2>
 *
 * <p>Among the checkpoints of the ACTIVE recording (exactly one recording is active) whose
 * {@code journalSeq <= applied}, the greatest position; rounded down to a segment base, and backed off so that two
 * whole segments stay below the recording's head. A checkpoint is written for a frame at the position where that
 * frame ended, so every record below the chosen position has a {@code journalSeq} at or below the checkpoint's, and
 * so at or below the applied value. No qualifying checkpoint is a logged no-op with exit 0.</p>
 *
 * <p>Stopped recordings (earlier incarnations of the node) are reported and never purged here.</p>
 *
 * <p>Exit codes: 0 done or nothing to purge, 64 usage, 65 refused input, 1 runtime failure (archive unreachable).</p>
 */
public final class JournalRetention {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 64;
    public static final int EXIT_REFUSED = 65;

    private static final String LOG = "[JOURNAL-RETENTION] ";

    static final String USAGE = "Usage: JournalRetention --journal-node-dir <dir> --aeron-dir <dir>"
            + " --archive-control-port <port> --me-epoch <int64> --applied-journal-seq <int64> [--dry-run]";
    static final String POSITIONAL_REFUSAL =
            "positional arguments are no longer accepted: name the unit (--me-epoch, --applied-journal-seq)";

    private static final String JOURNAL_NODE_DIR = "--journal-node-dir";
    private static final String AERON_DIR = "--aeron-dir";
    private static final String CONTROL_PORT = "--archive-control-port";
    private static final String ME_EPOCH = "--me-epoch";
    private static final String APPLIED_SEQ = "--applied-journal-seq";
    private static final String DRY_RUN = "--dry-run";
    private static final Set<String> VALUED = Set.of(JOURNAL_NODE_DIR, AERON_DIR, CONTROL_PORT, ME_EPOCH, APPLIED_SEQ);

    // Test seams, the negative controls of the two rules that make a wrong-unit number harmless.
    /** The applied value is not checked against the journal head. */
    static volatile boolean headBoundOffForTest;
    /** Checkpoints are selected on {@code egressSeq}, the rule this tool had before. */
    static volatile boolean selectOnEgressSeqForTest;

    /** An input this tool refuses: exit 65, nothing purged. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(final String message) {
            super(message);
        }
    }

    private static final class Usage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Usage(final String message) {
            super(message);
        }
    }

    private JournalRetention() {}

    /** One recording descriptor row we care about (subset of the 16-field Aeron descriptor). */
    private static final class Rec {
        final long recordingId;
        final long startPosition;
        final long stopPosition;
        final int termBufferLength;
        final int segmentFileLength;

        Rec(long recordingId, long startPosition, long stopPosition,
            int termBufferLength, int segmentFileLength) {
            this.recordingId = recordingId;
            this.startPosition = startPosition;
            this.stopPosition = stopPosition;
            this.termBufferLength = termBufferLength;
            this.segmentFileLength = segmentFileLength;
        }

        boolean isActive() {
            return stopPosition == AeronArchive.NULL_POSITION;
        }
    }

    /** What the node's own files say about a request, before the archive is asked anything. */
    static final class Checked {
        final JournalHeadFile.Head head;
        final List<JournalSidecarV2.Checkpoint> checkpoints;

        Checked(final JournalHeadFile.Head head, final List<JournalSidecarV2.Checkpoint> checkpoints) {
            this.head = head;
            this.checkpoints = checkpoints;
        }
    }

    /**
     * The checks that need no archive: the two numbers, the head file, the sidecar, the epoch and the head bound.
     *
     * @throws Refused naming the values
     */
    static Checked check(final Path journalNodeDir, final long meEpoch, final long appliedJournalSeq) {
        if (appliedJournalSeq < 1) {
            throw new Refused("--applied-journal-seq " + appliedJournalSeq + " is below 1: nothing can have been"
                    + " applied; nothing purged");
        }
        if (meEpoch == 0) {
            throw new Refused("--me-epoch 0 is no epoch (an engine without a bound epoch has no journalSeq to"
                    + " purge by); nothing purged");
        }
        final JournalHeadFile.Head head = JournalHeadFile.read(journalNodeDir);
        if (head == null) {
            throw new Refused("the journal head file " + journalNodeDir.resolve(JournalHeadFile.FILE_NAME)
                    + " is missing or fails its CRC: the writer has not written it yet; nothing purged");
        }
        final JournalSidecarV2.Read sidecar = JournalSidecarV2.readAll(journalNodeDir);
        if (sidecar.unusable != null) {
            throw new Refused("the checkpoint sidecar is unusable: " + sidecar.unusable + "; nothing purged");
        }
        if (head.meEpoch() != meEpoch) {
            throw new Refused("epoch mismatch: asked " + meEpoch + ", journal " + head.meEpoch()
                    + "; nothing purged");
        }
        if (!headBoundOffForTest && appliedJournalSeq > head.journalSeq()) {
            throw new Refused("applied journalSeq " + appliedJournalSeq + " is beyond the journal head "
                    + head.journalSeq() + ": wrong unit, or the head file is stale; nothing purged");
        }
        return new Checked(head, sidecar.checkpoints);
    }

    /**
     * The position to purge below, chosen from the checkpoints of {@code activeRecordingId}: the greatest position
     * of a checkpoint whose {@code journalSeq <= appliedJournalSeq}; -1 when none qualifies.
     *
     * @throws Refused when a checkpoint of the active recording carries another epoch
     */
    static long selectCandidatePosition(final List<JournalSidecarV2.Checkpoint> checkpoints,
                                        final long activeRecordingId, final long meEpoch,
                                        final long appliedJournalSeq) {
        long best = -1;
        for (final JournalSidecarV2.Checkpoint c : checkpoints) {
            if (c.recordingId() != activeRecordingId) {
                continue; // an earlier incarnation's recording: its positions are another byte space
            }
            if (c.meEpoch() != meEpoch) {
                throw new Refused("epoch mismatch: asked " + meEpoch + ", journal " + c.meEpoch()
                        + " (a checkpoint of the active recording " + activeRecordingId + "); nothing purged");
            }
            final long key = selectOnEgressSeqForTest ? c.egressSeq() : c.journalSeq();
            final boolean qualifies = selectOnEgressSeqForTest ? key < appliedJournalSeq : key <= appliedJournalSeq;
            if (qualifies && c.position() > best) {
                best = c.position();
            }
        }
        return best;
    }

    /**
     * Purge the journal segments every reader has passed, on the node whose journal directory is
     * {@code journalNodeDir} (it holds the head file, the checkpoint sidecar and an {@code archive/} subdirectory).
     * Live-safe: {@code archive} is a connected client of the node's running journal archive.
     *
     * @param meEpoch           the epoch the applied cursor belongs to
     * @param appliedJournalSeq every reader has durably applied every record of that epoch up to and including this
     * @param dryRun            say what would be purged and purge nothing
     * @return bytes reclaimed from the active recording (0 on a no-op and on a dry run)
     * @throws Refused when the input is refused; nothing was purged
     */
    public static long purgeBelow(final AeronArchive archive, final Path journalNodeDir, final long meEpoch,
                                  final long appliedJournalSeq, final boolean dryRun) {
        final Checked checked = check(journalNodeDir, meEpoch, appliedJournalSeq);

        // Enumerate every journal recording (active + all stopped incarnations) for the journal stream.
        final List<Rec> recordings = new ArrayList<>();
        archive.listRecordingsForUri(0, Integer.MAX_VALUE, "aeron:ipc",
                InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID,
                (controlSessionId, correlationId, recordingId, startTimestamp, stopTimestamp,
                 startPosition, stopPosition, initialTermId, segmentFileLength, termBufferLength,
                 mtuLength, sessionId, streamId, strippedChannel, originalChannel, sourceIdentity) ->
                        recordings.add(new Rec(recordingId, startPosition, stopPosition,
                                termBufferLength, segmentFileLength)));

        if (recordings.isEmpty()) {
            System.out.println(LOG + "no journal recording found for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID + " in " + journalNodeDir
                    + ": the journal never wrote, or this is the wrong archive. No-op.");
            return 0;
        }

        Rec active = null;
        int activeCount = 0;
        for (final Rec r : recordings) {
            if (r.isActive()) {
                active = r;
                activeCount++;
            } else {
                // A stopped recording is an earlier incarnation of this node. It is reported and left intact.
                System.out.println(LOG + "STOPPED recording " + r.recordingId + " retained: startPosition="
                        + r.startPosition + " stopPosition=" + r.stopPosition + " bytes="
                        + (r.stopPosition - r.startPosition) + ". NOT purged.");
            }
        }

        if (activeCount == 0) {
            System.out.println(LOG + "no ACTIVE recording for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID
                    + " (only stopped incarnations): nothing to purge on the active archive. No-op.");
            return 0;
        }
        if (activeCount > 1) {
            System.out.println(LOG + "ANOMALY: " + activeCount + " ACTIVE recordings for stream "
                    + InfrastructureConstants.SETTLEMENT_JOURNAL_STREAM_ID
                    + ": refusing to purge (ambiguous which is live). No-op.");
            return 0;
        }

        final long candidate = selectCandidatePosition(checked.checkpoints, active.recordingId, meEpoch,
                appliedJournalSeq);
        if (candidate < 0) {
            System.out.println(LOG + "nothing at or below journalSeq " + appliedJournalSeq + " (epoch " + meEpoch
                    + ") among the checkpoints of the active recording " + active.recordingId + ". No-op.");
            return 0;
        }

        final long recordingPosition = archive.getRecordingPosition(active.recordingId);
        if (recordingPosition == AeronArchive.NULL_POSITION) {
            System.out.println(LOG + "active recording " + active.recordingId + " position unavailable "
                    + "(recording just stopped?): refusing to purge. No-op.");
            return 0;
        }

        // Translate candidate -> segment base, back off to keep >= 2 whole segments below the recording head.
        final long target = computePurgeTarget(active.startPosition, recordingPosition, candidate,
                active.termBufferLength, active.segmentFileLength);
        if (target <= active.startPosition) {
            System.out.println(LOG + "computed purge target " + target + " <= startPosition "
                    + active.startPosition + " (candidate=" + candidate + ", recordingPosition="
                    + recordingPosition + ", segmentFileLength=" + active.segmentFileLength
                    + ", keeping >= 2 whole segments): nothing to purge. No-op.");
            return 0;
        }

        final long bytes = target - active.startPosition;
        final String what = "recording " + active.recordingId + ": startPosition " + active.startPosition + " -> "
                + target + " (" + bytes + " bytes; meEpoch=" + meEpoch + ", appliedJournalSeq=" + appliedJournalSeq
                + ", journal head journalSeq=" + checked.head.journalSeq() + ", candidate checkpoint position="
                + candidate + ", recordingPosition=" + recordingPosition
                + ", kept >= 2 whole segments below recordingPosition)";
        if (dryRun) {
            System.out.println(LOG + "dry run: would purge journal segments on " + what + ". Nothing purged.");
            return 0;
        }
        final long segmentsPurged = archive.purgeSegments(active.recordingId, target);
        System.out.println(LOG + "purged " + segmentsPurged + " journal segment(s) on " + what + ".");
        return bytes;
    }

    /**
     * The clamped purge target (a segment base) for the active recording. Returns a value
     * {@code <= startPosition} (signalling "no-op") when nothing can be safely purged.
     *
     * <p>Guards, in order:
     * <ul>
     *   <li>no candidate ({@code candidatePosition < 0}): no-op;</li>
     *   <li>candidate beyond the recording head ({@code candidatePosition > recordingPosition}): the checkpoint
     *       cannot describe this recording as the archive sees it, so refuse: no-op;</li>
     *   <li>otherwise: {@code min(segmentBaseOf(candidate), segmentBaseOf(recordingPosition) - 2
     *       segments)}: round the candidate down to a segment base, then back off so at least two
     *       whole segments remain below the recording head.</li>
     * </ul>
     */
    static long computePurgeTarget(final long startPosition, final long recordingPosition,
                                   final long candidatePosition, final int termBufferLength,
                                   final int segmentFileLength) {
        if (candidatePosition < 0) {
            return startPosition;
        }
        if (candidatePosition > recordingPosition) {
            return startPosition;
        }
        final long fromCandidate = AeronArchive.segmentFileBasePosition(
                startPosition, candidatePosition, termBufferLength, segmentFileLength);
        final long activeSegmentBase = AeronArchive.segmentFileBasePosition(
                startPosition, recordingPosition, termBufferLength, segmentFileLength);
        final long keepTwoWholeSegments = activeSegmentBase - 2L * segmentFileLength;
        return Math.min(fromCandidate, keepTwoWholeSegments);
    }

    /** The named flags of the command line. A positional argument or a flag problem is a usage error. */
    static Map<String, String> flags(final String[] args) {
        final Map<String, String> found = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            final String flag = args[i];
            if (!flag.startsWith("--")) {
                throw new Usage(POSITIONAL_REFUSAL);
            }
            if (found.containsKey(flag)) {
                throw new Usage("flag " + flag + " given twice");
            }
            if (DRY_RUN.equals(flag)) {
                found.put(flag, "true");
            } else if (VALUED.contains(flag)) {
                if (i + 1 >= args.length) {
                    throw new Usage("flag " + flag + " needs a value");
                }
                found.put(flag, args[++i]);
            } else {
                throw new Usage("unknown flag " + flag);
            }
        }
        for (final String flag : new String[] {JOURNAL_NODE_DIR, AERON_DIR, CONTROL_PORT, ME_EPOCH, APPLIED_SEQ}) {
            if (!found.containsKey(flag)) {
                throw new Usage("missing flag " + flag);
            }
        }
        return found;
    }

    private static long number(final Map<String, String> flags, final String flag) {
        try {
            return Long.parseLong(flags.get(flag));
        } catch (final NumberFormatException e) {
            throw new Refused(flag + " is not a number: '" + flags.get(flag) + "'; nothing purged");
        }
    }

    /**
     * The command without the exit: parse, check the node's own files, then connect and purge.
     *
     * <p>{@code --journal-node-dir} is {@code <SETTLEMENT_JOURNAL_DIR>/node<N>}, {@code --aeron-dir} is node N's
     * media driver directory, {@code --archive-control-port} is
     * {@code portBase + N*stride + }{@link InfrastructureConstants#JOURNAL_ARCHIVE_CONTROL_PORT_OFFSET}.</p>
     *
     * @return the exit code
     */
    static int run(final String[] args, final PrintStream out, final PrintStream err) {
        final Path journalNodeDir;
        final String aeronDir;
        final long controlPort;
        final long meEpoch;
        final long appliedJournalSeq;
        final boolean dryRun;
        try {
            final Map<String, String> flags = flags(args);
            journalNodeDir = Path.of(flags.get(JOURNAL_NODE_DIR));
            aeronDir = flags.get(AERON_DIR);
            controlPort = number(flags, CONTROL_PORT);
            if (controlPort < 1 || controlPort > 65_535) {
                throw new Refused(CONTROL_PORT + " " + controlPort + " is not a port; nothing purged");
            }
            meEpoch = number(flags, ME_EPOCH);
            appliedJournalSeq = number(flags, APPLIED_SEQ);
            dryRun = flags.containsKey(DRY_RUN);
            // refuse from the node's own files before any connection is made
            check(journalNodeDir, meEpoch, appliedJournalSeq);
        } catch (final Usage usage) {
            err.println(LOG + usage.getMessage());
            err.println(USAGE);
            return EXIT_USAGE;
        } catch (final Refused refused) {
            err.println(LOG + "refused: " + refused.getMessage());
            return EXIT_REFUSED;
        }

        // Mirror SettlementJournalRuntime: when the archive is bound to a specific address
        // (SETTLEMENT_JOURNAL_ARCHIVE_BIND), localhost is no longer a valid destination.
        final String bindHost = SettlementJournalRuntime.archiveBindHost();
        final String connectHost = "0.0.0.0".equals(bindHost) ? "localhost" : bindHost;
        final String controlChannel = "aeron:udp?endpoint=" + connectHost + ":" + controlPort;

        try (AeronArchive archive = AeronArchive.connect(
                new AeronArchive.Context()
                        .aeronDirectoryName(aeronDir)
                        .controlRequestChannel(controlChannel)
                        .controlRequestStreamId(InfrastructureConstants.JOURNAL_ARCHIVE_CONTROL_STREAM_ID)
                        .controlResponseChannel("aeron:udp?endpoint=localhost:0"))) {

            final long bytes = purgeBelow(archive, journalNodeDir, meEpoch, appliedJournalSeq, dryRun);
            out.println(LOG + "Done: bytesReclaimed=" + bytes + " (journalNodeDir=" + journalNodeDir
                    + ", meEpoch=" + meEpoch + ", appliedJournalSeq=" + appliedJournalSeq
                    + (dryRun ? ", dry run" : "") + ")");
            return EXIT_OK;
        } catch (final Refused refused) {
            err.println(LOG + "refused: " + refused.getMessage());
            return EXIT_REFUSED;
        } catch (final Exception e) {
            err.println(LOG + "FAILED: " + e.getMessage());
            e.printStackTrace(err);
            return EXIT_FAILED;
        }
    }

    /**
     * CLI entry point, run per node by whoever knows the readers' cursors.
     *
     * <p>{@code JournalRetention --journal-node-dir <dir> --aeron-dir <dir> --archive-control-port <port>
     * --me-epoch <int64> --applied-journal-seq <int64> [--dry-run]}</p>
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
