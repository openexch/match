Repo: openexch/match | Milestone: v0.3.0-beta
Title: Archive housekeeping strands lagging followers: unrecoverable rejoin + local archive corruption

Severity: critical durability gap (found live on the dev cluster 2026-07-02; full incident report: docs/incidents/2026-07-02-follower-stranded-by-housekeeping.md).

A follower that falls behind by more than one snapshot+housekeeping cycle can never rejoin, and the rejoin attempt corrupts its local archive:

1. Healthy nodes snapshot and ArchiveHousekeeping purges their logs below the newest snapshot. Housekeeping only consults the local node's recording.log; nothing retains log for a lagging/offline member, so the range between the laggard's recovery point and the cluster snapshot is destroyed cluster-wide. (The class javadoc documents this caveat; it is unguarded.)
2. On rejoin, Aeron leader-driven catch-up replicates the leader's log recording into the follower's archive and the destination recording ADOPTS THE LEADER'S PURGED-FORWARD START POSITION, silently detaching the follower's own older segments (verified: node2's recording-0 start moved 21.14GB -> 23,890,755,584 -> 30,735,859,712, byte-identical to the leader's post-purge earliest segment, while node2's recording.log had no snapshot newer than 21.15GB).
3. Recovery then hot-loops forever: "ArchiveException: requested replay start position=23097376768 is less than recording start position=30735859712 for recording 0" (44,991 occurrences in ~15min), with the process alive and looking healthy.

Proposed fixes:
- P1: housekeeping lag guard: purge only below min(latest snapshot, lowest member commit/recovery position); refuse when a member is unreachable/lagging without --force; or retain last N snapshot generations of log.
- Supported reseed procedure for a stranded member (wipe + snapshot replication) instead of full cluster reset.
- Fail-fast on repeated identical archive replay failures (terminate for PM restart + alert) instead of infinite hot retry.

Related: ClusterBackup node (the designed reseed source) was itself stalled with an empty state dir; separate issue.
