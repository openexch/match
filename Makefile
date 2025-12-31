sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated.sbe -Dsbe.output.dir=match/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match/src/main/resources/sbe/order-schema.xml

# ==================== NATIVE CLUSTER ====================
# JVM flags for ultra-low latency
JAVA_OPTS = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx2g -Xms2g

JAR_PATH = match/target/cluster-engine-1.0.jar

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

# Start all 3 nodes in background
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

# Stop native cluster
stop-cluster:
	@echo "Stopping cluster..."
	@pkill -f "cluster-engine-1.0.jar" || true
	@echo "Cluster stopped"

# Native load tests
loadtest-native:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator

loadtest-native-1k:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 1000 --duration 60

loadtest-native-10k:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 10000 --duration 60

loadtest-native-warmup:
	@echo "=== Warmup (30s) ===" && \
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 10000 --duration 30 && \
	echo "=== Main Test (60s) ===" && \
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator --rate 10000 --duration 60

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