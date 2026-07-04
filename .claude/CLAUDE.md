# Match Engine - Claude Guidelines

## Project Overview
Ultra-low latency matching engine running on a **3-node Aeron Cluster** (Raft consensus). Designed for **24/7 operation** with zero downtime.

## Architecture
```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│       OMS       │     │ Market Gateway  │     │  Admin Gateway  │
│   HTTP :8080    │     │   WS :8081      │     │   HTTP :8082    │
│ (separate repo) │     └────────┬────────┘     │   (Go service)  │
└────────┬────────┘              │ egress       └────────┬────────┘
         │ ingress               │                       │ process mgmt
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Aeron Cluster (Raft)                         │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐                 │
│  │  Node 0 │◄────►│  Node 1 │◄────►│  Node 2 │                 │
│  │ :9000+  │      │ :9100+  │      │ :9200+  │                 │
│  └─────────┘      └─────────┘      └─────────┘                 │
└─────────────────────────────────────────────────────────────────┘
```

## Priorities (Strict Order)
1. **Uptime** - System must run 24/7 without downtime
2. **Ultra-low latency** - Zero allocations in hot path, O(1) matching
3. **Reliability** - Raft consensus, state replication, snapshots

## Critical Rules

### NEVER Do
- Never flush/delete cluster data without explicit permission
- Never do random cleanups or "improvements"
- Never skip bugs found during sessions - track and fix them
- Never make direct systemctl calls for runtime management - use Admin API
- Never modify cluster state files manually

### ALWAYS Do
- Always look for **root causes** instead of quick fixes
- Always use Admin Gateway API for service management
- Always consider impact on all 3 nodes before changes
- Always verify cluster consensus after infrastructure changes

## Service Management (Admin Gateway API)

**Single source of truth for runtime operations: `http://localhost:8082/api/admin/*`**

### Check Cluster Status
```bash
curl http://localhost:8082/api/admin/status
```

### Restart a Node (Zero Downtime)
```bash
curl -X POST http://localhost:8082/api/admin/restart-node \
  -H "Content-Type: application/json" \
  -d '{"nodeId": 0}'
```

### Rolling Update (All Nodes, No Downtime)
```bash
curl -X POST http://localhost:8082/api/admin/rolling-update
```

### Trigger Snapshot
```bash
curl -X POST http://localhost:8082/api/admin/snapshot
```

### Process Management
```bash
curl http://localhost:8082/api/admin/processes                        # List all processes
curl -X POST http://localhost:8082/api/admin/processes/start-all      # Start all (dependency order)
curl -X POST http://localhost:8082/api/admin/processes/stop-all       # Stop all (reverse order)
curl -X POST http://localhost:8082/api/admin/processes/node0/start    # Start specific process
curl -X POST http://localhost:8082/api/admin/processes/node0/stop     # Stop specific process
```

### Check Operation Progress
```bash
curl http://localhost:8082/api/admin/progress
```

### View Node Logs
```bash
curl "http://localhost:8082/api/admin/logs?node=0&lines=100"
```

### Rebuild & Self-Update
```bash
curl -X POST http://localhost:8082/api/admin/rebuild-admin            # Self-update admin gateway
curl -X POST http://localhost:8082/api/admin/rebuild-cluster          # Rebuild cluster module
curl -X POST http://localhost:8082/api/admin/rebuild-gateway          # Rebuild gateway module
```

### Archive Compaction & Cleanup
```bash
curl -X POST http://localhost:8082/api/admin/housekeeping              # Reclaim archive disk on LIVE cluster: purge log segments below latest snapshot (also runs automatically after /snapshot)
curl -X POST http://localhost:8082/api/admin/cleanup -d '{"force":true}'  # Wipe Aeron mark/lock files (requires all nodes stopped)
curl -X POST http://localhost:8082/api/admin/cleanup-node              # Per-node cleanup
```

**Note:** Aeron snapshots do NOT truncate the log — they only add recordings. `ArchiveHousekeeping`
(match-cluster) is what reclaims disk: it purges whole log segment files below the latest snapshot
position. It runs LIVE (no downtime) and is invoked automatically after every snapshot.

**REMOVED (2026-06-13):** `/api/admin/compact`, `/compact-archive`, and `/rolling-cleanup` — they ran
Aeron's **offline** `ArchiveTool compact`/`delete-orphaned-segments` against **live** node archives,
which corrupts the latest snapshot recording and breaks recover-from-snapshot (nodes crash on restart
with `unknown recording id` and the cluster comes up unable to serve ingress → frozen UI order book).
Auto-snapshot used to call `compact` every cycle, silently corrupting recoverability. The auto-snapshot
cycle now takes **only** a snapshot; disk is reclaimed by the live-safe `ArchiveHousekeeping`. For offline
compaction, stop all nodes first (there is no live-safe ArchiveTool compaction).

### Auto-Snapshot Schedule
```bash
curl http://localhost:8082/api/admin/auto-snapshot                                 # Get current schedule
curl -X POST http://localhost:8082/api/admin/auto-snapshot -d '{"intervalMinutes":30}'  # Enable
curl -X DELETE http://localhost:8082/api/admin/auto-snapshot                       # Disable
```

### Backup & Recovery
```bash
curl http://localhost:8082/api/admin/backup-info                       # Inspect backup + FRESHNESS (fresh/freshReason/heartbeat)
curl -X POST http://localhost:8082/api/admin/recover-from-backup       # Restore from backup
```
Backup data lives on DISK at `match/backup/` (ClusterBackupApp, `backup` PM service; tmpfs
`/dev/shm/aeron-cluster/backup` is legacy/unused). Trust `backup.fresh` in `/status`, never
`running` alone (match#36: agent wedged silently for days). Full procedure + power-loss drill:
`docs/backup-restore.md`.

### Process Manager (Extended)
```bash
curl -X POST http://localhost:8082/api/admin/processes/{name}/restart        # Restart specific process
curl -X POST http://localhost:8082/api/admin/processes/{name}/force-stop     # SIGKILL specific process
curl -X POST http://localhost:8082/api/admin/processes/restart-all           # Restart all in dependency order
curl http://localhost:8082/api/admin/processes/summary                        # Process summary
```

### Node operations
```bash
curl -X POST http://localhost:8082/api/admin/start-node -d '{"nodeId":0}'    # Start single node
curl -X POST http://localhost:8082/api/admin/stop-node  -d '{"nodeId":0}'    # Stop single node
curl -X POST http://localhost:8082/api/admin/start-all-nodes                  # Start nodes 0-2
curl -X POST http://localhost:8082/api/admin/stop-all-nodes                   # Stop nodes 0-2
```

## Key Files

| Component | Path |
|-----------|------|
| Cluster Setup | `match-cluster/src/main/java/com/match/infrastructure/persistence/AeronCluster.java` |
| Cluster Service | `match-cluster/src/main/java/com/match/infrastructure/persistence/AppClusteredService.java` |
| Matching Engine | `match-cluster/src/main/java/com/match/application/engine/Engine.java` |
| Order book (DEFAULT) | `match-cluster/src/main/java/com/match/application/orderbook/ArrayMatchingEngine.java` (+ `ArrayOrderBook.java`) — array-backed, geometry-free |
| Order book (fallback) | `match-cluster/src/main/java/com/match/application/orderbook/DirectMatchingEngine.java` — preallocated; select with `MATCH_ENGINE_IMPL=direct` |
| Engine selection | `MatchingEngine` interface; `Engine.java` constructs the impl from `match.engine.impl` |
| Gateway Base | `match-gateway/src/main/java/com/match/infrastructure/gateway/AeronGateway.java` |
| Gateway WS (backpressure/conflation) | `match-gateway/src/main/java/com/match/infrastructure/websocket/MarketDataWebSocket.java` |
| Market Identity | `match-common/src/main/java/com/match/domain/MarketInfo.java` |
| Constants | `match-common/src/main/java/com/match/infrastructure/InfrastructureConstants.java` |
| Transport config (driver mode/idle/channels) | `match-common/src/main/java/com/match/infrastructure/TransportConfig.java` |
| Media driver launcher + tuning | `deploy/media-driver/launch-driver.sh`, `deploy/media-driver/driver.properties` |
| OS tuning script | `deploy/tuning/system-tuning.sh` (`make tune` / `make tune-report` / `sudo make tune-persist`) |
| Transport architecture doc | `docs/kernel-bypass.md` |
| Backup & restore doc (ClusterBackup → disk, RPO, drill) | `docs/backup-restore.md` |
| Performance baseline + data | `docs/perf/2026-07-02-performance-baseline.md`, `docs/perf/data/` |
| Incident reports | `docs/incidents/` |
| Hot-path allocation audit | `docs/hot-path-allocations.md` |
| SBE Schema | `match-common/src/main/resources/sbe/order-schema.xml` |
| Admin Gateway | `admin-gateway/` (Go service) |

## Build Commands

```bash
make build-java    # Build Java modules (mvn clean package -DskipTests)
make build-admin   # Build Go admin gateway
make build         # Build everything
make install       # Full installation with services
```

## Service Management Architecture

Only `admin.service` runs as a systemd unit (`~/.config/systemd/user/admin.service`).
All other processes (`node0`, `node1`, `node2`, `backup`, `oms`, `market`) are managed by the Go admin gateway's built-in process manager via HTTP API.

Logs at: `~/.local/log/cluster/`

## Configuration

| Parameter | Value | Location |
|-----------|-------|----------|
| Market Gateway Port | 8081 | InfrastructureConstants |
| Admin Gateway Port | 8082 | InfrastructureConstants |
| Cluster Base Port | 9000 | InfrastructureConstants |
| Term Buffer | 16MB | InfrastructureConstants |
| Socket Buffer | 4MB | InfrastructureConstants |
| Session Timeout | 10s | InfrastructureConstants |
| Session Keep-Alive | 1s (protocol-level, not logged) | InfrastructureConstants |
| Egress Keep-Warm | 1s (ClusterHeartbeat, leader only) | AppClusteredService |

### Transport / media driver (full reference: docs/kernel-bypass.md)
- Nodes run with **EXTERNAL media drivers** (`driver0/1/2` PM services) by default; the C driver
  (`~/.local/bin/aeronmd`, built from aeron 1.51.0 source in `~/Apps/aeron`) is preferred, Java fallback
  automatic. `TRANSPORT_DRIVER_MODE`, `AERON_DIR`, `TRANSPORT_IDLE_MODE`, `TRANSPORT_MTU` etc. per the doc.
- Profile switch lives in `~/.config/systemd/user/admin.service.d/driver-profile.conf`:
  **quiet mode** (dev profile + backoff, ~148k/s ceiling, CPUs free for Chrome/UI work) vs
  **prod mode** (busy-spin DEDICATED, ~800k/s). After editing: daemon-reload, restart admin, then roll
  each node (stop nodeN → restart driverN → start nodeN, followers first).
- Driver crash cascades to its node automatically (RestartCascades), EXCEPT for processes adopted after
  an admin restart (no monitor — admin-gateway#13); re-arm by API stop/start.

### ⚠️ Operational rules (from the 2026-07-02 incident — docs/incidents/)
- **NEVER run snapshot/housekeeping while any node is down, lagging, or recovering** (match#35:
  it strands the laggard PERMANENTLY; rejoin then corrupts its local archive via replication).
- Archives live on tmpfs: ~165 B/order/node → 16 GB /dev/shm fills in <1 min at max load. Housekeep
  between heavy load runs; a full shm wedges followers AND breaks ClusterTool/admin snapshot ops.
- Stranded-member reseed (validated): stop a healthy follower, copy its `cluster/`+`archive/` dirs over
  the member's wiped state EXCLUDING `cluster-mark*.dat`, `node-state.dat`, `archive-mark.dat`, `*.lck`,
  start both. Seconds of quorum outage.
- Status API can show stale (healthy-looking) data for dead nodes: verify liveness via `ss -uln`
  (ingress port bound) + fresh log lines, not status alone.
- OS tuning must be BOOT-PERSISTENT: `sudo make tune-persist` writes
  `/etc/sysctl.d/99-openexchange.conf` + `openexchange-tuning.service` (THP/governor). After any
  reboot or kernel upgrade, run `make tune-report` (shows runtime-vs-persisted drift) BEFORE starting
  drivers/nodes — unpersisted socket limits after the 2026-07-03 reboot crash-looped the drivers and
  corrupted node0's archive (match#48).

### Engine selection & tunables (system properties / env)
- `MATCH_ENGINE_IMPL` / `-Dmatch.engine.impl` — `array` (DEFAULT, array-backed) or `direct` (preallocated fallback).
- `-Dmatch.engine.book.capacity` — per-side resting-order pool for the array book (default 131072).
  Exhaustion is a loud `BOOK_FULL` reject (this replaces the old 64-orders-per-level cap). Memory ∝ capacity.
- `-Dmatch.egress.buffer.max` — egress buffer entry cap (default 200k). Egress is ALSO **byte-bounded**
  end-to-end (~176 MB: OMS 128 MB + market-data 32 MB) in `AppClusteredService`, so a slow/backed-up
  consumer sheds (loud CRITICAL log + OMS reconciliation) instead of OOM'ing the matching/consensus thread.

## Order Flow

```
1. HTTP POST/PUT/DELETE /api/v1/orders → OMS (8080, separate repo: order-management)
2. → Risk checks → Balance holds → ClusterClient.submitOrder()
3. → SBE CreateOrder/CancelOrder/UpdateOrder → Cluster ingress
4. → AppClusteredService.onSessionMessage()
5. → Engine.acceptOrder() → Dispatch(CREATE|CANCEL|UPDATE) → MatchingEngine
     (ArrayMatchingEngine by default; DirectMatchingEngine if MATCH_ENGINE_IMPL=direct)
6. → Publish TradeExecution + OrderStatus via egress
7. → MarketGateway → WebSocket → UI
```

## Testing Changes

After any infrastructure change:
1. Check cluster status: `curl http://localhost:8082/api/admin/status`
2. Verify all 3 nodes healthy and one is LEADER
3. Submit test order and verify market data flow
4. Check logs for errors: `curl "http://localhost:8082/api/admin/logs?node=0&lines=50"`

## Load Testing

```bash
./run-load-test.sh quick        # Smoke test: 1k/s for 10s
./run-load-test.sh baseline     # Baseline: 1k/s for 60s
./run-load-test.sh stress       # Stress: 10k/s for 60s
./run-load-test.sh endurance    # Endurance: 2k/s for 1 hour
./run-load-test.sh progressive  # Ramp: 1k → 10k/s
```

**Measuring true throughput (important).** `run-load-test.sh` does NOT pin the load generator's CPU
affinity. On a single box the generator's busy-spin threads contend with the cluster's matching/consensus
threads, so an **unpinned** run reports an artifactual ceiling (~9k/s). Pin the generator OFF the cluster's
cores to measure the real ceiling — e.g. `taskset -c 20-23 ./run-load-test.sh stress` on a 13700K (the 4
spare E-cores). Current verified ceiling (2026-07-02, external prod-profile drivers + `make optimize-os`,
single generator thread): **800k orders/sec @ 100.00%** (ingress p50 0.22µs); quiet mode caps ~148k/s.
Full methodology + caveats (latency metric semantics, ≥30s warmup rule, per-config ladders):
`docs/perf/2026-07-02-performance-baseline.md`. The old embedded-driver number was ~281k.
A **manual** load-gen invocation (outside the script) must include `--add-opens
java.base/jdk.internal.misc=ALL-UNNAMED` (plus `sun.nio.ch`, `java.nio`) or it dies with
`IllegalAccessError`; `run-load-test.sh` already sets these.

## Debugging

### Cluster Won't Start (Mark File Error)
```bash
curl -X POST http://localhost:8082/api/admin/cleanup
```

### Check Leader
```bash
curl http://localhost:8082/api/admin/status | jq '.leader'
```

### Force Node Rejoin
```bash
curl -X POST http://localhost:8082/api/admin/restart-node -d '{"nodeId": 0}'
```
