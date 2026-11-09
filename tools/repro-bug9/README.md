# repro-bug9: fill exactness across leader switchovers

Observation-only harness. It drives load through the order service of the v0.5 stack, forces leader switchovers, and reconciles the order service's state against an independent cluster egress observer. It changes no production code.

Used directly and by `tools/durability/durability.py fillexact` and `tools/durability/storm.py`.

## Pieces

- `Bug9Observer.java`: independent cluster egress client. Records every `TradeExecutionBatch` (template 26) and `OrderStatusBatch` (template 24) to JSONL, plus leader change markers. Build: `javac -cp <match-loadtest.jar> -d out Bug9Observer.java`.
- `oms_load.py`: seeds balances and drives crossing limit orders through the order service REST API (`seed|run|snapshot|cancel`).
- `switchover.sh`: forces one leader switchover through the admin gateway and waits for re-election.
- `reconcile.py`: treats observer trades as ground truth, compares per-user balances and per-order filled quantity, and classifies MISSED, DUPLICATE or MISCOUNT fills.
- `run.sh`: preflight, observer, seed, load, N switchovers, quiesce, cancel, reconcile, into `runs/<timestamp>/`.

## Run

```bash
./run.sh [num_switchovers=4] [users=30] [rate=200] [run_seconds=130]
```

Endpoints: `ADMIN` (default `http://localhost:8082`), `OMS` (default `http://localhost:8080`), `JAR` (default `../../match-loadtest/target/match-loadtest.jar`).

Output: `runs/<id>/reconciliation-report.json` and a console summary. Each switchover causes a short election; run it on a dev cluster only.
