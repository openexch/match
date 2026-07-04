Repo: openexch/admin-gateway
Title: Ops blind spots: status API serves stale CnC counters for dead/wedged nodes; adopted processes lose crash monitoring

Two observability gaps that compounded the 2026-07-02 stranded-follower incident (match repo, docs/incidents/2026-07-02-follower-stranded-by-housekeeping.md):

1. /api/admin/status reads CnC counter files that survive process death, so a dead or wedged node can report a healthy role and commit position ("consensus restored" was observed on a cluster whose nodes were all dead). Status should verify process liveness + counter freshness (e.g. heartbeat timestamp) before reporting, and flag "frozen commit position" as unhealthy.
2. After an admin-gateway restart, adoptExisting() re-attaches processes by PID but cannot re-arm monitor() (no cmd handle), so crashes of adopted processes are never detected: no auto-restart, no RestartCascades (driver->node coupling silently disabled). Needs a poll-based watchdog for adopted processes.
