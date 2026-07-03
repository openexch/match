#!/usr/bin/env python3
"""
Bug #9 repro — OMS HTTP load driver (seed / run / snapshot / cancel).

Drives crossing LIMIT orders through the OMS REST API (the path that moves balances),
on BTC-USD (marketId 1, $1 tick). The match-loadtest bypasses OMS, so we cannot use it
to exercise balances — hence this driver.

Modes:
  seed     deposit USD + BTC to users [base, base+n)
  run      post crossing LIMIT orders at a target rate for a duration; log submitted.jsonl
  snapshot dump per-user balances to a file (use before and after the run)
  cancel   cancel all open orders for the users (release holds before reconciling)

Prices are whole dollars clustered tightly around --mid so both sides match frequently.
Stdlib only.
"""
import argparse, json, random, sys, threading, time
import http.client
from urllib.parse import urlparse

# OMS closes the connection after each response (Connection: close), so we open a
# fresh connection per request rather than reusing one (which breaks with BrokenPipe).
# Retries on transient errors (timeouts under load) so a single hiccup doesn't abort the run.
def _req(base, method, path, body=None, attempts=4):
    u = urlparse(base)
    last = None
    for k in range(attempts):
        c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=10)
        try:
            headers = {"Content-Type": "application/json"} if body is not None else {}
            c.request(method, path, json.dumps(body) if body is not None else None, headers)
            r = c.getresponse(); data = r.read()
            return r.status, data
        except Exception as e:
            last = e
            time.sleep(0.2 * (k + 1))
        finally:
            c.close()
    raise last

def post(base, path, body):  return _req(base, "POST", path, body)
def get(base, path):         return _req(base, "GET", path)
def delete(base, path):      return _req(base, "DELETE", path)

def users(args):
    return range(args.base, args.base + args.n)

def cmd_seed(args):
    ok = 0
    for u in users(args):
        post(args.url, f"/api/v1/accounts/{u}/deposit", {"assetId": 0, "amount": args.usd})
        post(args.url, f"/api/v1/accounts/{u}/deposit", {"assetId": 1, "amount": args.btc})
        ok += 1
    print(f"[seed] deposited USD={args.usd} BTC={args.btc} to {ok} users [{args.base},{args.base+args.n})")

def cmd_snapshot(args):
    out = {}
    for u in users(args):
        st, data = get(args.url, f"/api/v1/accounts/{u}")
        if st == 200:
            assets = {a["assetId"]: a["total"] for a in json.loads(data).get("assets", [])}
            out[str(u)] = {"USD": assets.get(0, 0.0), "BTC": assets.get(1, 0.0)}
    with open(args.out, "w") as f:
        json.dump(out, f, indent=0)
    print(f"[snapshot] wrote balances for {len(out)} users -> {args.out}")

def cmd_cancel(args):
    cancelled = 0
    for u in users(args):
        st, data = get(args.url, f"/api/v1/orders?userId={u}")
        if st != 200:
            continue
        try:
            orders = json.loads(data)
        except Exception:
            continue
        for o in orders:
            status = o.get("status", "")
            if status in ("FILLED", "CANCELLED", "REJECTED", "EXPIRED"):
                continue
            oid = o.get("omsOrderId")
            if oid is not None:
                delete(args.url, f"/api/v1/orders/{oid}")
                cancelled += 1
    print(f"[cancel] cancelled {cancelled} open orders")

def cmd_run(args):
    submitted_lock = threading.Lock()
    f = open(args.log, "a")
    stop_at = time.time() + args.duration
    per_thread_rate = max(1, args.rate // args.threads)
    counters = {"accepted": 0, "rejected": 0, "error": 0}

    def worker(tid):
        rng = random.Random(1000 + tid)
        interval = 1.0 / per_thread_rate if per_thread_rate > 0 else 0
        while time.time() < stop_at:
            t0 = time.time()
            u = rng.randrange(args.base, args.base + args.n)
            side = "BUY" if rng.random() < 0.5 else "SELL"
            price = rng.randint(args.mid - args.spread, args.mid + args.spread)  # whole-$ tick
            qty = round(rng.uniform(args.qmin, args.qmax), 6)
            body = {"userId": u, "marketId": 1, "side": side, "orderType": "LIMIT",
                    "timeInForce": "GTC", "price": price, "quantity": qty}
            try:
                st, data = post(args.url, "/api/v1/orders", body)
                if st == 201 or (st == 200):
                    resp = json.loads(data)
                    if resp.get("accepted"):
                        rec = {"ts": int(time.time()*1000), "omsOrderId": resp.get("omsOrderId"),
                               "userId": u, "side": side, "price": price, "quantity": qty,
                               "status": resp.get("status")}
                        with submitted_lock:
                            f.write(json.dumps(rec) + "\n")
                            counters["accepted"] += 1
                    else:
                        counters["rejected"] += 1
                else:
                    counters["rejected"] += 1
            except Exception:
                counters["error"] += 1
            dt = time.time() - t0
            if interval - dt > 0:
                time.sleep(interval - dt)

    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(args.threads)]
    for t in threads: t.start()
    last = time.time()
    while time.time() < stop_at:
        time.sleep(2)
        now = time.time()
        if now - last >= 5:
            last = now
            print(f"[run] accepted={counters['accepted']} rejected={counters['rejected']} "
                  f"err={counters['error']} t-{int(stop_at-now)}s", flush=True)
    for t in threads: t.join(timeout=5)
    f.flush(); f.close()
    print(f"[run] DONE accepted={counters['accepted']} rejected={counters['rejected']} err={counters['error']}")

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["seed", "run", "snapshot", "cancel"])
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--base", type=int, default=700000, help="first userId")
    ap.add_argument("--n", type=int, default=30, help="number of users")
    ap.add_argument("--usd", type=float, default=1e8)
    ap.add_argument("--btc", type=float, default=10000.0)
    ap.add_argument("--rate", type=int, default=200, help="orders/sec total")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--duration", type=int, default=90, help="run seconds")
    ap.add_argument("--mid", type=int, default=100000)
    ap.add_argument("--spread", type=int, default=3, help="+/- whole-$ around mid")
    ap.add_argument("--qmin", type=float, default=0.01)
    ap.add_argument("--qmax", type=float, default=0.30)
    ap.add_argument("--log", default="submitted.jsonl")
    ap.add_argument("--out", default="balances.json")
    args = ap.parse_args()
    {"seed": cmd_seed, "run": cmd_run, "snapshot": cmd_snapshot, "cancel": cmd_cancel}[args.mode](args)

if __name__ == "__main__":
    main()
