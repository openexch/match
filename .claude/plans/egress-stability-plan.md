# Aeron Cluster Egress Stability - Plan

## Status: Phase 0 COMPLETED

**Test Results (2026-01-12):**
- Baseline: 60k orders @ 1k/s, 100% success
- Stress: 538k orders @ 9k/s, 100% success, 0 failures

---

## Completed Work

### Phase 0: Connection Breaking After Load Test (FIXED)

**Root Causes Found & Fixed:**

1. **Order Book Infinite Loop** - Self-referential cycle when tail slot cancelled and reused
   - File: `DirectIndexOrderBook.java:167-171`
   - Added safety check to prevent self-referential cycle

2. **Gateway Using Custom Heartbeat Instead of sendKeepAlive()** - Custom heartbeat had no retry
   - File: `AeronGateway.java`
   - Simplified to use only `sendKeepAlive()` (has 3-attempt retry built-in)

3. **LoadGenerator Sharing Aeron Directory** - Caused conflicts with cluster nodes
   - File: `LoadGenerator.java`
   - Added unique directory: `/dev/shm/aeron-loadtest-{nanoTime}`

4. **Max Sessions Too Low** - Default 10 could be exhausted
   - File: `ClusterConfig.java`
   - Increased to `maxConcurrentSessions(50)`

5. **Broadcasting to Specific Gateway Session** - Complex and fragile
   - File: `AppClusteredService.java`
   - Changed to broadcast to ALL connected sessions

**Commits:**
- `968e9f2` - Fix order book infinite loop and simplify Aeron gateway
- `13bdb9e` - Add gateway state management and infrastructure improvements
- `f6cf767` - Fix admin API logs endpoint to read from log files

### Other Completed Improvements

- Single-threaded gateway model (removed separate heartbeat thread)
- MediaDriver in `/dev/shm/` for RAM-only operations
- Gateway reconnection with exponential backoff
- Mark file cleanup in systemd services
- Admin API logs endpoint fixed to read from `~/.local/log/cluster/`

---

## Remaining TODO

None - all critical issues resolved.

---

## Future Phases (Optional)

### Phase 1: Replace JSON with SBE (Priority: MEDIUM)

Currently heartbeat ACKs and market data use JSON encoding which allocates on every message.

**Files to modify:**
1. `order-schema.xml` - Add egress message types
2. `AppClusteredService.java` - Replace JSON with SBE encoding
3. `AeronGateway.java` - Decode SBE, convert to JSON only at WebSocket boundary

### Phase 2: Improve Rolling Update Resilience (Priority: MEDIUM)

- Gateway auto-cleanup on reconnect failure
- Consider IPC channels for localhost deployment

### Phase 3: Monitoring Improvements (Priority: LOW)

Add metrics endpoint to market gateway:
```json
{
  "heartbeatsSent": 1298,
  "egressReceived": 1034,
  "connected": true,
  "sessionId": 2
}
```

---

## Testing Checklist

After changes:

1. [x] Clean start with all services
2. [x] Verify heartbeats flow
3. [x] Run baseline load test (60k orders)
4. [x] Check post-load-test connectivity
5. [x] Run stress test (538k orders)
6. [ ] Test leader failover (gateway reconnects)
7. [ ] Test rolling update

---

## Key Code Locations

| Component | Path |
|-----------|------|
| Cluster setup | `match/src/main/java/com/match/infrastructure/persistence/AeronCluster.java` |
| Gateway base | `match/src/main/java/com/match/infrastructure/gateway/AeronGateway.java` |
| Cluster service | `match/src/main/java/com/match/infrastructure/persistence/AppClusteredService.java` |
| Order book | `match/src/main/java/com/match/application/orderbook/DirectIndexOrderBook.java` |
| Cluster config | `match/src/main/java/com/match/infrastructure/persistence/ClusterConfig.java` |
| Load generator | `match/src/main/java/com/match/loadtest/LoadGenerator.java` |
