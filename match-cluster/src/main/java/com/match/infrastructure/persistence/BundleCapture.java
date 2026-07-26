// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.Aeron;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.RecordingLog;
import io.aeron.AeronVersion;
import com.match.infrastructure.generated.BookDeltaEncoder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Ships one snapshot and the log behind it out of a live node, as a self-contained
 * bundle on real disk.
 *
 * <p>This is step 01 of the durable ledger archive. It changes NO purging
 * behaviour: it only starts producing bundles, so the ledger gains durability
 * before anything begins deleting on the strength of it.
 *
 * <p>A bundle is one snapshot plus the log segments from the previous bundle's
 * position up to this snapshot's position. Bundles chain, so any contiguous run
 * replays as one continuous history. The chaining is why {@link StagingArchive}
 * is persistent - see that class for the Aeron constraint that forces it.
 *
 * <pre>
 * &lt;bundleRoot&gt;/2026-07-26T00-05-00Z-pos-1057814752/
 *     manifest.json
 *     snapshot/            consensus module + service recordings
 *     log/                 segments (previousPosition .. snapshotPosition)
 *     recording-log.txt    RecordingLog entries at capture time
 * </pre>
 *
 * <p><b>Ordering obligation.</b> Run this BEFORE archive housekeeping purges the
 * log below the snapshot, or the range this bundle needs is already gone. Today
 * the admin gateway snapshots and reclaims in one operation, so capture belongs
 * between those two steps. Step 02 makes that structural by holding the purge
 * watermark at the last position durable in S3.
 */
public final class BundleCapture {

    private static final DateTimeFormatter BUNDLE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    /** Outcome of one capture, for the caller's log and the admin gateway's parser. */
    public static final class Result {
        public final boolean captured;
        public final String reason;
        public final File bundleDir;
        public final long snapshotPosition;
        public final long previousPosition;
        public final long logBytes;

        Result(final boolean captured, final String reason, final File bundleDir,
               final long snapshotPosition, final long previousPosition, final long logBytes) {
            this.captured = captured;
            this.reason = reason;
            this.bundleDir = bundleDir;
            this.snapshotPosition = snapshotPosition;
            this.previousPosition = previousPosition;
            this.logBytes = logBytes;
        }

        static Result skipped(final String reason, final long snapshotPosition) {
            return new Result(false, reason, null, snapshotPosition, -1, 0);
        }

        @Override
        public String toString() {
            return captured
                    ? "Result{captured, dir=" + bundleDir.getName()
                        + ", range=" + previousPosition + ".." + snapshotPosition
                        + ", logBytes=" + logBytes + "}"
                    : "Result{skipped: " + reason + ", snapshotPosition=" + snapshotPosition + "}";
        }
    }

    private BundleCapture() {
    }

    /**
     * Capture the newest snapshot on this node, if it is newer than the last one
     * captured.
     *
     * @param clusterDir        the node's cluster directory (holds recording.log)
     * @param srcControlChannel the node archive's UDP control channel
     * @param stagingRoot       persistent staging archive location (real disk)
     * @param bundleRoot        where bundle directories are written
     * @param cluster           cluster name for the manifest
     * @param nodeId            node id for the manifest
     */
    public static Result capture(final File clusterDir, final String srcControlChannel,
                                 final File stagingRoot, final File bundleRoot,
                                 final String cluster, final int nodeId) throws IOException {

        final SnapshotSelection selection = readLatestSnapshot(clusterDir);
        if (selection == null) {
            return Result.skipped("no valid snapshot in recording.log", -1);
        }

        final StagingState state = StagingState.load(stagingRoot);

        if (selection.logPosition <= state.lastBundledPosition) {
            return Result.skipped("snapshot already captured", selection.logPosition);
        }

        // A position that moved BACKWARDS means the source is not the cluster this
        // staging archive has been tracking - a re-form from genesis, or a reseed
        // from a different node. Chaining across that boundary would produce a
        // bundle whose log does not continue its own manifest's previousPosition,
        // and the break would only be discovered during a restore. Refuse instead.
        if (state.lastBundledPosition >= 0 && selection.logPosition < state.lastBundledPosition) {
            throw new IllegalStateException("[BUNDLE] snapshot position "
                    + selection.logPosition + " is BELOW the last captured position "
                    + state.lastBundledPosition + " - the source cluster was re-formed or "
                    + "reseeded. Archive this staging directory and start a new chain; do not "
                    + "extend across the break.");
        }

        //noinspection ResultOfMethodCallIgnored
        bundleRoot.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        stagingRoot.mkdirs();

        final Instant capturedAt = Instant.now();
        final File bundleDir = new File(bundleRoot,
                BUNDLE_STAMP.format(capturedAt) + "-pos-" + selection.logPosition);
        final File snapshotDir = new File(bundleDir, "snapshot");
        final File logDir = new File(bundleDir, "log");
        //noinspection ResultOfMethodCallIgnored
        snapshotDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        logDir.mkdirs();

        final long logBytes;
        final long snapshotBytes;
        final List<Long> stagedSnapshotRecordings = new ArrayList<>();

        try (StagingArchive staging = StagingArchive.launch(stagingRoot)) {
            // Snapshot recordings are COMPLETE, so they replicate whole. There is one
            // per service plus one for the consensus module; a restore needs all of them.
            for (final RecordingLog.Entry entry : selection.snapshotEntries) {
                final long staged = staging.replicate(srcControlChannel, entry.recordingId,
                        Aeron.NULL_VALUE, AeronArchive.NULL_POSITION);
                stagedSnapshotRecordings.add(staged);
                System.out.println("[BUNDLE] replicated snapshot recording "
                        + entry.recordingId + " (serviceId=" + entry.serviceId + ") -> " + staged);
            }

            // The log is LIVE, so it replicates with an explicit stop position -
            // without one this would follow the recording forever and never return.
            // Extending the existing staged recording is what makes the copy resume
            // exactly where the previous bundle ended.
            final long dstLogRecordingId = staging.replicate(srcControlChannel,
                    selection.logRecordingId, state.dstLogRecordingId, selection.logPosition);

            final long stagedStop = staging.stopPosition(dstLogRecordingId);
            if (stagedStop < selection.logPosition) {
                throw new IllegalStateException("[BUNDLE] staged log stops at " + stagedStop
                        + " but the snapshot is at " + selection.logPosition
                        + " - refusing to write a bundle whose log does not reach its snapshot");
            }
            System.out.println("[BUNDLE] replicated log " + selection.logRecordingId
                    + " -> " + dstLogRecordingId + " up to " + selection.logPosition);

            logBytes = copySegments(staging.archiveDir(), dstLogRecordingId,
                    state.lastBundledPosition, selection.logPosition, logDir);

            long snapshot = 0;
            for (final long staged : stagedSnapshotRecordings) {
                snapshot += copyRecordingFiles(staging.archiveDir(), staged, snapshotDir);
            }
            snapshotBytes = snapshot;

            state.dstLogRecordingId = dstLogRecordingId;
        }

        Files.copy(clusterDir.toPath().resolve("recording.log"),
                bundleDir.toPath().resolve("recording-log.txt"),
                StandardCopyOption.REPLACE_EXISTING);

        writeManifest(bundleDir, cluster, nodeId, selection, state.lastBundledPosition,
                capturedAt, logBytes, snapshotBytes, snapshotDir, logDir);

        final long previousPosition = state.lastBundledPosition;
        state.lastBundledPosition = selection.logPosition;
        state.save(stagingRoot);

        return new Result(true, "captured", bundleDir, selection.logPosition,
                previousPosition, logBytes);
    }

    // ---- recording.log ----

    private static final class SnapshotSelection {
        final long logPosition;
        final long logRecordingId;
        final List<RecordingLog.Entry> snapshotEntries;

        SnapshotSelection(final long logPosition, final long logRecordingId,
                          final List<RecordingLog.Entry> snapshotEntries) {
            this.logPosition = logPosition;
            this.logRecordingId = logRecordingId;
            this.snapshotEntries = snapshotEntries;
        }
    }

    /**
     * The newest VALID snapshot group: every entry sharing the highest snapshot
     * position (consensus module plus one per service). An invalidated entry is one
     * Aeron has logically removed and is not a restore point.
     */
    private static SnapshotSelection readLatestSnapshot(final File clusterDir) {
        final List<RecordingLog.Entry> snapshots = new ArrayList<>();
        final long logRecordingId;
        try (RecordingLog recordingLog = new RecordingLog(clusterDir, false)) {
            for (final RecordingLog.Entry entry : recordingLog.entries()) {
                if (entry.type == RecordingLog.ENTRY_TYPE_SNAPSHOT && entry.isValid) {
                    snapshots.add(entry);
                }
            }
            logRecordingId = recordingLog.findLastTermRecordingId();
        }

        if (snapshots.isEmpty() || logRecordingId == Aeron.NULL_VALUE) {
            return null;
        }

        final long latest = snapshots.stream()
                .map(e -> e.logPosition)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        final List<RecordingLog.Entry> group = snapshots.stream()
                .filter(e -> e.logPosition == latest)
                .toList();

        return new SnapshotSelection(latest, logRecordingId, group);
    }

    // ---- staging state ----

    /**
     * Where the chain stands. Kept beside the staging archive because it describes
     * that archive's contents; losing one without the other is what would silently
     * restart the chain.
     */
    static final class StagingState {
        long dstLogRecordingId = Aeron.NULL_VALUE;
        long lastBundledPosition = -1;

        static StagingState load(final File stagingRoot) throws IOException {
            final Path file = stagingRoot.toPath().resolve("bundle-state.properties");
            final StagingState state = new StagingState();
            if (!Files.exists(file)) {
                return state;
            }
            final Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            state.dstLogRecordingId = Long.parseLong(
                    props.getProperty("dstLogRecordingId", String.valueOf(Aeron.NULL_VALUE)));
            state.lastBundledPosition = Long.parseLong(
                    props.getProperty("lastBundledPosition", "-1"));
            return state;
        }

        void save(final File stagingRoot) throws IOException {
            final Properties props = new Properties();
            props.setProperty("dstLogRecordingId", String.valueOf(dstLogRecordingId));
            props.setProperty("lastBundledPosition", String.valueOf(lastBundledPosition));

            // Write-then-rename: a torn state file would either restart the chain or
            // point at a recording that does not exist.
            final Path tmp = stagingRoot.toPath().resolve("bundle-state.properties.tmp");
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "Assets Engine bundle chain state");
            }
            Files.move(tmp, stagingRoot.toPath().resolve("bundle-state.properties"),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---- file extraction ----

    /**
     * Copy the staged log segments covering {@code from..to}.
     *
     * <p>Whole segments, not byte ranges: a segment is the unit Aeron reads back, and
     * a partial one is not replayable. The first bundle of a chain has no lower bound
     * and takes everything the staging archive holds.
     *
     * @return bytes actually written to the bundle (compressed)
     */
    static long copySegments(final File archiveDir, final long recordingId,
                                     final long from, final long to, final File targetDir)
            throws IOException {
        final String prefix = recordingId + "-";
        final File[] files = archiveDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".rec"));
        if (files == null) {
            return 0;
        }

        long bytes = 0;
        for (final File file : files) {
            final long segmentBase = segmentBasePosition(file.getName(), prefix);
            if (segmentBase < 0) {
                continue;
            }
            // A segment belongs to this bundle if any of it lies above the previous
            // bundle's position and its start is below this snapshot's position.
            final long segmentEnd = segmentBase + file.length();
            if (segmentEnd <= from || segmentBase >= to) {
                continue;
            }
            bytes += compressInto(file, targetDir);
        }
        return bytes;
    }

    /** Every {@code .rec} file of a recording; snapshots are copied whole. */
    private static long copyRecordingFiles(final File archiveDir, final long recordingId,
                                           final File targetDir) throws IOException {
        final String prefix = recordingId + "-";
        final File[] files = archiveDir.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".rec"));
        if (files == null) {
            return 0;
        }
        long bytes = 0;
        for (final File file : files) {
            bytes += compressInto(file, targetDir);
        }
        return bytes;
    }

    /**
     * Gzip a recording file into the bundle.
     *
     * <p>Aeron pre-allocates segment files and leaves them full of holes, so a plain
     * copy is a trap that has already cost this project once: the source occupies
     * 36 MB of blocks and the copy lands as 193 MB of real bytes, because copying
     * does not preserve sparseness. Uploading that inflation would multiply the S3
     * bill and the transfer time by the same factor for no data at all.
     *
     * <p>Compressing solves it without needing hole-aware copying: the pre-allocated
     * zeroes collapse to almost nothing, and the genuine log data shrinks too. It
     * also makes bundle size a function of real content, which is what the cost
     * model in the design assumes.
     *
     * @return bytes written
     */
    static long compressInto(final File source, final File targetDir) throws IOException {
        final Path target = targetDir.toPath().resolve(source.getName() + ".gz");
        try (InputStream in = Files.newInputStream(source.toPath());
             var out = new java.util.zip.GZIPOutputStream(
                     Files.newOutputStream(target), 64 * 1024)) {
            in.transferTo(out);
        }
        return Files.size(target);
    }

    /** Aeron names segments {@code <recordingId>-<basePosition>.rec}. */
    private static long segmentBasePosition(final String fileName, final String prefix) {
        final int dot = fileName.lastIndexOf(".rec");
        if (dot < 0) {
            return -1;
        }
        try {
            return Long.parseLong(fileName.substring(prefix.length(), dot));
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    // ---- manifest ----

    /**
     * The manifest is what makes a bundle openable in six months.
     *
     * <p>{@code buildSha} and {@code schemaId} are payload, not metadata: a snapshot
     * serialized by one build may not deserialize in another, and log semantics move.
     * A replay tool must check them and refuse a mismatched tree.
     */
    private static void writeManifest(final File bundleDir, final String cluster, final int nodeId,
                                      final SnapshotSelection selection, final long previousPosition,
                                      final Instant capturedAt, final long logBytes,
                                      final long snapshotBytes,
                                      final File snapshotDir, final File logDir) throws IOException {
        final String json = "{\n"
                + "  \"cluster\": \"" + cluster + "\",\n"
                + "  \"nodeId\": " + nodeId + ",\n"
                + "  \"snapshotPosition\": " + selection.logPosition + ",\n"
                + "  \"previousPosition\": " + previousPosition + ",\n"
                + "  \"capturedAt\": \"" + capturedAt + "\",\n"
                + "  \"buildSha\": \"" + buildSha() + "\",\n"
                + "  \"schemaId\": " + BookDeltaEncoder.SCHEMA_ID + ",\n"
                + "  \"schemaVersion\": " + BookDeltaEncoder.SCHEMA_VERSION + ",\n"
                + "  \"aeronVersion\": \"" + AeronVersion.VERSION + "\",\n"
                + "  \"logBytes\": " + logBytes + ",\n"
                + "  \"snapshotBytes\": " + snapshotBytes + ",\n"
                + "  \"compression\": \"gzip\",\n"
                + "  \"sha256\": {\n"
                + "    \"snapshot\": \"" + digestDirectory(snapshotDir) + "\",\n"
                + "    \"log\": \"" + digestDirectory(logDir) + "\"\n"
                + "  }\n"
                + "}\n";

        Files.writeString(bundleDir.toPath().resolve("manifest.json"), json,
                StandardCharsets.UTF_8);
    }

    /**
     * The build that produced this bundle, read from the running jar's manifest.
     *
     * <p>Returns the literal {@code "unknown"} when the jar was not stamped, rather
     * than inventing a value. A bundle that cannot name its build is one a replay
     * must refuse, and saying so is the point.
     */
    static String buildSha() {
        try {
            final Class<?> self = BundleCapture.class;
            final String path = self.getResource(self.getSimpleName() + ".class").toString();
            if (!path.startsWith("jar:")) {
                return "unknown";
            }
            final String manifestPath = path.substring(0, path.lastIndexOf('!') + 1)
                    + "/META-INF/MANIFEST.MF";
            try (InputStream in = new java.net.URL(manifestPath).openStream()) {
                final Attributes attrs = new Manifest(in).getMainAttributes();
                final String sha = attrs.getValue("Build-Sha");
                return sha == null || sha.isBlank() ? "unknown" : sha;
            }
        } catch (final Exception e) {
            return "unknown";
        }
    }

    /**
     * A single digest over every file in a directory, name-ordered so it is stable
     * across filesystems that do not agree on listing order.
     */
    private static String digestDirectory(final File dir) throws IOException {
        final File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return "";
        }
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));

        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final File file : files) {
                digest.update(file.getName().getBytes(StandardCharsets.UTF_8));
                try (InputStream in = Files.newInputStream(file.toPath());
                     DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                    final byte[] buffer = new byte[64 * 1024];
                    while (digestIn.read(buffer) != -1) {
                        // digest consumes the stream
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- CLI ----

    /**
     * Invoked per node by the admin gateway, between the snapshot and the reclaim.
     *
     * <p>Usage: {@code BundleCapture <clusterDir> <srcControlChannel> <stagingRoot>
     * <bundleRoot> <cluster> <nodeId>}
     */
    public static void main(final String[] args) {
        if (args.length < 6) {
            System.err.println("Usage: BundleCapture <clusterDir> <srcControlChannel> "
                    + "<stagingRoot> <bundleRoot> <cluster> <nodeId>");
            System.exit(2);
        }

        try {
            final Result result = capture(new File(args[0]), args[1], new File(args[2]),
                    new File(args[3]), args[4], Integer.parseInt(args[5]));
            System.out.println("[BUNDLE] " + result);
            if (!result.captured) {
                System.exit(0);
            }
        } catch (final Exception e) {
            System.err.println("[BUNDLE] FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
