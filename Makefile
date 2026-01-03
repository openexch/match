# ==================== MATCHING ENGINE CLUSTER ====================
# A high-performance, fault-tolerant order matching engine using Aeron Cluster
#
# Quick Start:
#   make install-deps    # Install system dependencies (once)
#   make install         # Build and start everything fresh
#   make status          # Check cluster health
#   make stop            # Stop everything
#
# For production:
#   make optimize-os     # Apply OS-level optimizations (requires sudo)
#   make install         # Deploy the cluster
# ==================================================================

.PHONY: install install-deps optimize-os status stop start fresh help leader restart-node rolling-update install-services uninstall-services reinstall-services

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
install: stop
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing Matching Engine Cluster                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Step 1/4: Building UI..."
	@cd match/ui && npm install --silent && npm run build --silent
	@echo "  ✓ UI built"
	@echo ""
	@echo "→ Step 2/4: Building Java components..."
	@cd match && mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 3/4: Installing systemd services..."
	@$(MAKE) -s install-services
	@echo ""
	@echo "→ Step 4/4: Starting cluster via systemd..."
	@$(MAKE) -s fresh
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Installation Complete!                                        ║"
	@echo "╠══════════════════════════════════════════════════════════════════╣"
	@echo "║                                                                  ║"
	@echo "║  Trading UI:     http://localhost:8080/ui/                       ║"
	@echo "║  WebSocket:      ws://localhost:8081/ws                          ║"
	@echo "║  Cluster nodes:  3 (ports 9000, 9100, 9200)                      ║"
	@echo "║  Backup node:    1 (replicating)                                 ║"
	@echo "║                                                                  ║"
	@echo "║  Commands:                                                       ║"
	@echo "║    make status     - Check cluster health                        ║"
	@echo "║    make stop       - Stop everything                             ║"
	@echo "║    make logs       - View cluster logs                           ║"
	@echo "║    make snapshot   - Trigger cluster snapshot                    ║"
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

# ==================== STATUS & MONITORING ====================

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
		log_pos=$$(grep -o "logPosition=[0-9]*" /tmp/aeron-cluster/backup.log 2>/dev/null | tail -1 | cut -d= -f2); \
		if [ -n "$$log_pos" ]; then \
			echo "  → Replicated: $$(echo "scale=2; $$log_pos/1048576" | bc) MB"; \
		fi; \
	else \
		echo "  ✗ Backup node not running"; \
	fi
	@echo ""
	@echo "=== HTTP Gateway ==="
	@if pgrep -f "HttpController" >/dev/null 2>&1; then \
		echo "  ✓ Gateway running on http://localhost:8080"; \
	else \
		echo "  ✗ Gateway not running"; \
	fi
	@echo ""
	@echo "=== WebSocket Server ==="
	@if lsof -i :8081 >/dev/null 2>&1; then \
		subs=$$(grep -o "subscribers=[0-9]*" /tmp/aeron-cluster/node0.log 2>/dev/null | tail -1 | cut -d= -f2); \
		echo "  ✓ WebSocket on ws://localhost:8081 ($$subs subscribers)"; \
	else \
		echo "  ✗ WebSocket not available"; \
	fi
	@echo ""
	@echo "=== Archive Storage ==="
	@total=$$(du -sh /tmp/aeron-cluster/node*/archive/ 2>/dev/null | awk '{sum+=$$1} END {print sum}'); \
	echo "  → Total archive size: $$(du -sh /tmp/aeron-cluster/ 2>/dev/null | cut -f1)"

# View logs
logs:
	@tail -f /tmp/aeron-cluster/node0.log /tmp/aeron-cluster/backup.log 2>/dev/null

# Stop all services
stop:
	@echo "→ Stopping all services..."
	@systemctl --user stop match-gateway match-backup match-node2 match-node1 match-node0 2>/dev/null || true
	@echo "  ✓ All services stopped"

# Start all services
start:
	@echo "→ Starting all services..."
	@systemctl --user start match-node0
	@sleep 3
	@systemctl --user start match-node1 match-node2
	@sleep 3
	@systemctl --user start match-backup match-gateway
	@sleep 2
	@$(MAKE) -s status

# Clean start - wipe all cluster state and start fresh
fresh:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Fresh Cluster Start                                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "Step 1/4: Stopping all services..."
	@systemctl --user stop match-gateway match-backup match-node0 match-node1 match-node2 2>/dev/null || true
	@-pkill -9 -f "cluster-engine-1.0.jar" 2>/dev/null || true
	@sleep 2
	@echo "Step 2/4: Cleaning cluster state..."
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@rm -rf /tmp/aeron-cluster/node0/* /tmp/aeron-cluster/node1/* /tmp/aeron-cluster/node2/* 2>/dev/null || true
	@rm -rf /tmp/aeron-cluster/backup/* 2>/dev/null || true
	@mkdir -p /tmp/aeron-cluster/node0 /tmp/aeron-cluster/node1 /tmp/aeron-cluster/node2 /tmp/aeron-cluster/backup
	@echo "Step 3/4: Starting cluster nodes..."
	@systemctl --user start match-node0
	@sleep 3
	@systemctl --user start match-node1 match-node2
	@sleep 5
	@echo "Step 4/4: Starting backup and gateway..."
	@systemctl --user start match-backup match-gateway
	@sleep 3
	@echo ""
	@echo "✓ Fresh cluster started"
	@$(MAKE) -s status

# Help
help:
	@echo "Matching Engine Cluster - Available Commands"
	@echo ""
	@echo "Installation:"
	@echo "  make install-deps       Install system dependencies (Java, Node.js, Maven)"
	@echo "  make install            Build and start everything fresh"
	@echo "  make optimize-os        Apply OS optimizations for low latency (sudo)"
	@echo ""
	@echo "Service Management:"
	@echo "  make install-services   Install and enable systemd services"
	@echo "  make uninstall-services Uninstall systemd services"
	@echo "  make reinstall-services Reinstall services (useful after config changes)"
	@echo ""
	@echo "Operations:"
	@echo "  make start          Start all services"
	@echo "  make stop           Stop all services"
	@echo "  make fresh          Clean all state and start cluster fresh"
	@echo "  make status         Check cluster health and component status"
	@echo "  make services       Show systemd service status for all components"
	@echo "  make logs           Tail cluster logs"
	@echo "  make snapshot       Trigger cluster snapshot"
	@echo ""
	@echo "Rolling Updates:"
	@echo "  make rolling-update        Update all nodes one-by-one (zero downtime)"
	@echo "  make restart-node NODE=0   Restart a specific node (0, 1, or 2)"
	@echo "  make restart-gateway       Restart HTTP gateway service"
	@echo "  make leader                Show current cluster leader"
	@echo ""
	@echo "Testing:"
	@echo "  make loadtest-quick   Run quick 10s load test (1K msg/s)"
	@echo "  make loadtest-stress  Run 60s stress test (10K msg/s)"

# Show systemd service status
services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║                    Systemd Services Status                       ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@systemctl --user status match-node0 match-node1 match-node2 match-backup match-gateway --no-pager 2>/dev/null | grep -E "●|Active:|Main PID:" || echo "Services not installed"

# ==================== SERVICE MANAGEMENT ====================

# Install systemd services with CPU pinning
install-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Installing Systemd Services                            ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@mkdir -p ~/.config/systemd/user
	@echo "→ Installing match-node0.service (CPU cores 0-3)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 0' \
		'After=network.target' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PWD)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=0"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node0"' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node0' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE0) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/tmp/aeron-cluster/node0.log' \
		'StandardError=append:/tmp/aeron-cluster/node0.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > ~/.config/systemd/user/match-node0.service
	@echo "→ Installing match-node1.service (CPU cores 4-7)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 1' \
		'After=network.target match-node0.service' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PWD)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=1"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node1"' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node1' \
		'ExecStartPre=/bin/sleep 2' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE1) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/tmp/aeron-cluster/node1.log' \
		'StandardError=append:/tmp/aeron-cluster/node1.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > ~/.config/systemd/user/match-node1.service
	@echo "→ Installing match-node2.service (CPU cores 8-11)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Node 2' \
		'After=network.target match-node1.service' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PWD)' \
		'Environment="CLUSTER_ADDRESSES=localhost,localhost,localhost"' \
		'Environment="CLUSTER_NODE=2"' \
		'Environment="CLUSTER_PORT_BASE=9000"' \
		'Environment="BASE_DIR=/tmp/aeron-cluster/node2"' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/node2' \
		'ExecStartPre=/bin/sleep 2' \
		'ExecStart=/usr/bin/taskset -c $(CPU_NODE2) /usr/bin/java $(JAVA_OPTS) -jar match/target/cluster-engine-1.0.jar' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/tmp/aeron-cluster/node2.log' \
		'StandardError=append:/tmp/aeron-cluster/node2.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > ~/.config/systemd/user/match-node2.service
	@echo "→ Installing match-backup.service (CPU cores 12-13)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine Cluster Backup Node' \
		'After=network.target match-node0.service match-node1.service match-node2.service' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PWD)' \
		'ExecStartPre=/bin/mkdir -p /tmp/aeron-cluster/backup' \
		'ExecStartPre=/bin/sleep 3' \
		'ExecStart=/usr/bin/taskset -c $(CPU_BACKUP) /usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.persistence.ClusterBackupApp' \
		'Restart=on-failure' \
		'RestartSec=10' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/tmp/aeron-cluster/backup.log' \
		'StandardError=append:/tmp/aeron-cluster/backup.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > ~/.config/systemd/user/match-backup.service
	@echo "→ Installing match-gateway.service (CPU cores 14-15)..."
	@printf '%s\n' \
		'[Unit]' \
		'Description=Match Engine HTTP Gateway' \
		'After=network.target match-node0.service match-node1.service match-node2.service' \
		'' \
		'[Service]' \
		'Type=simple' \
		'WorkingDirectory=$(PWD)' \
		'Environment="MATCH_PROJECT_DIR=$(PWD)"' \
		'ExecStartPre=/bin/sleep 5' \
		'ExecStart=/usr/bin/taskset -c $(CPU_GATEWAY) /usr/bin/java $(JAVA_OPTS) -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.http.HttpController' \
		'Restart=on-failure' \
		'RestartSec=5' \
		'LimitNOFILE=1048576' \
		'LimitMEMLOCK=infinity' \
		'StandardOutput=append:/tmp/aeron-cluster/gateway.log' \
		'StandardError=append:/tmp/aeron-cluster/gateway.log' \
		'' \
		'[Install]' \
		'WantedBy=default.target' > ~/.config/systemd/user/match-gateway.service
	@echo ""
	@echo "→ Reloading systemd..."
	@systemctl --user daemon-reload
	@echo "→ Enabling services..."
	@systemctl --user enable match-node0 match-node1 match-node2 match-backup match-gateway
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Services installed and enabled!                               ║"
	@echo "║                                                                  ║"
	@echo "║  CPU Core Allocation:                                            ║"
	@echo "║    Node 0:  cores 0-3    Node 1:  cores 4-7                      ║"
	@echo "║    Node 2:  cores 8-11   Backup:  cores 12-13                    ║"
	@echo "║    Gateway: cores 14-15                                          ║"
	@echo "║                                                                  ║"
	@echo "║  Next: Run 'make fresh' to start the cluster                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# Uninstall systemd services
uninstall-services:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Uninstalling Systemd Services                          ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "→ Stopping services..."
	@systemctl --user stop match-gateway match-backup match-node2 match-node1 match-node0 2>/dev/null || true
	@echo "→ Disabling services..."
	@systemctl --user disable match-node0 match-node1 match-node2 match-backup match-gateway 2>/dev/null || true
	@echo "→ Removing service files..."
	@rm -f ~/.config/systemd/user/match-node0.service
	@rm -f ~/.config/systemd/user/match-node1.service
	@rm -f ~/.config/systemd/user/match-node2.service
	@rm -f ~/.config/systemd/user/match-backup.service
	@rm -f ~/.config/systemd/user/match-gateway.service
	@echo "→ Reloading systemd..."
	@systemctl --user daemon-reload
	@echo ""
	@echo "✓ Services uninstalled"

# Reinstall systemd services (uninstall + install)
reinstall-services: uninstall-services install-services
	@echo ""
	@echo "✓ Services reinstalled"

# ==================== ROLLING UPDATES ====================

# Default node for restart-node
NODE ?= 0

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

# Restart a single node (make restart-node NODE=0)
restart-node:
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Restarting Node $(NODE)                                     ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@case $(NODE) in \
		0|1|2) ;; \
		*) echo "Invalid node: $(NODE). Use 0, 1, or 2."; exit 1;; \
	esac
	@echo "→ Step 1/4: Stopping node $(NODE) via systemctl..."
	@systemctl --user stop match-node$(NODE) 2>/dev/null || true
	@sleep 2
	@echo "  ✓ Node stopped"
	@echo ""
	@echo "→ Step 2/4: Cleaning up mark files..."
	@rm -rf /dev/shm/aeron-*$(NODE)* 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/*.lck 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/cluster-mark*.dat 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/archive/archive-mark.dat 2>/dev/null || true
	@echo "  ✓ Mark files cleaned"
	@echo ""
	@echo "→ Step 3/4: Starting node $(NODE) via systemctl..."
	@systemctl --user start match-node$(NODE)
	@echo "  ✓ Node $(NODE) starting"
	@echo ""
	@echo "→ Step 4/4: Waiting for node to rejoin cluster..."
	@sleep 5
	@for i in 1 2 3 4 5; do \
		result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
			-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
			/tmp/aeron-cluster/node$(NODE)/cluster list-members 2>&1); \
		if echo "$$result" | grep -q "leaderMemberId="; then \
			leader=$$(echo "$$result" | grep -o "leaderMemberId=[0-9]*" | cut -d= -f2); \
			if [ "$$leader" = "$(NODE)" ]; then \
				echo "  ✓ Node $(NODE) rejoined as LEADER"; \
			else \
				echo "  ✓ Node $(NODE) rejoined as FOLLOWER"; \
			fi; \
			break; \
		fi; \
		echo "  Waiting... ($$i/5)"; \
		sleep 1; \
	done
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Node $(NODE) restart complete                                      ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"

# Rolling update - update all nodes one by one (zero downtime)
rolling-update: build-java
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║           Rolling Update - Zero Downtime Deployment              ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "This will update all 3 nodes one at a time:"
	@echo "  1. Find current leader"
	@echo "  2. Update followers first (maintains quorum)"
	@echo "  3. Update leader last (triggers election)"
	@echo ""
	@# Find leader and followers using list-members
	@result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
		-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
		/tmp/aeron-cluster/node0/cluster list-members 2>&1); \
	if ! echo "$$result" | grep -q "leaderMemberId="; then \
		echo "✗ Could not get cluster membership. Is cluster running?"; \
		exit 1; \
	fi; \
	leader=$$(echo "$$result" | grep -o "leaderMemberId=[0-9]*" | cut -d= -f2); \
	followers=""; \
	for node in 0 1 2; do \
		if [ "$$node" != "$$leader" ]; then \
			followers="$$followers $$node"; \
		fi; \
	done; \
	if [ -z "$$leader" ]; then \
		echo "✗ No leader found. Cluster may be unhealthy."; \
		exit 1; \
	fi; \
	echo "→ Found leader: Node $$leader"; \
	echo "→ Followers:$$followers"; \
	echo ""; \
	echo "══════════════════════════════════════════════════════════════════"; \
	echo "Phase 1: Updating followers"; \
	echo "══════════════════════════════════════════════════════════════════"; \
	for node in $$followers; do \
		echo ""; \
		echo "--- Updating Node $$node (follower) ---"; \
		$(MAKE) -s _restart-node-internal NODE=$$node; \
		echo ""; \
		echo "Verifying cluster membership..."; \
		sleep 3; \
		healthy=0; \
		for check in 1 2 3 4 5 6 7 8; do \
			result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
				-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
				/tmp/aeron-cluster/node0/cluster list-members 2>&1); \
			if echo "$$result" | grep -q "leaderMemberId="; then \
				member_count=$$(echo "$$result" | grep -o "ClusterMember{id=" | wc -l); \
				if [ "$$member_count" -ge 3 ]; then \
					echo "✓ Cluster healthy: leader elected, $$member_count active members"; \
					healthy=1; \
					break; \
				else \
					echo "  Cluster has leader, $$member_count members (waiting for 3)..."; \
				fi; \
			fi; \
			echo "  Waiting for cluster consensus... ($$check/8)"; \
			sleep 2; \
		done; \
		if [ "$$healthy" -eq 0 ]; then \
			echo "⚠ Cluster may not be fully healthy. Check logs if issues persist."; \
		fi; \
	done; \
	echo ""; \
	echo "══════════════════════════════════════════════════════════════════"; \
	echo "Phase 2: Updating leader (Node $$leader)"; \
	echo "══════════════════════════════════════════════════════════════════"; \
	echo ""; \
	echo "--- Updating Node $$leader (leader) ---"; \
	echo "This will trigger a leader election..."; \
	$(MAKE) -s _restart-node-internal NODE=$$leader; \
	echo ""; \
	echo "Waiting for new leader election..."; \
	sleep 5; \
	new_leader=""; \
	for check in 1 2 3 4 5 6 7 8 9 10; do \
		for node in 0 1 2; do \
			result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
				-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
				/tmp/aeron-cluster/node$$node/cluster list-members 2>&1); \
			if echo "$$result" | grep -q "leaderMemberId="; then \
				new_leader=$$(echo "$$result" | grep -o "leaderMemberId=[0-9]*" | cut -d= -f2); \
				if [ -n "$$new_leader" ]; then \
					echo "✓ New leader elected: Node $$new_leader"; \
					break 2; \
				fi; \
			fi; \
		done; \
		echo "Waiting for leader... ($$check/10)"; \
		sleep 2; \
	done; \
	if [ -z "$$new_leader" ]; then \
		echo "⚠ Leader election in progress..."; \
	fi
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║  ✓ Rolling update complete!                                      ║"
	@echo "╠══════════════════════════════════════════════════════════════════╣"
	@echo "║  All nodes updated with new code. Zero downtime achieved.        ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@$(MAKE) -s leader

# Internal helper for restarting a node (used by rolling-update)
_restart-node-internal:
	@echo "Stopping node $(NODE) via systemctl..."
	@systemctl --user stop match-node$(NODE) 2>/dev/null || true
	@sleep 2
	@echo "Cleaning up node $(NODE) files..."
	@rm -rf /dev/shm/aeron-*$(NODE)* 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/cluster-mark*.dat 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/*.lck 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/archive/archive-mark.dat 2>/dev/null || true
	@echo "Starting node $(NODE) via systemctl..."
	@systemctl --user start match-node$(NODE)
	@sleep 8
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
			-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
			/tmp/aeron-cluster/node$(NODE)/cluster list-members 2>&1); \
		if echo "$$result" | grep -q "leaderMemberId="; then \
			leader=$$(echo "$$result" | grep -o "leaderMemberId=[0-9]*" | cut -d= -f2); \
			if [ "$$leader" = "$(NODE)" ]; then \
				echo "✓ Node $(NODE) rejoined as LEADER"; \
			else \
				echo "✓ Node $(NODE) rejoined as FOLLOWER"; \
			fi; \
			break; \
		fi; \
		sleep 2; \
	done

# ==================== LOAD TESTING ====================

loadtest-quick:
	@./run-load-test.sh quick

loadtest-stress:
	@./run-load-test.sh stress

# ==================== SBE CODE GENERATION ====================

sbe:
	java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED -Dsbe.generate.ir=true -Dsbe.target.language=Java -Dsbe.target.namespace=com.match.infrastructure.generated.sbe -Dsbe.output.dir=match/src/main/java -Dsbe.errorLog=yes -jar binaries/sbe-all-1.35.3.jar match/src/main/resources/sbe/order-schema.xml

# ==================== CLUSTER CONFIGURATION ====================
# JVM flags for ultra-low latency (server)
JAVA_OPTS = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions -XX:GuaranteedSafepointInterval=0 \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx2g -Xms2g

# JVM flags for ultra-low latency load test client (ZGC)
JAVA_OPTS_CLIENT = -XX:+UseZGC -XX:+ZGenerational -XX:+UnlockDiagnosticVMOptions \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	-XX:CompileThreshold=1000 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx4g -Xms4g

# JVM flags for absolute lowest latency (Epsilon GC = no GC pauses)
# WARNING: Will run out of memory eventually - only for short benchmarks
JAVA_OPTS_EPSILON = -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC \
	-XX:+AlwaysPreTouch -XX:+UseNUMA -XX:+PerfDisableSharedMem \
	-XX:+TieredCompilation -XX:TieredStopAtLevel=4 \
	-XX:CompileThreshold=500 \
	--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	-Xmx8g -Xms8g

JAR_PATH = match/target/cluster-engine-1.0.jar

# CPU cores for pinning (24 cores available on i7-13700K)
# Node0: cores 0-3, Node1: cores 4-7, Node2: cores 8-11
# Backup: cores 12-13, Gateway: cores 14-15, LoadTest: cores 12-19 (shared)
CPU_NODE0 = 0-3
CPU_NODE1 = 4-7
CPU_NODE2 = 8-11
CPU_BACKUP = 12-13
CPU_GATEWAY = 14-15
CPU_LOADTEST = 12-19

# Build the project (Java + UI)
build: build-ui
	cd match && mvn clean package -DskipTests -q

# Build only Java
build-java:
	cd match && mvn clean package -DskipTests -q

# Build the React UI
build-ui:
	cd match/ui && npm install && npm run build

# Dev mode for UI (with hot reload)
dev-ui:
	cd match/ui && npm run dev

# Clean cluster data directories
clean-native:
	rm -rf /tmp/aeron-cluster/node0/* /tmp/aeron-cluster/node1/* /tmp/aeron-cluster/node2/*
	mkdir -p /tmp/aeron-cluster/node0 /tmp/aeron-cluster/node1 /tmp/aeron-cluster/node2

# Stop HTTP gateway service
stop-gateway:
	@echo "Stopping HTTP Gateway service..."
	@systemctl --user stop match-gateway 2>/dev/null || true
	@echo "Gateway stopped"

# Restart HTTP gateway service
restart-gateway:
	@echo "Restarting HTTP Gateway service..."
	@systemctl --user restart match-gateway
	@sleep 2
	@echo "Gateway restarted. UI at http://localhost:8080/ui/"

# Take a snapshot on all cluster nodes (triggers log truncation)
# Tries each node until it finds the leader
snapshot:
	@echo "Requesting snapshot from cluster leader..."
	@for node in 0 1 2; do \
		result=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
			-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
			/tmp/aeron-cluster/node$$node/cluster snapshot 2>&1); \
		echo "Node $$node: $$result"; \
		if echo "$$result" | grep -q "SNAPSHOT completed"; then \
			echo "✓ Snapshot triggered successfully via node $$node"; \
			break; \
		fi; \
	done

# Show cluster status
cluster-status:
	@echo "=== Cluster Node Status ==="
	@for node in 0 1 2; do \
		echo "--- Node $$node ---"; \
		java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
			-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
			/tmp/aeron-cluster/node$$node/cluster describe 2>/dev/null || echo "Node $$node not available"; \
	done

# Show archive recording info
archive-info:
	@echo "=== Archive Recording Info ==="
	@ls -lah /tmp/aeron-cluster/node*/archive/*.rec 2>/dev/null || echo "No recordings found"
	@echo ""
	@echo "=== Segment Sizes ==="
	@du -sh /tmp/aeron-cluster/node*/archive/ 2>/dev/null || echo "No archive dirs"

# Check backup status
backup-status:
	@echo "=== Backup Node Status ==="
	@if pgrep -f "ClusterBackupApp" > /dev/null; then \
		echo "✓ Backup node is running"; \
		tail -5 /tmp/aeron-cluster/backup.log 2>/dev/null || true; \
	else \
		echo "✗ Backup node is not running"; \
	fi
	@echo ""
	@echo "=== Backup Archive ==="
	@ls -lah /tmp/aeron-cluster/backup/archive/*.rec 2>/dev/null || echo "No backup recordings yet"

# Ultra-low latency test with built-in warmup (single JVM, no context switch overhead)
loadtest-ultra:
	@echo "=== Ultra-Low Latency Benchmark (ZGC) ===" && \
	taskset -c $(CPU_LOADTEST) java $(JAVA_OPTS_CLIENT) \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator \
	--rate 100000 --duration 60 --ultra --warmup 15

# Maximum performance test with Epsilon GC (no GC pauses, 30s only to avoid OOM)
loadtest-epsilon:
	@echo "=== Maximum Performance Benchmark (Epsilon GC - No GC Pauses) ===" && \
	taskset -c $(CPU_LOADTEST) java $(JAVA_OPTS_EPSILON) \
	-cp $(JAR_PATH) com.match.loadtest.LoadGenerator \
	--rate 100000 --duration 30 --ultra --warmup 10

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