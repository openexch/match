# OOM protection units

`exchange-oom-protect.sh` lowers the Linux `oom_score_adj` of the exchange processes so the kernel OOM killer picks other processes first. It looks up process ids through the v0.5 admin gateway API (`ADMIN`, default `http://127.0.0.1:8082`) and is meant for single-box deployments supervised by it.

| Process | oom_score_adj |
|---|---|
| `node0-2`, `ae0-2` | -900 |
| `admin-gateway` | -800 |
| `oms`, `market`, `bridge`, `backup` | -700 |
| `sim` | +500 |

A negative value needs `CAP_SYS_RESOURCE`, so the script runs as root from a timer that re-applies it every 20 s (restarts reset the score).

## Install (root)

```bash
sudo install -m0755 exchange-oom-protect.sh /usr/local/bin/exchange-oom-protect.sh
sudo install -m0644 exchange-oom-protect.service exchange-oom-protect.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now exchange-oom-protect.timer
```

`term-length-footprint.conf` is an optional drop-in that sets `TRANSPORT_TERM_LENGTH=8m` to reduce `/dev/shm` use; see the comments in the file.
