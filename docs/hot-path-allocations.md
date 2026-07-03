# Hot-Path Allocation Audit (2026-07-02)

Inventory of allocations on the ingress -> match -> egress message path.
**FLAG-ONLY: nothing here has been changed.** Each entry needs a fix/accept
decision before the beta. Line numbers are as of the audit date.

## Confirmed zero-allocation (the happy path)

The core order path allocates nothing per message:

- `SbeDemuxer` (match-cluster): reused SBE flyweight decoders + pooled command
  objects (`CreateOrderCommand` etc., reset and refilled per message); dispatch
  by templateId int switch; enum mapping returns cached constants.
- `Engine.processCreate/Cancel/Update`: engines from `Int2ObjectHashMap`,
  maker correlation via `Long2LongHashMap` (no boxing).
- `ArrayMatchingEngine` match loop: writes into a preallocated
  `long[] matchResults`; seqlock top-of-book into preallocated arrays.
- `MatchEventPublisher`: per-market LMAX Disruptor (BusySpinWaitStrategy,
  65536 slots), preallocated `PublishEvent` slots reused.

## Flagged allocations

### Egress copy path (consensus/service thread, per egress message)
| Where | What |
|---|---|
| `AppClusteredService.java:133` | `byte[] copy = new byte[length]` per market-data broadcast |
| `AppClusteredService.java:137` | `new QueuedMessage(copy, length)` per market-data broadcast |
| `AppClusteredService.java:155` | `byte[] copy = new byte[length]` per reliable (OMS) broadcast |
| `AppClusteredService.java:159` | `new QueuedMessage(copy, length)` per reliable broadcast |

Highest-value fix candidates: these run once per egress message on the
consensus-adjacent thread. A pooled-buffer ring (fixed slabs + free list) would
remove both the `byte[]` and the wrapper.

### MarketPublisher buffering (per-market consumer thread)
| Where | What |
|---|---|
| `MarketPublisher.java:331` | `new TradeExecutionEntry()` per trade buffered |
| `MarketPublisher.java:532` | `new OrderStatusEntry()` per order status buffered |
| `MarketPublisher.java:788` | `new java.util.HashSet<>()` in `computeLevelChanges`, twice per book-delta flush |
| `MarketPublisher.java:790,813,819` | `long -> Long` autoboxing per level into that set |
| `MarketPublisher.java:827` | boxed `for (Long price : ...)` iterator per residual level |

Off the consensus thread (20 ms flush cadence) so lower priority, but the
entry objects are per-trade at full rate. Pools exist in the same class
(`aggregatedTradePool`, `levelPool`, `changePool`); extending the pattern to
these two entry types and swapping the `HashSet` for an Agrona
`LongHashSet` is mechanical.

### Reject-path logging (consensus thread, reject/error only)
| Where | What |
|---|---|
| `Engine.java:216,243,269,289,415,459` | `logger.warn("... {}", int/long)`: the `Object[]` varargs and boxing are allocated at the call site BEFORE the level guard inside `Logger.warn`, so they allocate even at the default ERROR level |

Fires only on rejects, but a reject storm is exactly when you least want
allocation on the consensus thread. Fix options: guard call sites with
`if (Logger.getLevel() ...)`, or add primitive-overload `warn(String, long)`
methods to `Logger`.

### Edge-only (bounded, acceptable unless they start firing)
| Where | What |
|---|---|
| `ArrayMatchingEngine.java:124,153` | `System.err.println` string build when `MAX_MATCHES_PER_ORDER` (10,000) is hit |
| `MatchEventPublisher.java:160,163` | warn-string concat on ring-buffer low/full backpressure |

### Non-Agrona structures on the flush thread (accepted for now)
`MarketPublisher` uses `TreeMap` bid/ask buffers, `ArrayList` batch buffers and
`ArrayDeque` pools on the 20 ms flush thread; `ClientSessions` uses a
`CopyOnWriteArrayList` (reads are allocation-free; writes only on session
open/close). These are deliberate and off the hot thread.

## Doc note found during audit

`MarketPublisher` javadoc says "50ms" flush; the constant is
`FLUSH_INTERVAL_MS = 20` (`MarketPublisher.java:33`). Fix the comment when next
touching the file.
