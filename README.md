# match

The Open Exchange matching engine: a deterministic order matching service that runs as a 3-node [Aeron Cluster](https://github.com/aeron-io/aeron) (Raft). Java 21.

**Status: nightly prerelease** (`v1.0.0-nightly.20261105`). Not for production use.

User documentation (architecture, configuration, operations, API): **https://openexchange.dev/docs**. Release notes: [GitHub Releases](https://github.com/openexch/match/releases).

## Where it fits in v1

| Repo | Role |
|---|---|
| **match** (this repo) | Matching engine cluster, market-data gateway, load generator |
| [assets](https://github.com/openexch/assets) | Order and ledger core: order lifecycle, risk, holds and the ledger in one Java Aeron cluster |
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

Other directories: `deploy/` (media driver launcher, OS tuning, OOM protection units), `edge/market-relay/` (Cloudflare Worker that fans out market data), `grafana/` (Prometheus and Grafana starter config), `tools/` (multi-node durability harnesses).

Wire schemas live in `match-common/src/main/resources/sbe/`:

- `order-schema.xml`: schema id 1, version 12 (ingress commands and egress)
- `me-journal-schema.xml`: schema id 4, version 1 (the ME journal stream)
- `settlement-journal-schema.xml`: schema id 3, version 1 (legacy, kept for older readers)

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
| `METRICS_PORT` | `9500 + nodeId` | Prometheus `/metrics` |
| `ME_AUTH_KEYS` | unset (every session rejected) | Node key table: `keyId:CLASS:secretHex::users=*` entries joined by `;`. Classes: `GATEWAY`, `FORWARDER`, `ADMIN`, `REPLICA`. A malformed table stops the node at boot |
| `ME_AUTH_CREDENTIAL` | unset (client refuses to start) | Client credential `keyId:CLASS:secretHex`, required by the backup agent (`REPLICA`), market gateway (`GATEWAY`) and load generator |
| `MATCH_ENGINE_FROM_CONFIG` | `false` | `true` creates engines from the replicated `EngineConfig` message instead of the built-in market set |
| `MATCH_ENGINE_IMPL` | `array` | Order book implementation: `array` or `direct` |
| `SETTLEMENT_JOURNAL_ENABLED` / `SETTLEMENT_JOURNAL_DIR` | `false` / none | Enables the ME journal; the directory must be on disk, not tmpfs |
| `SNAPSHOT_LOG_BYTES`, `SNAPSHOT_INTERVAL_MINUTES`, `SNAPSHOT_PRUNE_ENABLED` | see cluster-kit | Snapshot cadence and log pruning (shared with the assets engine) |
| `TRANSPORT_DRIVER_MODE` | `embedded` | `external` connects to a standalone media driver (`deploy/media-driver/launch-driver.sh`) |
| `TRANSPORT_IDLE_MODE`, `TRANSPORT_MTU`, `TRANSPORT_TERM_LENGTH`, `TRANSPORT_INTERFACE` | see `TransportConfig` | Aeron transport tuning |

Invalid values are refused at startup rather than silently defaulted.

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
