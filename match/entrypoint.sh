#!/bin/bash
# entrypoint.sh

java \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Djava.net.preferIPv4Stack=true \
  -Daeron.ipc.mtu.length=8k \
  -cp /home/aeron/jar/cluster.jar \
  com.match.Main \
  "$@"