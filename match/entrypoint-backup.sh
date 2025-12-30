#!/bin/bash
# entrypoint-backup.sh - ClusterBackup JVM configuration

# Memory settings - backup node needs less memory than cluster nodes
HEAP_SIZE="${HEAP_SIZE:-512m}"

# JVM flags for ClusterBackup
java \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  \
  -Xms${HEAP_SIZE} \
  -Xmx${HEAP_SIZE} \
  \
  -XX:+UseZGC \
  \
  -XX:+AlwaysPreTouch \
  \
  -XX:+PerfDisableSharedMem \
  \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.ipc.mtu.length=8k \
  -Daeron.socket.so_sndbuf=2097152 \
  -Daeron.socket.so_rcvbuf=2097152 \
  -Daeron.rcv.initial.window.length=2097152 \
  \
  -cp /home/aeron/jar/cluster.jar \
  com.match.infrastructure.persistence.ClusterBackupApp \
  "$@"
