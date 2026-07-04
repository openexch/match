# The Open Exchange stack

Open Exchange is open-source exchange infrastructure: a deterministic
matching engine on Raft consensus, an order management service with an exact
ledger, an operations gateway, and a trading UI. It is four repositories
versioned and released together as one product; this document is the
umbrella view.

| Repo | Role | Language |
|---|---|---|
| [match](https://github.com/openexch/match) | Matching engine cluster (this repo, the anchor): 3-node Aeron Cluster (Raft), deterministic matching core, market-data and order gateways | Java 21 |
| [oms](https://github.com/openexch/oms) | Order management service: REST/gRPC edge, auth, risk checks, balances and holds, Postgres ledger, cluster client | Java 21 |
| [admin-gateway](https://github.com/openexch/admin-gateway) | Process manager and operations API: supervises nodes, drivers, gateways, and the OMS; snapshots, backups, rolling updates | Go 1.23 |
| [trading-ui](https://github.com/openexch/trading-ui) | Trading and admin web interface | React 19 / Node 22 |

## Big picture

```
                        Browser
                           |
                    [ trading-ui ]
                    REST /api/v1  \  WS market data        [ admin ops ]
                           |       \                            |
                           v        \                           v
                    +-------------+  \                  +----------------+
                    |     OMS     |   +---------------- | admin-gateway  |
                    | HTTP :8080  |                     |  HTTP :8082    |
                    | gRPC :9090  |                     | process mgmt,  |
                    +------+------+                     | snapshots,     |
        auth, risk checks, |  ^                         | backups,       |
        balance holds,     |  | egress                  | rolling update |
        Postgres ledger    |  | (status, fills)         +-------+--------+
                           v  |                                 |
                  ingress (SBE over Aeron)              supervises everything
                           |  |                                 |
              +------------+--+--------------------------------+------+
              |                Aeron Cluster (Raft)                   |
              |   +--------+      +--------+      +--------+          |
              |   | node 0 | <--> | node 1 | <--> | node 2 |          |
              |   | :9000+ |      | :9100+ |      | :9200+ |          |
              |   +--------+      +--------+      +--------+          |
              |   deterministic matching engine, replicated log,      |
              |   snapshots + archive, ClusterBackup to disk          |
              +----------------------------+--------------------------+
                                           | egress (trades, books)
                                           v
                                  [ market gateway ]
                                  WebSocket :8081 --> UI order books,
                                                      trades, candles
```

## Order lifecycle

1. The UI (or any API client) sends an order to the OMS REST/gRPC edge with
   a bearer token. Money is exact decimal strings; ids are JSON strings.
2. The OMS authenticates (pluggable SPI: `api-key`, `jwt`, or explicit
   `dev`), runs risk checks, places balance holds, and submits the order to
   the cluster over Aeron ingress (SBE-encoded).
3. The leader appends to the replicated Raft log; every node's matching
   engine consumes the same log deterministically (no wall clock, no
   randomness, byte-identical state on every node).
4. Matches publish `TradeExecution` and `OrderStatus` on egress. The OMS
   consumes them to settle balances and persist the ledger (Postgres is
   ground truth: `filledQty == SUM(executions)`, conservation asserted by
   the E2E suite). The market gateway fans the same stream out to WebSocket
   subscribers.
5. The admin gateway supervises all processes (nodes, external media
   drivers, gateways, OMS, backup agent), refuses unsafe operations against
   an unhealthy cluster, and drives snapshots, archive housekeeping,
   backups, and zero-downtime rolling updates.

## Durability and recovery

- **Replication**: 3-node Raft; the cluster survives any single-node loss
  and elects a new leader in seconds. The OMS reconciles in-flight orders
  across switchovers (egress gap detection plus authoritative open-order
  resnapshot).
- **Snapshots and archive**: periodic cluster snapshots plus the Aeron
  archive; live-safe housekeeping reclaims disk below the latest snapshot.
- **Backup**: a ClusterBackup agent replicates to disk with heartbeat-based
  freshness reporting; restore and power-loss drills are documented in
  `docs/backup-restore.md` (measured RPO around zero).
- **OMS persistence**: Postgres ledger with startup rebuild (positions and
  open orders reconstructed on boot), plus restart-surviving idempotency
  keys.

## What we provide vs. what you own

Open Exchange is the infrastructure layer. A production deployment plugs it
into the operator's own compliance and custody environment.

| Concern | Provided by Open Exchange | You (the integrator) own |
|---|---|---|
| Order matching, determinism, replication | Yes | |
| Order management, risk checks, holds, ledger | Yes | |
| Operations: snapshots, backups, rolling updates, runbooks | Yes | |
| Trading/admin UI | Yes | |
| Authentication mechanism | SPI + api-key/JWT providers | Identity provider, user onboarding, session policy |
| KYC / AML / sanctions screening | | Yes |
| Custody, wallets, deposits/withdrawals, fiat rails | | Yes (the ledger tracks balances; moving real assets is yours) |
| Market surveillance, regulatory reporting | | Yes |
| Network perimeter, DDoS protection, TLS termination | | Yes |
| Legal authorization to operate an exchange | | Yes |

## Version and compatibility matrix (v0.3.0-beta)

One stack version spans all four repos; mixing repo versions across a stack
version is unsupported.

| Surface | Version / contract |
|---|---|
| Java (match, oms) | 21 |
| Aeron / Aeron Cluster | 1.51.0 (match and oms pin the same version) |
| SBE wire schema (OMS and gateways to cluster) | schema id 1, version 3 (`match-common/src/main/resources/sbe/order-schema.xml`); generated codecs ship in `match-common` |
| OMS gRPC proto | package `com.openexchange.oms.grpc` (`oms-api/src/main/proto/`) |
| OMS REST contract | frozen per `oms/docs/API.md`: money as exact decimal strings, ids as JSON strings, documented error taxonomy |
| Cross-repo build | oms depends on `com.match:match-common:1.0` installed from a sibling match checkout (not on Maven Central) |
| Go (admin-gateway) | 1.23 |
| Node (trading-ui) | 22 (React 19, Vite 8) |
| PostgreSQL | 14+ (tested against 16) |
| Redis | 7 |
| OS | Linux with writable `/dev/shm`; `net.core.rmem_max`/`wmem_max` raised to 16 MB for cluster replay channels |

## Disclaimer

Open Exchange is **beta software**, provided as is, without warranty of any
kind (see the Apache-2.0 license). It has not had an external security
audit and is not yet recommended for production deployments holding real
funds. Nothing in this project constitutes legal or financial advice;
operating an exchange requires licenses and compliance obligations that are
entirely the operator's responsibility.

## Further reading

- `docs/QUICKSTART.md`: fresh clone to a working stack
- `docs/CONFIGURATION.md`: every runtime knob across the four repos
- `docs/RELEASING.md`: how stack releases are cut
- `docs/backup-restore.md`, `docs/kernel-bypass.md`,
  `docs/hot-path-allocations.md`, `docs/perf/`: deep dives (this repo)
- `oms/docs/API.md`, `oms/docs/PROTOCOLS.md`: the OMS API contract
- `admin-gateway/docs/RUNBOOKS.md`: operations runbooks
