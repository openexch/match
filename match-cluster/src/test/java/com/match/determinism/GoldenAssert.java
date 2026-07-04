// SPDX-License-Identifier: Apache-2.0
package com.match.determinism;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Golden-master comparison for determinism scenarios.
 *
 * <p>Compares rendered output to a committed {@code .golden} file. Any divergence — i.e. any code
 * change that alters the engine's output for a fixed input — fails loudly with a line diff. This
 * is the "same input → same output, whatever change we do" gate. Regenerate intentionally with
 * {@code -Dupdate.golden=true} (then review + commit the diff). A missing golden fails rather than
 * silently auto-passing, so a scenario can't be shipped without its committed expected output.</p>
 */
public final class GoldenAssert {

    /** Resolved relative to the module basedir (Surefire's working directory). */
    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "determinism");

    private GoldenAssert() {
    }

    public static void match(String scenarioName, String actual) {
        Path golden = GOLDEN_DIR.resolve(scenarioName + ".golden");
        boolean update = Boolean.getBoolean("update.golden");

        try {
            if (update) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(golden, actual, StandardCharsets.UTF_8);
                System.out.println("[GOLDEN] wrote " + golden);
                return;
            }
            if (!Files.exists(golden)) {
                throw new AssertionError("No golden for '" + scenarioName + "' at " + golden
                        + " — generate it with -Dupdate.golden=true, then review and commit it.");
            }
            String expected = Files.readString(golden, StandardCharsets.UTF_8);
            if (!expected.equals(actual)) {
                throw new AssertionError("Determinism golden mismatch for '" + scenarioName + "':\n"
                        + diff(expected, actual)
                        + "\nIf intentional, regenerate with -Dupdate.golden=true and commit.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Minimal line-oriented diff: '-' expected, '+' actual, for the lines that differ. */
    private static String diff(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int max = Math.max(e.length, a.length);
        for (int i = 0; i < max; i++) {
            String el = i < e.length ? e[i] : null;
            String al = i < a.length ? a[i] : null;
            if (!Objects.equals(el, al)) {
                sb.append("  @ line ").append(i + 1).append('\n');
                if (el != null) sb.append("  - ").append(el).append('\n');
                if (al != null) sb.append("  + ").append(al).append('\n');
            }
        }
        return sb.toString();
    }
}
