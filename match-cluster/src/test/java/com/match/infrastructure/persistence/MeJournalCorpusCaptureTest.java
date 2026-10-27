// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.infrastructure.mejournal.generated.CommandOutcomeDecoder;
import com.match.infrastructure.mejournal.generated.CommandReleaseDecoder;
import com.match.infrastructure.mejournal.generated.MessageHeaderDecoder;
import com.match.infrastructure.mejournal.generated.TerminalDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RC2 S9h 11.8 (W-E1): the committed E1 corpus ({@code src/test/resources/mejournal-e1/}) against its SHA256SUMS and the
 * structural claims of each MANIFEST (record kinds present, the dark-node hole, the two epochs, the one synthetic schema 3
 * frame). J-ME3 is not byte-reproducible, so a fresh capture is never compared byte for byte; {@link #capture} re-records
 * the corpus only when {@code -Dme.e1.capture.dir} names a directory.
 */
public class MeJournalCorpusCaptureTest {

    static Path corpus() throws Exception {
        return Path.of(MeJournalCorpusCaptureTest.class.getClassLoader().getResource("mejournal-e1/SHA256SUMS").toURI()).getParent();
    }

    @Test
    public void capture() throws Exception {
        final String dir = System.getProperty("me.e1.capture.dir");
        Assume.assumeTrue("capture only when -Dme.e1.capture.dir is set", dir != null && !dir.isBlank());
        final String only = System.getProperty("me.e1.capture.scenarios", "");
        MeJournalCorpusCapture.capture(Path.of(dir), only.isBlank() ? MeJournalCorpusCapture.SCENARIOS
                : List.of(only.split(",")));
    }

    /** name -> bytes, every file checked against the SUMS. */
    static Map<String, byte[]> files() throws Exception {
        final Path root = corpus();
        final Map<String, byte[]> out = new TreeMap<>();
        for (final String line : Files.readString(root.resolve("SHA256SUMS"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            final String name = line.substring(66);
            final byte[] b = Files.readAllBytes(root.resolve(name));
            assertEquals("SHA256SUMS: " + name, line.substring(0, 64), MeJournalCorpusCapture.sha256(b));
            out.put(name, b);
        }
        return out;
    }

    @Test
    public void corpusMatchesItsSums() throws Exception {
        final Map<String, byte[]> f = files();
        for (final String s : MeJournalCorpusCapture.SCENARIOS) {
            for (int k = 0; k < 3; k++) {
                assertTrue(s + " member" + k, f.containsKey(s + "/member" + k + ".fjc"));
            }
            assertTrue(s + " MANIFEST", f.containsKey(s + "/MANIFEST"));
        }
        assertEquals("every scenario x 4 files", MeJournalCorpusCapture.SCENARIOS.size() * 4, f.size());
    }

    static List<List<List<byte[]>>> members(final Map<String, byte[]> f, final String s) {
        final List<List<List<byte[]>>> m = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            m.add(MeJournalCorpusCapture.parseFjc(f.get(s + "/member" + k + ".fjc")));
        }
        return m;
    }

    static List<byte[]> all(final List<List<List<byte[]>>> members) {
        final List<byte[]> out = new ArrayList<>();
        members.forEach(m -> m.forEach(out::addAll));
        return out;
    }

    @Test
    public void manifestsStateTheStructure() throws Exception {
        final Map<String, byte[]> f = files();
        for (final String s : MeJournalCorpusCapture.SCENARIOS) {
            final String manifest = new String(f.get(s + "/MANIFEST"), StandardCharsets.US_ASCII);
            assertTrue(s + ": scenario line", manifest.startsWith("scenario=" + s + "\n"));
            assertTrue(s + ": the boundary rule is stated (N-6)", manifest.contains("boundary_rule="));
            final String structure = MeJournalCorpusCapture.structure(members(f, s));
            assertTrue(s + ": the MANIFEST states the structure of the streams:\n" + structure, manifest.contains(structure));
        }
    }

    static boolean schema4(final byte[] f) {
        return MeJournalCorpusCapture.schema(f) == 4;
    }

    static boolean any(final List<byte[]> frames, final Predicate<byte[]> p) {
        return frames.stream().filter(MeJournalCorpusCaptureTest::schema4).anyMatch(p);
    }

    static CommandOutcomeDecoder outcome(final byte[] f) {
        return new CommandOutcomeDecoder().wrapAndApplyHeader(new UnsafeBuffer(f), 0, new MessageHeaderDecoder());
    }

    static TerminalDecoder terminal(final byte[] f) {
        return new TerminalDecoder().wrapAndApplyHeader(new UnsafeBuffer(f), 0, new MessageHeaderDecoder());
    }

    static boolean isOutcome(final byte[] f, final int kind, final int result, final int oldCancelled) {
        if (MeJournalCorpusCapture.template(f) != 3) {
            return false;
        }
        final CommandOutcomeDecoder o = outcome(f);
        return o.commandKind() == kind && o.result() == result && (oldCancelled < 0 || o.oldCancelled() == oldCancelled);
    }

    static boolean isTerminal(final byte[] f, final int status, final int reason) {
        if (MeJournalCorpusCapture.template(f) != 2) {
            return false;
        }
        final TerminalDecoder t = terminal(f);
        return t.statusRaw() == status && (reason < 0 || t.rejectReason() == reason);
    }

    @Test
    public void scenarioClaims() throws Exception {
        final Map<String, byte[]> f = files();
        for (final String s : MeJournalCorpusCapture.SCENARIOS) {
            final List<List<List<byte[]>>> m = members(f, s);
            final List<byte[]> frames = all(m);
            // every member stream opens with the FRESH 68 at seq 1 under G
            for (int k = 0; k < 3; k++) {
                final byte[] first = m.get(k).get(0).get(0);
                assertEquals(s + " member" + k + ": record 5 first", 5, MeJournalCorpusCapture.template(first));
                assertEquals(s + " member" + k + ": seq 1", 1L, MeJournalCorpusCapture.seq(first));
                assertEquals(s + " member" + k + ": epoch G", MeJournalCorpusCapture.G, MeJournalCorpusCapture.epoch(first));
                assertEquals(s + " member" + k + ": FRESH", 0, first[8 + 38]);
            }
            // copies of one (epoch, seq) are byte-equal across members and recordings
            final Map<String, byte[]> bySeq = new HashMap<>();
            for (final byte[] x : frames) {
                if (schema4(x)) {
                    final byte[] prior = bySeq.putIfAbsent(MeJournalCorpusCapture.epoch(x) + "/" + MeJournalCorpusCapture.seq(x), x);
                    if (prior != null) {
                        assertArrayEquals(s + ": every copy of seq " + MeJournalCorpusCapture.seq(x) + " is equal", prior, x);
                    }
                }
            }
            final long nonMe = frames.stream().filter(x -> !schema4(x)).count();
            assertEquals(s + ": non-schema-4 frames", s.equals("schema3_record") ? 1 : 0, nonMe);
            switch (s) {
                case "plain_fill" -> {
                    assertTrue(s, any(frames, x -> MeJournalCorpusCapture.template(x) == 1));
                    assertTrue(s, frames.stream().filter(x -> schema4(x) && isTerminal(x, 2, -1))
                            .map(x -> terminal(x).omsOrderId()).distinct().count() == 2);
                }
                case "partial_cancel" -> {
                    assertTrue(s + ": a trade", any(frames, x -> MeJournalCorpusCapture.template(x) == 1));
                    assertTrue(s + ": CANCELLED", any(frames, x -> isTerminal(x, 3, -1)));
                    assertTrue(s + ": the cancel applied", any(frames, x -> isOutcome(x, 1, 0, -1)));
                }
                case "iceberg_slices" -> {
                    final TreeSet<Long> lows = new TreeSet<>();
                    frames.stream().filter(x -> schema4(x) && isOutcome(x, 0, 0, -1) && outcome(x).commandIdHigh() == 1)
                            .forEach(x -> lows.add(outcome(x).commandIdLow()));
                    assertEquals(s + ": three slices of order 1", 3, lows.size());
                }
                case "replace" -> {
                    assertTrue(s + ": replace APPLIED oldCancelled", any(frames, x -> isOutcome(x, 2, 0, 1)));
                    assertTrue(s + ": replace REJECTED oldCancelled (AMEND_NO_REMAINDER)",
                            any(frames, x -> isOutcome(x, 2, 1, 1) && outcome(x).reason() == 15));
                }
                case "ioc" -> assertTrue(s + ": CANCELLED IOC_REMAINDER", any(frames, x -> isTerminal(x, 3, 12)));
                case "fok" -> {
                    assertTrue(s + ": CANCELLED FOK_UNFILLABLE", any(frames, x -> isTerminal(x, 3, 13)));
                    assertTrue(s + ": a FOK that fills", any(frames, x -> MeJournalCorpusCapture.template(x) == 1));
                }
                case "stop_child" -> {
                    assertTrue(s + ": the MARKET child applied",
                            any(frames, x -> isOutcome(x, 0, 0, -1) && outcome(x).orderType() == 1 && outcome(x).orderSide() == 1));
                    assertTrue(s + ": it traded", any(frames, x -> MeJournalCorpusCapture.template(x) == 1));
                }
                case "market_budget" -> assertTrue(s + ": BUDGET_EXHAUSTED", any(frames, x -> isTerminal(x, 3, 14)));
                case "release" -> assertTrue(s + ": a release with 2 entries", any(frames, x -> MeJournalCorpusCapture.template(x) == 4
                        && new CommandReleaseDecoder().wrapAndApplyHeader(new UnsafeBuffer(x), 0, new MessageHeaderDecoder())
                        .releases().count() == 2));
                case "fresh_config" -> assertTrue(s, any(frames, x -> MeJournalCorpusCapture.template(x) == 5));
                case "dark_node_hole" -> {
                    boolean dark = false;
                    for (int k = 0; k < 3; k++) {
                        final String manifest = new String(f.get(s + "/MANIFEST"), StandardCharsets.US_ASCII);
                        if (m.get(k).size() == 2 && manifest.contains("member" + k + ".missing_")) {
                            dark = true;
                        }
                    }
                    assertTrue(s + ": one member has two recordings and a contiguous hole", dark);
                }
                case "regenesis" -> {
                    final TreeSet<Long> epochs = new TreeSet<>();
                    frames.stream().filter(MeJournalCorpusCaptureTest::schema4).forEach(x -> epochs.add(MeJournalCorpusCapture.epoch(x)));
                    assertEquals(s + ": two epochs on one stream", 2, epochs.size());
                    for (int k = 0; k < 3; k++) {
                        final List<byte[]> second = m.get(k).get(m.get(k).size() - 1);
                        final byte[] first = second.get(0);
                        assertEquals(s + " member" + k + ": the new recording opens with the FRESH under G2", 5,
                                MeJournalCorpusCapture.template(first));
                        assertEquals(s + " member" + k + ": seq 1 again", 1L, MeJournalCorpusCapture.seq(first));
                        assertEquals(s + " member" + k + ": epoch G2", MeJournalCorpusCapture.G2, MeJournalCorpusCapture.epoch(first));
                    }
                }
                case "schema3_record" -> {
                    final List<byte[]> m0 = new ArrayList<>();
                    m.get(0).forEach(m0::addAll);
                    final byte[] last = m0.get(m0.size() - 1);
                    assertEquals(s + ": member 0's last frame is schema 3", 3, MeJournalCorpusCapture.schema(last));
                    assertArrayEquals(s + ": the synthetic frame", MeJournalCorpusCapture.schema3Trade(), last);
                    final String manifest = new String(f.get(s + "/MANIFEST"), StandardCharsets.US_ASCII);
                    assertTrue(s + ": the MANIFEST names the expected refusal", manifest.contains("member0.refused="
                            + (m0.size() - 1) + " SCHEMA schemaId 3"));
                }
                case "maker_sweep" -> {
                    assertTrue(s + ": five trades", frames.stream().filter(x -> schema4(x) && MeJournalCorpusCapture.template(x) == 1)
                            .count() >= 5 * 3);
                    // the finding this scenario records: the ME journals the exhausted maker's FILLED (or the taker's
                    // terminal) after every trade, so two trade records are never adjacent and a live bridge never stages a 62
                    for (final List<List<byte[]>> mem : m) {
                        for (final List<byte[]> rec : mem) {
                            for (int i = 1; i < rec.size(); i++) {
                                assertTrue(s + ": no two adjacent trades", !(MeJournalCorpusCapture.template(rec.get(i)) == 1
                                        && MeJournalCorpusCapture.template(rec.get(i - 1)) == 1));
                            }
                        }
                    }
                }
                case "restart_rejournal" -> {
                    boolean overlap = false;
                    for (int k = 0; k < 3; k++) {
                        if (m.get(k).size() == 2) {
                            final List<byte[]> a = m.get(k).get(0);
                            final List<byte[]> b = m.get(k).get(1);
                            overlap = MeJournalCorpusCapture.seq(b.get(0)) == 1
                                    && MeJournalCorpusCapture.seq(b.get(b.size() - 1)) > MeJournalCorpusCapture.seq(a.get(a.size() - 1));
                        }
                    }
                    assertTrue(s + ": a new recording re-journals from seq 1 over what was delivered (K8)", overlap);
                }
                default -> throw new AssertionError(s);
            }
        }
    }

    @Test
    public void fjcReaderRefusesBadInput() {
        try {
            MeJournalCorpusCapture.parseFjc(new byte[] {0x46, 0x4A, 0x43, 0x32});
            throw new AssertionError("bad magic accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
        try {
            MeJournalCorpusCapture.parseFjc(Arrays.copyOf(new byte[] {0x46, 0x4A, 0x43, 0x31, 1, 0, 0, 0, 0, 0, 0, 0, 0}, 13));
            throw new AssertionError("unterminated recording accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unterminated"));
        }
    }
}
