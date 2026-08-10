// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Guards the {@code --aeron-dir} contract at the parse boundary: the flag decides whether
 * the generator launches its own embedded media driver or attaches to one it does not own
 * (the bench rig's pinned busy-spin aeronmd). The failure mode is silent — a dropped flag
 * still produces a working run, just with an embedded driver quietly back inside the
 * measurement.
 */
public class LoadGeneratorArgsTest {

    @Test
    public void aeronDirFlagCarriesTheExternalDriverDirectoryIntoTheConfig() {
        final LoadConfig config = LoadGenerator.parseArgs(new String[] {
            "--rate", "1000",
            "--aeron-dir", "/dev/shm/aeron-bench",
            "--ultra",  // must coexist: the bench rig runs exactly this combination
        });

        assertEquals("/dev/shm/aeron-bench", config.getAeronDir());
        assertEquals("neighbouring flags must still parse", 1000, config.getTargetOrdersPerSecond());
    }

    @Test
    public void withoutTheFlagTheConfigAsksForAnEmbeddedDriver() {
        final LoadConfig config = LoadGenerator.parseArgs(new String[] {"--rate", "1000"});

        assertNull("null means launch the embedded driver, exactly as before", config.getAeronDir());
    }

    @Test
    public void intervalLogFlagIsAcceptedAlongsideTheBenchFlags() {
        // The parse switch exits the JVM on unknown flags, so a dropped case here kills the
        // whole bench invocation, not just the ILOG lines.
        final LoadConfig config = LoadGenerator.parseArgs(new String[] {
            "--rate", "1000",
            "--interval-log",
            "--no-ui",  // the bench path runs exactly this combination
            "--aeron-dir", "/dev/shm/aeron-bench",
        });

        assertEquals("neighbouring flags must still parse", 1000, config.getTargetOrdersPerSecond());
        assertEquals("/dev/shm/aeron-bench", config.getAeronDir());
    }
}
