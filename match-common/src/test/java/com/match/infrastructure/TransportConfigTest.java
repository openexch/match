// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pins the idle-strategy and driver-threading knobs of {@link TransportConfig}.
 *
 * <p>TransportConfig reads env-first, then the system property {@code KEY} lower-cased with
 * dots. Env vars can't be set from a plain JUnit run, so these drive the system-property
 * fallback and every property is cleared in {@link #tearDown()}. The default-value tests are
 * skipped when the matching env var is set, so a developer who exports TRANSPORT_IDLE_MODE
 * doesn't see a spurious failure.</p>
 */
public class TransportConfigTest {

    @After
    public void tearDown() {
        System.clearProperty("transport.idle.mode");
        System.clearProperty("transport.driver.threading");
    }

    @Test
    public void idleDefaultsToBusySpin() {
        if (System.getenv("TRANSPORT_IDLE_MODE") != null) {
            return; // env override in effect; the default can't be observed
        }
        assertEquals(TransportConfig.IdleMode.BUSY_SPIN, TransportConfig.idleMode());
        assertTrue(TransportConfig.idleStrategySupplier().get() instanceof BusySpinIdleStrategy);
    }

    @Test
    public void backoffIdleMode() {
        System.setProperty("transport.idle.mode", "backoff");
        assertEquals(TransportConfig.IdleMode.BACKOFF, TransportConfig.idleMode());
        assertTrue(TransportConfig.idleStrategySupplier().get() instanceof BackoffIdleStrategy);
    }

    @Test
    public void sleepIdleModeParksInsteadOfSpinning() {
        System.setProperty("transport.idle.mode", "sleep");
        assertEquals(TransportConfig.IdleMode.SLEEP, TransportConfig.idleMode());
        assertTrue(TransportConfig.idleStrategySupplier().get() instanceof SleepingMillisIdleStrategy);
    }

    @Test
    public void idleModeIsCaseInsensitive() {
        System.setProperty("transport.idle.mode", "Sleep");
        assertEquals(TransportConfig.IdleMode.SLEEP, TransportConfig.idleMode());
    }

    @Test
    public void invalidIdleModeThrows() {
        System.setProperty("transport.idle.mode", "turbo");
        try {
            TransportConfig.idleMode();
            fail("expected IllegalArgumentException for an unknown idle mode");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("busy_spin|backoff|sleep"));
        }
    }

    @Test
    public void threadingDefaultsToDedicated() {
        if (System.getenv("TRANSPORT_DRIVER_THREADING") != null) {
            return;
        }
        assertEquals(TransportConfig.DriverThreading.DEDICATED, TransportConfig.driverThreadingMode());
    }

    @Test
    public void sharedThreading() {
        System.setProperty("transport.driver.threading", "shared");
        assertEquals(TransportConfig.DriverThreading.SHARED, TransportConfig.driverThreadingMode());
    }

    @Test
    public void sharedNetworkThreading() {
        System.setProperty("transport.driver.threading", "shared_network");
        assertEquals(TransportConfig.DriverThreading.SHARED_NETWORK, TransportConfig.driverThreadingMode());
    }

    @Test
    public void invalidThreadingThrows() {
        System.setProperty("transport.driver.threading", "hyperthreaded");
        try {
            TransportConfig.driverThreadingMode();
            fail("expected IllegalArgumentException for an unknown threading mode");
        } catch (final IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dedicated|shared|shared_network"));
        }
    }
}
