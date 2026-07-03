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

## P1 gate harness (match#32)

`storm.py` is the P1 acceptance instrument: sustained OMS-REST load + N forced leader
switchovers (default 50), reconciled against the independent `repro-bug9` observer, plus an
engine-counter harvest. One command, one report (`runs/<ts>/p1-gate-report.json`):

```bash
make p1-gate          # 50 switchovers + Postgres balance reconcile + stranded-follower gate
make p1-gate-smoke    # same harness, 3 switchovers (~5 min)
```

Pieces (each also runnable standalone):

- `storm.py` — orchestrator. Detaches the market gateway during the run (match#37: it OOMs
  under load until P3.3) and restores it after. REQUIRES the truthful status API
  (admin-gateway#13 item 1); refuses to run without per-node `health` fields, and additionally
  cross-checks the status API's leader claim against the observer's `newLeader` events.
  Exit 0 = harness completed; divergence itself is data (see `gates` in the report;
  `--enforce-gates` makes gate breaches fatal once P1.1/P1.2 land).
- `diag_counters.py` — harvests `submitted` / `terminal` / `overflowRej` / `droppedMkt` /
  `droppedOms` from the EGRESS-DIAG lines in `~/.local/log/cluster/node*.log`. Counters are
  in-process values that RESET on every node restart: a decreasing value marks a new
  incarnation and the run total is the sum of last-values per incarnation. Log rotation
  (rename + fresh file on every PM start) is stitched by rotation timestamp.
- `stranded.py` — the stranded-follower regression gate (match#35 / P1.3-iv): stop a follower,
  advance the log, snapshot + housekeeping (the stranding mechanism), restart it. PASS =
  rejoins cleanly OR fails loudly; FAIL = the silent hot-loop from the 2026-07-02 incident.
- `pg_reconcile.py` — per-user balances vs the OMS Postgres `executions` ledger (oms#22),
  sampled at T1 (post-quiesce) and T2 (fully settled) to split snapshot-timing artifacts from
  real drift. Needs OMS running WITH persistence; refuses on an empty executions table.

Wall-clock budget: ~15-30 s per switchover cycle, so `make p1-gate` is a 25-45 minute run.
Until P3.3 lands, keep UI/browser tabs off the market gateway during runs (it is stopped by
the harness anyway).

### Run sizing until P1.2 lands (OMS open-order slot leak)

The OMS caps open orders at 500/user in an in-memory counter that only decrements when a
terminal status arrives. bug9-lost terminals (P1.2) leak those slots for the OMS process
lifetime, so consecutive runs suffocate (observed 2026-07-03: 15,001 then 2,356 then 617
accepted before OPEN_ORDER_LIMIT walls). Until match#31/oms#34 land:

- RESTART THE OMS before a full gate run (clears the leaked counters), and
- size the run so `users x 500 > orders x ~0.2` (the observed terminal-loss rate), e.g.
  100 users at 100/s for the 50-switchover run.

The report's `acceptanceRate` / `loadCollapsed` fields make a walled run self-evident.
