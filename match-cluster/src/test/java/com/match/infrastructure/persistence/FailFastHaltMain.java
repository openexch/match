// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import java.io.File;

/**
 * Entry point for {@link FailFastHaltProcessTest}'s forked JVM. Registers a shutdown hook that
 * writes a marker file, then terminates via the requested mode:
 *
 * <ul>
 *   <li>{@code halt} — the production failFast default
 *       ({@link AppClusteredService#failFastHalt()}): must exit with code 1 WITHOUT running the
 *       hook (no marker). This is the #223 fix under test.</li>
 *   <li>{@code exit} — {@code System.exit(1)}: control run proving the marker mechanism works
 *       (the hook runs, the marker appears). Safe in this bare JVM — there is no Aeron teardown
 *       hook here to deadlock on.</li>
 * </ul>
 */
public final class FailFastHaltMain {

    private FailFastHaltMain() {
    }

    public static void main(final String[] args) {
        final String mode = args[0];
        final File marker = new File(args[1]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                marker.createNewFile();
            } catch (Exception ignore) {
                // A failed marker write surfaces as a missing marker in the control assertion.
            }
        }, "marker-hook"));

        if ("halt".equals(mode)) {
            AppClusteredService.failFastHalt(); // the exact production seam default
        } else if ("exit".equals(mode)) {
            System.exit(1);
        }
        throw new IllegalStateException("unreachable: mode=" + mode);
    }
}
