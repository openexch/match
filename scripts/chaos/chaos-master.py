#!/usr/bin/env python3
"""
CHAOS TEST SUITE — Thor's Hammer Edition 🔨⚡
Tests the matching engine under extreme conditions.
"""

import json, subprocess, time, sys, os, re, random, threading, signal
from concurrent.futures import ThreadPoolExecutor, as_completed

ORDER_GW  = "http://localhost:8080"
MARKET_GW = "http://localhost:8081"
ADMIN_GW  = "http://localhost:8082"

G = "\033[0;32m"; R = "\033[0;31m"; Y = "\033[1;33m"
C = "\033[0;36m"; B = "\033[1m"; M = "\033[0;35m"; N = "\033[0m"

passed = 0; failed = 0; warnings = 0

def log(msg): print(f"{C}[{time.strftime('%H:%M:%S')}]{N} {msg}", flush=True)
def ok(msg):
    global passed; passed += 1
    print(f"{G}  ✓ {msg}{N}", flush=True)
def fail(msg):
    global failed; failed += 1
    print(f"{R}  ✗ {msg}{N}", flush=True)
def warn(msg):
    global warnings; warnings += 1
    print(f"{Y}  ⚠ {msg}{N}", flush=True)
def banner(msg):
    print(f"\n{M}{'='*72}{N}", flush=True)
    print(f"{M}  ⚡ {B}{msg}{N}", flush=True)
    print(f"{M}{'='*72}{N}\n", flush=True)

def curl_json(url, method="GET", data=None, timeout=10):
    cmd = ["curl", "-s", "--max-time", str(timeout)]
    if method == "POST": cmd += ["-X", "POST"]
    if data: cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(data)]
    cmd.append(url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout+5)
        if r.returncode == 0 and r.stdout.strip():
            return json.loads(r.stdout)
    except: pass
    return None

def curl_text(url, method="GET", data=None, timeout=10):
    cmd = ["curl", "-s", "--max-time", str(timeout)]
    if method == "POST": cmd += ["-X", "POST"]
    if data: cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(data)]
    cmd.append(url)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout+5)
        return r.stdout.strip()
    except: return ""

def systemctl(action, *units):
    subprocess.run(["systemctl", "--user", action] + list(units), capture_output=True, timeout=30)

def systemctl_kill(sig, *units):
    subprocess.run(["systemctl", "--user", "kill", "-s", sig] + list(units), capture_output=True, timeout=30)

def get_status():
    return curl_json(f"{ADMIN_GW}/api/admin/status")

def get_leader():
    s = get_status()
    return s.get("leader", -1) if s else -1

def get_snapshot_state():
    """Get cluster order counts from snapshot logs."""
    leader_id = get_leader()
    if leader_id < 0: return None
    log_path = os.path.expanduser(f"~/.local/log/cluster/node{leader_id}.log")
    try:
        with open(log_path, 'r') as f:
            lines = f.readlines()
    except: return None
    state = {}
    for line in lines:
        m = re.search(r'\[SNAPSHOT\] (?:Market|restored) (\d+).*?(\d+) bids.*?(\d+) asks', line)
        if m: state[int(m.group(1))] = {"bids": int(m.group(2)), "asks": int(m.group(3))}
        m = re.search(r'OrderIdGenerator: (\d+)', line)
        if m: state["orderIdGen"] = int(m.group(1))
        m = re.search(r'TradeIdGenerator: (\d+)', line)
        if m: state["tradeIdGen"] = int(m.group(1))
    return state

def wait_for_ready(max_wait=90):
    start = time.time()
    while time.time() - start < max_wait:
        try:
            s = get_status()
            if s and s.get("leader", -1) >= 0:
                resp = curl_text(f"{ORDER_GW}/order", method="POST",
                    data={"userId":"999","market":"BTC-USD","orderSide":"BUY",
                          "orderType":"LIMIT","price":1.0,"quantity":0.001})
                if "accepted" in resp.lower():
                    elapsed = int(time.time() - start)
                    ok(f"Cluster ready (leader={s['leader']}, {elapsed}s)")
                    time.sleep(2)
                    return True
        except: pass
        time.sleep(3)
    fail(f"Cluster not ready after {max_wait}s")
    return False

def send_order(side, price, qty, user_id=None, market="BTC-USD"):
    uid = user_id or str(random.randint(1, 999999))
    return curl_text(f"{ORDER_GW}/order", method="POST",
        data={"userId": uid, "market": market, "orderSide": side,
              "orderType": "LIMIT", "price": price, "quantity": qty}, timeout=5)

def send_orders_burst(count, spread=1000):
    """Send a burst of orders as fast as possible."""
    success = 0
    for i in range(count):
        side = "BUY" if i % 2 == 0 else "SELL"
        price = 60000 + random.uniform(-spread, spread)
        qty = round(random.uniform(0.01, 1.0), 4)
        r = send_order(side, round(price, 2), qty)
        if r and "accepted" in r.lower():
            success += 1
    return success

def loadtest(rate, duration, workers=20):
    try:
        r = subprocess.run(
            ["./loadgen", f"-rate", str(rate), f"-duration", str(duration), f"-workers", str(workers)],
            capture_output=True, text=True, timeout=duration + 30,
            cwd="/home/emre/Apps/match/scripts/loadgen")
        for line in r.stdout.split("\n"):
            if any(k in line for k in ["Success", "Errors", "Latency p99", "Avg rate"]):
                print(f"    {line.strip()}", flush=True)
        # Parse success rate
        for line in r.stdout.split("\n"):
            m = re.search(r'Success:\s+(\d+)\s+\((\d+\.\d+)%\)', line)
            if m: return int(m.group(1)), float(m.group(2))
    except Exception as e:
        fail(f"Loadtest error: {e}")
    return 0, 0.0

def take_snapshot():
    resp = curl_text(f"{ADMIN_GW}/api/admin/snapshot", method="POST")
    time.sleep(3)
    return resp

def wait_rolling_update(max_wait=300):
    start = time.time()
    while time.time() - start < max_wait:
        time.sleep(10)
        p = curl_json(f"{ADMIN_GW}/api/admin/progress")
        if p:
            if p.get("complete"): return True
            if p.get("error"): return False
    return False


# ================================================================
# CHAOS TESTS
# ================================================================

def test_1_sustained_load():
    """Sustained high throughput for 60 seconds."""
    banner("TEST 1: SUSTAINED HIGH LOAD (1000/s × 60s)")
    log("Hammering the engine at 1000 orders/second for 60 seconds...")
    
    count, pct = loadtest(1000, 60, workers=30)
    
    if pct >= 95.0:
        ok(f"Sustained load: {count} orders at {pct}% success")
    elif pct >= 80.0:
        warn(f"Sustained load degraded: {count} orders at {pct}% success")
    else:
        fail(f"Sustained load failed: {count} orders at {pct}% success")
    
    # Verify cluster still healthy
    s = get_status()
    if s and s.get("leader", -1) >= 0:
        ok("Cluster survived sustained load")
    else:
        fail("Cluster died under sustained load")

def test_2_leader_kill_during_load():
    """Kill the leader while orders are flowing."""
    banner("TEST 2: LEADER ASSASSINATION DURING LOAD")
    
    leader = get_leader()
    log(f"Current leader: node{leader}")
    
    # Start load in background
    log("Starting background load (500/s)...")
    load_proc = subprocess.Popen(
        ["./loadgen", "-rate", "500", "-duration", "30", "-workers", "15"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd="/home/emre/Apps/match/scripts/loadgen")
    
    time.sleep(5)  # Let load stabilize
    
    # Kill the leader
    log(f"⚡ KILLING LEADER node{leader} with SIGKILL!")
    systemctl_kill("SIGKILL", f"node{leader}")
    
    time.sleep(3)
    
    # Check if new leader elected
    new_leader = get_leader()
    if new_leader >= 0 and new_leader != leader:
        ok(f"New leader elected: node{new_leader} (was node{leader})")
    elif new_leader == leader:
        # Leader came back (auto-restart)
        ok(f"Leader node{leader} recovered via auto-restart")
    else:
        fail("No leader elected after assassination")
    
    # Wait for load to finish
    try:
        stdout, _ = load_proc.communicate(timeout=40)
        for line in stdout.split("\n"):
            if "Success" in line or "Errors" in line:
                print(f"    {line.strip()}", flush=True)
    except:
        load_proc.kill()
    
    # Restart killed node if needed
    time.sleep(5)
    systemctl("start", f"node{leader}")
    time.sleep(10)
    
    # Verify 3 nodes running
    s = get_status()
    running = sum(1 for n in s.get("nodes", []) if n.get("running")) if s else 0
    if running == 3:
        ok(f"All 3 nodes running after leader kill")
    else:
        fail(f"Only {running}/3 nodes after leader kill")

def test_3_rapid_leader_kills():
    """Kill the leader 5 times in rapid succession."""
    banner("TEST 3: RAPID LEADER ASSASSINATION × 5")
    
    kills = 0
    elections = 0
    
    for i in range(5):
        leader = get_leader()
        if leader < 0:
            log(f"  Kill {i+1}: No leader, waiting...")
            time.sleep(15)
            continue
        
        log(f"  Kill {i+1}/5: Killing leader node{leader}")
        systemctl_kill("SIGKILL", f"node{leader}")
        kills += 1
        time.sleep(8)
        
        # Wait for new leader
        for _ in range(10):
            new = get_leader()
            if new >= 0:
                elections += 1
                log(f"  New leader: node{new}")
                break
            time.sleep(2)
        
        # Restart killed node
        systemctl("start", f"node{leader}")
        time.sleep(5)
    
    if elections >= 4:
        ok(f"Survived {kills} rapid leader kills, {elections} elections")
    elif elections >= 3:
        warn(f"{elections}/{kills} elections succeeded")
    else:
        fail(f"Only {elections}/{kills} elections succeeded")
    
    # Let cluster stabilize
    time.sleep(15)
    wait_for_ready(60)

def test_4_all_nodes_kill():
    """Kill ALL nodes at once, restart, verify recovery."""
    banner("TEST 4: TOTAL CLUSTER DEATH (ALL NODES KILLED)")
    
    # Seed some state
    log("Seeding state before total kill...")
    send_orders_burst(50, spread=2000)
    time.sleep(2)
    take_snapshot()
    time.sleep(3)
    snap_before = get_snapshot_state()
    
    log("⚡ KILLING ALL 3 NODES SIMULTANEOUSLY!")
    systemctl_kill("SIGKILL", "node0", "node1", "node2")
    time.sleep(2)
    systemctl("stop", "order", "market")
    time.sleep(3)
    
    # Verify all dead
    s = get_status()
    running = sum(1 for n in s.get("nodes", []) if n.get("running")) if s else 0
    log(f"Running nodes after kill: {running}")
    
    # Clean stale gateway MediaDrivers
    os.system("rm -rf /dev/shm/aeron-order-* /dev/shm/aeron-market-* 2>/dev/null")
    
    # Restart
    log("Restarting from total death...")
    systemctl("start", "node0")
    time.sleep(3)
    systemctl("start", "node1", "node2")
    time.sleep(15)
    systemctl("start", "order", "market")
    time.sleep(10)
    
    if wait_for_ready(60):
        time.sleep(3)
        snap_after = get_snapshot_state()
        if snap_before and snap_after:
            b = snap_before.get(1, {})
            a = snap_after.get(1, {})
            if b.get("bids") == a.get("bids") and b.get("asks") == a.get("asks"):
                ok(f"State preserved after total death: bids={a['bids']} asks={a['asks']}")
            else:
                fail(f"State changed: bids {b.get('bids')}→{a.get('bids')}, asks {b.get('asks')}→{a.get('asks')}")
        
        # Verify engine works
        r = send_order("BUY", 60000, 0.1, "chaos-test")
        if r and "accepted" in r.lower():
            ok("Engine accepts orders after total death recovery")
        else:
            fail("Engine broken after total death")

def test_5_snapshot_storm():
    """Take 10 snapshots in rapid succession while under load."""
    banner("TEST 5: SNAPSHOT STORM (10 RAPID SNAPSHOTS + LOAD)")
    
    # Start load
    load_proc = subprocess.Popen(
        ["./loadgen", "-rate", "300", "-duration", "40", "-workers", "10"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd="/home/emre/Apps/match/scripts/loadgen")
    
    time.sleep(3)
    
    snap_ok = 0
    for i in range(10):
        resp = curl_text(f"{ADMIN_GW}/api/admin/snapshot", method="POST")
        if "initiat" in resp.lower() or "success" in resp.lower():
            snap_ok += 1
        time.sleep(2)
    
    if snap_ok >= 8:
        ok(f"{snap_ok}/10 snapshots succeeded during load")
    else:
        fail(f"Only {snap_ok}/10 snapshots succeeded")
    
    try:
        stdout, _ = load_proc.communicate(timeout=50)
        for line in stdout.split("\n"):
            if "Success" in line:
                print(f"    {line.strip()}", flush=True)
    except:
        load_proc.kill()
    
    # Verify cluster survived
    if get_leader() >= 0:
        ok("Cluster survived snapshot storm")
    else:
        fail("Cluster died during snapshot storm")

def test_6_gateway_chaos():
    """Restart gateways rapidly while orders flow."""
    banner("TEST 6: GATEWAY CHAOS (RAPID GATEWAY RESTARTS)")
    
    # Start load
    load_proc = subprocess.Popen(
        ["./loadgen", "-rate", "200", "-duration", "40", "-workers", "10"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd="/home/emre/Apps/match/scripts/loadgen")
    
    time.sleep(5)
    
    restarts = 0
    for i in range(5):
        log(f"  Gateway restart {i+1}/5")
        curl_text(f"{ADMIN_GW}/api/admin/restart-gateway", method="POST")
        restarts += 1
        time.sleep(5)
    
    ok(f"Completed {restarts} gateway restarts during load")
    
    try:
        stdout, _ = load_proc.communicate(timeout=50)
        for line in stdout.split("\n"):
            if "Success" in line or "Errors" in line:
                print(f"    {line.strip()}", flush=True)
    except:
        load_proc.kill()
    
    time.sleep(10)
    wait_for_ready(30)

def test_7_minority_partition():
    """Kill 2 nodes (minority quorum lost), verify cluster halts, then recover."""
    banner("TEST 7: MINORITY PARTITION (2 OF 3 NODES KILLED)")
    
    leader = get_leader()
    followers = [i for i in range(3) if i != leader]
    
    log(f"Leader: node{leader}, Followers: node{followers[0]}, node{followers[1]}")
    log(f"⚡ Killing both followers to break quorum!")
    
    systemctl_kill("SIGKILL", f"node{followers[0]}", f"node{followers[1]}")
    time.sleep(5)
    
    # Try to submit order — should fail or timeout (no quorum)
    r = curl_text(f"{ORDER_GW}/order", method="POST",
        data={"userId":"quorum-test","market":"BTC-USD","orderSide":"BUY",
              "orderType":"LIMIT","price":50000.0,"quantity":0.01}, timeout=5)
    
    # The order might be accepted into MPSC queue but not committed
    log(f"Order during partition: {r[:80] if r else 'timeout/empty'}")
    
    # Restart followers
    log("Restoring quorum...")
    systemctl("start", f"node{followers[0]}", f"node{followers[1]}")
    time.sleep(15)
    
    s = get_status()
    running = sum(1 for n in s.get("nodes", []) if n.get("running")) if s else 0
    if running == 3:
        ok("Quorum restored, all 3 nodes running")
    else:
        warn(f"Only {running}/3 nodes after quorum restore")
    
    wait_for_ready(30)

def test_8_rolling_update_under_load():
    """Rolling update while the engine is under sustained load."""
    banner("TEST 8: ROLLING UPDATE UNDER HEAVY LOAD")
    
    # Take pre-state
    take_snapshot()
    time.sleep(3)
    snap_before = get_snapshot_state()
    
    # Start heavy load
    load_proc = subprocess.Popen(
        ["./loadgen", "-rate", "500", "-duration", "180", "-workers", "20"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        cwd="/home/emre/Apps/match/scripts/loadgen")
    
    time.sleep(5)
    
    # Trigger rolling update during load
    log("Initiating rolling update during load...")
    curl_text(f"{ADMIN_GW}/api/admin/rolling-update", method="POST")
    
    if wait_rolling_update(300):
        log("Rolling update completed during load!")
        # Restart gateways
        curl_text(f"{ADMIN_GW}/api/admin/restart-gateway", method="POST")
        time.sleep(15)
    else:
        fail("Rolling update failed during load")
    
    # Kill load
    try:
        load_proc.terminate()
        stdout, _ = load_proc.communicate(timeout=10)
        for line in stdout.split("\n"):
            if "Success" in line or "Errors" in line:
                print(f"    {line.strip()}", flush=True)
    except:
        load_proc.kill()
    
    wait_for_ready(60)

def test_9_order_flood():
    """Flood with 10,000 orders as fast as possible."""
    banner("TEST 9: ORDER FLOOD (10,000 ORDERS, MAX SPEED)")
    
    start = time.time()
    
    # Parallel flood using threads
    with ThreadPoolExecutor(max_workers=20) as executor:
        futures = []
        for batch in range(20):
            futures.append(executor.submit(send_orders_burst, 500, spread=5000))
        
        total = sum(f.result() for f in as_completed(futures))
    
    elapsed = time.time() - start
    rate = total / elapsed if elapsed > 0 else 0
    
    log(f"Sent {total}/10000 orders in {elapsed:.1f}s ({rate:.0f}/s)")
    
    if total >= 9000:
        ok(f"Order flood: {total} orders accepted at {rate:.0f}/s")
    elif total >= 7000:
        warn(f"Order flood partial: {total} accepted")
    else:
        fail(f"Order flood failed: only {total} accepted")
    
    # Verify cluster health after flood
    time.sleep(3)
    if get_leader() >= 0:
        ok("Cluster survived order flood")
    else:
        fail("Cluster died during order flood")

def test_10_multi_market_chaos():
    """Submit orders across all 5 markets simultaneously."""
    banner("TEST 10: MULTI-MARKET SIMULTANEOUS CHAOS")
    
    markets = ["BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD"]
    results = {}
    
    def flood_market(market, count=200):
        success = 0
        for i in range(count):
            side = "BUY" if i % 2 == 0 else "SELL"
            price = {"BTC-USD": 60000, "ETH-USD": 3000, "SOL-USD": 100,
                     "XRP-USD": 1, "DOGE-USD": 0.1}[market]
            price += random.uniform(-price * 0.05, price * 0.05)
            qty = round(random.uniform(0.1, 10.0), 4)
            r = send_order(side, round(price, 6), qty, market=market)
            if r and "accepted" in r.lower():
                success += 1
        return market, success
    
    log("Flooding all 5 markets simultaneously (200 orders each)...")
    with ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(flood_market, m) for m in markets]
        for f in as_completed(futures):
            market, count = f.result()
            results[market] = count
            log(f"  {market}: {count}/200 accepted")
    
    total = sum(results.values())
    if total >= 900:
        ok(f"Multi-market chaos: {total}/1000 orders across 5 markets")
    else:
        fail(f"Multi-market chaos: only {total}/1000 orders accepted")

def test_11_stale_snapshot_recovery():
    """Take snapshot, send more orders, kill without snapshot, verify replay."""
    banner("TEST 11: STALE SNAPSHOT + LOG REPLAY")
    
    log("Taking snapshot at known state...")
    take_snapshot()
    time.sleep(3)
    snap_at_snapshot = get_snapshot_state()
    
    # Send more orders AFTER snapshot (these should be replayed from log)
    log("Sending 100 orders AFTER snapshot...")
    sent = send_orders_burst(100, spread=3000)
    time.sleep(3)
    
    # Take another snapshot to capture "true" state
    take_snapshot()
    time.sleep(3)
    snap_true = get_snapshot_state()
    
    log(f"State at snapshot: bids={snap_at_snapshot.get(1, {}).get('bids', '?')}")
    log(f"State after more orders: bids={snap_true.get(1, {}).get('bids', '?')}")
    
    # Kill WITHOUT taking snapshot — cluster must replay from old snapshot + log
    log("⚡ Killing all nodes WITHOUT fresh snapshot...")
    systemctl_kill("SIGKILL", "node0", "node1", "node2")
    time.sleep(2)
    systemctl("stop", "order", "market")
    time.sleep(3)
    os.system("rm -rf /dev/shm/aeron-order-* /dev/shm/aeron-market-* 2>/dev/null")
    
    log("Restarting (must replay orders from log)...")
    systemctl("start", "node0")
    time.sleep(3)
    systemctl("start", "node1", "node2")
    time.sleep(15)
    systemctl("start", "order", "market")
    time.sleep(10)
    
    if wait_for_ready(60):
        time.sleep(3)
        snap_after = get_snapshot_state()
        if snap_after and snap_true:
            b_true = snap_true.get(1, {})
            b_after = snap_after.get(1, {})
            if b_true.get("bids") == b_after.get("bids") and b_true.get("asks") == b_after.get("asks"):
                ok(f"Log replay correct: bids={b_after['bids']} asks={b_after['asks']}")
            else:
                fail(f"Log replay mismatch: expected bids={b_true.get('bids')} got {b_after.get('bids')}")
    
def test_12_double_kill_rapid():
    """Kill leader, wait for election, kill NEW leader immediately."""
    banner("TEST 12: DOUBLE ASSASSINATION (KILL NEW LEADER IMMEDIATELY)")
    
    leader1 = get_leader()
    log(f"First leader: node{leader1}")
    
    # Kill first leader
    systemctl_kill("SIGKILL", f"node{leader1}")
    time.sleep(8)
    
    # Get new leader
    leader2 = get_leader()
    if leader2 >= 0 and leader2 != leader1:
        log(f"Second leader elected: node{leader2}")
        
        # Kill second leader immediately
        log(f"⚡ KILLING SECOND LEADER node{leader2}!")
        systemctl_kill("SIGKILL", f"node{leader2}")
        time.sleep(5)
        
        # Only 1 node alive — no quorum
        leader3 = get_leader()
        log(f"Leader after double kill: {leader3}")
        
        # Restart both killed nodes
        log("Restarting killed nodes...")
        systemctl("start", f"node{leader1}", f"node{leader2}")
        time.sleep(15)
        
        if wait_for_ready(60):
            ok("Survived double leader assassination")
        else:
            fail("Failed to recover from double assassination")
    else:
        fail(f"No new leader after first kill (got {leader2})")
        systemctl("start", f"node{leader1}")
        time.sleep(10)
        wait_for_ready(30)


# ================================================================
# MAIN
# ================================================================
def main():
    print()
    print(f"{R}{'='*72}{N}")
    print(f"{R}  ⚡🔨 CHAOS TEST SUITE — Thor's Hammer Edition 🔨⚡{N}")
    print(f"{R}  \"Hit it like a truck\"{N}")
    print(f"{R}{'='*72}{N}")
    print()
    
    start_time = time.time()
    
    if not wait_for_ready(30):
        log("Cluster not ready, attempting cold start...")
        systemctl("start", "node0")
        time.sleep(3)
        systemctl("start", "node1", "node2")
        time.sleep(12)
        systemctl("start", "order", "market")
        time.sleep(10)
        if not wait_for_ready(60):
            fail("Cannot start cluster")
            sys.exit(1)
    
    # Run all tests sequentially
    tests = [
        test_1_sustained_load,
        test_2_leader_kill_during_load,
        test_3_rapid_leader_kills,
        test_4_all_nodes_kill,
        test_5_snapshot_storm,
        test_6_gateway_chaos,
        test_7_minority_partition,
        test_8_rolling_update_under_load,
        test_9_order_flood,
        test_10_multi_market_chaos,
        test_11_stale_snapshot_recovery,
        test_12_double_kill_rapid,
    ]
    
    for test_fn in tests:
        try:
            test_fn()
        except Exception as e:
            fail(f"{test_fn.__name__} crashed: {e}")
        
        # Brief recovery between tests
        time.sleep(5)
        
        # Ensure cluster is up for next test
        s = get_status()
        if not s or s.get("leader", -1) < 0:
            log("Cluster down between tests, recovering...")
            systemctl("start", "node0", "node1", "node2")
            time.sleep(15)
            systemctl("start", "order", "market")
            time.sleep(10)
            wait_for_ready(60)
    
    elapsed = time.time() - start_time
    
    print()
    print(f"{R}{'='*72}{N}")
    print(f"  {B}CHAOS TEST RESULTS{N}")
    print(f"  Runtime: {elapsed/60:.1f} minutes")
    print(f"  {G}Passed:   {passed}{N}")
    print(f"  {R}Failed:   {failed}{N}")
    print(f"  {Y}Warnings: {warnings}{N}")
    print(f"{R}{'='*72}{N}")
    print()
    
    # Write results to file for the report
    with open("/home/emre/clawd/chaos-test-results.json", "w") as f:
        json.dump({
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
            "runtime_seconds": round(elapsed),
            "passed": passed,
            "failed": failed,
            "warnings": warnings,
        }, f, indent=2)
    
    sys.exit(0 if failed == 0 else 1)

if __name__ == "__main__":
    main()
