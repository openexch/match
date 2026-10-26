// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.application.engine.DurableCommandLedger;
import com.match.application.engine.Engine;
import com.match.domain.FixedPoint;
import com.match.domain.commands.DurableCommandOutcome;
import com.match.domain.commands.DurableOrderIntent;
import org.junit.Test;

import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.application.engine.BehaviourTwoHarness.journalSeqOf;
import static com.match.application.engine.BehaviourTwoHarness.record3;
import static com.match.application.engine.BehaviourTwoHarness.templateId;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S3 contract section 5 (X4): under behaviour 2 an equal-intent duplicate of a RECORDED
 * outcome journals nothing and consumes no journalSeq; CONFLICT, CAPACITY and INVALID are never
 * recorded and journal every copy at consecutive seqs.
 */
public class DuplicateSuppressionTest {

    private static final long PRICE = FixedPoint.fromDouble(59_000);
    private static final long QTY = FixedPoint.fromDouble(1);

    private static DurableOrderIntent create(long idLow, long price) {
        return new DurableOrderIntent(17, idLow, 100, 9000 + idLow, 0, price, QTY, 0, BehaviourTwoHarness.MARKET, 0, 0, 0);
    }

    private static Replica fresh() {
        final Replica r = new Replica();
        r.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
        return r;
    }

    private static DurableCommandOutcome at(Replica r, long position, DurableOrderIntent c) {
        r.h.engine.setCurrentLogPosition(position);
        return r.h.engine.acceptDurable(c, 1_000L + position);
    }

    /** Fill the ledger to MAX_ENTRIES with recorded outcomes (never through the 1 MiB ring). */
    private static void fillLedger(Engine engine) {
        for (long i = engine.commandLedger().size() + 1000; engine.commandLedger().size() < DurableCommandLedger.MAX_ENTRIES; i++) {
            engine.commandLedger().record(new DurableCommandOutcome(
                    new DurableOrderIntent(17, i, 100, 9001, 999, 0, 0, 0, 1, 1, 0, 0), 0, 1_000, 0, -1, 0, false, 4));
        }
    }

    @Test
    public void equalDuplicateJournalsNothingAndConsumesNoSeq() {
        try (Replica r = fresh()) {
            final DurableOrderIntent c = create(1, PRICE);
            final DurableCommandOutcome original = at(r, 192L, c);
            final List<byte[]> first = r.h.drain();
            assertEquals(1, first.size());
            final long k = journalSeqOf(first.get(0));

            final DurableCommandOutcome retry = at(r, 320L, c);
            assertEquals("the caller still gets the prior outcome", original, retry);
            assertEquals("the equal retry adds no frame to the ring", List.of(), r.h.drain());
            assertEquals(1L, r.h.engine.suppressedDuplicateCount());
            assertEquals("the original outcome at seq 2", 2L, k);
            assertEquals(1, r.h.engine.commandLedger().size());

            at(r, 448L, create(2, PRICE));
            final List<byte[]> next = r.h.drain();
            assertEquals("the next command's first record is k + 1", k + 1, journalSeqOf(next.get(0)));
        }
    }

    @Test
    public void conflictCapacityAndInvalidJournalEveryCopy() {
        try (Replica r = fresh()) {
            at(r, 192L, create(1, PRICE));
            r.h.drain();

            // Two copies of a conflicting payload: two record 3 result 2 at consecutive seqs.
            at(r, 320L, create(1, PRICE + 100_000_000L));
            at(r, 448L, create(1, PRICE + 100_000_000L));
            final List<byte[]> conflicts = r.h.drain();
            assertEquals(2, conflicts.size());
            assertEquals(DurableCommandOutcome.CONFLICT, record3(conflicts.get(0)).result());
            assertEquals(DurableCommandOutcome.CONFLICT, record3(conflicts.get(1)).result());
            assertEquals(List.of(3L, 4L), List.of(journalSeqOf(conflicts.get(0)), journalSeqOf(conflicts.get(1))));

            // Two above-ceiling template 9 copies: two INVALID records at consecutive seqs.
            final byte[] above = withHeaderVersion(durableCreate(5, 500, 9005, 1, 59_000, 1), 13);
            final List<byte[]> invalid = r.apply(new Entry(above, 576L, 1_576L));
            invalid.addAll(r.apply(new Entry(above, 704L, 1_704L)));
            assertEquals(2, invalid.size());
            assertEquals(DurableCommandOutcome.INVALID, record3(invalid.get(0)).result());
            assertEquals(DurableCommandOutcome.INVALID, record3(invalid.get(1)).result());
            assertEquals(List.of(5L, 6L), List.of(journalSeqOf(invalid.get(0)), journalSeqOf(invalid.get(1))));
            assertEquals("neither CONFLICT nor INVALID is recorded", 1, r.h.engine.commandLedger().size());

            // Ledger full: two CAPACITY copies at consecutive seqs.
            fillLedger(r.h.engine);
            at(r, 832L, create(900_001, PRICE));
            at(r, 960L, create(900_001, PRICE));
            final List<byte[]> capacity = r.h.drain();
            assertEquals(2, capacity.size());
            assertEquals(DurableCommandOutcome.CAPACITY, record3(capacity.get(0)).result());
            assertEquals(DurableCommandOutcome.CAPACITY, record3(capacity.get(1)).result());
            assertEquals(List.of(7L, 8L), List.of(journalSeqOf(capacity.get(0)), journalSeqOf(capacity.get(1))));
            assertEquals("CAPACITY is not recorded", DurableCommandLedger.MAX_ENTRIES, r.h.engine.commandLedger().size());
            assertEquals(0L, r.h.engine.suppressedDuplicateCount());
            assertEquals(9L, r.h.engine.meIdentity().nextJournalSeq());
        }
    }

    @Test
    public void equalDuplicateAtFullCapacityIsSuppressed() {
        try (Replica r = fresh()) {
            final DurableOrderIntent c = create(1, PRICE);
            final DurableCommandOutcome original = at(r, 192L, c);
            r.h.drain();
            fillLedger(r.h.engine);
            final long next = r.h.engine.meIdentity().nextJournalSeq();

            final DurableCommandOutcome retry = at(r, 320L, c);
            assertEquals("the prior outcome, not CAPACITY", original, retry);
            assertEquals("no frame, not a CAPACITY record", List.of(), r.h.drain());
            assertEquals(1L, r.h.engine.suppressedDuplicateCount());
            assertEquals(next, r.h.engine.meIdentity().nextJournalSeq());
        }
    }

    @Test
    public void duplicateOfARecordedRejectionIsSuppressed() {
        try (Replica r = fresh()) {
            final DurableOrderIntent unknownMarket =
                    new DurableOrderIntent(17, 1, 100, 9001, 0, PRICE, QTY, 0, 2, 0, 0, 0);
            final DurableOrderIntent unknownLeg =
                    new DurableOrderIntent(17, 2, 100, 9002, 777, 0, 0, 0, BehaviourTwoHarness.MARKET, 1, 0, 0);
            assertEquals(DurableCommandOutcome.UNKNOWN_MARKET, at(r, 192L, unknownMarket).result());
            assertEquals(DurableCommandOutcome.UNKNOWN_LEG, at(r, 320L, unknownLeg).result());
            final List<byte[]> first = r.h.drain();
            assertEquals(2, first.size());
            assertEquals("one seq each", List.of(2L, 3L), List.of(journalSeqOf(first.get(0)), journalSeqOf(first.get(1))));
            assertEquals("both are recorded", 2, r.h.engine.commandLedger().size());

            assertEquals(DurableCommandOutcome.UNKNOWN_MARKET, at(r, 448L, unknownMarket).result());
            assertEquals(DurableCommandOutcome.UNKNOWN_LEG, at(r, 576L, unknownLeg).result());
            assertEquals("equal retries add no frame", List.of(), r.h.drain());
            assertEquals(2L, r.h.engine.suppressedDuplicateCount());
            assertEquals(4L, r.h.engine.meIdentity().nextJournalSeq());
        }
    }

    @Test
    public void suppressionIsTheSameAfterSnapshotRestore() {
        final DurableOrderIntent c = create(1, PRICE);
        try (Replica a = fresh()) {
            at(a, 192L, c);
            a.h.drain();
            final byte[] image = a.snapshot();
            try (Replica b = Replica.restoredFrom(image)) {
                assertEquals(a.h.engine.meIdentity().toString(), b.h.engine.meIdentity().toString());

                at(a, 320L, c);
                at(b, 320L, c);
                assertEquals("no frame on the never-stopped replica", List.of(), a.h.drain());
                assertEquals("no frame on the restored replica", List.of(), b.h.drain());

                at(a, 448L, create(2, PRICE));
                at(b, 448L, create(2, PRICE));
                final List<byte[]> fa = a.h.drain();
                final List<byte[]> fb = b.h.drain();
                assertFramesEqual("the later frames", fa, fb);
                assertEquals(3, templateId(fa.get(0)));
                assertEquals(3L, journalSeqOf(fa.get(0)));
                assertArrayEquals(a.snapshot(), b.snapshot());
            }
        }
    }
}
