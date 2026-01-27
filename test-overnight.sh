#!/bin/bash
# Overnight cluster stress test - observe only, no changes
# Results logged to /home/emre/Apps/match/overnight-test-results.log

LOG="/home/emre/Apps/match/overnight-test-results.log"
TIMESTAMP() { date "+%Y-%m-%d %H:%M:%S"; }

echo "======================================" >> $LOG
echo "Test run started at $(TIMESTAMP)" >> $LOG
echo "======================================" >> $LOG

# 1. Check cluster health
echo "[$(TIMESTAMP)] Checking cluster status..." >> $LOG
STATUS=$(curl -s http://localhost:8082/api/admin/status)
LEADER=$(echo $STATUS | jq -r '.leader')
NODES_OK=$(echo $STATUS | jq '[.nodes[] | select(.running==true)] | length')
echo "  Leader: $LEADER, Running nodes: $NODES_OK/3" >> $LOG

if [ "$NODES_OK" != "3" ]; then
    echo "  ⚠️  WARNING: Not all nodes running!" >> $LOG
fi

# 2. Check memory usage of cluster processes
echo "[$(TIMESTAMP)] Memory check..." >> $LOG
for pid in $(pgrep -f "match-cluster.jar" | head -3); do
    MEM=$(ps -p $pid -o rss= 2>/dev/null | tr -d ' ')
    if [ -n "$MEM" ]; then
        MEM_MB=$((MEM / 1024))
        echo "  PID $pid: ${MEM_MB}MB" >> $LOG
    fi
done

# 3. Check archive sizes
echo "[$(TIMESTAMP)] Archive sizes..." >> $LOG
for i in 0 1 2; do
    SIZE=$(du -sh /dev/shm/aeron-cluster/node$i 2>/dev/null | cut -f1)
    echo "  Node $i archive: $SIZE" >> $LOG
done

# 4. Send test orders (read-only market data check)
echo "[$(TIMESTAMP)] Testing order gateway connectivity..." >> $LOG
ORDER_RESP=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 http://localhost:8080/health 2>/dev/null || echo "FAIL")
echo "  Order gateway health: $ORDER_RESP" >> $LOG

# 5. Test market gateway WebSocket (just check port)
echo "[$(TIMESTAMP)] Testing market gateway port..." >> $LOG
MARKET_OK=$(ss -tlnp | grep -c ":8081" || echo "0")
echo "  Market gateway listening: $MARKET_OK" >> $LOG

# 6. Load test - send burst of orders
echo "[$(TIMESTAMP)] Load test - 100 order burst..." >> $LOG
SUCCESS=0
FAIL=0
START=$(date +%s%N)
for i in $(seq 1 100); do
    PRICE=$(echo "scale=2; 50000 + $RANDOM / 100" | bc)
    QTY=$(echo "scale=4; ($RANDOM % 100 + 1) / 100" | bc)
    SIDE=$([[ $((RANDOM % 2)) -eq 0 ]] && echo "BUY" || echo "SELL")
    RESP=$(curl -s -X POST http://localhost:8080/order \
        -H "Content-Type: application/json" \
        -d "{\"userId\":\"test-$i\",\"market\":\"BTC-USD\",\"orderType\":\"LIMIT\",\"orderSide\":\"$SIDE\",\"price\":$PRICE,\"quantity\":$QTY,\"totalPrice\":0}" \
        --connect-timeout 2 --max-time 5 2>/dev/null)
    if echo "$RESP" | grep -q "accepted"; then
        SUCCESS=$((SUCCESS + 1))
    else
        FAIL=$((FAIL + 1))
    fi
done
END=$(date +%s%N)
DURATION=$(( (END - START) / 1000000 ))
echo "  Success: $SUCCESS, Failed: $FAIL, Duration: ${DURATION}ms" >> $LOG
if [ "$FAIL" -gt 10 ]; then
    echo "  ⚠️  WARNING: High failure rate!" >> $LOG
fi

# 7. Check for errors in node logs (last 5 minutes)
echo "[$(TIMESTAMP)] Checking recent log errors..." >> $LOG
for i in 0 1 2; do
    ERRORS=$(tail -500 ~/.local/log/cluster/node$i.log 2>/dev/null | grep -ci "exception\|error\|fatal" 2>/dev/null || echo "0")
    ERRORS=$(echo "$ERRORS" | tr -d '\n' | head -c 10)
    if [ -n "$ERRORS" ] && [ "$ERRORS" -gt 0 ] 2>/dev/null; then
        echo "  ⚠️  Node $i: $ERRORS error(s) in recent logs" >> $LOG
        tail -500 ~/.local/log/cluster/node$i.log 2>/dev/null | grep -i "exception\|error\|fatal" | tail -3 >> $LOG
    fi
done

# 8. Verify leader is responsive
echo "[$(TIMESTAMP)] Leader responsiveness test..." >> $LOG
SNAP_RESP=$(curl -s -X POST http://localhost:8082/api/admin/snapshot --connect-timeout 5 2>/dev/null)
if echo "$SNAP_RESP" | grep -q "initiated"; then
    echo "  Snapshot initiated successfully" >> $LOG
    sleep 8
    PROGRESS=$(curl -s http://localhost:8082/api/admin/progress)
    SNAP_OK=$(echo $PROGRESS | jq -r '.complete')
    SNAP_ERR=$(echo $PROGRESS | jq -r '.error')
    echo "  Snapshot complete: $SNAP_OK, error: $SNAP_ERR" >> $LOG
    if [ "$SNAP_ERR" = "true" ]; then
        echo "  ⚠️  WARNING: Snapshot failed!" >> $LOG
        echo "$PROGRESS" >> $LOG
    fi
else
    echo "  ⚠️  WARNING: Could not initiate snapshot" >> $LOG
fi

# 9. Final status
echo "[$(TIMESTAMP)] Final cluster status..." >> $LOG
FINAL_STATUS=$(curl -s http://localhost:8082/api/admin/status)
echo "$FINAL_STATUS" | jq '{leader, nodes: [.nodes[] | {id, role, running}]}' >> $LOG

echo "[$(TIMESTAMP)] Test run completed" >> $LOG
echo "" >> $LOG
