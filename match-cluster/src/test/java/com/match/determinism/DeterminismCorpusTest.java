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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

/**
 * The determinism regression suite: every {@code .scenario} in
 * {@code src/test/resources/determinism} is replayed through a fresh {@link com.match.application.engine.Engine}
 * and checked three ways. This is the "same input → same output, whatever change we do" gate.
 */
@RunWith(Parameterized.class)
public class DeterminismCorpusTest {

    private static final Path DIR = Path.of("src", "test", "resources", "determinism");

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

    public DeterminismCorpusTest(String name, Path scenarioFile) {
        this.name = name;
        this.scenarioFile = scenarioFile;
    }

    /** Same input must reproduce the committed golden output exactly. */
    @Test
    public void matchesGolden() throws IOException {
        GoldenAssert.match(name, ScenarioRunner.run(scenarioFile));
    }

    /** Same input through two independent fresh engines must be byte-identical (no in-run nondeterminism). */
    @Test
    public void runTwiceIsIdentical() throws IOException {
        String a = ScenarioRunner.run(scenarioFile);
        String b = ScenarioRunner.run(scenarioFile);
        assertEquals("Run-twice divergence in '" + name + "'", a, b);
    }

    /** Output must be invariant to wall-clock time: same logical input, real time advancing between runs. */
    @Test
    public void wallClockInvariant() throws IOException, InterruptedException {
        String first = ScenarioRunner.run(scenarioFile);
        Thread.sleep(3);
        String second = ScenarioRunner.run(scenarioFile);
        assertEquals("Wall-clock leaked into output of '" + name + "'", first, second);
    }
}
