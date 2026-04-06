# Match Engine - Claude Guidelines

## Project Overview
Ultra-low latency matching engine running on a **3-node Aeron Cluster** (Raft consensus). Designed for **24/7 operation** with zero downtime.

## Architecture
```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Order Gateway  │     │ Market Gateway  │     │  Admin Gateway  │
│   HTTP :8080    │     │   WS :8081      │     │   HTTP :8082    │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │ ingress               │ egress                │ process mgmt
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Aeron Cluster (Raft)                         │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐                 │
│  │  Node 0 │◄────►│  Node 1 │◄────►│  Node 2 │                 │
│  │ :9000+  │      │ :9100+  │      │ :9200+  │                 │
│  └─────────┘      └─────────┘      └─────────┘                 │
└─────────────────────────────────────────────────────────────────┘
```

## Priorities (Strict Order)
1. **Uptime** - System must run 24/7 without downtime
2. **Ultra-low latency** - Zero allocations in hot path, O(1) matching
3. **Reliability** - Raft consensus, state replication, snapshots

## Critical Rules

### NEVER Do
- Never flush/delete cluster data without explicit permission
- Never do random cleanups or "improvements"
- Never skip bugs found during sessions - track and fix them
- Never make direct systemctl calls for runtime management - use Admin API
- Never modify cluster state files manually

### ALWAYS Do
- Always look for **root causes** instead of quick fixes
- Always use Admin Gateway API for service management
- Always consider impact on all 3 nodes before changes
- Always verify cluster consensus after infrastructure changes

## Service Management (Admin Gateway API)

**Single source of truth for runtime operations: `http://localhost:8082/api/admin/*`**

### Check Cluster Status
```bash
curl http://localhost:8082/api/admin/status
```

### Restart a Node (Zero Downtime)
```bash
curl -X POST http://localhost:8082/api/admin/restart-node \
  -H "Content-Type: application/json" \
  -d '{"nodeId": 0}'
```

### Rolling Update (All Nodes, No Downtime)
```bash
curl -X POST http://localhost:8082/api/admin/rolling-update
```

### Trigger Snapshot
```bash
curl -X POST http://localhost:8082/api/admin/snapshot
```

### Process Management
```bash
curl http://localhost:8082/api/admin/processes                        # List all processes
curl -X POST http://localhost:8082/api/admin/processes/start-all      # Start all (dependency order)
curl -X POST http://localhost:8082/api/admin/processes/stop-all       # Stop all (reverse order)
curl -X POST http://localhost:8082/api/admin/processes/node0/start    # Start specific process
curl -X POST http://localhost:8082/api/admin/processes/node0/stop     # Stop specific process
```

### Check Operation Progress
```bash
curl http://localhost:8082/api/admin/progress
```

### View Node Logs
```bash
curl "http://localhost:8082/api/admin/logs?node=0&lines=100"
```

### Rebuild & Self-Update
```bash
curl -X POST http://localhost:8082/api/admin/rebuild-admin            # Self-update admin gateway
curl -X POST http://localhost:8082/api/admin/rebuild-cluster          # Rebuild cluster module
curl -X POST http://localhost:8082/api/admin/rebuild-gateway          # Rebuild gateway module
```

## Key Files

| Component | Path |
|-----------|------|
| Cluster Setup | `match-cluster/src/main/java/com/match/infrastructure/persistence/AeronCluster.java` |
| Cluster Service | `match-cluster/src/main/java/com/match/infrastructure/persistence/AppClusteredService.java` |
| Matching Engine | `match-cluster/src/main/java/com/match/application/engine/Engine.java` |
| Order Book | `match-cluster/src/main/java/com/match/application/orderbook/DirectMatchingEngine.java` |
| Gateway Base | `match-gateway/src/main/java/com/match/infrastructure/gateway/AeronGateway.java` |
| Constants | `match-common/src/main/java/com/match/infrastructure/InfrastructureConstants.java` |
| SBE Schema | `match-common/src/main/resources/sbe/order-schema.xml` |
| Admin Gateway | `admin-gateway/` (Go service) |

## Build Commands

```bash
make build-java    # Build Java modules (mvn clean package -DskipTests)
make build-admin   # Build Go admin gateway
make build-ui      # Build React UI
make build         # Build everything
make install       # Full installation with services
```

## Service Management Architecture

Only `admin.service` runs as a systemd unit (`~/.config/systemd/user/admin.service`).
All other processes (`node0`, `node1`, `node2`, `backup`, `order`, `market`) are managed by the Go admin gateway's built-in process manager via HTTP API.

Logs at: `~/.local/log/cluster/`

## Configuration

| Parameter | Value | Location |
|-----------|-------|----------|
| Order Gateway Port | 8080 | InfrastructureConstants |
| Market Gateway Port | 8081 | InfrastructureConstants |
| Admin Gateway Port | 8082 | InfrastructureConstants |
| Cluster Base Port | 9000 | InfrastructureConstants |
| Term Buffer | 16MB | InfrastructureConstants |
| Socket Buffer | 4MB | InfrastructureConstants |
| Session Timeout | 10s | InfrastructureConstants |
| Gateway Heartbeat | 100ms | InfrastructureConstants |

## Order Flow

```
1. HTTP POST /order → OrderGateway (8080)
2. → AeronGateway.submitOrder() → Cluster ingress
3. → AppClusteredService.onSessionMessage()
4. → Engine.acceptOrder() → DirectMatchingEngine.processLimitOrder()
5. → Publish TradeExecution + OrderStatus via egress
6. → MarketGateway → WebSocket → UI
```

## Testing Changes

After any infrastructure change:
1. Check cluster status: `curl http://localhost:8082/api/admin/status`
2. Verify all 3 nodes healthy and one is LEADER
3. Submit test order and verify market data flow
4. Check logs for errors: `curl "http://localhost:8082/api/admin/logs?node=0&lines=50"`

## Load Testing

```bash
./run-load-test.sh quick        # Smoke test: 1k/s for 10s
./run-load-test.sh baseline     # Baseline: 1k/s for 60s
./run-load-test.sh stress       # Stress: 10k/s for 60s
./run-load-test.sh endurance    # Endurance: 2k/s for 1 hour
./run-load-test.sh progressive  # Ramp: 1k → 10k/s
```

## Debugging

### Cluster Won't Start (Mark File Error)
```bash
curl -X POST http://localhost:8082/api/admin/cleanup
```

### Check Leader
```bash
curl http://localhost:8082/api/admin/status | jq '.leader'
```

### Force Node Rejoin
```bash
curl -X POST http://localhost:8082/api/admin/restart-node -d '{"nodeId": 0}'
```
