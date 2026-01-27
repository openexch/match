# ==================== MATCHING ENGINE CLUSTER ====================
# A high-performance, fault-tolerant order matching engine using Aeron Cluster
#
# Build & Setup: Use this Makefile
# Runtime Management: Use Admin Gateway API (http://localhost:8082)
#
# Quick Start:
#   make install-deps    # Install system dependencies (once)
#   make install         # Build and start everything fresh
#
# ==================================================================

.PHONY: install install-deps optimize-os help install-services uninstall-services reinstall-services build build-java build-cluster build-gateway build-admin build-loadtest build-ui sbe setup-port-80 os-check

# ==================== CONFIGURATION ====================
PROJECT_DIR := $(shell pwd)
SERVICE_USER := $(shell whoami)
COMMA := ,
CLUSTER_ADDRS := 127.0.0.1$(COMMA)127.0.0.1$(COMMA)127.0.0.1

# JAR paths
CLUSTER_JAR = match-cluster/target/match-cluster.jar
GATEWAY_JAR = match-gateway/target/match-gateway.jar
LOADTEST_JAR = match-loadtest/target/match-loadtest.jar

# JVM flags for ultra-low latency
JAVA_OPTS = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx2g -Xms2g

# CPU cores for pinning (24 cores available on i7-13700K)
CPU_NODE0 = 0-3
CPU_NODE1 = 4-7
CPU_NODE2 = 8-11

# ==================== USER SERVICE CONFIGURATION ====================
LOG_DIR := $(HOME)/.local/log/cluster
USER_SERVICE_DIR := $(HOME)/.config/systemd/user

LOG_ROTATE = /bin/bash -c '"'"'test -f $(LOG_DIR)/$(1).log && mv $(LOG_DIR)/$(1).log $(LOG_DIR)/$(1).log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'

# Java service template: $(1)=name, $(2)=desc, $(3)=env_lines, $(4)=pre_cmds, $(5)=exec, $(6)=restart_sec, $(7)=extra_limits
define JAVA_SERVICE
@printf '%s\n' \
	'[Unit]' \
	'Description=$(2)' \
	'After=default.target' \
	'' \
	'[Service]' \
	'Type=simple' \
	'WorkingDirectory=$(PROJECT_DIR)' \
	$(3) \
	'ExecStartPre=$(call LOG_ROTATE,$(1))' \
	$(4) \
	'ExecStart=$(5)' \
	'Restart=on-failure' \
	'RestartSec=$(6)' \
	'TimeoutStopSec=5' \
	'KillMode=mixed' \
	$(7) \
	'StandardOutput=append:$(LOG_DIR)/$(1).log' \
	'StandardError=append:$(LOG_DIR)/$(1).log' \
	'' \
	'[Install]' \
	'WantedBy=default.target' > $(USER_SERVICE_DIR)/$(1).service
endef

define UI_SERVICE
@printf '%s\n' \
	'[Unit]' \
	'Description=Match Engine Trading UI' \
	'After=default.target' \
	'' \
	'[Service]' \
	'Type=simple' \
	'WorkingDirectory=$(PROJECT_DIR)/match/ui' \
	'ExecStartPre=$(call LOG_ROTATE,ui)' \
	'ExecStart=/usr/bin/npx vite preview --port 3000 --host' \
	'Restart=on-failure' \
	'RestartSec=5' \
	'StandardOutput=append:$(LOG_DIR)/ui.log' \
	'StandardError=append:$(LOG_DIR)/ui.log' \
	'' \
	'[Install]' \
	'WantedBy=default.target' > $(USER_SERVICE_DIR)/ui.service
endef

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
	@echo "→ Checking Node.js 18+..."
	@if command -v node >/dev/null 2>&1; then \
		node_version=$$(node -v | cut -d'v' -f2 | cut -d'.' -f1); \
		if [ "$$node_version" -ge 18 ] 2>/dev/null; then \
			echo "  ✓ Node.js $$node_version found"; \
		else \
			echo "  ✗ Node.js 18+ required"; \
			exit 1; \
		fi; \
	else \
		echo "  Installing Node.js..."; \
		curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -; \
		sudo apt install -y nodejs; \
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
	@systemctl --user stop ui admin order market backup node2 node1 node0 2>/dev/null || true
	@echo "→ Step 1/5: Building UI..."
	@cd match/ui && npm install --silent && npm run build --silent
	@echo "  ✓ UI built"
	@echo ""
	@echo "→ Step 2/5: Building Java components..."
	@mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 3/5: Installing systemd services..."
	@$(MAKE) -s install-services
	@echo ""
	@echo "→ Step 4/5: Cleaning cluster state..."
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/node0/* /dev/shm/aeron-cluster/node1/* /dev/shm/aeron-cluster/node2/* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/backup/* 2>/dev/null || true
	@mkdir -p /dev/shm/aeron-cluster/node0 /dev/shm/aeron-cluster/node1 /dev/shm/aeron-cluster/node2 /dev/shm/aeron-cluster/backup
	@echo "  ✓ Cluster state cleaned"
	@echo ""
	@echo "→ Step 5/5: Starting cluster..."
	@systemctl --user start node0
	@sleep 3
	@systemctl --user start node1 node2
	@sleep 5
	@systemctl --user start backup market order admin ui
	@sleep 3
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Installation Complete!                                        ║"
	@echo "║                                                                  ║"
	@echo "║  Trading UI:     http://localhost:3000                           ║"
	@echo "║  Order API:      http://localhost:8080/order                     ║"
	@echo "║  Market WS:      ws://localhost:8081/ws                          ║"
	@echo "║  Admin API:      http://localhost:8082/api/admin/status          ║"
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

# ==================== SERVICE MANAGEMENT ====================

install-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing User-Level Systemd Services                 ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@mkdir -p $(USER_SERVICE_DIR)
	@mkdir -p $(LOG_DIR)
	@echo "→ Installing node0.service (CPU cores 0-3)..."
	$(call JAVA_SERVICE,node0,Match Engine Cluster Node 0,\
		'Environment="CLUSTER_ADDRESSES=$(CLUSTER_ADDRS)"' 'Environment="CLUSTER_NODE=0"' 'Environment="CLUSTER_PORT_BASE=9000"' 'Environment="BASE_DIR=/dev/shm/aeron-cluster/node0"',\
		'ExecStartPre=/bin/mkdir -p /dev/shm/aeron-cluster/node0',\
		/usr/bin/taskset -c $(CPU_NODE0) /usr/bin/java $(JAVA_OPTS) -jar $(CLUSTER_JAR),10,)
	@echo "→ Installing node1.service (CPU cores 4-7)..."
	$(call JAVA_SERVICE,node1,Match Engine Cluster Node 1,\
		'Environment="CLUSTER_ADDRESSES=$(CLUSTER_ADDRS)"' 'Environment="CLUSTER_NODE=1"' 'Environment="CLUSTER_PORT_BASE=9000"' 'Environment="BASE_DIR=/dev/shm/aeron-cluster/node1"',\
		'ExecStartPre=/bin/mkdir -p /dev/shm/aeron-cluster/node1' 'ExecStartPre=/bin/sleep 2',\
		/usr/bin/taskset -c $(CPU_NODE1) /usr/bin/java $(JAVA_OPTS) -jar $(CLUSTER_JAR),10,)
	@echo "→ Installing node2.service (CPU cores 8-11)..."
	$(call JAVA_SERVICE,node2,Match Engine Cluster Node 2,\
		'Environment="CLUSTER_ADDRESSES=$(CLUSTER_ADDRS)"' 'Environment="CLUSTER_NODE=2"' 'Environment="CLUSTER_PORT_BASE=9000"' 'Environment="BASE_DIR=/dev/shm/aeron-cluster/node2"',\
		'ExecStartPre=/bin/mkdir -p /dev/shm/aeron-cluster/node2' 'ExecStartPre=/bin/sleep 2',\
		/usr/bin/taskset -c $(CPU_NODE2) /usr/bin/java $(JAVA_OPTS) -jar $(CLUSTER_JAR),10,)
	@echo "→ Installing backup.service..."
	$(call JAVA_SERVICE,backup,Match Engine Cluster Backup Node,,\
		'ExecStartPre=/bin/mkdir -p /dev/shm/aeron-cluster/backup' 'ExecStartPre=/bin/sleep 3',\
		/usr/bin/java $(JAVA_OPTS) -cp $(CLUSTER_JAR) com.match.infrastructure.persistence.ClusterBackupApp,10,)
	@echo "→ Installing market.service..."
	$(call JAVA_SERVICE,market,Match Engine Market Gateway,\
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' 'Environment="EGRESS_PORT=9091"',\
		'ExecStartPre=/bin/sleep 5',\
		/usr/bin/java $(JAVA_OPTS) -cp $(GATEWAY_JAR) com.match.infrastructure.gateway.MarketGatewayMain,5,)
	@echo "→ Installing order.service..."
	$(call JAVA_SERVICE,order,Match Engine Order Gateway,\
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' 'Environment="EGRESS_PORT=9092"',\
		'ExecStartPre=/bin/sleep 5',\
		/usr/bin/java $(JAVA_OPTS) -cp $(GATEWAY_JAR) com.match.infrastructure.gateway.OrderGatewayMain,5,)
	@echo "→ Installing admin.service (Go)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Admin Gateway (Go)' \
		'After=default.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' \
		'ExecStartPre=$(call LOG_ROTATE,admin)' \
		'ExecStart=$(PROJECT_DIR)/admin-gateway/admin-gateway' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'TimeoutStopSec=5' \
		'KillMode=mixed' \
		'StandardOutput=append:$(LOG_DIR)/admin.log' \
		'StandardError=append:$(LOG_DIR)/admin.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > $(USER_SERVICE_DIR)/admin.service
	@echo "→ Installing ui.service (port 3000)..."
	$(call UI_SERVICE)
	@echo ""
	@echo "→ Reloading user systemd..."
	@systemctl --user daemon-reload
	@echo "→ Enabling services..."
	@systemctl --user enable node0 node1 node2 backup market order admin ui
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Services installed! Use 'make install' to start cluster.     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

uninstall-services:
	@echo "→ Stopping services..."
	@systemctl --user stop ui admin order market backup node2 node1 node0 2>/dev/null || true
	@echo "→ Disabling services..."
	@systemctl --user disable node0 node1 node2 backup market order admin ui 2>/dev/null || true
	@echo "→ Removing service files..."
	@rm -f $(USER_SERVICE_DIR)/node0.service $(USER_SERVICE_DIR)/node1.service $(USER_SERVICE_DIR)/node2.service
	@rm -f $(USER_SERVICE_DIR)/backup.service $(USER_SERVICE_DIR)/market.service
	@rm -f $(USER_SERVICE_DIR)/order.service $(USER_SERVICE_DIR)/admin.service $(USER_SERVICE_DIR)/ui.service
	@systemctl --user daemon-reload
	@echo "✓ Services uninstalled"

reinstall-services: uninstall-services install-services
	@echo ""
	@echo "✓ Services reinstalled"

# ==================== BUILD ====================

build: build-ui build-java
	@echo "✓ Build complete"

build-java:
	mvn clean package -DskipTests -q

build-cluster:
	mvn package -pl match-cluster -am -DskipTests -q

build-gateway:
	mvn package -pl match-gateway -am -DskipTests -q

build-admin:
	cd admin-gateway && go build -o admin-gateway .

build-loadtest:
	mvn package -pl match-loadtest -am -DskipTests -q

build-ui:
	cd match/ui && npm install && npm run build

# ==================== CODE GENERATION ====================

sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated -Dsbe.output.dir=match-common/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match-common/src/main/resources/sbe/order-schema.xml

# ==================== SETUP (run once) ====================

setup-port-80:
	@echo "→ Granting node permission to bind to port 80..."
	@sudo setcap 'cap_net_bind_service=+ep' /usr/bin/node
	@echo "  ✓ Done. Run 'make reinstall-services' to apply."

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
	@echo "  make install            Build and start everything fresh"
	@echo "  make build              Build all (Java + UI)"
	@echo "  make build-java         Build all Java modules"
	@echo "  make build-cluster      Build cluster module only"
	@echo "  make build-gateway      Build gateway module only"
	@echo "  make build-admin        Build admin gateway (Go)"
	@echo "  make build-ui           Build UI only"
	@echo "  make sbe                Generate SBE codec classes"
	@echo ""
	@echo "Services:"
	@echo "  make install-services   Install systemd services"
	@echo "  make uninstall-services Remove systemd services"
	@echo "  make reinstall-services Reinstall services"
	@echo ""
	@echo "System:"
	@echo "  make optimize-os        OS tuning for low latency (sudo)"
	@echo "  make os-check           Show current OS settings"
	@echo "  make setup-port-80      Allow node to bind port 80 (sudo)"
	@echo ""
	@echo "Runtime: http://localhost:8082/api/admin/"
	@echo "  curl .../status             Cluster status"
	@echo "  curl -X POST .../snapshot   Take snapshot"
	@echo "  curl -X POST .../rolling-update   Deploy code"
	@echo "  curl -X POST .../restart-gateway  Restart gateways"
	@echo "  curl -X POST .../rebuild-gateway  Build gateway JAR"
