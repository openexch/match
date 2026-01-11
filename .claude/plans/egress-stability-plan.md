# Aeron Cluster Egress Stability - Findings and Plan

## Session Summary

This document captures findings from investigating Aeron Cluster egress stability issues and outlines the implementation plan.

## What Was Implemented

### 1. Single-Threaded Model (COMPLETED)
**File**: `match/src/main/java/com/match/infrastructure/gateway/AeronGateway.java`

- Removed separate heartbeat thread (was "awful design" per Aeron docs)
- Integrated heartbeats into main polling loop
- All cluster operations now happen on single thread: `pollEgress()`, `offer()`, `sendKeepAlive()`

### 2. MediaDriver Location (COMPLETED)
- Changed from `/tmp/aeron-gateway-*` to `/dev/shm/aeron-gateway-*`
- Pure RAM operations, no disk I/O
- Uses unique directory per gateway instance to avoid stale image conflicts

### 3. Gateway Reconnection Logic (COMPLETED)
- Added consecutive failure tracking
- Recreates MediaDriver after 3 consecutive failures
- Detects `ingressPublication=null` errors and forces MediaDriver reset
- Exponential backoff: 500ms -> 1s -> 2s -> 4s (max)

### 4. Mark File Cleanup in Systemd (COMPLETED)
**Files**: `~/.config/systemd/user/node0.service`, `node1.service`, `node2.service`

Added ExecStartPre to delete mark files before node start:
```bash
ExecStartPre=/bin/bash -c 'rm -f /tmp/aeron-cluster/nodeX/cluster/cluster-mark*.dat /tmp/aeron-cluster/nodeX/archive/archive-mark.dat 2>/dev/null || true'
```

This fixes "active Mark file detected" errors during rolling updates.

## Critical Findings

### Finding 0: CONNECTION COMPLETELY BROKEN AFTER LOAD TEST (CRITICAL)

**This is the most severe issue discovered:**

After load test completes, the connection between gateway and cluster is PERMANENTLY broken:
- Gateway reports `connected=true` but messages don't reach cluster
- `cluster.offer()` returns positive values (local buffer write succeeds)
- But cluster NEVER receives the messages
- Even RESTARTING the gateway doesn't fix it - new session (4) can't communicate
- Cluster still shows old session (2) receiving nothing

**Evidence:**
```
Gateway: heartbeats=5795, egress=856 (stuck), connected=true
Cluster: heartbeatsReceived=501, session=2 (stopped receiving)

After gateway restart:
Gateway: session=4, heartbeats=499, egress=0 (no ACKs at all!)
Cluster: still shows session=2, heartbeatsReceived=501 (never sees session 4)
```

**Root Cause Hypothesis:**
The cluster's ingress subscription or the Aeron transport layer is in a broken state. The cluster node may need to be restarted, or there's a fundamental issue with how the cluster handles high message volume.

**Only known fix:** Full cluster + gateway restart with Aeron directory cleanup:
```bash
systemctl --user stop node0 node1 node2 market order admin
rm -rf /dev/shm/aeron-* /tmp/aeron-*
systemctl --user start node0 node1 node2
sleep 8
systemctl --user start market order admin
```

**This must be fixed before production use.**

---

### Finding 1: Heartbeat Loss During High Load

**Symptoms**:
- Before load test: Gateway sends 299 heartbeats, cluster receives 301 (100% delivery)
- After load test: Gateway sends 1298 heartbeats, cluster receives 501 (39% delivery)
- Egress stops flowing after load test (stuck at fixed count)

**Root Cause**:
`cluster.offer()` returns positive values (position in publication buffer) but this only means the message was written to LOCAL buffer. Aeron UDP doesn't guarantee delivery. During high load:
1. Cluster ingress subscription falls behind processing 60k orders
2. Market gateway's heartbeat messages compete with load test orders
3. Term buffer may wrap before messages consumed
4. UDP packets dropped silently

**Evidence**:
```
Gateway stats: egress=856, heartbeats=1298  (856 ACKs for 1298 heartbeats)
Cluster stats: heartbeatsReceived=501       (only 501 actually received)
```

### Finding 2: Rolling Update Leaves Stale Aeron State

**Symptoms**:
- After rolling update, gateways fail to connect
- Error: `ingressPublication=null`, `state=AWAIT_PUBLICATION_CONNECTED`
- Requires full Aeron directory cleanup to recover

**Root Cause**:
- Rolling update restarts cluster nodes but not gateways
- Gateway's MediaDriver has stale subscriptions/images from old cluster sessions
- New cluster connections can't establish ingress publication

**Current Workaround**:
Full cleanup required: `rm -rf /dev/shm/aeron-* /tmp/aeron-*`

### Finding 3: JSON in Egress is Suboptimal

**Current Implementation**:
```java
// AppClusteredService.java:310-312
String ack = HEARTBEAT_ACK_PREFIX + System.currentTimeMillis() + HEARTBEAT_ACK_SUFFIX;
byte[] bytes = ack.getBytes(StandardCharsets.UTF_8);
session.offer(broadcastBuffer, 0, bytes.length);
```

**Problems**:
- String allocation on every heartbeat ACK
- UTF-8 encoding overhead
- JSON parsing on gateway side
- Larger message sizes than binary

**Best Practice**:
Use SBE (Simple Binary Encoding) for all cluster messages - already have schema and generated code.

## Load Test Results

After fixes, load test performs excellently:
```
Total Duration:     60,354 ms
Messages Sent:      60,000
Successful:         60,000 (100.00%)
Failed:             0
Backpressure:       0

Latency Distribution:
  p50:    0.48 μs
  p95:    2.94 μs
  p99:    12.39 μs
  Max:    3,754.53 μs
```

## Implementation Plan

### Phase 0: Investigate and Fix Broken Connection (Priority: CRITICAL)

**Must be done first.** The connection breaks after load test and doesn't recover.

**Investigation steps:**
1. Check if cluster leader's ingress subscription is healthy after load test
2. Check if load test client disconnection causes issues
3. Check Aeron error counters via AeronStat
4. Check if cluster archive is corrupted or full
5. Review load test's AeronCluster client cleanup

**Possible causes to investigate:**
1. Load test client and market gateway share same egress endpoint (127.0.0.1:9091)
2. Cluster's max sessions exhausted (default 10, check `aeron.cluster.max.sessions`)
3. Term buffer exhaustion causing publication to become unusable
4. Archive recording falling behind causing backpressure

**Potential fixes:**
1. Use different egress ports for each client
2. Increase max sessions
3. Ensure load test client disconnects cleanly
4. Add cluster health monitoring

---

### Phase 1: Fix Heartbeat Reliability (Priority: HIGH)

#### Option A: Separate Heartbeat Publication (Recommended)
Create dedicated ingress channel for heartbeats, separate from order flow.

**Files to modify**:
- `AeronGateway.java`: Add separate heartbeat publication
- `AppClusteredService.java`: Subscribe to heartbeat channel separately

**Approach**:
```java
// Dedicated heartbeat channel (different stream ID)
private static final int HEARTBEAT_STREAM_ID = 200;
private Publication heartbeatPublication;

// In connectToCluster():
heartbeatPublication = aeron.addPublication(
    "aeron:udp?endpoint=127.0.0.1:9003", // Dedicated port
    HEARTBEAT_STREAM_ID
);
```

#### Option B: Adaptive Heartbeat Rate
Reduce heartbeat frequency during detected high load.

```java
// In polling loop:
if (lastPollWork > HIGH_LOAD_THRESHOLD) {
    heartbeatIntervalNs = HEARTBEAT_INTERVAL_NS * 10; // 1 second instead of 100ms
} else {
    heartbeatIntervalNs = HEARTBEAT_INTERVAL_NS;
}
```

#### Option C: Heartbeat Retry with ACK Tracking
Track sent heartbeats and retry if ACK not received.

```java
private long lastHeartbeatSentNs;
private long lastAckReceivedNs;

// If no ACK for 500ms, resend
if (nowNs - lastAckReceivedNs > 500_000_000 && nowNs - lastHeartbeatSentNs > 100_000_000) {
    sendGatewayHeartbeat(timestamp);
}
```

### Phase 2: Replace JSON with SBE (Priority: MEDIUM)

**Files to modify**:
1. `match/src/main/resources/sbe/order-schema.xml` - Add message types:
   - `HeartbeatAck` (templateId=22)
   - `TradeExecution` (existing, needs egress version)
   - `OrderStatus` (existing, needs egress version)
   - `MarketDataUpdate` (new)

2. `AppClusteredService.java`:
   - Replace JSON string building with SBE encoding
   - Use pre-allocated encoders (zero allocation)

3. `AeronGateway.java`:
   - Decode SBE messages in `onMessage()`
   - Convert to JSON only at WebSocket boundary

**Example SBE schema addition**:
```xml
<sbe:message name="HeartbeatAck" id="22">
    <field name="timestamp" id="1" type="int64"/>
    <field name="gatewayId" id="2" type="int64"/>
</sbe:message>
```

### Phase 3: Improve Rolling Update Resilience (Priority: MEDIUM)

**Option A: Gateway auto-cleanup on reconnect failure**
```java
// In tryReconnect(), after MAX_FAILURES:
if (consecutiveReconnectFailures >= MAX_FAILURES_BEFORE_DRIVER_RESET) {
    // Close and fully cleanup MediaDriver
    CloseHelper.quietClose(mediaDriver);
    mediaDriver = null;
    // Delete stale Aeron directory
    deleteAeronDirectory("/dev/shm/aeron-gateway-" + gatewayId);
    createMediaDriver(); // Fresh start
}
```

**Option B: Use IPC for local cluster**
Switch from UDP to IPC channels for localhost deployment:
```java
.ingressChannel("aeron:ipc?term-length=16m")
.egressChannel("aeron:ipc")
```
Benefits: No UDP packet loss, lower latency, simpler configuration.

### Phase 4: Monitoring Improvements (Priority: LOW)

Add metrics endpoint to market gateway:
```java
// GET /metrics
{
    "heartbeatsSent": 1298,
    "heartbeatsAcked": 856,
    "egressReceived": 1034,
    "connected": true,
    "sessionId": 2,
    "leader": 0
}
```

## Files Modified in This Session

| File | Changes |
|------|---------|
| `AeronGateway.java` | Single-threaded model, /dev/shm location, reconnection logic |
| `AppClusteredService.java` | Heartbeat counter logging, ACK failure logging |
| `~/.config/systemd/user/node*.service` | Mark file cleanup |

## Testing Checklist

After implementing changes:

1. [ ] Clean start: `rm -rf /dev/shm/aeron-* /tmp/aeron-*` then start all services
2. [ ] Verify heartbeats flow: Check gateway egress matches heartbeat count
3. [ ] Run load test: `./run-load-test.sh baseline`
4. [ ] Check post-load-test: Heartbeats should continue flowing
5. [ ] Test leader failover: Kill leader, verify gateway reconnects
6. [ ] Test rolling update: `curl -X POST http://localhost:8082/api/admin/rolling-update`
7. [ ] Verify WebSocket receives market data during load test

## Key Code Locations

- Cluster setup: `match/src/main/java/com/match/infrastructure/persistence/AeronCluster.java`
- Gateway base: `match/src/main/java/com/match/infrastructure/gateway/AeronGateway.java`
- Cluster service: `match/src/main/java/com/match/infrastructure/persistence/AppClusteredService.java`
- SBE schema: `match/src/main/resources/sbe/order-schema.xml`
- Constants: `match/src/main/java/com/match/infrastructure/InfrastructureConstants.java`

## References

- [Aeron Cluster Egress](https://theaeronfiles.com/aeron-cluster/messages/egress/)
- [Aeron Cluster Clients](https://aeron.io/docs/aeron-cluster/cluster-clients/)
- [AeronCluster Source](https://github.com/aeron-io/aeron/blob/master/aeron-cluster/src/main/java/io/aeron/cluster/client/AeronCluster.java)
