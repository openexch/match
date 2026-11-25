#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# smoke.sh: build the matching engine image from a published match-cluster.jar and start one member in it until /health is 200.
#
# Usage: deploy/oci/smoke.sh --jar <match-cluster.jar> [--cluster-tools-image <image>] [--tag <tag>]
#   --cluster-tools-image  also run the member with the external driver: aeronmd from that image (built from the
#                          cluster-tools deploy/oci/Dockerfile) as a sidecar sharing the network namespace and the
#                          driver directory; then kill the driver and require the member to exit non-zero
#
# A one-member cluster (CLUSTER_ADDRESSES=localhost, node 0, stride 0), read-only root, non-root uid, small terms.
# Every container and volume this script creates is removed at exit. Docker builds are heavy on a shared box: run
# this under the box's build lock.
# Exit: 0 every step passed, 1 a step failed.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JAR=""
CT_IMAGE=""
TAG=openexchange/match:smoke
while [ $# -gt 0 ]; do
    case "$1" in
        --jar) JAR=$2; shift 2 ;;
        --cluster-tools-image) CT_IMAGE=$2; shift 2 ;;
        --tag) TAG=$2; shift 2 ;;
        *) echo "usage: $0 --jar <match-cluster.jar> [--cluster-tools-image <image>] [--tag <tag>]" >&2; exit 64 ;;
    esac
done
[ -r "$JAR" ] || { echo "smoke: --jar <match-cluster.jar> is required" >&2; exit 64; }

ID=match-smoke-$$
CTX=$(mktemp -d)
cleanup() {
    docker rm -f "$ID-emb" "$ID-drv" "$ID-ext" "$ID-neg" >/dev/null 2>&1 || true
    docker volume rm -f "$ID-state" "$ID-state2" "$ID-aeron" >/dev/null 2>&1 || true
    rm -rf "$CTX"
}
trap cleanup EXIT
FAILS=0
fail() { echo "FAIL $1"; FAILS=$((FAILS + 1)); }
pass() { echo "ok   $1"; }

cp "$JAR" "$CTX/match-cluster.jar"
docker build -q -f "$HERE/oci/Dockerfile" --build-context deploy="$HERE" -t "$TAG" "$CTX" >/dev/null
pass "image $TAG built from $(sha256sum "$JAR" | cut -c1-12) $(basename "$JAR")"

ENVS=(-e CLUSTER_ADDRESSES=localhost -e CLUSTER_PORT_BASE=9000 -e CLUSTER_PORT_STRIDE=0 -e CLUSTER_NODE=0
      -e JAVA_HEAP=768m -e TRANSPORT_LOG_TERM_LENGTH=8m -e TRANSPORT_TERM_LENGTH=1m
      -e TRANSPORT_SNAPSHOT_TERM_LENGTH=1m -e TRANSPORT_IDLE_MODE=backoff -e MATCH_ENGINE_FROM_CONFIG=true
      -e SETTLEMENT_JOURNAL_ENABLED=true -e ME_AUTH_KEYS=)
RO=(--read-only --tmpfs "/tmp:rw,size=64m")

wait_health() { # container host-port seconds
    local c=$1 port=$2 secs=$3 code=000 i
    for ((i = 0; i < secs * 2; i++)); do
        code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/health" || true)
        [ "$code" = 200 ] && return 0
        [ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" = true ] || break
        sleep 0.5
    done
    echo "  /health $code; last log lines of $c:"
    docker logs --tail 30 "$c" 2>&1 | sed 's/^/  | /'
    return 1
}

# A start that names no role is refused before any JVM.
rc=0
docker run --rm --name "$ID-neg" "${RO[@]}" "$TAG" >/dev/null 2>&1 || rc=$?
if [ "$rc" = 78 ]; then pass "no driver mode: exit 78"; else fail "no driver mode: exit $rc, expected 78"; fi
rc=0
docker run --rm --name "$ID-neg" "${RO[@]}" "${ENVS[@]}" -e TRANSPORT_DRIVER_MODE=external "$TAG" embedded \
    >/dev/null 2>&1 || rc=$?
if [ "$rc" = 78 ]; then pass "env driver mode contradicting the command: exit 78"; else fail "env driver mode contradicting the command: exit $rc, expected 78"; fi

# Embedded: one member to /health 200.
docker volume create "$ID-state" >/dev/null
docker run -d --name "$ID-emb" "${RO[@]}" --shm-size 512m -v "$ID-state:/var/lib/openexchange/match" \
    -p 127.0.0.1::9006 "${ENVS[@]}" "$TAG" embedded >/dev/null
port=$(docker port "$ID-emb" 9006/tcp | head -1 | sed 's/.*://')
if wait_health "$ID-emb" "$port" 120; then
    pass "embedded member: /health 200 on 9006 (read-only root: $(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$ID-emb"), uid $(docker exec "$ID-emb" id -u))"
else
    fail "embedded member never answered /health 200"
fi
docker stop -t 30 "$ID-emb" >/dev/null
rc=$(docker inspect -f '{{.State.ExitCode}}' "$ID-emb")
case "$rc" in 0 | 143) pass "embedded member stopped on SIGTERM with $rc" ;; *) fail "embedded member SIGTERM exit $rc" ;; esac

# External: aeronmd sidecar from the cluster-tools image, then the driver dies and the member must exit non-zero.
if [ -n "$CT_IMAGE" ]; then
    docker volume create --driver local --opt type=tmpfs --opt device=tmpfs \
        --opt o=size=768m,uid=10001,gid=10001 "$ID-aeron" >/dev/null
    docker volume create "$ID-state2" >/dev/null
    docker run -d --name "$ID-drv" "${RO[@]}" -v "$ID-aeron:/var/run/aeron" -p 127.0.0.1::9006 \
        -e AERON_DIR=/var/run/aeron/match -e AERON_DRIVER_PROFILE=dev "$CT_IMAGE" \
        /opt/openexchange/cluster-tools/bin/aeronmd-launch run >/dev/null
    if docker exec -e AERON_DIR=/var/run/aeron/match "$ID-drv" /opt/openexchange/cluster-tools/bin/aeronmd-launch wait-cnc; then
        pass "external driver: cnc.dat present"
    else
        fail "external driver: no cnc.dat"; docker logs --tail 20 "$ID-drv" 2>&1 | sed 's/^/  | /'
    fi
    docker run -d --name "$ID-ext" "${RO[@]}" --network "container:$ID-drv" -v "$ID-aeron:/var/run/aeron" \
        -v "$ID-state2:/var/lib/openexchange/match" -e AERON_DIR=/var/run/aeron/match "${ENVS[@]}" \
        "$TAG" external >/dev/null
    port=$(docker port "$ID-drv" 9006/tcp | head -1 | sed 's/.*://')
    if wait_health "$ID-ext" "$port" 120; then
        pass "external member: /health 200 through the sidecar driver"
        docker kill "$ID-drv" >/dev/null
        rc=$(timeout 60 docker wait "$ID-ext" || echo timeout)
        case "$rc" in
            0 | timeout) fail "external member after its driver died: exit $rc, expected non-zero" ;;
            *) pass "external member exited $rc after its driver was killed" ;;
        esac
    else
        fail "external member never answered /health 200"
    fi
fi

if [ $FAILS -gt 0 ]; then
    echo "smoke: $FAILS step(s) failed"
    exit 1
fi
echo "smoke: all steps passed"
