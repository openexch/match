# Multi-Module Maven Project Structure

This document describes the refactored Maven multi-module structure for the matching engine.

## Module Overview

```
match/
├── pom.xml                    # Parent POM (aggregator)
├── match-common/              # Shared code
├── match-cluster/             # Cluster nodes
├── match-gateway/             # Gateways (Order, Market, Admin)
└── match-loadtest/            # Load testing tools
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
Gateway processes:
- Order Gateway (`OrderGatewayMain`) - HTTP API on port 8080
- Market Gateway (`MarketGatewayMain`) - WebSocket on port 8081
- Admin Gateway (`AdminGatewayMain`) - HTTP API on port 8082
- Aeron cluster client (`AeronGateway`)
- HTTP/WebSocket handlers

**Produces:** `match-gateway/target/match-gateway.jar` (uber JAR)
**Dependencies:** match-common, aeron-all, netty-all

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

After building, the systemd services use these JARs:
- Cluster nodes (node0, node1, node2): `match-cluster.jar`
- Backup node: `match-cluster.jar`
- Order/Market/Admin gateways: `match-gateway.jar`

```bash
# Reinstall services after code changes
make reinstall-services

# Start the cluster
systemctl --user start node0 node1 node2 backup
systemctl --user start order market admin
```

## Safe Deployment

The multi-module structure enables safer rolling updates:

1. **Gateway-only changes**: Build and deploy only `match-gateway.jar`
2. **Cluster changes**: Use rolling update via admin API
3. **Shared code changes**: Rebuild all modules

## Migration from Monolithic Structure

The old structure (`match/target/cluster-engine-1.0.jar`) is preserved for reference.
To fully migrate:

1. Verify new JARs work: `make reinstall-services && systemctl --user restart node0 node1 node2`
2. Test order submission and market data
3. Delete old structure when confident: `rm -rf match/target/`

## SBE Code Generation

SBE codecs are generated in match-common:
```bash
make sbe
```

The schema file is at `match-common/src/main/resources/sbe/order-schema.xml`.
