# Observability starter kit (match#33 / oms#38)

- `prometheus.yml` — scrape config for a single-box deployment: cluster nodes
  (`/metrics` on `9500+nodeId`, JDK HTTP server reading hot-path-safe plain-long
  counters; override with `METRICS_PORT`), market gateway (`:8081/metrics`),
  OMS (`:8080/metrics`, Micrometer), admin gateway (`:8082/metrics` — the
  `admin_*` series the admin alert rules depend on), market simulator
  (`:8090/metrics`, `sim_*` canary series).
- `alerts.yml` — starter alert rules: no leader, snapshot age > 2x interval,
  reliable-egress drops, OMS egress gaps, risk-reject spike, OMS disconnected,
  backup not fresh, node unhealthy, managed process failed, admin gateway
  down, and `DemoUnhealthy` (`admin_demo_healthy == 0` — the public demo's
  end-to-end outage signal).
- `openexchange-dashboard.json` — import into Grafana (it will prompt for the
  Prometheus datasource): engine throughput + sampled order latency
  percentiles, cluster role/snapshot age, egress queues/drops, OMS request
  latency/outcomes/integrity, gateway WS, JVM heap.

Engine metrics are recorded on the cluster agent thread as plain longs and a
preallocated log2 histogram (1-in-16 sampling), published to the scraper
thread via a volatile piggyback on the flush timer — no Micrometer, no
allocation, no locks on the matching hot path (see NodeMetrics).
