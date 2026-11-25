// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * One SBE schema id names one wire schema in every repo. The ids in use are listed in
 * {@code sbe-schema-ids.txt} at the repo root (the same file in every repo that publishes or decodes
 * a schema); this test holds the file and this repo's schema files to it:
 * <ul>
 *   <li>every line is {@code <id> <package> <owner>:<path>}, id 1..65535;</li>
 *   <li>no id and no package is listed twice;</li>
 *   <li>every schema XML in this repo is listed under this repo with its own id and package;</li>
 *   <li>every line this repo owns names a file that exists.</li>
 * </ul>
 * A new schema therefore has to take a free id in the list before it can ship.
 */
public class SbeSchemaIdRegistryTest {

    static final String OWNER = "match";
    static final String REGISTRY = "sbe-schema-ids.txt";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void everySchemaIdIsListedOnceAndEverySchemaOfThisRepoMatchesItsLine() throws Exception {
        final Path root = repoRoot();
        final List<String> problems = check(root.resolve(REGISTRY), root, OWNER);
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    // ---------------------------------------------------------------- negative controls

    @Test
    public void aDuplicateIdIsRefused() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                7 a.two %1$s:s/two.xml
                """, "s/one.xml", schema(7, "a.one"), "s/two.xml", schema(7, "a.two"));
        assertProblem(root, "duplicate schema id 7");
    }

    @Test
    public void aDuplicatePackageIsRefused() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                8 a.one %1$s:s/two.xml
                """, "s/one.xml", schema(7, "a.one"), "s/two.xml", schema(8, "a.one"));
        assertProblem(root, "package a.one listed twice");
    }

    @Test
    public void anUnlistedSchemaIsRefused() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                """, "s/one.xml", schema(7, "a.one"), "s/new.xml", schema(9, "a.new"));
        assertProblem(root, "s/new.xml (id 9, package a.new) is not in sbe-schema-ids.txt");
    }

    @Test
    public void aSchemaWhoseIdDiffersFromItsLineIsRefused() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                """, "s/one.xml", schema(9, "a.one"));
        assertProblem(root, "s/one.xml declares id 9 and package a.one, sbe-schema-ids.txt says id 7 package a.one");
    }

    @Test
    public void aListedSchemaThatIsGoneIsRefused() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                8 a.gone %1$s:s/gone.xml
                """, "s/one.xml", schema(7, "a.one"));
        assertProblem(root, "s/gone.xml is listed but missing");
    }

    @Test
    public void malformedLinesAndIdsOutOfRangeAreRefused() throws Exception {
        assertProblem(fixture("7 a.one\n"), "malformed line 1");
        assertProblem(fixture("x a.one %1$s:s/one.xml\n"), "malformed line 1");
        assertProblem(fixture("0 a.one %1$s:s/one.xml\n"), "id 0 out of range");
        assertProblem(fixture("65536 a.one %1$s:s/one.xml\n"), "id 65536 out of range");
        assertProblem(fixture("7 a.one nowhere\n"), "malformed line 1");
    }

    @Test
    public void linesOfOtherOwnersAreOnlyCheckedForUniqueness() throws Exception {
        final Path root = fixture("""
                7 a.one %1$s:s/one.xml
                8 b.other someone-else:not/here.xml
                """, "s/one.xml", schema(7, "a.one"));
        assertEquals(List.of(), check(root.resolve(REGISTRY), root, OWNER));
    }

    // ---------------------------------------------------------------- the check

    static List<String> check(final Path registry, final Path root, final String owner) throws Exception {
        final List<String> problems = new ArrayList<>();
        final Map<Integer, String> byId = new HashMap<>();
        final Map<String, String> byPackage = new HashMap<>();
        final Map<String, String[]> owned = new HashMap<>(); // path -> {id, package}
        final List<String> lines = Files.readAllLines(registry, StandardCharsets.UTF_8);
        for (int n = 1; n <= lines.size(); n++) {
            final String line = lines.get(n - 1).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final String[] f = line.split("\\s+");
            if (f.length != 3 || !f[0].matches("\\d+") || f[2].indexOf(':') <= 0 || f[2].endsWith(":")) {
                problems.add("malformed line " + n + ": '" + line + "' (want <id> <package> <owner>:<path>)");
                continue;
            }
            final int id = Integer.parseInt(f[0]);
            if (id < 1 || id > 65535) {
                problems.add("line " + n + ": id " + id + " out of range 1..65535");
                continue;
            }
            final String where = f[1] + " (" + f[2] + ")";
            final String clash = byId.putIfAbsent(id, where);
            if (clash != null) {
                problems.add("duplicate schema id " + id + ": " + clash + " and " + where);
            }
            if (byPackage.putIfAbsent(f[1], f[2]) != null) {
                problems.add("package " + f[1] + " listed twice");
            }
            final String lineOwner = f[2].substring(0, f[2].indexOf(':'));
            if (lineOwner.equals(owner)) {
                owned.put(f[2].substring(f[2].indexOf(':') + 1), new String[] {f[0], f[1]});
            }
        }
        final Map<String, String[]> found = schemasUnder(root);
        for (final Map.Entry<String, String[]> e : found.entrySet()) {
            final String[] listed = owned.get(e.getKey());
            final String[] got = e.getValue();
            if (listed == null) {
                problems.add(e.getKey() + " (id " + got[0] + ", package " + got[1] + ") is not in " + REGISTRY);
            } else if (!listed[0].equals(got[0]) || !listed[1].equals(got[1])) {
                problems.add(e.getKey() + " declares id " + got[0] + " and package " + got[1] + ", " + REGISTRY
                        + " says id " + listed[0] + " package " + listed[1]);
            }
        }
        for (final String path : owned.keySet()) {
            if (!found.containsKey(path)) {
                problems.add(path + " is listed but missing");
            }
        }
        return problems;
    }

    /** Every XML file under root whose root element is an SBE messageSchema: relative path -> {id, package}. */
    static Map<String, String[]> schemasUnder(final Path root) throws Exception {
        final Map<String, String[]> out = new HashMap<>();
        final List<Path> xmls;
        try (Stream<Path> walk = Files.walk(root)) {
            xmls = walk.filter(p -> p.toString().endsWith(".xml") && Files.isRegularFile(p))
                    .filter(p -> !skipped(root.relativize(p)))
                    .toList();
        }
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (final Path xml : xmls) {
            if (!Files.readString(xml, StandardCharsets.UTF_8).contains("messageSchema")) {
                continue;
            }
            final Element top = factory.newDocumentBuilder().parse(xml.toFile()).getDocumentElement();
            if (!"messageSchema".equals(top.getLocalName())) {
                continue;
            }
            out.put(root.relativize(xml).toString().replace('\\', '/'),
                    new String[] {top.getAttribute("id"), top.getAttribute("package")});
        }
        return out;
    }

    private static boolean skipped(final Path relative) {
        for (final Path part : relative) {
            final String name = part.toString();
            if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                return true;
            }
        }
        return false;
    }

    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve(REGISTRY))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(REGISTRY + " not found above " + System.getProperty("user.dir"));
        }
        return dir;
    }

    // ---------------------------------------------------------------- fixtures

    private Path fixture(final String registry, final String... files) throws IOException {
        final Path root = temp.newFolder().toPath();
        Files.writeString(root.resolve(REGISTRY), String.format(registry, OWNER));
        for (int i = 0; i < files.length; i += 2) {
            final Path p = root.resolve(files[i]);
            Files.createDirectories(p.getParent());
            Files.writeString(p, files[i + 1]);
        }
        return root;
    }

    private static String schema(final int id, final String pkg) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<sbe:messageSchema xmlns:sbe=\"http://fixprotocol.io/2016/sbe\" package=\"" + pkg + "\" id=\"" + id
                + "\" version=\"1\" byteOrder=\"littleEndian\"><types/></sbe:messageSchema>\n";
    }

    private void assertProblem(final Path root, final String expected) throws Exception {
        final List<String> problems = check(root.resolve(REGISTRY), root, OWNER);
        assertTrue("expected a problem containing '" + expected + "', got " + problems,
                problems.stream().anyMatch(p -> p.contains(expected)));
    }
}
