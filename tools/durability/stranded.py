#!/usr/bin/env python3
"""
Stranded-follower regression gate (match#32 triage item 1; gates match#35 / P1.3-iv).

Deliberately reproduces the 2026-07-02 incident shape
(docs/incidents/2026-07-02-follower-stranded-by-housekeeping.md):

  1. stop a follower F
  2. advance the log under load
  3. snapshot + housekeeping on the live majority (purges the log segments F
     still needs — the stranding mechanism)
  4. restart F and watch what happens

Acceptable outcomes (gate PASSES):
  * F rejoins: health HEALTHY with its commit position tracking the leader, or
  * F fails LOUDLY: process exits (running=false) or an explicit replay error
    appears in its log.

The gate FAILS on the incident behavior: F stays running + DEGRADED (commit
frozen while the others advance) past the grace window with no error — the
silent hot-loop. When P1.3 (match#35) lands its lag guard, housekeeping will
refuse step 3 while a member is down; that refusal is ALSO a pass (the guard
is the fix) — rerun with the guard's --force to exercise the reseed path.

Requires the truthful status API (admin-gateway#13 item 1).
On FAIL the cluster keeps a stranded member: reseed per the validated manual
procedure (copy a healthy follower's cluster/+archive/ dirs, excluding
cluster-mark*.dat, node-state.dat, archive-mark.dat, *.lck).

Stdlib only.
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

import durability as dur  # noqa: E402
from storm import require_truthful_status, wait_healthy_core  # noqa: E402

ERROR_MARKERS = ("unknown recording", "Exception", "FATAL", "ClusterException")


def status():
    st, data = dur.admin_get("/api/admin/status")
    return json.loads(data)


def node(s, i):
    return next(n for n in s["nodes"] if n["id"] == i)


def wait_progress_done(timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, data = dur.admin_get("/api/admin/progress")
        p = json.loads(data)
        if p.get("complete", True):
            return p
        time.sleep(2)
    raise TimeoutError("admin operation did not complete in time")


def log_errors_since(node_id, offset):
    path = os.path.expanduser(f"~/.local/log/cluster/node{node_id}.log")
    hits = []
    try:
        with open(path, errors="replace") as f:
            f.seek(offset if os.path.getsize(path) >= offset else 0)
            for line in f:
                if any(m in line for m in ERROR_MARKERS):
                    hits.append(line.strip()[:300])
    except OSError:
        pass
    return hits


def main():
    ap = argparse.ArgumentParser(description="Stranded-follower durability gate")
    ap.add_argument("--users", type=int, default=12)
    ap.add_argument("--base", type=int, default=830000)
    ap.add_argument("--rate", type=int, default=100)
    ap.add_argument("--load-seconds", type=int, default=30)
    ap.add_argument("--rejoin-timeout", type=int, default=180)
    args = ap.parse_args()

    require_truthful_status()
    s = dur.wait_healthy()
    leader = s.get("leader")
    follower = next(n["id"] for n in s["nodes"] if n["id"] != leader and n.get("running"))
    print(f"[stranded] leader={leader}, stranding follower {follower}")

    verdict = {"follower": follower, "outcome": None, "detail": None}

    print(f"[stranded] stop-node {follower}")
    dur.admin_post("/api/admin/stop-node", {"nodeId": follower})
    time.sleep(3)

    try:
        print(f"[stranded] advancing log: {args.rate}/s for {args.load_seconds}s")
        subprocess.run([sys.executable, os.path.join(BUG9, "oms_load.py"), "seed",
                        "--url", dur.OMS, "--base", str(args.base), "--n", str(args.users)],
                       check=True)
        subprocess.run([sys.executable, os.path.join(BUG9, "oms_load.py"), "run",
                        "--url", dur.OMS, "--base", str(args.base), "--n", str(args.users),
                        "--rate", str(args.rate), "--duration", str(args.load_seconds),
                        "--mid", str(dur.discover_mid()),
                        "--log", "/dev/null"], check=True)

        pre = status()
        snap_before = max(node(pre, i).get("snapshotCount", 0)
                          for i in (0, 1, 2) if i != follower)
        print("[stranded] snapshot...")
        st, data = dur.admin_post("/api/admin/snapshot")
        wait_progress_done()
        for _ in range(30):
            cur = status()
            if max(node(cur, i).get("snapshotCount", 0)
                   for i in (0, 1, 2) if i != follower) > snap_before:
                break
            time.sleep(2)

        print("[stranded] housekeeping (this is what strands the laggard pre-match#35)...")
        st, data = dur.admin_post("/api/admin/housekeeping")
        if st >= 400:
            # A refusal here means the match#35 lag guard landed and works.
            verdict["outcome"] = "PASS_GUARD_REFUSED"
            verdict["detail"] = data.decode(errors="replace")[:300]
            print(f"[stranded] housekeeping REFUSED with a member down — lag guard works: {verdict['detail']}")
        else:
            wait_progress_done()
    finally:
        log_path = os.path.expanduser(f"~/.local/log/cluster/node{follower}.log")
        log_offset = os.path.getsize(log_path) if os.path.exists(log_path) else 0
        print(f"[stranded] start-node {follower}")
        dur.admin_post("/api/admin/start-node", {"nodeId": follower})

    if verdict["outcome"] is None:
        deadline = time.time() + args.rejoin_timeout
        while time.time() < deadline:
            time.sleep(5)
            s = status()
            f = node(s, follower)
            lead = node(s, s.get("leader")) if s.get("leader", -1) >= 0 else {}
            if not f.get("running") and f.get("health") in ("DEAD", "OFFLINE"):
                verdict["outcome"] = "PASS_LOUD_EXIT"
                verdict["detail"] = f"follower exited: health={f.get('health')}"
                break
            errors = log_errors_since(follower, log_offset)
            if errors:
                verdict["outcome"] = "PASS_LOUD_ERROR"
                verdict["detail"] = errors[:3]
                break
            if (f.get("health") == "HEALTHY"
                    and f.get("commitPosition") is not None
                    and lead.get("commitPosition") is not None
                    and abs(lead["commitPosition"] - f["commitPosition"]) < 1_000_000):
                verdict["outcome"] = "PASS_REJOINED"
                verdict["detail"] = (f"commit {f['commitPosition']} vs leader "
                                     f"{lead['commitPosition']}")
                break
            print(f"[stranded]   waiting: follower health={f.get('health')} "
                  f"advancing={f.get('commitAdvancing')} commit={f.get('commitPosition')}")
        else:
            f = node(status(), follower)
            verdict["outcome"] = "FAIL_SILENT_HOTLOOP"
            verdict["detail"] = (f"follower still running+{f.get('health')} after "
                                 f"{args.rejoin_timeout}s with no error line — the "
                                 "2026-07-02 incident behavior (match#35)")

    print("\n" + "=" * 64)
    print(f"STRANDED-FOLLOWER GATE: {verdict['outcome']}")
    print(f"  {verdict['detail']}")
    print("=" * 64)
    if verdict["outcome"] == "FAIL_SILENT_HOTLOOP":
        print("  Cluster now has a stranded member. Reseed (validated manual procedure):")
        print("  stop a healthy follower, copy its cluster/ + archive/ dirs over the")
        print("  stranded member's, EXCLUDING cluster-mark*.dat, node-state.dat,")
        print("  archive-mark.dat, *.lck; start both.")
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
