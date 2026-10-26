// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test-scope generator of the ME journal schema 4 cross-language fixtures (RC2 S1 contract
 * FIX-7, 5.4, 8.2). Every file is ONE journal frame exactly as {@link SettlementJournal} puts it
 * on the ring (and the writer agent offers it as one unfragmented frame), produced from fixed
 * inputs, so the bytes are a pure function of this source. The execution projector copies the
 * files and pins the producing match commit as {@code fixture_source_commit}.
 *
 * <p>Usage: {@code MeJournalFixtureDump <outDir>} writes the six {@code .bin} files and a
 * {@code SHA256SUMS} in {@code sha256sum} format. The committed copy lives under
 * {@code match-cluster/src/test/resources/mejournal/}; {@code MeJournalFixtureDumpTest} fails if
 * the committed files drift from what this class produces.</p>
 *
 * <p>The values follow the schema 3 era projector fixtures (trade 1 / orders 91, 92 / OMS 101,
 * 102; command 17:1 for OMS 9001 at positions 128 and 256) and pass the projector validation of
 * contract 5.5. journalSeq and meEpoch are 0, the S1 placeholders.</p>
 */
public final class MeJournalFixtureDump {

    public static final String TRADE = "java-trade.bin";
    public static final String TERMINAL = "java-terminal.bin";
    public static final String COMMAND_FIRST = "java-command.first.bin";
    public static final String COMMAND_RETRY = "java-command.retry.bin";
    public static final String RELEASE = "java-release.bin";
    public static final String CONFIG = "java-config.bin";
    public static final String SUMS = "SHA256SUMS";

    private MeJournalFixtureDump() {
    }

    /** The six fixtures, by file name (sorted), each one journal frame. */
    public static Map<String, byte[]> fixtures() {
        final Map<String, byte[]> out = new TreeMap<>();

        out.put(TRADE, single(j -> j.appendTrade(
                10_000L, 1L, 1, 91L, 11L, 92L, 12L,
                12_345_678_901_234L, 123_456_789L, true, 1_700_000_000_123L, 101L, 102L)));

        out.put(TERMINAL, single(j -> j.appendTerminal(
                10_000L, 91L, 11L, 1, 2 /* FILLED */, 1_700_000_000_123L, 101L, 0 /* NONE */)));

        final DurableOrderIntent intent = new DurableOrderIntent(17L, 1L, 100L, 9001L, 0L,
                5_900_000_000_000L, 100_000_000L, 0L, 1, 0 /* CREATE */, 0 /* LIMIT */, 0 /* BID */,
                0 /* GTC */, 0L);
        final DurableCommandOutcome outcome = new DurableCommandOutcome(intent, 128L, 1_000L,
                1L, 0 /* NEW */, 0, false, DurableCommandOutcome.APPLIED);
        // The retry journals the original outcome again at its own log position (Engine.acceptDurable).
        out.put(COMMAND_FIRST, single(j -> j.appendCommandOutcome(128L, outcome, 1)));
        out.put(COMMAND_RETRY, single(j -> j.appendCommandOutcome(256L, outcome, 1)));

        out.put(RELEASE, single(j -> j.appendCommandRelease(
                640L, 7L, 1, 100_000, 1, 2,
                new long[] {17L, 17L}, new long[] {2L, 5L},
                new int[] {0 /* UP_TO */, 1 /* ORDER */}, new int[] {0 /* APPLIED */, 1 /* NOOP */},
                new int[] {1, 0})));

        out.put(CONFIG, single(j -> j.appendEngineConfig(
                64L, 1L, 1L, 100_000L, 12, 0 /* FRESH */)));
        return out;
    }

    /** {@code sha256sum} format: {@code <hex>  <name>\n}, names sorted. */
    public static String sha256Sums(final Map<String, byte[]> fixtures) {
        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, byte[]> e : new TreeMap<>(fixtures).entrySet()) {
            sb.append(sha256Hex(e.getValue())).append("  ").append(e.getKey()).append('\n');
        }
        return sb.toString();
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: MeJournalFixtureDump <outDir>");
            System.exit(2);
        }
        final Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        final Map<String, byte[]> fixtures = fixtures();
        for (final Map.Entry<String, byte[]> e : fixtures.entrySet()) {
            Files.write(dir.resolve(e.getKey()), e.getValue());
        }
        Files.writeString(dir.resolve(SUMS), sha256Sums(fixtures), StandardCharsets.US_ASCII);
        System.out.print(sha256Sums(fixtures));
    }

    private interface Append {
        void to(SettlementJournal journal);
    }

    /** Runs one append on a fresh journal and returns the single ring payload it produced. */
    private static byte[] single(final Append append) {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        append.to(journal);
        final List<byte[]> frames = new ArrayList<>();
        journal.ringBuffer().read((msgTypeId, buffer, index, length) -> {
            final byte[] copy = new byte[length];
            buffer.getBytes(index, copy);
            frames.add(copy);
        }, Integer.MAX_VALUE);
        if (frames.size() != 1) {
            throw new IllegalStateException("expected one journal frame, got " + frames.size());
        }
        return frames.get(0);
    }

    static String sha256Hex(final byte[] bytes) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder sb = new StringBuilder(64);
            for (final byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is JDK-mandated and missing", e);
        }
    }
}
