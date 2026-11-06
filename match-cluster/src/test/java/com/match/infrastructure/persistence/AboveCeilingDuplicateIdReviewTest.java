// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.application.engine.BehaviourTwoHarness;
import com.match.domain.commands.DurableCommandOutcome;
import org.junit.Test;

import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S2 review addition (S2/review.md): contract section 6 "duplicate ids are not consulted:
 * the ceiling check precedes the ledger". An above-ceiling template 9 that re-sends an already
 * APPLIED command id is still journaled INVALID, the ledger and the book are untouched, and the
 * next v12 retry of the same id is the ledger's normal duplicate answer: from RC2 S3 (X4) a
 * behaviour-2 equal duplicate, suppressed (nothing journaled, no journalSeq consumed).
 */
public class AboveCeilingDuplicateIdReviewTest {

    @Test
    public void aboveCeilingRetryOfAnAppliedIdIsInvalidAndLeavesTheLedgerAlone() {
        try (Replica r = new Replica()) {
            r.apply(new Entry(engineConfig(1, 2, G, 12, 50_000), 64L, 1_000L));
            r.h.drain();
            final byte[] create = durableCreate(1, 100, 9001, 0, 59_000, 1);
            final List<byte[]> first = r.apply(new Entry(create, 192L, 1_001L));
            final var applied = BehaviourTwoHarness.record3(first.get(0));
            assertEquals(DurableCommandOutcome.APPLIED, applied.result());
            final long orderId = applied.orderId();
            assertEquals(1, r.h.engine.ledgerV2().size());

            final List<byte[]> above = r.apply(new Entry(withHeaderVersion(create, 13), 320L, 1_002L));
            assertEquals(1, above.size());
            final var invalid = BehaviourTwoHarness.record3(above.get(0));
            assertEquals("ceiling precedes the ledger", DurableCommandOutcome.INVALID, invalid.result());
            assertEquals(0L, invalid.orderId());
            assertEquals(320L, invalid.appliedPosition());
            assertEquals("ledger size unchanged", 1, invalid.ledgerSize());
            assertEquals(1, r.h.engine.ledgerV2().size());
            assertEquals(1, r.demuxer.aboveCeilingInvalidCount());

            // The v12 retry still meets the ORIGINAL ledger entry (the INVALID was never recorded):
            // a behaviour-2 equal duplicate, suppressed by RC2 S3 X4.
            final long seqBeforeRetry = r.h.engine.meIdentity().nextJournalSeq();
            final List<byte[]> retry = r.apply(new Entry(create, 448L, 1_003L));
            assertTrue("the equal duplicate journals nothing", retry.isEmpty());
            assertEquals(1L, r.h.engine.suppressedDuplicateCount());
            assertEquals(1, r.h.engine.ledgerV2().size());
            assertEquals("the retry consumes no journalSeq", seqBeforeRetry,
                    r.h.engine.meIdentity().nextJournalSeq());
            // RC2 S4: the ledger v2 keeps the original outcome under the core-shaped id (9001, 1 << 2).
            assertEquals("the original APPLIED outcome is still the recorded one", orderId,
                    r.h.engine.ledgerV2().get(9001, 1L << 2).orderId());
            assertEquals(0, r.demuxer.applyErrorCount());
        }
    }
}
