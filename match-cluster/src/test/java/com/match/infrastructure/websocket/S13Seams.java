// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.websocket;

import java.util.Arrays;

/**
 * RC2 S13 test scope (contract 11.7, the match test convention): the ME negative-control switches. A rule is
 * switched off for a run with {@code -Dmatch.test.disable=RULE[,RULE...]}; the tests then flip the
 * package-private seam field it names, which main code never writes. Rules: {@code RESNAPSHOT_ON_LEADER},
 * {@code RESNAPSHOT_BEFORE_IS_LEADER} (switches the withdrawn order ON), {@code RESTORE_REPUBLISH},
 * {@code EMPTY_BOOK_VERSION_GUARD}.
 */
public final class S13Seams {

    public static final String PROPERTY = "match.test.disable";

    private S13Seams() {
    }

    /** True when {@code rule} is switched off for this run. */
    public static boolean off(final String rule) {
        final String v = System.getProperty(PROPERTY, "");
        return Arrays.stream(v.split(",")).map(String::trim).anyMatch(rule::equals);
    }

    /** Apply the publisher seam of this run to {@code p}. */
    public static void apply(final MarketPublisher p) {
        p.emptyBookVersionGuard = !off("EMPTY_BOOK_VERSION_GUARD");
    }
}
