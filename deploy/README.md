# Deploying the matching engine

Units, a launcher and an image definition for running matching engine cluster members on hosts you manage. One member per host; the unit files are the same on every host, and the host's identity is one env file.

| Path | What |
|---|---|
| `systemd/match-member.service` | a member with the external media driver (production) |
| `systemd/match-member-embedded.service` | a member with the driver inside the JVM (development and demo) |
| `bin/match-launch` | the command every unit and the image run: checks the driver mode and the knobs it owns, then execs the JVM |
| `env/*.env.example` | the env files, one per scope |
| `oci/Dockerfile`, `oci/smoke.sh` | the image (published jar + Java 21, non-root, read-only root) and its smoke test |
| `render-verify.sh` | renders a 3-member cluster and checks the result |
| `media-driver/driver.properties` | the external driver's properties: cluster-tools publishes a byte copy of this file (pinned by commit and sha256) as the `driver.properties` its `aeronmd@.service` loads |
| `media-driver/launch-driver.sh` | the driver launcher for a process manager or a manual run; the systemd units use the cluster-tools `aeronmd@.service` instead |
| `tuning/`, `oom-protect/`, `timescale/` | host tuning (the socket buffers `driver.properties` needs, isolated cores), OOM protection, the market data database |

The external media driver unit, `aeronmd@.service`, ships with cluster-tools (`deploy/systemd`).

## Install on a member host

1. Create the user both the engine and its driver run as: `useradd --system --no-create-home --shell /usr/sbin/nologin openexchange`.
2. Put the published `match-cluster.jar` at `/opt/openexchange/match/match-cluster.jar` and `bin/match-launch` at
   `/opt/openexchange/match/bin/match-launch` (mode 0755). Install Java 21 (`java` on the unit's PATH, or set `JAVA`).
3. Copy the units to `/etc/systemd/system/`. For the external driver, install the cluster-tools set at
   `/opt/openexchange/cluster-tools/` and its `aeronmd@.service`.
4. Write the env files under `/etc/openexchange/match/`:

   | File | Scope | Holds |
   |---|---|---|
   | `cluster.env` | the same on every host | `CLUSTER_ADDRESSES`, `CLUSTER_PORT_BASE`, `CLUSTER_PORT_STRIDE`, the ME journal and transport settings |
   | `member.env` | this host | `CLUSTER_NODE` (and `METRICS_PORT` only as an optional override) |
   | `host.env` | this host | `JAVA_HEAP`, `CPU_SET`, `JAVA_OPTS` |
   | `secrets.env` | root:root 0600 | `ME_AUTH_KEYS` (systemd reads it before dropping privileges) |

5. `systemctl enable --now match-member.service` (or `match-member-embedded.service`; the two conflict).
   Enabling the external unit also links it into `aeronmd@match.service.wants/`.

The cluster state lives in `/var/lib/openexchange/match/cluster` (`BASE_DIR`), the ME journal in
`/var/lib/openexchange/match/journal` (`SETTLEMENT_JOURNAL_DIR`); both are unit defaults an env file can move.

## Ports

`port = CLUSTER_PORT_BASE + id * CLUSTER_PORT_STRIDE + offset`: offsets 1 to 5 are UDP (archive control, client,
member, log, transfer), offset 6 is the node HTTP endpoint (`/health`, `/ready`, `/metrics`), offset 10 (UDP) the
ME journal archive control. With stride 0 (one member per host) every host uses the same ports: 9001 to 9006 and
9010 at the default base. The engine computes the node endpoint port from the rule; `METRICS_PORT` is an optional
explicit override, accepted only when it equals the rule's number (any other value refuses the boot).

## Driver modes and restarts

- External: the member unit `BindsTo=` and is ordered `After=` its driver, `aeronmd@match.service`, which turns active
  only once its `cnc.dat` exists. When the driver stops or dies, systemd stops the member; when systemd restarts the
  driver, the member comes back with it (the member is `WantedBy=` its driver). Stopping only the member keeps it
  stopped. Stopping the driver stops both.
- Embedded: the driver runs inside the JVM; no driver unit.
- `match-launch` sets `TRANSPORT_DRIVER_MODE` from the unit and refuses an env file that says otherwise.
- `Restart=on-failure` with a backoff from 2 s to 60 s; exit 78 (a refused setting) is not restarted, exit 0 (the
  cluster was terminated) is not restarted, 143 (SIGTERM) is a clean stop.

## Memory and CPU

`JAVA_HEAP` (required), `CPU_SET` (taskset list) and `JAVA_OPTS` are env knobs in `host.env`. cgroup limits are unit
properties, which systemd does not take from env files; set them per host in a drop-in:

```ini
# /etc/systemd/system/match-member.service.d/limits.conf
[Service]
MemoryMax=12G
CPUQuota=400%
# a state root or journal outside /var/lib/openexchange/match also needs:
#ReadWritePaths=/data/match
```

## Image

```sh
docker build -f deploy/oci/Dockerfile --build-context deploy=deploy -t openexchange/match <dir with match-cluster.jar>
docker run --read-only --tmpfs /tmp --shm-size 1g -v match-state:/var/lib/openexchange/match \
    --env-file cluster.env --env-file member.env --env-file host.env --env-file secrets.env \
    openexchange/match embedded
```

The image runs as uid 10001 with a read-only root; state and the ME journal live on the `/var/lib/openexchange/match` volume. For the
external driver, run the cluster-tools image's `aeronmd` as a sidecar: the member shares its network namespace
(`--network container:<driver>`) and its driver directory (a shared tmpfs volume, `AERON_DIR` in both), and is
started with `external`.

## Tests

- `deploy/render-verify.sh [--cluster-tools <checkout>] [--self-test]`: renders a 3-member cluster at stride 0 and
  100, then checks unit identity across hosts, the port rule, the restart and driver wiring,
  `systemd-analyze verify` on every unit, and the launcher (syntax, shellcheck, its refusals). `--self-test` feeds
  each check a broken render and requires it to fail with its message.
- `deploy/oci/smoke.sh --jar <match-cluster.jar> [--cluster-tools-image <image>]`: builds the image and starts a
  one-member cluster in it until `/health` answers 200; with the cluster-tools image it also runs the member on an
  external driver sidecar and requires the member to exit non-zero when the driver is killed.
