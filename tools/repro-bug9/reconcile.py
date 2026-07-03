#!/usr/bin/env python3
"""
Bug #9 repro — reconciler.

Builds ground truth from the independent observer's TradeExecution capture, compares it to
what the OMS actually did (balances via REST, per-order filledQty via REST), and classifies
each discrepancy: MISSED_FILL (OMS under-credited), DUPLICATE_FILL (over-credited),
FILLEDQTY_MISCOUNT (per-order fill qty wrong). Also checks tradeId contiguity (gaps) and
correlates discrepancies with switchover/reconnect windows.

Observer price/quantity are SBE fixed-point longs (scale 1e8). All output is real units.

Usage:
  reconcile.py --dir <runDir> --url http://localhost:8080 \
      --initial balances-initial.json --final balances-final.json
"""
import argparse, json, os, sys
import http.client
from urllib.parse import urlparse

SCALE = 1e8
USD, BTC = "USD", "BTC"

def load_jsonl(path):
    rows = []
    if not os.path.exists(path):
        return rows
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                try: rows.append(json.loads(line))
                except Exception: pass
    return rows

def load_json(path):
    with open(path) as f:
        return json.load(f)

def get_order(url, oid):
    u = urlparse(url)
    c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=10)
    try:
        c.request("GET", f"/api/v1/orders/{oid}")
        r = c.getresponse(); data = r.read()
        if r.status == 200:
            return json.loads(data)
    except Exception:
        return None
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--initial", default="balances-initial.json")
    ap.add_argument("--final", default="balances-final.json")
    ap.add_argument("--usd-tol", type=float, default=0.5)
    ap.add_argument("--btc-tol", type=float, default=1e-5)
    args = ap.parse_args()

    d = args.dir
    trades_raw = load_jsonl(os.path.join(d, "observer-trades.jsonl"))
    events = load_jsonl(os.path.join(d, "observer-events.jsonl"))
    submitted = load_jsonl(os.path.join(d, "submitted.jsonl"))
    initial = load_json(os.path.join(d, args.initial))
    final = load_json(os.path.join(d, args.final))

    test_users = set(int(u) for u in initial.keys())

    # --- tradeId contiguity (dedup observer self-duplicates) ---
    seen_tid = {}
    obs_dups = 0
    for t in trades_raw:
        tid = t["tradeId"]
        if tid in seen_tid:
            obs_dups += 1
        else:
            seen_tid[tid] = t
    tids = sorted(seen_tid.keys())
    gaps = []
    if tids:
        for i in range(1, len(tids)):
            if tids[i] != tids[i-1] + 1:
                gaps.append([tids[i-1] + 1, tids[i] - 1])
    contiguity = {
        "uniqueTrades": len(tids),
        "observerDuplicateRows": obs_dups,
        "minTradeId": tids[0] if tids else None,
        "maxTradeId": tids[-1] if tids else None,
        "missingInRange": (tids[-1] - tids[0] + 1 - len(tids)) if tids else 0,
        "gapRanges": gaps[:50],
    }

    # --- expected per-user deltas + per-order filled qty from ground truth ---
    exp = {u: {USD: 0.0, BTC: 0.0} for u in test_users}
    order_truth_filled = {}  # omsOrderId -> filled base qty
    for t in seen_tid.values():
        base = t["quantity"] / SCALE
        quote = (t["price"] / SCALE) * (t["quantity"] / SCALE)
        taker_buy = (t["takerSideRaw"] == 0)  # BID=0 buy, ASK=1 sell
        buyer = t["takerUserId"] if taker_buy else t["makerUserId"]
        seller = t["makerUserId"] if taker_buy else t["takerUserId"]
        buyer_oms = t["takerOmsOrderId"] if taker_buy else t["makerOmsOrderId"]
        seller_oms = t["makerOmsOrderId"] if taker_buy else t["takerOmsOrderId"]
        if buyer in exp:
            exp[buyer][BTC] += base; exp[buyer][USD] -= quote
        if seller in exp:
            exp[seller][BTC] -= base; exp[seller][USD] += quote
        order_truth_filled[buyer_oms] = order_truth_filled.get(buyer_oms, 0.0) + base
        order_truth_filled[seller_oms] = order_truth_filled.get(seller_oms, 0.0) + base

    # --- compare balances ---
    user_findings = []
    for u in sorted(test_users):
        iu, fu = initial.get(str(u), {}), final.get(str(u), {})
        act = {USD: fu.get(USD, 0.0) - iu.get(USD, 0.0), BTC: fu.get(BTC, 0.0) - iu.get(BTC, 0.0)}
        e = exp[u]
        d_usd = act[USD] - e[USD]; d_btc = act[BTC] - e[BTC]
        ok = abs(d_usd) <= args.usd_tol and abs(d_btc) <= args.btc_tol
        verdict = "OK"
        if not ok:
            under = (d_usd < -args.usd_tol) or (d_btc < -args.btc_tol)
            over = (d_usd > args.usd_tol) or (d_btc > args.btc_tol)
            if under and not over: verdict = "UNDER_CREDITED(MISSED_FILL?)"
            elif over and not under: verdict = "OVER_CREDITED(DUPLICATE_FILL?)"
            else: verdict = "ANOMALY"
        if verdict != "OK":
            user_findings.append({"userId": u, "verdict": verdict,
                "expectedUSD": round(e[USD], 6), "actualUSD": round(act[USD], 6), "dUSD": round(d_usd, 6),
                "expectedBTC": round(e[BTC], 8), "actualBTC": round(act[BTC], 8), "dBTC": round(d_btc, 8)})

    # --- per-order filledQty miscount (only orders that actually had fills) ---
    submitted_ids = {r["omsOrderId"]: r for r in submitted if r.get("omsOrderId") is not None}
    order_findings = []
    checked = 0
    for oid, truth in order_truth_filled.items():
        if oid not in submitted_ids:
            continue  # order not from our test set
        checked += 1
        o = get_order(args.url, oid)
        if o is None:
            # terminal/evicted: if fully filled, oms_filled == submitted quantity
            oms_filled = submitted_ids[oid]["quantity"]
            status = "EVICTED/404"
        else:
            oms_filled = o.get("filledQty", 0.0)
            status = o.get("status", "?")
        if abs(oms_filled - truth) > args.btc_tol:
            order_findings.append({"omsOrderId": oid, "status": status,
                "omsFilledQty": round(oms_filled, 8), "truthFilledQty": round(truth, 8),
                "diff": round(oms_filled - truth, 8)})

    # --- engine-stream divergence: OrderStatus (coalesced/lossy) vs TradeExecution (true) ---
    # Both are cluster egress. OMS derives filledQty from OrderStatus; this isolates the mechanism.
    status_rows = load_jsonl(os.path.join(d, "observer-status.jsonl"))
    status_last = {}  # omsOrderId -> final filledQty from the OrderStatus stream
    for s in status_rows:
        status_last[s["omsOrderId"]] = s["filledQty"] / SCALE
    engine_divergent = 0
    engine_examples = []
    for oid, truth in order_truth_filled.items():
        if oid in status_last and abs(status_last[oid] - truth) > args.btc_tol:
            engine_divergent += 1
            if len(engine_examples) < 20:
                engine_examples.append({"omsOrderId": oid,
                    "fromTradeExecution": round(truth, 8), "fromOrderStatus": round(status_last[oid], 8)})
    engine_checked = sum(1 for oid in order_truth_filled if oid in status_last)

    switchovers = [e for e in events if e.get("kind") in ("newLeader", "disconnected", "connected", "connectError", "pollError")]

    report = {
        "summary": {
            "testUsers": len(test_users),
            "observedTrades": len(trades_raw),
            "uniqueTrades": contiguity["uniqueTrades"],
            "submittedOrders": len(submitted),
            "ordersWithFillsChecked": checked,
            "balanceDiscrepancies": len(user_findings),
            "filledQtyMiscounts_omsVsTrue": len(order_findings),
            "engineStreamDivergence_statusVsTrades": engine_divergent,
            "engineStreamChecked": engine_checked,
            "tradeIdGaps": len(contiguity["gapRanges"]),
            "tradeIdMissingInRange": contiguity["missingInRange"],
        },
        "engineStreamDivergenceExamples": engine_examples,
        "tradeIdContiguity": contiguity,
        "balanceDiscrepancies": user_findings,
        "filledQtyMiscounts": order_findings[:100],
        "observerEvents": switchovers,
    }

    out = os.path.join(d, "reconciliation-report.json")
    with open(out, "w") as f:
        json.dump(report, f, indent=2)

    s = report["summary"]
    print("=" * 64)
    print("BUG #9 RECONCILIATION SUMMARY")
    print("=" * 64)
    for k, v in s.items():
        print(f"  {k:28s}: {v}")
    if user_findings:
        print("\n  BALANCE DISCREPANCIES:")
        for fnd in user_findings[:20]:
            print("   ", fnd)
    if order_findings:
        print("\n  FILLEDQTY MISCOUNTS (first 20):")
        for fnd in order_findings[:20]:
            print("   ", fnd)
    if engine_divergent:
        print(f"\n  ENGINE-STREAM DIVERGENCE: {engine_divergent}/{engine_checked} orders where the cluster's")
        print("  OrderStatus stream (what OMS trusts for filledQty) UNDER-REPORTS vs TradeExecution truth:")
        for ex in engine_examples[:12]:
            print("   ", ex)
    if contiguity["gapRanges"]:
        print("\n  TRADEID GAPS (observer's own view; cross-ref reconnect windows):")
        print("   ", contiguity["gapRanges"][:20])
    if not user_findings and not order_findings and contiguity["missingInRange"] == 0:
        print("\n  CLEAN: no balance/filledQty discrepancies and contiguous tradeIds.")
        print("  (Either the bug did not trigger this run, or fills reconciled despite switchovers.)")
    print(f"\n  full report -> {out}")

if __name__ == "__main__":
    main()
