// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * The embedded driver's client liveness and publication unblock timeouts are settings with defaults
 * ({@code TRANSPORT_DRIVER_CLIENT_LIVENESS_MS}, {@code TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS}), parsed and range
 * checked at boot; an unblock timeout at or below the liveness is refused (Aeron's rule).
 */
public class DriverTimeoutsTest {

    private static Function<String, String> env(final String... pairs) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m::get;
    }

    private static String refusal(final Function<String, String> env) {
        return assertThrows(IllegalArgumentException.class, () -> TransportConfig.DriverTimeouts.parse(env))
                .getMessage();
    }

    @Test
    public void theDefaultsKeepFiveAndTenSeconds() {
        final TransportConfig.DriverTimeouts t = TransportConfig.DriverTimeouts.parse(env());
        assertEquals(5_000L, t.clientLivenessMs());
        assertEquals(10_000L, t.publicationUnblockMs());
    }

    @Test
    public void bothAreSettingsWithinTheirRanges() {
        final TransportConfig.DriverTimeouts t = TransportConfig.DriverTimeouts.parse(env(
                "TRANSPORT_DRIVER_CLIENT_LIVENESS_MS", "15000", "TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS", "20000"));
        assertEquals(15_000L, t.clientLivenessMs());
        assertEquals(20_000L, t.publicationUnblockMs());
        assertEquals("TRANSPORT_DRIVER_CLIENT_LIVENESS_MS must be 1000..600000 ms: 500",
                refusal(env("TRANSPORT_DRIVER_CLIENT_LIVENESS_MS", "500")));
        assertEquals("TRANSPORT_DRIVER_CLIENT_LIVENESS_MS is not an integer: 5s",
                refusal(env("TRANSPORT_DRIVER_CLIENT_LIVENESS_MS", "5s")));
        assertEquals("TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS must be 2000..1200000 ms: 1200001",
                refusal(env("TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS", "1200001")));
    }

    @Test
    public void theUnblockTimeoutMustExceedTheLiveness() {
        assertEquals("TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS 10000 must exceed TRANSPORT_DRIVER_CLIENT_LIVENESS_MS"
                + " 12000 (Aeron refuses the driver otherwise)", refusal(env("TRANSPORT_DRIVER_CLIENT_LIVENESS_MS", "12000")));
    }
}
