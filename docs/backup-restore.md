# Cluster backup & restore

How the exchange survives power loss while the live Raft archive stays on tmpfs
(`/dev/shm`, chosen for the latency model — see `kernel-bypass.md`).

## Architecture

```
node0..2 (archives on tmpfs)          ClusterBackupApp ("backup" PM service)
   │  live log + snapshots   ────────►   non-voting observer, replicates
   │  (no cluster pause)                 snapshots + log continuously
   ▼                                          │
/dev/shm (VOLATILE)                           ▼
                                      match/backup/  (DURABLE DISK)
                                        ├── cluster/recording.log
                                        ├── archive/archive.catalog + *.rec
                                        └── backup-progress.json  (heartbeat)
```

- The backup app queries the cluster every `BACKUP_INTERVAL_SEC` (60 s) and
  additionally records the **live log** between queries, so the on-disk copy
  trails the leader by seconds, not by a snapshot interval.
- **RPO**: live-log replication lag (≈ seconds) for the Raft log; a freshly
  retrieved snapshot every backup query cycle. Effective worst case ≈
  `BACKUP_INTERVAL_SEC` + replication lag. **RTO**: restore copy time + normal
  node recovery (snapshot load + log replay).
- The process manager MUST pass `CLUSTER_ADDRESSES` (all three members) and
  `BASE_DIR` to the app. Running it with defaults gives it a single consensus
  endpoint, and Aeron's ClusterBackup wedges in an infinite loop whenever the
  leader is not node 0 (match#36; burned a core silently for days).

## Monitoring (match#36)

- `ClusterBackupApp` writes `backup-progress.json` every 5 s and **halts
  itself** (exit 3) when it makes no progress for `BACKUP_STALL_EXIT_SEC`
  (300 s), so the process manager restarts it fresh; a persistent failure trips
  the PM crash-loop cap and shows as a loud `failed` + lastError.
- `GET /api/admin/backup-info` → `fresh` + `freshReason` + heartbeat details
  (last response, live-log position/age, snapshots retrieved, stall warnings).
- `GET /api/admin/status` → `backup: {running, fresh, reason}`. Alert on
  `fresh=false`, not on `running` — a running process proves nothing.
- Healthy signals: `recordingLogBytes > 0`, `catalogModifiedAgoSec` recent,
  `BACKUP: response, log source member N` lines in `backup.log` (~1/min).

## Restore: single node (stranded/corrupted member)

Prefer reseeding from a healthy follower when quorum is intact (see
`.claude/CLAUDE.md` ops rules). Use the disk backup when no healthy source
exists:

```bash
curl -X POST http://localhost:8082/api/admin/processes/nodeN/force-stop
curl -X POST http://localhost:8082/api/admin/recover-from-backup \
  -H 'Content-Type: application/json' -d '{"nodeId": N, "force": true}'
curl -X POST http://localhost:8082/api/admin/processes/nodeN/start
```

The node recovers from the backup's latest snapshot and catches the rest up
from the leader via normal replication.

## Restore: full cluster (power loss, /dev/shm wiped)

1. Confirm the stack is down (`processes/summary`); do NOT start nodes yet.
2. For each node 0..2: `recover-from-backup {"nodeId": N, "force": true}`
   (copies the same backup archive + recording.log into each node dir).
3. `POST /api/admin/processes/start-all` (drivers come up first, the driver
   gate holds nodes until drivers are stable).
4. The members elect a leader and recover to the backup's last replicated log
   position. Verify: 3/3 consensus in `/api/admin/status`, order books plausible,
   OMS reconciles against Postgres on startup (oms#35).
5. Restart the backup service last; it re-syncs from the recovered cluster.

Data written between the backup's last replicated position and the power loss
is gone — that is the RPO. The OMS Postgres ledger (durable, synchronous) is
the source of truth for money movements; reconcile any gap against it.

> Drill status: single-node restore machinery exists (`recover-from-backup`);
> the full power-loss drill (kill -9 all, wipe /dev/shm, restore, verify) is
> the admin-gateway#9 exit criterion — run it with
> `tools/durability/backup_restore_drill.sh` on a dev box only.
