// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.Engine;
import com.openexchange.cluster.BuildInfo;
import org.agrona.ExpandableArrayBuffer;
import org.junit.After;
import org.junit.Test;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

/**
 * S4 T3 (match, through a real service): {@code /health} and {@code /ready} carry the capability tokens after
 * {@code build=}; the old body is a prefix of the new one; before anything is recorded the recorded values read 0;
 * after an apply (here the restore of an image that recorded a behaviour-2 config) they read the applied values.
 */
public class NodeEndpointCapabilitiesServiceTest {

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

    /** An image of a deferred engine after a behaviour-2 FRESH (config, identity and ceiling recorded). */
    private static byte[] behaviourTwoImage() {
        try (BehaviourTwoHarness h = new BehaviourTwoHarness(Engine.deferredUntilConfig())) {
            h.apply(BehaviourTwoHarness.fresh2(), 64L);
            final ExpandableArrayBuffer b = new ExpandableArrayBuffer();
            final int len = SnapshotCodec.serialize(h.engine, 0L, b);
            return java.util.Arrays.copyOf(b.byteArray(), len);
        }
    }

    @Test
    public void theCapabilityTokensFollowBuildAndTheRecordedValuesFollowTheApply() throws Exception {
        final int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        final MeStubCluster c = new MeStubCluster();
        final AppClusteredService s = new AppClusteredService();
        s.nodeEndpointPort(port);
        s.failFast = () -> { throw new AssertionError("failFast"); };
        s.failFastIncompatible = () -> { throw new AssertionError("failFastIncompatible"); };
        s.buildInfo = new BuildInfo("1.2.3-test", "abc1234");
        try {
            s.onStart(c, null);
            final String constant = " compat=g1.1 wire=9-12 behaviors=1,2 layouts=1,2";
            for (final String path : new String[] {"/health", "/ready"}) {
                final String b = body(port, path).strip();
                final int build = b.indexOf(" version=1.2.3-test build=abc1234");
                assertTrue(path + ": " + b, build > 0);
                assertTrue(path + " old body is a prefix: " + b, b.startsWith(b.substring(0, build)
                        + " version=1.2.3-test build=abc1234 compat="));
                assertTrue(path + ": " + b, b.endsWith(" version=1.2.3-test build=abc1234" + constant
                        + " recorded_behavior=0 recorded_wire_max=0 recorded_genesis=0 driver=embedded"));
            }
            assertTrue(s.processSnapshotPayload(new org.agrona.concurrent.UnsafeBuffer(behaviourTwoImage()), 0,
                    behaviourTwoImage().length));
            final String after = body(port, "/ready").strip();
            assertTrue(after, after.endsWith(constant + " recorded_behavior=2 recorded_wire_max=12 recorded_genesis=1"
                    + " driver=embedded"));
            final String metrics = body(port, "/metrics");
            for (final String g : new String[] {"match_member_compat_generation 1", "match_member_compat_level 1",
                    "match_supported_min_wire_version 9", "match_supported_max_wire_version 12",
                    "match_supported_behavior_versions 3"}) {
                assertTrue(g + " in " + metrics, metrics.contains("\n" + g + "\n"));
            }
            assertTrue("the launch-mode gauge is gone", !metrics.contains("match_engine_from_config"));
        } finally {
            s.onTerminate(c);
        }
    }
}
