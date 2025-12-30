# Aeron Cluster Load Test Results

## Overview

This document summarizes the load testing performed on the 3-node Aeron cluster matching engine to determine maximum sustainable throughput under various configurations.

## Test Environment

- **Cluster**: 3-node Aeron Cluster (Raft consensus)
- **Test Duration**: 30 seconds per test
- **Warmup**: 5 seconds at 1,000 orders/sec
- **Order Pattern**: BUY_HEAVY (70% buy, 30% sell limit orders)
- **Price Range**: 9,900 - 10,100 (around midpoint 10,000)

### Hardware
- macOS Darwin 24.6.0
- Docker containers with 1GB shared memory each
- 1GB heap per node

## Results Summary

### Docker Desktop vs Colima

| Rate (orders/sec) | Docker Desktop | Colima (tuned) |
|-------------------|----------------|----------------|
| 17,000 | 100% success | - |
| 18,000 | 66% success | **100% success** |
| 18,500 | - | **100% success** |
| 19,000 | 11% success | 74% success |
| 20,000 | 32% success | 25% success |

### Key Finding: Socket Buffer Limitation

Docker Desktop limits socket buffers to **212KB** regardless of requested size, while Colima allows full **4MB** buffers after kernel tuning.

```
WARNING: Could not set desired SO_SNDBUF, adjust OS to allow
aeron.socket.so_sndbuf attempted=4194304, actual=212992
```

## Detailed Results

### Best Result: 18,500 orders/sec (Colima)

| Metric | Value |
|--------|-------|
| Success Rate | 100% |
| Avg Throughput | 17,434 orders/sec |
| Total Orders Sent | 555,000 |
| Backpressure Events | 65 |
| p50 Latency | 16.7 μs |
| p99 Latency | 29.3 ms |
| p999 Latency | 94.7 ms |

### Throughput Breakdown at 18,500/sec

- Orders Sent: 555,000
- Orders Acknowledged: 555,000
- Orders Failed: 0
- Backpressure Events: 65

## Configuration

### Colima Setup (Recommended)

Start Colima with tuned parameters:
```bash
colima start --cpu 4 --memory 8 --disk 60
```

Set kernel parameters inside Colima VM:
```bash
colima ssh
sudo sysctl -w net.core.rmem_max=8388608
sudo sysctl -w net.core.wmem_max=8388608
sudo sysctl -w net.core.rmem_default=4194304
sudo sysctl -w net.core.wmem_default=4194304
```

### Aeron Buffer Settings

In `AeronCluster.java` and `ClusterConfig.java`:
```java
// Socket buffers - 4MB for high throughput (requires tuned OS)
.socketSndbufLength(4 * 1024 * 1024)
.socketRcvbufLength(4 * 1024 * 1024)
// Initial window - 4MB for high throughput
.initialWindowLength(4 * 1024 * 1024)
```

### JVM Flags (entrypoint.sh)

Key low-latency JVM settings:
- ZGC with generational mode
- 2GB heap (pre-touched)
- Disabled safepoint intervals
- Aeron IPC MTU: 8KB
- Bounds checks disabled

## Running Load Tests

### Start Cluster
```bash
cd docker
docker compose up -d node0 node1 node2 load-generator
```

### Run Load Test
```bash
docker compose exec load-generator java \
  -cp /home/aeron/jar/cluster.jar \
  com.match.loadtest.LoadGenerator \
  172.16.202.2,172.16.202.3,172.16.202.4 \
  9000 \
  18500 \
  30 \
  BUY_HEAVY
```

Parameters:
1. Cluster addresses
2. Base port
3. Orders per second
4. Duration (seconds)
5. Order pattern (BUY_HEAVY, SELL_HEAVY, BALANCED, MARKET_MAKER)

## Conclusions

1. **Sustainable Throughput**: ~18,500 orders/sec with Colima, ~17,000 with Docker Desktop
2. **Bottleneck**: Network socket buffers are the primary limiting factor
3. **Recommendation**: Use Colima (or Linux) with tuned kernel parameters for production-like testing
4. **Latency**: Sub-millisecond p50 latency achieved at sustainable throughput levels

## Future Optimizations

- Test with larger socket buffers (8MB+) on native Linux
- Evaluate CPU pinning and NUMA-aware allocation
- Test with kernel bypass (DPDK/io_uring)
- Measure impact of SBE message size optimization
