# Changelog

All notable changes to `match` (the Open Exchange matching engine cluster)
are documented here. The stack (`match`, `oms`, `admin-gateway`,
`trading-ui`) is versioned together; one version spans all four repos.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.3.0-beta] - 2026-07-05

The beta hardening release: correctness and durability first, then security,
observability, and a frozen wire contract.

### Fixed
- `FixedPoint.multiply`/`divide` are now exact and throw on true overflow;
  overflowing orders are rejected at admission with `OVERFLOW` instead of
  silently truncating (#52).
- Egress ordering primitive plus open-order membership resnapshot: order
  status can no longer be reordered or silently dropped across leader
  switchovers; the OMS reconciles from an authoritative snapshot (#53).
- Fail-fast on the archive-replay error hot-loop instead of spinning with a
  corrupt archive (#55).
- ClusterBackup watchdog and heartbeat: a wedged backup agent now exits and
  restarts, and backup freshness is observable; validated by a power-loss
  drill (#57).
- Market-WS `ORDER_STATUS` sends `omsOrderId` as a JSON string, matching the
  frozen id contract (#65).

### Added
- `FixedPoint.parse`: exact decimal-string round-trip for the money-as-strings
  API contract (#63).
- Hot-path-safe node metrics, gateway `/metrics`, and a Grafana starter kit
  (#61), plus alert rules for the admin-gateway metrics (#62) and engine
  diagnostic counters on `EGRESS-DIAG` (#42).
- Per-client WebSocket backpressure with conflation and resync in the market
  gateway (#40).
- External media-driver mode with per-profile tuning (`TRANSPORT_DRIVER_MODE`,
  dev/prod profiles) (#38).
- OS tuning persisted across reboots (sysctl.d plus a boot tuning unit) (#56).
- P1 verification tooling: gate storm harness (load plus repeated leader
  switchovers with a divergence report) (#44), stranded-follower regression
  gate (#45), per-user balance reconciliation against the OMS Postgres ledger
  (#46), rolling-cancel load mode and reject-reason tallies (#59, #60).

### Security
- Trivy dependency and secret scanning in CI (#43).

### Docs
- `docs/RELEASING.md`, 2026-07-02 perf baseline, incident reports, and the
  bug9 repro harness (#41, #28, #29).

## [0.2.0-alpha] - 2026-06-28

- Array-backed order book (AA tree plus pooled FIFO in flat primitive
  arrays): geometry-free, no per-level order cap, zero hot-path allocation,
  about 3x less memory; now the default engine (`MATCH_ENGINE_IMPL=direct`
  falls back).
- Egress byte-bounded end to end; sheds load with OMS reconciliation instead
  of OOMing under overload.
- Determinism corpus: both engines replay byte-identical.

## [0.1.0-alpha] - 2026-05-08

- First tagged release: 3-node Aeron Cluster (Raft) matching engine with
  market-data and order gateways.

[0.3.0-beta]: https://github.com/openexch/match/compare/v0.2.0-alpha...v0.3.0-beta
[0.2.0-alpha]: https://github.com/openexch/match/compare/v0.1.0-alpha...v0.2.0-alpha
[0.1.0-alpha]: https://github.com/openexch/match/releases/tag/v0.1.0-alpha
