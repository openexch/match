# Exchange OOM protection (ag#68)

The stack runs under the user graphical session in systemd `app.slice`, which sets
`OOMScoreAdjust=200` on every service. That makes the money-cluster JVMs the kernel's
**preferred** OOM victims (oom_score ~810). On 2026-07-12 a memory-heavy browser on the
same box drove the machine into a global OOM; the kernel killed 4 of 6 cluster nodes at
once and both clusters lost quorum (their `/dev/shm` driver dirs went with them — the
symptom previously mis-filed as a "phantom deleter").

This unit re-scores the critical processes negative so the kernel targets desktop apps
first, and makes the sim the deliberate first sacrifice:

| Process | oom_score_adj | rationale |
|---|---|---|
| node0-2, ae0-2 | **-900** | money-cluster quorum — protect hardest |
| oms, market, bridge, backup | -700 | serving/settlement path |
| admin-gateway | -800 | the process manager |
| sim | +500 | demo load — acceptable first casualty |

A negative `oom_score_adj` requires `CAP_SYS_RESOURCE`, so this runs as **root** via a
timer that re-applies every 20s (catching node rolls/restarts, which reset the score).

## Install (root)
```bash
sudo install -m0755 exchange-oom-protect.sh /usr/local/bin/exchange-oom-protect.sh
sudo install -m0644 exchange-oom-protect.service /etc/systemd/system/
sudo install -m0644 exchange-oom-protect.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now exchange-oom-protect.timer
```

## Apply immediately to the running cluster (no wait)
```bash
sudo /usr/local/bin/exchange-oom-protect.sh
# verify (should read -900 for the nodes):
for s in node0 node1 node2 ae0 ae1 ae2; do
  p=$(curl -s localhost:8082/api/admin/processes/$s | grep -oP '"pid":\s*\K[0-9]+')
  echo "$s: $(cat /proc/$p/oom_score_adj)"
done
```

## Real fix vs. this mitigation
This is the reliable mitigation. The cleaner long-term fix is to move the exchange out
of the killable user `app.slice` entirely (a dedicated/system slice), and to reduce the
`/dev/shm` term-buffer footprint so the box isn't near the memory edge in the first place.
Also: don't co-locate a heavy browser with the exchange, or cap it with a `MemoryMax` on
its slice / point `systemd-oomd` at it.
