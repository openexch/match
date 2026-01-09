# ==================== MATCHING ENGINE CLUSTER ====================
# A high-performance, fault-tolerant order matching engine using Aeron Cluster
#
# IMPORTANT: Runtime cluster management is done via Admin Gateway API.
# See docs/PRINCIPLES.md for single source of truth documentation.
#
# Quick Start:
#   make install-deps    # Install system dependencies (once)
#   make install         # Build and start everything fresh
#
# Runtime Management (use Admin API at http://localhost:8082):
#   curl http://localhost:8082/api/admin/status
#   curl -X POST http://localhost:8082/api/admin/rolling-update
#   curl -X POST http://localhost:8082/api/admin/restart-gateway
#
# ==================================================================

.PHONY: install install-deps optimize-os status logs help services leader install-services uninstall-services reinstall-services build build-java build-ui sbe migrate-services setup-sudoers

# ==================== CONFIGURATION ====================
# Absolute project directory (captured at make time)
PROJECT_DIR := $(shell pwd)
# Current user for running services
SERVICE_USER := $(shell whoami)

# ==================== INSTALLATION ====================

# Install all system dependencies
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
	@echo "  ✓ System utilities installed"
	@echo ""
	@echo "→ Checking CPU pinning support..."
	@if command -v taskset >/dev/null 2>&1; then \
		echo "  ✓ taskset available for CPU pinning"; \
	else \
		echo "  ⚠ taskset not available - CPU pinning disabled"; \
	fi
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ All dependencies installed successfully!                      ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# Full installation - build everything and start fresh
install:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing Matching Engine Cluster                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@# Stop any existing services first
	@sudo systemctl stop ui admin order market backup node2 node1 node0 2>/dev/null || true
	@echo "→ Step 1/5: Building UI..."
	@cd match/ui && npm install --silent && npm run build --silent
	@echo "  ✓ UI built"
	@echo ""
	@echo "→ Step 2/5: Building Java components..."
	@cd match && mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 3/5: Installing systemd services..."
	@$(MAKE) -s install-services
	@echo ""
	@echo "→ Step 4/5: Cleaning cluster state..."
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@rm -rf /tmp/aeron-cluster/node0/* /tmp/aeron-cluster/node1/* /tmp/aeron-cluster/node2/* 2>/dev/null || true
	@rm -rf /tmp/aeron-cluster/backup/* 2>/dev/null || true
	@mkdir -p /tmp/aeron-cluster/node0 /tmp/aeron-cluster/node1 /tmp/aeron-cluster/node2 /tmp/aeron-cluster/backup
	@echo "  ✓ Cluster state cleaned"
	@echo ""
	@echo "→ Step 5/5: Starting cluster..."
	@sudo systemctl start node0
	@sleep 3
	@sudo systemctl start node1 node2
	@sleep 5
	@sudo systemctl start backup market order admin ui
	@sleep 3
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Installation Complete!                                        ║"
	@echo "╠══════════════════════════════════════════════════════════════════╣"
	@echo "║                                                                  ║"
	@echo "║  Trading UI:     http://localhost                                ║"
	@echo "║  Admin UI:       http://localhost:8082/ui/                       ║"
	@echo "║  Order API:      http://localhost:8080/order                     ║"
	@echo "║  Market WS:      ws://localhost:8081/ws                          ║"
	@echo "║                                                                  ║"
	@echo "║  Runtime Management (Admin API):                                 ║"
	@echo "║    curl http://localhost:8082/api/admin/status                   ║"
	@echo "║    curl -X POST http://localhost:8082/api/admin/rolling-update   ║"
	@echo "║                                                                  ║"
	@echo "║  See docs/PRINCIPLES.md for full API documentation               ║"
	@echo "║                                                                  ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# Comprehensive OS optimization for ultra-low latency
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
	@echo "  ✓ CPU governor set to performance"
	@echo ""
	@echo "→ Disabling CPU frequency scaling..."
	@sudo sh -c 'echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo' 2>/dev/null || true
	@echo "  ✓ Turbo boost configured"
	@echo ""
	@echo "→ Network buffer optimization..."
	@sudo sysctl -w net.core.rmem_max=16777216 >/dev/null
	@sudo sysctl -w net.core.wmem_max=16777216 >/dev/null
	@sudo sysctl -w net.core.rmem_default=1048576 >/dev/null
	@sudo sysctl -w net.core.wmem_default=1048576 >/dev/null
	@sudo sysctl -w net.core.netdev_max_backlog=30000 >/dev/null
	@sudo sysctl -w net.core.somaxconn=4096 >/dev/null
	@echo "  ✓ Network buffers optimized (16MB max)"
	@echo ""
	@echo "→ TCP optimization for low latency..."
	@sudo sysctl -w net.ipv4.tcp_rmem="4096 1048576 16777216" >/dev/null
	@sudo sysctl -w net.ipv4.tcp_wmem="4096 1048576 16777216" >/dev/null
	@sudo sysctl -w net.ipv4.tcp_low_latency=1 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_fastopen=3 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_nodelay=1 2>/dev/null || true
	@sudo sysctl -w net.ipv4.tcp_timestamps=0 >/dev/null
	@sudo sysctl -w net.ipv4.tcp_sack=0 >/dev/null
	@echo "  ✓ TCP optimized (low latency mode)"
	@echo ""
	@echo "→ Memory management optimization..."
	@sudo sysctl -w vm.swappiness=0 >/dev/null
	@sudo sysctl -w vm.dirty_ratio=10 >/dev/null
	@sudo sysctl -w vm.dirty_background_ratio=5 >/dev/null
	@sudo sysctl -w vm.zone_reclaim_mode=0 >/dev/null
	@sudo sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/enabled' 2>/dev/null || true
	@sudo sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/defrag' 2>/dev/null || true
	@echo "  ✓ Memory optimized (swappiness=0, THP disabled)"
	@echo ""
	@echo "→ Kernel scheduling optimization..."
	@sudo sysctl -w kernel.sched_min_granularity_ns=10000000 >/dev/null 2>&1 || true
	@sudo sysctl -w kernel.sched_wakeup_granularity_ns=15000000 >/dev/null 2>&1 || true
	@sudo sysctl -w kernel.sched_migration_cost_ns=5000000 >/dev/null 2>&1 || true
	@echo "  ✓ Kernel scheduler tuned"
	@echo ""
	@echo "→ File descriptor limits..."
	@sudo sysctl -w fs.file-max=2097152 >/dev/null
	@sudo sh -c 'echo "* soft nofile 1048576" >> /etc/security/limits.conf' 2>/dev/null || true
	@sudo sh -c 'echo "* hard nofile 1048576" >> /etc/security/limits.conf' 2>/dev/null || true
	@echo "  ✓ File descriptor limits increased"
	@echo ""
	@echo "→ Huge pages configuration..."
	@sudo sysctl -w vm.nr_hugepages=1024 >/dev/null 2>&1 || true
	@echo "  ✓ Huge pages configured (1024 x 2MB)"
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ OS Optimization Complete!                                     ║"
	@echo "║                                                                  ║"
	@echo "║  Note: Some settings require a reboot to take full effect.       ║"
	@echo "║  Run 'make os-check' to verify current settings.                 ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# ==================== STATUS & MONITORING (READ-ONLY) ====================

# Check overall system status
status:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║                    Cluster Status                                ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "=== Cluster Nodes ==="
	@cluster_nodes=$$(pgrep -f "cluster-engine-1.0.jar" 2>/dev/null | xargs -I{} sh -c 'ps -p {} -o args= 2>/dev/null | grep -v "ClusterBackupApp\|HttpController"' | wc -l); \
	if [ "$$cluster_nodes" -ge 3 ]; then \
		echo "  ✓ $$cluster_nodes/3 cluster nodes running"; \
	elif [ "$$cluster_nodes" -gt 0 ]; then \
		echo "  ⚠ Only $$cluster_nodes/3 cluster nodes running"; \
	else \
		echo "  ✗ No cluster nodes running"; \
	fi
	@echo ""
	@echo "=== Backup Node ==="
	@if pgrep -f "ClusterBackupApp" >/dev/null 2>&1; then \
		echo "  ✓ Backup node running"; \
		log_pos=$$(grep -o "logPosition=[0-9]*" /var/log/cluster/backup.log 2>/dev/null | tail -1 | cut -d= -f2); \
		if [ -n "$$log_pos" ]; then \
			echo "  → Replicated: $$(echo "scale=2; $$log_pos/1048576" | bc) MB"; \
		fi; \
	else \
		echo "  ✗ Backup node not running"; \
	fi
	@echo ""
	@echo "=== Gateways ==="
	@if pgrep -f "MarketGatewayMain" >/dev/null 2>&1; then \
		echo "  ✓ Market Gateway on ws://localhost:8081"; \
	else \
		echo "  ✗ Market Gateway not running"; \
	fi
	@if pgrep -f "OrderGatewayMain" >/dev/null 2>&1; then \
		echo "  ✓ Order Gateway on http://localhost:8080"; \
	else \
		echo "  ✗ Order Gateway not running"; \
	fi
	@if pgrep -f "AdminGatewayMain" >/dev/null 2>&1; then \
		echo "  ✓ Admin API on http://localhost:8082"; \
	else \
		echo "  ✗ Admin API not running"; \
	fi
	@echo ""
	@echo "=== UI ==="
	@if pgrep -f "vite preview" >/dev/null 2>&1; then \
		echo "  ✓ Trading UI on http://localhost"; \
	else \
		echo "  ✗ Trading UI not running"; \
	fi
	@echo ""
	@echo "=== Archive Storage ==="
	@total=$$(du -sh /tmp/aeron-cluster/node*/archive/ 2>/dev/null | awk '{sum+=$$1} END {print sum}'); \
	echo "  → Total archive size: $$(du -sh /tmp/aeron-cluster/ 2>/dev/null | cut -f1)"
	@echo ""
	@echo "For management, use Admin API: http://localhost:8082/api/admin/"

# View logs
logs:
	@tail -f /var/log/cluster/node0.log /var/log/cluster/backup.log 2>/dev/null

# Help
help:
	@echo "Matching Engine Cluster - Available Commands"
	@echo ""
	@echo "Installation & Setup:"
	@echo "  make install-deps       Install system dependencies (Java, Node.js, Maven)"
	@echo "  make install            Build and start everything fresh"
	@echo "  make optimize-os        Apply OS optimizations for low latency (sudo)"
	@echo "  make build              Build Java and UI components"
	@echo "  make build-java         Build Java components only"
	@echo "  make build-ui           Build UI only"
	@echo ""
	@echo "Systemd Service Management:"
	@echo "  make install-services   Install and enable systemd services"
	@echo "  make uninstall-services Uninstall systemd services"
	@echo "  make reinstall-services Reinstall services (after config changes)"
	@echo "  make setup-port-80      Allow node to bind to port 80 (sudo)"
	@echo ""
	@echo "Status & Monitoring (read-only):"
	@echo "  make status             Check cluster health and component status"
	@echo "  make services           Show systemd service status"
	@echo "  make logs               Tail cluster logs"
	@echo "  make leader             Show current cluster leader"
	@echo "  make os-check           Show current OS settings"
	@echo ""
	@echo "Code Generation:"
	@echo "  make sbe                Generate SBE codec classes"
	@echo ""
	@echo "═══════════════════════════════════════════════════════════════════"
	@echo "RUNTIME MANAGEMENT - Use Admin Gateway API (http://localhost:8082)"
	@echo "═══════════════════════════════════════════════════════════════════"
	@echo ""
	@echo "  Status:         curl http://localhost:8082/api/admin/status"
	@echo "  Rolling Update: curl -X POST http://localhost:8082/api/admin/rolling-update"
	@echo "  Restart Node:   curl -X POST http://localhost:8082/api/admin/restart-node -d '{\"nodeId\":0}'"
	@echo "  Restart Gateways: curl -X POST http://localhost:8082/api/admin/restart-gateway"
	@echo "  Snapshot:       curl -X POST http://localhost:8082/api/admin/snapshot"
	@echo ""
	@echo "See docs/PRINCIPLES.md for full API documentation."

# Show systemd service status
services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║                    Systemd Services Status                       ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@sudo systemctl status node0 node1 node2 backup market order admin ui --no-pager 2>/dev/null | grep -E "●|Active:|Main PID:" || echo "Services not installed"

# ==================== SERVICE MANAGEMENT ====================

# Setup log directory (requires sudo, run once)
setup-logs:
	@echo "→ Creating /var/log/cluster directory..."
	sudo mkdir -p /var/log/cluster
	sudo chown $(USER):$(USER) /var/log/cluster
	@echo "✓ Log directory created: /var/log/cluster"

# Install system-wide systemd services (requires sudo)
install-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing System-Wide Systemd Services                ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Installing node0.service (CPU cores 0-3)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 0' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=0"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node0"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/node0.log && mv /var/log/cluster/node0.log /var/log/cluster/node0.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node0' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE0) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/node0.log' \
		'StandardError=append:/var/log/cluster/node0.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/node0.service > /dev/null
	@echo "→ Installing node1.service (CPU cores 4-7)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 1' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=1"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node1"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/node1.log && mv /var/log/cluster/node1.log /var/log/cluster/node1.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node1' \
		'ExecStartPre=/bin/sleep 2' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE1) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/node1.log' \
		'StandardError=append:/var/log/cluster/node1.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/node1.service > /dev/null
	@echo "→ Installing node2.service (CPU cores 8-11)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 2' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=2"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node2"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/node2.log && mv /var/log/cluster/node2.log /var/log/cluster/node2.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node2' \
		'ExecStartPre=/bin/sleep 2' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE2) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/node2.log' \
		'StandardError=append:/var/log/cluster/node2.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/node2.service > /dev/null
	@echo "→ Installing backup.service..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Backup Node' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/backup.log && mv /var/log/cluster/backup.log /var/log/cluster/backup.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/backup' \
		'ExecStartPre=/bin/sleep 3' \
		'ExecStart=/usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.persistence.ClusterBackupApp' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/backup.log' \
		'StandardError=append:/var/log/cluster/backup.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/backup.service > /dev/null
	@echo "→ Installing market.service..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Market Gateway' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' \
		'Environment="EGRESS_PORT=9091"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/market.log && mv /var/log/cluster/market.log /var/log/cluster/market.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/sleep 5' \
		'ExecStart=/usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.gateway.MarketGatewayMain' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/market.log' \
		'StandardError=append:/var/log/cluster/market.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/market.service > /dev/null
	@echo "→ Installing order.service..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Order Gateway' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' \
		'Environment="EGRESS_PORT=9092"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/order.log && mv /var/log/cluster/order.log /var/log/cluster/order.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStartPre=/bin/sleep 5' \
		'ExecStart=/usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.gateway.OrderGatewayMain' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/var/log/cluster/order.log' \
		'StandardError=append:/var/log/cluster/order.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/order.service > /dev/null
	@echo "→ Installing admin.service..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Admin Gateway' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)' \
		'Environment="MATCH_PROJECT_DIR=$(PROJECT_DIR)"' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/admin.log && mv /var/log/cluster/admin.log /var/log/cluster/admin.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStart=/usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.gateway.AdminGatewayMain' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'LimitNOFILE=1048576' \
		'StandardOutput=append:/var/log/cluster/admin.log' \
		'StandardError=append:/var/log/cluster/admin.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/admin.service > /dev/null
	@echo "→ Installing ui.service (port 80)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Trading UI' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'User=$(SERVICE_USER)' \
		'Group=$(SERVICE_USER)' \
		'WorkingDirectory=$(PROJECT_DIR)/match/ui' \
		'ExecStartPre=/bin/bash -c '"'"'test -f /var/log/cluster/ui.log && mv /var/log/cluster/ui.log /var/log/cluster/ui.log.$$(date +%%Y%%m%%d-%%H%%M%%S) || true'"'"'' \
		'ExecStart=/usr/bin/npx vite preview --port 80 --host' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'StandardOutput=append:/var/log/cluster/ui.log' \
		'StandardError=append:/var/log/cluster/ui.log' \
		'' \
		'[Install]' \
		'WantedBy=multi-user.target' | sudo tee /etc/systemd/system/ui.service > /dev/null
	@echo ""
	@echo "→ Reloading systemd..."
	@sudo systemctl daemon-reload
	@echo "→ Enabling services..."
	@sudo systemctl enable node0 node1 node2 backup market order admin ui
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ System services installed and enabled!                        ║"
	@echo "║                                                                  ║"
	@echo "║  Service Management:                                             ║"
	@echo "║    sudo systemctl start|stop|restart node0                       ║"
	@echo "║    sudo service node0 start|stop|restart                         ║"
	@echo "║                                                                  ║"
	@echo "║  Services: node0 node1 node2 backup market order admin ui        ║"
	@echo "║                                                                  ║"
	@echo "║  CPU Core Allocation:                                            ║"
	@echo "║    Node 0:  cores 0-3    Node 1:  cores 4-7                      ║"
	@echo "║    Node 2:  cores 8-11                                           ║"
	@echo "║    Gateways & Backup: no pinning (SHARED threading mode)         ║"
	@echo "║                                                                  ║"
	@echo "║  Next: Run 'make install' to build and start the cluster         ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# Uninstall system-wide systemd services (requires sudo)
uninstall-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Uninstalling Systemd Services                          ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Stopping services..."
	@sudo systemctl stop ui admin order market backup node2 node1 node0 2>/dev/null || true
	@echo "→ Disabling services..."
	@sudo systemctl disable node0 node1 node2 backup market order admin ui 2>/dev/null || true
	@echo "→ Removing service files..."
	@sudo rm -f /etc/systemd/system/node0.service
	@sudo rm -f /etc/systemd/system/node1.service
	@sudo rm -f /etc/systemd/system/node2.service
	@sudo rm -f /etc/systemd/system/backup.service
	@sudo rm -f /etc/systemd/system/market.service
	@sudo rm -f /etc/systemd/system/order.service
	@sudo rm -f /etc/systemd/system/admin.service
	@sudo rm -f /etc/systemd/system/ui.service
	@echo "→ Reloading systemd..."
	@sudo systemctl daemon-reload
	@echo ""
	@echo "✓ Services uninstalled"

# Reinstall systemd services (uninstall + install)
reinstall-services: uninstall-services install-services
	@echo ""
	@echo "✓ Services reinstalled"

# Migrate from old user services to new system services
migrate-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Migrating from User to System Services                 ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Stopping old user services..."
	@systemctl --user stop match-ui match-admin-gateway match-order-gateway match-market-gateway match-backup match-node2 match-node1 match-node0 2>/dev/null || true
	@echo "→ Disabling old user services..."
	@systemctl --user disable match-node0 match-node1 match-node2 match-backup match-market-gateway match-order-gateway match-admin-gateway match-ui 2>/dev/null || true
	@echo "→ Removing old service files..."
	@rm -f ~/.config/systemd/user/match-node0.service
	@rm -f ~/.config/systemd/user/match-node1.service
	@rm -f ~/.config/systemd/user/match-node2.service
	@rm -f ~/.config/systemd/user/match-backup.service
	@rm -f ~/.config/systemd/user/match-market-gateway.service
	@rm -f ~/.config/systemd/user/match-order-gateway.service
	@rm -f ~/.config/systemd/user/match-admin-gateway.service
	@rm -f ~/.config/systemd/user/match-ui.service
	@rm -f ~/.config/systemd/user/match-gateway.service
	@rm -f ~/.config/user-tmpfiles.d/aeron-cluster.conf 2>/dev/null || true
	@echo "→ Reloading user systemd..."
	@systemctl --user daemon-reload
	@echo ""
	@echo "✓ Old user services removed"
	@echo "→ Run 'make install-services' to install new system services"

# Setup sudoers for passwordless service management (requires sudo, run once)
setup-sudoers:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Setting up Sudoers for Service Management              ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Creating /etc/sudoers.d/match-services..."
	@printf '%s\n' \
		'# Allow $(SERVICE_USER) to manage match engine services without password' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start node0' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop node0' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart node0' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active node0' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl show *' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start node1' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop node1' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart node1' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active node1' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start node2' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop node2' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart node2' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active node2' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start backup' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop backup' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart backup' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active backup' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start market' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop market' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart market' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active market' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start order' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop order' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart order' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active order' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start admin' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop admin' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart admin' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active admin' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl start ui' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl stop ui' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart ui' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl is-active ui' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/systemctl daemon-reload' \
		'$(SERVICE_USER) ALL=(ALL) NOPASSWD: /usr/bin/journalctl *' | sudo tee /etc/sudoers.d/match-services > /dev/null
	@sudo chmod 440 /etc/sudoers.d/match-services
	@echo ""
	@echo "✓ Sudoers configured - admin gateway can now manage services"

# ==================== CLUSTER STATUS (READ-ONLY) ====================

JAR_PATH = match/target/cluster-engine-1.0.jar

# Show current cluster leader
leader:
	@echo "=== Cluster Leadership Status ==="
	@found=0; \
	for check_node in 0 1 2; do \
		result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
			-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
			/tmp/aeron-cluster/node$$check_node/cluster list-members 2>&1); \
		if echo "$$result" | grep -q "leaderMemberId="; then \
			leader_id=$$(echo "$$result" | grep -o "leaderMemberId=[0-9]*" | cut -d= -f2); \
			echo "→ Node $$leader_id is LEADER"; \
			for node in 0 1 2; do \
				if [ "$$node" != "$$leader_id" ]; then \
					echo "  Node $$node is FOLLOWER"; \
				fi; \
			done; \
			found=1; \
			break; \
		fi; \
	done; \
	if [ "$$found" = "0" ]; then \
		echo "⚠ Could not determine leader - cluster may be starting"; \
	fi

# ==================== BUILD ====================

# Build the project (Java + UI)
build: build-ui build-java
	@echo "✓ Build complete"

# Build only Java
build-java:
	cd match && mvn clean package -DskipTests -q

# Build the React UI
build-ui:
	cd match/ui && npm install && npm run build

# ==================== SBE CODE GENERATION ====================

sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated.sbe -Dsbe.output.dir=match/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match/src/main/resources/sbe/order-schema.xml

# ==================== CONFIGURATION ====================

# JVM flags for ultra-low latency (server)
JAVA_OPTS = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx2g -Xms2g

# CPU cores for pinning (24 cores available on i7-13700K)
CPU_NODE0 = 0-3
CPU_NODE1 = 4-7
CPU_NODE2 = 8-11

# ==================== PORT 80 SETUP ====================
# Grant node permission to bind to privileged ports (run once after node install)
setup-port-80:
	@echo "→ Granting node permission to bind to port 80 (requires sudo)..."
	@sudo setcap 'cap_net_bind_service=+ep' /usr/bin/node
	@echo "  ✓ Node can now bind to port 80"
	@echo "  Run 'make reinstall-services' then 'make install' to apply"

# ==================== OS STATUS ====================

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
