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

.PHONY: install install-deps optimize-os help install-services uninstall-services reinstall-services build build-java build-cluster build-gateway build-admin build-loadtest sbe os-check rebuild-admin processes processes-summary

# ==================== CONFIGURATION ====================
PROJECT_DIR := $(shell pwd)

# ==================== USER SERVICE CONFIGURATION ====================
LOG_DIR := $(HOME)/.local/log/cluster
USER_SERVICE_DIR := $(HOME)/.config/systemd/user

LOG_ROTATE = /bin/bash -c '"'"'test -f $(LOG_DIR)/$(1).log && mv $(LOG_DIR)/$(1).log $(LOG_DIR)/$(1).log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'

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
	@systemctl --user stop admin order market backup node2 node1 node0 2>/dev/null || true
	@echo "→ Step 1/4: Building Java components..."
	@mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 2/4: Building admin gateway (Go)..."
	@cd admin-gateway && go build -o admin-gateway .
	@echo "  ✓ Admin gateway built"
	@echo ""
	@echo "→ Step 3/4: Installing admin service + cleaning cluster state..."
	@$(MAKE) -s install-services
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/node0/* /dev/shm/aeron-cluster/node1/* /dev/shm/aeron-cluster/node2/* 2>/dev/null || true
	@rm -rf /dev/shm/aeron-cluster/backup/* 2>/dev/null || true
	@mkdir -p /dev/shm/aeron-cluster/node0 /dev/shm/aeron-cluster/node1 /dev/shm/aeron-cluster/node2 /dev/shm/aeron-cluster/backup
	@echo "  ✓ Cluster state cleaned"
	@echo ""
	@echo "→ Step 4/4: Starting cluster via Admin Process Manager..."
	@systemctl --user start admin
	@sleep 2
	@curl -sf -X POST http://localhost:8082/api/admin/processes/start-all > /dev/null
	@echo "  Process manager starting all services in dependency order..."
	@sleep 15
	@sleep 3
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Installation Complete!                                        ║"
	@echo "║                                                                  ║"
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
	@echo "║     Installing Admin Gateway Service (Process Manager)           ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "  Admin gateway is the process manager for all services."
	@echo "  Only admin.service uses systemd. All other processes are"
	@echo "  managed directly by the admin gateway process manager."
	@echo ""
	@mkdir -p $(USER_SERVICE_DIR)
	@mkdir -p $(LOG_DIR)
	@mkdir -p $(HOME)/.local/run/match
	@echo "→ Removing old per-service systemd units (if any)..."
	@systemctl --user stop node0 node1 node2 backup order market 2>/dev/null || true
	@systemctl --user disable node0 node1 node2 backup order market 2>/dev/null || true
	@rm -f $(USER_SERVICE_DIR)/node0.service $(USER_SERVICE_DIR)/node1.service $(USER_SERVICE_DIR)/node2.service
	@rm -f $(USER_SERVICE_DIR)/backup.service $(USER_SERVICE_DIR)/market.service
	@rm -f $(USER_SERVICE_DIR)/order.service
	@echo "→ Installing admin.service (Go process manager)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Admin Gateway + Process Manager' \
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
		'KillMode=process' \
		'StandardOutput=append:$(LOG_DIR)/admin.log' \
		'StandardError=append:$(LOG_DIR)/admin.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > $(USER_SERVICE_DIR)/admin.service
	@echo ""
	@echo "→ Reloading user systemd..."
	@systemctl --user daemon-reload
	@echo "→ Enabling admin service..."
	@systemctl --user enable admin
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Admin gateway installed!                                      ║"
	@echo "║    All services managed via: http://localhost:8082/api/admin/    ║"
	@echo "║    Process control:  make processes                              ║"
	@echo "║    Start everything: curl -X POST .../processes/start-all       ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

uninstall-services:
	@echo "→ Stopping all processes via admin..."
	@curl -sf -X POST http://localhost:8082/api/admin/processes/stop-all 2>/dev/null || true
	@sleep 5
	@echo "→ Stopping admin gateway..."
	@systemctl --user stop admin 2>/dev/null || true
	@systemctl --user disable admin 2>/dev/null || true
	@rm -f $(USER_SERVICE_DIR)/admin.service
	@echo "→ Cleaning up old service files..."
	@rm -f $(USER_SERVICE_DIR)/node0.service $(USER_SERVICE_DIR)/node1.service $(USER_SERVICE_DIR)/node2.service
	@rm -f $(USER_SERVICE_DIR)/backup.service $(USER_SERVICE_DIR)/market.service
	@rm -f $(USER_SERVICE_DIR)/order.service
	@systemctl --user daemon-reload
	@echo "→ Cleaning PID files..."
	@rm -f $(HOME)/.local/run/match/*.pid
	@echo "✓ All services uninstalled"

reinstall-services: uninstall-services install-services
	@echo ""
	@echo "✓ Services reinstalled"

# ==================== BUILD ====================

build: build-java build-admin
	@echo "✓ Build complete"

build-java:
	mvn clean package -DskipTests -q

build-cluster:
	mvn package -pl match-cluster -am -DskipTests -q

build-gateway:
	mvn package -pl match-gateway -am -DskipTests -q

build-admin:
	cd admin-gateway && go build -o admin-gateway .

rebuild-admin:
	@echo "→ Triggering admin gateway self-update..."
	@curl -sf -X POST http://localhost:8082/api/admin/rebuild-admin | python3 -m json.tool 2>/dev/null || echo '{"error": "admin gateway not running"}'
	@echo "Admin gateway will rebuild and restart automatically"

processes:
	@curl -sf http://localhost:8082/api/admin/processes 2>/dev/null | python3 -c "import sys,json;data=json.load(sys.stdin);[print(f\"  {'●' if p['running'] else '○'} {p['name']:10s} {p['status']:10s} PID {str(p.get('pid') or '-'):>8s}  {p.get('memoryBytes',0)//1048576:>5d} MB  {p.get('cpuPercent',0):>6.1f}%%\") for p in data]" 2>/dev/null || echo "  Admin gateway not running"

processes-summary:
	@curl -sf http://localhost:8082/api/admin/processes/summary 2>/dev/null | python3 -m json.tool 2>/dev/null || echo '{"error": "admin gateway not running"}'

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
	@echo "  make install            Build and start everything fresh"
	@echo "  make build              Build all (Java + Admin)"
	@echo "  make build-java         Build all Java modules"
	@echo "  make build-cluster      Build cluster module only"
	@echo "  make build-gateway      Build gateway module only"
	@echo "  make build-admin        Build admin gateway (Go)"
	@echo "  make sbe                Generate SBE codec classes"
	@echo ""
	@echo "Process Manager:"
	@echo "  make processes          Show live status of all processes"
	@echo "  make processes-summary  Process summary (running/stopped/memory)"
	@echo "  make rebuild-admin      Self-update admin gateway via API"
	@echo "  make install-services   Install admin service (process manager)"
	@echo "  make uninstall-services Remove all services"
	@echo "  make reinstall-services Reinstall services"
	@echo ""
	@echo "System:"
	@echo "  make optimize-os        OS tuning for low latency (sudo)"
	@echo "  make os-check           Show current OS settings"
	@echo ""
	@echo "Runtime: http://localhost:8082/api/admin/"
	@echo "  GET  .../processes                  Live process status"
	@echo "  POST .../processes/{name}/start     Start a service"
	@echo "  POST .../processes/{name}/stop      Stop a service"
	@echo "  POST .../processes/start-all        Start all (dependency order)"
	@echo "  POST .../processes/stop-all         Stop all (reverse order)"
	@echo "  POST .../rolling-update             Deploy code (zero-downtime)"
	@echo "  POST .../snapshot                   Take cluster snapshot"
	@echo "  POST .../rebuild-admin              Self-update admin gateway"
