# Durability scenarios (multi-node, manual)

Assertion-based durability tests that exercise real multi-node recovery against a live cluster. They complement the in-process determinism and snapshot suite (`make determinism`).

These are not part of CI: they need a running 3-node cluster plus the v0.5 order service and admin gateway, and they disrupt nodes. Run them on a dev cluster only.

## What they assert

| Invariant | Catches |
|---|---|
| Value conservation | Sum of each asset across the test users is unchanged; a lost or duplicated fill breaks it |
| No stuck holds | Every `locked` balance is 0 after all orders are cancelled |
| Recovery health | One leader, three nodes running, gateways healthy |
| Ingress live | A fresh order is accepted after the disruption |

## Scenarios (`durability.py`)

| Scenario | Disruption |
|---|---|
| `smoke` | None (validates the harness) |
| `restart` | Graceful restart of each node |
| `rolling` | Rolling update |
| `crash` | SIGKILL all nodes, then start |
| `failover` | Kill the leader mid-load |
| `fillexact` | Delegates to `../repro-bug9` |
| `all` | smoke, restart, rolling, crash, failover |

```bash
make durability                                  # durability.py all
python3 tools/durability/durability.py failover --base 800000 --n 12
```

Endpoints come from `ADMIN` (default `http://localhost:8082`), `OMS` (`http://localhost:8080`) and `MARKET` (`http://localhost:8081`). Test users trade in an isolated price band, so run on an otherwise idle cluster. Exit code 0 means every invariant held.

## Switchover storm (`storm.py`)

Sustained load plus N forced leader switchovers (default 50), reconciled against the `repro-bug9` observer, with engine counter harvest. Writes `runs/<ts>/p1-gate-report.json`.

```bash
make p1-gate          # 50 switchovers + Postgres balance reconcile + stranded-follower check
make p1-gate-smoke    # 3 switchovers
```

Helpers, each runnable on its own:

- `diag_counters.py`: harvests engine counters from `EGRESS-DIAG` log lines, stitching restarts and log rotation.
- `stranded.py`: stops a follower, advances and prunes the log, restarts it. Passes if it rejoins or fails loudly; fails on a silent hot loop.
- `pg_reconcile.py`: per-user balances against the order service Postgres `executions` table.
- `backup_restore_drill.sh`: power-loss restore drill.
