#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# system-tuning.sh — OS tuning for the Open Exchange low-latency transport.
#
# Idempotent: every mutation checks current state first and skips when already
# applied. Degrades to WARN (never fails) when a tool or permission is missing,
# so it is safe on a dev laptop without sudo. Prints a before/after report and
# a compact diff of what changed.
#
# Usage:
#   system-tuning.sh [--report]   # report only, change nothing
#                    [--strict]   # exit 1 if any change FAILED (default: exit 0)
#                    [--persist]  # also persist across reboots: write the sysctl
#                                 # set to /etc/sysctl.d/99-openexchange.conf and
#                                 # install a boot oneshot unit for THP=never +
#                                 # performance governor (openexchange-tuning.service)
#
# --report also shows drift between runtime values and the persisted sysctl.d
# file, so `make tune-report` catches a kernel upgrade or manual change that
# left boot state diverging from the running config.
#
# Relationship to `make optimize-os`: that target keeps broader system knobs
# (TCP, swappiness, scheduler). This script owns the TRANSPORT-critical set and
# the pinned-core/IRQ layout; `make tune` invokes it. Values here must stay in
# sync with deploy/media-driver/driver.properties (4 MB socket buffers).
#
# Boot-parameter guidance (NOT applied by this script — kernel cmdline):
#   For hard isolation of the media driver + engine cores, add to GRUB
#   (/etc/default/grub, GRUB_CMDLINE_LINUX_DEFAULT), then update-grub + reboot:
#     isolcpus=<ISOLATED_CORES>    # kernel scheduler keeps tasks off these cores
#     nohz_full=<ISOLATED_CORES>   # no scheduler tick on busy-spin cores
#     rcu_nocbs=<ISOLATED_CORES>   # RCU callbacks handled elsewhere
#   Only then does BusySpinIdleStrategy get truly quiet cores. Without isolcpus,
#   taskset pinning still works but the kernel may schedule other tasks there.

set -euo pipefail

# ==================== TUNABLES (env-overridable) ====================
# Cores reserved for media driver + engine busy-spin threads. Empty (default):
# IRQ moves and irqbalance banning are skipped with a WARN. Example: "2-11"
ISOLATED_CORES="${ISOLATED_CORES:-}"
# NIC carrying Aeron UDP traffic. Empty: auto-detect from the default route.
NIC="${NIC:-}"
# Socket buffer ceiling. Driver requests 4 MB; 16 MB gives headroom and matches
# make optimize-os. Sockets size themselves AT CREATION: restart the media
# drivers after raising this or they silently keep the old (208 KB!) buffers.
RMEM_MAX_TARGET="${RMEM_MAX_TARGET:-16777216}"
WMEM_MAX_TARGET="${WMEM_MAX_TARGET:-16777216}"
# 2 MB huge pages to reserve (Aeron on /dev/shm works without; best-effort).
NR_HUGEPAGES="${NR_HUGEPAGES:-1024}"

REPORT_ONLY=0
STRICT=0
PERSIST=0
for arg in "$@"; do
    case "$arg" in
        --report) REPORT_ONLY=1 ;;
        --strict) STRICT=1 ;;
        --persist) PERSIST=1 ;;
        *) echo "unknown flag: $arg (use --report|--strict|--persist)" >&2; exit 1 ;;
    esac
done

# ==================== PERSISTENCE TARGETS (env-overridable for tests) ====================
SYSCTL_PERSIST_FILE="${SYSCTL_PERSIST_FILE:-/etc/sysctl.d/99-openexchange.conf}"
TUNING_UNIT_NAME="${TUNING_UNIT_NAME:-openexchange-tuning.service}"
TUNING_UNIT_FILE="${TUNING_UNIT_FILE:-/etc/systemd/system/${TUNING_UNIT_NAME}}"

# Full boot-persisted sysctl set: the transport-critical values owned by this
# script PLUS the broader `make optimize-os` set, so one file covers both.
# Keys absent on the running kernel are commented out at write time.
desired_sysctls() {
    cat <<EOF
net.core.rmem_max=$RMEM_MAX_TARGET
net.core.wmem_max=$WMEM_MAX_TARGET
net.core.rmem_default=1048576
net.core.wmem_default=1048576
net.core.netdev_max_backlog=30000
net.core.somaxconn=4096
net.ipv4.tcp_rmem=4096 1048576 16777216
net.ipv4.tcp_wmem=4096 1048576 16777216
net.ipv4.tcp_fastopen=3
net.ipv4.tcp_timestamps=0
net.ipv4.tcp_sack=0
net.ipv4.tcp_low_latency=1
vm.swappiness=0
vm.dirty_ratio=10
vm.dirty_background_ratio=5
vm.zone_reclaim_mode=0
vm.nr_hugepages=$NR_HUGEPAGES
fs.file-max=2097152
kernel.sched_min_granularity_ns=10000000
kernel.sched_wakeup_granularity_ns=15000000
kernel.sched_migration_cost_ns=5000000
EOF
}

FAILS=0
log()  { echo "[tune] $*"; }
ok()   { echo "[tune]   OK: $*"; }
skip() { echo "[tune]   already set: $*"; }
warn() { echo "[tune]   WARN: $*" >&2; FAILS=$((FAILS+1)); }

# Privilege helper: direct write as root, sudo -n otherwise, WARN when neither works.
SUDO=""
if [[ $EUID -ne 0 ]]; then
    if sudo -n true 2>/dev/null; then SUDO="sudo -n"; else SUDO="__nosudo__"; fi
fi
priv_write() { # value file
    if [[ "$SUDO" == "__nosudo__" ]]; then return 1; fi
    echo "$1" | $SUDO tee "$2" > /dev/null 2>&1
}
priv_sysctl() { # key value
    if [[ "$SUDO" == "__nosudo__" ]]; then return 1; fi
    $SUDO sysctl -qw "$1=$2" > /dev/null 2>&1
}

# NIC auto-detect from the default route
if [[ -z "$NIC" ]]; then
    NIC="$(ip -o route show default 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="dev") print $(i+1)}' | head -1)"
fi

# ==================== REPORT ====================
report() {
    echo "-------------------------------------------------------------"
    echo "governors:        $(cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor 2>/dev/null | sort | uniq -c | awk '{printf "%s x%s  ", $2, $1}')"
    echo "rmem_max/wmem_max: $(sysctl -n net.core.rmem_max 2>/dev/null) / $(sysctl -n net.core.wmem_max 2>/dev/null) (target ${RMEM_MAX_TARGET}/${WMEM_MAX_TARGET})"
    echo "irqbalance:       $(systemctl is-active irqbalance 2>/dev/null || true), banned_cpus=$(grep -hoP 'IRQBALANCE_BANNED_CPUS=\K.*' /etc/default/irqbalance 2>/dev/null || echo unset)"
    echo "hugepages:        total=$(grep -oP 'HugePages_Total:\s*\K\d+' /proc/meminfo) free=$(grep -oP 'HugePages_Free:\s*\K\d+' /proc/meminfo) (target ${NR_HUGEPAGES})"
    echo "THP:              $(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || echo n/a)"
    if [[ -n "$NIC" ]] && command -v ethtool >/dev/null 2>&1; then
        echo "nic ($NIC) coalesce: $(ethtool -c "$NIC" 2>/dev/null | grep -E '^(rx-usecs|tx-usecs|Adaptive)' | tr '\n' ' ' || echo unsupported)"
    else
        echo "nic:              ${NIC:-none-detected} (ethtool $(command -v ethtool >/dev/null && echo present || echo MISSING))"
    fi
    echo "boot params:      isolcpus=$(grep -oP 'isolcpus=\K\S+' /proc/cmdline || echo unset)  nohz_full=$(grep -oP 'nohz_full=\K\S+' /proc/cmdline || echo unset)  rcu_nocbs=$(grep -oP 'rcu_nocbs=\K\S+' /proc/cmdline || echo unset)"
    if [[ -n "$NIC" ]]; then
        local irqs
        irqs=$(grep -w "$NIC" /proc/interrupts 2>/dev/null | awk -F: '{print $1}' | tr -d ' ' | tr '\n' ',' | sed 's/,$//')
        echo "nic irqs:         ${irqs:-none} (affinity: $(for i in ${irqs//,/ }; do cat /proc/irq/$i/smp_affinity_list 2>/dev/null | tr '\n' ' '; done))"
    fi
    echo "/dev/shm:         $(df -h /dev/shm 2>/dev/null | awk 'NR==2{print $3" used / "$2" ("$5")"}')  aeron archive: $(du -sh /dev/shm/aeron-cluster 2>/dev/null | cut -f1 || echo none)"
    # Boot persistence + runtime-vs-persisted drift
    if [[ -f "$SYSCTL_PERSIST_FILE" ]]; then
        local drift="" nkeys=0 key want have
        while IFS='=' read -r key want; do
            [[ "$key" =~ ^[[:space:]]*(#|$) ]] && continue
            key="$(echo "$key" | tr -d '[:space:]')"
            want="$(echo "$want" | tr -s '\t ' ' ' | sed 's/^ //; s/ $//')"
            nkeys=$((nkeys+1))
            have="$(sysctl -n "$key" 2>/dev/null | tr -s '\t ' ' ' || true)"
            [[ "$have" == "$want" ]] || drift+="$key(runtime=${have:-unreadable} persisted=$want) "
        done < "$SYSCTL_PERSIST_FILE"
        echo "persisted:        $SYSCTL_PERSIST_FILE ($nkeys keys)  drift: ${drift:-none}"
    else
        echo "persisted:        NOT PERSISTED — sysctls revert on reboot (run with --persist)"
    fi
    local unit_state
    unit_state="$(systemctl is-enabled "$TUNING_UNIT_NAME" 2>/dev/null || true)"
    echo "boot unit:        $TUNING_UNIT_NAME: ${unit_state:-missing} (THP=never + performance governor at boot)"
    echo "-------------------------------------------------------------"
}

log "BEFORE:"
BEFORE=$(report)
echo "$BEFORE"

if [[ $REPORT_ONLY -eq 1 ]]; then
    log "--report: no changes made"
    exit 0
fi

# ==================== 1. CPU GOVERNOR -> performance ====================
log "1. CPU governor"
CHANGED_GOV=0
for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    [[ -f "$gov" ]] || continue
    if [[ "$(cat "$gov")" != "performance" ]]; then
        if priv_write performance "$gov"; then CHANGED_GOV=$((CHANGED_GOV+1)); else
            warn "cannot set $(dirname "$gov" | grep -oP 'cpu\d+') governor (need sudo)"; break
        fi
    fi
done
[[ $CHANGED_GOV -gt 0 ]] && ok "set performance on $CHANGED_GOV cores" || skip "all cores on performance (or unwritable)"

# ==================== 2. SOCKET BUFFER LIMITS ====================
log "2. net.core socket buffer ceilings"
for kv in "net.core.rmem_max:$RMEM_MAX_TARGET" "net.core.wmem_max:$WMEM_MAX_TARGET"; do
    key="${kv%%:*}"; target="${kv##*:}"
    current=$(sysctl -n "$key" 2>/dev/null || echo 0)
    if [[ "$current" -ge "$target" ]]; then
        skip "$key=$current"
    elif priv_sysctl "$key" "$target"; then
        ok "$key: $current -> $target"
        echo "[tune]   NOTE: restart the media drivers — sockets keep their creation-time size"
    else
        warn "$key=$current < $target and cannot raise (need sudo). Aeron will fall back to ~208KB sockets!"
    fi
done

# ==================== 3. HUGE PAGES (best effort) ====================
log "3. huge pages"
current_hp=$(grep -oP 'HugePages_Total:\s*\K\d+' /proc/meminfo)
if [[ "$current_hp" -ge "$NR_HUGEPAGES" ]]; then
    skip "HugePages_Total=$current_hp"
elif priv_sysctl vm.nr_hugepages "$NR_HUGEPAGES"; then
    actual=$(grep -oP 'HugePages_Total:\s*\K\d+' /proc/meminfo)
    if [[ "$actual" -ge "$NR_HUGEPAGES" ]]; then ok "reserved $actual huge pages"; else
        warn "requested $NR_HUGEPAGES huge pages, kernel granted $actual (fragmented memory; harmless — Aeron runs fine on /dev/shm)"
    fi
else
    warn "cannot set vm.nr_hugepages (need sudo); optional — Aeron runs fine on /dev/shm"
fi

# ==================== 4. IRQ AFFINITY AWAY FROM ISOLATED CORES ====================
log "4. NIC IRQ affinity + irqbalance"
if [[ -z "$ISOLATED_CORES" ]]; then
    warn "ISOLATED_CORES unset — skipping IRQ moves and irqbalance ban (set e.g. ISOLATED_CORES=2-11)"
elif [[ -z "$NIC" ]]; then
    warn "no NIC detected — skipping IRQ moves"
else
    # Complement of ISOLATED_CORES over all online CPUs = where IRQs may run
    HOUSEKEEPING=$(python3 - "$ISOLATED_CORES" <<'EOF'
import sys
def parse(s):
    out=set()
    for part in s.split(','):
        if '-' in part: a,b=part.split('-'); out.update(range(int(a),int(b)+1))
        elif part: out.add(int(part))
    return out
iso=parse(sys.argv[1])
online=parse(open('/sys/devices/system/cpu/online').read().strip())
rest=sorted(online-iso)
print(','.join(map(str,rest)))
EOF
)
    NIC_IRQS=$(grep -w "$NIC" /proc/interrupts | awk -F: '{print $1}' | tr -d ' ' || true)
    if [[ -z "$NIC_IRQS" ]]; then
        warn "no IRQs listed for $NIC in /proc/interrupts (wifi/virtual NIC?) — nothing to move"
    else
        moved=0
        for irq in $NIC_IRQS; do
            cur=$(cat /proc/irq/$irq/smp_affinity_list 2>/dev/null || echo "")
            if [[ "$cur" == "$HOUSEKEEPING" ]]; then continue; fi
            if priv_write "$HOUSEKEEPING" "/proc/irq/$irq/smp_affinity_list"; then moved=$((moved+1)); else
                warn "cannot set IRQ $irq affinity (need sudo)"; break
            fi
        done
        [[ $moved -gt 0 ]] && ok "moved $moved NIC IRQs to cores $HOUSEKEEPING" || skip "NIC IRQs already off isolated cores"
    fi

    # Ban isolated cores in irqbalance so it does not move IRQs back
    if systemctl is-active --quiet irqbalance 2>/dev/null; then
        BANNED_MASK=$(python3 - "$ISOLATED_CORES" <<'EOF'
import sys
mask=0
for part in sys.argv[1].split(','):
    if '-' in part:
        a,b=part.split('-'); [mask:=mask|(1<<c) for c in range(int(a),int(b)+1)]
    elif part: mask|=1<<int(part)
print(f"{mask:x}")
EOF
)
        if grep -q "IRQBALANCE_BANNED_CPUS=$BANNED_MASK" /etc/default/irqbalance 2>/dev/null; then
            skip "irqbalance ban mask $BANNED_MASK"
        elif [[ "$SUDO" != "__nosudo__" ]] && $SUDO sh -c "sed -i '/^IRQBALANCE_BANNED_CPUS=/d' /etc/default/irqbalance 2>/dev/null; echo IRQBALANCE_BANNED_CPUS=$BANNED_MASK >> /etc/default/irqbalance" 2>/dev/null; then
            $SUDO systemctl restart irqbalance 2>/dev/null || true
            ok "irqbalance banned from cores $ISOLATED_CORES (mask $BANNED_MASK)"
        else
            warn "cannot write /etc/default/irqbalance (need sudo) — irqbalance may move IRQs back onto isolated cores"
        fi
    else
        skip "irqbalance not active"
    fi
fi

# ==================== 5. NIC COALESCING OFF ====================
log "5. NIC interrupt coalescing"
if [[ -z "$NIC" ]]; then
    warn "no NIC detected — skipping coalescing"
elif ! command -v ethtool >/dev/null 2>&1; then
    warn "ethtool not installed — skipping coalescing"
else
    cur=$(ethtool -c "$NIC" 2>/dev/null | grep -m1 '^rx-usecs:' | awk '{print $2}' || echo "")
    if [[ "$cur" == "0" ]]; then
        skip "$NIC rx-usecs=0"
    elif [[ -z "$cur" ]]; then
        warn "$NIC does not support coalescing (virtual/loopback NIC — expected on dev boxes)"
    elif [[ "$SUDO" != "__nosudo__" ]] && $SUDO ethtool -C "$NIC" adaptive-rx off adaptive-tx off rx-usecs 0 tx-usecs 0 2>/dev/null; then
        ok "$NIC coalescing off (rx-usecs 0, tx-usecs 0, adaptive off)"
    else
        warn "cannot disable coalescing on $NIC (need sudo, or NIC rejects some params)"
    fi
fi

# ==================== 6. BOOT PARAM GUIDANCE ====================
log "6. kernel boot parameters (guidance only — see header)"
if grep -q "isolcpus=" /proc/cmdline; then
    skip "isolcpus present in cmdline"
else
    echo "[tune]   isolcpus/nohz_full/rcu_nocbs not set. For full isolation add to GRUB:"
    echo "[tune]     isolcpus=${ISOLATED_CORES:-<cores>} nohz_full=${ISOLATED_CORES:-<cores>} rcu_nocbs=${ISOLATED_CORES:-<cores>}"
fi

# ==================== 7. PERSIST ACROSS REBOOTS (opt-in) ====================
if [[ $PERSIST -eq 1 ]]; then
    log "7. persistence (sysctl.d + boot tuning unit)"
    if [[ "$SUDO" == "__nosudo__" ]]; then
        warn "cannot persist (need sudo) — tuning reverts on reboot"
    else
        # --- 7a. /etc/sysctl.d/99-openexchange.conf ---
        TMP_CONF=$(mktemp)
        {
            echo "# Open Exchange low-latency tuning."
            echo "# Written by match/deploy/tuning/system-tuning.sh --persist (make optimize-os)."
            echo "# Edit via the script, not by hand: reruns overwrite this file."
            echo "# Verify runtime-vs-persisted drift anytime: make tune-report"
            while IFS='=' read -r key val; do
                if sysctl -n "$key" >/dev/null 2>&1; then
                    echo "$key = $val"
                else
                    echo "# absent on this kernel: $key = $val"
                fi
            done < <(desired_sysctls)
        } > "$TMP_CONF"
        if cmp -s "$TMP_CONF" "$SYSCTL_PERSIST_FILE" 2>/dev/null; then
            skip "$SYSCTL_PERSIST_FILE up to date"
        elif $SUDO install -m 0644 "$TMP_CONF" "$SYSCTL_PERSIST_FILE" 2>/dev/null; then
            # Apply immediately so runtime == boot state (idempotent over 1-6)
            $SUDO sysctl -qp "$SYSCTL_PERSIST_FILE" >/dev/null 2>&1 || true
            ok "wrote $SYSCTL_PERSIST_FILE ($(grep -c '^[^#]' "$TMP_CONF") keys) and applied it"
        else
            warn "cannot write $SYSCTL_PERSIST_FILE"
        fi
        rm -f "$TMP_CONF"

        # --- 7b. boot oneshot unit: THP=never + performance governor ---
        # (neither is a sysctl, so sysctl.d cannot carry them across reboots)
        TMP_UNIT=$(mktemp)
        cat > "$TMP_UNIT" <<'EOF'
[Unit]
Description=Open Exchange low-latency boot tuning (THP=never, performance governor)
Documentation=https://github.com/openexch/match/blob/main/deploy/tuning/system-tuning.sh

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=-/bin/sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/enabled'
ExecStart=-/bin/sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/defrag'
ExecStart=-/bin/sh -c 'for g in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo performance > "$g" 2>/dev/null || true; done'

[Install]
WantedBy=multi-user.target
EOF
        if cmp -s "$TMP_UNIT" "$TUNING_UNIT_FILE" 2>/dev/null \
           && [[ "$(systemctl is-enabled "$TUNING_UNIT_NAME" 2>/dev/null)" == "enabled" ]]; then
            skip "$TUNING_UNIT_NAME installed and enabled"
        elif $SUDO install -m 0644 "$TMP_UNIT" "$TUNING_UNIT_FILE" 2>/dev/null \
             && $SUDO systemctl daemon-reload 2>/dev/null \
             && $SUDO systemctl enable --now "$TUNING_UNIT_NAME" >/dev/null 2>&1; then
            ok "installed + enabled $TUNING_UNIT_NAME (runs at every boot)"
        else
            warn "cannot install/enable $TUNING_UNIT_NAME — THP/governor revert on reboot"
        fi
        rm -f "$TMP_UNIT"
    fi
fi

# ==================== AFTER REPORT + DIFF ====================
log "AFTER:"
AFTER=$(report)
echo "$AFTER"
log "changes:"
diff <(echo "$BEFORE") <(echo "$AFTER") | grep -E '^[<>]' | sed 's/^</[tune]   was:/; s/^>/[tune]   now:/' || echo "[tune]   none (already tuned)"

if [[ $STRICT -eq 1 && $FAILS -gt 0 ]]; then
    log "$FAILS warning(s) with --strict"
    exit 1
fi
log "done ($FAILS warning(s))"
exit 0
