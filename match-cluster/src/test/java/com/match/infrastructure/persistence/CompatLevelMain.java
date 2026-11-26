// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.openexchange.cluster.MemberCompat;

/**
 * Test main: the production entry point ({@code com.match.Main}) launched as a build of another member version.
 * Arguments: {@code <generation> <level>}; the negative controls add {@code -Dtest.member.compat.prelaunch=off} or
 * {@code -Dtest.member.compat.validator=aeron}. The seam is the kit's {@code MemberCompat.TestSeam}, the one the core's
 * process tests use. Everything else (environment, settings, the lifecycle and its exits) is the production path.
 */
public final class CompatLevelMain {

    private CompatLevelMain() {
    }

    public static void main(final String[] args) throws Exception {
        MemberCompat.TestSeam.fromSystemProperties();
        MemberCompat.TestSeam.set(MemberCompat.of(Integer.parseInt(args[0]), Integer.parseInt(args[1])),
                "off".equals(System.getProperty("test.member.compat.prelaunch")),
                "aeron".equals(System.getProperty("test.member.compat.validator")));
        com.match.Main.main(new String[0]);
    }
}
