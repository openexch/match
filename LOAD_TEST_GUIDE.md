# Load Test Guide - Direct Aeron Cluster Load Generator

## Overview

This load testing framework directly connects to the Aeron Cluster, bypassing the HTTP layer to test the pure matching engine performance under high load. This approach provides:

- **Maximum throughput testing**: No HTTP overhead
- **Realistic market scenarios**: Multiple predefined trading patterns
- **Detailed metrics**: Latency percentiles, throughput, backpressure events
- **Concurrent load generation**: Multi-threaded worker architecture

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     LoadGenerator                           │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  MediaDriver (Embedded)                              │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  AeronCluster Client                                 │   │
│  │  - Direct SBE message encoding                       │   │
│  │  - Bypasses HTTP layer                               │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  OrderPublisher Workers (4-16 threads)               │   │
│  │  - Rate-limited message generation                   │   │
│  │  - Scenario-based order creation                     │   │
│  │  - Automatic backpressure handling                   │   │
│  └─────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  MetricsCollector                                    │   │
│  │  - Real-time throughput                              │   │
│  │  - Latency percentiles (p50, p95, p99)               │   │
│  │  - Success/failure tracking                          │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ SBE-encoded messages
                           │ (aeron:udp)
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              Aeron Cluster (3 nodes)                        │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Node 0    │  │   Node 1    │  │   Node 2    │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
│                     │                                        │
│                     ▼                                        │
│           AppClusteredService                                │
│                     │                                        │
│                     ▼                                        │
│            Matching Engine                                   │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Build the Project

```bash
cd /Users/emre.bulutlar/Apps/phoenix/match-java/match
mvn clean package
```

### 2. Ensure Cluster is Running

The Aeron Cluster must be running on the configured hosts (default: 172.16.202.2, 172.16.202.3, 172.16.202.4).

### 3. Run Load Test

**Using the shell script (recommended):**
```bash
./run-load-test.sh quick
./run-load-test.sh baseline
./run-load-test.sh market-maker
```

**Basic test (1000 orders/sec for 60 seconds):**
```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator
```

**Custom configuration:**
```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  --rate 5000 \
  --duration 120 \
  --threads 8 \
  --scenario MARKET_MAKER
```

**High throughput test:**
```bash
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 10000 \
  -d 60 \
  -t 16 \
  -s AGGRESSIVE
```

> **Note:** The `--add-opens` flags are required for Aeron/Agrona to access internal JDK APIs for high-performance operations.

## Command Line Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--rate` | `-r` | Target orders per second | 1000 |
| `--duration` | `-d` | Test duration in seconds | 60 |
| `--threads` | `-t` | Number of worker threads | 4 |
| `--scenario` | `-s` | Trading scenario (see below) | BALANCED |
| `--hosts` | `-h` | Cluster hosts (comma-separated) | 172.16.202.2,172.16.202.3,172.16.202.4 |
| `--help` | | Show help message | |

## Trading Scenarios

### BALANCED
**Normal market conditions with mixed order types**

- **Order Mix**: 70% limit orders, 30% market orders
- **Spread**: 0.1% - 0.5% around mid price
- **Order Size**: 0.001 - 1.0 BTC
- **Use Case**: Baseline testing, typical market behavior

Example:
```bash
./run-load-test.sh custom 2000 60 4 BALANCED
```

### MARKET_MAKER
**High-frequency market making with tight spreads**

- **Order Mix**: 95% limit orders, 5% market orders
- **Spread**: 0.01% - 0.1% (very tight)
- **Order Size**: 0.01 - 0.5 BTC (smaller)
- **Use Case**: Test deep order book handling, frequent updates

Example:
```bash
./run-load-test.sh market-maker
# Or custom: ./run-load-test.sh custom 5000 120 8 MARKET_MAKER
```

### AGGRESSIVE
**High volatility with many market orders**

- **Order Mix**: 60% market orders, 40% limit orders
- **Spread**: 0.2% - 1.0% (wider)
- **Order Size**: 0.1 - 2.0 BTC (larger)
- **Use Case**: Test matching engine throughput, stress test

Example:
```bash
./run-load-test.sh stress
# Or custom: ./run-load-test.sh custom 8000 60 12 AGGRESSIVE
```

### SPIKE
**Burst traffic patterns with calm periods**

- **Pattern**: 5 seconds of intense activity, 15 seconds calm
- **Spike Mode**: 70% market orders, 30% limit orders
- **Calm Mode**: 80% limit orders, 20% market orders
- **Use Case**: Test backpressure handling, burst capacity

Example:
```bash
./run-load-test.sh spike
# Or custom: ./run-load-test.sh custom 3000 180 8 SPIKE
```

### DEEP_BOOK
**Build deep order book with many price levels**

- **Order Mix**: 90% limit orders
- **Spread**: 0.1% - 2.0% (wide range)
- **Order Size**: 0.001 - 1.0 BTC (varied)
- **Use Case**: Test order book depth limits, memory usage

Example:
```bash
./run-load-test.sh deep-book
# Or custom: ./run-load-test.sh custom 4000 300 8 DEEP_BOOK
```

## Metrics Output

### Real-Time Display (every second)

```
│  5,234 msg/s │    312,450 sent │    312,125 success │      325 fails │    127 BP │  99.90% │ p50:   2.14ms │ p99:  12.45ms │
```

**Columns:**
- **msg/s**: Messages per second (current rate)
- **sent**: Total messages sent
- **success**: Successfully acknowledged messages
- **fails**: Failed messages
- **BP**: Backpressure events
- **%**: Success rate percentage
- **p50**: Median latency
- **p99**: 99th percentile latency

### Final Report

```
╔════════════════════════════════════════════════════════════╗
║                    Final Results                           ║
╚════════════════════════════════════════════════════════════╝

Total Duration:             60,000 ms
Messages Sent:             300,000
Successful:                299,750 (99.92%)
Failed:                        250
Backpressure Events:         1,234
Timeouts:                        0
Average Throughput:          5,000.00 msg/s

Latency Distribution:
  Min:                          0.45 ms
  p50 (median):                 2.34 ms
  p95:                          8.12 ms
  p99:                         15.67 ms
  Max:                         45.23 ms
  Avg:                          3.21 ms
```

## Performance Tuning

### Thread Count Selection

- **Low throughput (< 2000 msg/s)**: 4 threads
- **Medium throughput (2000-5000 msg/s)**: 8 threads
- **High throughput (5000-10000 msg/s)**: 12-16 threads
- **Very high throughput (> 10000 msg/s)**: 16+ threads

Rule of thumb: Start with `threads = rate / 1000`

### Configuration Tuning

Edit `/Users/emre.bulutlar/Apps/phoenix/match-java/match/src/main/java/com/match/loadtest/LoadConfig.java`:

```java
// Increase retries for high load
private int maxRetries = 10;  // Default: 5

// Increase retry delay for backpressure
private long retryDelayMs = 20;  // Default: 10
```

### Network Configuration

For production-like testing, adjust cluster connection:

```java
// In LoadConfig.Builder
private String egressChannel = "aeron:udp?endpoint=YOUR_IP:9091";
private String ingressChannel = "aeron:udp?term-length=64k";
```

## Test Plans

### 1. Baseline Performance Test
**Goal**: Establish normal operating capacity

```bash
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 1000 -d 300 -t 4 -s BALANCED
```

### 2. Throughput Stress Test
**Goal**: Find maximum sustainable throughput

```bash
# Start low and incrementally increase
for rate in 2000 5000 8000 10000 15000; do
  echo "Testing rate: $rate"
  java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
    -r $rate -d 60 -t $((rate / 800)) -s AGGRESSIVE
  sleep 30  # Cool down between tests
done
```

### 3. Endurance Test
**Goal**: Verify stability over extended periods

```bash
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 3000 -d 3600 -t 8 -s BALANCED
```

### 4. Spike Handling Test
**Goal**: Test burst capacity and recovery

```bash
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 5000 -d 300 -t 10 -s SPIKE
```

### 5. Market Maker Simulation
**Goal**: Realistic market making workload

```bash
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 10000 -d 600 -t 16 -s MARKET_MAKER
```

## Interpreting Results

### Success Rate
- **> 99.5%**: Excellent, system handling load well
- **95-99.5%**: Good, some backpressure but manageable
- **90-95%**: Moderate issues, consider reducing load
- **< 90%**: Severe issues, system overloaded

### Backpressure Events
- **< 1% of sent messages**: Normal, expected under load
- **1-5%**: Moderate backpressure, system at capacity
- **> 5%**: High backpressure, reduce load or increase capacity

### Latency
- **p50 < 5ms**: Excellent
- **p50 5-10ms**: Good
- **p50 10-20ms**: Moderate, investigate bottlenecks
- **p50 > 20ms**: Poor, system struggling

- **p99 < 20ms**: Excellent
- **p99 20-50ms**: Acceptable
- **p99 50-100ms**: Concerning
- **p99 > 100ms**: Poor tail latency

## Troubleshooting

### Connection Failures

```
✗ Failed to start load generator: Failed to connect to cluster
```

**Solutions:**
1. Verify cluster is running: `docker ps`
2. Check cluster hosts configuration
3. Verify network connectivity: `ping 172.16.202.2`

### High Backpressure

```
Backpressure Events:  50,000
```

**Solutions:**
1. Reduce target rate: `-r 2000`
2. Increase worker threads: `-t 16`
3. Increase retry delay in `LoadConfig.java`
4. Check cluster performance (CPU, memory)

### Low Throughput

```
Average Throughput: 500.00 msg/s  (Target: 5000)
```

**Solutions:**
1. Increase worker threads
2. Check for network issues
3. Verify cluster nodes are healthy
4. Monitor cluster CPU/memory usage

### High Failure Rate

```
Failed:  5,000 (5.00%)
```

**Solutions:**
1. Check cluster logs for errors
2. Increase `maxRetries` in configuration
3. Reduce load to sustainable level
4. Verify SBE message encoding

## Advanced Usage

### Custom Scenario

Create your own scenario in `OrderScenario.java`:

```java
CUSTOM("My Custom Scenario") {
    @Override
    public OrderParams generateOrder(String market, double midPrice) {
        // Your custom logic here
        return new OrderParams(...);
    }
}
```

### Integration with Monitoring

Export metrics to external monitoring:

```java
// In MetricsCollector.java
public void exportMetrics(MetricsExporter exporter) {
    exporter.gauge("orders.success", successCount.sum());
    exporter.gauge("orders.latency.p99", latencyTracker.getStats().p99);
    // ...
}
```

## Files

- **LoadGenerator.java**: Main orchestration and CLI
- **LoadConfig.java**: Configuration builder
- **OrderPublisher.java**: Worker thread for sending orders
- **OrderScenario.java**: Trading pattern generators
- **MetricsCollector.java**: Performance metrics tracking
- **LoadTestEgressListener.java**: Cluster response handler

## Next Steps

1. Run baseline test to establish normal performance
2. Execute stress tests to find limits
3. Analyze metrics and identify bottlenecks
4. Tune configuration and retest
5. Document findings for capacity planning
