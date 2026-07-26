// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.Aeron;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The parts of bundle capture that do not need a live cluster.
 *
 * <p>Replication itself is verified against a running node (it is the one thing a
 * unit test cannot fake honestly); what is pinned here is the arithmetic and the
 * bookkeeping around it, where a silent mistake produces a bundle that looks fine
 * and cannot be restored.
 */
public class BundleCaptureTest {

    @Rule
    public final TemporaryFolder tmpFolder = new TemporaryFolder();

    private Path tmp() {
        return tmpFolder.getRoot().toPath();
    }

    /** Aeron names segments {@code <recordingId>-<basePosition>.rec}. */
    private static void segment(final File dir, final long recordingId, final long basePosition,
                                final int length) throws IOException {
        final byte[] content = new byte[length];
        // Non-zero so a copy that silently produced an empty file would be caught.
        java.util.Arrays.fill(content, (byte) (basePosition % 251 + 1));
        Files.write(dir.toPath().resolve(recordingId + "-" + basePosition + ".rec"), content);
    }

    private static List<String> names(final File dir) {
        final String[] listed = dir.list();
        assertTrue("directory did not list", listed != null);
        java.util.Arrays.sort(listed);
        return List.of(listed);
    }

    // ---- segment selection ----

    // A bundle covers previousPosition..snapshotPosition. Including a segment that
    // sits entirely below the previous bundle re-uploads history every round;
    // excluding one that straddles the boundary leaves a hole in the chain, and the
    // hole is only discovered during a restore.
    @Test
    public void selectsOnlySegmentsOverlappingTheRange() throws IOException {
        final Path tmp = tmp();
        final File archive = tmp.resolve("archive").toFile();
        final File out = tmp.resolve("out").toFile();
        assertTrue(archive.mkdirs() && out.mkdirs());

        segment(archive, 2, 0, 1024);      // fully below the range
        segment(archive, 2, 1024, 1024);   // straddles the lower bound
        segment(archive, 2, 2048, 1024);   // inside
        segment(archive, 2, 4096, 1024);   // fully above the range
        segment(archive, 9, 2048, 1024);   // a different recording

        BundleCapture.copySegments(archive, 2, 1500, 3000, out);

        assertEquals(List.of("2-1024.rec.gz", "2-2048.rec.gz"), names(out));
    }

    // The first bundle of a chain has no lower bound: previousPosition is -1 and it
    // must take everything the staging archive holds, or the chain starts with a gap.
    @Test
    public void firstBundleTakesEverythingBelowTheSnapshot() throws IOException {
        final Path tmp = tmp();
        final File archive = tmp.resolve("archive").toFile();
        final File out = tmp.resolve("out").toFile();
        assertTrue(archive.mkdirs() && out.mkdirs());

        segment(archive, 2, 0, 1024);
        segment(archive, 2, 1024, 1024);

        BundleCapture.copySegments(archive, 2, -1, 2048, out);

        assertEquals(List.of("2-0.rec.gz", "2-1024.rec.gz"), names(out));
    }

    @Test
    public void reportsBytesActuallyWritten() throws IOException {
        final Path tmp = tmp();
        final File archive = tmp.resolve("archive").toFile();
        final File out = tmp.resolve("out").toFile();
        assertTrue(archive.mkdirs() && out.mkdirs());
        segment(archive, 2, 0, 4096);

        final long reported = BundleCapture.copySegments(archive, 2, -1, 4096, out);

        assertEquals("reported size must be what lands in the bundle, since that is what gets uploaded",
                Files.size(out.toPath().resolve("2-0.rec.gz")), reported);
    }

    // ---- compression ----

    // Aeron pre-allocates segments and leaves them full of holes. A plain copy
    // inflated a 36 MB staging directory into a 193 MB bundle in production;
    // compressing is what keeps the upload proportional to real content.
    @Test
    public void compressesAndRoundTrips() throws IOException {
        final Path tmp = tmp();
        final File archive = tmp.resolve("archive").toFile();
        final File out = tmp.resolve("out").toFile();
        assertTrue(archive.mkdirs() && out.mkdirs());

        final byte[] content = new byte[512 * 1024]; // mostly zeroes, like a fresh segment
        content[0] = 42;
        content[content.length - 1] = 7;
        final File source = archive.toPath().resolve("2-0.rec").toFile();
        Files.write(source.toPath(), content);

        final long written = BundleCapture.compressInto(source, out);

        final Path gz = out.toPath().resolve("2-0.rec.gz");
        assertTrue("compressed file missing", Files.exists(gz));
        assertEquals(Files.size(gz), written);
        assertTrue("pre-allocated emptiness must collapse, got " + written + " of " + content.length,
                written < content.length / 10);

        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            assertArrayEquals("a bundle that does not decompress to the original is not a backup",
                    content, in.readAllBytes());
        }
    }

    // ---- chain state ----

    @Test
    public void stateRoundTrips() throws IOException {
        final Path tmp = tmp();
        final File root = tmp.toFile();

        final BundleCapture.StagingState fresh = BundleCapture.StagingState.load(root);
        assertEquals(Aeron.NULL_VALUE, fresh.dstLogRecordingId);
        assertEquals("a fresh chain has no previous position", -1, fresh.lastBundledPosition);

        fresh.dstLogRecordingId = 2;
        fresh.lastBundledPosition = 1314884064L;
        fresh.save(root);

        final BundleCapture.StagingState reloaded = BundleCapture.StagingState.load(root);
        assertEquals(2, reloaded.dstLogRecordingId);
        assertEquals(1314884064L, reloaded.lastBundledPosition);
    }

    // The state file names the recording the next bundle extends. A torn write
    // would either restart the chain or point at a recording that does not exist,
    // so it is written and renamed rather than edited in place.
    @Test
    public void stateSaveLeavesNoTemporaryBehind() throws IOException {
        final Path tmp = tmp();
        final File root = tmp.toFile();
        final BundleCapture.StagingState state = new BundleCapture.StagingState();
        state.dstLogRecordingId = 5;
        state.lastBundledPosition = 100;
        state.save(root);

        assertFalse("temporary state file survived the rename",
                Files.exists(tmp.resolve("bundle-state.properties.tmp")));
        assertTrue(Files.exists(tmp.resolve("bundle-state.properties")));
    }

    // ---- provenance ----

    // A bundle that cannot name the build that produced it is one a replay must
    // refuse. Saying "unknown" is the point; inventing a value would let a restore
    // run against a tree that never produced this state.
    @Test
    public void buildShaIsUnknownRatherThanGuessedOutsideAJar() {
        assertEquals("running from classes, not a stamped jar, must report unknown",
                "unknown", BundleCapture.buildSha());
    }
}
