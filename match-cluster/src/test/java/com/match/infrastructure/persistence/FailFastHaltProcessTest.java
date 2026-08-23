// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * #223: the failFast seam's production default must be a HOOKLESS hard stop. System.exit(1)
 * from the cluster service thread deadlocks: the JVM's shutdown hooks (the Aeron teardown
 * chain) await the service thread that is sitting inside exit() — observed live 2026-08-23,
 * all three nodes zombified on the first adopt (alive, reporting HEALTHY, processing nothing,
 * TERM-immune). {@link Runtime#halt(int)} skips hooks by definition; this test proves the seam
 * default {@link AppClusteredService#failFastHalt()} actually does so, in a real forked JVM: a
 * shutdown hook writes a marker file, and the marker must NOT appear while the exit code is
 * still 1. The exit-mode control run proves the marker mechanism itself works, so the halt
 * assertion cannot pass vacuously.
 */
public class FailFastHaltProcessTest {

    @Test
    public void productionFailFastDefaultSkipsShutdownHooks() throws Exception {
        final Result halt = runForked("halt");
        assertEquals("forked JVM must die with exit code 1", 1, halt.exitCode);
        assertFalse("halt must NOT run shutdown hooks (a marker means the hook ran)",
                halt.markerExists);
    }

    @Test
    public void controlSystemExitRunsShutdownHooks() throws Exception {
        // Control: proves the marker hook is live in this harness. In the bare forked JVM
        // exit() is safe — there is no Aeron hook to deadlock on.
        final Result exit = runForked("exit");
        assertEquals("forked JVM must die with exit code 1", 1, exit.exitCode);
        assertTrue("System.exit must run the marker hook (harness self-check)",
                exit.markerExists);
    }

    private record Result(int exitCode, boolean markerExists) {
    }

    private static Result runForked(final String mode) throws Exception {
        final File marker = new File(
                Files.createTempDirectory("failfast-halt").toFile(), mode + ".marker");
        final File java = new File(new File(System.getProperty("java.home"), "bin"), "java");
        final Process p = new ProcessBuilder(
                java.getAbsolutePath(),
                "-cp", System.getProperty("java.class.path"),
                FailFastHaltMain.class.getName(),
                mode,
                marker.getAbsolutePath())
                // No pipes: the child must never block on a full pipe, and its stderr lands in
                // the surefire output for debuggability.
                .inheritIO()
                .start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("forked JVM (" + mode + ") did not terminate within 60s");
        }
        // The JVM only reports termination after any shutdown hooks have finished, so the
        // marker's presence is final once waitFor returns.
        return new Result(p.exitValue(), marker.exists());
    }
}
