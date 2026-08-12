# Gateway book dedup/staleness ladder — executable spec for the C++ port

**Status: FROZEN** (as of `b04e416`, 2026-08-12). The market gateway (`match-gateway`) will be
rewritten in C++ in a separate repo. The classes and tests named here are the ONLY behavioral
specification of the book dedup/staleness ladder — the logic that, across leader switchovers,
discards stale/reordered egress, detects drift, and re-anchors from snapshots. Until the port
lands, behavior-changing refactors of these classes are forbidden; an intentional behavior change
is a spec change and must update this file and the pinning tests in the same commit.

## How to use this as a port checklist

1. Port the invariants below, not the Java idioms. Each invariant is a sentence you must be able
   to assert about the C++ implementation.
2. Port the pinning tests first (same inputs, same observable outputs — the SBE test encodes real
   frames; do the same). A test you cannot port is a behavior you have not understood yet.
3. The test-to-behavior map tells you which test fails if you get a given invariant wrong.
4. When every ported test passes and the counters/JSON markers match, the ladder is done; nothing
   else in these two classes is load-bearing for correctness.

Frozen implementation (both in `match-gateway/src/main/java/com/match/infrastructure/gateway/state/`):

- `GatewayStateManager` — the ladder (gates, ordering, counters)
- `GatewayOrderBook` — per-market book state, stale flag, level mechanics

Pinning tests (`match-gateway/src/test/java/com/match/infrastructure/gateway/state/`):

- `GatewayStateManagerSbeTest` — the ladder itself, over real encoded SBE frames
- `GatewayOrderBookTest` — book/stale-flag/level mechanics
- `GatewayStateManagerTest` — accessor surface (null-before-data contracts)

## The seams

**Inbound** (cluster egress, SBE, single poll thread, via `AeronGateway.EgressMessageListener`):
`BookSnapshot` (marketId, timestamp, bidVersion, askVersion, bookVersion, bid/ask levels),
`BookDelta` (same prefix + `fromVersion` + changes: side, price, quantity, orderCount,
NEW_LEVEL/UPDATE_LEVEL/DELETE_LEVEL), `TradesBatch`, `OrderStatusBatch` (decode-and-drop: never
rebroadcast — privacy), `onNewLeader` (log only; NO state reset — the ladder itself absorbs the
switchover). `bookVersion` at its SBE null value means a pre-v4 upstream: treat as 0/absent.

**State kept**: one `GatewayOrderBook` per market (created on first message), four monotonic
counters (below). Nothing is persisted; a gateway restart starts empty and re-anchors from the
next snapshot.

**Outbound**: JSON broadcasts to market-data WebSocket clients (BOOK_SNAPSHOT / BOOK_DELTA pass
the version chain through: clients run the same gap check on `fromVersion`); cached book JSON for
REST and new subscribers; counters for /metrics (`gateway_chain_breaks_total`,
`gateway_deltas_dropped_stale_total`, stale-delta and stale-snapshot drop counts,
`gateway_books_stale` gauge). A dropped frame is never forwarded.

## Invariants — GatewayStateManager

**Snapshot path** (`onBookSnapshot`):

- S1 (match#95): a snapshot whose `bookVersion` does not advance the cached book
  (`incoming <= have`, including equality) is dropped and counted — but ONLY when both sides
  carry a real (v4+, >0) bookVersion. Applying it would rewind the displayed book.
- S2: a legacy snapshot (bookVersion absent/0, mixed-version rolling update) is applied
  unconditionally; its version falls back to `max(bidVersion, askVersion)`. Guarding legacy
  frames would freeze the book forever.
- S3 (match#96): an applied snapshot re-anchors the version chain and clears the stale flag.
  Snapshots are the ONLY recovery path; there is no gateway-side resnapshot request — recovery
  rides the cluster's periodic resnapshot.

**Delta path** (`onBookDelta`) — three gates, in this exact order:

- D1 (match#96): if the book is already stale, drop the delta and count it in a counter distinct
  from chain breaks (`chainBreaks` counts break EVENTS, not every delta while stale). Applying a
  delta onto a known-stale base is the drift bug.
- D2 (match#19): monotonic dedup — a delta advancing NEITHER side's version
  (`bidVersion <= have && askVersion <= have`) is a stale/duplicate old-leader or redelivered
  frame: drop and count. Checked BEFORE D3 so a benign redelivery (whose `fromVersion` trivially
  mismatches) is deduped, not misflagged as a chain break.
- D3 (match#96): chain continuity — when `fromVersion > 0` and the book has a real version and
  `fromVersion != have`, this consumer genuinely missed a forward update: drop the delta, mark
  the book stale, count a chain break, log at ERROR (the only diagnostic evidence). Never apply.
- D4: a delta that passes all gates is applied change-by-change, versions updated (v4
  `bookVersion` authoritative, per-side max as legacy fallback), then broadcast with the chain
  intact.
- E1: every handler is wrapped per-message; a poison frame logs an error and never kills the
  egress poll loop or affects other markets.

## Invariants — GatewayOrderBook

- B1: versions are per-side monotonic (sourced from the engine book); `isStaleUpdate` is the
  both-sides-`<=` predicate of D2. Snapshots bypass it (they reset the baseline).
- B2 (match#96): `markStale` KEEPS serving the last good levels/version but regenerates the
  cached JSON so it carries `"stale": true` immediately; `update()` (snapshot) clears the flag.
- B3 (byte-compat): a healthy book's JSON carries NO `stale` key at all — healthy payloads stay
  byte-identical to pre-match#96 clients.
- B4 (match#70): retention is 64 levels, deliberately deeper than the ~20 UIs render, so a
  DELETE of a visible level backfills from retained depth instead of shrinking the window. A
  NEW_LEVEL beyond a full book is dropped unless it beats the worst retained level, which it
  replaces. Bids sort descending, asks ascending; inserts keep order.
- B5: UPDATE_LEVEL / DELETE_LEVEL of a price the book never saw silently no-op. This is exactly
  the silent-drift failure mode the ladder exists to catch upstream — the C++ port must not
  "fix" it into an error, and must not rely on it as recovery. Price match uses an absolute
  epsilon (1e-7) on the decimal-converted price.
- B6: single writer (the egress poll thread), lock-protected writes, concurrent lock-free reads
  of a pre-built cached JSON string; readers never observe a torn book.
- B7: a book with no data yet serves null JSON and version 0 (version 0 means "no real version":
  it disables S1/D3 guards until first anchored).

## Cluster-side contract (context — NOT frozen here, but the ladder assumes it)

- `MarketPublisher` produces the chain: each delta names
  `fromVersion = last PUBLISHED bookVersion -> bookVersion`, advanced only when a frame was
  actually encoded (match#115) — an empty visible diff does not burn a version.
- The market-data egress is lossy under burst (drop-oldest byte budget): chain breaks are an
  EXPECTED runtime event, not a bug signal.
- `AppClusteredService` resnapshots every market periodically (10s) and keeps egress warm (1s
  heartbeat); S1 makes the steady-state cost of that a cheap drop. This bounds staleness.
- The cluster performs NO egress dedup across leader switchovers — it delegates that entirely to
  this ladder. If the C++ gateway drops the ladder, stale old-leader frames WILL reach clients.

## Test-to-behavior map

| Test (class :: method) | Pins |
|---|---|
| SbeTest :: testOnBookSnapshot_StaleAfterNewerSnapshot_BookUnchangedAndCounted | S1: lower-version snapshot dropped, book byte-unchanged, counted |
| SbeTest :: testOnBookSnapshot_EqualVersionAfterNewerSnapshot_TreatedAsStale | S1: equal version = duplicate, dropped |
| SbeTest :: testOnBookSnapshot_StaleAfterNewerDelta_BookUnchangedAndCounted | S1 vs. delta-advanced version (snapshot older than applied delta) |
| SbeTest :: testOnBookSnapshot_HigherVersionApplied | S1/S3: genuine re-snapshot applies, not counted |
| SbeTest :: testOnBookSnapshot_LegacyVersionZero_AppliedUnconditionally | S2: legacy frames never guarded |
| SbeTest :: testOnBookDelta_ChainBreak_DeltaDroppedBookFlaggedStale | D3+B2: drop, flag, count, `"stale":true` served |
| SbeTest :: testOnBookDelta_WhileStale_DeltasDroppedNotReCountedAsBreaks | D1: distinct counter; chainBreaks counts events |
| SbeTest :: testOnBookDelta_RecoverySnapshotClearsStale_ThenChainedDeltaApplies | S3+D4: re-anchor clears stale; chained delta applies after |
| SbeTest :: testOnBookDelta_HealthyChainedDelta_NoStaleKeyByteCompat | B3+D4: healthy path, no stale key |
| SbeTest :: testOnBookDelta_AppliesDelta / _NewLevel / _DeleteLevel / _NoExistingBook_CreatesOne | D4: apply semantics; book auto-created |
| SbeTest :: testOnBookSnapshot_* (creation/JSON/multi-market group) | Snapshot decode -> book state -> served JSON shape |
| SbeTest :: testOnTradesBatch_* (incl. _PreV5Header_SideOmitted) | Trades fan-out; taker side mapping; pre-v5 upstream omits `side` |
| SbeTest :: testOnOrderStatusBatch_DoesNotBroadcast | Order statuses never hit the public feed (privacy) |
| SbeTest :: testOnNewLeader_NoCrash | Leader change is log-only; no state reset |
| OrderBookTest :: testApplyDelta_* (NEW/UPDATE/DELETE groups) | B4/B5: insert ordering, update/delete, unknown-price no-op |
| OrderBookTest :: testMaxLevels_* | B4: 64-cap clamp, backfill-from-depth (match#70), replace-worst rule |
| OrderBookTest :: testUpdateVersions_UpdatesMetadataAndRegeneratesJson | B1 fallback (per-side max) + cached-JSON regeneration |
| OrderBookTest :: testInitialState_* / testUpdate_* / testToJson_* | B7 + snapshot-apply state + served JSON structure |
| StateManagerTest :: (all) | Accessor contracts: null before data, empty buffers, null-WS tolerance |
