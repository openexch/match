#!/usr/bin/env bash
# Shield the Open Exchange cluster from the Linux OOM killer.
#
# Why this exists (incident 2026-07-12, "ag#68"): the stack runs under the user
# graphical session in systemd's app.slice, which stamps OOMScoreAdjust=200 on
# every service — so the kernel scores the money-cluster JVMs ~810 and picks THEM
# first under memory pressure. A heavy browser on the same box (Chrome + Meet +
# many tabs) exhausted RAM, the OOM killer took out 4 of 6 cluster nodes at once,
# and both clusters lost quorum.
#
# This re-scores the critical processes NEGATIVE (kernel targets desktop apps
# first) and makes the sim the deliberate first sacrifice. Setting a negative
# oom_score_adj needs CAP_SYS_RESOURCE, so this MUST run as root (via the
# accompanying systemd timer). It re-applies periodically to catch node rolls.
set -u
ADMIN=${ADMIN:-http://127.0.0.1:8082}/api/admin
pidof_svc() { curl -s "$ADMIN/processes/$1" 2>/dev/null | grep -oP '"pid":\s*\K[0-9]+'; }
set_adj() { local pid=$1 adj=$2; [ -n "$pid" ] && [ -w "/proc/$pid/oom_score_adj" ] && echo "$adj" > "/proc/$pid/oom_score_adj" 2>/dev/null; }

# Money-critical: the 6 cluster nodes get the strongest protection.
for s in node0 node1 node2 ae0 ae1 ae2; do set_adj "$(pidof_svc "$s")" -900; done
# Gateways, bridge, backup: protected, a notch below the nodes.
for s in oms market bridge backup; do set_adj "$(pidof_svc "$s")" -700; done
# The admin gateway is the process manager itself — protect it.
for pid in $(pgrep -x admin-gateway 2>/dev/null); do set_adj "$pid" -800; done
# The market sim is demo load, not money state: make it the FIRST exchange
# victim so under pressure the demo dies before any cluster node.
set_adj "$(pidof_svc sim)" 500
exit 0
