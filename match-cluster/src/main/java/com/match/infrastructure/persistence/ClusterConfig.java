// SPDX-License-Identifier: Apache-2.0
/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.match.infrastructure.persistence;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.ConsensusModule;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import com.match.infrastructure.TransportConfig;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.NoOpLock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper class to simplify cluster configuration. This is sample code and is intended to show a mechanism for
 * managing the configuration of the components required for Aeron Cluster. This code may change between versions
 * and API compatibility is not guaranteed.
 */
public final class ClusterConfig
{
    /**
     * Ports per node when several members share a host. Zero by default now:
     * see {@link com.openexchange.cluster.ClusterPorts}. Kept as a named
     * constant because the historical layout is still what a one-host demo
     * wants, and because callers used to read this to mean "the stride".
     */
    public static final int PORTS_PER_NODE = com.openexchange.cluster.ClusterPorts.SHARED_HOST_STRIDE;

    /**
     * Offset from base port that the archive control channel is on.
     */
    public static final int ARCHIVE_CONTROL_PORT_OFFSET =
            com.openexchange.cluster.ClusterPorts.ARCHIVE_CONTROL_OFFSET;

    /**
     * Offset from base port that the client facing port is on for ingress.
     */
    public static final int CLIENT_FACING_PORT_OFFSET =
            com.openexchange.cluster.ClusterPorts.CLIENT_FACING_OFFSET;

    /**
     * Offset from base port that the member listens on for consensus traffic.
     */
    public static final int MEMBER_FACING_PORT_OFFSET =
            com.openexchange.cluster.ClusterPorts.MEMBER_FACING_OFFSET;

    /**
     * Offset from base port that the cluster log is on.
     */
    public static final int LOG_PORT_OFFSET = com.openexchange.cluster.ClusterPorts.LOG_OFFSET;

    /**
     * Offset from base port that the transfer of files is on.
     */
    public static final int TRANSFER_PORT_OFFSET =
            com.openexchange.cluster.ClusterPorts.TRANSFER_OFFSET;

    /**
     * Subdirectory into which archive files are stored.
     */
    public static final String ARCHIVE_SUB_DIR = "archive";

    /**
     * Subdirectory into which cluster files are stored.
     */
    public static final String CLUSTER_SUB_DIR = "cluster";

    private final int memberId;
    private final String ingressHostname;
    private final String clusterHostname;
    private final MediaDriver.Context mediaDriverContext;
    private final Archive.Context archiveContext;
    private final AeronArchive.Context aeronArchiveContext;
    private final ConsensusModule.Context consensusModuleContext;
    private final List<ClusteredServiceContainer.Context> clusteredServiceContexts;
    /** The member version every context carries (S4) and what a refusal by its validator does. */
    private com.openexchange.cluster.MemberCompat memberCompat = MatchMemberCompat.current();
    /**
     * Fail closed until the node installs the lifecycle's exit 78 (or a rig its explicit test refusal):
     * {@code NOT_INSTALLED} refuses every version.
     */
    private com.openexchange.cluster.MemberCompat.Refusal memberCompatRefusal =
            com.openexchange.cluster.MemberCompat.NOT_INSTALLED;
    /** The service, when it is the engine's, so its /ready {@code compat=} is the validator's own value. */
    private AppClusteredService engineService;

    ClusterConfig(
            final int memberId,
            final String ingressHostname,
            final String clusterHostname,
            final MediaDriver.Context mediaDriverContext,
            final Archive.Context archiveContext,
            final AeronArchive.Context aeronArchiveContext,
            final ConsensusModule.Context consensusModuleContext,
            final List<ClusteredServiceContainer.Context> clusteredServiceContexts)
    {
        this.memberId = memberId;
        this.ingressHostname = ingressHostname;
        this.clusterHostname = clusterHostname;
        this.mediaDriverContext = mediaDriverContext;
        this.archiveContext = archiveContext;
        this.aeronArchiveContext = aeronArchiveContext;
        this.consensusModuleContext = consensusModuleContext;
        this.clusteredServiceContexts = clusteredServiceContexts;
    }

    /**
     * Create a new ClusterConfig. This call allows for 2 separate lists of hostnames, so that there can be 'external'
     * addresses for ingress requests and 'internal' addresses that will handle all the cluster replication and
     * control traffic.
     *
     * @param startingMemberId   id for the first member in the list of entries.
     * @param memberId           id for this node.
     * @param ingressHostnames   list of hostnames that will receive ingress request traffic.
     * @param clusterHostnames   list of hostnames that will receive cluster traffic.
     * @param portBase           base port to derive remaining ports from.
     * @param parentDir          directory under which the persistent directories will be created.
     * @param clusteredService   instance of the clustered service that will run with this configuration.
     * @param additionalServices instances of additional clustered services that will run with this configuration.
     * @return configuration that wraps all aeron service configuration.
     */
    public static ClusterConfig create(
            final int startingMemberId,
            final int memberId,
            final List<String> ingressHostnames,
            final List<String> clusterHostnames,
            final int portBase,
            final File parentDir,
            final ClusteredService clusteredService,
            final ClusteredService... additionalServices)
    {
        if (memberId < startingMemberId || (startingMemberId + ingressHostnames.size()) <= memberId)
        {
            throw new IllegalArgumentException(
                    "memberId=" + memberId + " is invalid, should be " + startingMemberId +
                            " <= memberId < " + startingMemberId + ingressHostnames.size());
        }
        // S4 R-C3: the removed launch-mode settings refuse the boot (exit 78) before any Aeron component.
        refuseRemovedSettings();

        final String clusterMembers = clusterMembers(startingMemberId, ingressHostnames, clusterHostnames, portBase);

        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + memberId + "-driver";
        final File baseDir = new File(parentDir, "aeron-cluster-" + memberId);

        final String ingressHostname = ingressHostnames.get(memberId - startingMemberId);
        final String hostname = clusterHostnames.get(memberId - startingMemberId);

        // ==================== MEDIA DRIVER CONFIG ====================
        // Threading + idle are configurable (TRANSPORT_DRIVER_THREADING / TRANSPORT_IDLE_MODE):
        // DEDICATED + busy-spin (default) gives ultra-low latency on isolated cores but burns up
        // to 3 cores per node; SHARED + sleep lets a constrained host (cloud demo) run the same
        // cluster on a fraction of a core when quiet. termBufferSparseFile stays dense either way
        // for consistent latency.
        final ThreadingMode driverThreading = driverThreadingMode(TransportConfig.driverThreadingMode());
        final MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(driverThreading)
                // Memory: Pre-allocate term buffers for consistent latency (no sparse file faults)
                .termBufferSparseFile(false)
                // Idle strategies per TRANSPORT_IDLE_MODE (busy_spin default = lowest latency).
                .conductorIdleStrategy(TransportConfig.idleStrategySupplier().get())
                .senderIdleStrategy(TransportConfig.idleStrategySupplier().get())
                .receiverIdleStrategy(TransportConfig.idleStrategySupplier().get())
                .sharedIdleStrategy(TransportConfig.idleStrategySupplier().get())
                // Flow control
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                // Buffer sizes for high throughput
                .publicationTermBufferLength(16 * 1024 * 1024)  // 16MB publication term buffer
                .ipcTermBufferLength(16 * 1024 * 1024)          // 16MB IPC term buffer
                // Socket buffers - 4MB for high throughput (requires tuned OS)
                .socketSndbufLength(4 * 1024 * 1024)
                .socketRcvbufLength(4 * 1024 * 1024)
                // Timing
                .timerIntervalNs(1_000_000)  // 1ms timer resolution
                .clientLivenessTimeoutNs(10_000_000_000L)  // 10s client timeout
                // Loss/NAK handling
                .nakUnicastDelayNs(100_000)      // 100us NAK delay
                .nakMulticastMaxBackoffNs(1_000_000)  // 1ms max NAK backoff
                .retransmitUnicastDelayNs(100_000);   // 100us retransmit delay

        final AeronArchive.Context replicationArchiveContext = new AeronArchive.Context()
                .controlResponseChannel("aeron:udp?endpoint=" + hostname + ":0");

        // ==================== ULTRA-LOW LATENCY ARCHIVE CONFIG ====================
        final Archive.Context archiveContext = new Archive.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDir, ARCHIVE_SUB_DIR))
                .controlChannel(udpChannel(memberId, hostname, portBase))
                .archiveClientContext(replicationArchiveContext)
                .localControlChannel("aeron:ipc?term-length=16m")  // 16MB for high throughput
                .replicationChannel("aeron:udp?endpoint=" + hostname + ":0|term-length="
                        + TransportConfig.logTermLength())
                // Disable events for lower latency
                .recordingEventsEnabled(false)
                // Threading follows TRANSPORT_DRIVER_THREADING (DEDICATED default = low latency;
                // SHARED collapses the archive's recorder/replayer/conductor onto one thread on
                // constrained hosts).
                .threadingMode(archiveThreadingMode(TransportConfig.driverThreadingMode()))
                // Catalog and segment sizes
                .catalogCapacity(1024 * 1024)  // 1MB catalog for more recordings
                // Segment length sets archive RECLAMATION granularity, not throughput:
                // purgeSegments can only delete whole inactive segment files. 1GB
                // segments meant nothing was reclaimable until the log passed 1GB.
                // 64MB = multiple of the 16MB term buffer, reclaimable in usable units.
                .segmentFileLength(64 * 1024 * 1024)
                // Max concurrent recordings/replays
                .maxConcurrentRecordings(10)
                .maxConcurrentReplays(10);

        final AeronArchive.Context aeronArchiveContext = new AeronArchive.Context()
                .lock(NoOpLock.INSTANCE)
                .controlRequestChannel(archiveContext.localControlChannel())
                .controlRequestStreamId(archiveContext.localControlStreamId())
                .controlResponseChannel(archiveContext.localControlChannel())
                .aeronDirectoryName(aeronDirName);

        // Snapshot channel term-length is configurable (TRANSPORT_SNAPSHOT_TERM_LENGTH):
        // a snapshot publication maps a fresh 3-term buffer per cycle, so 64m = 192MB in
        // /dev/shm every snapshot. On a co-located box (two clusters, one tmpfs) that
        // exhausts the archive and fails the snapshot; nothing prunes, the log grows.
        // Small/co-located hosts set this down (snapshots are ~6MB); default stays 64m
        // (8MB max message) to preserve prod behavior. See TransportConfig#snapshotTermLength.
        final String snapshotChannel = "aeron:ipc?term-length=" + TransportConfig.snapshotTermLength();

        // ==================== ULTRA-LOW LATENCY CONSENSUS MODULE CONFIG ====================
        final ConsensusModule.Context consensusModuleContext = new ConsensusModule.Context()
                .clusterMemberId(memberId)
                .clusterMembers(clusterMembers)
                .clusterDir(new File(baseDir, CLUSTER_SUB_DIR))
                .archiveContext(aeronArchiveContext.clone())
                .serviceCount(1 + additionalServices.length)
                .replicationChannel("aeron:udp?endpoint=" + hostname + ":0|term-length="
                        + TransportConfig.logTermLength())
                .snapshotChannel(snapshotChannel)
                // TRANSPORT_CONSENSUS_TERM_LENGTH (default 1m): Aeron's default consensus channel names no term, so it
                // took the driver's 16m (two publications and two images of 48 MB per member of three)
                .consensusChannel(com.openexchange.cluster.ConsensusChannel.fromEnvironment().uri())
                // Idle strategy for consensus
                .idleStrategySupplier(org.agrona.concurrent.BusySpinIdleStrategy::new)
                // NOTE: leaderHeartbeatIntervalNs / leaderHeartbeatTimeoutNs / electionTimeoutNs /
                // startupCanvassTimeoutNs / terminationTimeoutNs / sessionTimeoutNs are set in
                // AeronCluster.java to keep all cluster timing in one place.
                // Log buffer size
                .logFragmentLimit(64)  // Process more fragments per work cycle
                // Wheel timer tick resolution
                .wheelTickResolutionNs(1_000_000)  // 1ms tick resolution
                .ticksPerWheel(1024)  // More ticks for finer granularity
                // Allow more concurrent sessions (default is 10)
                // Prevents session exhaustion during load tests + gateway reconnections
                .maxConcurrentSessions(50);

        // Every session authenticates against the node-local key table
        // (ME_AUTH_KEYS / -Dmatch.auth.keys) and the minted principal rides SessionOpen to every member.
        // Always on, on every behaviour: an empty table rejects everyone, loudly; a malformed table
        // throws here, so the node never starts with a partial table. BackupQuery, HeartbeatRequest,
        // StandbySnapshot and AdminRequest pass the class table: REPLICA may BackupQuery, nothing else.
        // One instance of each; Aeron calls each supplier once per consensus module, and the
        // service reads the same instances for its gauges.
        final com.openexchange.cluster.auth.ClusterAuthenticator authenticator =
                com.match.infrastructure.auth.MatchAuth.authenticatorFromEnvironment(consensusModuleContext::sessionTimeoutNs);
        final com.openexchange.cluster.auth.ClassAuthorisationService authorisation =
                com.match.infrastructure.auth.MatchAuth.authorisationService();
        consensusModuleContext
                .authenticatorSupplier(() -> authenticator)
                .authorisationServiceSupplier(() -> authorisation);

        if (clusteredService instanceof AppClusteredService engineService) {
            // The probe and metrics port follows the member ports' rule; a METRICS_PORT that disagrees refuses the
            // boot here, before any Aeron component exists.
            engineService.nodeEndpointPort(nodeEndpointPort(memberId, portBase));
            engineService.readinessConsensus(consensusModuleContext);
            engineService.sessionAuthority(authenticator, authorisation);
        }

        final List<ClusteredServiceContainer.Context> serviceContexts = new ArrayList<>();

        // ==================== ULTRA-LOW LATENCY SERVICE CONTAINER CONFIG ====================
        final ClusteredServiceContainer.Context clusteredServiceContext = new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveContext(aeronArchiveContext.clone())
                .clusterDir(new File(baseDir, CLUSTER_SUB_DIR))
                .clusteredService(clusteredService)
                .serviceId(0)
                // Idle strategy: BusySpin for lowest latency
                .idleStrategySupplier(org.agrona.concurrent.BusySpinIdleStrategy::new)
                // Snapshot duration threshold (trigger warning if snapshot takes too long)
                .snapshotDurationThresholdNs(5_000_000_000L)  // 5 seconds
                // Snapshot channel: 64MB term buffer -> 8MB max message size
                .snapshotChannel(snapshotChannel);
        serviceContexts.add(clusteredServiceContext);

        for (int i = 0; i < additionalServices.length; i++)
        {
            final ClusteredServiceContainer.Context additionalServiceContext = new ClusteredServiceContainer.Context()
                    .aeronDirectoryName(aeronDirName)
                    .archiveContext(aeronArchiveContext.clone())
                    .clusterDir(new File(baseDir, CLUSTER_SUB_DIR))
                    .clusteredService(additionalServices[i])
                    .serviceId(i + 1)
                    .idleStrategySupplier(org.agrona.concurrent.BusySpinIdleStrategy::new)
                    .snapshotDurationThresholdNs(5_000_000_000L)
                    .snapshotChannel(snapshotChannel);
            serviceContexts.add(additionalServiceContext);
        }

        final ClusterConfig config = new ClusterConfig(
                memberId,
                ingressHostname,
                hostname,
                mediaDriverContext,
                archiveContext,
                aeronArchiveContext,
                consensusModuleContext,
                serviceContexts);
        if (clusteredService instanceof AppClusteredService engineService)
        {
            config.engineService = engineService;
        }
        config.applyMemberCompat();
        return config;
    }

    // ==================== S4: removed launch-mode settings ====================

    /** The removed launch switch: {@code true} (the one legal value, launchers set it today) is accepted and ignored. */
    public static final String ENGINE_FROM_CONFIG_ENV = "MATCH_ENGINE_FROM_CONFIG";
    public static final String ENGINE_FROM_CONFIG_PROP = "match.engine.from.config";
    public static final String ENGINE_IMPL_ENV = "MATCH_ENGINE_IMPL";
    public static final String ENGINE_IMPL_PROP = "match.engine.impl";
    public static final String BOOK_CAPACITY_ENV = "MATCH_ENGINE_BOOK_CAPACITY";
    public static final String BOOK_CAPACITY_PROP = "match.engine.book.capacity";

    /**
     * A setting the node no longer reads refuses the boot. Thrown before any Aeron component exists;
     * the launcher stops with {@link com.openexchange.cluster.EngineLifecycle#EXIT_CONFIG} (78), which
     * the units never restart: the recovery is to unset it.
     */
    public static final class SettingRefusedException extends IllegalStateException
    {
        public SettingRefusedException(final String message)
        {
            super(message);
        }
    }

    /**
     * The refusal for the removed settings, null when none is set (or the switch is set to exactly
     * {@code true}). Pure: {@code get} reads one name (an environment variable or a system property)
     * and returns null when it is unset.
     */
    static String removedSettingsRefusal(final java.util.function.UnaryOperator<String> get)
    {
        for (final String name : new String[] {ENGINE_FROM_CONFIG_ENV, ENGINE_FROM_CONFIG_PROP})
        {
            final String v = get.apply(name);
            if (v != null && !"true".equals(v))
            {
                return name + "=" + v + " is no longer supported: engines are created only from the replicated"
                        + " EngineConfig; unset it";
            }
        }
        for (final String name : new String[] {ENGINE_IMPL_ENV, ENGINE_IMPL_PROP, BOOK_CAPACITY_ENV, BOOK_CAPACITY_PROP})
        {
            if (get.apply(name) != null)
            {
                return name + " is no longer read: the implementation and the capacity come from the replicated"
                        + " EngineConfig; unset it";
            }
        }
        return null;
    }

    /** {@link #removedSettingsRefusal} over this process's environment (the upper-case names) and properties. */
    static void refuseRemovedSettings()
    {
        final String refusal = removedSettingsRefusal(
                name -> name.indexOf('.') >= 0 ? System.getProperty(name) : System.getenv(name));
        if (refusal != null)
        {
            throw new SettingRefusedException(refusal);
        }
    }

    // ==================== S4: member version ====================

    /**
     * The member version every context carries and the refusal its validator runs (production:
     * {@code MemberCompat.fatalOnRefusal(lifecycle, build)}, exit 78). One validator instance for the
     * consensus module and every service container, recording accepted versions in
     * {@code <clusterDir>/member-compat.seen}. Call after the base directory is final ({@link #baseDir}
     * re-applies it).
     */
    public void memberCompat(
            final com.openexchange.cluster.MemberCompat own, final com.openexchange.cluster.MemberCompat.Refusal onRefusal)
    {
        this.memberCompat = java.util.Objects.requireNonNull(own, "own");
        this.memberCompatRefusal = java.util.Objects.requireNonNull(onRefusal, "onRefusal");
        applyMemberCompat();
    }

    /** The member version the contexts carry. */
    public com.openexchange.cluster.MemberCompat memberCompat()
    {
        return memberCompat;
    }

    private void applyMemberCompat()
    {
        com.openexchange.cluster.MemberCompat.install(consensusModuleContext, clusteredServiceContexts, memberCompat,
                memberCompatRefusal);
        if (engineService != null)
        {
            engineService.memberCompat = memberCompat;
        }
    }

    /**
     * Create a new ClusterConfig. This call allows for 2 separate lists of hostnames, so that there can be 'external'
     * addresses for ingress requests and 'internal' addresses that will handle all the cluster replication and
     * control traffic.
     *
     * @param nodeId             id for this node.
     * @param ingressHostnames   list of hostnames that will receive ingress request traffic.
     * @param clusterHostnames   list of hostnames that will receive cluster traffic.
     * @param portBase           base port to derive remaining ports from.
     * @param clusteredService   instance of the clustered service that will run with this configuration.
     * @param additionalServices instances of additional clustered services that will run with this configuration.
     * @return configuration that wraps all aeron service configuration.
     */
    public static ClusterConfig create(
            final int nodeId,
            final List<String> ingressHostnames,
            final List<String> clusterHostnames,
            final int portBase,
            final ClusteredService clusteredService,
            final ClusteredService... additionalServices)
    {
        return create(
                0,
                nodeId,
                ingressHostnames,
                clusterHostnames,
                portBase,
                new File(System.getProperty("user.dir")),
                clusteredService,
                additionalServices);
    }

    /**
     * Create a new ClusterConfig. This only supports a single lists of hostnames.
     *
     * @param nodeId           id for this node.
     * @param hostnames        list of hostnames that will receive ingress request and cluster traffic.
     * @param portBase         base port to derive remaining ports from.
     * @param clusteredService instance of the clustered service that will run on this node.
     * @return configuration that wraps the detailed aeron service configuration.
     */
    public static ClusterConfig create(
            final int nodeId,
            final List<String> hostnames,
            final int portBase,
            final ClusteredService clusteredService)
    {
        return create(nodeId, hostnames, hostnames, portBase, clusteredService);
    }

    /**
     * Set the same error handler for all contexts.
     *
     * @param errorHandler to receive errors.
     */
    public void errorHandler(final ErrorHandler errorHandler)
    {
        this.mediaDriverContext.errorHandler(errorHandler);
        this.archiveContext.errorHandler(errorHandler);
        this.aeronArchiveContext.errorHandler(errorHandler);
        this.consensusModuleContext.errorHandler(errorHandler);
        this.clusteredServiceContexts.forEach((ctx) -> ctx.errorHandler(errorHandler));
    }

    /**
     * Set the same idle strategy supplier for all contexts: media driver threads (embedded
     * mode only; an external driver reads its own driver.properties), consensus module and
     * every service container. Each agent gets its own instance from the supplier.
     *
     * @param supplier of idle strategies.
     */
    public void idleStrategySupplier(final java.util.function.Supplier<org.agrona.concurrent.IdleStrategy> supplier)
    {
        this.mediaDriverContext
                .conductorIdleStrategy(supplier.get())
                .senderIdleStrategy(supplier.get())
                .receiverIdleStrategy(supplier.get())
                .sharedIdleStrategy(supplier.get());
        this.consensusModuleContext.idleStrategySupplier(supplier::get);
        this.clusteredServiceContexts.forEach((ctx) -> ctx.idleStrategySupplier(supplier::get));
    }

    /**
     * Set the aeron directory for all configuration contexts.
     *
     * @param aeronDir directory to use for aeron.
     */
    public void aeronDirectoryName(final String aeronDir)
    {
        this.mediaDriverContext.aeronDirectoryName(aeronDir);
        this.archiveContext.aeronDirectoryName(aeronDir);
        this.aeronArchiveContext.aeronDirectoryName(aeronDir);
        this.consensusModuleContext.aeronDirectoryName(aeronDir);
        this.clusteredServiceContexts.forEach(ctx -> ctx.aeronDirectoryName(aeronDir));
    }

    /**
     * Set the base directory for cluster and archive.
     *
     * @param baseDir parent directory to be used for archive and cluster stored data.
     */
    public void baseDir(final File baseDir)
    {
        this.archiveContext.archiveDir(new File(baseDir, ARCHIVE_SUB_DIR));
        this.consensusModuleContext.clusterDir(new File(baseDir, CLUSTER_SUB_DIR));
        this.clusteredServiceContexts.forEach((ctx) -> ctx.clusterDir(new File(baseDir, CLUSTER_SUB_DIR)));
        applyMemberCompat();
    }

    /**
     * Gets the configuration's media driver context.
     *
     * @return configured {@link io.aeron.driver.MediaDriver.Context}.
     * @see io.aeron.driver.MediaDriver.Context
     */
    public MediaDriver.Context mediaDriverContext()
    {
        return mediaDriverContext;
    }

    /**
     * Gets the configuration's archive context.
     *
     * @return configured {@link io.aeron.archive.Archive.Context}.
     * @see io.aeron.archive.Archive.Context
     */
    public Archive.Context archiveContext()
    {
        return archiveContext;
    }

    /**
     * Gets the configuration's aeron archive context.
     *
     * @return configured {@link io.aeron.archive.Archive.Context}.
     * @see io.aeron.archive.client.AeronArchive.Context
     */
    public AeronArchive.Context aeronArchiveContext()
    {
        return aeronArchiveContext;
    }

    /**
     * Gets the configuration's consensus module context.
     *
     * @return configured {@link io.aeron.cluster.ConsensusModule.Context}.
     * @see io.aeron.cluster.ConsensusModule.Context
     */
    public ConsensusModule.Context consensusModuleContext()
    {
        return consensusModuleContext;
    }

    /**
     * Gets the configuration's clustered service container context.
     *
     * @return configured {@link io.aeron.cluster.service.ClusteredServiceContainer.Context}.
     * @see io.aeron.cluster.service.ClusteredServiceContainer.Context
     */
    public ClusteredServiceContainer.Context clusteredServiceContext()
    {
        return clusteredServiceContexts.get(0);
    }

    /**
     * Gets the configuration's list of clustered service container contexts.
     *
     * @return configured list of {@link io.aeron.cluster.service.ClusteredServiceContainer.Context}.
     * @see io.aeron.cluster.service.ClusteredServiceContainer.Context
     */
    public List<ClusteredServiceContainer.Context> clusteredServiceContexts()
    {
        return clusteredServiceContexts;
    }

    /**
     * memberId of this node.
     *
     * @return memberId.
     */
    public int memberId()
    {
        return memberId;
    }

    /**
     * Hostname of this node that will receive ingress traffic.
     *
     * @return ingress hostname.
     */
    public String ingressHostname()
    {
        return ingressHostname;
    }

    /**
     * Hostname of this node that will receive cluster traffic.
     *
     * @return cluster hostname
     */
    public String clusterHostname()
    {
        return clusterHostname;
    }

    /**
     * String representing the cluster members configuration which can be used for
     * {@link io.aeron.cluster.ClusterMember#parse(String)}.
     *
     * @param ingressHostnames of the cluster members.
     * @param clusterHostnames of the cluster members internal address (can be the same as 'hostnames').
     * @param portBase         initial port to derive other port from via appropriate node id and offset.
     * @return the String which can be used for {@link io.aeron.cluster.ClusterMember#parse(String)}.
     */
    public static String clusterMembers(
            final List<String> ingressHostnames, final List<String> clusterHostnames, final int portBase)
    {
        return clusterMembers(0, ingressHostnames, clusterHostnames, portBase);
    }

    /**
     * String representing the cluster members configuration which can be used for
     * {@link io.aeron.cluster.ClusterMember#parse(String)}.
     *
     * @param startingMemberId first memberId to be used in the list of clusterMembers. The memberId will increment by 1
     *                         from that value for each entry.
     * @param ingressHostnames of the cluster members.
     * @param clusterHostnames of the cluster members internal address (can be the same as 'hostnames').
     * @param portBase         initial port to derive other port from via appropriate node id and offset.
     * @return the String which can be used for {@link io.aeron.cluster.ClusterMember#parse(String)}.
     */
    public static String clusterMembers(
            final int startingMemberId,
            final List<String> ingressHostnames,
            final List<String> clusterHostnames,
            final int portBase)
    {
        if (ingressHostnames.size() != clusterHostnames.size())
        {
            throw new IllegalArgumentException("ingressHostnames and clusterHostnames must be the same size");
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ingressHostnames.size(); i++)
        {
            final int memberId = i + startingMemberId;
            sb.append(memberId);
            sb.append(',').append(endpoint(memberId, ingressHostnames.get(i), portBase, CLIENT_FACING_PORT_OFFSET));
            sb.append(',').append(endpoint(memberId, clusterHostnames.get(i), portBase, MEMBER_FACING_PORT_OFFSET));
            sb.append(',').append(endpoint(memberId, clusterHostnames.get(i), portBase, LOG_PORT_OFFSET));
            sb.append(',').append(endpoint(memberId, clusterHostnames.get(i), portBase, TRANSFER_PORT_OFFSET));
            sb.append(',').append(endpoint(memberId, clusterHostnames.get(i), portBase, ARCHIVE_CONTROL_PORT_OFFSET));
            sb.append('|');
        }

        return sb.toString();
    }

    /**
     * Ingress endpoints generated from a list of hostnames.
     *
     * @param hostnames              for the cluster members.
     * @param portBase               Base port for the cluster
     * @param clientFacingPortOffset Offset for the client facing port
     * @return a formatted string of ingress endpoints for connecting to a cluster.
     */
    public static String ingressEndpoints(
            final List<String> hostnames,
            final int portBase,
            final int clientFacingPortOffset)
    {
        return ingressEndpoints(0, hostnames, portBase, clientFacingPortOffset);
    }


    /**
     * Ingress endpoints generated from a list of hostnames.
     *
     * @param startingMemberId       first memberId to be used when generating the ports.
     * @param hostnames              for the cluster members.
     * @param portBase               Base port for the cluster
     * @param clientFacingPortOffset Offset for the client facing port
     * @return a formatted string of ingress endpoints for connecting to a cluster.
     */
    public static String ingressEndpoints(
            final int startingMemberId,
            final List<String> hostnames,
            final int portBase,
            final int clientFacingPortOffset)
    {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hostnames.size(); i++)
        {
            final int memberId = i + startingMemberId;
            sb.append(memberId).append('=');
            sb.append(hostnames.get(i)).append(':').append(calculatePort(memberId, portBase, clientFacingPortOffset));
            sb.append(',');
        }

        sb.setLength(sb.length() - 1);

        return sb.toString();
    }

    /**
     * Calculates a port for use with a node based on a specific offset. Can be used with the predefined offsets, e.g.
     * {@link ClusterConfig#ARCHIVE_CONTROL_PORT_OFFSET} or with custom offsets. For custom offsets select a value
     * larger than the largest predefined offset, and smaller than the configured stride when there is one.
     *
     * <p>The arithmetic lives in {@link com.openexchange.cluster.ClusterPorts} because OMS, the
     * settlement bridge, the load generators and the backup agent all derive these same numbers
     * independently. By default every member listens on the SAME ports, one member per address;
     * {@code CLUSTER_PORT_STRIDE=100} restores the layout where several members share a host.
     *
     * @param nodeId   The id for the member of the cluster.
     * @param portBase The port base to be used.
     * @param offset   The offset to add onto the port base
     * @return a calculated port, which should be unique for the specified criteria.
     */
    public static int calculatePort(final int nodeId, final int portBase, final int offset)
    {
        return com.openexchange.cluster.ClusterPorts.port(nodeId, portBase, offset);
    }

    private static String udpChannel(final int nodeId, final String hostname, final int portBase)
    {
        final int port = calculatePort(nodeId, portBase, ClusterConfig.ARCHIVE_CONTROL_PORT_OFFSET);
        return "aeron:udp?endpoint=" + hostname + ":" + port;
    }

    private static String endpoint(final int nodeId, final String hostname, final int portBase, final int portOffset)
    {
        return hostname + ":" + calculatePort(nodeId, portBase, portOffset);
    }

    /** Map the transport-neutral threading knob to the media driver's ThreadingMode. */
    private static ThreadingMode driverThreadingMode(final TransportConfig.DriverThreading mode)
    {
        switch (mode)
        {
            case SHARED:
                return ThreadingMode.SHARED;
            case SHARED_NETWORK:
                return ThreadingMode.SHARED_NETWORK;
            case DEDICATED:
            default:
                return ThreadingMode.DEDICATED;
        }
    }

    /**
     * Map the same knob to the archive's threading mode. The archive has no SHARED_NETWORK
     * variant, so both shared options collapse to ArchiveThreadingMode.SHARED.
     */
    private static ArchiveThreadingMode archiveThreadingMode(final TransportConfig.DriverThreading mode)
    {
        switch (mode)
        {
            case SHARED:
            case SHARED_NETWORK:
                return ArchiveThreadingMode.SHARED;
            case DEDICATED:
            default:
                return ArchiveThreadingMode.DEDICATED;
        }
    }

    /** {@code METRICS_PORT} (env, then {@code metrics.port}): accepted only when it names the computed port. */
    public static final String NODE_PORT_SETTING = "METRICS_PORT";

    /**
     * The member's probe and metrics port: {@code portBase + nodeId x CLUSTER_PORT_STRIDE + 6} (kit
     * {@code ClusterPorts.NODE_ENDPOINT_OFFSET}), the same on every host at the default stride. {@code METRICS_PORT}
     * may repeat it and nothing else.
     *
     * @throws IllegalArgumentException when {@code METRICS_PORT} is not a number or names another port
     */
    public static int nodeEndpointPort(final int nodeId, final int portBase)
    {
        String override = System.getenv(NODE_PORT_SETTING);
        if (override == null || override.isBlank())
        {
            override = System.getProperty("metrics.port");
        }
        return com.openexchange.cluster.ClusterPorts.nodeEndpointPort(nodeId, portBase, NODE_PORT_SETTING, override);
    }
}
