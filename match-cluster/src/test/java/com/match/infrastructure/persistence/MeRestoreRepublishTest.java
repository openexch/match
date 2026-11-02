// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.domain.FixedPoint;
import com.match.domain.commands.CreateOrderCommand;
import com.match.domain.enums.OrderSide;
import com.match.domain.enums.OrderType;
import com.match.application.engine.Engine;
import com.match.infrastructure.websocket.PublisherRig;
import io.aeron.cluster.service.Cluster;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.match.application.engine.BehaviourTwoHarness.G;
import static com.match.infrastructure.persistence.BehaviourTwoLog.*;
import static org.junit.Assert.*;

/**
 * RC2 S13 W-ME3 (contract 8.3, F-S13-7, RC-B1, CL-B-1 item 3): after the onStart restore step every configured
 * market's book version is raised to the snapshot position P and its top of book republished past the dirty
 * gate. Before, a restored ARRAY node published an empty top with book version 0 for every market without a
 * command since the restore, so a quiet market's resnapshot sent nothing (and the W-ME2 guard keeps it silent).
 */
public class MeRestoreRepublishTest {

    /** The stub cluster's log position at onStart: the snapshot position P. */
    private static final long P = 4_096;

    private static S13Node restoredLeader(final byte[] image, final long sessionId) {
        final S13Node n = new S13Node(sessionId);
        assertEquals(P, n.cluster.logPosition);
        n.restoreByOnStartStep(image);
        n.attach(Cluster.Role.LEADER, 5);
        return n;
    }

    @Test
    public void restoredQuietMarketPublishesItsBookWithAPositiveVersion() {
        final S13Node n = restoredLeader(S13Node.image(), 71);
        n.open(); // one generation bump (session open)
        n.rig.flushAll();
        n.busyFlush();
        final PublisherRig.BookFrame snap = n.received(1).stream().filter(PublisherRig.BookFrame::isSnapshot)
                .findFirst().orElse(null);
        if (snap == null) {
            fail("expected a BookSnapshot for market 1 after the restore, got none");
        }
        assertEquals("the restored bid", 1, snap.bids);
        assertEquals("bookVersion == P (the image's position)", P, snap.bookVersion);
        // market 2 (empty in the image) carries P too: an empty snapshot of a known state (W-ME2)
        final List<PublisherRig.BookFrame> m2 = n.received(2);
        assertEquals("market 2: one empty snapshot at P", 1, m2.size());
        assertTrue(m2.get(0).isSnapshot());
        assertEquals(0, m2.get(0).bids + m2.get(0).asks);
        assertEquals(P, m2.get(0).bookVersion);
    }

    @Test
    public void restoreRepublishIsEgressOnly() {
        final byte[] image = S13Node.image();
        final S13Node republished = restoredLeader(image, 72);
        final S13Node plain = new S13Node(73).restoreByCodec(image); // the codec only: no republish
        republished.open();
        for (int i = 0; i < 2; i++) {
            republished.rig.flushAll();
            republished.busyFlush();
        }
        assertFalse("the republished node delivered its book", republished.received(1).isEmpty());
        // snapshot bytes equal before (the image) and after the restore plus republish plus flushes
        assertArrayEquals(image, plain.snapshot());
        assertArrayEquals(image, republished.snapshot());
    }

    @Test
    public void restoredNodeConvergesAtTheNextCommand() {
        final List<byte[]> frames = new ArrayList<>();
        frames.add(engineConfigWith(1, 2, G, 12, 50_000, false, 4096, 100, 0, S13Node.MARKETS));
        frames.add(durableCreate(1, 100, 9001, 0, 59_000, 1));
        final List<Entry> log = positioned(frames);
        final long q = 8_192;
        try (Replica replayed = new Replica()) {
            replayed.apply(log, 0, log.size());
            final byte[] image = replayed.snapshot();
            final S13Node restored = restoredLeader(image, 74);
            assertEquals("restored: labelled P before its first command", P, restored.bookVersion(1));
            assertEquals("replayed: labelled with its last command's position", 192,
                    replayed.h.engine.getEngine(1).getBookVersion());

            // the same next command at log position q on both nodes
            restored.create(1, q, 9201, OrderSide.BID, 59_100);
            replayed.h.engine.setCurrentLogPosition(q);
            final CreateOrderCommand cmd = new CreateOrderCommand();
            cmd.setUserId(500 + 9201);
            cmd.setOmsOrderId(9201);
            cmd.setPrice(FixedPoint.fromDouble(59_100));
            cmd.setQuantity(FixedPoint.fromDouble(1.0));
            cmd.setTotalPrice(0L);
            cmd.setOrderSide(OrderSide.BID);
            cmd.setOrderType(OrderType.LIMIT);
            replayed.h.engine.acceptOrder(1, Engine.CMD_CREATE, cmd, restored.cluster.time);

            assertEquals("converged at the next command", replayed.h.engine.getEngine(1).getBookVersion(),
                    restored.bookVersion(1));
            assertEquals(q, restored.bookVersion(1));
            assertArrayEquals("same replicated state", replayed.snapshot(), restored.snapshot());
        }
    }
}
