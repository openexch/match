// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.BuildInfo;
import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * The node's {@code /health} and {@code /ready} bodies end with the build it runs: the service hands its build
 * identity to the kit's endpoint at onStart.
 */
public class NodeEndpointBuildIdentityTest {

    @After
    public void clear() {
        System.clearProperty("node.id");
    }

    private static String body(final int port, final String path) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
        c.setConnectTimeout(5_000);
        c.setReadTimeout(5_000);
        c.getResponseCode();
        try (InputStream in = c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void healthAndReadyEndWithTheBuildTheServiceRuns() throws Exception {
        final int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        // The metrics port is 9500 + node id when METRICS_PORT is unset; pick a node id that lands on a free port.
        System.setProperty("node.id", Integer.toString(port - 9_500));
        final MeStubCluster c = new MeStubCluster();
        final AppClusteredService s = new AppClusteredService();
        s.failFast = () -> { throw new AssertionError("failFast"); };
        s.buildInfo = new BuildInfo("1.2.3-test", "abc1234");
        try {
            s.onStart(c, null);
            final String health = body(port, "/health");
            final String ready = body(port, "/ready");
            assertTrue("health body: " + health, health.strip().endsWith(" version=1.2.3-test build=abc1234"));
            assertTrue("ready body: " + ready, ready.strip().endsWith(" version=1.2.3-test build=abc1234"));
            assertFalse("the build token is build=, never a second commit=", health.contains("commit=abc1234"));
        } finally {
            s.onTerminate(c);
        }
    }
}
