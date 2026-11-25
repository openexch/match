# match

The Open Exchange matching engine: a deterministic order matching service that runs as a 3-node [Aeron Cluster](https://github.com/aeron-io/aeron) (Raft). Java 21.

**Status: nightly prerelease** (`v1.0.0-nightly.20261105`). Not for production use.

User documentation (architecture, configuration, operations, API): **https://openexchange.dev/docs**. Release notes: [GitHub Releases](https://github.com/openexch/match/releases).

## Where it fits in v1

| Repo | Role |
|---|---|
| **match** (this repo) | Matching engine cluster, market-data gateway, load generator |
| [core](https://github.com/openexch/core) | Order and ledger core: order lifecycle, risk, holds and the ledger in one Java Aeron cluster |
| [cluster-kit](https://github.com/openexch/cluster-kit) | Shared cluster machinery used by both engines (lifecycle, ports, auth, snapshot cadence) |
| [cluster-tools](https://github.com/openexch/cluster-tools) | C++ cluster client, ME forwarder, settlement bridge, projectors |
| [tools](https://github.com/openexch/tools) | Probe and test tooling |

The engine consumes order commands from its replicated log, matches them with price-time priority, and publishes trades, order status and book data on cluster egress. It also writes a per-node ME journal (trades, terminals, command outcomes) to an Aeron Archive for downstream consumers.

## Modules

| Module | Contents | Artifact |
|---|---|---|
| `match-common` | Domain types, `FixedPoint` (10^8 scale), SBE schemas and generated codecs, `TransportConfig` | `match-common` jar (published to GitHub Packages for consumers) |
| `match-cluster` | Clustered service, engine, order books, publisher, ME journal, backup agent, bundle capture | `match-cluster/target/match-cluster.jar` (main class `com.match.Main`) |
| `match-gateway` | Market-data WebSocket gateway (`MarketGatewayMain`, port 8081) and edge publisher. Used by the v0.5 demo stack | `match-gateway/target/match-gateway.jar` (no main class in the manifest) |
| `match-loadtest` | Load generator (`com.match.loadtest.LoadGenerator`) | `match-loadtest/target/match-loadtest.jar` |

Other directories: `deploy/` (systemd units, launcher and image for cluster members, see `deploy/README.md`; media driver launcher, OS tuning, OOM protection units), `edge/market-relay/` (Cloudflare Worker that fans out market data), `grafana/` (Prometheus and Grafana starter config), `tools/` (multi-node durability harnesses).

Wire schemas live in `match-common/src/main/resources/sbe/`:

- `order-schema.xml`: schema id 1, version 12 (ingress commands and egress)
- `me-journal-schema.xml`: schema id 4, version 1 (the ME journal stream)

Schema ids are unique across the repos: `sbe-schema-ids.txt` lists every id in use (the same file in core, match, cluster-tools, gateway and tools), and `SbeSchemaIdRegistryTest` (match-common) fails when an id is listed twice or a schema file here is missing from the list or disagrees with its line. Id 3 is the core money journal; the earlier match settlement journal that also used it is gone (nothing wrote it since the ME journal, schema 4). A new schema takes a free id in that file, in every repo, first.

## Build and test

Requirements: Linux, Java 21, Maven 3.9+.

The build depends on `com.openexchange:cluster-kit` at the version pinned in `pom.xml` (`cluster-kit.version`). It is resolved from GitHub Packages, which requires a token with `read:packages` even for public packages:

```bash
export GITHUB_ACTOR=<your-github-user>
export GITHUB_TOKEN=<token with read:packages>
mvn -B -s .mvn-settings.xml clean verify
```

This is the same command CI runs. Alternatively, install that exact cluster-kit version into `~/.m2` from a cluster-kit checkout and build without `-s .mvn-settings.xml`.

Useful targets:

```bash
mvn -B -s .mvn-settings.xml clean package -DskipTests   # jars only
mvn -s .mvn-settings.xml -pl match-common generate-sources  # regenerate SBE codecs after a schema edit (CI fails on drift)
make determinism       # determinism corpus + snapshot replay suite
make update-goldens    # regenerate determinism goldens after an intentional output change
```

`TimescaleIntegrationTest` (market-data persistence) runs only when `MARKET_PG_TEST_URL` points at a TimescaleDB instance; otherwise it skips.

## Running a node

```bash
CLUSTER_NODE=0 \
CLUSTER_ADDRESSES=host0,host1,host2 \
BASE_DIR=/var/lib/match/node0 \
ME_AUTH_KEYS='<keyId>:<CLASS>:<secretHex>::users=*;...' \
java -jar match-cluster/target/match-cluster.jar
```

The jar manifest carries the required `Add-Opens`, so a plain `java -jar` works. Aeron needs a writable `/dev/shm` and raised socket buffers (`sudo make tune-persist`, see `deploy/tuning/system-tuning.sh`).

The backup agent runs from the same jar: `java -cp match-cluster/target/match-cluster.jar com.match.infrastructure.persistence.ClusterBackupApp`.

## Key configuration

All knobs are environment variables (most also accept a system property). The full reference is on the website: https://openexchange.dev/docs (Configuration).

| Variable | Default | Purpose |
|---|---|---|
| `CLUSTER_NODE` | `0` | Member id |
| `CLUSTER_ADDRESSES` | `localhost` | Comma-separated member hosts |
| `CLUSTER_PORT_BASE` | `9000` | Base port; per-member offsets come from cluster-kit `ClusterPorts` |
| `BASE_DIR` | `<cwd>/node<N>` | Cluster data directory |
| `METRICS_PORT` | computed: `CLUSTER_PORT_BASE + nodeId x CLUSTER_PORT_STRIDE + 6` | `/health`, `/ready`, Prometheus `/metrics`: the same on every host at stride 0. Optional; a value that disagrees with the computed port refuses the boot |
| `TRANSPORT_CONSENSUS_TERM_LENGTH` | `1m` | The consensus channel's term. Without it the channel took the driver's term (16m): two publications and two images of 48 MB per member of three. A power of two in [64k, 1g]; anything else refuses the boot |
| `ME_AUTH_KEYS` | unset (every session rejected) | Node key table: `keyId:CLASS:secretHex::users=*` entries joined by `;`. Classes: `GATEWAY`, `FORWARDER`, `ADMIN`, `REPLICA`. A malformed table stops the node at boot |
| `ME_AUTH_CREDENTIAL` | unset (client refuses to start) | Client credential `keyId:CLASS:secretHex`, required by the backup agent (`REPLICA`), market gateway (`GATEWAY`) and load generator |
| `MATCH_ENGINE_FROM_CONFIG` | `false` | `true` creates engines from the replicated `EngineConfig` message instead of the built-in market set |
| `MATCH_ENGINE_IMPL` | `array` | Order book implementation: `array` or `direct` |
| `SETTLEMENT_JOURNAL_ENABLED` / `SETTLEMENT_JOURNAL_DIR` | `false` / none | Enables the ME journal; the directory must be on disk, not tmpfs |
| `SNAPSHOT_LOG_BYTES`, `SNAPSHOT_INTERVAL_MINUTES`, `SNAPSHOT_PRUNE_ENABLED` | see cluster-kit | Snapshot cadence and log pruning (shared with the core) |
| `TRANSPORT_DRIVER_MODE` | `embedded` | `external` connects to a standalone media driver (`deploy/media-driver/launch-driver.sh`, or the cluster-tools `aeronmd@.service` under systemd) |
| `TRANSPORT_IDLE_MODE`, `TRANSPORT_MTU`, `TRANSPORT_TERM_LENGTH`, `TRANSPORT_INTERFACE` | see `TransportConfig` | Aeron transport tuning |
| `TRANSPORT_DRIVER_CLIENT_LIVENESS_MS` | `5000` | Embedded mode: the driver's client liveness timeout (1000..600000), also the longest whole-process pause a node survives (after a longer stall the node exits 1 for a supervised restart). External mode: `aeron.client.liveness.timeout` in `deploy/media-driver/driver.properties` |
| `TRANSPORT_DRIVER_PUBLICATION_UNBLOCK_MS` | `10000` | Embedded mode: the driver's publication unblock timeout (2000..1200000); must exceed `TRANSPORT_DRIVER_CLIENT_LIVENESS_MS` or the node refuses to boot. External mode: `aeron.publication.unblock.timeout` |

Invalid values are refused at startup rather than silently defaulted.

### Log retention and catch-up

The pruner and the cluster watchdogs come from cluster-kit; the engine arms them on every node in both driver modes, with no setting of its own. A value outside its range refuses the boot. The retention floor applies when `SNAPSHOT_PRUNE_ENABLED` is on (the default). Details in cluster-kit's README ("Log retention and a member that was away", "Rejoin and peer reachability").

| Variable | Default | Purpose |
|---|---|---|
| `RETENTION_FLOOR` | `apply` | `apply`: a member never purges log that another member may still need (the leader holds its purge at the lowest member position, a follower at the leader's log start). `report` only logs the floor |
| `RETENTION_MEMBER_WAIT_MS` | `1800000` | how long the floor waits for a member that does not move before the purge goes on without it (1000..604800000) |
| `RETENTION_MEMBER_WAIT_BYTES` | `2147483648` | how far behind the newest snapshot a member may fall before the floor stops waiting for it (1048576..1099511627776) |
| `RETENTION_PROBE_TIMEOUT_MS` | `5000` | bound on one read of the leader's log start from the leader's archive (100..60000) |
| `RETENTION_RECHECK_MAX_MS` | `60000` | longest wait between re-checks of a retention floor that stopped the last purge pass; a floor that rose is purged behind at once, without a new snapshot (1000..3600000) |
| `ENGINE_CATCHUP_REFUSALS` | `3` | failed catch-up attempts in a row after which a member reads the leader's log start; a member below it exits 1 (1..1000) |
| `ENGINE_CATCHUP_REFUSAL_WAIT_MS` | `30000` | the time bound next to the count: catch-up failing this long triggers the same check (1000..3600000) |
| `ENGINE_REJOIN_PEER_WINDOW_MS` | `10000` | how long a gap in the evidence of a reachable quorum is bridged before the rejoin watchdog's clock is cleared (1000..600000); keep it at or above the driver's publication connection timeout. `ENGINE_REJOIN_WAIT_MS` must exceed it or the boot refuses |
| `ENGINE_LEADER_ELECT_WAIT_MS` | `300000` | how long a leader elect may stay in LEADER_LOG_REPLICATION or LEADER_READY (Aeron gives these no timeout) while a quorum of members is reachable before it exits 1 for a supervised restart (10000..3600000); must exceed `ENGINE_REJOIN_PEER_WINDOW_MS` |
| `ENGINE_REJOIN_PROBE_TERM_LENGTH` | `65536` | term length in bytes of the rejoin watchdog's idle peer probe publications, opened sparse on their own term instead of the consensus channel's (a power of two, 65536..1073741824) |
| `ENGINE_REJOIN_PROBE_STREAM_ID` | `4306` | stream id of the rejoin watchdog's participation subscription on each member's own consensus endpoint, opened once the consensus module runs; the peer probes publish to it, so a member still loading its snapshot reads unreachable to its peers (1..2147483647, must differ from the consensus stream id; use the same value on every member) |

## Load testing

```bash
ME_AUTH_CREDENTIAL=<keyId>:GATEWAY:<secretHex> ./run-load-test.sh quick
```

Scenarios: `quick`, `baseline`, `market-maker`, `stress`, `spike`, `deep-book`, `endurance`, `progressive`, `custom <rate> <duration> <threads> <scenario>`. Set `LOADGEN_CORES` to pin the generator away from the cluster's cores.

## Further reading in this repo

- `docs/settlement-journal-semantics.md`: when the journal emits a terminal for an amend
- `docs/ingress-apply-failures.md`: decode failures versus application faults
- `docs/gateway-port-spec.md`: the market gateway's book dedup and staleness rules

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately as described in the [security policy](https://github.com/openexch/.github/blob/main/SECURITY.md).

## License

[Apache License 2.0](LICENSE).
