// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.auth;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.HexFormat;

import static org.junit.Assert.assertEquals;

/**
 * RC2 S6 9.2 (FIX-10): the cluster-kit pinned in match carries the S6 authority and helpers, with the bytes the
 * C++ client and the fixture produce. Reflection only, so the same test compiles against a kit without them
 * and fails at run time (ClassNotFoundException) on the pre-S6 pin.
 */
public class KitPinGuardTest {

    private static final String SECRET = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    public void theKitEncodesTheGoldenCredentialAndReadsTheGoldenPrincipal() throws Exception {
        final Class<?> credentials = Class.forName("com.openexchange.cluster.auth.ClusterCredentials");
        final Object c = credentials.getMethod("fromSpec", String.class).invoke(null, "6:FORWARDER:" + SECRET);
        final byte[] encoded = (byte[]) credentials.getMethod("encode").invoke(c);
        assertEquals("010206000000" + SECRET, HexFormat.of().formatHex(encoded));

        final Class<?> principal = Class.forName("com.openexchange.cluster.auth.ClusterPrincipal");
        final Method classCodeOf = principal.getMethod("classCodeOf", byte[].class);
        final byte[] forwarderAnyUser = HexFormat.of().parseHex("01020100060000000000");
        assertEquals(2, classCodeOf.invoke(null, (Object) forwarderAnyUser));

        Class.forName("com.openexchange.cluster.auth.ClusterAuthenticator");
        Class.forName("com.openexchange.cluster.auth.ClassAuthorisationService");
        Class.forName("com.openexchange.cluster.SnapshotImageReader");
        final Class<?> term = Class.forName("com.openexchange.cluster.TermTimerId");
        assertEquals(1_000_000_000_000L + 3_000_000L,
                term.getMethod("of", long.class, long.class).invoke(null, 1_000_000_000_000L, 3L));
    }
}
