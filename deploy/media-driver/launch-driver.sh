#!/usr/bin/env bash
#
# launch-driver.sh — start a standalone Aeron media driver for one instance.
#
# The matching engine (TRANSPORT_DRIVER_MODE=external) talks to this driver over
# shared-memory IPC only. Kernel bypass (Solarflare Onload / Mellanox VMA) is applied
# HERE, to the driver process, via LD_PRELOAD wrapping — the engine JVM never knows
# whether packets go through kernel sockets or a bypass stack. Without bypass hardware
# the same driver runs identically on kernel UDP (degraded-but-correct).
#
# Usage:
#   launch-driver.sh --instance <name> [--dir <aeron.dir>] [--profile prod|dev]
#                    [--onload auto|on|off] [--driver auto|c|java]
#
#   --instance  node0|node1|node2 (dir matches ClusterConfig naming) or any other
#               name, e.g. bench-ping / bench-pong (dir: /dev/shm/aeron-$USER-<name>-driver)
#   --dir       override the aeron.dir (default derived from --instance)
#   --profile   prod: DEDICATED threading + busy-spin idles + core pinning (default)
#               dev:  SHARED threading + backoff idles, no pinning (laptops, CI)
#   --onload    auto: wrap in onload iff `onload --version` works (default)
#               on:   require onload, fail if missing
#               off:  plain kernel UDP
#   --driver    auto: aeronmd if on PATH (or $AERONMD), else Java driver (default)
#
# Idempotent: exits 0 if this instance's driver is already running.
# The script execs into the driver, so the PID the caller sees IS the driver's PID
# and SIGTERM/SIGKILL propagate directly (required by the admin-gateway process manager).

set -euo pipefail

# ==================== CORE PINNING (prod profile) ====================
# Set these to pin the three DEDICATED driver threads to isolated cores
# (see deploy/tuning/system-tuning.sh and docs/kernel-bypass.md for the layout).
# Empty (default) = no pinning, a WARN is printed. Do not hardcode core IDs below.
SENDER_CORE="${SENDER_CORE:-}"
RECEIVER_CORE="${RECEIVER_CORE:-}"
CONDUCTOR_CORE="${CONDUCTOR_CORE:-}"

# C-driver idle strategy tokens (value syntax differs from the Java class names).
# "noop" = pure busy-spin, the lowest-latency option. Confirm against your aeronmd
# build with: aeronmd 2>&1 | head — unknown tokens are rejected at startup.
AERONMD_IDLE_PROD="${AERONMD_IDLE_PROD:-noop}"
AERONMD_IDLE_DEV="${AERONMD_IDLE_DEV:-backoff}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MATCH_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROPERTIES_FILE="${SCRIPT_DIR}/driver.properties"
MATCH_CLUSTER_JAR="${MATCH_CLUSTER_JAR:-${MATCH_DIR}/match-cluster/target/match-cluster.jar}"

INSTANCE=""
AERON_DIR_OVERRIDE=""
PROFILE="prod"
ONLOAD_MODE="auto"
DRIVER_MODE="auto"

log()  { echo "[launch-driver] $*"; }
warn() { echo "[launch-driver] WARN: $*" >&2; }
die()  { echo "[launch-driver] ERROR: $*" >&2; exit 1; }

usage() { sed -n '3,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --instance) INSTANCE="${2:?--instance needs a value}"; shift 2 ;;
        --dir)      AERON_DIR_OVERRIDE="${2:?--dir needs a value}"; shift 2 ;;
        --profile)  PROFILE="${2:?--profile needs a value}"; shift 2 ;;
        --onload)   ONLOAD_MODE="${2:?--onload needs a value}"; shift 2 ;;
        --driver)   DRIVER_MODE="${2:?--driver needs a value}"; shift 2 ;;
        -h|--help)  usage ;;
        *)          die "unknown argument: $1 (see --help)" ;;
    esac
done

[[ -n "${INSTANCE}" ]] || die "--instance is required (e.g. --instance node0)"
[[ "${PROFILE}" == "prod" || "${PROFILE}" == "dev" ]] || die "--profile must be prod|dev"
[[ "${ONLOAD_MODE}" =~ ^(auto|on|off)$ ]] || die "--onload must be auto|on|off"
[[ "${DRIVER_MODE}" =~ ^(auto|c|java)$ ]] || die "--driver must be auto|c|java"
[[ -f "${PROPERTIES_FILE}" ]] || die "missing ${PROPERTIES_FILE}"

# ==================== AERON DIR ====================
# nodeN instances MUST match ClusterConfig.java / TransportConfig.aeronDir naming
# (/dev/shm/aeron-<user>-<N>-driver) so engine defaults and admin-gateway
# housekeeping keep working unchanged.
if [[ -n "${AERON_DIR_OVERRIDE}" ]]; then
    AERON_DIR="${AERON_DIR_OVERRIDE}"
elif [[ "${INSTANCE}" =~ ^node([0-9]+)$ ]]; then
    AERON_DIR="/dev/shm/aeron-${USER}-${BASH_REMATCH[1]}-driver"
else
    AERON_DIR="/dev/shm/aeron-${USER}-${INSTANCE}-driver"
fi
PID_FILE="${AERON_DIR}.pid"

# ==================== IDEMPOTENCY ====================
if [[ -f "${PID_FILE}" ]]; then
    existing_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${existing_pid}" ]] && kill -0 "${existing_pid}" 2>/dev/null; then
        log "driver for ${INSTANCE} already running (PID ${existing_pid}, dir ${AERON_DIR})"
        exit 0
    fi
    rm -f "${PID_FILE}"
fi

# ==================== ONLOAD DETECTION ====================
ONLOAD_WRAP=()
if [[ "${ONLOAD_MODE}" != "off" ]]; then
    if command -v onload >/dev/null 2>&1 && onload --version >/dev/null 2>&1; then
        ONLOAD_WRAP=(onload --profile=latency)
        log "onload detected: driver will run under kernel bypass (verify with onload_stackdump)"
    elif [[ "${ONLOAD_MODE}" == "on" ]]; then
        die "--onload on requested but 'onload --version' failed (is Onload installed?)"
    else
        warn "onload not found — running on kernel UDP (fallback path, correct but slower)"
    fi
fi

# ==================== DRIVER SELECTION ====================
AERONMD_BIN="${AERONMD:-$(command -v aeronmd || true)}"
case "${DRIVER_MODE}" in
    c)    [[ -n "${AERONMD_BIN}" ]] || die "--driver c requested but aeronmd not found (set \$AERONMD)" ;;
    java) AERONMD_BIN="" ;;
    auto)
        if [[ -z "${AERONMD_BIN}" ]]; then
            warn "aeronmd not found — falling back to the Java media driver"
        fi
        ;;
esac
if [[ -z "${AERONMD_BIN}" ]]; then
    [[ -f "${MATCH_CLUSTER_JAR}" ]] || die "Java driver needs ${MATCH_CLUSTER_JAR} (run: make build-java)"
fi

# ==================== PROFILE / PINNING ====================
TASKSET_WRAP=()
PIN_CORES=""
if [[ "${PROFILE}" == "prod" ]]; then
    THREADING_MODE="DEDICATED"
    JAVA_IDLE="org.agrona.concurrent.BusySpinIdleStrategy"
    C_IDLE="${AERONMD_IDLE_PROD}"
    if [[ -n "${SENDER_CORE}" && -n "${RECEIVER_CORE}" && -n "${CONDUCTOR_CORE}" ]]; then
        PIN_CORES="${CONDUCTOR_CORE},${SENDER_CORE},${RECEIVER_CORE}"
    else
        warn "prod profile without SENDER_CORE/RECEIVER_CORE/CONDUCTOR_CORE — driver threads unpinned"
    fi
else
    THREADING_MODE="SHARED"
    JAVA_IDLE="org.agrona.concurrent.BackoffIdleStrategy"
    C_IDLE="${AERONMD_IDLE_DEV}"
fi

# ==================== MERGED RUNTIME PROPERTIES ====================
RUNTIME_PROPS="$(mktemp "${TMPDIR:-/tmp}/aeron-driver-${INSTANCE}.XXXXXX.properties")"
cp "${PROPERTIES_FILE}" "${RUNTIME_PROPS}"
{
    echo ""
    echo "# --- injected by launch-driver.sh (instance=${INSTANCE}, profile=${PROFILE}) ---"
    echo "aeron.dir=${AERON_DIR}"
    echo "aeron.threading.mode=${THREADING_MODE}"
    if [[ -n "${AERONMD_BIN}" ]]; then
        echo "aeron.conductor.idle.strategy=${C_IDLE}"
        echo "aeron.sender.idle.strategy=${C_IDLE}"
        echo "aeron.receiver.idle.strategy=${C_IDLE}"
        if [[ "${PROFILE}" == "prod" && -n "${PIN_CORES}" ]]; then
            # C driver supports per-thread affinity natively
            echo "aeron.conductor.cpu.affinity=${CONDUCTOR_CORE}"
            echo "aeron.sender.cpu.affinity=${SENDER_CORE}"
            echo "aeron.receiver.cpu.affinity=${RECEIVER_CORE}"
        fi
    else
        echo "aeron.conductor.idle.strategy=${JAVA_IDLE}"
        echo "aeron.sender.idle.strategy=${JAVA_IDLE}"
        echo "aeron.receiver.idle.strategy=${JAVA_IDLE}"
        echo "aeron.shared.idle.strategy=${JAVA_IDLE}"
    fi
} >> "${RUNTIME_PROPS}"

# Java driver has no per-thread affinity: pin the whole process to the union of cores
if [[ -z "${AERONMD_BIN}" && -n "${PIN_CORES}" ]]; then
    TASKSET_WRAP=(taskset -c "${PIN_CORES}")
fi

log "instance=${INSTANCE} profile=${PROFILE} dir=${AERON_DIR}"
log "driver=$([[ -n "${AERONMD_BIN}" ]] && echo "C (${AERONMD_BIN})" || echo "Java (${MATCH_CLUSTER_JAR})")"
log "bypass=$([[ ${#ONLOAD_WRAP[@]} -gt 0 ]] && echo onload || echo kernel-udp) pinning=${PIN_CORES:-none}"
log "properties: ${RUNTIME_PROPS}"

# exec keeps this shell's PID, so writing it before exec is the driver's PID
echo $$ > "${PID_FILE}"

if [[ -n "${AERONMD_BIN}" ]]; then
    exec "${ONLOAD_WRAP[@]+"${ONLOAD_WRAP[@]}"}" "${AERONMD_BIN}" "${RUNTIME_PROPS}"
else
    # Small heap: the driver's term buffers are memory-mapped, not heap
    exec "${ONLOAD_WRAP[@]+"${ONLOAD_WRAP[@]}"}" "${TASKSET_WRAP[@]+"${TASKSET_WRAP[@]}"}" \
        java \
        -Xms512m -Xmx512m \
        -XX:+AlwaysPreTouch \
        -XX:+PerfDisableSharedMem \
        --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens java.base/java.nio=ALL-UNNAMED \
        -cp "${MATCH_CLUSTER_JAR}" \
        io.aeron.driver.MediaDriver "${RUNTIME_PROPS}"
fi
