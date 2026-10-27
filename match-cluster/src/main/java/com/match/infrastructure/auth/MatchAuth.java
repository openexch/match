// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.auth;

import com.openexchange.cluster.auth.AuthKeyTable;
import com.openexchange.cluster.auth.AuthPolicy;
import com.openexchange.cluster.auth.ClassAuthorisationService;
import com.openexchange.cluster.auth.ClusterAuthenticator;
import com.openexchange.cluster.auth.ClusterSessionClass;

/**
 * The matching engine's use of the shared K6 v1 authority (RC2 S6 contract 2.7). Every session authenticates
 * against the node-local key table ({@code ME_AUTH_KEYS} / {@code -Dmatch.auth.keys}); ME clients present
 * {@code ME_AUTH_CREDENTIAL} / {@code -Dmatch.auth.credential}. Classes: GATEWAY (market data, sends no ingress
 * at behaviour 2), FORWARDER (the durable lane), ADMIN (EngineConfig, operator reads) and REPLICA (the backup:
 * BackupQuery and nothing else, decision A14). The ME has no user scope: every key is {@code users=*} and an id
 * list fails the load (ownership is derived from the book, RC2 S4 5.1).
 *
 * <p>There is no anonymous mode (A1): an empty table rejects every session, loudly.</p>
 */
public final class MatchAuth {

    public static final AuthPolicy POLICY = AuthPolicy.of("match",
            "match.auth.keys", "ME_AUTH_KEYS",
            "match.auth.credential", "ME_AUTH_CREDENTIAL",
            false,
            ClusterSessionClass.GATEWAY, ClusterSessionClass.FORWARDER, ClusterSessionClass.ADMIN,
            ClusterSessionClass.REPLICA);

    private MatchAuth() {
    }

    /**
     * The consensus-module authorisation table: REPLICA may send BackupQuery; every other (class, action),
     * HeartbeatRequest, StandbySnapshot and AdminRequest included, is denied. RCS S11 extends this table and
     * must keep the REPLICA BackupQuery row.
     */
    public static ClassAuthorisationService authorisationService() {
        return ClassAuthorisationService.builder()
                .allow(ClusterSessionClass.REPLICA, ClassAuthorisationService.CLUSTER_PROTOCOL_ID,
                        ClassAuthorisationService.BACKUP_QUERY)
                .build();
    }

    /**
     * The authenticator over the table read from the environment; a malformed table fails here, at boot.
     * {@code sessionTimeoutNs} is read when a backup-style request arrives (after the consensus module context
     * is concluded): the fail-closed window for Aeron's shared backup session id must outlive Aeron's pending
     * session (RC2 S6 review DEFECT-1, kit docs/auth.md).
     */
    public static ClusterAuthenticator authenticatorFromEnvironment(final java.util.function.LongSupplier sessionTimeoutNs) {
        return new ClusterAuthenticator(AuthKeyTable.fromEnvironment(POLICY), ClusterAuthenticator.DEFAULT_MAX_PENDING,
                () -> ClusterAuthenticator.backupWindowMsFor(sessionTimeoutNs.getAsLong()));
    }
}
