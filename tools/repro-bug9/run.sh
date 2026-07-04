#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Bug #9 repro — orchestrate one full run: observe -> seed -> load -> N switchovers -> quiesce -> reconcile.
#
# OBSERVATION ONLY. Forces leader switchovers via restart-node (each self-heals, ~seconds of UI stutter).
# Never stop-all / cleanup / compact / rolling-update.
#
# Usage: ./run.sh [num_switchovers] [users] [rate] [run_seconds]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADMIN="${ADMIN:-http://localhost:8082}"
OMS="${OMS:-http://localhost:8080}"
JAR="${JAR:-$HERE/../../match-loadtest/target/match-loadtest.jar}"

NSW="${1:-4}"          # number of switchovers
USERS="${2:-30}"       # number of test users
RATE="${3:-200}"       # orders/sec
RUN_SECONDS="${4:-130}"  # total load duration (must cover warmup + switchovers + quiesce)
BASE_USER=700000
WARMUP=12
BETWEEN=14
QUIESCE=15

RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN="$HERE/runs/$RUN_ID"
mkdir -p "$RUN"
echo "[run] run dir: $RUN"

# --- pre-flight ---
S="$(curl -s --max-time 8 "$ADMIN/api/admin/status")"
echo "[run] preflight: leader=$(echo "$S" | jq .leader) roles=$(echo "$S" | jq -c '[.nodes[].role]') mkt=$(echo "$S" | jq .gateways.market.healthy) oms=$(echo "$S" | jq .gateways.oms.healthy)"
echo "$S" | jq -e '([.nodes[]|select(.running==true)]|length)==3 and (.gateways.oms.healthy) and (.gateways.market.healthy)' >/dev/null \
  || { echo "[run] ABORT: cluster/gateways not healthy"; exit 1; }

# --- 1. observer (background) ---
echo "[run] starting observer..."
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -cp "$HERE/out:$JAR" Bug9Observer "$RUN" "$((RUN_SECONDS + 40))" \
  > "$RUN/observer.stdout" 2>&1 &
OBS_PID=$!
# wait until observer logs a connected event
for i in $(seq 1 30); do
  grep -q '"kind":"connected"' "$RUN/observer-events.jsonl" 2>/dev/null && break
  sleep 1
done
grep -q '"kind":"connected"' "$RUN/observer-events.jsonl" 2>/dev/null \
  && echo "[run] observer connected" || echo "[run] WARNING: observer not confirmed connected"

# --- 2. seed + initial snapshot ---
python3 "$HERE/oms_load.py" seed --url "$OMS" --base $BASE_USER --n "$USERS"
python3 "$HERE/oms_load.py" snapshot --url "$OMS" --base $BASE_USER --n "$USERS" --out "$RUN/balances-initial.json"

# --- 3. load (background) ---
echo "[run] starting load: $RATE/s for ${RUN_SECONDS}s, $USERS users"
python3 "$HERE/oms_load.py" run --url "$OMS" --base $BASE_USER --n "$USERS" \
  --rate "$RATE" --duration "$RUN_SECONDS" --log "$RUN/submitted.jsonl" > "$RUN/load.stdout" 2>&1 &
LOAD_PID=$!

echo "[run] warmup ${WARMUP}s..."; sleep "$WARMUP"

# --- 4. switchovers ---
for i in $(seq 1 "$NSW"); do
  echo "[run] === switchover $i/$NSW ==="
  ADMIN="$ADMIN" bash "$HERE/switchover.sh" "$ADMIN" | tee -a "$RUN/switchovers.log"
  echo "[run] load for ${BETWEEN}s..."; sleep "$BETWEEN"
done

# --- 5. wait for load to finish, quiesce ---
echo "[run] waiting for load to complete..."
wait "$LOAD_PID" 2>/dev/null || true
echo "[run] quiesce ${QUIESCE}s (let final egress drain + OMS reconnect)..."; sleep "$QUIESCE"

# --- 6. cancel open orders (release holds) + final snapshot ---
python3 "$HERE/oms_load.py" cancel --url "$OMS" --base $BASE_USER --n "$USERS"
sleep 3
python3 "$HERE/oms_load.py" snapshot --url "$OMS" --base $BASE_USER --n "$USERS" --out "$RUN/balances-final.json"

# --- 7. stop observer ---
echo "[run] stopping observer..."
touch "$RUN/STOP"
wait "$OBS_PID" 2>/dev/null || true

# --- 8. reconcile ---
echo "[run] reconciling..."
python3 "$HERE/reconcile.py" --dir "$RUN" --url "$OMS"

echo "[run] DONE. Artifacts in $RUN"
