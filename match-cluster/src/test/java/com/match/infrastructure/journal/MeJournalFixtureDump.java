// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.journal;

import com.match.application.engine.MeIdentity;
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
 * 102; command 17:1 for OMS 9001) and pass the projector validation of contract 5.5.</p>
 *
 * <p>RC2 S3 (W5a, review FIX-5 and NOTE-10): the six frames are ONE identity-bound behaviour-2
 * producer sequence, bound through {@link MeIdentity#restore} with meEpoch {@link #G}:
 * config FRESH journalSeq 1, trade 2, terminal 3, command.first 4, command.conflict 5, release
 * 6, egressSeq non-decreasing in seq order (64, 128, 128, 192, 256, 640), behaviour 2 on records
 * 3 and 5, maxWireVersion 12. A behaviour-2 ME never re-journals an equal duplicate (X4), so the
 * schema-4 retry fixture is replaced by a CONFLICT in the commandRejection shape (orderId 0,
 * status -1, reason 0, result 2, appliedPosition = egressSeq).</p>
 */
public final class MeJournalFixtureDump {

    public static final String TRADE = "java-trade.bin";
    public static final String TERMINAL = "java-terminal.bin";
    public static final String COMMAND_FIRST = "java-command.first.bin";
    public static final String COMMAND_CONFLICT = "java-command.conflict.bin";
    public static final String RELEASE = "java-release.bin";
    public static final String CONFIG = "java-config.bin";
    public static final String SUMS = "SHA256SUMS";

    /** The meEpoch of every fixture: a positive genesis nonce (BehaviourTwoHarness.G). */
    public static final long G = 0x1234_5678_9ABC_DEF1L;

    private MeJournalFixtureDump() {
    }

    /** The six fixtures, by file name (sorted), each one journal frame. */
    public static Map<String, byte[]> fixtures() {
        final Map<String, byte[]> out = new TreeMap<>();

        out.put(CONFIG, single(j -> j.appendEngineConfig(
                1L, 64L, 1L, 2L /* behaviour 2 */, 100_000L, 12, 0 /* FRESH */)));

        out.put(TRADE, single(j -> j.appendTrade(
                2L, 128L, 1L, 1, 91L, 11L, 92L, 12L,
                12_345_678_901_234L, 123_456_789L, true, 1_700_000_000_123L, 101L, 102L)));

        out.put(TERMINAL, single(j -> j.appendTerminal(
                3L, 128L, 91L, 11L, 1, 2 /* FILLED */, 1_700_000_000_123L, 101L, 0 /* NONE */)));

        final DurableOrderIntent intent = new DurableOrderIntent(17L, 1L, 100L, 9001L, 0L,
                5_900_000_000_000L, 100_000_000L, 0L, 1, 0 /* CREATE */, 0 /* LIMIT */, 0 /* BID */,
                0 /* GTC */, 0L);
        final DurableCommandOutcome outcome = new DurableCommandOutcome(intent, 192L, 1_000L,
                1L, 0 /* NEW */, 0, false, DurableCommandOutcome.APPLIED);
        out.put(COMMAND_FIRST, single(j -> j.appendCommandOutcome(4L, 192L, outcome, 1)));

        // The same command id with a different payload: CONFLICT, never recorded (ledger size 1).
        final DurableOrderIntent conflicting = new DurableOrderIntent(17L, 1L, 100L, 9001L, 0L,
                6_000_000_000_000L, 100_000_000L, 0L, 1, 0 /* CREATE */, 0 /* LIMIT */, 0 /* BID */,
                0 /* GTC */, 0L);
        final DurableCommandOutcome conflict = new DurableCommandOutcome(conflicting, 256L, 1_001L,
                0L, -1, 0, false, DurableCommandOutcome.CONFLICT);
        out.put(COMMAND_CONFLICT, single(j -> j.appendCommandOutcome(5L, 256L, conflict, 1)));

        out.put(RELEASE, single(j -> j.appendCommandRelease(
                6L, 640L, 7L, 1, 100_000, 1, 2,
                new long[] {17L, 17L}, new long[] {2L, 5L},
                new int[] {0 /* UP_TO */, 1 /* ORDER */}, new int[] {0 /* APPLIED */, 1 /* NOOP */},
                new int[] {1, 0})));
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

    /**
     * Runs one append on a fresh journal bound to a behaviour-2 identity with meEpoch {@link #G}
     * (through the test-scope {@code MeIdentityTestAccess}: binding is engine-package private) and
     * returns the single ring payload it produced.
     */
    private static byte[] single(final Append append) {
        final SettlementJournal journal = new SettlementJournal(1 << 16);
        final MeIdentity identity = com.match.application.engine.MeIdentityTestAccess.restored(G, 7L, 2L);
        journal.bindIdentity(identity);
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
