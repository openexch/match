// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.domain.enums.OrderSide;
import com.match.infrastructure.websocket.PublisherRig;
import io.aeron.cluster.service.Cluster;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * A node that becomes LEADER requests a resnapshot right after its
 * {@code isLeader} write. A promoted follower's publisher chain is its own (it encoded while broadcast()
 * discarded), and the periodic bump waits up to 10 s on a node that led recently, so without
 * the request a surviving session gets no BookSnapshot after an ME failover. Flushes are counted in calls
 * ({@link PublisherRig#flushAll()} then {@link EpochHelloNode#busyFlush()}), never in wall time.
 */
public class MeResnapshotOnLeaderTest {

    /** A follower restored from the image, with its session open, books versioned on both markets after the
     *  restore (no restored-book republish dependence), its publishers flushed as a follower, and a recent lead (1 s ago). */
    private static EpochHelloNode followerWithBooks(final byte[] image, final long sessionId) {
        final EpochHelloNode n = new EpochHelloNode(sessionId).restoreByCodec(image);
        n.attach(Cluster.Role.FOLLOWER, 5);
        n.open();
        n.rig.flushAll(); // follower: the session-open generation is consumed, the snapshot discarded
        n.create(1, 10_000, 9101, OrderSide.BID, 59_500);
        n.create(2, 10_128, 9102, OrderSide.BID, 60_000);
        n.rig.flushAll(); // follower chain advances, nothing enqueued
        n.ledAgo(1_000);
        return n;
    }

    @Test
    public void resnapshotReachesSessionsWithinTwoLeaderFlushes() {
        final EpochHelloNode n = followerWithBooks(EpochHelloNode.image(), 77);
        assertTrue("a follower enqueues nothing", n.session.offered.isEmpty());
        n.promote(6);
        for (int i = 0; i < 2; i++) {
            n.rig.flushAll();
            n.busyFlush();
        }
        for (int m : EpochHelloNode.MARKETS) {
            final List<PublisherRig.BookFrame> frames = n.received(m);
            final PublisherRig.BookFrame snap = frames.stream().filter(PublisherRig.BookFrame::isSnapshot)
                    .findFirst().orElse(null);
            if (snap == null) {
                fail("expected a BookSnapshot for market " + m + " within 2 leader flushes after the role change,"
                        + " got none");
            }
            assertEquals("the snapshot carries the market's book version", n.bookVersion(m), snap.bookVersion);
            assertTrue("the snapshot holds the market's levels", snap.bids > 0);
        }
        assertEquals("market 1: the image's bid and the new one", 2, n.received(1).stream()
                .filter(PublisherRig.BookFrame::isSnapshot).findFirst().get().bids);
    }

    @Test
    public void resnapshotOnLeaderIsEgressOnly() {
        final byte[] image = EpochHelloNode.image();
        final EpochHelloNode leader = followerWithBooks(image, 81);
        final EpochHelloNode a = followerWithBooks(image, 82);
        final EpochHelloNode b = followerWithBooks(image, 83);
        final byte[] before = leader.snapshot();
        assertArrayEquals(before, a.snapshot());
        assertArrayEquals(before, b.snapshot());

        leader.promote(6);
        a.demote();
        b.demote();
        for (int i = 0; i < 3; i++) {
            for (EpochHelloNode n : List.of(leader, a, b)) {
                n.rig.flushAll();
                if (n == leader) {
                    n.busyFlush();
                }
            }
        }
        assertFalse("the leader delivered its resnapshot", leader.received(1).isEmpty());
        for (EpochHelloNode f : List.of(a, b)) {
            assertEquals("a follower enqueues nothing", 0, f.marketDataQueued());
            assertTrue("a follower's session received nothing", f.session.offered.isEmpty());
        }
        // nothing replicated changed: the snapshot is the same bytes on every member, before and after
        assertArrayEquals(before, leader.snapshot());
        assertArrayEquals(before, a.snapshot());
        assertArrayEquals(before, b.snapshot());
    }

    @Test
    public void generationBumpAfterIsLeaderNeverLosesTheSnapshot() {
        final EpochHelloNode n = followerWithBooks(EpochHelloNode.image(), 91);
        // a mutation not yet flushed: the probe's flush (between the two writes) has something to encode
        n.create(1, 10_256, 9103, OrderSide.BID, 59_600);
        n.roleChangeProbe(n.rig::flushAll);
        n.promote(6);
        n.roleChangeProbe(null);
        n.create(1, 10_384, 9104, OrderSide.BID, 59_700);
        n.rig.flushAll();
        n.busyFlush();
        n.create(1, 10_512, 9105, OrderSide.BID, 59_800);
        n.rig.flushAll();
        n.busyFlush();
        final List<PublisherRig.BookFrame> frames = n.received(1);
        if (frames.stream().noneMatch(PublisherRig.BookFrame::isSnapshot)) {
            fail("no BookSnapshot enqueued after promotion: " + frames);
        }
        EpochHelloNode.assertChainedAfterFirstSnapshot("market 1", frames);
        assertEquals("the last frame carries the last command's version", n.bookVersion(1),
                frames.get(frames.size() - 1).bookVersion);
    }
}
