# Changelog

All notable changes to `match` (the Open Exchange matching engine cluster)
are documented here. The stack (`match`, `oms`, `admin-gateway`,
`trading-ui`, `assets`) is versioned together; one version spans all five repos.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.4.0-beta] - 2026-07-22

The money release: a replayable settlement journal, totally-ordered egress
(SBE v7), market data at the edge, and the Assets Engine joining the stack
(now five repos, one version).

### Added
- Settlement journal: lossless per-node recording of every trade and terminal
  event, carrying the OMS order ids (the money key); SBE schema id=3;
  watermark-gated retention; flush-error egress discards counted and exported
  (#120, #121, #122, #123, #143).
- Totally-ordered egress: `egressSeq` log-position order key on trade and
  status egress (SBE v7) (#119); engine reject reason on order-status egress
  (SBE v6) (#110); taker side in TradesBatch and per-user status broadcast
  removed (SBE v5) (#74).
- Single monotonic order-book version chain across snapshots and deltas (#71);
  periodic book resnapshot bounds gateway staleness after a chain break (#112).
- Edge fan-out for market data: publish once, serve every viewer from
  Cloudflare; `market.openexch.io/ws*` served from the edge; publisher
  liveness + relay hardening for gapped replay, snapshot starvation, cold
  start, and the trades race; feed DO pinned near the publisher (#84, #85,
  #86, #88, #111).
- Durable market-data time series via TimescaleDB (#72).
- Per-session egress observability on the ME leader (#144); chain-break
  diagnostics at the default log level with per-side versions (#114); Grafana
  edge/demo alerts and scrapes (#77, #87).

### Fixed
- Post-only honored on LIMIT_MAKER amends; processUpdate matched as a taker
  (#104).
- Taker terminates at the match cap instead of resting a crossed remainder
  (#106).
- quantity<=0 rejected at order admission — silent loss + LIMIT_MAKER poison
  pill (#103).
- DirectIndexOrderBook requote exhausted the 64-slot/level cap; tombstones
  compact on allocation pressure (#101).
- BOOK_SNAPSHOT guarded against stale redelivery at leader switchover (#105);
  chain-broken BOOK_DELTAs dropped and the book flagged stale instead of
  drifting (#108); the version chain no longer advances when no frame is
  emitted (#116).
- Torn OHLCV: current-candle in-place update raced readers (#90); live
  trades/candles buffered until the async subscribe snapshot flushes (#107);
  REST empty-book response carries the version fields the WS path includes
  (#109).
- Maker terminal FILLED + replace-cancel journal exemption (AE hold
  integrity) (#135).
- Hot-path `logger.warn` guarded — stops consensus-thread allocation (#118).
- Replication channels honor TRANSPORT_LOG_TERM_LENGTH (#127); cluster
  log-channel term length configurable (#102); journal archive
  catalogFileSyncLevel >= fileSyncLevel (#124).
- Gateway retains 64 book levels so deletes backfill the visible 20 (#70);
  follower "Market data egress full" spam silenced (#100); launch-driver.sh
  refuses to start over a live driver pid (#113).

### Changed
- The stack is now five repos: `assets` (the Assets Engine) joins the
  coordinated version at v0.4.0-beta.
- Aeron 1.52.2, Agrona 2.5.0, SBE 1.39.0, slf4j 2.0.18, PostgreSQL 42.7.12;
  netty 4.2.16.Final (CVE-2026-44891)
  (#78-#82, #131, #141).
- Contact email is info@openexch.io (#83).

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
