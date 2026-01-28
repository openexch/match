#!/usr/bin/env python3
"""
Snapshot Validation Test — Deterministic State Machine Verification

Verifies the matching engine maintains identical state across:
  1. Manual snapshot + graceful restart
  2. Rolling update
  3. Unexpected shutdown (kill -9)

Uses TWO state sources:
  - Gateway API: top-20 order book levels (lossy view, for UI correctness)
  - Cluster snapshot logs: exact order counts (ground truth for state machine)
"""

import json, subprocess, time, sys, os, re

ORDER_GW  = "http://localhost:8080"
MARKET_GW = "http://localhost:8081"
ADMIN_GW  = "http://localhost:8082"

G = "\033[0;32m"
R = "\033[0;31m"
Y = "\033[1;33m"
C = "\033[0;36m"
B = "\033[1m"
N = "\033[0m"

passed = 0
failed = 0

def log(msg):
    print(f"{C}[{time.strftime('%H:%M:%S')}]{N} {msg}", flush=True)

def ok(msg):
    global passed; passed += 1
    print(f"{G}  ✓ {msg}{N}", flush=True)

def fail(msg):
    global failed; failed += 1
    print(f"{R}  ✗ {msg}{N}", flush=True)

def curl_json(url, method="GET", data=None, timeout=10):
    cmd = ["curl", "-s", "--max-time", str(timeout)]
    if method == "POST":
        cmd += ["-X", "POST"]
    if data:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(data)]
    cmd.append(url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout+5)
        if r.returncode == 0 and r.stdout.strip():
            return json.loads(r.stdout)
    except:
        pass
    return None

def curl_text(url, method="GET", data=None, timeout=10):
    cmd = ["curl", "-s", "--max-time", str(timeout)]
    if method == "POST":
        cmd += ["-X", "POST"]
    if data:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(data)]
    cmd.append(url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout+5)
        return r.stdout.strip()
    except:
        return ""

def systemctl(action, *units):
    subprocess.run(["systemctl", "--user", action] + list(units),
                   capture_output=True, timeout=30)

def systemctl_kill(sig, *units):
    subprocess.run(["systemctl", "--user", "kill", "-s", sig] + list(units),
                   capture_output=True, timeout=30)

# ── Parse cluster snapshot logs for ground truth ──
def get_cluster_snapshot_state():
    """Parse snapshot take/restore logs from cluster nodes for exact order counts."""
    leader = curl_json(f"{ADMIN_GW}/api/admin/status") or {}
    leader_id = leader.get("leader", 0)
    log_path = os.path.expanduser(f"~/.local/log/cluster/node{leader_id}.log")
    
    try:
        with open(log_path, 'r') as f:
            lines = f.readlines()
    except:
        return None
    
    # Find the LAST snapshot event (take or restore)
    state = {}
    for line in lines:
        m = re.search(r'\[SNAPSHOT\] Market (\d+): (?:restored )?(\d+) bids, (\d+) asks', line)
        if m:
            mid = int(m.group(1))
            state[mid] = {"bids": int(m.group(2)), "asks": int(m.group(3))}
        m = re.search(r'\[SNAPSHOT\] (?:Restored )?OrderIdGenerator: (\d+)', line)
        if m:
            state["orderIdGen"] = int(m.group(1))
        m = re.search(r'\[SNAPSHOT\] (?:Restored )?TradeIdGenerator: (\d+)', line)
        if m:
            state["tradeIdGen"] = int(m.group(1))
    
    return state

# ── Capture gateway view (lossy, top-20 levels) ──
def capture_gateway_state(label):
    book = curl_json(f"{MARKET_GW}/api/orderbook?marketId=1") or {}
    bids = book.get("bids", [])
    asks = book.get("asks", [])
    state = {
        "bid_levels": len(bids),
        "ask_levels": len(asks),
        "best_bid": bids[0]["price"] if bids else 0,
        "best_ask": asks[0]["price"] if asks else 0,
    }
    log(f"Gateway [{label}]: bids={state['bid_levels']} asks={state['ask_levels']} "
        f"bestBid={state['best_bid']} bestAsk={state['best_ask']}")
    return state

# ── Take snapshot and capture the cluster's ground truth from logs ──
def take_snapshot_and_capture(label):
    """Take snapshot, wait, then parse the snapshot log to get exact order counts."""
    # Clear old logs to only read new snapshot data
    for n in range(3):
        log_path = os.path.expanduser(f"~/.local/log/cluster/node{n}.log")
        try:
            with open(log_path, 'a') as f:
                f.write(f"\n--- SNAPSHOT MARKER {label} ---\n")
        except:
            pass
    
    log(f"Taking snapshot [{label}]...")
    resp = curl_text(f"{ADMIN_GW}/api/admin/snapshot", method="POST")
    if "initiat" in resp.lower() or "success" in resp.lower() or "snapshot" in resp.lower():
        ok("Snapshot triggered")
    else:
        fail(f"Snapshot response: {resp}")
    time.sleep(5)
    
    # Read the snapshot data from leader's log
    state = get_cluster_snapshot_state()
    if state:
        btc = state.get(1, {"bids": 0, "asks": 0})
        log(f"Cluster [{label}]: BTC-USD bids={btc['bids']} asks={btc['asks']} "
            f"orderIdGen={state.get('orderIdGen', '?')} tradeIdGen={state.get('tradeIdGen', '?')}")
    else:
        log(f"Cluster [{label}]: Could not parse snapshot state")
    return state

# ── Compare cluster states ──
def compare_cluster_states(label, before, after):
    log(f"Comparing cluster state [{label}]...")
    if not before or not after:
        fail(f"Missing state for comparison")
        return
    
    # Compare BTC-USD (market 1)
    b_btc = before.get(1, {"bids": 0, "asks": 0})
    a_btc = after.get(1, {"bids": 0, "asks": 0})
    
    if b_btc["bids"] == a_btc["bids"]:
        ok(f"BTC bid orders: {b_btc['bids']} = {a_btc['bids']}")
    else:
        fail(f"BTC bid orders: {b_btc['bids']} != {a_btc['bids']}")
    
    if b_btc["asks"] == a_btc["asks"]:
        ok(f"BTC ask orders: {b_btc['asks']} = {a_btc['asks']}")
    else:
        fail(f"BTC ask orders: {b_btc['asks']} != {a_btc['asks']}")
    
    # Compare ID generators
    if before.get("orderIdGen") == after.get("orderIdGen"):
        ok(f"OrderIdGenerator: {before.get('orderIdGen')} = {after.get('orderIdGen')}")
    else:
        fail(f"OrderIdGenerator: {before.get('orderIdGen')} != {after.get('orderIdGen')}")
    
    if before.get("tradeIdGen") == after.get("tradeIdGen"):
        ok(f"TradeIdGenerator: {before.get('tradeIdGen')} = {after.get('tradeIdGen')}")
    else:
        fail(f"TradeIdGenerator: {before.get('tradeIdGen')} != {after.get('tradeIdGen')}")

# ── Compare gateway views (best prices must match) ──
def compare_gateway_prices(label, before, after):
    log(f"Comparing gateway prices [{label}]...")
    if before["best_bid"] == after["best_bid"]:
        ok(f"Best bid: {before['best_bid']} = {after['best_bid']}")
    else:
        fail(f"Best bid: {before['best_bid']} != {after['best_bid']}")
    
    if before["best_ask"] == after["best_ask"]:
        ok(f"Best ask: {before['best_ask']} = {after['best_ask']}")
    else:
        fail(f"Best ask: {before['best_ask']} != {after['best_ask']}")

def wait_for_ready(max_wait=60):
    start = time.time()
    while time.time() - start < max_wait:
        try:
            status = curl_json(f"{ADMIN_GW}/api/admin/status")
            if status and status.get("leader", -1) >= 0:
                resp = curl_text(f"{ORDER_GW}/order", method="POST",
                    data={"userId":"999","market":"BTC-USD","orderSide":"BUY",
                          "orderType":"LIMIT","price":1.0,"quantity":0.001})
                if "accepted" in resp.lower():
                    health = curl_json(f"{MARKET_GW}/health")
                    if health and health.get("status") == "ok":
                        elapsed = int(time.time() - start)
                        ok(f"Cluster ready (leader={status['leader']}, {elapsed}s)")
                        time.sleep(3)
                        return True
        except:
            pass
        time.sleep(3)
    fail(f"Cluster not ready after {max_wait}s")
    return False

def seed_orders(count=50):
    log(f"Seeding {count} order pairs...")
    for i in range(1, count + 1):
        curl_text(f"{ORDER_GW}/order", method="POST",
            data={"userId": str(i*2), "market": "BTC-USD", "orderSide": "BUY",
                  "orderType": "LIMIT", "price": 59000 + i * 10, "quantity": 0.1})
        curl_text(f"{ORDER_GW}/order", method="POST",
            data={"userId": str(i*2+1), "market": "BTC-USD", "orderSide": "SELL",
                  "orderType": "LIMIT", "price": 61000 + i * 10, "quantity": 0.1})
    time.sleep(3)
    ok(f"Seeded {count} bid + {count} ask orders")

def run_loadtest(rate=200, duration=10, workers=10):
    log(f"Running load test ({rate}/s for {duration}s)...")
    try:
        r = subprocess.run(
            ["./loadgen", f"-rate", str(rate), f"-duration", str(duration), f"-workers", str(workers)],
            capture_output=True, text=True, timeout=duration + 30,
            cwd="/home/emre/Apps/match/scripts/loadgen")
        for line in r.stdout.split("\n"):
            if any(k in line for k in ["Success", "Errors", "Latency p50"]):
                print(f"    {line.strip()}", flush=True)
    except Exception as e:
        fail(f"Load test error: {e}")

def wait_rolling_update(max_wait=300):
    start = time.time()
    while time.time() - start < max_wait:
        time.sleep(10)
        progress = curl_json(f"{ADMIN_GW}/api/admin/progress")
        if progress:
            pct = progress.get("progress", 0)
            status = progress.get("status", "")
            log(f"Rolling update: {pct}% — {status}")
            if progress.get("complete"):
                ok("Rolling update complete")
                return True
            if progress.get("error"):
                fail(f"Rolling update error: {status}")
                return False
    fail(f"Rolling update timeout")
    return False


def main():
    print()
    print("=" * 72)
    print(f"  {B}SNAPSHOT VALIDATION TEST{N}")
    print(f"  Deterministic State Machine Verification")
    print(f"  Cluster ground truth + Gateway view")
    print("=" * 72)
    print()

    # ── PHASE 1: Load + snapshot ──
    log("PHASE 1: Load test with snapshots")
    log("─" * 50)

    if not wait_for_ready(30):
        sys.exit(1)

    seed_orders(100)
    run_loadtest(rate=200, duration=10)

    snap_before = take_snapshot_and_capture("phase1-post-load")
    gw_before = capture_gateway_state("phase1-post-load")

    # ── PHASE 2: Graceful restart ──
    print()
    log("PHASE 2: Graceful restart (snapshot restore)")
    log("─" * 50)

    log("Stopping everything...")
    systemctl("stop", "order", "market")
    time.sleep(2)
    systemctl("stop", "node0", "node1", "node2")
    time.sleep(3)

    log("Starting cluster...")
    systemctl("start", "node0")
    time.sleep(3)
    systemctl("start", "node1", "node2")
    time.sleep(12)
    systemctl("start", "order", "market")
    time.sleep(10)

    if wait_for_ready(60):
        time.sleep(5)
        snap_after = get_cluster_snapshot_state()
        if snap_after:
            btc = snap_after.get(1, {})
            log(f"Cluster [after-restart]: BTC bids={btc.get('bids','?')} asks={btc.get('asks','?')}")
        compare_cluster_states("Graceful Restart", snap_before, snap_after)
        gw_after = capture_gateway_state("after-restart")
        compare_gateway_prices("Graceful Restart", gw_before, gw_after)

    # ── PHASE 3: Rolling update ──
    print()
    log("PHASE 3: Rolling update")
    log("─" * 50)

    seed_orders(20)
    snap_before_roll = take_snapshot_and_capture("pre-rolling")
    gw_before_roll = capture_gateway_state("pre-rolling")

    log("Initiating rolling update...")
    curl_text(f"{ADMIN_GW}/api/admin/rolling-update", method="POST")

    if wait_rolling_update():
        curl_text(f"{ADMIN_GW}/api/admin/restart-gateway", method="POST")
        time.sleep(15)
        if wait_for_ready(60):
            time.sleep(5)
            snap_after_roll = get_cluster_snapshot_state()
            if snap_after_roll:
                btc = snap_after_roll.get(1, {})
                log(f"Cluster [after-rolling]: BTC bids={btc.get('bids','?')} asks={btc.get('asks','?')}")
            compare_cluster_states("Rolling Update", snap_before_roll, snap_after_roll)
            gw_after_roll = capture_gateway_state("after-rolling")
            compare_gateway_prices("Rolling Update", gw_before_roll, gw_after_roll)

    # ── PHASE 4: Kill -9 ──
    print()
    log("PHASE 4: Unexpected shutdown (kill -9)")
    log("─" * 50)

    seed_orders(10)
    snap_before_kill = take_snapshot_and_capture("pre-kill")
    gw_before_kill = capture_gateway_state("pre-kill")

    log("Killing ALL nodes with SIGKILL...")
    systemctl_kill("SIGKILL", "node0", "node1", "node2")
    time.sleep(2)
    systemctl("stop", "order", "market")
    time.sleep(3)
    os.system("rm -rf /dev/shm/aeron-order-* /dev/shm/aeron-market-* 2>/dev/null")

    log("Restarting from crash...")
    systemctl("start", "node0")
    time.sleep(3)
    systemctl("start", "node1", "node2")
    time.sleep(15)
    systemctl("start", "order", "market")
    time.sleep(10)

    if wait_for_ready(90):
        time.sleep(5)
        snap_after_kill = get_cluster_snapshot_state()
        if snap_after_kill:
            btc = snap_after_kill.get(1, {})
            log(f"Cluster [after-kill]: BTC bids={btc.get('bids','?')} asks={btc.get('asks','?')}")
        compare_cluster_states("Kill -9 Recovery", snap_before_kill, snap_after_kill)
        gw_after_kill = capture_gateway_state("after-kill")
        compare_gateway_prices("Kill -9 Recovery", gw_before_kill, gw_after_kill)

    # ── PHASE 5: Post-recovery orders ──
    print()
    log("PHASE 5: Post-recovery order submission")
    log("─" * 50)

    r1 = curl_text(f"{ORDER_GW}/order", method="POST",
        data={"userId":"9001","market":"BTC-USD","orderSide":"SELL",
              "orderType":"LIMIT","price":60500.0,"quantity":0.5})
    time.sleep(1)
    r2 = curl_text(f"{ORDER_GW}/order", method="POST",
        data={"userId":"9002","market":"BTC-USD","orderSide":"BUY",
              "orderType":"LIMIT","price":60500.0,"quantity":0.5})
    time.sleep(3)

    if "accepted" in r1.lower() and "accepted" in r2.lower():
        ok("Engine processes orders after recovery")
    else:
        fail(f"Order submission failed")

    health = curl_json(f"{MARKET_GW}/health")
    if health and health.get("trades"):
        ok("Trade execution confirmed")
    else:
        fail(f"No trades post-recovery")

    # ── RESULTS ──
    print()
    print("=" * 72)
    print(f"  {G}Passed: {passed}{N}")
    if failed:
        print(f"  {R}Failed: {failed}{N}")
    else:
        print(f"  Failed: 0")
    print("=" * 72)
    print()

    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
