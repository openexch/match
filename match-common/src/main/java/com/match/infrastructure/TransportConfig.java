// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import io.aeron.CncFileDescriptor;
import io.aeron.CommonContext;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.BusySpinIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import java.io.File;
import java.util.function.Supplier;

/**
 * Transport-layer configuration for the Aeron media driver and channels.
 *
 * All values are read env-var-first with a system-property fallback, following the
 * convention established in AeronCluster (CLUSTER_ADDRESSES / cluster.addresses etc.).
 *
 * The Java code is deliberately agnostic to HOW packets reach the wire: in EXTERNAL
 * driver mode the media driver runs as a separate process (optionally wrapped in
 * Onload/VMA via LD_PRELOAD for kernel bypass) and this JVM only talks to it over
 * shared-memory IPC through {@link #aeronDir(int)}. Nothing here may assume kernel
 * sockets vs bypass.
 *
 * | Env var                | System property        | Default                          |
 * |------------------------|------------------------|----------------------------------|
 * | TRANSPORT_DRIVER_MODE  | transport.driver.mode  | embedded                         |
 * | TRANSPORT_IDLE_MODE    | transport.idle.mode    | busy_spin                        |
 * | AERON_DIR              | aeron.driver.dir       | &lt;aeron-default&gt;-&lt;nodeId&gt;-driver  |
 * | TRANSPORT_INTERFACE    | transport.interface    | (unset: bind decided by OS)      |
 * | TRANSPORT_MTU          | transport.mtu          | 8192                             |
 * | TRANSPORT_TERM_LENGTH  | transport.term.length  | 16m                              |
 * | TRANSPORT_LOG_TERM_LENGTH | transport.log.term.length | 64m                       |
 */
public final class TransportConfig {
    private static final Logger log = Logger.getLogger(TransportConfig.class);

    /** How the Aeron media driver is provided to this JVM. */
    public enum DriverMode {
        /** Media driver runs as a separate process; connect via shared-memory IPC (prod). */
        EXTERNAL,
        /** Media driver is launched inside this JVM (dev / single-process convenience). */
        EMBEDDED
    }

    /** Idle strategy profile for driver-facing agents (consensus, containers). */
    public enum IdleMode {
        /** Lowest latency, burns a core per agent (prod on isolated cores). */
        BUSY_SPIN,
        /** Spin, then yield, then park (dev laptops, CI). */
        BACKOFF
    }

    /** How long a client polls for an external driver's cnc.dat before giving up. */
    public static final long EXTERNAL_DRIVER_TIMEOUT_MS = 30_000;

    private TransportConfig() {}

    /**
     * Driver mode for this process. Defaults to EMBEDDED so existing deployments are
     * unaffected until they opt in with TRANSPORT_DRIVER_MODE=external.
     *
     * @return the configured driver mode.
     */
    public static DriverMode driverMode() {
        final String value = envOrProp("TRANSPORT_DRIVER_MODE", "transport.driver.mode", "embedded");
        try {
            return DriverMode.valueOf(value.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            // A typo silently falling back to the wrong mode would be far worse than failing fast.
            throw new IllegalArgumentException(
                    "Invalid TRANSPORT_DRIVER_MODE/transport.driver.mode '" + value +
                    "' (expected external|embedded)");
        }
    }

    /**
     * Idle strategy profile. Defaults to BUSY_SPIN (today's behavior on pinned cores).
     *
     * @return the configured idle mode.
     */
    public static IdleMode idleMode() {
        final String value = envOrProp("TRANSPORT_IDLE_MODE", "transport.idle.mode", "busy_spin");
        try {
            return IdleMode.valueOf(value.trim().toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid TRANSPORT_IDLE_MODE/transport.idle.mode '" + value +
                    "' (expected busy_spin|backoff)");
        }
    }

    /**
     * Supplier of idle strategies matching {@link #idleMode()}. A supplier because every
     * Aeron agent (consensus module, service containers, driver threads in embedded mode)
     * needs its own instance.
     *
     * @return idle strategy supplier.
     */
    public static Supplier<IdleStrategy> idleStrategySupplier() {
        return idleMode() == IdleMode.BUSY_SPIN
                ? BusySpinIdleStrategy::new
                : BackoffIdleStrategy::new;
    }

    /**
     * The aeron.dir this process should use to reach its media driver. The default matches
     * both ClusterConfig's embedded-driver naming and the admin-gateway housekeeping pattern
     * (/dev/shm/aeron-&lt;user&gt;-&lt;nodeId&gt;-driver), so EXTERNAL and EMBEDDED modes share one layout.
     *
     * @param nodeId cluster member id used in the default directory name.
     * @return aeron directory path.
     */
    public static String aeronDir(final int nodeId) {
        return envOrProp("AERON_DIR", "aeron.driver.dir",
                CommonContext.getAeronDirectoryName() + "-" + nodeId + "-driver");
    }

    /**
     * Optional network interface (address or address/prefix) for UDP channels. Unset by
     * default: single-NIC hosts and loopback dev clusters need no explicit bind.
     *
     * @return interface spec or null when not configured.
     */
    public static String networkInterface() {
        final String value = envOrProp("TRANSPORT_INTERFACE", "transport.interface", "");
        return value.isEmpty() ? null : value;
    }

    /**
     * Channel MTU. Default 8192 preserves current behavior; requires loopback or jumbo
     * frames end-to-end (see the warning in AeronCluster).
     *
     * @return MTU in bytes.
     */
    public static int mtuLength() {
        return Integer.parseInt(envOrProp("TRANSPORT_MTU", "transport.mtu", "8192"));
    }

    /**
     * Term buffer length for UDP channels, in Aeron URI syntax (e.g. "16m").
     *
     * @return term length string.
     */
    public static String termLength() {
        return envOrProp("TRANSPORT_TERM_LENGTH", "transport.term.length", "16m");
    }

    /**
     * Term buffer length for the cluster LOG channel (consensus log fan-out and
     * follower catch-up replay), in Aeron URI syntax. Separate from
     * {@link #termLength()} because the log channel never goes through
     * {@link #udpChannel(String)}: without an explicit override, Aeron's own
     * LOG_CHANNEL_DEFAULT (term-length=64m) applies regardless of
     * TRANSPORT_TERM_LENGTH, and every catch-up replay maps and zero-faults a
     * fresh non-sparse 3-term buffer — 192MB at 64m — which starves small
     * hosts during elections (oms#73). The default preserves prod behavior.
     *
     * @return log channel term length string.
     */
    public static String logTermLength() {
        return envOrProp("TRANSPORT_LOG_TERM_LENGTH", "transport.log.term.length", "64m");
    }

    /**
     * Build a UDP channel URI with the configured term-length, MTU and optional interface
     * applied consistently. Accepts a base with or without existing URI params, e.g.
     * "aeron:udp" or "aeron:udp?endpoint=host:port".
     *
     * @param base channel URI to extend.
     * @return channel URI with transport params appended.
     */
    public static String udpChannel(final String base) {
        final StringBuilder sb = new StringBuilder(base);
        sb.append(base.indexOf('?') >= 0 ? '|' : '?');
        sb.append("term-length=").append(termLength());
        sb.append("|mtu=").append(mtuLength());
        final String iface = networkInterface();
        if (iface != null) {
            sb.append("|interface=").append(iface);
        }
        return sb.toString();
    }

    /**
     * Block until an external media driver is up (its cnc.dat exists) or the timeout
     * elapses. Called before launching Aeron components in EXTERNAL mode so a missing
     * driver produces one actionable error instead of a hang or a cryptic
     * DriverTimeoutException.
     *
     * @param aeronDir  directory the driver was configured with.
     * @param timeoutMs how long to wait.
     */
    public static void awaitExternalDriver(final String aeronDir, final long timeoutMs) {
        final File cncFile = new File(aeronDir, CncFileDescriptor.CNC_FILE);
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cncFile.exists()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException(
                        "No external media driver found at " + aeronDir + " after " + timeoutMs +
                        "ms. Start it with deploy/media-driver/launch-driver.sh --instance node<N>, " +
                        "or unset TRANSPORT_DRIVER_MODE to run with an embedded driver.");
            }
            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for external media driver at " + aeronDir, e);
            }
        }
        log.info("External media driver detected at %s", aeronDir);
    }

    private static String envOrProp(final String envName, final String propName, final String defaultValue) {
        final String env = System.getenv(envName);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return System.getProperty(propName, defaultValue);
    }
}
