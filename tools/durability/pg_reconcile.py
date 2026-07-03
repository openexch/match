#!/usr/bin/env python3
"""
Per-user balance reconciliation against the OMS Postgres ledger (oms#22 / P1.5).

Ground truth per test user = seeded deposits + side-aware deltas from the
`executions` table (every fill the OMS settled, BIGINT fixed-point scale 1e8),
compared against (a) `account_balances` rows and (b) the live OMS REST view.

The oms#22 question is whether the ~0.3%-of-volume residual seen by
tools/repro-bug9 is a measurement artifact or real drift. Isolation is by
double sampling:

  T1  immediately post-quiesce (orders may still be terminalizing)
  T2  after --settle seconds with every test order terminal and holds released

Residual at T1 that vanishes at T2  -> snapshot-timing artifact (measurement).
Residual persisting at T2           -> real drift, reported per user with the
                                       contributing execution rows for drill-down.

`residualPctT2` is the gate number.

Shells out to psql (stdlib-only discipline); connection via PGURL env,
default postgresql://oms@localhost:5432/oms with the password resolved from
~/.pgpass (dev credentials live only in gitignored places).
NOTE: requires OMS to actually be persisting (its startup log must NOT say
"PostgreSQL not available"); refuse loudly otherwise, since an empty
executions table reconciles to a false CLEAN.
"""
import argparse
import json
import os
import subprocess
import sys
import time

SCALE = 1e8
USD_ASSET, BTC_ASSET = 0, 1
# No password in the default URL: psql resolves it from ~/.pgpass (dev creds
# live only in gitignored places). Override the whole URL via PGURL if needed.
PGURL = os.environ.get("PGURL", "postgresql://oms@localhost:5432/oms")


def psql(query):
    """Run a query, return rows as lists of strings (psql -At, comma-separated)."""
    r = subprocess.run(["psql", "-At", "-F", ",", "-c", query, PGURL],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"ABORT: psql failed: {r.stderr.strip()[:300]}\n"
                 f"  (set PGURL; OMS must be running WITH persistence for this check)")
    return [line.split(",") for line in r.stdout.splitlines() if line]


def executions_deltas(base, n):
    """Side-aware per-user deltas from the executions ledger."""
    rows = psql(
        "SELECT user_id, side, "
        "SUM(quantity)::text, SUM(price::numeric * quantity / %d)::text "
        "FROM executions WHERE user_id >= %d AND user_id < %d "
        "GROUP BY user_id, side" % (int(SCALE), base, base + n))
    deltas = {}
    for user_id, side, qty_s, quote_s in rows:
        u = int(user_id)
        d = deltas.setdefault(u, {"USD": 0.0, "BTC": 0.0})
        base_qty = float(qty_s) / SCALE
        quote = float(quote_s) / SCALE
        if side.strip().upper() == "BUY":
            d["BTC"] += base_qty
            d["USD"] -= quote
        else:
            d["BTC"] -= base_qty
            d["USD"] += quote
    return deltas


def db_balances(base, n):
    rows = psql(
        "SELECT user_id, asset_id, available::text, locked::text "
        "FROM account_balances WHERE user_id >= %d AND user_id < %d" % (base, base + n))
    out = {}
    for user_id, asset_id, avail, locked in rows:
        u, a = int(user_id), int(asset_id)
        key = "USD" if a == USD_ASSET else "BTC" if a == BTC_ASSET else None
        if key:
            out.setdefault(u, {})[key] = (float(avail) + float(locked)) / SCALE
    return out


def execution_rows_for(user, limit=20):
    rows = psql(
        "SELECT trade_id, oms_order_id, side, price::text, quantity::text, is_maker, executed_at "
        "FROM executions WHERE user_id = %d ORDER BY executed_at DESC LIMIT %d" % (user, limit))
    return [{"tradeId": r[0], "omsOrderId": r[1], "side": r[2],
             "price": float(r[3]) / SCALE, "quantity": float(r[4]) / SCALE,
             "isMaker": r[5], "at": r[6]} for r in rows]


def sample(initial, base, n, usd_tol, btc_tol):
    """One reconciliation pass; returns (residuals, volumeUSD)."""
    deltas = executions_deltas(base, n)
    balances = db_balances(base, n)
    volume = sum(abs(d["USD"]) for d in deltas.values()) / 2 or 1.0
    residuals = []
    for u in range(base, base + n):
        init = initial.get(str(u), {"USD": 0.0, "BTC": 0.0})
        exp_usd = init["USD"] + deltas.get(u, {}).get("USD", 0.0)
        exp_btc = init["BTC"] + deltas.get(u, {}).get("BTC", 0.0)
        act = balances.get(u)
        if act is None:
            continue
        d_usd = act.get("USD", 0.0) - exp_usd
        d_btc = act.get("BTC", 0.0) - exp_btc
        if abs(d_usd) > usd_tol or abs(d_btc) > btc_tol:
            residuals.append({"userId": u, "dUSD": round(d_usd, 6), "dBTC": round(d_btc, 8)})
    return residuals, volume


def main():
    ap = argparse.ArgumentParser(description="Balance reconciliation vs the OMS Postgres ledger")
    ap.add_argument("--dir", required=True, help="storm run dir (uses balances-initial.json)")
    ap.add_argument("--base", type=int, required=True)
    ap.add_argument("--n", type=int, required=True)
    ap.add_argument("--settle", type=int, default=30, help="seconds between T1 and T2")
    ap.add_argument("--usd-tol", type=float, default=0.5)
    ap.add_argument("--btc-tol", type=float, default=1e-5)
    args = ap.parse_args()

    # Refuse to reconcile against a ledger the OMS is not writing.
    count = int(psql("SELECT count(*) FROM executions")[0][0])
    if count == 0:
        sys.exit("ABORT: executions table is empty — OMS is not persisting "
                 "(check its startup log for 'PostgreSQL not available'). "
                 "An empty ledger would reconcile to a false CLEAN.")

    initial = json.load(open(os.path.join(args.dir, "balances-initial.json")))

    t1_res, volume = sample(initial, args.base, args.n, args.usd_tol, args.btc_tol)
    t1_pct = round(100.0 * sum(abs(r["dUSD"]) for r in t1_res) / volume, 4)
    print(f"[pg] T1: {len(t1_res)} users with residual ({t1_pct}% of volume); settling {args.settle}s...")
    time.sleep(args.settle)
    t2_res, volume = sample(initial, args.base, args.n, args.usd_tol, args.btc_tol)
    t2_pct = round(100.0 * sum(abs(r["dUSD"]) for r in t2_res) / volume, 4)

    if t2_res:
        classification = "real-drift"
        for r in t2_res[:5]:
            r["recentExecutions"] = execution_rows_for(r["userId"])
    elif t1_res:
        classification = "snapshot-timing-artifact"
    else:
        classification = "clean"

    report = {
        "executionsRows": count,
        "volumeUSD": round(volume, 2),
        "residualPctT1": t1_pct,
        "residualPctT2": t2_pct,
        "residualUsersT1": len(t1_res),
        "residualUsersT2": len(t2_res),
        "classification": classification,
        "driftUsers": t2_res[:20],
    }
    out = os.path.join(args.dir, "pg-reconcile-report.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)
    print(f"[pg] classification={classification} T1={t1_pct}% T2={t2_pct}% -> {out}")
    sys.exit(0 if classification != "real-drift" else 1)


if __name__ == "__main__":
    main()
