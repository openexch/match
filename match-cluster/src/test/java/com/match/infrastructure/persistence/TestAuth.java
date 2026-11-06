// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.auth.MatchAuth;
import com.openexchange.cluster.auth.ClusterCredentials;
import com.openexchange.cluster.auth.ClusterPrincipal;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.cluster.service.ClientSession;
import io.aeron.security.CredentialsSupplier;
import org.agrona.DirectBuffer;

import java.util.HexFormat;

/**
 * RC2 S6: the ME test key table (one any-user key per ME class, written in the A16 form {@code ::users=*}) and
 * the client-side and service-side helpers the in-process and cluster tests use. {@link #install()} publishes
 * the table through the property the production ClusterConfig reads, so every test cluster runs the real
 * authenticator and authorisation service.
 */
public final class TestAuth {

    static final String SECRET_GATEWAY = "a1".repeat(32);
    static final String SECRET_FORWARDER = "b2".repeat(32);
    static final String SECRET_ADMIN = "c3".repeat(32);
    static final String SECRET_REPLICA = "d4".repeat(32);

    /** keyId:CLASS:secretHex:identity:users=* */
    public static final String KEYS = String.join(";",
            "1:GATEWAY:" + SECRET_GATEWAY + "::users=*",
            "2:FORWARDER:" + SECRET_FORWARDER + "::users=*",
            "4:ADMIN:" + SECRET_ADMIN + "::users=*",
            "7:REPLICA:" + SECRET_REPLICA + "::users=*");

    private TestAuth() {
    }

    /** Publish the test key table where ClusterConfig reads it. Idempotent. */
    public static void install() {
        System.setProperty(MatchAuth.POLICY.keysProperty(), KEYS);
    }

    public static void clear() {
        System.clearProperty(MatchAuth.POLICY.keysProperty());
        System.clearProperty(MatchAuth.POLICY.credentialProperty());
    }

    public static ClusterCredentials credentialsOf(final ClusterSessionClass c) {
        switch (c) {
            case GATEWAY: return new ClusterCredentials(c, 1, HexFormat.of().parseHex(SECRET_GATEWAY));
            case FORWARDER: return new ClusterCredentials(c, 2, HexFormat.of().parseHex(SECRET_FORWARDER));
            case ADMIN: return new ClusterCredentials(c, 4, HexFormat.of().parseHex(SECRET_ADMIN));
            case REPLICA: return new ClusterCredentials(c, 7, HexFormat.of().parseHex(SECRET_REPLICA));
            default: throw new IllegalArgumentException("no ME test key for " + c);
        }
    }

    public static CredentialsSupplier credentials(final ClusterSessionClass c) {
        return credentialsOf(c).supplier();
    }

    /** The FORWARDER key with a wrong secret. */
    public static CredentialsSupplier wrongSecret() {
        return new ClusterCredentials(ClusterSessionClass.FORWARDER, 2, HexFormat.of().parseHex("ee".repeat(32))).supplier();
    }

    /** The FORWARDER key presented as ADMIN. */
    public static CredentialsSupplier classMismatch() {
        return new ClusterCredentials(ClusterSessionClass.ADMIN, 2, HexFormat.of().parseHex(SECRET_FORWARDER)).supplier();
    }

    /** The principal the ME authenticator mints for the test key of class {@code c}. */
    public static byte[] principal(final ClusterSessionClass c) {
        return ClusterPrincipal.anyUser(c, credentialsOf(c).keyId()).encode();
    }

    /** A service-side session carrying a principal (for in-process onSessionMessage / onSessionOpen calls). */
    public static ClientSession session(final long id, final byte[] principal) {
        return new StubSession(id, principal);
    }

    public static ClientSession session(final long id, final ClusterSessionClass c) {
        return new StubSession(id, principal(c));
    }

    static final class StubSession implements ClientSession {
        final long id;
        final byte[] principal;
        final java.util.List<byte[]> offered = new java.util.ArrayList<>();

        StubSession(final long id, final byte[] principal) {
            this.id = id;
            this.principal = principal;
        }

        public long id() { return id; }
        public int responseStreamId() { return 0; }
        public String responseChannel() { return "aeron:ipc"; }
        public byte[] encodedPrincipal() { return principal; }
        public void close() { }
        public boolean isClosing() { return false; }

        public long offer(final DirectBuffer buffer, final int offset, final int length) {
            final byte[] b = new byte[length];
            buffer.getBytes(offset, b);
            offered.add(b);
            return 1;
        }

        public long offer(final io.aeron.DirectBufferVector[] vectors) { return 1; }

        public long tryClaim(final int length, final io.aeron.logbuffer.BufferClaim bufferClaim) {
            throw new UnsupportedOperationException();
        }
    }
}
