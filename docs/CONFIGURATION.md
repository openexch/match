# Configuration reference

Every runtime knob across the four Open Exchange repos. Unless noted
otherwise, Java knobs are resolved **environment variable first, then the
system property** (`OMS_HTTP_PORT` beats `-Doms.http.port`); exceptions are
flagged. Ports listed are defaults.

## match: cluster node

Source: `AeronCluster`, `AppClusteredService`, `Engine`, `MarketPublisher`
(match-cluster), `TransportConfig` (match-common).

| Knob | Default | Purpose |
|---|---|---|
| `CLUSTER_NODE` / `-Dnode.id` | `0` | This member's node id |
| `CLUSTER_ADDRESSES` / `-Dcluster.addresses` | `localhost` | Comma-separated cluster member addresses |
| `CLUSTER_PORT_BASE` / `-Dport.base` | `9000` | Base port; node N uses `base + 100*N` upward |
| `BASE_DIR` (env only) | `<cwd>/node<N>` | Cluster data directory (log, archive, mark files) |
| `DNS_DELAY` (env only) | `false` | Startup delay before DNS resolution (Kubernetes) |
| `METRICS_PORT` (env only) | `9500 + nodeId` | Prometheus node metrics port |
| `MATCH_ENGINE_IMPL` / `-Dmatch.engine.impl` | `array` | Matching engine: `array` (default) or `direct` (preallocated fallback). System property takes precedence here |
| `-Dmatch.engine.book.capacity` / `MATCH_ENGINE_BOOK_CAPACITY` | `131072` | Per-side resting-order pool for the array book; exhaustion is a loud `BOOK_FULL` reject |
| `-Dmatch.egress.buffer.max` (sysprop only) | `200000` | Egress buffer entry cap (egress is also byte-bounded, about 176 MB end to end) |

## match: snapshot cadence and log retention

Source: `SnapshotCadence`, `SnapshotLogPruner` (cluster-kit), wired in
`AppClusteredService`. The **Assets Engine reads the same names**, and so does
anything else built on cluster-kit.

The node takes its own snapshots and reclaims its own disk; nothing external has
to be running. The decision is made on the leader only and travels through the
consensus module, so every member snapshots at the same log position. An
unparseable value here is refused at startup rather than silently defaulted.

| Knob | Default | Purpose |
|---|---|---|
| `SNAPSHOT_LOG_BYTES` / `-Dsnapshot.log.bytes` | `1073741824` (1 GiB) | Cluster-log bytes since the last snapshot that force a new one; `0` disables the byte trigger |
| `SNAPSHOT_INTERVAL_MINUTES` / `-Dsnapshot.interval.minutes` | `5` | Quiet-market floor, so a slow day still leaves a recent restore point; `0` disables the timer |
| `SNAPSHOT_PRUNE_ENABLED` / `-Dsnapshot.prune.enabled` | `true` | Purge log segments below the newest snapshot after it completes |

Both triggers at `0` means the cluster never snapshots on its own: its log grows
without bound until the disk fills. It is logged loudly at startup and it is
never what you want in production.

**The byte trigger is the one that matters under load.** A fixed interval
silently caps throughput, because the whole log between two snapshots has to fit
in tmpfs. It also sets how often the leader pauses: taking a snapshot moves the
consensus module out of `ACTIVE`, and a leader only polls ingress while `ACTIVE`,
so no new order enters the log for the length of it. At 800k orders/s, 1 GiB is
roughly eight seconds of log. Raising the threshold trades RAM for fewer pauses.

**Purging strands a member that was offline across the snapshot** (match#35): it
can no longer catch up from this node's log and has to be reseeded by hand. That
is deliberate and it is what etcd does with its compacted WAL. A node purges
nothing while it is not caught up.

Observability on `/metrics`: `match_log_bytes_since_snapshot`,
`match_snapshot_requests_total`, `match_log_prunes_total`,
`match_log_bytes_reclaimed_total`. A flat reclaim counter under a climbing log is
a disk that is filling.

## match: transport / media driver

Source: `TransportConfig` (match-common). Full architecture:
`docs/kernel-bypass.md`.

| Knob | Default | Purpose |
|---|---|---|
| `TRANSPORT_DRIVER_MODE` / `-Dtransport.driver.mode` | `embedded` | `embedded` (in-JVM driver) or `external` (standalone media driver via IPC; production mode). Invalid values fail fast |
| `AERON_DIR` / `-Daeron.driver.dir` | `/dev/shm/aeron-<user>-<node>-driver` | Directory used to reach the media driver |
| `TRANSPORT_IDLE_MODE` / `-Dtransport.idle.mode` | `busy_spin` | `busy_spin` (lowest latency, burns a core) or `backoff` (dev/CI) |
| `TRANSPORT_INTERFACE` / `-Dtransport.interface` | unset (OS decides) | Network interface (address or address/prefix) for UDP channels |
| `TRANSPORT_MTU` / `-Dtransport.mtu` | `8192` | Channel MTU (needs loopback or jumbo frames) |
| `TRANSPORT_TERM_LENGTH` / `-Dtransport.term.length` | `16m` | UDP term buffer length (Aeron URI syntax) |

Fixed constants (compile-time, `InfrastructureConstants`): market gateway
WS port `8081`, admin API port `8082`, term buffer 16 MB, socket buffer
4 MB, session timeout 10 s, keep-alive 1 s.

## match: gateways and backup agent

Source: `AeronGateway` (match-gateway), `ClusterBackupApp` (match-cluster).
Gateway knobs are **env only**.

| Knob | Default | Purpose |
|---|---|---|
| `CLUSTER_ADDRESSES` (gateway) | `127.0.0.1,127.0.0.1,127.0.0.1` | Cluster nodes the gateway connects to |
| `EGRESS_HOST` / `EGRESS_PORT` (gateway) | `127.0.0.1` / `9091` | Gateway egress channel (note: the OMS uses 9093) |
| `GATEWAY_TYPE` | `gateway` | Names the gateway's `/dev/shm/aeron-<type>-<pid>` driver dir |
| `BACKUP_HOST` / `-Dbackup.host` | `localhost` | Backup agent host |
| `BACKUP_INTERVAL_SEC` / `-Dbackup.interval.sec` | `60` | Backup query interval |
| `BACKUP_STALL_EXIT_SEC` / `-Dbackup.stall.exit.sec` | `300` | Exit (for supervisor restart) after this long without progress |
| `BASE_DIR` (backup, env only) | `<cwd>/backup` | Backup storage directory |

## oms

Source: `OmsConfig` (oms-app), `ClusterClient` (oms-cluster-client).
Secrets support a `*_FILE` variant that reads the value from a file.

| Knob | Default | Purpose |
|---|---|---|
| `OMS_HTTP_PORT` | `8080` | REST edge and Prometheus `/metrics` |
| `OMS_GRPC_PORT` | `9090` | gRPC edge |
| `OMS_AUTH_MODE` | `api-key` | `api-key` (secure default: no keys configured means every request is rejected), `jwt` (HS256), or `dev` (accepts everything; never production) |
| `OMS_API_KEYS` / `OMS_API_KEYS_FILE` | empty | API keys for `api-key` mode |
| `OMS_JWT_SECRET` / `OMS_JWT_SECRET_FILE` | empty | HS256 secret for `jwt` mode |
| `OMS_POSTGRES_URL` | `jdbc:postgresql://localhost:5432/oms` | Ledger database |
| `OMS_POSTGRES_USER` | `oms` | Database user |
| `OMS_POSTGRES_PASSWORD` / `_FILE` | none | **No default.** Without valid credentials the OMS runs in-memory only (no durable ledger). Apply `V001__init_schema.sql` manually; there is no auto-migration |
| `AE_CLUSTER_ADDRESSES` | `127.0.0.1` | Assets Engine nodes. The AE is the **only** balance store: no config, no alternative, and the OMS refuses to start without a healthy one |
| `AE_CLUSTER_PORT_BASE` | `9300` | AE cluster port base |
| `AE_EGRESS_HOST` / `AE_EGRESS_PORT` | `127.0.0.1` / `9393` | Where the AE sends replies. Across hosts this must be the OMS's own routable address; the loopback default is correct only when everything shares one |
| `OMS_AE_HOLD_TIMEOUT_MS` | `250` | Per-hold round-trip budget before the order is rejected |
| `OMS_AE_CONNECT_TIMEOUT_MS` | `30000` | Boot gate: how long to wait for the AE balance projection before FATAL |
| `OMS_CORS_ORIGINS` | empty (none) | CORS allowlist for the REST edge |
| `OMS_AUDIT_LOG` | `oms-audit.log` | Audit log path (`off` disables) |
| `OMS_CLUSTER_INGRESS` | `localhost:9000` | Cluster ingress endpoint |
| `OMS_NODE_ID` | `0` | Node id used by the cluster client |
| `CLUSTER_ADDRESSES` (env only) | `127.0.0.1,127.0.0.1,127.0.0.1` | Cluster nodes for the OMS cluster client |
| `CLUSTER_PORT_BASE` (env only) | `9000` | Base port for computing ingress endpoints |
| `EGRESS_HOST` / `EGRESS_PORT` (env only) | `127.0.0.1` / `9093` | OMS egress channel (the market gateway uses 9091) |

## admin-gateway

Source: `config/config.go`, `services/process_manager.go`.

| Knob | Default | Purpose |
|---|---|---|
| `MATCH_PROJECT_DIR` | grandparent of the binary | match checkout root (locates `match-cluster/target/match-cluster.jar` etc.). Set it explicitly unless the binary runs from `<root>/admin-gateway/admin-gateway` with match as a sibling |
| `OMS_PROJECT_DIR` | sibling `order-management/` | oms checkout root (locates `oms-app/target/oms-app.jar`) |
| `ADMIN_PROJECT_DIR` | the binary's own directory | admin-gateway checkout root |
| `ADMIN_PORT` | `8082` | Admin API port |
| `ADMIN_BIND` | `127.0.0.1` | Bind address. Non-loopback binds are refused unless a token is set |
| `ADMIN_AUTH_TOKEN` / `ADMIN_AUTH_TOKEN_FILE` | empty | Bearer token for the admin API; empty means loopback-only dev mode |
| `ADMIN_LOG_FORMAT` | `json` | `json` or `text` |
| `ENGINE_DRIVER_MODE` | external | `embedded` reverts nodes to in-JVM media drivers |
| `ENGINE_DRIVER_PROFILE` | `dev` | `dev` (shared threads, backoff) or `prod` (busy-spin dedicated; set `SENDER_CORE`/`RECEIVER_CORE`/`CONDUCTOR_CORE` for the driver launcher) |

Derived paths: logs in `~/.local/log/cluster`, cluster IPC in
`/dev/shm/aeron-cluster`.

## trading-ui

Build-time Vite variables (baked into the bundle at `npm run build`).

| Knob | Default | Purpose |
|---|---|---|
| `VITE_ORDER_API_URL` | empty (same origin; dev proxy to `:8080`) | OMS REST base URL |
| `VITE_ADMIN_API_URL` | empty (same origin; dev proxy to `:8082`) | Admin API base URL |
| `VITE_MARKET_WS_URL` | unset (same origin; dev proxy to `:8081`) | Market-data WebSocket base |
| `VITE_AUTH_TOKEN` | `dev:1` | Bearer token sent to the OMS; set a real API key or JWT outside dev mode |

See `.env.production.example` in trading-ui for a production shape.

## Cross-repo gotchas

- `EGRESS_PORT` defaults differ by design: market gateway `9091`, OMS
  `9093`. Two consumers cannot share one egress port.
- `CLUSTER_ADDRESSES` defaults differ: cluster nodes and the backup agent
  default to `localhost`, clients and gateways to a 3-entry
  `127.0.0.1,...` list. Set it explicitly everywhere in real deployments.
- Precedence is env-first for most Java knobs, but the engine tunables
  (`match.engine.impl`, `match.engine.book.capacity`) are
  system-property-first.
- Aeron needs a writable `/dev/shm` and
  `net.core.rmem_max`/`net.core.wmem_max` of 16 MB (`sudo make
  tune-persist` in match, or `sysctl -w net.core.rmem_max=16777216
  net.core.wmem_max=16777216`); without it, replay channels stall and
  elections hang.
