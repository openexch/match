// SPDX-License-Identifier: Apache-2.0
package com.match.infrastructure.persistence;

import com.match.domain.FixedPoint;
import com.match.infrastructure.generated.CreateOrderEncoder;
import com.match.infrastructure.generated.MessageHeaderEncoder;
import com.match.infrastructure.generated.OrderSide;
import com.match.infrastructure.generated.OrderType;
import com.match.infrastructure.metrics.NodeMetrics;
import com.match.infrastructure.metrics.NodeMetricsServer;
import com.openexchange.cluster.auth.ClusterSessionClass;
import io.aeron.logbuffer.Header;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@code match_log_position}: the live cluster log position of the last entry this member applied. It follows
 * every applied ingress message and timer entry, not only snapshots, and it is rendered on /metrics.
 */
public class LogPositionGaugeTest {

    static final String DID_NOT_MOVE = "match_log_position did not move with the log";

    private static final int POSITION_BITS_TO_SHIFT = 16;

    @Test
    public void gaugeFollowsAppliedLogEntries() throws Exception {
        assertGaugeFollowsLog(true);
    }

    /** Control: with the update switched off the same assertion fails, so the test above can catch a stale gauge. */
    @Test
    public void controlWithoutTheUpdateTheTestFails() {
        final AssertionError failure = assertThrows(AssertionError.class, () -> assertGaugeFollowsLog(false));
        System.out.println("control failure: " + failure.getMessage());
        assertEquals(DID_NOT_MOVE, failure.getMessage());
    }

    private static void assertGaugeFollowsLog(final boolean recordLogPosition) throws Exception {
        final MeStubCluster cluster = new MeStubCluster();
        final AppClusteredService service = MeStubCluster.attach(new AppClusteredService(), cluster);
        service.recordLogPosition = recordLogPosition;
        final Field timer = AppClusteredService.class.getDeclaredField("flushTimerScheduled");
        timer.setAccessible(true);
        timer.setBoolean(service, true);
        final NodeMetricsServer server = new NodeMetricsServer(nodeMetricsOf(service));

        final String before = server.render();
        assertTrue("series rendered:\n" + before, before.contains("# TYPE match_log_position gauge\n"));
        assertEquals("-1", series(before, "match_log_position"));

        final long[] seen = new long[3];
        final UnsafeBuffer frame = createOrder(1001);
        final Header header = header(0, 0, 128);
        service.onSessionMessage(TestAuth.session(1, ClusterSessionClass.ADMIN), 10, frame, 0, frame.capacity(), header);
        seen[0] = gauge(server);
        requireMoved(seen[0], header.position());

        final Header next = header(1, 64, 96);
        service.onSessionMessage(TestAuth.session(1, ClusterSessionClass.ADMIN), 20, createOrder(1002), 0,
                frame.capacity(), next);
        seen[1] = gauge(server);
        requireMoved(seen[1], next.position());

        // A timer entry is a log entry too; its position comes from the cluster, as Aeron hands it.
        cluster.logPosition = next.position() + 4_096;
        service.onTimerEvent(1L, 30);
        seen[2] = gauge(server);
        requireMoved(seen[2], cluster.logPosition);

        assertTrue("strictly increasing: " + seen[0] + " " + seen[1] + " " + seen[2],
                seen[0] < seen[1] && seen[1] < seen[2]);
    }

    private static void requireMoved(final long gauge, final long logPosition) {
        if (gauge != logPosition) {
            throw new AssertionError(DID_NOT_MOVE);
        }
    }

    private static long gauge(final NodeMetricsServer server) {
        return Long.parseLong(series(server.render(), "match_log_position"));
    }

    private static String series(final String body, final String name) {
        for (String line : body.split("\n")) {
            if (line.startsWith(name + " ")) {
                return line.substring(name.length() + 1);
            }
        }
        throw new AssertionError("no " + name + " series in:\n" + body);
    }

    private static NodeMetrics nodeMetricsOf(final AppClusteredService service) throws Exception {
        final Field f = AppClusteredService.class.getDeclaredField("nodeMetrics");
        f.setAccessible(true);
        return (NodeMetrics) f.get(service);
    }

    /** A data frame header whose {@link Header#position()} is the end of a frame of that length. */
    private static Header header(final int termId, final int termOffset, final int frameLength) {
        final Header header = new Header(0, POSITION_BITS_TO_SHIFT);
        final UnsafeBuffer buffer = new UnsafeBuffer(new byte[DataHeaderFlyweight.HEADER_LENGTH]);
        buffer.putInt(DataHeaderFlyweight.FRAME_LENGTH_FIELD_OFFSET, frameLength);
        buffer.putInt(DataHeaderFlyweight.TERM_OFFSET_FIELD_OFFSET, termOffset);
        buffer.putInt(DataHeaderFlyweight.TERM_ID_FIELD_OFFSET, termId);
        header.buffer(buffer);
        header.offset(0);
        return header;
    }

    private static UnsafeBuffer createOrder(final long omsOrderId) {
        final UnsafeBuffer b = new UnsafeBuffer(new byte[CreateOrderEncoder.BLOCK_LENGTH + MessageHeaderEncoder.ENCODED_LENGTH]);
        new CreateOrderEncoder().wrapAndApplyHeader(b, 0, new MessageHeaderEncoder())
                .userId(7).omsOrderId(omsOrderId).marketId(1).orderSide(OrderSide.BID)
                .orderType(OrderType.LIMIT).price(FixedPoint.fromDouble(60000))
                .quantity(FixedPoint.fromDouble(1));
        return b;
    }
}
