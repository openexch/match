# Bug #9 repro harness — OMS fill exactness across leader switchover

**Observation-only.** Reproduces and *classifies* the OMS fill-exactness bug across Raft leader
switchovers. Drives load through the OMS, forces controlled switchovers, and reconciles OMS state
against an independent egress observer. No production code is modified.

## The bug (three surfaces)
1. **filledQty miscount** — `OrderLifecycleManager.onClusterOrderStatus()` overwrites filledQty with
   no sequence/monotonicity check; `OrderStatusBatch` has no sequence id.
2. **dedup durability** — fills dedup by `tradeId` (`RedisBalanceStore` Lua, 1h TTL;
   `InMemoryBalanceStore` set lost on restart). NOTE: with the in-memory store and no OMS restart,
   the dedup set survives node switchovers, so duplicates are unlikely in a short run.
3. **missed fills (P2b)** — `ClusterClient` rebuilds the egress subscription on leader change with no
   resume position; an in-flight fill dropped during the swap is never reconciled.

## Pieces
- `Bug9Observer.java` — independent AeronCluster egress client (own MediaDriver, auto egress port).
  Records every TradeExecution (id 26, full per-trade truth incl. `tradeId`) and OrderStatus (id 24)
  to JSONL, plus leader/reconnect markers. Build: `javac -cp <match-loadtest.jar> -d out Bug9Observer.java`.
- `oms_load.py` — seed balances + drive crossing whole-$ LIMIT orders through OMS HTTP (the match
  loadtest bypasses OMS, so it can't move balances). Modes: `seed|run|snapshot|cancel`.
- `switchover.sh` — force ONE leader switchover (`restart-node` on the current leader) and wait until
  the cluster re-elects + the node rejoins.
- `reconcile.py` — ground-truth = observer trades; compares per-user balance deltas and per-order
  filledQty to OMS REST; checks tradeId contiguity; classifies MISSED / DUPLICATE / MISCOUNT.
- `run.sh` — orchestrates: preflight → observer → seed → load → N switchovers → quiesce → cancel →
  reconcile, into `runs/<timestamp>/`.

## Run
```bash
./run.sh [num_switchovers=4] [users=30] [rate=200] [run_seconds=130]
# control (no bug expected): ./run.sh 0 10 150 25
```
Output: `runs/<id>/reconciliation-report.json` + console summary.

## Safety / blast radius
Each `restart-node` triggers a few-seconds election; the live UI/OMS ingress stutter briefly and
self-heal. NEVER uses stop-all / cleanup / compact / rolling-update. Auto-snapshot stays ON.

## Notes verified at build time
- FixedPoint scale = 1e8; OMS API takes/returns doubles. Settlement: base=qty, quote=price*qty.
- Postgres/Redis are NOT running here → OMS uses the in-memory balance store; balances are queried
  via `GET /api/v1/accounts/{u}` (no executions table to query).
- assetId: USD=0, BTC=1. BTC-USD = marketId 1, $1 tick.
