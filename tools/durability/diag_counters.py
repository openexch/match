#!/usr/bin/env python3
"""
Harvest the engine's EGRESS-DIAG counters from node logs (match#32 / P1.5).

The cluster's diag counters (submitted, terminal, overflowRej, droppedMkt,
droppedOms) are in-process values printed on EGRESS-DIAG log lines by
AppClusteredService. Two realities shape this parser:

  * Counters RESET on every node restart, and a storm run restarts nodes
    dozens of times. A value that DECREASES marks a new process incarnation;
    the run total is the sum of each incarnation's last value.
  * The process manager ROTATES nodeN.log (rename to nodeN.log.YYYYMMDD-HHMMSS
    + fresh file) on every manual start, so one run's diag lines are spread
    over several files. Segments are stitched in rotation order, with the
    pre-run byte offset applied to the first one.

The admin /api/admin/logs endpoint caps at 500 lines, far too small for a
45-minute run, so logs are read straight from LOG_DIR (harness runs on the
cluster box). Unknown keys are ignored and missing keys tolerated, so this
works against binaries with or without the match#32 counter patch.

Stdlib only.
"""
import glob
import json
import os
import re
import time

LOG_DIR = os.environ.get("CLUSTER_LOG_DIR",
                         os.path.expanduser("~/.local/log/cluster"))

# Monotonic-per-incarnation counters worth totaling. Everything else on the
# diag line (queue depths, session counts, ages) is a gauge — meaningless to sum.
MONOTONIC_KEYS = ("submitted", "terminal", "overflowRej", "droppedMkt", "droppedOms")

_DIAG = re.compile(r"EGRESS-DIAG")
_KV = re.compile(r"([A-Za-z]+)=(\d+)")
_ROTATED_TS = re.compile(r"\.(\d{8}-\d{6})$")


def mark(node):
    """Record the current (size) position of a node's live log at run start."""
    path = os.path.join(LOG_DIR, f"node{node}.log")
    try:
        return {"path": path, "offset": os.path.getsize(path), "epoch": time.time()}
    except OSError:
        return {"path": path, "offset": 0, "epoch": time.time()}


def _segments(node, start_epoch, start_offset):
    """Ordered (path, offset) log segments covering the run window.

    Rotation renames the live file, so content written since the run started
    lives in every nodeN.log.<ts> whose rotation stamp is after run start
    (earliest first, starting at the recorded offset) plus the live file.
    """
    live = os.path.join(LOG_DIR, f"node{node}.log")
    rotated = []
    for p in glob.glob(live + ".*"):
        m = _ROTATED_TS.search(p)
        if not m:
            continue
        try:
            ts = time.mktime(time.strptime(m.group(1), "%Y%m%d-%H%M%S"))
        except ValueError:
            continue
        if ts >= start_epoch:
            rotated.append((ts, p))
    rotated.sort()
    segs = [(p, start_offset if i == 0 else 0) for i, (_, p) in enumerate(rotated)]
    segs.append((live, 0 if rotated else start_offset))
    return segs


def harvest(node, marker):
    """Total each monotonic counter across all of a node's incarnations."""
    completed = {k: 0 for k in MONOTONIC_KEYS}
    last = {k: 0 for k in MONOTONIC_KEYS}
    incarnations = 1
    lines = 0
    for path, offset in _segments(node, marker["epoch"], marker["offset"]):
        try:
            with open(path, errors="replace") as f:
                if offset:
                    f.seek(offset)
                for line in f:
                    if not _DIAG.search(line):
                        continue
                    kv = dict((k, int(v)) for k, v in _KV.findall(line))
                    lines += 1
                    reset = any(k in kv and kv[k] < last[k] for k in MONOTONIC_KEYS)
                    if reset:
                        incarnations += 1
                        for k in MONOTONIC_KEYS:
                            completed[k] += last[k]
                            last[k] = 0
                    for k in MONOTONIC_KEYS:
                        if k in kv:
                            last[k] = kv[k]
        except OSError:
            continue
    return {
        "node": node,
        "diagLines": lines,
        "incarnations": incarnations,
        **{k: completed[k] + last[k] for k in MONOTONIC_KEYS},
    }


def harvest_all(markers):
    """markers: {node: mark(node)} captured at run start."""
    return [harvest(n, m) for n, m in sorted(markers.items())]


if __name__ == "__main__":
    # Standalone: report totals from the last N minutes (default 60) for a quick look.
    import argparse
    ap = argparse.ArgumentParser(description="Harvest EGRESS-DIAG counters from node logs")
    ap.add_argument("--minutes", type=int, default=60, help="look-back window")
    args = ap.parse_args()
    since = time.time() - args.minutes * 60
    fake_markers = {n: {"path": "", "offset": 0, "epoch": since} for n in (0, 1, 2)}
    print(json.dumps(harvest_all(fake_markers), indent=2))
