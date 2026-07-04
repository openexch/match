#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Deterministic multi-node DURABILITY scenarios for the Open Exchange match engine.

Drives the LIVE cluster via the Admin Gateway (:8082) and OMS (:8080): seeds known balances,
applies a fixed, deterministic crossing-order load, disrupts the cluster, then asserts
TIMING-INDEPENDENT invariants that must hold no matter how recovery interleaves with the load:

  * value conservation  — Σ total USD and Σ total BTC across the test users are unchanged
                          (trades only move value between the test users; they are zero-sum)
  * no stuck holds      — every asset's `locked` is 0 after all orders are cancelled and egress
                          quiesces (a lost terminal / duplicate hold would strand `locked` > 0)
  * recovery health     — exactly one leader, all 3 nodes running, gateways healthy
  * ingress live        — a fresh order is accepted AFTER the disruption
                          (the match#25 egress-wedge regression: a wedged leader can't serve ingress)

Scenarios:
  smoke     no disruption — validates the harness + assertions against a healthy cluster
  restart   graceful per-node restart (restart-node x3)                  — snapshot+replay recovery
  rolling   rolling update of all nodes                                  — no-downtime recovery
  crash     SIGKILL all node processes, then start them                  — crash recovery from snapshot+log
  failover  kill the current leader mid-load                            — match#25 egress / bug#9 fill exactness
  fillexact delegate to tools/repro-bug9 (observer + trade-tape reconcile) — exact per-fill accounting
  all       run smoke, restart, rolling, crash, failover in sequence

Requires a live 3-node cluster + OMS. Destructive scenarios disrupt nodes (they self-heal in seconds).
Stdlib only. Exit 0 = every asserted invariant held; nonzero = a durability violation (details printed).
"""
import argparse
import json
import os
import subprocess
import sys
import time
import http.client
from urllib.parse import urlparse

# ---- config defaults ----
ADMIN = os.environ.get("ADMIN", "http://localhost:8082")
OMS = os.environ.get("OMS", "http://localhost:8080")
MARKET = os.environ.get("MARKET", "http://localhost:8081")
MARKET_ID = 1                 # BTC-USD, $1 tick
USD_ASSET, BTC_ASSET = 0, 1
SEED_USD = 5_000_000.0
SEED_BTC = 100.0
MID = 100_000                 # set at runtime by discover_mid() — collar-safe, below the lowest ask
QTY = 0.05
N_ORDERS = 120                # deterministic order count
USD_TOL = 0.5
BTC_TOL = 1e-5

# ---- HTTP (stdlib, fresh connection per request; OMS sends Connection: close) ----

def _req(base, method, path, body=None, attempts=4, timeout=10):
    u = urlparse(base)
    last = None
    for k in range(attempts):
        c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=timeout)
        try:
            headers = {"Content-Type": "application/json"} if body is not None else {}
            c.request(method, path, json.dumps(body) if body is not None else None, headers)
            r = c.getresponse()
            data = r.read()
            return r.status, data
        except Exception as e:  # noqa: BLE001 - transient under load; retry
            last = e
            time.sleep(0.3 * (k + 1))
        finally:
            c.close()
    raise last


def admin_get(path):
    st, data = _req(ADMIN, "GET", path)
    return st, data


def admin_post(path, body=None):
    st, data = _req(ADMIN, "POST", path, body if body is not None else {})
    return st, data


def oms_get(path):
    return _req(OMS, "GET", path)


def oms_post(path, body):
    return _req(OMS, "POST", path, body)


def oms_delete(path):
    return _req(OMS, "DELETE", path)


def discover_mid():
    """Pick a collar-safe price JUST BELOW the lowest resting ask, so our own buys and sells cross
    each other while our buys never reach external asks (which keeps trades inside the test user
    set, so value conservation is well-defined). Falls back to 100000 if the book is unreadable."""
    try:
        st, data = _req(MARKET, "GET", f"/api/orderbook/{MARKET_ID}")
        asks = [a["price"] for a in json.loads(data).get("asks", [])]
        if asks:
            return int(min(asks)) - 10
    except Exception:  # noqa: BLE001
        pass
    return 100_000


# ---- cluster control / health ----

def status():
    st, data = admin_get("/api/admin/status")
    if st != 200:
        raise RuntimeError(f"admin status HTTP {st}")
    return json.loads(data)


def is_healthy(s):
    nodes = s.get("nodes", [])
    running = sum(1 for n in nodes if n.get("running"))
    gw = s.get("gateways", {})
    return (running == 3
            and s.get("leader") is not None
            and gw.get("oms", {}).get("healthy")
            and gw.get("market", {}).get("healthy"))


def wait_healthy(timeout=120):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            s = status()
            last = s
            if is_healthy(s):
                return s
        except Exception as e:  # noqa: BLE001
            last = {"error": str(e)}
        time.sleep(2)
    raise RuntimeError(f"cluster did not return to health within {timeout}s; last={json.dumps(last)[:300]}")


def leader_id(s):
    return s.get("leader")


# ---- OMS load (deterministic) ----

def seed(base, n):
    for u in range(base, base + n):
        oms_post(f"/api/v1/accounts/{u}/deposit", {"assetId": USD_ASSET, "amount": SEED_USD})
        oms_post(f"/api/v1/accounts/{u}/deposit", {"assetId": BTC_ASSET, "amount": SEED_BTC})
    print(f"[seed] {n} users [{base},{base+n}) USD={SEED_USD} BTC={SEED_BTC}")


def submit_load(base, n, count=N_ORDERS):
    """Deterministic self-crossing LIMIT orders at MID, submitted synchronously — identical every
    run. Alternating BUY/SELL at the same price pair up among our own users (buy rests, next sell
    matches it), producing zero-sum trades inside the test set."""
    accepted = 0
    for i in range(count):
        u = base + (i % n)
        side = "BUY" if (i % 2 == 0) else "SELL"
        body = {"userId": u, "marketId": MARKET_ID, "side": side, "orderType": "LIMIT",
                "timeInForce": "GTC", "price": MID, "quantity": QTY}
        try:
            st, data = oms_post("/api/v1/orders", body)
            if st in (200, 201) and json.loads(data).get("accepted"):
                accepted += 1
        except Exception:  # noqa: BLE001
            pass
    print(f"[load] submitted {count} self-crossing orders at price {MID}, accepted={accepted}")
    return accepted


def fresh_order_accepted(base):
    """Submit ONE order after a disruption to prove ingress is live (match#25)."""
    body = {"userId": base, "marketId": MARKET_ID, "side": "BUY", "orderType": "LIMIT",
            "timeInForce": "GTC", "price": MID, "quantity": QTY}
    try:
        st, data = oms_post("/api/v1/orders", body)
        return st in (200, 201) and json.loads(data).get("accepted", False)
    except Exception:  # noqa: BLE001
        return False


def cancel_all(base, n):
    cancelled = 0
    for u in range(base, base + n):
        st, data = oms_get(f"/api/v1/orders?userId={u}")
        if st != 200:
            continue
        try:
            orders = json.loads(data)
        except Exception:  # noqa: BLE001
            continue
        for o in orders:
            if o.get("status") in ("FILLED", "CANCELLED", "REJECTED", "EXPIRED"):
                continue
            oid = o.get("omsOrderId")
            if oid is not None:
                oms_delete(f"/api/v1/orders/{oid}")
                cancelled += 1
    print(f"[cancel] cancelled {cancelled} open orders")


def balances(base, n):
    out = {}
    for u in range(base, base + n):
        st, data = oms_get(f"/api/v1/accounts/{u}")
        if st != 200:
            continue
        assets = {a["assetId"]: a for a in json.loads(data).get("assets", [])}
        out[u] = assets
    return out


def totals(bal):
    su = sum(a.get(USD_ASSET, {}).get("total", 0.0) for a in bal.values())
    sb = sum(a.get(BTC_ASSET, {}).get("total", 0.0) for a in bal.values())
    return su, sb


def locked_sums(bal):
    lu = sum(a.get(USD_ASSET, {}).get("locked", 0.0) for a in bal.values())
    lb = sum(a.get(BTC_ASSET, {}).get("locked", 0.0) for a in bal.values())
    return lu, lb


# ---- assertions ----

class Failures:
    def __init__(self):
        self.items = []

    def check(self, cond, msg):
        mark = "PASS" if cond else "FAIL"
        print(f"   [{mark}] {msg}")
        if not cond:
            self.items.append(msg)


def assert_recovery(f, label):
    s = wait_healthy()
    f.check(is_healthy(s), f"{label}: cluster healthy (1 leader, 3 running, gateways up)")
    f.check(fresh_order_accepted_retry(), f"{label}: ingress accepts a fresh order after disruption (match#25)")
    return s


def fresh_order_accepted_retry(attempts=10):
    # OMS may need a moment to reconnect egress after a switchover.
    base = CURRENT_BASE[0]
    for _ in range(attempts):
        if fresh_order_accepted(base):
            return True
        time.sleep(1.5)
    return False


CURRENT_BASE = [800000]


def run_scenario(name, base, n, disrupt, quiesce=12):
    print(f"\n=== scenario: {name} (users [{base},{base+n})) ===")
    CURRENT_BASE[0] = base
    f = Failures()

    pre = wait_healthy()
    f.check(is_healthy(pre), f"{name}: cluster healthy before start")

    seed(base, n)
    init = balances(base, n)
    init_usd, init_btc = totals(init)
    init_lu, init_lb = locked_sums(init)
    print(f"   initial totals: USD={init_usd:.4f} BTC={init_btc:.6f}  locked: USD={init_lu:.4f} BTC={init_lb:.6f}")

    submit_load(base, n)

    if disrupt is not None:
        print(f"   --- applying disruption ---")
        disrupt(pre)
        assert_recovery(f, name)

    cancel_all(base, n)
    print(f"   quiesce {quiesce}s (let egress drain + OMS reconnect)...")
    time.sleep(quiesce)

    fin = balances(base, n)
    fin_usd, fin_btc = totals(fin)
    lu, lb = locked_sums(fin)
    print(f"   final totals:   USD={fin_usd:.4f} BTC={fin_btc:.6f}  locked: USD={lu:.4f} BTC={lb:.6f}")

    f.check(abs(fin_usd - init_usd) <= USD_TOL,
            f"{name}: USD conserved (Δ={fin_usd - init_usd:.4f}, tol={USD_TOL})")
    f.check(abs(fin_btc - init_btc) <= BTC_TOL,
            f"{name}: BTC conserved (Δ={fin_btc - init_btc:.8f}, tol={BTC_TOL})")
    # Delta form: our load + cancellation must strand no NEW holds (robust to pre-existing state on
    # shared dev users). On a clean environment init locked is 0 and this is the absolute Σlocked==0.
    f.check(lu <= init_lu + USD_TOL and lb <= init_lb + BTC_TOL,
            f"{name}: no NEW stuck holds (locked Δ USD={lu - init_lu:.4f} BTC={lb - init_lb:.6f})")

    return f.items


# ---- disruptions ----

def disrupt_restart(pre):
    for node_id in (0, 1, 2):
        print(f"   restart-node {node_id}")
        admin_post("/api/admin/restart-node", {"nodeId": node_id})
        wait_healthy()


def disrupt_rolling(pre):
    print("   rolling-update")
    admin_post("/api/admin/rolling-update")


def disrupt_crash(pre):
    # SIGKILL every node process, then bring them back — recovery is from snapshot + log replay.
    for name in ("node0", "node1", "node2"):
        print(f"   force-stop {name} (SIGKILL)")
        admin_post(f"/api/admin/processes/{name}/force-stop")
    time.sleep(3)
    print("   start-all-nodes")
    admin_post("/api/admin/start-all-nodes")


def disrupt_failover(pre):
    lid = leader_id(pre)
    print(f"   killing current leader node {lid} mid-load")
    admin_post("/api/admin/restart-node", {"nodeId": lid})


# ---- fill-exactness via the existing observer-based reconciler ----

def scenario_fillexact():
    here = os.path.dirname(os.path.abspath(__file__))
    run_sh = os.path.join(here, "..", "repro-bug9", "run.sh")
    if not os.path.exists(run_sh):
        print(f"[fillexact] {run_sh} not found — skipping")
        return ["fillexact: repro-bug9/run.sh missing"]
    print("[fillexact] delegating to repro-bug9 (observer + trade-tape reconciliation, 2 switchovers)")
    rc = subprocess.call(["bash", run_sh, "2", "20", "150", "90"])
    # The reconciler prints CLEAN / discrepancy counts; non-zero rc or discrepancies => failure.
    return [] if rc == 0 else [f"fillexact: repro-bug9 run.sh exited {rc}"]


# ---- main ----

SCENARIOS_WITH_DISRUPTION = {
    "smoke": None,
    "restart": disrupt_restart,
    "rolling": disrupt_rolling,
    "crash": disrupt_crash,
    "failover": disrupt_failover,
}


def main():
    ap = argparse.ArgumentParser(description="Deterministic durability scenarios")
    ap.add_argument("scenario", choices=list(SCENARIOS_WITH_DISRUPTION) + ["fillexact", "all"])
    ap.add_argument("--base", type=int, default=800000, help="first test userId")
    ap.add_argument("--n", type=int, default=12, help="number of test users")
    args = ap.parse_args()

    global MID
    MID = discover_mid()
    print(f"[init] using collar-safe MID={MID} (just below the lowest resting ask)")

    failures = []
    if args.scenario == "fillexact":
        failures += scenario_fillexact()
    elif args.scenario == "all":
        for i, key in enumerate(("smoke", "restart", "rolling", "crash", "failover")):
            failures += run_scenario(key, args.base + i * 1000, args.n, SCENARIOS_WITH_DISRUPTION[key])
    else:
        failures += run_scenario(args.scenario, args.base, args.n,
                                 SCENARIOS_WITH_DISRUPTION[args.scenario])

    print("\n" + "=" * 64)
    if failures:
        print(f"DURABILITY: {len(failures)} VIOLATION(S)")
        for v in failures:
            print(f"  - {v}")
        sys.exit(1)
    print("DURABILITY: all invariants held")
    sys.exit(0)


if __name__ == "__main__":
    main()
