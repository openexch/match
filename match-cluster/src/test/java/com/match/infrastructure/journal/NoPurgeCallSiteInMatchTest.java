// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.openexchange.cluster.testkit.NeverSchedulesScan;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The match half of the one-purge-owner rule: the engine has no retention code of its own. Every main class of
 * match-cluster ({@code target/classes}, never the test tree) is scanned for calls to
 * {@code AeronArchive.purgeRecording}, {@code purgeSegments}, {@code truncateRecording}, {@code detachSegments} and
 * {@code deleteDetachedSegments}, and the count must be zero: the kit's {@code JournalRetention} is the only class
 * that deletes a journal segment or a journal recording, the same code in both engines. No main class names the
 * retention's negative-control seams either. Control: a probe class that does both is flagged by name.
 */
public class NoPurgeCallSiteInMatchTest {

    private static final String PREFIX = "com/match/";
    private static final String PROBE = PREFIX + "infrastructure/journal/ForbiddenMatchSegmentPurgeProbe";
    private static final String RETENTION_SEAMS = "com/openexchange/cluster/journal/JournalRetention$Seams";

    private static NeverSchedulesScan.Rules purgeRules() {
        return new NeverSchedulesScan.Rules(Set.of(), List.of(), Set.of(),
                Map.of("io/aeron/archive/client/AeronArchive", Set.of("purgeRecording", "purgeSegments",
                        "truncateRecording", "detachSegments", "deleteDetachedSegments")),
                Map.of(), Map.of());
    }

    private static NeverSchedulesScan.Rules seamRules() {
        return new NeverSchedulesScan.Rules(Set.of(RETENTION_SEAMS), List.of(), Set.of(), Map.of(), Map.of(), Map.of());
    }

    private static Path rootOf(final Class<?> c) {
        try {
            return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<NeverSchedulesScan.Finding> mainFindings(final NeverSchedulesScan.Rules rules) {
        final Path root = rootOf(SettlementJournalRuntime.class);
        assertTrue("the main class root is not the test root: " + root,
                !root.equals(rootOf(NoPurgeCallSiteInMatchTest.class)));
        final NeverSchedulesScan.Report report = NeverSchedulesScan.scanDirectory(root, n -> n.startsWith(PREFIX),
                rules);
        assertTrue("the scan saw only " + report.classesScanned() + " match-cluster main classes",
                report.classesScanned() >= 70); // 76 measured (match-cluster main)
        assertTrue("the scan reached the journal runtime",
                report.classes().contains(PREFIX + "infrastructure/journal/SettlementJournalRuntime"));
        assertTrue("the scan reached the service",
                report.classes().contains(PREFIX + "infrastructure/persistence/AppClusteredService"));
        return report.findings();
    }

    private static List<NeverSchedulesScan.Finding> probeFindings(final NeverSchedulesScan.Rules rules) {
        final Class<?> c = ForbiddenMatchSegmentPurgeProbe.class;
        try (InputStream in = c.getResourceAsStream(c.getSimpleName() + ".class")) {
            return NeverSchedulesScan.scanClass(PROBE, in.readAllBytes(), rules);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(final String what, final List<NeverSchedulesScan.Finding> findings) {
        for (final NeverSchedulesScan.Finding f : findings) {
            fail(what + " in match: " + f.className() + " (" + f.reference() + ")");
        }
    }

    private static void control(final String named, final Runnable body) {
        try {
            body.run();
        } catch (final AssertionError e) {
            assertTrue("failed for the wrong reason: " + e.getMessage(), String.valueOf(e.getMessage()).contains(named));
            System.out.println("NC " + e.getMessage());
            return;
        }
        fail("the control did not fail: the scan cannot see its rule");
    }

    @Test
    public void matchMainHasZeroPurgeCallSites() {
        check("purge call site", mainFindings(purgeRules()));
        final List<NeverSchedulesScan.Finding> withProbe = new ArrayList<>(mainFindings(purgeRules()));
        withProbe.addAll(probeFindings(purgeRules()));
        control("purge call site in match: " + PROBE, () -> check("purge call site", withProbe));
    }

    @Test
    public void matchMainNeverNamesTheJournalRetentionSeams() {
        check("journal retention seam named", mainFindings(seamRules()));
        control("journal retention seam named in match: " + PROBE,
                () -> check("journal retention seam named", probeFindings(seamRules())));
    }
}
