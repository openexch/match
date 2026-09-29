// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import io.aeron.DirectBufferVector;
import io.aeron.cluster.service.ClientSession;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.DirectBuffer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Egress is leader-local output, drained inside the apply callback. A frame larger than the
 * session publication's maxMessageLength makes Aeron throw IllegalArgumentException from offer;
 * that must not reach the apply-failure fence and halt the leader (the next leader would hit the
 * same frame). It is counted like any other lost egress frame and the drain continues.
 */
public class AppClusteredServiceOversizeEgressTest {

    private static final int MAX_MESSAGE_LENGTH = 8192; // term-length=64k / 8

    /** Mirrors Publication.checkMaxMessageLength for the one behaviour under test. */
    private static final class BoundedSession implements ClientSession {
        final List<Integer> delivered = new ArrayList<>();
        public long id() { return 1; }
        public int responseStreamId() { return 102; }
        public String responseChannel() { return "aeron:udp?term-length=64k"; }
        public byte[] encodedPrincipal() { return new byte[0]; }
        public void close() { }
        public boolean isClosing() { return false; }
        public long offer(DirectBuffer buffer, int offset, int length) {
            if (length > MAX_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("message exceeds maxMessageLength of "
                        + MAX_MESSAGE_LENGTH + ", length=" + length);
            }
            delivered.add(length);
            return 64;
        }
        public long offer(DirectBufferVector[] vectors) { throw new UnsupportedOperationException(); }
        public long tryClaim(int length, BufferClaim claim) { throw new UnsupportedOperationException(); }
    }

    @Test
    public void oversizedFrameIsCountedAndSkippedWithoutFailingTheCallback() {
        final AppClusteredService svc = new AppClusteredService();
        final Queue<AppClusteredService.QueuedMessage> queue = new ArrayBlockingQueue<>(4);
        queue.add(new AppClusteredService.QueuedMessage(new byte[16824], 16824)); // observed OrderStatusBatch
        queue.add(new AppClusteredService.QueuedMessage(new byte[99], 99));
        final AtomicLong bytes = new AtomicLong(16824 + 99);
        final BoundedSession session = new BoundedSession();

        final boolean delivered = svc.drainQueue(queue, bytes, List.of(session));

        assertTrue("the following frame is still delivered", delivered);
        assertEquals(List.of(99), session.delivered);
        assertEquals(0, queue.size());
        assertEquals(0L, bytes.get());
        assertEquals(1L, svc.egressOversizeDropTotal());
    }
}
