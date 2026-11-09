# Observability starter kit

Starter Prometheus and Grafana configuration for a single-box deployment of the v0.5 demo stack.

- `prometheus.yml`: scrapes the cluster nodes (`/metrics` on `9500 + nodeId`, override with `METRICS_PORT`), the market gateway (`:8081`), the order service (`:8080`), the admin gateway (`:8082`) and the market simulator (`:8090`).
- `alerts.yml`: starter alert rules (leader, snapshot age, egress drops, backup freshness, process health, demo health).
- `openexchange-dashboard.json`: import into Grafana and pick the Prometheus datasource.

Engine metrics are plain long counters and a preallocated histogram written on the cluster thread and read by the scraper thread, so metrics add no allocation or locking to the matching path (see `NodeMetrics`).
