# Session Handoff — 2026-06-13 (late evening)

Continuation notes for a fresh session. Context: ultra-low-latency Aeron-cluster matching engine,
3 nodes, 24/7. Repos under `/home/emre/Apps/openexchange/`: `match`, `admin-gateway`, `order-management`,
`trading-ui`. Admin API on `:8082` is the single source of truth for runtime ops.

---

## 0. TL;DR — what to do first next session

1. **Decide on commits.** Four sets of uncommitted/branch changes are deployed-and-working but NOT
   committed to `main`. All commits this session are **unsigned** (1Password SSH agent not connected).
   Connect the agent, then commit/merge (see §2).
2. **Run the deferred recovery proof** for the compact-removal fix (§1, the last validation step) when a
   ~30s market-gateway-down window is acceptable (it disrupts the live UI).
3. **Cluster is HEALTHY right now**: leader=0, all 3 up, market+oms up, auto-snapshot ON @1min
   (snapshot-only — the fixed behavior), 0 `unknown recording id`. Don't wipe without reason.

---

## 1. THE HEADLINE FIX — recover-from-snapshot wedge (was "#14")

### Real root cause (CONFIRMED by controlled experiment)
The recurring cluster wedge — on `stop-all`→`start-all`, every node crashes in
`ConsensusModuleAgent.recoverFromSnapshotAndLog` with `ArchiveException: unknown recording id: N`, the
cluster self-heals a leader but **cannot serve market-gateway ingress** (`AWAIT_PUBLICATION_CONNECTED`,
`ingressPublication=null` → frozen UI order book) — was caused by:

**`admin-gateway OperationsService.doCompact()` running Aeron's OFFLINE `ArchiveTool compact` +
`delete-orphaned-segments` against the LIVE running node archives.** `AutoSnapshot.runSnapshotCycle`
called `Compact()` after EVERY snapshot, silently corrupting the latest snapshot recording so the next
restart can't replay it.

**Proof:** snapshot-ONLY restart cycles recover cleanly (tested repeatedly); snapshot+**compact** cycles
reproduce the EXACT wedge (`unknown recording id` on all 3 nodes + market gateway `AWAIT_PUBLICATION_CONNECTED`).
Differentiator = `Compact()`.

### What was WRONG earlier (don't repeat)
- **`ArchiveHousekeeping` is EXONERATED.** It was a no-op in the failing runs (64MB segment vs ~11MB log
  ⇒ `segmentsPurged=0`). The `fix/housekeeping-recovery` branch (commit 0d6dcd7: stop purging snapshot
  recordings + verify net + `test3_RecoverySucceedsAfterHousekeeping`) is reasonable **hardening but NOT
  the fix**. Decide whether to keep/merge it.
- Two false readings earlier: "archive had only recording 0" was a **shell bug** (`sort -un` collapses
  `/0,/1,/2…`). Correct listing: `ls archive/ | grep -oE '^[0-9]+-' | tr -d - | sort -n | uniq`.
- The admin gateway already blocks **manual** snapshots during a rolling-update ("another operation in
  progress"); the rolling-update was not the trigger.

### The fix that was IMPLEMENTED + DEPLOYED (per user: "remove the compact feature; only take a snapshot")
In **`admin-gateway`** (branch `main`, **uncommitted**, −392/+16, builds with `GOTOOLCHAIN=go1.23.0`,
binary already deployed via `systemctl --user restart admin.service`):
- `services/autosnapshot.go` — `runSnapshotCycle` now takes ONLY a snapshot (dropped `Compact()` /
  `RollingArchiveCleanup()`). Disk reclaim still happens: `doSnapshot()` runs the LIVE-safe
  `ArchiveHousekeeping` on all 3 nodes after every snapshot.
- `services/operations.go` — DELETED `Compact/doCompact`, `CompactArchive/doCompactArchive`,
  `compactNodeArchive`, `RollingArchiveCleanup/doRollingArchiveCleanup`, `cleanSingleNodeArchive`,
  `waitForNodeRejoin` (300 lines).
- `services/cluster.go` — DELETED `ArchiveToolCompact`, `ArchiveToolDeleteOrphanedSegments`,
  `ArchiveToolMarkInvalid`.
- `handlers/handlers.go` — removed routes + handlers `/api/admin/compact`, `/compact-archive`,
  `/rolling-cleanup` (now return 404; `/housekeeping` kept → 202).
- **`match/.claude/CLAUDE.md`** — removed those 3 endpoints from docs + added a note explaining why.

**Verified deployed:** `/api/admin/compact` → 404, `/api/admin/housekeeping` → 202; auto-snapshot @1min
runs snapshot-only (admin.log: "triggering snapshot", no "triggering compact"); 0 `unknown recording id`
across ~15 snapshot cycles.

### LAST validation step still PENDING (deferred — disrupts live UI)
**The decisive recovery proof:** disable auto-snapshot → `stop-all` → `start-all` → assert NEW
`unknown recording id` == 0 and both clients reconnect + stay stable; then one extra manual `/snapshot`
+ restart to double-confirm. Deferred because it takes the market gateway down ~30s and the user was
viewing the live UI. The fix is already strongly evidenced; this is the clean-room confirmation.
Recovery from a wedge (if it ever happens) requires a `/dev/shm` wipe (authorized per-instance only):
`stop-all` → `curl -X POST :8082/api/admin/cleanup -d '{"force":true}'` → `start-all`.

---

## 2. UNCOMMITTED / UNMERGED WORK (all unsigned — connect 1Password SSH agent first)

| Repo | Branch | State | What |
|---|---|---|---|
| `admin-gateway` | `main` | **uncommitted** (4 files, −392/+16) | THE compact-removal fix (§1). Deployed. Commit it. |
| `match` | `validate/all-fixes` | uncommitted: `.claude/CLAUDE.md`, `match-loadtest/.../OrderScenario.java` | CLAUDE.md doc update (§1) + loadtest tick fix (§3). |
| `match` | `validate/all-fixes` | committed (merge of 2 branches) | `0d6dcd7` ArchiveHousekeeping hardening + `perf/relax-market-data-flush-timer` (Phase-1 flush 10→250ms + timer-arming fix). Decide merge to `main`. |
| `match` | `fix/housekeeping-recovery` | committed `0d6dcd7` | Same housekeeping hardening (now known not to be the root cause — keep as hardening or drop). |
| `order-management` | `perf/oms-protocol-keepalive` | committed `e551f89` | OMS: replace logged GatewayHeartbeat with protocol keep-alive (the ~1.1KB/s idle churn fix). Not merged to `main`. |

`validate/all-fixes` last commits: `1ff6fea` (merge perf/relax-market-data-flush-timer) / `0d6dcd7`
(housekeeping). The idle-log-growth work (Phase 1 = ~3x reduction; OMS heartbeat = remainder, ~14x total)
lives on `perf/relax-market-data-flush-timer` (in match) + `perf/oms-protocol-keepalive` (in OMS).

---

## 3. LOAD GENERATOR FIX (uncommitted, match `validate/all-fixes`)

**Symptom:** every load test left the UI order book empty — all orders REJECTED.
**Root cause:** `OrderScenario` computes `price = midPrice*(1±spread)` → fractional values, but BTC-USD
tick is **$1.00** (`MarketConfig.BTC_USD = 50_000..150_000, tick 1.0`). Fractional price → engine
`PRICE_OFF_TICK` reject (`DirectIndexOrderBook.validatePrice`). NOT a system bug.
**Fix:** `match-loadtest/.../OrderScenario.java` `OrderParams` ctor → `this.price = Math.rint(price);`
(rounds to the $1 tick; covers all 5 scenarios). Rebuilt `match-loadtest.jar`.
**Result (BALANCED 1k/s 30s):** before = 41,363 REJECTED / 0 rested / 3 trades / empty book;
after = **26,964 NEW + 8,717 FILLED / 2,784 REJECTED / 21,042 trades / 31,029 BOOK_DELTAs / 19 bid
levels @ ~109,900**, with continuous BOOK_DELTA/TICKER/CANDLE WS messages. The UI will now populate.

Run a load: `./run-load-test.sh baseline` (1k/s 60s) | `quick` | `stress` (10k/s).

---

## 4. TRADING UI — how it's actually served (corrected understanding)

- **The UI is a Cloudflare Pages deploy** (edge-hosted, not this box). It connects to
  `wss://market.openexch.io/ws` via the cloudflared tunnel (`.env.production`:
  `VITE_MARKET_WS_URL=wss://market.openexch.io`, `VITE_ORDER_API_URL=https://oms.openexch.io`).
- **Active tunnel** = `~/.cloudflared/config.yml` (domain **openexch.io**): `oms→8080, market→8081,
  admin→8082`, catch-all 404. (Inactive variants `config.yml.new`/`.backup` use **initex.io** + a UI
  route to `localhost:3000` — old.) Tunnel runs as `cloudflared.service` (user), `tunnel run match-engine`.
- **Verified working** this session: real WSS handshake to `wss://market.openexch.io/ws` → 101 + live
  messages; `https://admin.openexch.io/api/admin/status` → 200.
- **Why the UI showed nothing earlier:** (a) the market gateway was DOWN during this session's
  stop/wipe/recovery windows → tunnel `connection refused` to :8081 (cloudflared logs 22:22–22:42; clean
  since 22:44); AND (b) the load-generator off-tick reject bug (§3) → empty book even when connected.
- **Known issues to optionally fix (NOT blocking the Pages deploy):**
  - **Stale nginx (localhost:80):** `/var/www/match-trading` → dead symlink `/home/emre/Apps/match/...`
    (repo relocated to `/home/emre/Apps/openexchange/`). `curl localhost` → 500. Only matters if you want
    local/LAN serving. Fix: `sudo ln -sfn /home/emre/Apps/openexchange/trading-ui/dist /var/www/match-trading
    && sudo nginx -t && sudo systemctl reload nginx` (then ensure `www-data` can traverse `/home/emre`).
  - **Local `dist` bundle hardcodes `ws://<host>:8081/ws`** (ignores `VITE_MARKET_WS_URL`) — only relevant
    if you serve the local build instead of the Pages one. Better long-term: `getWebSocketUrl()` →
    `wss?://${window.location.host}/ws` (same-origin via nginx `/ws` proxy). The Pages build already uses
    the env var, so this only affects local/nginx serving.

---

## 5. OTHER OPEN BUGS / TASKS (tracked this session, not yet done)

- **#9 (DEFERRED money-bug):** OMS fill exactness across leader switchover (P2a/P2b). Highest-value
  open correctness item.
- **#10:** `/api/admin/cleanup` wipes the ENTIRE `/dev/shm` archive (not just mark/lock files) — DANGER,
  mis-documented in CLAUDE.md. Authorized per-instance only.
- **#11:** `rolling-update` can lose a node's `/dev/shm` media-driver dir and wedge election; recover via
  `stop-all`/`start-all`.
- **#13:** `OmsOrderServiceImplTest` doesn't compile (stale constructor) — build OMS jar with
  `-Dmaven.test.skip=true`.
- **#6:** `rebuild-admin` endpoint fails silently on this box — build admin-gateway + restart manually
  (`GOTOOLCHAIN=go1.23.0 go build -o admin-gateway . && systemctl --user restart admin.service`).
- **#7:** backup node not tracking the current cluster.

---

## 6. RUNTIME CHEAT-SHEET

```bash
# Status / health
curl -s :8082/api/admin/status | jq '{leader,nodes:[.nodes[].role],mkt:.gateways.market.healthy,oms:.gateways.oms.healthy}'
# Auto-snapshot (currently ON @1min, snapshot-only)
curl :8082/api/admin/auto-snapshot                                   # get
curl -X DELETE :8082/api/admin/auto-snapshot                         # disable
curl -X POST :8082/api/admin/auto-snapshot -d '{"intervalMinutes":30}'
# Snapshot (also runs live-safe ArchiveHousekeeping on all nodes)
curl -X POST :8082/api/admin/snapshot
# Lifecycle
curl -X POST :8082/api/admin/processes/stop-all
curl -X POST :8082/api/admin/processes/start-all
# Load test
./run-load-test.sh baseline        # 1k/s 60s   (loadtest jar already rebuilt with tick fix)

# WS probes written this session (stdlib, no deps):
python3 /tmp/ws_probe.py 30        # count messages by type
python3 /tmp/ws_probe2.py 40       # tally order statuses + book/trades  (run WITH a load)
# Tunnel WS check (the UI's real endpoint): inline ssl python in the session transcript.

# Build admin-gateway (rebuild-admin endpoint is broken here):
cd ../admin-gateway && GOTOOLCHAIN=go1.23.0 go build -o admin-gateway . && systemctl --user restart admin.service
```

Logs: `~/.local/log/cluster/{node0,node1,node2,admin,market,oms}.log`. cloudflared:
`journalctl --user -u cloudflared`.

---

## 7. Current runtime state at handoff

leader=0, nodes=[LEADER,FOLLOWER,FOLLOWER], market+oms healthy, auto-snapshot ON @1min (snapshot-only),
0 `unknown recording id`. Loadtest jar rebuilt with the tick fix. admin-gateway deployed with the
compact-removal fix. Nothing pushed/committed. The deferred recovery proof (§1) is the only validation
gap. Persistent memory updated (`compact-on-live-breaks-recovery`, etc. — see `MEMORY.md`).
