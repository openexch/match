#!/bin/bash
# entrypoint.sh - Ultra-low latency JVM configuration for Aeron Cluster
# Optimized for stable, consistent sub-millisecond latency

# Memory settings - need enough for order book arrays
HEAP_SIZE="${HEAP_SIZE:-2g}"

# ==================== ULTRA-LOW LATENCY JVM FLAGS ====================
exec java \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  -Xms${HEAP_SIZE} \
  -Xmx${HEAP_SIZE} \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:ZCollectionInterval=0 \
  -XX:ZFragmentationLimit=5 \
  -XX:+AlwaysPreTouch \
  -XX:+TieredCompilation \
  -XX:TieredStopAtLevel=4 \
  -XX:CompileThreshold=1000 \
  -XX:+PerfDisableSharedMem \
  -XX:+UseThreadPriorities \
  -XX:ThreadPriorityPolicy=1 \
  -XX:+UnlockDiagnosticVMOptions \
  -XX:GuaranteedSafepointInterval=0 \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.ipc.mtu.length=8k \
  -Daeron.socket.so_sndbuf=212992 \
  -Daeron.socket.so_rcvbuf=212992 \
  -Daeron.rcv.initial.window.length=212992 \
  -Daeron.term.buffer.sparse.file=false \
  -Daeron.pre.touch.mapped.memory=true \
  -Dagrona.disable.bounds.checks=true \
  -cp /home/aeron/jar/cluster.jar \
  com.match.Main \
  "$@"
