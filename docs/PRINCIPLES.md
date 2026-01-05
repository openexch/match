# Architecture Principles

This document defines core architectural decisions that must be respected across all sessions and modifications.

## Single Source of Truth: Admin Gateway API

**Rule**: All runtime cluster management operations MUST go through the Admin Gateway API (`http://localhost:8082/api/admin/*`).

**Why**:
- Consistent behavior across UI, CLI, and automation
- Proper state tracking and progress monitoring
- Thread-safe operations with proper locking
- Unified logging and error handling

### Admin API Endpoints (Single Source of Truth)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/admin/status` | GET | Cluster status (nodes, gateways, backup) |
| `/api/admin/rolling-update` | POST | Zero-downtime cluster update |
| `/api/admin/snapshot` | POST | Trigger cluster snapshot |
| `/api/admin/compact` | POST | Compact archive logs |
| `/api/admin/logs` | GET | Stream cluster logs |
| `/api/admin/progress` | GET | Get operation progress |

#### Node Operations
| Endpoint | Method | Body |
|----------|--------|------|
| `/api/admin/start-node` | POST | `{"nodeId": 0}` |
| `/api/admin/stop-node` | POST | `{"nodeId": 0}` |
| `/api/admin/restart-node` | POST | `{"nodeId": 0}` |

#### Gateway Operations
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/admin/start-gateway` | POST | Start all gateways |
| `/api/admin/stop-gateway` | POST | Stop all gateways |
| `/api/admin/restart-gateway` | POST | Restart all gateways |
| `/api/admin/start-market-gateway` | POST | Start market gateway |
| `/api/admin/stop-market-gateway` | POST | Stop market gateway |
| `/api/admin/restart-market-gateway` | POST | Restart market gateway |
| `/api/admin/start-order-gateway` | POST | Start order gateway |
| `/api/admin/stop-order-gateway` | POST | Stop order gateway |
| `/api/admin/restart-order-gateway` | POST | Restart order gateway |

#### Backup Operations
| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/admin/start-backup` | POST | Start backup node |
| `/api/admin/stop-backup` | POST | Stop backup node |
| `/api/admin/restart-backup` | POST | Restart backup node |

### What Makefile Should Contain

**ALLOWED** (installation/setup only):
- `make install-deps` - Install system dependencies
- `make install` - Build and initial setup
- `make install-services` - Install systemd services
- `make uninstall-services` - Remove systemd services
- `make optimize-os` - OS tuning
- `make build` - Compile code

**NOT ALLOWED** (use Admin API instead):
- ~~`make start`~~ → Use Admin API or UI
- ~~`make stop`~~ → Use Admin API or UI
- ~~`make restart-node`~~ → `POST /api/admin/restart-node`
- ~~`make restart-gateways`~~ → `POST /api/admin/restart-gateway`
- ~~`make rolling-update`~~ → `POST /api/admin/rolling-update`
- ~~`make snapshot`~~ → `POST /api/admin/snapshot`

### CLI Examples

```bash
# Check status
curl http://localhost:8082/api/admin/status

# Rolling update
curl -X POST http://localhost:8082/api/admin/rolling-update

# Restart node 1
curl -X POST http://localhost:8082/api/admin/restart-node -d '{"nodeId":1}'

# Restart all gateways
curl -X POST http://localhost:8082/api/admin/restart-gateway

# Trigger snapshot
curl -X POST http://localhost:8082/api/admin/snapshot
```

---

## Gateway Heartbeat Architecture

**Rule**: Only the Market Gateway sends heartbeats to the cluster. Order Gateway does NOT send heartbeats.

**Why**:
- Market Gateway is the only gateway that receives market data broadcasts
- Cluster uses heartbeats to determine if there's an active subscriber for market data
- Order Gateway only sends orders (ingress) and doesn't need egress data

**Implementation**:
- `AeronGateway(true)` - Market Gateway (sends heartbeats)
- `AeronGateway(false)` - Order Gateway (no heartbeats)

---

## Port Allocation

| Service | Port | Protocol |
|---------|------|----------|
| Order Gateway | 8080 | HTTP |
| Market Gateway | 8081 | WebSocket |
| Admin Gateway | 8082 | HTTP |
| Cluster Node 0 Ingress | 9002 | UDP |
| Cluster Node 1 Ingress | 9102 | UDP |
| Cluster Node 2 Ingress | 9202 | UDP |

---

## Service Dependencies

```
Admin Gateway (standalone, no cluster connection)
    └── Manages all other services via systemctl

Market Gateway
    └── Connects to Cluster (egress for market data)
    └── Broadcasts to WebSocket clients

Order Gateway
    └── Connects to Cluster (ingress for orders)
    └── Does NOT receive market data

Cluster Nodes (0, 1, 2)
    └── Raft consensus
    └── Matching engine
    └── State replication

Backup Node
    └── Passive cluster member
    └── Snapshot/recovery only
```
