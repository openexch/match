// SPDX-License-Identifier: Apache-2.0
package com.match.determinism;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * A/B equivalence gate for the order-book swap: every {@code .scenario} is replayed through BOTH the
 * preallocated direct-index engine and the array-backed engine, and their {@code MatchEventSink}
 * output streams must be byte-identical — except for the one scenario where the implementations are
 * <i>intended</i> to differ.
 *
 * <p>This is the guardrail that lets the array engine ship: it proves behavioral equivalence directly
 * (not just "both match a golden"), independent of which implementation the committed goldens track.
 *
 * <p>{@code level_full_reject} is the sole expected divergence: the direct book loudly rejects the
 * 65th order at a level (its hard 64-cap), while the array book — bounded only by the shared pool —
 * rests it as {@code NEW}. That exact difference is asserted, not merely tolerated.
 */
@RunWith(Parameterized.class)
public class DeterminismAbEquivalenceTest {

    private static final Path DIR = Path.of("src", "test", "resources", "determinism");

    /** Scenarios where "direct" and "array" are expected to differ (documented behavior change). */
    private static final Set<String> EXPECTED_DIVERGENT = Set.of("level_full_reject");

    @Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".scenario"))
                    .sorted()
                    .map(p -> new Object[]{stripExt(p), p})
                    .collect(Collectors.toList());
        }
    }

    private static String stripExt(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.length() - ".scenario".length());
    }

    private final String name;
    private final Path scenarioFile;

    public DeterminismAbEquivalenceTest(String name, Path scenarioFile) {
        this.name = name;
        this.scenarioFile = scenarioFile;
    }

    @Test
    public void directAndArrayProduceSameOutput() throws IOException {
        String direct = ScenarioRunner.run(scenarioFile, "direct");
        String array = ScenarioRunner.run(scenarioFile, "array");

        if (EXPECTED_DIVERGENT.contains(name)) {
            assertNotEquals("Expected '" + name + "' to diverge between implementations", direct, array);
            if (name.equals("level_full_reject")) {
                assertTrue("direct (64-cap) must reject the 65th order at the level",
                        direct.contains("order=65 user=200 status=REJECTED"));
                assertTrue("array (no per-level cap) must rest the 65th order as NEW",
                        array.contains("order=65 user=200 status=NEW"));
            }
        } else {
            assertEquals("Array engine diverged from direct on scenario '" + name + "'", direct, array);
        }
    }
}
