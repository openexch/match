# Match Engine

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Aeron](https://img.shields.io/badge/Aeron%20Cluster-1.48.1-green.svg)](https://github.com/aeron-io/aeron)

Ultra-low latency order matching engine built on a 3-node [Aeron Cluster](https://github.com/aeron-io/aeron) (Raft consensus). Designed for 24/7 operation with zero downtime.

## Key Features

- **Sub-microsecond matching** — Zero-allocation hot path with O(1) order book operations via direct array indexing
- **Fault-tolerant clustering** — 3-node Raft consensus with automatic leader election, snapshots, and log replay
- **Real-time market data** — WebSocket streaming of order book updates, trades, and market statistics
- **5 market pairs** — BTC, ETH, SOL, XRP, DOGE against USD with optimized price-level indexing
- **Fixed-point arithmetic** — 8-decimal precision (10^8 scaling) throughout, no floating-point in the hot path
- **Rolling updates** — Zero-downtime deployments via Admin API
- **Chaos engineering** — Built-in failure injection suite for resilience testing
- **Load testing** — Configurable scenarios from 1k to 10k+ orders/sec

## Architecture

```
                    ┌──────────────┐  ┌────────────────────┐  ┌──────────────────┐
                    │   Order     │  │  Market Gateway   │  │  Admin Gateway   │
                    │  Gateway    │  │   WebSocket       │  │   (Go)           │
                    │  HTTP :8080 │  │   WS :8081        │  │   HTTP :8082     │
                    └──────┬──────┘  └───▲──────────────┘  └──────┬───────────┘
                           │ ingress     │ egress                  │ process mgmt
                           ▼             │                         ▼
                    ┌─────────────────────────────────────────────────────────┐
                    │                 Aeron Cluster (Raft)                    │
                    │                                                         │
                    │   ┌───────────┐  ┌───────────┐  ┌───────────┐          │
                    │   │  Node 0   │◄►│  Node 1   │◄►│  Node 2   │          │
                    │   │  :9000+   │  │  :9100+   │  │  :9200+   │          │
                    │   └───────────┘  └───────────┘  └───────────┘          │
                    │                                                         │
                    │   Engine → DirectIndexOrderBook → SBE Egress           │
                    └─────────────────────────────────────────────────────────┘
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Matching Engine | Java 21, Aeron Cluster 1.48.1, Agrona |
| Serialization | Simple Binary Encoding (SBE) 1.33.1 |
| Event Processing | LMAX Disruptor 4.0.0 |
| Network I/O | Netty 4.1.100 |
| Admin Gateway | Go |
| Build | Maven, Make |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3+
- Go 1.22+
- Linux (Ubuntu/Debian recommended)

### Install & Run

```bash
# Install system dependencies
make install-deps

# Build everything and start all services
make install

# Verify cluster is running
curl http://localhost:8082/api/admin/status
```

### Build Only

```bash
make build-java      # Build Java modules
make build-admin     # Build Go admin gateway
make build           # Build everything
```

## Project Structure

```
match/
├── match-common/       # Shared domain models, SBE schema, constants
├── match-cluster/      # Aeron Cluster service + matching engine
├── match-gateway/      # Order (HTTP) and Market (WebSocket) gateways
├── match-loadtest/     # Load testing and benchmarking tools
├── admin-gateway/      # Go-based admin/process management service
├── scripts/            # Chaos engineering and test scripts
├── docs/               # Technical documentation
└── Makefile            # Build, deploy, and operations automation
```

## Order Flow

```
1. HTTP POST /order → Order Gateway (port 8080)
2. → Aeron ingress → Cluster consensus (Raft)
3. → Engine.acceptOrder() → DirectMatchingEngine
4. → O(1) price lookup → Price-time priority matching
5. → SBE-encoded TradeExecution + OrderStatus via egress
6. → Market Gateway → WebSocket → clients
```

## API

### Submit Order

```bash
curl -X POST http://localhost:8080/order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user1",
    "market": "BTC-USD",
    "orderSide": "BUY",
    "orderType": "LIMIT",
    "price": 100000.00,
    "quantity": 0.5
  }'
```

### Market Data (WebSocket)

```javascript
const ws = new WebSocket('ws://localhost:8081/ws');

// Subscribe to a market (required to receive data)
ws.onopen = () => {
  ws.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
};

ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  // BOOK_SNAPSHOT, BOOK_DELTA, TRADE_EXECUTION, etc.
};

// Other client commands:
// { "action": "refresh", "marketId": 1 }
// { "action": "unsubscribe", "marketId": 1 }
// { "action": "getOrderBook", "marketId": 1 }
// { "action": "getTrades", "limit": 50 }
// { "action": "ping" }
```

### Admin API

The Admin Gateway (Go) acts as the process manager for the entire system — cluster nodes, gateways, and operational tasks are all managed through its API.

```bash
# Cluster status
curl http://localhost:8082/api/admin/status

# Process management
curl http://localhost:8082/api/admin/processes                        # List all processes
curl -X POST http://localhost:8082/api/admin/processes/start-all      # Start all (dependency order)
curl -X POST http://localhost:8082/api/admin/processes/stop-all       # Stop all (reverse order)
curl -X POST http://localhost:8082/api/admin/processes/node0/start    # Start specific process
curl -X POST http://localhost:8082/api/admin/processes/node0/stop     # Stop specific process

# Operations
curl -X POST http://localhost:8082/api/admin/rolling-update           # Zero-downtime deployment
curl -X POST http://localhost:8082/api/admin/snapshot                 # Take cluster snapshot
curl -X POST http://localhost:8082/api/admin/rebuild-admin            # Self-update admin gateway

# Logs & monitoring
curl "http://localhost:8082/api/admin/logs?node=0&lines=100"
curl http://localhost:8082/api/admin/progress                         # Operation progress
```

Managed processes: `node0`, `node1`, `node2`, `backup`, `order`, `market`

## Load Testing

```bash
./run-load-test.sh quick        # Smoke test: 1k/s for 10s
./run-load-test.sh baseline     # Baseline: 1k/s for 60s
./run-load-test.sh stress       # Stress: 10k/s for 60s
./run-load-test.sh endurance    # Endurance: 2k/s for 1 hour
./run-load-test.sh progressive  # Ramp: 1k → 10k/s
```

## Configuration

Key parameters in `InfrastructureConstants.java`:

| Parameter | Value |
|-----------|-------|
| Order Gateway Port | 8080 |
| Market Gateway Port | 8081 |
| Admin Gateway Port | 8082 |
| Cluster Base Port | 9000 |
| Term Buffer | 16 MB |
| Socket Buffer | 4 MB |
| Session Timeout | 10s |
| Gateway Heartbeat | 100ms |

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes
4. Push to the branch and open a Pull Request

For bug reports and feature requests, please use [GitHub Issues](https://github.com/openexch/match/issues).

## Support

This project is **free to use** under the Apache 2.0 license.

For **advanced help**, custom deployments, performance tuning, or consulting — reach out:

- **Email**: emrebulutlar@gmail.com
- **GitHub Issues**: [openexch/match/issues](https://github.com/openexch/match/issues)

## License

This project is licensed under the [Apache License 2.0](LICENSE).

```
Copyright 2026 Ziya Emre Bulutlar
```
