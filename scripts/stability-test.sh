#!/bin/bash
#
# Stability Test: 10-minute load with snapshots + rolling updates
#
# Verifies the cluster remains stable under sustained load while performing:
# - Cluster snapshots (2x)
# - Rolling updates (2x)
#
# Gateway HTTP throughput caps at ~1.5k/s (single-threaded Aeron session).
# Use 1000/s for stability (headroom for back-pressure during operations).
#
# Prerequisites: cluster + gateways must be running
#

set -euo pipefail

ADMIN_URL="http://localhost:8082/api/admin"
ORDER_URL="http://127.0.0.1:8080"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOADGEN="$PROJECT_DIR/scripts/loadgen/loadgen"
LOG_DIR="$PROJECT_DIR/stability-test-logs"
DURATION=600
RATE=1000
WORKERS=50
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log() { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"; }
ok()  { echo -e "${GREEN}[$(date +%H:%M:%S)] ✓${NC} $1"; }
err() { echo -e "${RED}[$(date +%H:%M:%S)] ✗${NC} $1"; }
warn(){ echo -e "${YELLOW}[$(date +%H:%M:%S)] ⚠${NC} $1"; }

LOAD_PID=""
cleanup() {
    if [[ -n "$LOAD_PID" ]] && kill -0 "$LOAD_PID" 2>/dev/null; then
        log "Stopping load generator..."
        kill "$LOAD_PID" 2>/dev/null; wait "$LOAD_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ==================== Pre-flight ====================

preflight() {
    log "Running pre-flight checks..."

    local status
    status=$(curl -sf "$ADMIN_URL/status" 2>/dev/null) || { err "Admin API not reachable"; exit 1; }
    local leader=$(echo "$status" | jq -r '.leader')
    [[ -z "$leader" || "$leader" == "null" ]] && { err "No cluster leader"; exit 1; }
    ok "Cluster running, leader: node $leader"

    local running=$(echo "$status" | jq '[.nodes[] | select(.running)] | length')
    [[ "$running" != "3" ]] && { err "Expected 3 nodes, got $running"; exit 1; }
    ok "All 3 nodes running"

    curl -sf "$ORDER_URL/health" > /dev/null 2>&1 || { err "Order gateway unreachable"; exit 1; }
    ok "Order gateway healthy"

    [[ -x "$LOADGEN" ]] || { err "Load generator not found: $LOADGEN"; exit 1; }
    ok "Load generator ready"
}

# ==================== Health check ====================

check_health() {
    local label="$1"
    local status
    status=$(curl -sf --max-time 5 "$ADMIN_URL/status" 2>/dev/null) || { err "[$label] Admin API unreachable"; return 1; }
    local leader=$(echo "$status" | jq -r '.leader')
    local nodes=$(echo "$status" | jq '[.nodes[] | select(.running)] | length')

    [[ -z "$leader" || "$leader" == "null" ]] && { err "[$label] No leader!"; return 1; }
    [[ "$nodes" -lt 2 ]] && warn "[$label] Only $nodes/3 nodes running"

    ok "[$label] Healthy — leader: node $leader, nodes: $nodes/3"
}

# ==================== Order check ====================

verify_orders() {
    local label="$1"
    local resp
    resp=$(curl -sf --max-time 5 -X POST "$ORDER_URL/order" \
        -H "Content-Type: application/json" \
        -d '{"userId":"stability","market":"BTC-USD","orderSide":"BUY","orderType":"LIMIT","price":95000.0,"quantity":0.001}' 2>/dev/null)
    if [[ $? -eq 0 ]]; then
        ok "[$label] Order accepted"
        return 0
    else
        err "[$label] Order FAILED"
        return 1
    fi
}

# ==================== Snapshot ====================

trigger_snapshot() {
    local label="$1"
    log "[$label] Triggering snapshot..."
    local resp
    resp=$(curl -sf -X POST "$ADMIN_URL/snapshot" 2>/dev/null) || { err "[$label] Snapshot failed"; return 1; }
    ok "[$label] Snapshot initiated: $resp"
    log "[$label] Waiting 15s for completion..."
    sleep 15
    check_health "post-$label"
}

# ==================== Rolling update ====================

trigger_rolling_update() {
    local label="$1"
    log "[$label] Triggering rolling update..."

    local resp
    resp=$(curl -s -X POST "$ADMIN_URL/rolling-update" 2>/dev/null)
    if echo "$resp" | grep -q "error\|in progress"; then
        warn "[$label] $resp — waiting 30s and retrying..."
        sleep 30
        resp=$(curl -s -X POST "$ADMIN_URL/rolling-update" 2>/dev/null)
        if echo "$resp" | grep -q "error"; then
            err "[$label] Rolling update failed: $resp"; return 1
        fi
    fi
    ok "[$label] Rolling update started: $resp"

    # Monitor progress
    local elapsed=0
    while [[ $elapsed -lt 180 ]]; do
        sleep 10; elapsed=$((elapsed + 10))
        local status=$(curl -sf "$ADMIN_URL/status" 2>/dev/null) || continue
        local nodes=$(echo "$status" | jq '[.nodes[] | select(.running)] | length')
        local leader=$(echo "$status" | jq -r '.leader')
        log "[$label] Progress ($elapsed/180s) — nodes: $nodes/3, leader: $leader"
        [[ "$nodes" == "3" && $elapsed -gt 30 ]] && { ok "[$label] All nodes back"; break; }
    done

    # Gateway reconnect after rolling update
    sleep 5
    log "[$label] Restarting gateways for reconnection..."
    curl -sf -X POST "$ADMIN_URL/restart-gateway" > /dev/null 2>&1
    sleep 25

    # Verify gateway works
    local retries=0
    while [[ $retries -lt 6 ]]; do
        if verify_orders "post-$label"; then
            break
        fi
        retries=$((retries + 1))
        log "[$label] Waiting for gateway reconnection ($retries/6)..."
        sleep 10
    done

    check_health "post-$label"
}

# ==================== Main ====================

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║       Stability Test: Load + Snapshots + Rolling Updates     ║"
    echo "╠════════════════════════════════════════════════════════════════╣"
    echo "║  Rate:     ${RATE}/s via HTTP → Order Gateway                    ║"
    echo "║  Duration: 10 minutes                                        ║"
    echo "║  Actions:  2 snapshots + 2 rolling updates                   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    mkdir -p "$LOG_DIR"
    preflight
    echo ""

    local start_time=$(date +%s)
    check_health "pre-test"
    verify_orders "pre-test" || { err "Pre-test order failed, aborting"; exit 1; }
    echo ""

    # Start load generator
    log "Starting load: ${RATE}/s for ${DURATION}s..."
    "$LOADGEN" -rate "$RATE" -duration "$DURATION" -workers "$WORKERS" \
        -url "$ORDER_URL/order" \
        > "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>&1 &
    LOAD_PID=$!
    ok "Load generator started (PID: $LOAD_PID)"
    echo ""

    # Wait for ramp-up
    log "Waiting 30s for stabilization..."
    sleep 30
    if ! kill -0 "$LOAD_PID" 2>/dev/null; then
        err "Load generator died! See $LOG_DIR/loadtest_${TIMESTAMP}.log"
        tail -10 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
        exit 1
    fi
    log "Load stats:"; tail -1 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
    check_health "after-ramp-up"
    echo ""

    # ── ACTION 1: Snapshot at ~1.5 min ──
    echo -e "${BOLD}═══ ACTION 1: Cluster Snapshot (t≈90s) ═══${NC}"
    sleep 60
    trigger_snapshot "snapshot-1"
    log "Load stats:"; tail -1 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
    echo ""

    # ── ACTION 2: Rolling update at ~3.5 min ──
    echo -e "${BOLD}═══ ACTION 2: Rolling Update (t≈210s) ═══${NC}"
    sleep 60
    trigger_rolling_update "rolling-1"
    log "Load stats:"; tail -1 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
    echo ""

    # ── Mid-test check ──
    check_health "mid-test"
    echo ""

    # ── ACTION 3: Snapshot at ~6 min ──
    echo -e "${BOLD}═══ ACTION 3: Cluster Snapshot (t≈360s) ═══${NC}"
    sleep 60
    trigger_snapshot "snapshot-2"
    log "Load stats:"; tail -1 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
    echo ""

    # ── ACTION 4: Rolling update at ~8 min ──
    echo -e "${BOLD}═══ ACTION 4: Rolling Update (t≈480s) ═══${NC}"
    sleep 60
    trigger_rolling_update "rolling-2"
    log "Load stats:"; tail -1 "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null
    echo ""

    # Wait for load test to finish
    log "Waiting for load test to complete..."
    if wait "$LOAD_PID" 2>/dev/null; then
        ok "Load test completed"
    else
        local ec=$?
        [[ $ec -eq 143 || $ec -eq 137 ]] && ok "Load test terminated (signal)" || warn "Load test exit code $ec"
    fi
    LOAD_PID=""
    echo ""

    # ==================== Results ====================
    local end_time=$(date +%s)
    local total=$((end_time - start_time))

    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                   STABILITY TEST RESULTS                     ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    check_health "final"
    verify_orders "post-test"

    echo ""
    log "Total duration: ${total}s"
    log "Log: $LOG_DIR/loadtest_${TIMESTAMP}.log"
    echo ""
    echo -e "${BOLD}Load Test Results:${NC}"
    grep -A20 "FINAL RESULTS" "$LOG_DIR/loadtest_${TIMESTAMP}.log" 2>/dev/null || tail -15 "$LOG_DIR/loadtest_${TIMESTAMP}.log"
    echo ""
    ok "Stability test complete!"
}

main "$@"
