#!/usr/bin/env bash
# One-time provisioning of TimescaleDB for market-data persistence (match-gateway).
#
#   sudo bash deploy/timescale/provision-timescaledb.sh
#
# Idempotent: safe to re-run (re-running rotates the market role's password and
# rewrites the drop-in to match). Installs the timescaledb extension into the
# system PostgreSQL 16, creates the `marketdata` database (+ `marketdata_test`
# for the integration tests) owned by the `market` role, and writes the
# password drop-in for the admin service.
#
# Deliberately NOT run: timescaledb-tune. It sizes shared_buffers/work_mem for
# a database-first machine; this box's RAM and cores belong to the matching
# engine. Only the minimal preload config below is applied.
#
# The postgres restart in step 4 briefly interrupts OMS persistence; the OMS
# degrades gracefully and reconnects on its own (verify: curl :8080/api/v1/health).
#
# Afterwards, as the login user:
#   systemctl --user daemon-reload && systemctl --user restart admin
#   curl -X POST localhost:8082/api/admin/processes/market/restart
set -euo pipefail

[ "$(id -u)" -eq 0 ] || { echo "run with sudo"; exit 1; }

REAL_USER="${SUDO_USER:?must be run via sudo, not as a root login}"
REAL_HOME="$(getent passwd "$REAL_USER" | cut -d: -f6)"
DROPIN_DIR="$REAL_HOME/.config/systemd/user/admin.service.d"
DROPIN="$DROPIN_DIR/marketdata-db.conf"

echo "== 1/7 Timescale apt repository"
if [ ! -f /etc/apt/sources.list.d/timescaledb.list ]; then
    . /etc/os-release
    echo "deb https://packagecloud.io/timescale/timescaledb/ubuntu/ ${VERSION_CODENAME} main" \
        > /etc/apt/sources.list.d/timescaledb.list
    wget --quiet -O - https://packagecloud.io/timescale/timescaledb/gpgkey \
        | gpg --dearmor --yes -o /etc/apt/trusted.gpg.d/timescaledb.gpg
fi
apt-get update -qq

echo "== 2/7 Install extension for PostgreSQL 16 (held against surprise upgrades)"
apt-get install -y timescaledb-2-postgresql-16 timescaledb-tools
# A timescale package upgrade loads a new .so on the next PG restart and then
# needs a manual ALTER EXTENSION timescaledb UPDATE — hold to keep that a
# deliberate step, not an apt side effect.
apt-mark hold timescaledb-2-postgresql-16 >/dev/null

echo "== 3/7 Minimal PostgreSQL config (preload only, no tuning)"
install -d /etc/postgresql/16/main/conf.d
cat > /etc/postgresql/16/main/conf.d/timescaledb.conf <<'EOF'
shared_preload_libraries = 'timescaledb'
timescaledb.telemetry_level = off
timescaledb.max_background_workers = 8
EOF

echo "== 4/7 Restart PostgreSQL (brief OMS persistence blip; it reconnects)"
systemctl restart postgresql@16-main
sleep 3

echo "== 5/7 Role + databases + extension"
PASS=""
if [ -f "$DROPIN" ]; then
    PASS=$(grep -oP 'MARKET_PG_PASSWORD=\K[^"]+' "$DROPIN" || true)
fi
if [ -z "$PASS" ]; then
    PASS=$(openssl rand -hex 16)
fi
sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'market') THEN
        CREATE ROLE market LOGIN PASSWORD '$PASS';
    ELSE
        ALTER ROLE market WITH LOGIN PASSWORD '$PASS';
    END IF;
END \$\$;
SQL
for dbname in marketdata marketdata_test; do
    if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$dbname'" | grep -q 1; then
        sudo -u postgres createdb -O market "$dbname"
    fi
    sudo -u postgres psql -d "$dbname" -c "CREATE EXTENSION IF NOT EXISTS timescaledb;"
done

echo "== 6/7 Password drop-in for the admin service (children inherit it)"
install -d -o "$REAL_USER" -g "$REAL_USER" "$DROPIN_DIR"
(
    umask 077
    cat > "$DROPIN" <<EOF
[Service]
Environment="MARKET_PG_PASSWORD=$PASS"
EOF
)
chown "$REAL_USER:$REAL_USER" "$DROPIN"

echo "== 7/7 Verify as the app role"
PGPASSWORD="$PASS" psql -h localhost -U market -d marketdata -tAc \
    "SELECT 'timescaledb ' || extversion FROM pg_extension WHERE extname='timescaledb';"

echo
echo "Done. Next, as $REAL_USER:"
echo "  systemctl --user daemon-reload && systemctl --user restart admin"
echo "  curl -X POST localhost:8082/api/admin/processes/market/restart"
