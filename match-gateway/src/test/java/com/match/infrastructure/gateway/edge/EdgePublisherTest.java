// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.gateway.edge;

import com.match.infrastructure.gateway.state.GatewayStateManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for EdgePublisher's frame-priority queueing (match#99 item 2).
 *
 * The periodic EDGE_CACHE bundles (and any live full book snapshot) are the
 * relay's only resync path: they reset its snapshotVersion and trim its delta
 * buffer. A drop-oldest single queue let a burst of live deltas evict that
 * snapshot before it was sent. The fix routes snapshot/bundle frames to a
 * separate lane that deltas cannot evict and that is flushed first.
 *
 * The publisher is exercised WITHOUT a live connection: scheduleDrain()
 * early-outs while {@code channel} is null, so published frames simply
 * accumulate in the two lanes and can be inspected. Reflection reaches the
 * private constructor and queues (same package, but they are private).
 */
public class EdgePublisherTest {

    private static final String SNAPSHOT_BUNDLE =
        "{\"type\":\"EDGE_CACHE\",\"frame\":{\"type\":\"BOOK_SNAPSHOT\",\"marketId\":1,\"bookVersion\":100}}";
    private static final String LIVE_SNAPSHOT =
        "{\"type\":\"BOOK_SNAPSHOT\",\"marketId\":1,\"bookVersion\":100,\"bids\":[],\"asks\":[]}";

    private EdgePublisher publisher;

    @Before
    public void setUp() throws Exception {
        Constructor<EdgePublisher> ctor = EdgePublisher.class.getDeclaredConstructor(
            URI.class, String.class, GatewayStateManager.class);
        ctor.setAccessible(true);
        // No connection is made and no start() is called; the event loop group
        // is created by the constructor and torn down in tearDown.
        publisher = ctor.newInstance(new URI("wss://relay.invalid/publish"), "token", null);
    }

    @After
    public void tearDown() {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    public void snapshotBundleSurvivesDeltaPressureThatEvictsTheNormalLane() throws Exception {
        int queueCap = intField("QUEUE_CAPACITY");

        // A bundle snapshot lands, then a delta flood far exceeding the normal
        // lane's capacity. With a single drop-oldest queue the snapshot would be
        // evicted before it could ship; the priority lane keeps it.
        publisher.publish(SNAPSHOT_BUNDLE);
        for (int v = 0; v < queueCap * 2; v++) {
            publisher.publish("{\"type\":\"BOOK_DELTA\",\"marketId\":1,\"bookVersion\":" + v + "}");
        }

        Collection<?> normal = queueField("queue");
        Collection<?> priority = queueField("priorityQueue");

        assertTrue("bundle snapshot must be retained under delta pressure", priority.contains(SNAPSHOT_BUNDLE));
        assertEquals("normal lane stays bounded at capacity", queueCap, normal.size());
        assertTrue("deltas were dropped, not the snapshot", publisher.dropped.get() > 0);
    }

    @Test
    public void liveBookSnapshotIsRoutedToThePriorityLane() throws Exception {
        String delta = "{\"type\":\"BOOK_DELTA\",\"marketId\":1,\"bookVersion\":101}";
        publisher.publish(LIVE_SNAPSHOT);
        publisher.publish(delta);

        Collection<?> normal = queueField("queue");
        Collection<?> priority = queueField("priorityQueue");

        assertTrue("live full snapshot is prioritized", priority.contains(LIVE_SNAPSHOT));
        assertFalse("a delta is not prioritized", priority.contains(delta));
        assertTrue("a delta uses the normal lane", normal.contains(delta));
    }

    @Test
    public void priorityLaneIsBoundedAndKeepsTheNewestSnapshots() throws Exception {
        int priorityCap = intField("PRIORITY_QUEUE_CAPACITY");

        // Overfill the priority lane. Older snapshots are already superseded by
        // newer ones, so dropping them is safe; the lane stays bounded and holds
        // the freshest resync frame.
        int total = priorityCap + 50;
        for (int v = 0; v < total; v++) {
            publisher.publish("{\"type\":\"EDGE_CACHE\",\"frame\":{\"type\":\"BOOK_SNAPSHOT\",\"bookVersion\":" + v + "}}");
        }

        Collection<?> priority = queueField("priorityQueue");
        assertEquals("priority lane is bounded", priorityCap, priority.size());
        String newest = "{\"type\":\"EDGE_CACHE\",\"frame\":{\"type\":\"BOOK_SNAPSHOT\",\"bookVersion\":" + (total - 1) + "}}";
        assertTrue("freshest snapshot retained", priority.contains(newest));
    }

    private Collection<?> queueField(String name) throws Exception {
        Field f = EdgePublisher.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Collection<?>) f.get(publisher);
    }

    private int intField(String name) throws Exception {
        Field f = EdgePublisher.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
