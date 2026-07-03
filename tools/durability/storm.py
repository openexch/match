#!/usr/bin/env python3
"""
P1 gate storm (match#32 / P1.5): sustained OMS-REST load + >=50 forced leader
switchovers, reconciled against an independent egress observer, producing the
single report used to accept P1.1-P1.4 (p1-gate-report.json).

Composes the existing pieces rather than reinventing them:
  * tools/repro-bug9/Bug9Observer  — independent egress tape (trades + statuses)
  * tools/repro-bug9/oms_load.py   — seed / load / snapshot / cancel via OMS REST
  * tools/repro-bug9/reconcile.py  — divergence classification (extended for
                                     terminal-vs-OPEN, match#32)
  * tools/durability/diag_counters — engine counter harvest from node logs

Operational rules honored (docs/incidents/2026-07-02, match#35/#37):
  * The market gateway is DETACHED during the storm (it OOMs under load until
    P3.3 lands) and restored afterwards.
  * requires the truthful status API (admin-gateway#13 item 1): pre-flight
    refuses to run without the per-node `health` fields, because the storm
    loop trusts /api/admin/status between kills.
  * status is additionally cross-checked against the observer's newLeader
    events — the harness must not solely trust the API it is stressing.

Exit code: 0 = the HARNESS completed (all switchovers done, load flowed,
reconciliation produced numbers). Divergence itself is reported as data;
pass/fail per workstream gate lives in the report's `gates` block and is only
enforced with --enforce-gates (used once P1.1/P1.2 land).

Stdlib only. Requires a live cluster + OMS; run on the cluster box.
"""
import argparse
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BUG9 = os.path.abspath(os.path.join(HERE, "..", "repro-bug9"))
sys.path.insert(0, HERE)

import diag_counters  # noqa: E402
import durability as dur  # noqa: E402  (admin/OMS helpers + health predicates)

TERMINAL_STATUSES = ("FILLED", "CANCELLED", "REJECTED", "EXPIRED")


# ---- health predicates (core = market gateway EXCLUDED: it is detached) ----

def is_healthy_core(s):
    nodes = s.get("nodes", [])
    running = sum(1 for n in nodes if n.get("running"))
    leader = s.get("leader")
    return (running == 3
            and leader is not None and leader >= 0
            and s.get("allNodesHealthy") is True
            and s.get("gateways", {}).get("oms", {}).get("healthy"))


def wait_healthy_core(timeout=90):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            st, data = dur.admin_get("/api/admin/status")
            s = json.loads(data)
            last = s
            if is_healthy_core(s):
                return s
        except Exception as e:  # noqa: BLE001
            last = {"error": str(e)}
        time.sleep(2)
    raise TimeoutError(f"cluster not healthy-core within {timeout}s; last={json.dumps(last)[:400]}")


def wait_switchover_settled(killed, mid, timeout=90):
    """Post-kill settle that cannot be fooled by the status CACHE (2s poller):
    the 20260703-224136 run showed a restart-node followed by an immediate
    status read returns the PRE-KILL world — 0.0s 'heals', the old leader
    recorded, and kills outpacing the OMS's reconnect until it rejected the
    whole run. Sequence enforced here:

      1. the restart is OBSERVED: the killed node leaves HEALTHY at least
         once (or the leader moves off it);
      2. TWO consecutive healthy-core polls (a single one can be the stale
         cache flanking the transition);
      3. the OMS actually accepts a canary order (ingress live end-to-end,
         which is what the next storm cycle really needs).
    """
    deadline = time.time() + timeout
    restart_seen = False
    healthy_streak = 0
    last = None
    while time.time() < deadline:
        try:
            st, data = dur.admin_get("/api/admin/status")
            s = json.loads(data)
            last = s
            killed_node = next((n for n in s.get("nodes", []) if n.get("id") == killed), {})
            if not restart_seen:
                if killed_node.get("health") != "HEALTHY" or s.get("leader") != killed:
                    restart_seen = True
                    healthy_streak = 0
            if restart_seen:
                healthy_streak = healthy_streak + 1 if is_healthy_core(s) else 0
                if healthy_streak >= 2 and canary_accepted(mid):
                    return s
        except Exception as e:  # noqa: BLE001
            last = {"error": str(e)}
            healthy_streak = 0
        time.sleep(2)
    raise TimeoutError(f"switchover (killed node {killed}) not settled within {timeout}s; "
                       f"restartSeen={restart_seen} last={json.dumps(last)[:300]}")


CANARY_USER = 819999  # seeded once at storm start; outside the measured user set


def canary_accepted(mid):
    """Submit-and-cancel a resting LIMIT: proves OMS -> cluster ingress -> ack
    round-trips. Uses its own user so measured balances stay untouched.

    Price/qty are chosen to dodge every OMS risk check (learned in run
    20260703-233910, where a BUY at price=1 was collar-rejected for 90s and
    aborted the storm): SELL 5% above mid stays inside the 10% price collar
    and rests instead of crossing, and qty 0.05 keeps the notional clear of
    the FixedPoint negative band (~$922-$1844, match#30) that spuriously
    rejects with NOTIONAL_TOO_SMALL."""
    try:
        st, data = dur.oms_post("/api/v1/orders",
                                {"userId": CANARY_USER, "marketId": 1, "side": "SELL",
                                 "orderType": "LIMIT", "timeInForce": "GTC",
                                 "price": int(mid * 1.05), "quantity": 0.05})
        if st not in (200, 201):
            return False
        resp = json.loads(data)
        if not resp.get("accepted"):
            return False
        oid = resp.get("omsOrderId")
        if oid is not None:
            dur._req(dur.OMS, "DELETE", f"/api/v1/orders/{oid}")
        return True
    except Exception:  # noqa: BLE001
        return False


def require_truthful_status():
    st, data = dur.admin_get("/api/admin/status")
    s = json.loads(data)
    nodes = s.get("nodes", [])
    if "allNodesHealthy" not in s or any("health" not in n for n in nodes):
        sys.exit("ABORT: /api/admin/status lacks per-node health fields. "
                 "Deploy admin-gateway with the #13 status-truthfulness fix before "
                 "running destructive P1 scenarios.")
    return s


# ---- process control via admin gateway ----

def stop_market_gateway():
    dur.admin_post("/api/admin/processes/market/stop")


def start_market_gateway():
    dur.admin_post("/api/admin/processes/market/start")


def suspend_auto_snapshot():
    """Snapshot/housekeeping while a node is down or rejoining strands it
    (match#35), and the storm keeps nodes in exactly that state. Disable
    auto-snapshot for the run; return the prior config for restore."""
    st, data = dur.admin_get("/api/admin/auto-snapshot")
    try:
        prior = json.loads(data)
    except ValueError:
        prior = {}
    if prior.get("enabled"):
        dur._req(dur.ADMIN, "DELETE", "/api/admin/auto-snapshot")
        print(f"[storm] auto-snapshot suspended for the run "
              f"(was every {prior.get('intervalMinutes')}m; restored after)")
    return prior


def restore_auto_snapshot(prior):
    if prior.get("enabled"):
        dur.admin_post("/api/admin/auto-snapshot",
                       {"intervalMinutes": prior.get("intervalMinutes", 30)})
        print("[storm] auto-snapshot restored")


# ---- observer ----

def start_observer(run_dir, duration_s):
    jar = os.path.join(BUG9, "..", "..", "match-loadtest", "target", "match-loadtest.jar")
    cmd = ["java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
           "-cp", os.path.join(BUG9, "out") + ":" + jar,
           "Bug9Observer", run_dir, str(duration_s)]
    proc = subprocess.Popen(cmd, stdout=open(os.path.join(run_dir, "observer.stdout"), "w"),
                            stderr=subprocess.STDOUT)
    events = os.path.join(run_dir, "observer-events.jsonl")
    for _ in range(30):
        if os.path.exists(events) and '"kind":"connected"' in open(events).read():
            return proc
        time.sleep(1)
    print("[storm] WARNING: observer not confirmed connected (continuing)")
    return proc


def stop_observer(run_dir, proc):
    open(os.path.join(run_dir, "STOP"), "w").close()
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        proc.kill()


def observer_new_leaders(run_dir):
    """[(ts_ms, memberId)] from observer newLeader events."""
    out = []
    path = os.path.join(run_dir, "observer-events.jsonl")
    if not os.path.exists(path):
        return out
    for line in open(path):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("kind") == "newLeader":
            m = e.get("detail", "")
            member = None
            for tok in m.split():
                if tok.startswith("member="):
                    member = int(tok.split("=", 1)[1])
            if member is not None:
                out.append((e.get("ts", 0), member))
    return out


# ---- oms_load.py wrappers ----

def oms_load(mode, *extra):
    cmd = [sys.executable, os.path.join(BUG9, "oms_load.py"), mode, "--url", dur.OMS] + list(extra)
    return subprocess.run(cmd, check=True)


# ---- the storm ----

def run_storm(args):
    run_id = time.strftime("%Y%m%d-%H%M%S")
    run_dir = os.path.join(HERE, "runs", run_id)
    os.makedirs(run_dir, exist_ok=True)
    print(f"[storm] run dir: {run_dir}")

    # Pre-flight: full health INCLUDING market gateway (we stop it ourselves,
    # deliberately, next) + truthful status fields + collar-safe mid price.
    s = require_truthful_status()
    if not dur.is_healthy(s):
        s = dur.wait_healthy()
    mid = dur.discover_mid()
    print(f"[storm] preflight OK: leader={s.get('leader')} mid={mid}")

    # Budget: load must outlive warmup + all switchover cycles + quiesce.
    cycle_budget = args.cycle_budget
    load_duration = args.warmup + args.switchovers * cycle_budget + args.quiesce + 30
    print(f"[storm] plan: {args.switchovers} switchovers, cycle budget {cycle_budget}s, "
          f"load {args.rate}/s for {load_duration}s, users {args.users}")

    markers = {n: diag_counters.mark(n) for n in (0, 1, 2)}
    switchover_log = []
    result = {"completed": 0, "aborted": None}
    load_proc = None
    observer = None

    print("[storm] detaching market gateway (match#37: OOMs under load until P3.3)")
    stop_market_gateway()
    prior_autosnap = suspend_auto_snapshot()
    time.sleep(2)
    try:
        observer = start_observer(run_dir, load_duration + 60)

        oms_load("seed", "--base", str(args.base), "--n", str(args.users))
        oms_load("seed", "--base", str(CANARY_USER), "--n", "1")  # ingress-live probe user
        oms_load("snapshot", "--base", str(args.base), "--n", str(args.users),
                 "--out", os.path.join(run_dir, "balances-initial.json"))

        load_cmd = [sys.executable, os.path.join(BUG9, "oms_load.py"), "run",
                    "--url", dur.OMS, "--base", str(args.base), "--n", str(args.users),
                    "--rate", str(args.rate), "--duration", str(load_duration),
                    "--mid", str(mid),
                    "--log", os.path.join(run_dir, "submitted.jsonl")]
        load_proc = subprocess.Popen(load_cmd,
                                     stdout=open(os.path.join(run_dir, "load.stdout"), "w"),
                                     stderr=subprocess.STDOUT)
        print(f"[storm] warmup {args.warmup}s...")
        time.sleep(args.warmup)

        for i in range(1, args.switchovers + 1):
            st, data = dur.admin_get("/api/admin/status")
            leader = json.loads(data).get("leader")
            if leader is None or leader < 0:
                result["aborted"] = f"no leader before switchover {i}"
                break
            t0 = time.time()
            print(f"[storm] === switchover {i}/{args.switchovers}: restarting leader node {leader} ===")
            dur.admin_post("/api/admin/restart-node", {"nodeId": leader})
            try:
                s = wait_switchover_settled(leader, mid, timeout=args.switchover_timeout)
            except TimeoutError as e:
                result["aborted"] = f"switchover {i}: {e}"
                break
            elapsed = round(time.time() - t0, 1)
            switchover_log.append({"i": i, "killedLeader": leader,
                                   "newLeader": s.get("leader"),
                                   "tsMs": int(time.time() * 1000),
                                   "seconds": elapsed})
            result["completed"] = i
            print(f"[storm]     healthy again in {elapsed}s, leader={s.get('leader')}")
            # Keep some load flowing between kills so fills straddle elections.
            time.sleep(max(0.0, cycle_budget - elapsed))

        print("[storm] storm done; letting load finish...")
        if result["aborted"]:
            load_proc.terminate()
        load_proc.wait(timeout=load_duration + 60)

        print(f"[storm] quiesce {args.quiesce}s (egress drain + OMS reconnect)...")
        time.sleep(args.quiesce)
        oms_load("cancel", "--base", str(args.base), "--n", str(args.users))
        time.sleep(3)
        oms_load("snapshot", "--base", str(args.base), "--n", str(args.users),
                 "--out", os.path.join(run_dir, "balances-final.json"))
    finally:
        if observer:
            stop_observer(run_dir, observer)
        if load_proc and load_proc.poll() is None:
            load_proc.kill()
        print("[storm] restoring market gateway")
        start_market_gateway()
        restore_auto_snapshot(prior_autosnap)

    with open(os.path.join(run_dir, "switchovers.json"), "w") as f:
        json.dump(switchover_log, f, indent=2)

    # Reconcile (extended reconcile.py writes reconciliation-report.json).
    subprocess.run([sys.executable, os.path.join(BUG9, "reconcile.py"),
                    "--dir", run_dir, "--url", dur.OMS], check=False)

    if args.pg_reconcile:
        subprocess.run([sys.executable, os.path.join(HERE, "pg_reconcile.py"),
                        "--dir", run_dir, "--base", str(args.base),
                        "--n", str(args.users),
                        "--since", str(markers[0]["epoch"])], check=False)

    return run_dir, result, switchover_log, markers


# ---- report assembly ----

def leader_disagreements(run_dir, switchover_log):
    """Per completed switchover, the observer's freshest newLeader at that time
    must agree with what the status API claimed."""
    tape = observer_new_leaders(run_dir)
    disagreements = []
    for sw in switchover_log:
        candidates = [m for ts, m in tape if ts <= sw["tsMs"] + 2000]
        if not candidates:
            continue
        if candidates[-1] != sw["newLeader"]:
            disagreements.append({"switchover": sw["i"], "statusLeader": sw["newLeader"],
                                  "observerLeader": candidates[-1]})
    return disagreements


def assemble_report(run_dir, args, result, switchover_log, markers):
    recon = {}
    recon_path = os.path.join(run_dir, "reconciliation-report.json")
    if os.path.exists(recon_path):
        recon = json.load(open(recon_path))
    rs = recon.get("summary", {})

    counters = diag_counters.harvest_all(markers)
    totals = {k: sum(c.get(k, 0) for c in counters)
              for k in diag_counters.MONOTONIC_KEYS}

    balances = {}
    pg_path = os.path.join(run_dir, "pg-reconcile-report.json")
    if os.path.exists(pg_path):
        balances = json.load(open(pg_path))

    # Load-collapse guard (no silent caps): if the OMS rejected most of the
    # run's orders, divergence was measured over near-zero in-flight traffic
    # and the report must not read as a valid baseline.
    accepted = rejected = 0
    load_out = os.path.join(run_dir, "load.stdout")
    if os.path.exists(load_out):
        for line in open(load_out, errors="replace"):
            if line.startswith("[run] DONE"):
                for tok in line.split():
                    if tok.startswith("accepted="):
                        accepted = int(tok.split("=")[1])
                    elif tok.startswith("rejected="):
                        rejected = int(tok.split("=")[1])
    acceptance = accepted / (accepted + rejected) if (accepted + rejected) else 0.0
    load_collapsed = acceptance < 0.5

    submitted = rs.get("submittedOrders", 0)
    div_open_terminal = rs.get("omsOpenClusterTerminal", 0)
    div_terminal_open = rs.get("omsTerminalClusterOpen", 0)
    div_checked = rs.get("terminalityChecked", 0)
    disagreements = leader_disagreements(run_dir, switchover_log)

    def pct(n, d):
        return round(100.0 * n / d, 4) if d else 0.0

    report = {
        "run": {
            "dir": run_dir,
            "switchoversRequested": args.switchovers,
            "switchoversCompleted": result["completed"],
            "aborted": result["aborted"],
            "meanSwitchoverSec": round(sum(s["seconds"] for s in switchover_log)
                                       / len(switchover_log), 1) if switchover_log else None,
            "leaderDisagreements": len(disagreements),
            "leaderDisagreementDetails": disagreements[:10],
        },
        "orders": {
            "submitted": submitted,
            "rejectedByOms": rejected,
            "acceptanceRate": round(acceptance, 4),
            "observedTrades": rs.get("uniqueTrades", 0),
            "terminalityChecked": div_checked,
            "engineSubmitted": totals.get("submitted", 0),
            "engineTerminalized": totals.get("terminal", 0),
        },
        "divergence": {
            "omsOpenClusterTerminal": {"count": div_open_terminal,
                                       "pct": pct(div_open_terminal, div_checked),
                                       "examples": recon.get("terminalityDivergence", {})
                                                        .get("omsOpenClusterTerminalExamples", [])},
            "omsTerminalClusterOpen": {"count": div_terminal_open,
                                       "pct": pct(div_terminal_open, div_checked),
                                       "examples": recon.get("terminalityDivergence", {})
                                                        .get("omsTerminalClusterOpenExamples", [])},
            "balanceDiscrepancies": rs.get("balanceDiscrepancies", 0),
            "filledQtyMiscounts": rs.get("filledQtyMiscounts_omsVsTrue", 0),
            "engineStreamDivergence": rs.get("engineStreamDivergence_statusVsTrades", 0),
            "tradeIdGaps": rs.get("tradeIdGaps", 0),
        },
        "engineCounters": {
            "overflowRejectsTotal": totals.get("overflowRej", 0),
            "droppedOmsTotal": totals.get("droppedOms", 0),
            "droppedMktTotal": totals.get("droppedMkt", 0),
            "perNode": counters,
        },
        "balances": balances,
        "gates": {
            # P1.2 acceptance: 0% terminal divergence, <0.5% transient-OPEN.
            "terminalDivergenceZero": div_open_terminal == 0,
            "openDivergenceUnderHalfPct": pct(div_terminal_open, div_checked) < 0.5,
            # P1.1 acceptance baseline: no silent overflow -> rejects counted, none silent.
            "overflowRejects": totals.get("overflowRej", 0),
            "statusObserverAgreement": len(disagreements) == 0,
        },
        "harnessOk": (result["aborted"] is None
                      and result["completed"] >= args.switchovers
                      and submitted > 0
                      and not load_collapsed
                      and bool(rs)),
        "loadCollapsed": load_collapsed,
    }
    out = os.path.join(run_dir, "p1-gate-report.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)
    return report, out


def main():
    ap = argparse.ArgumentParser(description="P1 gate storm: load + N leader switchovers + reconcile")
    ap.add_argument("--switchovers", type=int, default=50)
    ap.add_argument("--users", type=int, default=30)
    ap.add_argument("--base", type=int, default=820000, help="first test userId")
    ap.add_argument("--rate", type=int, default=200, help="orders/sec via OMS REST")
    ap.add_argument("--warmup", type=int, default=12)
    ap.add_argument("--quiesce", type=int, default=20)
    ap.add_argument("--cycle-budget", type=int, default=30,
                    help="seconds budgeted per switchover cycle (kill + heal + load window)")
    ap.add_argument("--switchover-timeout", type=int, default=90)
    ap.add_argument("--pg-reconcile", action="store_true",
                    help="also reconcile balances against the OMS Postgres ledger (oms#22)")
    ap.add_argument("--enforce-gates", action="store_true",
                    help="also fail on gate breach (use once P1.1/P1.2 have landed)")
    args = ap.parse_args()

    run_dir, result, switchover_log, markers = run_storm(args)
    report, out = assemble_report(run_dir, args, result, switchover_log, markers)

    print("\n" + "=" * 64)
    print("P1 GATE REPORT")
    print("=" * 64)
    r = report
    print(f"  switchovers      : {r['run']['switchoversCompleted']}/{r['run']['switchoversRequested']}"
          f" (mean {r['run']['meanSwitchoverSec']}s, aborted={r['run']['aborted']})")
    print(f"  orders           : submitted={r['orders']['submitted']}"
          f" trades={r['orders']['observedTrades']}"
          f" engineSubmitted={r['orders']['engineSubmitted']}"
          f" engineTerminalized={r['orders']['engineTerminalized']}")
    d = r["divergence"]
    print(f"  divergence       : omsOpen/clusterTerminal={d['omsOpenClusterTerminal']['count']}"
          f" ({d['omsOpenClusterTerminal']['pct']}%)"
          f" omsTerminal/clusterOpen={d['omsTerminalClusterOpen']['count']}"
          f" ({d['omsTerminalClusterOpen']['pct']}%)")
    print(f"  balances/fills   : balanceDiscrepancies={d['balanceDiscrepancies']}"
          f" filledQtyMiscounts={d['filledQtyMiscounts']}"
          f" engineStreamDivergence={d['engineStreamDivergence']}")
    print(f"  engine counters  : overflowRejects={r['engineCounters']['overflowRejectsTotal']}"
          f" droppedOms={r['engineCounters']['droppedOmsTotal']}")
    print(f"  status vs observer: disagreements={r['run']['leaderDisagreements']}")
    print(f"  gates            : {json.dumps(r['gates'])}")
    print(f"  harnessOk        : {r['harnessOk']}")
    print(f"\n  full report -> {out}")

    if not report["harnessOk"]:
        sys.exit(1)
    if args.enforce_gates and not (report["gates"]["terminalDivergenceZero"]
                                   and report["gates"]["openDivergenceUnderHalfPct"]
                                   and report["gates"]["statusObserverAgreement"]):
        sys.exit(2)


if __name__ == "__main__":
    main()
