# Durability scenarios (multi-node, manual / nightly)

Deterministic, assertion-based durability tests for the match engine. These complement the fast,
in-process JUnit determinism + snapshot suite (`match-cluster` `com.match.determinism.*` and
`SnapshotCodecTest`) by exercising **real multi-node recovery** against a live cluster.

These are **not** part of the per-push CI unit gate — they require a running 3-node cluster + OMS
and disrupt nodes. Run them manually or on a nightly job.

## What it asserts (timing-independent)

After each disruption, no matter how recovery interleaves with the load:

| Invariant        | Why it catches durability bugs |
|------------------|--------------------------------|
| **value conservation** | Σ total USD and Σ total BTC across the test users are unchanged — trades only move value between test users (zero-sum). A lost or duplicated fill across a switchover breaks the sum. |
| **no stuck holds**     | every asset's `locked` is 0 after all orders are cancelled and egress quiesces. A dropped terminal status strands `locked > 0` (the oms#21 failure mode). |
| **recovery health**    | exactly one leader, all 3 nodes running, gateways healthy — recovery actually completed. |
| **ingress live**       | a fresh order is accepted *after* the disruption — a wedged leader can't serve ingress (the match#25 egress-wedge regression). |

## Scenarios

| Scenario   | Disruption | Recovery path exercised |
|------------|------------|--------------------------|
| `smoke`    | none | validates the harness + assertions on a healthy cluster |
| `restart`  | `restart-node` ×3 (graceful) | snapshot + log replay per node |
| `rolling`  | `rolling-update` | no-downtime rolling recovery |
| `crash`    | SIGKILL all node processes, then start | crash recovery from snapshot + log |
| `failover` | kill the current leader mid-load | match#25 egress / bug#9 fill exactness |
| `fillexact`| delegates to `../repro-bug9` (observer + trade-tape reconcile) | exact per-fill accounting across switchovers |
| `all`      | runs smoke → restart → rolling → crash → failover | end-to-end |

## Run

```bash
# From the match/ module root (cluster + OMS must be up and healthy):
make durability                 # runs the full sequence (tools/durability/durability.py all)

# Or a single scenario:
python3 tools/durability/durability.py smoke
python3 tools/durability/durability.py failover --base 800000 --n 12
python3 tools/durability/durability.py fillexact
```

Exit code `0` = every asserted invariant held; non-zero = a durability violation (details printed).

## Notes / assumptions

- Conservation is checked **within the test user set** at an isolated price band (`MID=120000`,
  `±5`), so test users trade only with each other. Run on an otherwise-idle cluster, or change the
  band, if external orders could rest in that range.
- Endpoints come from `ADMIN` (default `http://localhost:8082`) and `OMS` (`http://localhost:8080`),
  overridable via env vars.
- The order load is fully deterministic (fixed count, fixed user/side/price pattern) so reruns are
  comparable; the assertions themselves are conservation-based and independent of load timing.
- `fillexact` reuses the proven `repro-bug9` observer + `reconcile.py`, which builds ground truth
  from the cluster's TradeExecution tape and classifies any MISSED_FILL / DUPLICATE_FILL.
