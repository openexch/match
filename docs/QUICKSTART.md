# Quick start: fresh clone to a working stack

Goal: a 3-node matching cluster, the OMS with a durable Postgres ledger,
the admin gateway supervising everything, and the trading UI, on one Linux
box. The same cluster-plus-OMS bring-up runs on every CI commit (the oms
repo's `e2e` job boots the full stack on a clean runner and kills the
leader mid-load), so these steps are exercised continuously.

## Prerequisites

- Linux with a writable `/dev/shm` (Aeron lives there)
- Java 21, Maven 3.9+
- Go 1.23+
- PostgreSQL 14+ running locally, with the `psql` client
- Redis 7 (optional: the OMS falls back to in-memory without it)
- Node 22 (only for the trading UI)

## The one-block version

Everything below is copy-pasteable as a single block. The four repos
**must be cloned as siblings with these exact directory names** (the admin
gateway locates its peers by layout; override with `MATCH_PROJECT_DIR` /
`OMS_PROJECT_DIR` if you deviate). Note that the `oms` repo is cloned into
a directory named `order-management`.

```bash
set -euo pipefail

# 0. Socket buffers for Aeron replay channels (elections hang without this).
sudo sysctl -w net.core.rmem_max=16777216 net.core.wmem_max=16777216

# 1. Clone the stack (siblings, exact names).
mkdir -p openexchange && cd openexchange
git clone https://github.com/openexch/match.git
git clone https://github.com/openexch/oms.git order-management
git clone https://github.com/openexch/admin-gateway.git
git clone https://github.com/openexch/trading-ui.git

# 2. Build. match is installed to the local Maven repo because oms depends
#    on com.match:match-common (not on Maven Central).
(cd match && mvn -B clean install -DskipTests)
(cd order-management && mvn -B clean package -DskipTests \
  && cp oms-app/target/oms-app-1.0-SNAPSHOT.jar oms-app/target/oms-app.jar)
(cd admin-gateway && go build -o admin-gateway .)

# 3. Database. The OMS does not auto-migrate; apply the schema once.
sudo -u postgres psql -c "CREATE ROLE oms LOGIN PASSWORD 'oms-dev'" || true
sudo -u postgres createdb -O oms oms || true
PGPASSWORD=oms-dev psql -h localhost -U oms -d oms -v ON_ERROR_STOP=1 \
  -f order-management/oms-persistence/src/main/resources/db/migration/V001__init_schema.sql

# 4. Start the stack under the admin gateway. Children inherit this
#    environment; dev auth mode is for local evaluation only.
cd admin-gateway
export OMS_POSTGRES_PASSWORD=oms-dev
export OMS_AUTH_MODE=dev
nohup ./admin-gateway > admin.log 2>&1 &
sleep 2
curl -X POST http://localhost:8082/api/admin/processes/start-all
```

## Verify

```bash
# One node is LEADER, all three healthy:
curl -s http://localhost:8082/api/admin/status | python3 -m json.tool | head -40

# OMS is up and connected to the cluster:
curl -s http://localhost:8080/api/v1/health
```

Then the UI:

```bash
cd trading-ui && npm ci && npm run dev -- --port 5173
# open http://localhost:5173
```

The dev server proxies `/api/v1` to the OMS (8080), `/ws` to market data
(8081), and `/api/admin` to the admin gateway (8082). The default
`VITE_AUTH_TOKEN=dev:1` matches `OMS_AUTH_MODE=dev`.

## Notes and troubleshooting

- **Media drivers**: nodes default to external Aeron media drivers; the
  launcher prefers a native `aeronmd` if present and falls back to the Java
  driver automatically. `ENGINE_DRIVER_MODE=embedded` (in the admin
  gateway's environment) skips external drivers entirely; the profile
  defaults to `dev` (shared threads, backoff idling), which is the right
  choice on a laptop or shared box.
- **Elections hang / "log replication has not progressed"**: the sysctl in
  step 0 was skipped or did not persist. `sudo make tune-persist` in
  `match/` makes it boot-persistent.
- **OMS runs but nothing persists**: PostgreSQL credentials are wrong or
  the schema was not applied; the OMS deliberately degrades to in-memory.
  Look for "PostgreSQL persistence initialized" in the OMS log
  (`~/.local/log/cluster/oms.log`).
- **Auth failures from the API**: the shipped default is `api-key` mode
  with no keys, which rejects everything. This quick start runs `dev` mode;
  see `docs/CONFIGURATION.md` for real modes.
- **Ports in use**: the stack claims 8080 (OMS), 8081 (market WS), 8082
  (admin), 9000+ (cluster), 9090 (gRPC), 9091/9093 (egress), 9500+
  (metrics).
- **Full E2E proof**: `order-management/e2e/failover_e2e.py` boots an
  isolated cluster plus OMS, kills the leader mid-load, and asserts exact
  fills, balances, and ledger conservation. On a box that also runs another
  stack, set `E2E_CPUSET` (see `order-management/e2e/README.md`).
