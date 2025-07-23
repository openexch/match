package com.match.infrastructure.persistence;

import com.match.application.engine.Engine;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.Cluster.Role;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.logbuffer.Header;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import org.agrona.DirectBuffer;
import com.match.infrastructure.Logger;

public class AppClusteredService implements ClusteredService {
    private static final Logger logger = Logger.getLogger(AppClusteredService.class);

    private final Engine engine = new Engine();
    private final ClientSessions clientSessions = new ClientSessions();
    private final SessionMessageContextImpl context = new SessionMessageContextImpl(clientSessions);
    private final TimerManager timerManager = new TimerManager(context);
    private final SbeDemuxer sbeDemuxer = new SbeDemuxer(engine);

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage) {
        context.setIdleStrategy(cluster.idleStrategy());
        timerManager.setCluster(cluster);
        if (snapshotImage != null) {
            // TODO: Load snapshot
            System.out.println("loading snapshot");
        }
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp) {
        context.setClusterTime(timestamp);
        clientSessions.addSession(session);
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason) {
        context.setClusterTime(timestamp);
        clientSessions.removeSession(session);
    }

    @Override
    public void onSessionMessage(
        final ClientSession session,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header) {
        context.setSessionContext(session, timestamp);
        try {
            sbeDemuxer.dispatch(buffer,offset,length);
        } catch (Exception e) {
            System.out.printf("order exception %s", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp) {
        context.setClusterTime(timestamp);
        timerManager.onTimerEvent(correlationId, timestamp);
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication) {
        // TODO: Snapshot engine state
    }

    @Override
    public void onRoleChange(final Role newRole) {
        System.out.println("role changed: " + newRole);
    }

    @Override
    public void onTerminate(final Cluster cluster) {
        logger.info("Terminating cluster service");
        // Close all active sessions immediately
        clientSessions.getAllSessions().forEach(session -> {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing session %d: %s", session, e.getMessage());
            }
        });
        // Close engine
        engine.close();

        logger.info("Cluster service terminated");
    }
} 