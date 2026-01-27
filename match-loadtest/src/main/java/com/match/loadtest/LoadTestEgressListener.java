package com.match.loadtest;

import io.aeron.cluster.client.EgressListener;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;

/**
 * Listens to responses from the Aeron Cluster during load testing
 */
public class LoadTestEgressListener implements EgressListener {

    private final MetricsCollector metrics;
    private volatile long messageCount = 0;

    public LoadTestEgressListener(MetricsCollector metrics) {
        this.metrics = metrics;
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
