# Aeron Cluster - Critical Knowledge

## Session Management (IMPORTANT!)

### `onSessionOpen` / `onSessionClose` are NOT for external clients!

These callbacks are ONLY triggered for **cluster member sessions** (node-to-node consensus), NOT for external client connections like gateway or load generators.

**What this means:**
- Gateway connects → NO `onSessionClose` when it disconnects
- Load generator connects → NO cleanup when it crashes
- Sessions accumulate → Stale publications → Egress backpressure → Failure

### Solution: Application-Level Client Tracking

External clients MUST be tracked manually:

1. **Gateway Heartbeat System**
   - Gateway sends `GatewayHeartbeat` message every 250ms
   - Cluster tracks `gatewaySessionId` and `gatewayLastHeartbeatMs`
   - Broadcast only to gateway session (not all sessions)
   - 30-second timeout for gateway liveness check

2. **Load Generator = Fire-and-Forget**
   - Don't register load generators
   - They only send orders, don't need egress
   - Prevents stale session accumulation

```
┌─────────────────┐     ingress only    ┌─────────────────┐
│  Load Generator │ ──────────────────→ │                 │
│  (no egress)    │                     │  Aeron Cluster  │
└─────────────────┘                     │                 │
                                        │  Only sends     │
┌─────────────────┐  ingress + egress   │  egress to      │
│     Gateway     │ ←─────────────────→ │  gateway        │
│  (heartbeats)   │                     │                 │
└─────────────────┘                     └─────────────────┘
```

## Service Shutdown

### Don't call `session.close()` in `onTerminate()`!

When cluster terminates, consensus module is stopping. Calling `session.close()` tries to send close frames which blocks forever.

**Fix:** Just abandon sessions - they'll be forcibly closed anyway.

```java
@Override
public void onTerminate(final Cluster cluster) {
    // DON'T DO THIS - blocks forever:
    // clientSessions.forEach(session -> session.close());

    // Just log and let cluster terminate
    logger.info("Abandoning %d sessions", clientSessions.size());
    engine.close();
}
```

### Disruptor: Use `halt()` not `shutdown()`

`shutdown()` blocks waiting for pending events. Use `halt()` for immediate stop.

## Gateway Health Checks

### Don't use egress timeout during idle periods!

Old approach caused reconnection loops:
- No trades → No egress messages → 60s timeout → Reconnect → Repeat

**Fix:** Use heartbeats for liveness, not egress activity.

```java
// BAD: Causes reconnection loops during idle
private static final long EGRESS_TIMEOUT_MS = 60_000;

// GOOD: Disable egress timeout, rely on heartbeats
private static final long EGRESS_TIMEOUT_MS = Long.MAX_VALUE;
```

## Key Configuration Values

| Parameter | Value | Reason |
|-----------|-------|--------|
| Gateway heartbeat interval | 250ms | Fast enough to detect failures |
| Gateway timeout | 30s | Long enough for processing delays |
| Flush timer interval | 50ms | Batches market data efficiently |
| Market data queue | 10,000 | Bounded to prevent OOM |

## Files Changed for Heartbeat System

- `order-schema.xml` - Added `GatewayHeartbeat` message (ID 21)
- `AppClusteredService.java` - Gateway tracking, heartbeat handling, broadcast to gateway only
- `HttpController.java` - Send heartbeats every 250ms, disabled egress timeout
- `MatchEventPublisher.java` - Use `halt()` instead of `shutdown()`

## Testing Stability

Run multiple load tests in succession:
```bash
for i in 1 2 3; do ./run-load-test.sh quick; done
```

Verify:
- All tests complete with 100% success
- `hasSubscribers=true` throughout (check node0.log)
- WebSocket still active after tests
- Gateway heartbeats continue (`[GATEWAY] Heartbeat:` in logs)

## Common Failure Modes

1. **"Slow client" / backpressure**
   - Cause: Gateway polling too slow (old: 100ms sleep)
   - Fix: Use BackoffIdleStrategy (1ms max)

2. **Sessions never close**
   - Cause: `onSessionClose` not called for external clients
   - Fix: Heartbeat-based tracking

3. **Service stop hangs**
   - Cause: `session.close()` in onTerminate blocks
   - Fix: Remove session.close(), use halt() for Disruptor

4. **Gateway reconnection loops**
   - Cause: Egress timeout during idle periods
   - Fix: Disable egress timeout, use heartbeats

5. **Stale session state after restarts**
   - Symptom: Gateway sends heartbeats (positive offer() result) but cluster shows `hasSubscribers=false`
   - Cause: Cluster has stale session state from previous gateway, new gateway messages not processed
   - Fix: Clean restart with `make fresh` or manually clean `/dev/shm/aeron-emre-*`

6. **Gateway connection timeout on startup**
   - Symptom: `state=AWAIT_PUBLICATION_CONNECTED`, `ingressPublication=null`
   - Cause: Cluster nodes not fully started or stale Aeron directories
   - Fix: Wait for cluster to elect leader, clean `/dev/shm/aeron-emre-*` before restart

## Restart Procedures

### Clean Restart (Recommended)
```bash
make fresh
```
This stops all services, cleans cluster state, and starts fresh.

### Manual Clean Restart
```bash
# Stop services
systemctl --user stop match-gateway match-backup match-node0 match-node1 match-node2

# Wait for full stop
sleep 3

# Clean stale Aeron directories (CRITICAL!)
rm -rf /dev/shm/aeron-emre-*

# Start cluster nodes first
systemctl --user start match-node0 match-node1 match-node2
sleep 5  # Wait for leader election

# Start backup and gateway
systemctl --user start match-backup match-gateway
```

### Force Kill (If services hang)
```bash
for svc in match-gateway match-backup match-node0 match-node1 match-node2; do
    PID=$(systemctl --user show -p MainPID $svc | cut -d= -f2)
    [ "$PID" != "0" ] && kill -9 $PID
done
rm -rf /dev/shm/aeron-emre-*
```

## Debugging Market Data Flow

### Check Points
```
Cluster → Egress → Gateway → WebSocket → Browser
   ↓         ↓         ↓          ↓
 node0.log  gateway.log  ws:8081   DevTools
```

### Key Log Patterns

1. **Cluster side** (`/tmp/aeron-cluster/node0.log`):
   ```
   [GATEWAY] New gateway registered: sessionId=X    # Gateway connected
   [GATEWAY] Heartbeat: sessionId=X, age=Xms        # Heartbeats received
   [FLUSH-DEBUG] hasSubscribers=true                # Ready to broadcast
   ```

2. **Gateway side** (`/tmp/aeron-cluster/gateway.log`):
   ```
   [HB-SEND] result=X (positive = success)          # Heartbeats sent
   [EGRESS-DEBUG] msg=X len=X preview=...           # Market data received
   [POLL-DEBUG] work=X, totalWork=X                 # Egress polling
   ```

3. **Warning signs**:
   - `hasSubscribers=false` after load test = gateway session lost
   - `totalWork=0` in gateway = no egress being received
   - Old gatewayId in cluster logs = stale session state

### Quick Health Check
```bash
# Check heartbeat status
grep "GATEWAY" /tmp/aeron-cluster/node0.log | tail -3

# Check hasSubscribers
grep "hasSubscribers" /tmp/aeron-cluster/node0.log | tail -3

# Check egress flow
grep "EGRESS" /tmp/aeron-cluster/gateway.log | tail -3
```

## References

- [Aeron Cluster Clients](https://aeron.io/docs/aeron-cluster/cluster-clients/)
- [AdaptiveConsulting Sample](https://github.com/AdaptiveConsulting/aeron-io-samples/blob/main/admin/src/main/java/io/aeron/samples/admin/cluster/ClusterInteractionAgent.java)
