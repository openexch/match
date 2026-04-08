# Multi-Module Maven Project Structure

This document describes the refactored Maven multi-module structure for the matching engine.

## Module Overview

```
match/
├── pom.xml                    # Parent POM (aggregator)
├── match-common/              # Shared code
├── match-cluster/             # Cluster nodes
├── match-gateway/             # Market Gateway (WebSocket)
├── match-loadtest/            # Load testing tools
└── admin-gateway/             # Admin Gateway (Go, not Maven)
```

### match-common
Shared code used by all other modules:
- Domain models (`com.match.domain`)
- SBE generated codecs (`com.match.infrastructure.generated`)
- Infrastructure constants (`InfrastructureConstants.java`)
- Logger utility
- SubscriptionManager (for event subscription tracking)

**Dependencies:** agrona, sbe-tool, gson, netty-all, disruptor

### match-cluster
Cluster nodes (Aeron Cluster service):
- Main entry point (`com.match.Main`)
- Matching engine (`com.match.application.engine`)
- Order books (`com.match.application.orderbook`)
- Event publishing (`com.match.application.publisher`)
- Cluster service (`com.match.infrastructure.persistence`)

**Produces:** `match-cluster/target/match-cluster.jar` (uber JAR)
**Dependencies:** match-common, aeron-all, disruptor

### match-gateway
Market data gateway:
- Market Gateway (`MarketGatewayMain`) - WebSocket on port 8081
- Aeron cluster client (`AeronGateway`)
- WebSocket handlers and state management

Order submission is handled by OMS (separate repo: `order-management`).

**Produces:** `match-gateway/target/match-gateway.jar` (uber JAR)
**Dependencies:** match-common, aeron-all, netty-all

### admin-gateway (Go)
Admin and process management service (not a Maven module):
- Process manager for all cluster nodes and gateways
- HTTP API on port 8082
- Cluster operations (rolling update, snapshot, cleanup)
- Log aggregation and monitoring

**Produces:** `admin-gateway/admin-gateway` (Go binary)
**Build:** `make build-admin` or `cd admin-gateway && go build -o admin-gateway .`

### match-loadtest
Load testing tools:
- `LoadGenerator` - Direct Aeron cluster load testing
- Various test scenarios

**Produces:** `match-loadtest/target/match-loadtest.jar` (uber JAR)
**Dependencies:** match-common, aeron-all

## Building

```bash
# Build all modules
make build-java
# or
mvn clean package -DskipTests

# Build individual modules
make build-cluster    # Cluster nodes only
make build-gateway    # Gateways only
make build-loadtest   # Load test only
```

## Running Services

After building, the processes use these artifacts:
- Cluster nodes (node0, node1, node2): `match-cluster.jar`
- Backup node: `match-cluster.jar`
- Market gateway: `match-gateway.jar`
- OMS: `oms-app.jar` (from `order-management` repo)
- Admin gateway: `admin-gateway/admin-gateway` (Go binary)

Only the admin gateway runs as a systemd service. All other processes are managed via the admin gateway's HTTP API:

```bash
# Start everything via admin gateway
curl -X POST http://localhost:8082/api/admin/processes/start-all

# Or start individual processes
curl -X POST http://localhost:8082/api/admin/processes/node0/start
```

## Safe Deployment

The multi-module structure enables safer rolling updates:

1. **Gateway-only changes**: Build and deploy only `match-gateway.jar`
2. **Cluster changes**: Use rolling update via admin API
3. **Shared code changes**: Rebuild all modules

## SBE Code Generation

SBE codecs are generated in match-common:
```bash
make sbe
```

The schema file is at `match-common/src/main/resources/sbe/order-schema.xml`.
