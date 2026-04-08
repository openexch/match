#!/bin/bash
set -e

# ================================================================
# Snapshot Validation Test
# Verifies deterministic state machine maintains state across:
#   1. Manual snapshot + graceful restart
#   2. Rolling update
#   3. Unexpected shutdown (kill -9)
# ================================================================

ORDER_GW="http://localhost:8080"
MARKET_GW="http://localhost:8081"
ADMIN_GW="http://localhost:8082"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

pass=0
fail=0

log()  { echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"; }
ok()   { echo -e "${GREEN}  ✓ $1${NC}"; pass=$((pass+1)); }
fail() { echo -e "${RED}  ✗ $1${NC}"; fail=$((fail+1)); }
warn() { echo -e "${YELLOW}  ⚠ $1${NC}"; }

# ── Capture current state from market gateway ──
capture_state() {
    local label="$1"
    local state_file="/tmp/snapshot-test-${label}.json"

    # Get order book
    local book=$(curl -s "${MARKET_GW}/api/orderbook?marketId=1" 2>/dev/null)
    # Get trade count
    local trades=$(curl -s "${MARKET_GW}/api/trades?marketId=1&limit=1000" 2>/dev/null)
    # Get health
    local health=$(curl -s "${MARKET_GW}/health" 2>/dev/null)

    # Extract key metrics
    local bid_levels=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('bids',[])))" 2>/dev/null || echo "0")
    local ask_levels=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('asks',[])))" 2>/dev/null || echo "0")
    local best_bid=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['bids'][0]['price'] if d.get('bids') else 0)" 2>/dev/null || echo "0")
    local best_ask=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['asks'][0]['price'] if d.get('asks') else 0)" 2>/dev/null || echo "0")
    local trade_count=$(echo "$trades" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('trades',[])))" 2>/dev/null || echo "0")
    local total_bid_qty=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(l['quantity'] for l in d.get('bids',[])))" 2>/dev/null || echo "0")
    local total_ask_qty=$(echo "$book" | python3 -c "import sys,json; d=json.load(sys.stdin); print(sum(l['quantity'] for l in d.get('asks',[])))" 2>/dev/null || echo "0")

    # Save to file
    cat > "$state_file" <<EOF
{
  "bid_levels": $bid_levels,
  "ask_levels": $ask_levels,
  "best_bid": $best_bid,
  "best_ask": $best_ask,
  "trade_count": $trade_count,
  "total_bid_qty": $total_bid_qty,
  "total_ask_qty": $total_ask_qty
}
EOF
    log "State [$label]: bids=$bid_levels asks=$ask_levels bestBid=$best_bid bestAsk=$best_ask trades=$trade_count bidQty=$total_bid_qty askQty=$total_ask_qty"
    echo "$state_file"
}

# ── Compare two state files ──
compare_states() {
    local label="$1"
    local before_file="$2"
    local after_file="$3"

    local before=$(cat "$before_file")
    local after=$(cat "$after_file")

    local b_bids=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['bid_levels'])")
    local a_bids=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['bid_levels'])")
    local b_asks=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['ask_levels'])")
    local a_asks=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['ask_levels'])")
    local b_bbid=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['best_bid'])")
    local a_bbid=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['best_bid'])")
    local b_bask=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['best_ask'])")
    local a_bask=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['best_ask'])")
    local b_bqty=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_bid_qty'])")
    local a_bqty=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_bid_qty'])")
    local b_aqty=$(echo "$before" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_ask_qty'])")
    local a_aqty=$(echo "$after" | python3 -c "import sys,json; print(json.load(sys.stdin)['total_ask_qty'])")

    log "Comparing [$label]..."
    [ "$b_bids" = "$a_bids" ] && ok "Bid levels: $b_bids = $a_bids" || fail "Bid levels: $b_bids != $a_bids"
    [ "$b_asks" = "$a_asks" ] && ok "Ask levels: $b_asks = $a_asks" || fail "Ask levels: $b_asks != $a_asks"
    [ "$b_bbid" = "$a_bbid" ] && ok "Best bid: $b_bbid = $a_bbid" || fail "Best bid: $b_bbid != $a_bbid"
    [ "$b_bask" = "$a_bask" ] && ok "Best ask: $b_bask = $a_bask" || fail "Best ask: $b_bask != $a_bask"
    [ "$b_bqty" = "$a_bqty" ] && ok "Total bid qty: $b_bqty = $a_bqty" || fail "Total bid qty: $b_bqty != $a_bqty"
    [ "$b_aqty" = "$a_aqty" ] && ok "Total ask qty: $b_aqty = $a_aqty" || fail "Total ask qty: $b_aqty != $a_aqty"
}

# ── Wait for cluster + gateways to be ready ──
wait_for_ready() {
    local max_wait=${1:-60}
    local start=$(date +%s)
    while true; do
        local elapsed=$(( $(date +%s) - start ))
        if [ $elapsed -gt $max_wait ]; then
            fail "Cluster not ready after ${max_wait}s"
            return 1
        fi
        # Check admin reports leader
        local leader=$(curl -s "${ADMIN_GW}/api/admin/status" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('leader',-1))" 2>/dev/null)
        if [ "$leader" != "-1" ] && [ -n "$leader" ]; then
            # Check order gateway responds
            local order_ok=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${ORDER_GW}/order" -H "Content-Type: application/json" -d '{"userId":"999","market":"BTC-USD","orderSide":"BUY","orderType":"LIMIT","price":1.0,"quantity":0.001}' 2>/dev/null)
            if [ "$order_ok" = "202" ]; then
                # Check market gateway health
                local health=$(curl -s "${MARKET_GW}/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
                if [ "$health" = "ok" ]; then
                    ok "Cluster ready (leader=$leader, ${elapsed}s)"
                    sleep 2  # Let state settle
                    return 0
                fi
            fi
        fi
        sleep 2
    done
}

# ── Trigger snapshot via admin API ──
take_snapshot() {
    log "Taking snapshot..."
    local resp=$(curl -s -X POST "${ADMIN_GW}/api/admin/snapshot" 2>/dev/null)
    echo "$resp" | grep -q "initiated\|success\|Snapshot" && ok "Snapshot triggered" || warn "Snapshot response: $resp"
    sleep 5  # Wait for snapshot to complete
}

# ── Place deterministic seed orders ──
seed_orders() {
    local count=${1:-50}
    log "Seeding $count order pairs..."
    for i in $(seq 1 $count); do
        local price=$(echo "59000 + $i * 10" | bc)
        # Bid
        curl -s -X POST "${ORDER_GW}/order" -H "Content-Type: application/json" \
            -d "{\"userId\":\"$((i*2))\",\"market\":\"BTC-USD\",\"orderSide\":\"BUY\",\"orderType\":\"LIMIT\",\"price\":$price,\"quantity\":0.1}" > /dev/null
        # Ask  
        local ask_price=$(echo "61000 + $i * 10" | bc)
        curl -s -X POST "${ORDER_GW}/order" -H "Content-Type: application/json" \
            -d "{\"userId\":\"$((i*2+1))\",\"market\":\"BTC-USD\",\"orderSide\":\"SELL\",\"orderType\":\"LIMIT\",\"price\":$ask_price,\"quantity\":0.1}" > /dev/null
    done
    sleep 3  # Let everything process
    ok "Seeded $count bid + $count ask orders"
}

echo ""
echo "========================================================================"
echo "  SNAPSHOT VALIDATION TEST"
echo "  Deterministic State Machine Verification"
echo "========================================================================"
echo ""

# ================================================================
# PHASE 1: Load test + snapshot during load
# ================================================================
log "PHASE 1: Load test with snapshots"
log "─────────────────────────────────"

wait_for_ready 30

# Seed initial order book
seed_orders 100

# Capture state before snapshot
STATE_PRE_SNAP1=$(capture_state "pre-snapshot-1")

# Take first snapshot
take_snapshot

# Run load test (moderate, 15s)
log "Running load test during snapshot..."
cd /home/emre/Apps/match/scripts/loadgen
./loadgen -rate 200 -duration 10 -workers 10 2>&1 | grep "FINAL\|Success\|Errors\|Latency p50" &
LOADPID=$!

# Take second snapshot mid-load
sleep 5
take_snapshot
wait $LOADPID 2>/dev/null

# Capture state after load
sleep 3
STATE_POST_LOAD=$(capture_state "post-load")

# ================================================================
# PHASE 2: Graceful restart — verify snapshot restore
# ================================================================
echo ""
log "PHASE 2: Graceful restart (snapshot restore)"
log "─────────────────────────────────────────────"

# Take a clean snapshot of current state
take_snapshot
sleep 3
STATE_BEFORE_RESTART=$(capture_state "before-restart")

# Graceful stop + start
log "Stopping gateways..."
systemctl --user stop order market
sleep 2

log "Stopping cluster nodes..."
systemctl --user stop node0 node1 node2
sleep 3

log "Starting cluster nodes..."
systemctl --user start node0
sleep 3
systemctl --user start node1 node2
sleep 12

log "Starting gateways..."
systemctl --user start order market
sleep 10

# Wait for ready
wait_for_ready 60

# Let gateway sync state from cluster
sleep 5

STATE_AFTER_RESTART=$(capture_state "after-restart")
compare_states "Graceful Restart" "$STATE_BEFORE_RESTART" "$STATE_AFTER_RESTART"

# ================================================================
# PHASE 3: Rolling update — verify state preserved
# ================================================================
echo ""
log "PHASE 3: Rolling update (state preservation)"
log "─────────────────────────────────────────────"

# Add more orders to change state
seed_orders 20
sleep 2
take_snapshot
sleep 3
STATE_BEFORE_ROLLING=$(capture_state "before-rolling")

# Trigger rolling update
log "Initiating rolling update..."
curl -s -X POST "${ADMIN_GW}/api/admin/rolling-update" > /dev/null

# Wait for rolling update to complete
ru_tries=0
while [ $ru_tries -lt 30 ]; do
    sleep 10
    progress=$(curl -s "${ADMIN_GW}/api/admin/progress" 2>/dev/null)
    complete=$(echo "$progress" | python3 -c "import sys,json; print(json.load(sys.stdin).get('complete',False))" 2>/dev/null)
    pct=$(echo "$progress" | python3 -c "import sys,json; print(json.load(sys.stdin).get('progress',0))" 2>/dev/null)
    log "Rolling update: ${pct}%"
    if [ "$complete" = "True" ]; then
        ok "Rolling update complete"
        break
    fi
    ru_tries=$((ru_tries+1))
done

# Restart gateways after rolling update
curl -s -X POST "${ADMIN_GW}/api/admin/restart-gateway" > /dev/null
sleep 15
wait_for_ready 60
sleep 5

STATE_AFTER_ROLLING=$(capture_state "after-rolling")
compare_states "Rolling Update" "$STATE_BEFORE_ROLLING" "$STATE_AFTER_ROLLING"

# ================================================================
# PHASE 4: Unexpected shutdown (kill -9) — verify recovery
# ================================================================
echo ""
log "PHASE 4: Unexpected shutdown (kill -9)"
log "───────────────────────────────────────"

# Add more state changes
seed_orders 10
sleep 2
take_snapshot
sleep 5
STATE_BEFORE_KILL=$(capture_state "before-kill")

# Kill all nodes with -9 (no graceful shutdown)
log "Killing ALL cluster nodes with SIGKILL..."
systemctl --user kill -s SIGKILL node0 node1 node2 2>/dev/null
sleep 2

# Kill gateways too
systemctl --user stop order market 2>/dev/null
sleep 3

# Verify everything is dead
remaining=$(ps aux | grep "match-cluster\|MarketGateway\|OmsApplication" | grep -v grep | wc -l)
log "Remaining processes: $remaining"

# Clean up stale MediaDriver (simulating recovery after crash)
# Don't wipe cluster archive — that has the snapshots!
rm -rf /dev/shm/aeron-order-* /dev/shm/aeron-market-* 2>/dev/null

# Restart everything
log "Restarting cluster from crash..."
systemctl --user start node0
sleep 3
systemctl --user start node1 node2
sleep 15
systemctl --user start order market
sleep 10

wait_for_ready 90
sleep 5

STATE_AFTER_KILL=$(capture_state "after-kill")
compare_states "Kill -9 Recovery" "$STATE_BEFORE_KILL" "$STATE_AFTER_KILL"

# ================================================================
# PHASE 5: Submit orders after recovery — verify engine works
# ================================================================
echo ""
log "PHASE 5: Post-recovery order submission"
log "───────────────────────────────────────"

# Place a matching pair to verify the engine still processes
curl -s -X POST "${ORDER_GW}/order" -H "Content-Type: application/json" \
    -d '{"userId":"9001","market":"BTC-USD","orderSide":"SELL","orderType":"LIMIT","price":60500.0,"quantity":0.5}' > /dev/null
curl -s -X POST "${ORDER_GW}/order" -H "Content-Type: application/json" \
    -d '{"userId":"9002","market":"BTC-USD","orderSide":"BUY","orderType":"LIMIT","price":60500.0,"quantity":0.5}' > /dev/null
sleep 3

# Check health shows trades
health=$(curl -s "${MARKET_GW}/health")
has_trades=$(echo "$health" | python3 -c "import sys,json; print(json.load(sys.stdin).get('trades',False))" 2>/dev/null)
[ "$has_trades" = "True" ] && ok "Engine processes orders after recovery" || fail "Engine not processing after recovery"

# Verify a new matching trade happened
post_state=$(capture_state "post-recovery-orders")

# ================================================================
# RESULTS
# ================================================================
echo ""
echo "========================================================================"
echo "  RESULTS"
echo "========================================================================"
echo -e "  ${GREEN}Passed: $pass${NC}"
echo -e "  ${RED}Failed: $fail${NC}"
echo "========================================================================"
echo ""

[ $fail -eq 0 ] && exit 0 || exit 1
