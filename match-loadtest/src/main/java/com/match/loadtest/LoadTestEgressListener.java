// SPDX-License-Identifier: Apache-2.0
package com.match.loadtest;

import com.match.infrastructure.generated.MessageHeaderDecoder;
import com.match.infrastructure.generated.OrderStatusBatchDecoder;
import com.match.infrastructure.generated.OrderStatusUpdateDecoder;
import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;

/**
 * Listens to responses from the Aeron Cluster during load testing
 */
public class LoadTestEgressListener implements EgressListener {

    private final MetricsCollector metrics;
    private final Long2LongHashMap inFlight;
    private volatile long messageCount = 0;

    // Reused decoders — this runs on the duty thread inside pollEgress, so it must not allocate.
    private final MessageHeaderDecoder sbeHeader = new MessageHeaderDecoder();
    private final OrderStatusBatchDecoder statusBatch = new OrderStatusBatchDecoder();
    private final OrderStatusUpdateDecoder statusUpdate = new OrderStatusUpdateDecoder();

    public LoadTestEgressListener(MetricsCollector metrics, Long2LongHashMap inFlight) {
        this.metrics = metrics;
        this.inFlight = inFlight;
    }

    /**
     * Match a status back to the order that caused it and record the COMMITTED round trip.
     *
     * <p>Only the FIRST status for an order counts: the entry is removed on match, so a later fill or
     * cancel for the same order cannot record a second, longer sample. A status whose omsOrderId is
     * not in the map is ignored — another generator's order, or one sent before the warmup reset.</p>
     */
    private void recordAck(long omsOrderId, long nowNs) {
        final long sentNs = inFlight.remove(omsOrderId);
        if (sentNs != inFlight.missingValue()) {
            metrics.recordAck(nowNs - sentNs);
        }
    }

    @Override
    public void onMessage(
        long clusterSessionId,
        long timestamp,
        DirectBuffer buffer,
        int offset,
        int length,
        Header header
    ) {
        // Received response from cluster
        messageCount++;

        if (length >= MessageHeaderDecoder.ENCODED_LENGTH) {
            sbeHeader.wrap(buffer, offset);
            final long nowNs = System.nanoTime();
            if (sbeHeader.templateId() == OrderStatusBatchDecoder.TEMPLATE_ID) {
                statusBatch.wrapAndApplyHeader(buffer, offset, sbeHeader);
                final OrderStatusBatchDecoder.OrdersDecoder orders = statusBatch.orders();
                while (orders.hasNext()) {
                    orders.next();
                    recordAck(orders.omsOrderId(), nowNs);
                }
            } else if (sbeHeader.templateId() == OrderStatusUpdateDecoder.TEMPLATE_ID) {
                statusUpdate.wrapAndApplyHeader(buffer, offset, sbeHeader);
                recordAck(statusUpdate.omsOrderId(), nowNs);
            }
        }

        // Optional: Parse and log responses periodically
        if (messageCount % 10000 == 0) {
            System.out.printf("[Egress] Received %,d responses from cluster%n", messageCount);
        }
    }

    @Override
    public void onNewLeader(
        long clusterSessionId,
        long leadershipTermId,
        int leaderMemberId,
        String ingressEndpoints
    ) {
        System.out.printf(
            "[Egress] New cluster leader: memberId=%d, termId=%d, endpoints=%s%n",
            leaderMemberId, leadershipTermId, ingressEndpoints
        );
    }

    @Override
    public void onSessionEvent(
        long correlationId,
        long clusterSessionId,
        long leadershipTermId,
        int leaderMemberId,
        io.aeron.cluster.codecs.EventCode code,
        String detail
    ) {
        System.out.printf(
            "[Egress] Session event: code=%s, detail=%s%n",
            code, detail
        );
    }

    public long getMessageCount() {
        return messageCount;
    }
}
