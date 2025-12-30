# Load Testing Framework

Direct Aeron Cluster load generator for matching engine performance testing.

## Quick Start

```bash
# Build the project
cd /Users/emre.bulutlar/Apps/phoenix/match-java/match
mvn clean package

# Run basic load test
./run-load-test.sh quick

# Or use the menu
./run-load-test.sh
```

## Components

### LoadGenerator.java
Main orchestration class. Manages:
- Media driver and cluster connection
- Worker thread pool
- Metrics reporting
- Graceful shutdown

### LoadConfig.java
Configuration builder with defaults:
- **Rate**: 1000 orders/sec
- **Duration**: 60 seconds
- **Threads**: 4 workers
- **Market**: BTC-USD
- **Scenario**: BALANCED

### OrderPublisher.java
Worker threads that:
- Generate orders based on scenario
- Encode messages using SBE
- Send to cluster with backpressure handling
- Track latency and success/failure

### OrderScenario.java
Trading pattern generators:
- **BALANCED**: Normal market (70% limit, 30% market)
- **MARKET_MAKER**: HFT style (95% limit, tight spreads)
- **AGGRESSIVE**: High volatility (60% market orders)
- **SPIKE**: Burst patterns (5s spike, 15s calm)
- **DEEP_BOOK**: Wide price ranges (90% limit)

### MetricsCollector.java
Performance tracking:
- Success/failure counts
- Backpressure events
- Latency percentiles (p50, p95, p99)
- Real-time throughput

### LoadTestEgressListener.java
Handles cluster responses for metrics correlation

## Usage Examples

### Command Line

```bash
# Basic test
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator

# High throughput
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 10000 -d 120 -t 16 -s AGGRESSIVE

# Market maker simulation
java -cp target/cluster-engine-1.0.jar com.match.loadtest.LoadGenerator \
  -r 5000 -d 300 -t 8 -s MARKET_MAKER
```

### Script Shortcuts

```bash
# Predefined tests
./run-load-test.sh baseline
./run-load-test.sh market-maker
./run-load-test.sh stress

# Progressive testing
./run-load-test.sh progressive

# Custom test
./run-load-test.sh custom 3000 120 8 SPIKE
```

## Configuration

### Network Settings

Default cluster connection:
```java
clusterHosts: 172.16.202.2, 172.16.202.3, 172.16.202.4
basePort: 9000
egressChannel: aeron:udp?endpoint=172.16.202.10:9091
ingressChannel: aeron:udp?term-length=64k
```

Modify in `LoadConfig.Builder` for your environment.

### Tuning Parameters

```java
maxRetries: 5           // Retry attempts on backpressure
retryDelayMs: 10        // Delay between retries
```

## Metrics

### Real-time Output
```
│  5,234 msg/s │  312,450 sent │  312,125 success │  325 fails │  127 BP │  99.90% │ p50: 2.14ms │ p99: 12.45ms │
```

### Final Report
```
Total Duration:        60,000 ms
Messages Sent:        300,000
Successful:           299,750 (99.92%)
Failed:                   250
Backpressure Events:    1,234
Average Throughput:     5,000.00 msg/s

Latency Distribution:
  p50 (median):          2.34 ms
  p95:                   8.12 ms
  p99:                  15.67 ms
```

## Architecture

```
LoadGenerator
  ├── MediaDriver (embedded)
  ├── AeronCluster (client connection)
  ├── OrderPublisher[] (worker threads)
  │   ├── SBE message encoding
  │   ├── Rate limiting
  │   └── Backpressure handling
  ├── MetricsCollector (performance tracking)
  └── LoadTestEgressListener (response handler)
```

## Performance Guidelines

### Thread Sizing
- **< 2000 msg/s**: 4 threads
- **2000-5000 msg/s**: 8 threads
- **5000-10000 msg/s**: 12-16 threads
- **> 10000 msg/s**: 16+ threads

### Success Rate Targets
- **> 99.5%**: Excellent
- **95-99.5%**: Good
- **90-95%**: Moderate
- **< 90%**: Overloaded

### Latency Targets
- **p50 < 5ms**: Excellent
- **p50 < 10ms**: Good
- **p99 < 20ms**: Excellent
- **p99 < 50ms**: Acceptable

## Troubleshooting

**Connection failed:**
- Check cluster is running
- Verify network connectivity
- Confirm host configuration

**High backpressure:**
- Reduce target rate
- Increase worker threads
- Check cluster CPU/memory

**Low throughput:**
- Increase worker threads
- Check network latency
- Verify cluster health

## See Also

- [LOAD_TEST_GUIDE.md](../../../../../LOAD_TEST_GUIDE.md) - Comprehensive guide
- [run-load-test.sh](../../../../../run-load-test.sh) - Test runner script
