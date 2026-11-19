// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.TransportConfig;
import io.aeron.driver.MediaDriver;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The embedded driver takes its client liveness and publication unblock timeouts from the settings. */
public class EmbeddedDriverTimeoutsTest {

    @Test
    public void theSettingsReachTheEmbeddedDriverContext() {
        final MediaDriver.Context c = AeronCluster.embeddedDriverTimeouts(new MediaDriver.Context(),
                new TransportConfig.DriverTimeouts(15_000, 20_000));
        assertEquals(15_000_000_000L, c.clientLivenessTimeoutNs());
        assertEquals(20_000_000_000L, c.publicationUnblockTimeoutNs());
        final MediaDriver.Context d = AeronCluster.embeddedDriverTimeouts(new MediaDriver.Context(),
                TransportConfig.DriverTimeouts.defaults());
        assertEquals(5_000_000_000L, d.clientLivenessTimeoutNs());
        assertEquals(10_000_000_000L, d.publicationUnblockTimeoutNs());
    }
}
