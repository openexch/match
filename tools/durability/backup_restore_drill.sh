#!/usr/bin/env bash
#
# backup_restore_drill.sh — power-loss recovery drill (admin-gateway#9 exit criterion).
#
# Simulates total power loss of the cluster hosts and restores from the on-disk
# backup: kill -9 all nodes+drivers, wipe the tmpfs cluster state, restore every
# node from match/backup via the admin API, start the stack, verify consensus.
#
# DESTRUCTIVE: irreversibly deletes the LIVE cluster state on /dev/shm.
# Run on a dev box only, and only with an explicit acknowledgement:
#
#   ./backup_restore_drill.sh --i-understand-this-wipes-the-live-cluster
#
# Preconditions checked before any destruction:
#   - backup-info reports fresh=true (heartbeat live, backup tracking leader)
#   - recording.log is non-empty
set -euo pipefail

ADMIN="http://localhost:8082/api/admin"
DRILL_LOG_POS=""

[[ "${1:-}" == "--i-understand-this-wipes-the-live-cluster" ]] || {
    sed -n '2,16p' "$0"; exit 1; }

say() { echo "[drill $(date +%H:%M:%S)] $*"; }
fail() { echo "[drill] FAIL: $*" >&2; exit 1; }

api() { curl -sf -X "${2:-GET}" "$ADMIN/$1" ${3:+-H 'Content-Type: application/json' -d "$3"}; }

# ---------- 1. preconditions: the backup must be usable ----------
say "checking backup freshness"
info=$(api backup-info)
echo "$info" | python3 -c '
import json,sys
i=json.load(sys.stdin)
assert i["fresh"], f"backup not fresh: {i[\"freshReason\"]}"
assert i["recordingLogBytes"] > 0, "recording.log empty — backup has never completed a cycle"
hb=i.get("heartbeat") or {}
print(f"[drill]   fresh, liveLogPosition={hb.get(\"liveLogPosition\")}, recordings={i[\"recordingCount\"]}")
' || fail "backup precondition"
DRILL_LOG_POS=$(echo "$info" | python3 -c 'import json,sys; print((json.load(sys.stdin).get("heartbeat") or {}).get("liveLogPosition", 0))')

# ---------- 2. power loss: kill everything abruptly, wipe tmpfs ----------
say "simulating power loss: SIGKILL backup, nodes, drivers"
for svc in backup node0 node1 node2 driver0 driver1 driver2; do
    api "processes/$svc/force-stop" POST >/dev/null || true
done
sleep 3

say "wiping tmpfs cluster state (/dev/shm/aeron-cluster/node*, driver dirs)"
rm -rf /dev/shm/aeron-cluster/node0/* /dev/shm/aeron-cluster/node1/* /dev/shm/aeron-cluster/node2/*
rm -rf /dev/shm/aeron-"$USER"-*-driver

# ---------- 3. restore every node from the disk backup ----------
for n in 0 1 2; do
    say "restoring node$n from backup"
    api recover-from-backup POST "{\"nodeId\": $n, \"force\": true}" \
        | python3 -c 'import json,sys; r=json.load(sys.stdin); assert r.get("success"), r; print("[drill]   ", r.get("message", "ok"))' \
        || fail "recover-from-backup node$n"
done

# ---------- 4. start the stack (drivers first; the driver gate paces nodes) ----------
say "starting drivers + nodes"
for svc in driver0 driver1 driver2; do api "processes/$svc/start" POST >/dev/null || true; sleep 1; done
sleep 6
for svc in node0 node1 node2; do api "processes/$svc/start" POST >/dev/null || fail "start $svc"; sleep 2; done

# ---------- 5. verify recovery ----------
say "waiting for consensus (up to 120s)"
deadline=$((SECONDS + 120))
while (( SECONDS < deadline )); do
    if api status | python3 -c '
import json,sys
s=json.load(sys.stdin)
ok = s.get("leader") is not None and s.get("allNodesHealthy")
sys.exit(0 if ok else 1)' 2>/dev/null; then
        say "consensus restored: $(api status | python3 -c 'import json,sys; s=json.load(sys.stdin); print(f"leader={s[\"leader\"]}, allNodesHealthy={s[\"allNodesHealthy\"]}")')"
        say "restarting backup service"
        api "processes/backup/start" POST >/dev/null || true
        say "PASS — recovered from disk backup (pre-drill liveLogPosition=$DRILL_LOG_POS)"
        say "next: verify books via the UI/OMS and reconcile OMS against Postgres"
        exit 0
    fi
    sleep 5
done
fail "cluster did not reach healthy consensus within 120s — see /api/admin/status and node logs"
