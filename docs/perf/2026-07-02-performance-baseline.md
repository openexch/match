# Open Exchange Performance Baseline (2026-07-02)

Measured on the live dev cluster running the external media driver transport
(kernel-bypass-ready architecture, kernel UDP path since no bypass NIC is present).
Source data for the public performance page. Raw interval logs and parsed JSON live in
`docs/perf/data/2026-07-02/`.

## Headline numbers (single desktop CPU, full Raft consensus)

Final runs (`data/2026-07-02/ladder-final-3of3-verified.json`) with all 3 nodes
verified live and committing during each run (pre/mid/post commit positions in
`raw-final/run-transcript-with-node-commits.txt`):

| Configuration | Sustained rate | Success | Ingress p50 | Ingress p99 | Max |
|---|---|---|---|---|---|
| prod drivers + OS tuning | **800,000 orders/s** | **100.00%** | 0.22 µs | 0.32 µs | 39 µs |
| prod drivers + OS tuning | **700,000 orders/s** | 99.83% | 0.22 µs | 2.1 µs | 78 µs |
| prod drivers + OS tuning | 500,000 orders/s | 99.23% | 0.23 µs | 0.34 µs | 82 µs |
| prod drivers, untuned OS | 500,000 orders/s | 100.00% | 0.22 µs | 0.33 µs | 3.2 ms |
| dev drivers (backoff) | ~148,000 orders/s | 100.00% | 0.26 µs | ~1.3 µs | 6 ms |

The occasional sub-100% runs are single brief backpressure bursts (client-visible,
retryable, correlated with archive segment rolls), not rate-dependent: 800k ran
clean while one 500k run caught a burst. The earlier 2-of-3-quorum measurements
(now superseded) produced statistically identical numbers.

Knee: at a 1,000,000 orders/s target the cluster accepts ~90% (interval bursts to
~970k accepted). The clean-at-100% envelope ends between 500k and 600k; above that,
brief backpressure blips (~0.2-0.5% of offers, correlated with archive segment rolls)
appear while the cluster keeps running and never drops an accepted message.

Every accepted order passes the full path: SBE decode, Raft log replication to
3 nodes, deterministic matching in the array-backed order book, egress publication.
Zero egress drops and zero consensus failures across all runs (verified per rung via
node logs and commit positions).

## Environment

- CPU: Intel i7-13700K (8P+8E, 24 threads), governor `performance` (tuned runs) /
  `powersave` (dev-ladder and untuned-prod runs)
- Kernel: Linux 6.17.0-35-generic; RAM 32 GB; archives on tmpfs (/dev/shm, 16 GB)
- Java: Zulu 21.0.11 LTS, ZGC generational, `-Xmx2g` per node
- Aeron 1.51.0, Agrona 2.4.1; 3-node Aeron Cluster (Raft), loopback UDP,
  `mtu=8192`, `term-length=16m`
- Transport: standalone media driver per node (`deploy/media-driver/launch-driver.sh`),
  engine JVMs in `TRANSPORT_DRIVER_MODE=external`; matching engine = array-backed
  (default); egress byte-bounded (OMS 128 MB reliable + market-data 32 MB lossy)
- Core map: node0+driver0=0-3, node1+driver1=4-7, node2+driver2=8-11, OMS=12-15,
  market gateway=16-19, load generator=20-23 (the 4 spare E-cores)
- Load generator: match-loadtest, 1 publisher thread, AGGRESSIVE scenario,
  its own embedded driver, pinned off the cluster cores

## Metric semantics (important for honest publication)

- **Throughput / success**: orders offered to cluster ingress and accepted
  (`AeronCluster.offer` succeeded). "Fails" are client-visible backpressure
  rejections, safe and retryable; the cluster never silently drops an accepted order.
- **Latency (p50/p99)**: client-side enqueue-to-ingress-accept time. It is NOT
  end-to-end order-ack latency. End-to-end OrderStatus latency is dominated by the
  deliberate 20 ms egress batch flush (throughput/latency tradeoff), and a
  coordinated-omission-safe transport RTT benchmark (HdrHistogram) is being added
  as `match-bench` to fill this gap. Do not publish the µs numbers as "order latency".
- Single host, loopback UDP: no NIC/wire time in any number. Multi-host and
  Onload/VMA (kernel bypass) columns are future work on real hardware.
- **JVM warmup (measured 2026-07-02, A/B at 150k/s, threads=1, fresh generator JVM)**:
  with 2 s warmup the first measured intervals show p99 2.8 µs settling to ~1.7 µs and
  the run's Max was 3,680 µs; with 30 s warmup the first interval is already at
  steady state (p99 0.3 µs) and Max drops to 99 µs (37x). JIT compilation and
  first-touch dominate early-tail and Max outliers, so: use >= 30 s warmup, report
  steady-state windows only, and re-warm after any node restart (the rung tables in
  data/ show first-interval p99 spikes, e.g. 14.6 ms at 600k, from exactly this).
  The persistent ~3 ms p99 in the dev-profile ladder was NOT warmup: it was constant
  for 45 s at every rate and disappeared with 1 generator thread instead of 4
  (scheduler contention on the generator's 4 E-cores).

## What we learned: the driver profile is decisive

The transport rework moved the Aeron media driver out of the engine JVM
(prerequisite for LD_PRELOAD kernel bypass). The driver process profile then
dominates throughput:

| Driver profile | Threading | Idle strategy | Ingress ceiling |
|---|---|---|---|
| dev (default on the dev box) | SHARED (1 thread) | Backoff | ~148k/s |
| prod | DEDICATED (3 threads) | BusySpin | ~800k/s (tuned OS) |

A context-switch A/B under identical 200k load disproved the thread-thrash
hypothesis: the dev driver's single thread performs ~2 voluntary switches/s (backoff
never reaches park under load) and both profiles see only hundreds of involuntary
preemptions/s. The dev profile is simply single-thread saturated: conductor, sender
and receiver duty cycles share one core. The embedded driver had always run
DEDICATED+BusySpin, which is why the old 281k number beat the first external-driver
measurements. With prod-profile external drivers the same box now reaches ~800k/s,
2.8x the best embedded figure, helped by the OS tuning below.

- Old baseline (embedded driver, v0.2.0-alpha): ~281k orders/s @ 100%
- External driver, prod profile, untuned OS: 500k @ 100.00%, 600k @ 97.2%
- External driver, prod profile, tuned OS: 700k @ 99.47%, 800k @ 99.64%

## OS tuning impact (`make optimize-os`)

Before tuning, `net.core.rmem_max/wmem_max` was 212 KB, so every driver silently ran
with 208 KB sockets instead of the requested 4 MB (the driver logs a warning). After
raising to 16 MB and restarting drivers (sockets are sized at creation), plus CPU
governor performance, the 97%-at-600k run became 99.6%-at-800k. Socket buffer
headroom, not CPU frequency, was the main win.

## Capacity and operations findings

- **Raft log growth**: ~165 bytes/order/node. At 700k orders/s that is ~350 MB/s
  across 3 nodes. A 16 GB tmpfs archive fills in under a minute of max-rate load,
  faster than the 5-minute auto-snapshot cycle. Consequences on this rig: snapshot +
  housekeeping between max-rate runs is mandatory; production must put archives on
  NVMe, not tmpfs.
- **Exhaustion behavior (observed twice)**: a full /dev/shm wedges follower archive
  agents, the leader reports "inactive follower quorum" and commit stalls; admin
  tooling (ClusterTool) also fails because it cannot map buffers. Recovery that
  worked without data loss: force-stop nodes, restart drivers (frees leaked client
  publication buffers via `aeron.dir.delete.on.start`), start nodes (recover from
  snapshot + replay), snapshot + housekeeping immediately. Follow-ups worth filing:
  admin status API reports stale CnC counters for dead processes (a dead cluster can
  look healthy), and driver dirs accumulate orphaned publication buffers from killed
  clients until driver restart.
- Egress remained byte-bounded and clean throughout: zero CRITICAL sheds in all runs
  (the periodic `EGRESS-DIAG ... dropped*=0` heartbeats confirm).

## Reproduction

```bash
# one-time OS tuning (requires sudo; raises socket limits, governor)
make -C match optimize-os
# drivers must be restarted after tuning to pick up 4 MB sockets
# (rolling: stop nodeN, restart driverN, start nodeN, one node at a time)

# prod driver profile (ENGINE_DRIVER_PROFILE=prod on the admin gateway)
# rate rung, generator pinned to the spare E-cores:
taskset -c 20-23 java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED \
  -Xms1g -Xmx1g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem \
  -Daeron.socket.so_sndbuf=4194304 -Daeron.socket.so_rcvbuf=4194304 \
  -cp match-loadtest/target/match-loadtest.jar com.match.loadtest.LoadGenerator \
  --rate 700000 --duration 15 --threads 1 --scenario AGGRESSIVE --warmup 5 \
  --hosts "localhost,localhost,localhost" --no-ui

# reclaim the tmpfs archive between max-rate runs:
curl -X POST localhost:8082/api/admin/snapshot   # housekeeping runs automatically
```

## Gaps to close before the public page

1. True end-to-end latency (order submit to OrderStatus egress), coordinated-omission
   safe: `match-bench` HdrHistogram harness (in progress) + an RTT mode for the load
   generator. Expect the egress 20 ms batch flush to dominate; publish it with that
   explanation or add a low-latency egress path first.
2. Kernel-bypass column: same harness under Onload/VMA on real NICs (multi-host).
3. Sustained endurance run (30-60 min) at 500k with NVMe-backed archive.
4. Numbers above are one 12-45 s window per rung; the page should average 3+ runs.
5. RESOLVED: the earlier tuned ladder ran on a 2-of-3 quorum (node2 wedged, see the
   incident report). The headline table above is from the final re-runs with all 3
   nodes verified committing throughout; the exploratory 2-of-3 data is retained in
   `ladder-prod-profile-os-tuned.json` and matched the final numbers.
