# Incident: lagging follower permanently stranded by archive housekeeping (2026-07-02)

**Severity: critical durability finding (dev cluster, synthetic data, no production impact).**
A follower that falls behind by more than one snapshot+housekeeping cycle can NEVER
rejoin the cluster, and the attempt silently corrupts its local archive. Discovered
during max-throughput load testing; reproduced conditions and evidence below.

## Impact

- node2 permanently unable to rejoin; requires reseed (or full cluster reset on dev).
- Failure is silent: the node process stays "running" while hot-looping recovery
  (44,991 ArchiveExceptions in ~15 min), the process manager sees a healthy process,
  and the admin status API masks it further by reporting stale CnC counter values.
- The intended safety net (ClusterBackup node) was itself broken (empty state dir,
  "progress has stalled" repeating since ~12:22), so no reseed source existed.

## Root cause chain (each step verified)

1. **Trigger**: at 700k orders/s the Raft log grows ~350 MB/s across nodes; the 16 GB
   tmpfs (/dev/shm) archive filled mid-run. node2's archive agents wedged
   (`AgentTerminationException`), it stopped at commit ~23.87 GB while node0/node1
   continued to ~30.77 GB. (Original wedge, understood and rig-specific.)
2. **The design gap**: snapshot + `ArchiveHousekeeping` cycles on the healthy nodes
   purged their logs below their newest snapshots (23.90 GB, later ~30.70 GB).
   Housekeeping reads only the LOCAL node's recording.log; nothing in the cluster
   retains log for a lagging/offline member. The purged range 23.87→30.74 GB now
   exists nowhere. (`ArchiveHousekeeping.java` javadoc even documents this caveat;
   it was never guarded.)
3. **The corruption**: when node2 rejoined, Aeron's leader-driven catch-up
   REPLICATED the leader's log recording into node2's archive. The destination
   recording adopts the source's start position, so node2's local recording 0 start
   moved 21.14 GB → 23,890,755,584 → 30,735,859,712 (byte-identical to the leader's
   post-purge earliest segment each time; node2's own recording.log has NO snapshot
   newer than 21.15 GB, proving the move came from replication, not local
   housekeeping). node2's own still-present older segments (files from 21.1 GB)
   were silently detached from the catalog.
4. **The dead end**: node2's recovery = local snapshot at 21.15 GB + log replay from
   ~23.09 GB. Replay fails forever:
   `ArchiveException: requested replay start position=23097376768 is less than
   recording start position=30735859712 for recording 0`.
   The consensus module retries in a hot loop; no crash, no backoff, no alarm.

## Evidence

- node2 error histogram: 44,991 × `requested replay start position < recording start
  position for recording 0`; 4 × `log replication has not progressed`.
- Boot timeline (rotated logs `~/.local/log/cluster/node2.log.*`): 14:26 boot died of
  `ConductorServiceTimeoutException` (shm pressure); 14:34 boot shows recording start
  already at 23,890,755,584; 14:42 boot at 30,735,859,712.
- Archive segment files: node0/node1 earliest `0-30735859712.rec`; node2 catalog
  start 30,735,859,712 while orphaned files `0-21139292160.rec`... remain on disk.
- node2 recording.log dump (ClusterTool, offline): latest valid snapshot pair at
  logPosition=21,154,248,576; no newer entries.

## Why this matters beyond the dev rig

Any deployment doing routine snapshot+housekeeping has this failure mode: a member
down for longer than one housekeeping cycle is not merely slow to catch up, it is
unrecoverable by rejoin, and the rejoin attempt makes its local state worse. Combined
with the observability gaps (below) an operator can believe the cluster is 3/3
healthy while it is 2/3 with one member in a corrupting retry loop.

## Fixes (proposed)

1. **Housekeeping lag guard (P1)**: purge only below
   `min(latest snapshot position, lowest commit/recovery position of ALL members)`,
   or refuse to purge when any member is unreachable/lagging without `--force`.
   Alternative floor: retain the last N snapshot generations of log.
2. **Reseed path**: a supported, automated "reseed member from latest snapshot"
   procedure (wipe member state, replicate leader snapshot + retained log), so a
   stranded member has a designed recovery instead of a full cluster reset.
3. **Fail-fast recovery loop**: detect repeated identical archive replay failures in
   the error handler and terminate the node (process manager restarts and alerts)
   instead of hot-looping invisibly.
4. **Fix the ClusterBackup node**: it stalled and lost its state dir (separate
   investigation); it is the designed reseed source and must be monitored
   ("progress has stalled" should alert, not just log).
5. **Observability (admin-gateway)**: status API must not serve CnC counters of
   dead/wedged processes as live truth (probe process liveness + counter freshness);
   process manager should re-arm crash monitoring for adopted processes (a PM
   restart currently orphans cascade/auto-restart handling).

## Operational rules until fixed

- Never run housekeeping while any node is down, lagging, or recovering.
- After any node outage: verify it has fully caught up (commit position matches
  leader) BEFORE the next snapshot/housekeeping cycle.
- Treat "node running but commit position frozen" as a wedge, check for the
  ArchiveException loop in its log.
