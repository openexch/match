#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Bug #9 repro — force ONE clean leader switchover and wait until the cluster settles.
#
# Targets the CURRENT leader with the admin gateway's zero-downtime restart-node,
# which forces a Raft re-election. We confirm the switchover by (a) the status
# leader id changing and (b) all three nodes back running with exactly one LEADER.
#
# Safe: uses ONLY restart-node. Never stop-all / cleanup / compact / rolling-update.
# Each restart self-heals; if a node fails to rejoin, re-run restart-node on it.
#
# Usage: ./switchover.sh [admin_base]   (default http://localhost:8082)
set -euo pipefail

ADMIN="${1:-http://localhost:8082}"
OMS_LOG="${OMS_LOG:-$HOME/.local/log/cluster/oms.log}"
SETTLE_TIMEOUT="${SETTLE_TIMEOUT:-60}"   # seconds to wait for re-election + rejoin

status() { curl -s --max-time 8 "$ADMIN/api/admin/status"; }

leader_of() { echo "$1" | jq -r '.leader'; }
roles_of()  { echo "$1" | jq -c '[.nodes[].role]'; }
all_running() { echo "$1" | jq -e '([.nodes[] | select(.running==true)] | length) == 3' >/dev/null; }
one_leader()  { echo "$1" | jq -e '([.nodes[] | select(.role=="LEADER")] | length) == 1' >/dev/null; }

S0="$(status)"
L0="$(leader_of "$S0")"
echo "[switchover] starting leader=$L0 roles=$(roles_of "$S0")"

# Mark a position in the OMS log so we can detect the OMS "New leader elected" line for THIS event.
OMS_MARK=0
[ -f "$OMS_LOG" ] && OMS_MARK="$(wc -l < "$OMS_LOG")"

echo "[switchover] POST restart-node nodeId=$L0 (forces re-election)"
curl -s --max-time 30 -X POST "$ADMIN/api/admin/restart-node" \
  -H 'Content-Type: application/json' -d "{\"nodeId\":$L0}" | jq -c . || true

# Wait for a NEW leader to be elected AND the restarted node to rejoin (3 running, 1 LEADER).
echo "[switchover] waiting for re-election + rejoin (timeout ${SETTLE_TIMEOUT}s)..."
deadline=$((SECONDS + SETTLE_TIMEOUT))
NEWL="$L0"
while [ $SECONDS -lt $deadline ]; do
  S="$(status)" || { sleep 1; continue; }
  NEWL="$(leader_of "$S")"
  if [ "$NEWL" != "$L0" ] && [ "$NEWL" != "null" ] && all_running "$S" && one_leader "$S"; then
    echo "[switchover] settled: new leader=$NEWL roles=$(roles_of "$S")"
    break
  fi
  sleep 1
done

if [ "$NEWL" = "$L0" ] || [ "$NEWL" = "null" ]; then
  echo "[switchover] WARNING: leader did not change within ${SETTLE_TIMEOUT}s (still $NEWL). Check status."
fi

# Corroborate from the OMS log (authoritative client-side signal).
if [ -f "$OMS_LOG" ] && [ "$OMS_MARK" -gt 0 ]; then
  HIT="$(tail -n +"$((OMS_MARK+1))" "$OMS_LOG" | grep -m1 'New leader elected' || true)"
  [ -n "$HIT" ] && echo "[switchover] oms.log: $HIT" || echo "[switchover] (no 'New leader elected' line in oms.log yet)"
fi

# Emit a machine-readable marker for the reconcile step (epoch millis when settled).
echo "SWITCHOVER $(date +%s%3N) old_leader=$L0 new_leader=$NEWL"
