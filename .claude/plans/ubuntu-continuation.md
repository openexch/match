# Ubuntu Continuation — Session Handoff (2026-06-13)

Written on the Windows machine before switching to Ubuntu for runtime validation.
Everything below is committed/pushed; nothing lives only on the Windows box.

## Open PRs (merge these first or work atop their branches)

| PR | Repo | Content | State |
|----|------|---------|-------|
| openexch/match#13 | match | Loud limits (reject reasons, phantom-NEW fix) + protocol keep-alive replacing logged GatewayHeartbeats | Open, signed, CI green |
| openexch/match#14 | match | ArchiveHousekeeping (purge log segments below snapshot + prune old snapshots) + segmentFileLength 1GB→64MB | Open, signed |
| openexch/admin-gateway#6 | admin-gateway | Wires housekeeping into POST /snapshot (steps 5-7) + POST /api/admin/housekeeping | Open, CI green; needs match#14 jar deployed |
| openexch/admin-gateway#5 | admin-gateway | Dependabot chi 5.3.0; we added ci fix (go-version-file: go.mod) | CI green, mergeable. NOTE: `@dependabot rebase` would drop our ci fix commit |

All match/admin-gateway PR branches: `feat/loud-limits-cluster-keepalive`,
`feat/archive-housekeeping` (match), `feat/archive-housekeeping` (admin-gateway).

## Ubuntu validation checklist (the reason for switching)

1. **Build + deploy**: merge PRs (squash-merge satisfies the signed-commits rule),
   `make build`, deploy via admin gateway process manager.
2. **Keep-alive survives idle**: with no order flow for >10s (session timeout),
   gateway session must stay connected. Watch `GATEWAY STATS: ... keepAlives=N`
   in market gateway log; no reconnects during idle.
3. **Egress keep-warm**: during idle, gateway should receive ClusterHeartbeat
   (~1/s); stale-egress detector must NOT fire overnight on a quiet market.
4. **Archive actually shrinks** (the headline test):
   ```bash
   du -sh /dev/shm/aeron-cluster/node*/archive   # before
   curl -X POST localhost:8082/api/admin/snapshot
   curl localhost:8082/api/admin/progress         # watch steps 5-7 reclaim
   du -sh /dev/shm/aeron-cluster/node*/archive   # after — should DROP
   ```
   Also `[HOUSEKEEPING]` lines land in admin gateway stdout per node.
5. **Log growth rate**: with heartbeats no longer logged (10/s of Raft entries
   gone), idle log growth should be ~zero. Verify recording position is static
   during idle: `ClusterTool recording-log`.
6. **Failover**: restart-node on the leader; verify keep-warm + market data
   resume from new leader, no stale-egress reconnect loop.
7. **Recovery from snapshot after purge**: stop-all → start-all; nodes must
   recover from latest snapshot + replay only the unpurged tail.

## Known open issues / next work (priority order)

1. **Backup-node purge coordination** (before trusting housekeeping fully):
   housekeeping purges below the latest snapshot regardless of ClusterBackup
   progress. If the backup lags past a purge, it breaks and needs reseeding.
   Plan: bound purge by backup's retrieved position (ClusterBackupApp gets
   onUpdatedRecordingLog callbacks) or snapshot-retrieve before purging.
2. **/dev/shm durability decision**: ENTIRE archive incl. backup node lives in
   tmpfs (RAM). Host reboot = total state loss. Archive growth eats RAM.
   Decide: move ClusterDir (or at least backup) to NVMe. Driver dirs stay in
   /dev/shm. Note: config.go hardcodes `/dev/shm/aeron-cluster` (no env var).
3. **Retire compact/compact-archive endpoints** (admin-gateway): they only
   remove INVALID catalog entries — cannot reclaim the log. Superseded by
   housekeeping once validated.
4. **Reject reason on the wire**: loud-limits publishes REJECTED but the SBE
   OrderStatus message has no reason field — OMS can't tell users why. Schema
   addition + codec regen (sbe runs from order-schema.xml at build).
5. **Benchmark rig** (project principle: experiments need measurement): no JMH
   anywhere; run-load-test.sh references the deleted Go loadgen. Build JMH
   module for the order book + restore a load generator.
6. **Order book roadmap** (agreed direction): (a) loud limits ✅, (b) global
   order pool + intrusive per-level lists (kills 64-orders-per-level cap and
   per-level memory rent; the 64-cap is the binding constraint, not price
   range), (c) paged level table + hierarchical bitmap for next-active-level
   (also fixes the findNewBestPrice O(50K) scan latency spike on BTC).

## Machine setup notes for Ubuntu

- Commit signing: this repo requires verified signatures. On Ubuntu configure:
  `git config --global gpg.format ssh`, `user.signingkey 'key::ssh-ed25519
  AAAAC3NzaC1lZDI1NTE5AAAAIJ2AP2fRkjVNvWr0YcqcqTt2xJ11fVgpJm6DYHE4q0vk GitHub'`,
  `commit.gpgsign true`, and 1Password SSH agent with
  `gpg.ssh.program` left default (Linux ssh-keygen talks to SSH_AUTH_SOCK).
  The GitHub key must be registered on GitHub as a SIGNING key (separate from
  auth). On Windows a stale plaintext key sits at ~/.ssh/id_ed25519 (unrelated
  third key) — pending deletion there.
- admin-gateway hardcodes driver dir user lookup fixed in PR#6; process_manager
  still has `/dev/shm/aeron-emre-%d-driver` hardcoded at line ~998 (cleanup TODO).
- Embedded tests run anywhere (used for all TDD this session); only the 3-node
  runtime needs Linux.

## Context worth keeping

- Aeron snapshots do NOT truncate the log; reclamation = purgeSegments below
  snapshot at segment granularity. Segment length (now 64MB) is reclamation
  granularity, not throughput.
- ClusterTool snapshot bypasses the AuthorisationService; client admin requests
  (sendAdminRequestToTakeASnapshot) are DENIED by default — embedded tests set
  ALLOW_ALL explicitly.
- Embedded test sync rule: wait on egress CONTENT, never message counts —
  market data flows continuously since the flush timer became session-driven.
