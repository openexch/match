#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# render-verify.sh: render the matching engine deploy files for a 3-member cluster, then check them.
#
# Usage: deploy/render-verify.sh [--out DIR] [--cluster-tools DIR] [--self-test]
#   --out DIR            render into DIR (default: a temporary dir, removed at exit)
#   --cluster-tools DIR  a cluster-tools checkout: its deploy/systemd/aeronmd@.service joins every host, so the
#                        driver dependency is verified against the real unit (default: ../cluster-tools next to
#                        this repo when present; otherwise a minimal stand-in unit is used and said so)
#   --self-test          also run the negative controls: each check, fed a broken render, must fail with its text
#
# Each host is a root tree: etc/systemd/system (the units), etc/openexchange (the env files), opt/openexchange (the
# launcher and a stand-in jar). Checks:
#   identity  every host carries byte-identical units and an identical cluster.env; member.env files differ only in
#             the id-derived lines (CLUSTER_NODE, METRICS_PORT)
#   ports     the node endpoint port is base + id * stride + 6, computed by the engine; an explicit METRICS_PORT
#             override must equal it. Rendered at stride 0 (one member per host, no override) and at stride 100
#             (several members per host, with the override written)
#   units     every unit restarts on failure but not on 78, backs off, runs as the engine user; the external units
#             bind to, order after and are wanted by their own driver, whose AERON_DIR default they share
#   analyze   systemd-analyze verify --root=<host> on every unit (when systemd-analyze exists)
#   launcher  sh -n and shellcheck (local binary, else the pinned koalaman/shellcheck image when docker has it);
#             match-launch refuses contradictions with 78 and builds the expected java command
# Exit: 0 all checks pass, 1 a check failed (each failure printed as FAIL <check>: <text>).
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=""
CT=""
SELF_TEST=0
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT=$2; shift 2 ;;
        --cluster-tools) CT=$2; shift 2 ;;
        --self-test) SELF_TEST=1; shift ;;
        *) echo "usage: $0 [--out DIR] [--cluster-tools DIR] [--self-test]" >&2; exit 64 ;;
    esac
done
if [ -z "$OUT" ]; then
    OUT=$(mktemp -d)
    trap 'rm -rf "$OUT"' EXIT
fi
if [ -z "$CT" ] && [ -f "$HERE/../../cluster-tools/deploy/systemd/aeronmd@.service" ]; then
    CT=$(cd "$HERE/../../cluster-tools" && pwd)
fi
SHELLCHECK_IMAGE=koalaman/shellcheck:v0.10.0@sha256:2097951f02e735b613f4a34de20c40f937a6c8f18ecb170612c88c34517221fb

BASE=9000
MEMBERS=3
FAILS=0
fail() { echo "FAIL $1: $2"; FAILS=$((FAILS + 1)); }
pass() { echo "ok   $1: $2"; }

# ---------------------------------------------------------------- render

render_host() { # root id stride
    local root=$1 id=$2 stride=$3
    mkdir -p "$root/etc/systemd/system" "$root/etc/openexchange/match" "$root/opt/openexchange/match/bin"
    cp "$HERE"/systemd/*.service "$root/etc/systemd/system/"
    if [ -n "$CT" ]; then
        cp "$CT/deploy/systemd/aeronmd@.service" "$root/etc/systemd/system/"
        mkdir -p "$root/opt/openexchange/cluster-tools/bin"
        cp "$CT/deploy/bin/aeronmd-launch" "$root/opt/openexchange/cluster-tools/bin/"
    else
        printf '[Unit]\nDescription=stand-in driver %%i\n[Service]\nEnvironment=AERON_DIR=/dev/shm/aeron-openexchange-%%i\nExecStart=/bin/true\n' \
            > "$root/etc/systemd/system/aeronmd@.service"
    fi
    cp "$HERE/bin/match-launch" "$root/opt/openexchange/match/bin/"
    : > "$root/opt/openexchange/match/match-cluster.jar"
    local hosts="" i
    for i in $(seq 0 $((MEMBERS - 1))); do hosts="${hosts:+$hosts,}match-$i.example.internal"; done
    printf 'CLUSTER_ADDRESSES=%s\nCLUSTER_PORT_BASE=%s\nCLUSTER_PORT_STRIDE=%s\nSETTLEMENT_JOURNAL_ENABLED=true\nSETTLEMENT_JOURNAL_ARCHIVE_BIND=0.0.0.0\nMATCH_ENGINE_FROM_CONFIG=true\n' \
        "$hosts" "$BASE" "$stride" > "$root/etc/openexchange/match/cluster.env"
    printf 'CLUSTER_NODE=%s\n' "$id" > "$root/etc/openexchange/match/member.env"
    # The engine computes the endpoint port; the stride-100 render also writes the optional override, which the
    # engine accepts only when it equals the rule's number.
    if [ "$stride" != 0 ]; then
        printf 'METRICS_PORT=%s\n' "$((BASE + id * stride + 6))" >> "$root/etc/openexchange/match/member.env"
    fi
    printf 'JAVA_HEAP=4g\nCPU_SET=\nJAVA_OPTS=-XX:+AlwaysPreTouch\n' > "$root/etc/openexchange/match/host.env"
    printf 'ME_AUTH_KEYS=\n' > "$root/etc/openexchange/match/secrets.env"
}

render() { # dir stride
    local dir=$1 stride=$2 i
    rm -rf "$dir"
    for i in $(seq 0 $((MEMBERS - 1))); do render_host "$dir/match-$i" "$i" "$stride"; done
}

# ---------------------------------------------------------------- checks (each prints its own failures)

envval() { sed -n "s/^$2=//p" "$1" | tail -1; }

check_identity() { # dir
    local dir=$1 ok=1 i u
    for i in $(seq 1 $((MEMBERS - 1))); do
        local other=$dir/match-$i
        for u in "$dir"/match-0/etc/systemd/system/*.service; do
            if ! cmp -s "$u" "$other/etc/systemd/system/$(basename "$u")"; then
                fail identity "$(basename "$u") differs between match-0 and $(basename "$other")"; ok=0
            fi
        done
        if ! cmp -s "$dir/match-0/etc/openexchange/match/cluster.env" "$other/etc/openexchange/match/cluster.env"; then
            fail identity "cluster.env differs between match-0 and $(basename "$other")"; ok=0
        fi
    done
    for i in $(seq 1 $((MEMBERS - 1))); do
        local a b
        a=$(grep -v -E '^(CLUSTER_NODE|METRICS_PORT)=' "$dir/match-0/etc/openexchange/match/member.env" || true)
        b=$(grep -v -E '^(CLUSTER_NODE|METRICS_PORT)=' "$dir/match-$i/etc/openexchange/match/member.env" || true)
        if [ "$a" != "$b" ]; then fail identity "member.env of match-$i differs from match-0 beyond the id lines"; ok=0; fi
        if [ "$(envval "$dir/match-$i/etc/openexchange/match/member.env" CLUSTER_NODE)" != "$i" ]; then
            fail identity "match-$i member.env does not carry CLUSTER_NODE=$i"; ok=0
        fi
    done
    [ $ok = 1 ] && pass identity "units and cluster.env identical on $MEMBERS member hosts"
    return 0
}

check_ports() { # dir
    local dir=$1 ok=1 h env node want got base stride
    for h in "$dir"/match-*; do
        base=$(envval "$h/etc/openexchange/match/cluster.env" CLUSTER_PORT_BASE)
        stride=$(envval "$h/etc/openexchange/match/cluster.env" CLUSTER_PORT_STRIDE)
        env=$h/etc/openexchange/match/member.env; node=$(envval "$env" CLUSTER_NODE)
        want=$((base + node * stride + 6))
        got=$(envval "$env" METRICS_PORT)
        # unset: the engine computes the rule's port; set: an override, which must equal it
        if [ -n "$got" ] && [ "$got" != "$want" ]; then
            fail ports "$(basename "$h"): METRICS_PORT=$got, the rule gives $want (base $base + id $node x stride $stride + 6)"
            ok=0
        fi
    done
    [ $ok = 1 ] && pass ports "node endpoint = base + id x stride + 6 on every host ($(basename "$dir"))"
    return 0
}

unit_has() { grep -qx -- "$2" "$1"; }

check_units() { # dir
    local dir=$1 ok=1 u name line drv
    for u in "$dir"/match-0/etc/systemd/system/match-*.service; do
        name=$(basename "$u")
        for line in Restart=on-failure RestartPreventExitStatus=78 SuccessExitStatus=143 RestartSec=2s \
            RestartSteps=6 RestartMaxDelaySec=60s StartLimitIntervalSec=0 User=openexchange; do
            unit_has "$u" "$line" || { fail units "$name lacks $line"; ok=0; }
        done
        case "$name" in
            *-embedded.service)
                if grep -q aeronmd@ "$u"; then fail units "$name (embedded) depends on a driver unit"; ok=0; fi
                grep -q -- 'embedded$' <(sed -n 's/^ExecStart=//p' "$u") ||
                    { fail units "$name does not start the embedded driver mode"; ok=0; }
                ;;
            *)
                drv=$(sed -n 's/^BindsTo=//p' "$u")
                case "$drv" in aeronmd@*.service) ;; *) fail units "$name has no BindsTo=aeronmd@<name>.service"; ok=0; continue ;; esac
                unit_has "$u" "After=$drv" || { fail units "$name is not ordered After=$drv"; ok=0; }
                sed -n 's/^WantedBy=//p' "$u" | tr ' ' '\n' | grep -qx "$drv" ||
                    { fail units "$name is not WantedBy=$drv (a restarted driver would not bring it back)"; ok=0; }
                grep -q -- 'external$' <(sed -n 's/^ExecStart=//p' "$u") ||
                    { fail units "$name does not start the external driver mode"; ok=0; }
                local inst=${drv#aeronmd@}; inst=${inst%.service}
                local mine theirs
                mine=$(sed -n 's/^Environment=AERON_DIR=//p' "$u")
                theirs=$(sed -n 's/^Environment=AERON_DIR=//p' "$dir/match-0/etc/systemd/system/aeronmd@.service" | sed "s/%i/$inst/g")
                [ "$mine" = "$theirs" ] ||
                    { fail units "$name AERON_DIR default '$mine' is not its driver's '$theirs'"; ok=0; }
                ;;
        esac
    done
    [ $ok = 1 ] && pass units "restart policy, driver binding and AERON_DIR agreement on every unit"
    return 0
}

check_analyze() { # dir
    if ! command -v systemd-analyze >/dev/null 2>&1; then
        echo "skip analyze: systemd-analyze not installed"
        return 0
    fi
    local dir=$1 ok=1 h u out
    for h in "$dir"/match-*; do
        # the distribution's own units (sysinit.target, network-online.target, ...) resolve inside the root
        if [ -d /usr/lib/systemd/system ] && [ ! -d "$h/usr/lib/systemd/system" ]; then
            mkdir -p "$h/usr/lib/systemd"
            cp -r /usr/lib/systemd/system "$h/usr/lib/systemd/"
        fi
        for u in "$h"/etc/systemd/system/match-*.service; do
            out=$(cd "$h/etc/systemd/system" && systemd-analyze verify --man=no --root="$h" "$(basename "$u")" 2>&1) || true
            if [ -n "$out" ]; then fail analyze "$(basename "$h")/$(basename "$u"): $out"; ok=0; fi
        done
    done
    [ $ok = 1 ] && pass analyze "systemd-analyze verify clean on every host's units${CT:+ (with the cluster-tools driver unit)}"
    [ -n "$CT" ] || echo "note analyze: no cluster-tools checkout, the driver unit was a stand-in"
    return 0
}

check_launcher() { # launcher
    local launcher=$1 ok=1 tmp rc out
    sh -n "$launcher" || { fail launcher "sh -n rejects $launcher"; ok=0; }
    if command -v shellcheck >/dev/null 2>&1; then
        out=$( (shellcheck -s sh "$launcher" && shellcheck "$HERE/render-verify.sh" "$HERE/oci/smoke.sh") 2>&1) ||
            { fail launcher "shellcheck: $out"; ok=0; }
    elif command -v docker >/dev/null 2>&1 && docker image inspect "$SHELLCHECK_IMAGE" >/dev/null 2>&1; then
        out=$(docker run --rm -v "$launcher:/w/launch:ro" -v "$HERE/render-verify.sh:/w/render-verify.sh:ro" \
            "$SHELLCHECK_IMAGE" -s sh /w/launch 2>&1 && docker run --rm \
            -v "$HERE/render-verify.sh:/w/render-verify.sh:ro" -v "$HERE/oci/smoke.sh:/w/smoke.sh:ro" \
            "$SHELLCHECK_IMAGE" /w/render-verify.sh /w/smoke.sh 2>&1) ||
            { fail launcher "shellcheck: $out"; ok=0; }
    else
        echo "skip shellcheck: neither shellcheck nor the $SHELLCHECK_IMAGE image is available"
    fi
    tmp=$(mktemp -d)
    # shellcheck disable=SC2016 # the stand-in java expands its own arguments
    printf '#!/bin/sh\nprintf "%%s\\n" "$TRANSPORT_DRIVER_MODE" "$@" > "%s/args"\n' "$tmp" > "$tmp/java"
    chmod +x "$tmp/java"
    : > "$tmp/match-cluster.jar"
    run() { # expected-rc VAR=value... -- launcher-args...
        local want=$1 vars=()
        shift
        while [ "$1" != -- ]; do vars+=("$1"); shift; done
        shift
        rc=0
        out=$(env -i PATH="$tmp:/usr/bin:/bin" MATCH_JAR="$tmp/match-cluster.jar" BASE_DIR="$tmp/state" "${vars[@]}" \
            sh "$launcher" "$@" 2>&1) || rc=$?
        [ "$rc" = "$want" ]
    }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 TRANSPORT_DRIVER_MODE=embedded AERON_DIR=/x -- external ||
        { fail launcher "a contradicting TRANSPORT_DRIVER_MODE was not refused with 78 (rc $rc): $out"; ok=0; }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 TRANSPORT_DRIVER_MODE=external -- embedded ||
        { fail launcher "embedded unit with TRANSPORT_DRIVER_MODE=external was not refused with 78 (rc $rc)"; ok=0; }
    run 78 JAVA_HEAP=1g -- embedded || { fail launcher "a member without CLUSTER_NODE was not refused (rc $rc)"; ok=0; }
    run 78 CLUSTER_NODE=0 -- embedded || { fail launcher "a start without JAVA_HEAP was not refused (rc $rc)"; ok=0; }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 -- external ||
        { fail launcher "external without AERON_DIR was not refused (rc $rc)"; ok=0; }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 CPU_SET=a-b -- embedded ||
        { fail launcher "a malformed CPU_SET was not refused (rc $rc)"; ok=0; }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 SETTLEMENT_JOURNAL_DIR=/proc/nope -- embedded ||
        { fail launcher "an unusable SETTLEMENT_JOURNAL_DIR was not refused (rc $rc)"; ok=0; }
    run 78 JAVA_HEAP=1g CLUSTER_NODE=0 -- member embedded ||
        { fail launcher "a second argument was not refused (rc $rc)"; ok=0; }
    if run 0 JAVA_HEAP=1g CLUSTER_NODE=1 AERON_DIR=/dev/shm/x SETTLEMENT_JOURNAL_DIR="$tmp/journal" \
        JAVA_OPTS="-Da=1 -Db=2" -- external; then
        local args; args=$(tr '\n' ' ' < "$tmp/args")
        case "$args" in
            "external "*"-Xms1g -Xmx1g -Da=1 -Db=2 -jar $tmp/match-cluster.jar ") ;;
            *) fail launcher "unexpected java command: $args"; ok=0 ;;
        esac
    else
        fail launcher "a valid member start did not exec java (rc $rc): $out"; ok=0
    fi
    rm -rf "$tmp"
    [ $ok = 1 ] && pass launcher "syntax, lint and refusals (78) of match-launch"
    return 0
}

run_all() { # dir0 dir100
    check_identity "$1"
    check_ports "$1"
    check_ports "$2"
    check_units "$1"
    check_analyze "$1"
}

# ---------------------------------------------------------------- main

render "$OUT/stride0" 0
render "$OUT/stride100" 100
echo "rendered: $OUT/stride0 (one member per host) and $OUT/stride100"
run_all "$OUT/stride0" "$OUT/stride100"
check_launcher "$HERE/bin/match-launch"

if [ $SELF_TEST = 1 ]; then
    expect_fail() { # name expected-text command...
        local name=$1 text=$2 out before=$FAILS; shift 2
        out=$("$@" 2>&1) || true
        FAILS=$before
        if grep -q -- "$text" <<<"$out"; then
            pass "self-test" "$name fails with '$text'"
        else
            fail "self-test" "$name did not fail with '$text'; got: $out"
        fi
    }
    neg=$OUT/neg
    render "$neg" 0
    printf '# host edit\n' >> "$neg/match-1/etc/systemd/system/match-member.service"
    expect_fail "a unit edited on one host" "match-member.service differs between match-0 and match-1" check_identity "$neg"
    render "$neg" 0
    printf 'METRICS_PORT=9501\n' >> "$neg/match-1/etc/openexchange/match/member.env"
    expect_fail "a node port off the rule" "METRICS_PORT=9501, the rule gives 9006" check_ports "$neg"
    render "$neg" 0
    sed -i '/^RestartPreventExitStatus=78$/d' "$neg/match-0/etc/systemd/system/match-member.service"
    expect_fail "a unit without RestartPreventExitStatus=78" "match-member.service lacks RestartPreventExitStatus=78" check_units "$neg"
    render "$neg" 0
    sed -i 's/^WantedBy=.*/WantedBy=multi-user.target/' "$neg/match-0/etc/systemd/system/match-member.service"
    expect_fail "an external unit not wanted by its driver" "match-member.service is not WantedBy=aeronmd@match.service" check_units "$neg"
    render "$neg" 0
    sed -i 's|^Environment=AERON_DIR=.*|Environment=AERON_DIR=/dev/shm/elsewhere|' "$neg/match-0/etc/systemd/system/match-member.service"
    expect_fail "a member AERON_DIR off its driver's" "AERON_DIR default '/dev/shm/elsewhere'" check_units "$neg"
    if command -v systemd-analyze >/dev/null 2>&1; then
        render "$neg" 0
        sed -i 's/^Restart=on-failure$/Restart=sometimes/' "$neg/match-2/etc/systemd/system/match-member-embedded.service"
        expect_fail "a unit systemd cannot parse" "Failed to parse service restart specifier" check_analyze "$neg"
        render "$neg" 0
        rm "$neg/match-1/etc/systemd/system/aeronmd@.service"
        expect_fail "a host without the driver unit" "Unit aeronmd@match.service not found" check_analyze "$neg"
    fi
    neglaunch=$OUT/match-launch-broken
    sed 's/refuse "TRANSPORT_DRIVER_MODE=/: "TRANSPORT_DRIVER_MODE=/' "$HERE/bin/match-launch" > "$neglaunch"
    expect_fail "a launcher that accepts a contradicting driver mode" "contradicting TRANSPORT_DRIVER_MODE was not refused" check_launcher "$neglaunch"
fi

if [ $FAILS -gt 0 ]; then
    echo "render-verify: $FAILS check(s) failed"
    exit 1
fi
echo "render-verify: all checks passed"
