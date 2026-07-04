# Kernel-Bypass-Ready Transport

How the matching engine's Aeron transport is structured so kernel bypass
(Solarflare Onload / Mellanox VMA) can be applied without touching Java code,
and how the same binaries run degraded-but-correct on any dev box.

## Architecture

```
  ENGINE JVM (per node)                    MEDIA DRIVER PROCESS (per node)
 +---------------------------+            +------------------------------+
 |  ClusteredServiceContainer|            |  aeronmd (C, preferred)      |
 |  ConsensusModule (Raft)   |  shared    |  or Java MediaDriver         |
 |  Archive                  |  memory    |                              |
 |                           |  IPC       |  conductor | sender | recv   |
 |  aeron client  <----------+----------->|  (optionally under          |
 |  (/dev/shm/aeron-<u>-N-   |  aeron.dir |   `onload` LD_PRELOAD)       |
 |   driver)                 |            +-------------+----------------+
 +---------------------------+                          |
                                             kernel UDP | or bypass stack
                                                        v
                                                      NIC / loopback
```

- The engine JVM never opens a network socket for cluster traffic. It maps the
  driver's shared-memory buffers under `aeron.dir` and the driver does all I/O.
- Kernel bypass is applied by wrapping ONLY the driver process in `onload`
  (LD_PRELOAD intercepts its socket calls). The JVM is agnostic: same bytes,
  same shared memory, regardless of what carries the packets.
- No bypass NIC (or no onload installed): the identical driver runs on kernel
  UDP. Same binaries, same config; only what the launcher detects differs.

## Why standalone driver + LD_PRELOAD (and not the alternatives)

| Alternative | Why not |
|---|---|
| Embedded driver + onload on the whole JVM | Bypass stacks and the JVM fight over threads, signals and memory; wrapping a 2 GB JVM to accelerate three driver threads is fragile and unsupportable. |
| DPDK | Requires dedicating the NIC to a poll-mode driver, custom build chain, and Aeron-side native integration; heavyweight for what Onload gives transparently. |
| JNI to a bypass API | Couples engine code to vendor APIs; breaks the "Java never knows" property; every NIC family needs new code. |
| Embedded driver, no bypass (old setup) | Driver threads live inside the engine JVM and stall at JVM safepoints, so GC pauses become packet-loss windows. Externalizing the driver removes that even on kernel UDP. |

## Driver modes and profiles

Mode is chosen by environment, never by code:

| | Who runs the driver | When to use |
|---|---|---|
| `TRANSPORT_DRIVER_MODE` unset / `embedded` | Inside the engine JVM (`ClusteredMediaDriver`) | Dev convenience, CI, single-process runs. Code default; zero setup. |
| `TRANSPORT_DRIVER_MODE=external` | Standalone process via `deploy/media-driver/launch-driver.sh` | Everything else. The admin-gateway process manager defaults to this (`driver0/1/2` services). |

Launcher profiles (`--profile`, or `ENGINE_DRIVER_PROFILE` via the admin gateway):

| Profile | Threading | Idle | Purpose |
|---|---|---|---|
| `prod` | DEDICATED (3 threads) | busy-spin | Benchmarks and production. This is what reaches ~800k orders/s. |
| `dev` | SHARED (1 thread) | backoff | Laptops, UI work, anything sharing the box. Ceiling ~148k/s. |

Driver binary (`--driver`, or `AERONMD=<path>`): `aeronmd` (C) preferred, Java
fallback automatic. Both speak the same protocol and read the same
`driver.properties`; the launcher injects the per-flavor idle-strategy syntax.

**Measured impact (2026-07-02, i7-13700K, 3 nodes one host, kernel UDP,
loopback; full data in `docs/perf/`):**

| Config | Ingress ceiling |
|---|---|
| dev profile (backoff drivers) | ~148k orders/s @ 100% |
| prod profile + OS tuning | **800k orders/s @ 100.00%** (p50 ingress 0.22 µs) |

The old embedded-driver baseline was ~281k. The driver profile, not the
external/embedded split, dominates throughput.

## Configuration reference

Java side (env var first, system property fallback; `TransportConfig.java`):

| Env | Sysprop | Default | Meaning |
|---|---|---|---|
| `TRANSPORT_DRIVER_MODE` | `transport.driver.mode` | `embedded` | `external` = connect to standalone driver |
| `AERON_DIR` | `aeron.driver.dir` | `/dev/shm/aeron-<user>-<node>-driver` | shared-memory dir (must match the driver's) |
| `TRANSPORT_IDLE_MODE` | `transport.idle.mode` | `busy_spin` | `backoff` for dev boxes (consensus + container idles) |
| `TRANSPORT_INTERFACE` | `transport.interface` | unset | appended as `\|interface=` to UDP channels |
| `TRANSPORT_MTU` | `transport.mtu` | `8192` | needs loopback or jumbo frames end to end; 1408 for plain Ethernet |
| `TRANSPORT_TERM_LENGTH` | `transport.term.length` | `16m` | UDP channel term buffers |

Launcher / process-manager side:

| Variable | Where | Meaning |
|---|---|---|
| `ENGINE_DRIVER_MODE=embedded` | admin-gateway env | omit driver services, run nodes embedded (fallback) |
| `ENGINE_DRIVER_PROFILE=prod\|dev` | admin-gateway env | passed to launch-driver.sh `--profile` |
| `AERONMD=<path>` | admin-gateway env / shell | C driver binary (else PATH lookup, else Java fallback) |
| `SENDER_CORE`/`RECEIVER_CORE`/`CONDUCTOR_CORE` | launcher env | prod-profile per-thread pinning (C driver native, Java via taskset union) |
| `ISOLATED_CORES`, `NIC` | `deploy/tuning/system-tuning.sh` | IRQ moves + irqbalance ban |

The current standing config lives in
`~/.config/systemd/user/admin.service.d/driver-profile.conf` (a comment there
documents the prod/quiet switch procedure).

## Building the C driver (aeronmd)

Built once per box from the exact Aeron tag the Java side uses:

```bash
sudo apt install -y cmake uuid-dev zlib1g-dev libbsd-dev
git clone --depth 1 --branch 1.51.0 https://github.com/aeron-io/aeron.git ~/Apps/aeron
cd ~/Apps/aeron
# Ubuntu 24.04 ships cmake 3.28; aeron's gate wants 3.30 but 3.28 builds fine:
sed -i 's/cmake_minimum_required(VERSION 3.30/cmake_minimum_required(VERSION 3.28/' CMakeLists.txt
sed -i 's/cmake_policy(VERSION 3.30)/cmake_policy(VERSION 3.28)/' CMakeLists.txt
mkdir cmake-build && cd cmake-build
cmake -DCMAKE_BUILD_TYPE=Release -DAERON_TESTS=OFF -DAERON_SYSTEM_TESTS=OFF \
      -DAERON_BUILD_SAMPLES=OFF -DBUILD_AERON_ARCHIVE_API=OFF ..
make -j12 aeronmd
cp binaries/aeronmd ~/.local/bin/aeronmd
# then set AERONMD=~/.local/bin/aeronmd where the launcher runs
```

Keep the tag in lockstep with `<aeron.version>` in `match/pom.xml` when
upgrading Aeron.

## Verifying kernel bypass is actually active

Only meaningful on a host with a Solarflare NIC and Onload installed:

1. `launch-driver.sh` logs `bypass=onload` (vs the WARN fallback line).
2. `onload_stackdump lots` lists the driver process with active stacks.
3. `ss -u` does NOT show the driver's UDP sockets (they bypassed the kernel);
   conversely `tcpdump` on the NIC goes blind for that traffic. That is
   expected, not a bug; use `onload_stackdump` for observability.
4. Latency drop on the RTT bench (see `bench` when it lands: same harness,
   `--onload on` vs `off`).

Without bypass hardware the launcher prints
`WARN: onload not found, running kernel UDP (fallback path, correct but slower)`
and everything works identically. That warning is the design working.

## Failure semantics (driver death)

- The engine's Aeron clients detect a dead driver via the CnC heartbeat
  (~10 s liveness). A FATAL `DriverTimeoutException` reaches the error handler,
  which signals shutdown and, after a 5 s grace, halts the JVM (non-daemon app
  threads would otherwise keep a driverless node alive forever).
- The process manager couples `driverN -> nodeN` (`RestartCascades`): on driver
  crash it force-stops the node, restarts the driver (3 s), then the node.
  Validated end to end (kill -9 on a live driver; node rejoined as follower).
- Caveat (admin-gateway#13): after an admin-gateway restart, adopted processes
  are not crash-monitored, so the cascade does not fire for them until they are
  stop/started through the API again.

## Production checklist

1. Archives on NVMe, not tmpfs. On /dev/shm the Raft log grows ~165 B/order/node
   and outruns the snapshot cycle at high rates; a full tmpfs wedges followers
   and the admin tooling (see `docs/incidents/2026-07-02-*.md`).
2. Never run snapshot+housekeeping while any member is down, lagging, or
   recovering (openexch/match#35, strands the laggard permanently until fixed).
3. `sudo make tune-persist` (deploy/tuning/system-tuning.sh --persist) with
   `ISOLATED_CORES`/`NIC` set: applies the tuning AND persists it across reboots
   (`/etc/sysctl.d/99-openexchange.conf` + `openexchange-tuning.service` for
   THP/governor). Restart drivers after raising socket limits (sockets size at
   creation; the silent fallback is 208 KB and it costs ~25% throughput at the
   top end). Unpersisted sysctls reverting on reboot is exactly the 2026-07-03
   corruption chain (openexch/match#48): drivers crash-loop on SO_RCVBUF
   validation, nodes start against absent drivers, kill-mid-write corrupts the
   archive. After kernel upgrades, verify with `make tune-report` (shows
   runtime-vs-persisted drift).
4. GRUB: `isolcpus= nohz_full= rcu_nocbs=` for the driver + engine cores
   (guidance printed by the tuning script; boot params, applied manually).
5. `ENGINE_DRIVER_PROFILE=prod` + `SENDER/RECEIVER/CONDUCTOR_CORE`; one node
   per host for a true DEDICATED layout.
6. `TRANSPORT_IDLE_MODE=BUSY_SPIN` (default) on nodes.
7. `TRANSPORT_MTU`: keep 8192 only with jumbo frames end to end; else 1408.
8. Warm up after any node restart (30 s or ~10M ops) before trusting latency
   numbers; JIT tails are 37x on Max otherwise (measured, see perf baseline).
9. Verify bypass with `onload_stackdump` after deploy (step above).

## Known follow-ups

- `ClusterBackupApp` and the client processes (market gateway, OMS, load
  generator) still use `MediaDriver.launchEmbedded`; same treatment applies if
  they ever need bypass or GC isolation. Backup node health: openexch/match#36.
- Latency envelopes for the doc table await the `match-bench` RTT harness
  (plan Step 4): the load generator's µs numbers are ingress-accept latency,
  not end-to-end (egress batches on a 20 ms flush by design).
- Admin status could display per-node driver flavor/profile (read-only);
  deliberately not a runtime toggle.
