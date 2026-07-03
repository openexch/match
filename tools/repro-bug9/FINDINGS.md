# Bug #9 — Findings (reproduce-only, 2026-06-19)

## Headline
**OMS systematically under-reports order fill quantity (`filledQty`) because it trusts the
cluster's `OrderStatus` egress stream, which is coalesced/lossy (no sequence id). This happens in
steady state — a leader switchover is NOT required to trigger it.** The independent, lossless
`TradeExecution` stream (carrying the monotonic `tradeId`) is the true record of fills.

## Evidence
Reconciled an independent Aeron egress observer (lossless ground truth — verified contiguous
`tradeId`s, conserves, and per-trade userIds match submitted orders) against OMS REST state.

| Run | Trades | filledQty wrong (OMS vs true) | engine OrderStatus-vs-TradeExecution divergence | tradeId gaps |
|---|---|---|---|---|
| Control, **no switchover**, 8/s | 158 | 86 / 162 (53%) | 86 / 162 | 0 |
| **4 forced switchovers**, 50/s | 5,008 | 2,602 / 5,010 (52%) | 2,602 / 5,010 | 0 |

- **The divergence rate (~52%) is identical with and without switchover** → the defect is the
  coalesced OrderStatus egress, not the leader change itself.
- **OMS `filledQty` exactly equals the lossy OrderStatus value**, not the true TradeExecution sum.
  Example (control): order …908779520 — TradeExecution truth = 0.294 filled, OrderStatus = 0.0,
  OMS API `filledQty` = 0.0.
- Root: `OrderLifecycleManager.onClusterOrderStatus()` overwrites `filledQty` from each OrderStatus
  message; `OrderStatusBatch` (SBE id 24) has **no sequence/exec id**, and the cluster's
  `MarketPublisher` coalesces order-status updates per flush window — so intermediate/final fill
  states are dropped before they reach OMS.

## Secondary observations
- **Egress is RE-DELIVERED on reconnect.** Across the switchover run the observer received 10,669
  trade rows for only 5,008 unique `tradeId`s — the new leader re-sent ~5,661 trades to the
  reconnecting client. So **TradeExecutions are not lost across a switchover** (no tradeId gaps);
  P2b "missed fills" did not manifest as lost trades in these runs.
- **Balance settlement is largely protected.** Balances settle via TradeExecution deduped by
  `tradeId` (`InMemoryBalanceStore` here — Postgres/Redis were down). Aggregate balances conserve
  exactly; re-delivered duplicates are absorbed by the tradeId dedup (the in-memory set survives
  node switchovers because the OMS process is not restarted). A smaller **per-user balance residual**
  remains (control ≤0.1 BTC/user; switchover run ≤0.22 BTC/user, ~0.3% of volume, conserving in
  aggregate) whose exact cause is not fully isolated here — settlement-by-tradeId should be exact,
  so the residual points at a hold/price-improvement accounting path or a partial dependence on the
  same lossy status stream. **Needs OMS source-level tracing or the execution DB (currently down) to
  pin down.**
- **Compact-removal fix held:** 0 new `unknown recording id` across 4 forced re-elections; cluster
  self-healed each time (leader 2→0→2→0→2, all 3 nodes rejoined).

## Fix direction (NOT implemented — for a follow-up session)
1. **Make filledQty derive from the authoritative source.** Either (a) have OMS compute `filledQty`
   by summing `TradeExecution`s per order (it already receives them, deduped by `tradeId`), instead
   of trusting `OrderStatus`; or (b) add a per-order **monotonic sequence number / cumulative
   filledQty** to `OrderStatusBatch` and have OMS reject non-monotonic / stale updates
   (`onClusterOrderStatus` guard: ignore if incoming filledQty < current).
2. **Stop the cluster from coalescing away terminal OrderStatus** (ensure the final FILLED status of
   an order is always emitted before the order is evicted), or accept (1a) and treat OrderStatus as
   advisory only.
3. **Investigate the per-user balance residual** with the execution DB up (Postgres) or by
   instrumenting `LedgerService`/hold release on price-improved fills.

## FIX APPLIED + VERIFIED (2026-06-19)

OMS-side fix deployed (no cluster/SBE change): `filledQty` is now driven authoritatively by the
lossless **TradeExecution** stream (`OrderLifecycleManager.applyFill`, called from
`OmsCoreEngine.onTradeExecution`), with the OrderStatus write reduced to a **monotonic guard**;
re-delivered trades are deduped via the existing per-`tradeId` `settle()` (now returns applied/dup).
56 unit tests pass.

Repro verification (no-switchover, post-fix), `runs/20260619-175746`:
- `filledQtyMiscounts_omsVsTrue`: **52% → 5.8%** (199/3442). The residual is **timing-only**: all are
  "under" on still-OPEN (PARTIALLY_FILLED/NEW) orders by ~1 in-flight trade (observer keeps recording
  during async cancel); **zero miscounts on terminal/FILLED orders**.
- `engineStreamDivergence_statusVsTrades`: unchanged at 52% — confirms the cluster's OrderStatus
  stream is untouched (a drop here would have signalled accidental cluster impact).
- 0 tradeId gaps.

### Incident during verification (resolved)
The earlier 4-switchover repro run wedged cluster **egress** (both market gateway AND OMS stuck in a
stale-egress reconnect loop; leader accepted sessions but sent no egress/keep-warm). Root cause: the
running cluster lacks the **flush-timer-rearm fix (match PR #18)** — handoff §1's known wedge on the
recover-into-leader path. Recovered via `stop-all`/`start-all` (clean: 0 `unknown recording id`).
**This was NOT caused by the OMS code change** (untouched market gateway equally affected).

## Remaining gaps / follow-ups
1. **Deploy match PR #18 (flush-timer-rearm) to the cluster — HIGH priority.** Until then, ANY leader
   switchover re-wedges egress (caused today's incident).
2. **Lossy OrderStatus also drops terminal CANCELLED/REJECTED updates.** A cancel is sent via reliable
   ingress (engine removes the resting order), but OMS may never see the CANCELLED egress → OMS view
   stays PARTIALLY_FILLED AND **the hold for the cancelled remainder is never released** (funds stuck
   locked). filledQty has no this-side remedy (a cancel isn't a trade); the complete fix is
   cluster-side: sequence/guarantee OrderStatus delivery (don't coalesce away terminal states), or an
   OMS periodic reconcile against cluster state. Same root cause as bug #9.
3. Per-user **balance residual** (~0.05 BTC, conserves in aggregate) persists — partly open-order
   snapshot timing; confirm with the execution DB (Postgres) up.

## How to reproduce
`cd match/tools/repro-bug9 && ./run.sh 4 12 50 130` (control baseline: `./run.sh 0 8 8 25`).
See `README.md`. Artifacts per run in `runs/<timestamp>/reconciliation-report.json`.
