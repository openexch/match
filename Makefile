# ==================== MATCHING ENGINE CLUSTER ====================
# A high-performance, fault-tolerant order matching engine using Aeron Cluster
#
# Build & Setup: Use this Makefile
# Runtime Management: Use Admin Gateway (see ../admin-gateway)
#
# Quick Start:
#   make install-deps    # Install system dependencies (once)
#   make build           # Build Java components
#
# ==================================================================

.PHONY: install install-deps optimize-os help build build-java build-cluster build-gateway build-loadtest sbe os-check

# ==================== CONFIGURATION ====================
PROJECT_DIR := $(shell pwd)

# ==================== INSTALLATION ====================

install-deps:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing System Dependencies                         ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Checking Java 21+..."
	@if command -v java >/dev/null 2>&1; then \
		java_version=$$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1); \
		if [ "$$java_version" -ge 21 ] 2>/dev/null; then \
			echo "  ✓ Java $$java_version found"; \
		else \
			echo "  ✗ Java 21+ required (found $$java_version)"; \
			echo "  Run: sudo apt install openjdk-21-jdk"; \
			exit 1; \
		fi; \
	else \
		echo "  ✗ Java not found"; \
		echo "  Installing OpenJDK 21..."; \
		sudo apt update && sudo apt install -y openjdk-21-jdk; \
	fi
	@echo ""
	@echo "→ Checking Maven..."
	@if command -v mvn >/dev/null 2>&1; then \
		echo "  ✓ Maven found: $$(mvn -v | head -1)"; \
	else \
		echo "  Installing Maven..."; \
		sudo apt install -y maven; \
	fi
	@echo ""
	@echo "→ Checking system utilities..."
	@sudo apt install -y taskset util-linux procps net-tools 2>/dev/null || true
	@echo "  ✓ All dependencies installed"

install:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing Matching Engine Cluster                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Step 1/2: Building Java components..."
	@mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 2/2: Cleaning cluster state..."
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/node0/* /dev/shm/aeron-cluster/node1/* /dev/shm/aeron-cluster/node2/* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/backup/* 2>/dev/null || true
	@mkdir -p /dev/shm/aeron-cluster/node0 /dev/shm/aeron-cluster/node1 /dev/shm/aeron-cluster/node2 /dev/shm/aeron-cluster/backup
	@echo "  ✓ Cluster state cleaned"
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Match engine built!                                           ║"
	@echo "║                                                                  ║"
	@echo "║  To start the cluster, install and run the admin gateway:       ║"
	@echo "║    cd ../admin-gateway && make install                          ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

optimize-os:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Optimizing OS for Ultra-Low Latency                    ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ CPU Governor: Setting to performance mode..."
	@sudo cpupower frequency-set -g performance 2>/dev/null || \
		(for cpu in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do \
			echo performance | sudo tee $$cpu >/dev/null 2>&1; \
		done) || echo "  ⚠ Could not set CPU governor"
	@echo "→ Network buffer optimization..."
	@sudo sysctl -w net.core.rmem_max=16777216 >/dev/null
	@sudo sysctl -w net.core.wmem_max=16777216 >/dev/null
	@sudo sysctl -w net.core.rmem_default=1048576 >/dev/null
	@sudo sysctl -w net.core.wmem_default=1048576 >/dev/null
	@sudo sysctl -w net.core.netdev_max_backlog=30000 >/dev/null
	@sudo sysctl -w net.core.somaxconn=4096 >/dev/null
	@echo "  ✓ Network buffers optimized (16MB max)"
	@echo "→ TCP optimization..."
	@sudo sysctl -w net.ipv4.tcp_rmem="4096 1048576 16777216" >/dev/null
	@sudo sysctl -w net.ipv4.tcp_wmem="4096 1048576 16777216" >/dev/null
	@sudo sysctl -w net.ipv4.tcp_low_latency=1 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_fastopen=3 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_nodelay=1 2>/dev/null || true
	@sudo sysctl -w net.ipv4.tcp_timestamps=0 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_sack=0 >/dev/null
	@echo "  ✓ TCP optimized (low latency mode)"
	@echo "→ Memory management..."
	@sudo sysctl -w vm.swappiness=0 >/dev/null
	@sudo sysctl -w vm.dirty_ratio=10 >/dev/null
	@sudo sysctl -w vm.dirty_background_ratio=5 >/dev/null
	@sudo sysctl -w vm.zone_reclaim_mode=0 >/dev/null
	@sudo sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/enabled' 2>/dev/null || true
	@sudo sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/defrag' 2>/dev/null || true
	@echo "  ✓ Memory optimized (swappiness=0, THP disabled)"
	@echo "→ Kernel scheduling..."
	@sudo sysctl -w kernel.sched_min_granularity_ns=10000000 >/dev/null 2>&1 || true
	@sudo sysctl -w kernel.sched_wakeup_granularity_ns=15000000 >/dev/null 2>&1 || true
	@sudo sysctl -w kernel.sched_migration_cost_ns=5000000 >/dev/null 2>&1 || true
	@echo "  ✓ Kernel scheduler tuned"
	@echo "→ File descriptors & huge pages..."
	@sudo sysctl -w fs.file-max=2097152 >/dev/null
	@sudo sysctl -w vm.nr_hugepages=1024 >/dev/null 2>&1 || true
	@echo "  ✓ OS optimization complete"

# ==================== BUILD ====================

build: build-java
	@echo "✓ Build complete"

build-java:
	mvn clean package -DskipTests -q

build-cluster:
	mvn package -pl match-cluster -am -DskipTests -q

build-gateway:
	mvn package -pl match-gateway -am -DskipTests -q

build-loadtest:
	mvn package -pl match-loadtest -am -DskipTests -q

# ==================== CODE GENERATION ====================

sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated -Dsbe.output.dir=match-common/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match-common/src/main/resources/sbe/order-schema.xml

# ==================== SETUP (run once) ====================

os-check:
	@echo "=== CPU Governor ==="
	@cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo "Not available"
	@echo "\n=== Network Buffers ==="
	@sysctl net.core.rmem_max net.core.wmem_max net.core.netdev_max_backlog
	@echo "\n=== VM Settings ==="
	@sysctl vm.swappiness vm.dirty_ratio
	@echo "\n=== CPU Count ==="
	@nproc

# ==================== HELP ====================

help:
	@echo "Matching Engine Cluster"
	@echo ""
	@echo "Build & Setup:"
	@echo "  make install-deps       Install system dependencies (once)"
	@echo "  make install            Build Java and clean cluster state"
	@echo "  make build              Build all Java modules"
	@echo "  make build-java         Build all Java modules"
	@echo "  make build-cluster      Build cluster module only"
	@echo "  make build-gateway      Build gateway module only"
	@echo "  make build-loadtest     Build load test module"
	@echo "  make sbe                Generate SBE codec classes"
	@echo ""
	@echo "System:"
	@echo "  make optimize-os        OS tuning for low latency (sudo)"
	@echo "  make os-check           Show current OS settings"
	@echo ""
	@echo "Runtime management: see ../admin-gateway"
