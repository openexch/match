sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated.sbe -Dsbe.output.dir=match/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match/src/main/resources/sbe/order-schema.xml

# ==================== NATIVE CLUSTER ====================
# JVM flags for ultra-low latency (server)
JAVA_OPTS = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx2g -Xms2g

# JVM flags for ultra-low latency load test client
JAVA_OPTS_CLIENT = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	-XX:CompileThreshold=1000 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx4g -Xms4g

JAR_PATH = match/target/cluster-engine-1.0.jar

# CPU cores for pinning (24 cores available on i7-13700K)
# Node0: cores 0-3, Node1: cores 4-7, Node2: cores 8-11, LoadTest: cores 12-19
# More cores for load test to handle DEDICATED threading + busy-spin threads
CPU_NODE0 = 0-3
CPU_NODE1 = 4-7
CPU_NODE2 = 8-11
CPU_LOADTEST = 12-19

# Build the project
build:
	cd match && mvn clean package -DskipTests -q

# Clean cluster data directories
clean-native:
	rm -rf /tmp/aeron-cluster/node0/* /tmp/aeron-cluster/node1/* /tmp/aeron-cluster/node2/*
	mkdir -p /tmp/aeron-cluster/node0 /tmp/aeron-cluster/node1 /tmp/aeron-cluster/node2

# Start native cluster (run in 3 separate terminals or use start-cluster)
start-node0:
	CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=0 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node0 java $(JAVA_OPTS) -jar $(JAR_PATH)

start-node1:
	CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=1 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node1 java $(JAVA_OPTS) -jar $(JAR_PATH)

start-node2:
	CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=2 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node2 java $(JAVA_OPTS) -jar $(JAR_PATH)

# Start all 3 nodes in background (no CPU pinning)
start-cluster: clean-native
	@echo "Starting 3-node cluster..."
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=0 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node0 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node0.log 2>&1 &
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=1 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node1 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node1.log 2>&1 &
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=2 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node2 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node2.log 2>&1 &
	@sleep 3
	@echo "Cluster started. Check logs: tail -f /tmp/aeron-cluster/node*.log"

# Start all 3 nodes with CPU pinning (requires 12+ cores)
start-cluster-pinned: clean-native
	@echo "Starting 3-node cluster with CPU pinning..."
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=0 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node0 taskset -c $(CPU_NODE0) java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node0.log 2>&1 &
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=1 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node1 taskset -c $(CPU_NODE1) java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node1.log 2>&1 &
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=2 CLUSTER_PORT_BASE=9000 \
	BASE_DIR=/tmp/aeron-cluster/node2 taskset -c $(CPU_NODE2) java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node2.log 2>&1 &
	@sleep 3
	@echo "Cluster started with CPU pinning. Check logs: tail -f /tmp/aeron-cluster/node*.log"

# Stop native cluster
stop-cluster:
	@echo "Stopping cluster..."
	@pkill -f "cluster-engine-1.0.jar" || true
	@echo "Cluster stopped"

# Native load tests (with optimized JVM flags)
loadtest-native:
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator

loadtest-native-1k:
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 1000 --duration 60

loadtest-native-10k:
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 10000 --duration 60

loadtest-native-50k:
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 50000 --duration 60

loadtest-native-100k:
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 100000 --duration 60

# Load test with CPU pinning (ultra-low latency)
loadtest-native-pinned:
	taskset -c $(CPU_LOADTEST) java $(JAVA_OPTS_CLIENT) \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 100000 --duration 60

# Load test with in-JVM warmup (recommended for accurate latency measurement)
# Runs warmup at lower rate, then main test - all in same JVM for JIT optimization
loadtest-native-warmup:
	@echo "=== Ultra-Low Latency Test with Warmup ===" && \
	@echo "Phase 1: JIT Warmup (10s at 50k/s)..." && \
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 50000 --duration 10 --no-ui && \
	@echo "Phase 2: Main Test (60s at 100k/s)..." && \
	java $(JAVA_OPTS_CLIENT) -cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 100000 --duration 60

# Full ultra-low latency test with CPU pinning and warmup
loadtest-ultra:
	@echo "=== Ultra-Low Latency Benchmark ===" && \
	@echo "Phase 1: JIT Warmup (15s at 50k/s)..." && \
	taskset -c $(CPU_LOADTEST) java $(JAVA_OPTS_CLIENT) \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 50000 --duration 15 --no-ui && \
	sleep 2 && \
	@echo "Phase 2: Main Test (60s at 100k/s)..." && \
	taskset -c $(CPU_LOADTEST) java $(JAVA_OPTS_CLIENT) \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 100000 --duration 60

# ==================== OS TUNING ====================
# Apply OS optimizations for ultra-low latency (requires sudo)
os-tune:
	@echo "Applying OS optimizations for ultra-low latency..."
	@echo "Setting CPU governor to performance..."
	sudo cpupower frequency-set -g performance || echo "cpupower not available, skipping"
	@echo "Tuning network buffers..."
	sudo sysctl -w net.core.rmem_max=16777216
	sudo sysctl -w net.core.wmem_max=16777216
	sudo sysctl -w net.core.rmem_default=1048576
	sudo sysctl -w net.core.wmem_default=1048576
	sudo sysctl -w net.core.netdev_max_backlog=30000
	sudo sysctl -w net.core.somaxconn=4096
	@echo "Tuning TCP settings..."
	sudo sysctl -w net.ipv4.tcp_rmem="4096 1048576 16777216"
	sudo sysctl -w net.ipv4.tcp_wmem="4096 1048576 16777216"
	sudo sysctl -w net.ipv4.tcp_low_latency=1
	sudo sysctl -w net.ipv4.tcp_fastopen=3
	@echo "Tuning VM settings..."
	sudo sysctl -w vm.swappiness=0
	sudo sysctl -w vm.dirty_ratio=10
	sudo sysctl -w vm.dirty_background_ratio=5
	@echo "OS tuning complete!"

# Show current OS settings
os-check:
	@echo "=== CPU Governor ==="
	@cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo "Not available"
	@echo "\n=== Network Buffers ==="
	@sysctl net.core.rmem_max net.core.wmem_max net.core.netdev_max_backlog
	@echo "\n=== VM Settings ==="
	@sysctl vm.swappiness vm.dirty_ratio
	@echo "\n=== CPU Count ==="
	@nproc

# ==================== DOCKER CLUSTER ====================
# Docker Compose komutları
up:
	docker compose -f docker/docker-compose.yml up -d

down:
	docker compose -f docker/docker-compose.yml down

logs:
	docker compose -f docker/docker-compose.yml logs -f

# Yük testi komutları
loadtest:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient

loadtest-custom:
	@read -p "Orders per second: " ops; \
	read -p "Duration (ms): " duration; \
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient $$ops $$duration

# Hızlı yük testi örnekleri
loadtest-1k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 1000 60000

loadtest-5k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 5000 60000

loadtest-10k:
	docker compose -f docker/docker-compose.yml exec loadtest java -cp /home/aeron/jar/cluster.jar com.match.LoadTestClient 10000 60000

# Cluster durumu kontrolü
status:
	docker compose -f docker/docker-compose.yml ps

# Temizlik
clean:
	docker compose -f docker/docker-compose.yml down -v
	docker system prune -f