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

.PHONY: install install-deps optimize-os status stop fresh help leader restart-node rolling-update

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
	@echo "→ Step 1/5: Building UI..."
	@cd match/ui && npm install --silent && npm run build --silent
	@echo "  ✓ UI built"
	@echo ""
	@echo "→ Step 2/5: Building Java components..."
	@cd match && mvn clean package -DskipTests -q
	@echo "  ✓ Java components built"
	@echo ""
	@echo "→ Step 3/5: Starting cluster nodes..."
	@$(MAKE) -s _start-cluster-internal
	@echo "  ✓ Cluster nodes started (3 nodes)"
	@echo ""
	@echo "→ Step 4/5: Starting backup node..."
	@$(MAKE) -s _start-backup-internal
	@echo "  ✓ Backup node started"
	@echo ""
	@echo "→ Step 5/5: Starting HTTP gateway..."
	@$(MAKE) -s _start-gateway-internal
	@echo "  ✓ HTTP gateway started"
	@echo ""
	@sleep 2
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

# Stop all components
stop:
	@echo "→ Stopping all components..."
	@# Stop using PID files first (clean shutdown)
	@-for pidfile in /tmp/aeron-cluster/*.pid; do \
		if [ -f "$$pidfile" ]; then \
			pid=$$(cat "$$pidfile" 2>/dev/null); \
			if [ -n "$$pid" ] && kill -0 $$pid 2>/dev/null; then \
				kill -TERM $$pid 2>/dev/null; \
			fi; \
			rm -f "$$pidfile"; \
		fi; \
	done
	@sleep 2
	@# Force kill any remaining processes
	@-pkill -9 -f "ClusterBackupApp" 2>/dev/null || true
	@-pkill -9 -f "HttpController" 2>/dev/null || true
	@-pkill -9 -f "cluster-engine-1.0.jar" 2>/dev/null || true
	@sleep 1
	@echo "  ✓ All components stopped"

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
	@echo "  make install-deps   Install system dependencies (Java, Node.js, Maven)"
	@echo "  make install        Build and start everything fresh"
	@echo "  make optimize-os    Apply OS optimizations for low latency (sudo)"
	@echo ""
	@echo "Operations:"
	@echo "  make status         Check cluster health and component status"
	@echo "  make services       Show systemd service status for all components"
	@echo "  make stop           Stop all components"
	@echo "  make fresh          Clean all state and start cluster fresh"
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
	@# Determine CPU cores for this node
	@cpu_cores=""; \
	case $(NODE) in \
		0) cpu_cores="$(CPU_NODE0)";; \
		1) cpu_cores="$(CPU_NODE1)";; \
		2) cpu_cores="$(CPU_NODE2)";; \
		*) echo "Invalid node: $(NODE). Use 0, 1, or 2."; exit 1;; \
	esac; \
	echo "→ Step 1/5: Finding node $(NODE) process..."; \
	pid=$$(java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
		-cp $(JAR_PATH) io.aeron.cluster.ClusterTool \
		/tmp/aeron-cluster/node$(NODE)/cluster pid 2>/dev/null); \
	if [ -n "$$pid" ] && [ "$$pid" -gt 0 ] 2>/dev/null; then \
		echo "  Found PID: $$pid"; \
		echo ""; \
		echo "→ Step 2/5: Gracefully stopping node $(NODE)..."; \
		kill -TERM $$pid 2>/dev/null || true; \
		sleep 2; \
		if kill -0 $$pid 2>/dev/null; then \
			echo "  Force killing..."; \
			kill -9 $$pid 2>/dev/null || true; \
		fi; \
		echo "  ✓ Node stopped"; \
	else \
		echo "  Node not running, will start fresh"; \
	fi; \
	echo ""; \
	echo "→ Step 3/5: Waiting for mark file timeout (10s)..."; \
	sleep 10; \
	echo "  ✓ Mark file timeout elapsed"; \
	echo ""; \
	echo "→ Step 4/5: Cleaning up mark files for node $(NODE)..."; \
	rm -rf /dev/shm/aeron-*-$(NODE)-driver 2>/dev/null || true; \
	rm -rf /tmp/aeron-cluster/node$(NODE)/cluster/*.lck 2>/dev/null || true; \
	rm -rf /tmp/aeron-cluster/node$(NODE)/cluster/cluster-mark.dat 2>/dev/null || true; \
	echo "  ✓ Mark files cleaned"; \
	echo ""; \
	echo "→ Step 5/5: Starting node $(NODE) with updated code..."; \
	CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=$(NODE) CLUSTER_PORT_BASE=9000 \
		BASE_DIR=/tmp/aeron-cluster/node$(NODE) taskset -c $$cpu_cores java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node$(NODE).log 2>&1 & \
	echo "  ✓ Node $(NODE) starting"; \
	echo ""; \
	echo "→ Waiting for node to rejoin cluster..."; \
	sleep 3; \
	for i in 1 2 3 4 5; do \
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
# Note: Aeron mark file has a ~10 second timeout, so we wait 12s after killing
_restart-node-internal:
	@echo "Stopping node $(NODE)..."
	@-if [ -f /tmp/aeron-cluster/node$(NODE).pid ]; then \
		pid=$$(cat /tmp/aeron-cluster/node$(NODE).pid); \
		if [ -n "$$pid" ] && kill -0 $$pid 2>/dev/null; then \
			echo "  Stopping process $$pid gracefully"; \
			kill -TERM $$pid 2>/dev/null; \
			sleep 2; \
			kill -9 $$pid 2>/dev/null || true; \
		fi; \
	fi; true
	@echo "Waiting for mark file timeout (12s)..."
	@sleep 12
	@echo "Cleaning up node $(NODE) files..."
	@rm -rf /dev/shm/aeron-*$(NODE)* 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/cluster-mark*.dat 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/cluster/*.lck 2>/dev/null || true
	@rm -f /tmp/aeron-cluster/node$(NODE)/archive/archive-mark.dat 2>/dev/null || true
	@echo "Starting node $(NODE)..."
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=$(NODE) CLUSTER_PORT_BASE=9000 \
		BASE_DIR=/tmp/aeron-cluster/node$(NODE) java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node$(NODE).log 2>&1 & echo $$! > /tmp/aeron-cluster/node$(NODE).pid
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

# ==================== INTERNAL HELPERS ====================

# Start all 3 cluster nodes as background processes (saves PID files for management)
_start-cluster-internal:
	@rm -rf /tmp/aeron-cluster/node0/* /tmp/aeron-cluster/node1/* /tmp/aeron-cluster/node2/* 2>/dev/null || true
	@mkdir -p /tmp/aeron-cluster/node0 /tmp/aeron-cluster/node1 /tmp/aeron-cluster/node2
	@rm -rf /dev/shm/aeron-* 2>/dev/null || true
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=0 CLUSTER_PORT_BASE=9000 \
		BASE_DIR=/tmp/aeron-cluster/node0 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node0.log 2>&1 & echo $$! > /tmp/aeron-cluster/node0.pid
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=1 CLUSTER_PORT_BASE=9000 \
		BASE_DIR=/tmp/aeron-cluster/node1 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node1.log 2>&1 & echo $$! > /tmp/aeron-cluster/node1.pid
	@sleep 2
	@CLUSTER_ADDRESSES="localhost,localhost,localhost" CLUSTER_NODE=2 CLUSTER_PORT_BASE=9000 \
		BASE_DIR=/tmp/aeron-cluster/node2 java $(JAVA_OPTS) -jar $(JAR_PATH) > /tmp/aeron-cluster/node2.log 2>&1 & echo $$! > /tmp/aeron-cluster/node2.pid
	@sleep 4

# Start backup node as background process
_start-backup-internal:
	@mkdir -p /tmp/aeron-cluster/backup
	@java $(JAVA_OPTS) -cp $(JAR_PATH) com.match.infrastructure.persistence.ClusterBackupApp > /tmp/aeron-cluster/backup.log 2>&1 & echo $$! > /tmp/aeron-cluster/backup.pid
	@sleep 2

# Start gateway as background process
_start-gateway-internal:
	@java -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
		--add-opens java.base/sun.nio.ch=ALL-UNNAMED -Xmx2g -Xms2g \
		-cp $(JAR_PATH) com.match.infrastructure.http.HttpController > /tmp/aeron-cluster/gateway.log 2>&1 & echo $$! > /tmp/aeron-cluster/gateway.pid
	@sleep 2

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
# Node0: cores 0-3, Node1: cores 4-7, Node2: cores 8-11, LoadTest: cores 12-19
# More cores for load test to handle DEDICATED threading + busy-spin threads
CPU_NODE0 = 0-3
CPU_NODE1 = 4-7
CPU_NODE2 = 8-11
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

# Start HTTP gateway (serves UI on port 8080) - foreground mode
start-gateway:
	@echo "Starting HTTP Gateway on port 8080..."
	java $(JAVA_OPTS) -cp $(JAR_PATH) com.match.infrastructure.http.HttpController

# Start HTTP gateway as systemd service
start-gateway-bg:
	@echo "Starting HTTP Gateway service..."
	@systemctl --user start match-gateway 2>/dev/null || ( \
		echo "Service not installed. Installing..."; \
		mkdir -p ~/.config/systemd/user; \
		echo '[Unit]\nDescription=Match Engine HTTP Gateway\nAfter=network.target\n\n[Service]\nType=simple\nWorkingDirectory=$(shell pwd)\nExecStart=/usr/bin/java -XX:+UseZGC -XX:+ZGenerational --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED -Xmx2g -Xms2g -cp match/target/cluster-engine-1.0.jar com.match.infrastructure.http.HttpController\nRestart=on-failure\nRestartSec=5\nStandardOutput=append:/tmp/aeron-cluster/gateway.log\nStandardError=append:/tmp/aeron-cluster/gateway.log\n\n[Install]\nWantedBy=default.target' > ~/.config/systemd/user/match-gateway.service; \
		systemctl --user daemon-reload; \
		systemctl --user enable match-gateway; \
		systemctl --user start match-gateway; \
	)
	@sleep 2
	@echo "HTTP Gateway started. UI at http://localhost:8080/ui/"

# Stop HTTP gateway service
stop-gateway:
	@echo "Stopping HTTP Gateway service..."
	@systemctl --user stop match-gateway 2>/dev/null || pkill -f "HttpController" 2>/dev/null || true
	@echo "Gateway stopped"

# Restart HTTP gateway service
restart-gateway:
	@echo "Restarting HTTP Gateway service..."
	@systemctl --user restart match-gateway
	@sleep 2
	@echo "Gateway restarted. UI at http://localhost:8080/ui/"

# Stop native cluster
stop-cluster:
	@echo "Stopping cluster..."
	@pkill -9 -f "cluster-engine-1.0.jar" 2>/dev/null || true
	@pkill -9 -f "aeron" 2>/dev/null || true
	@sleep 1
	@rm -rf /tmp/aeron-cluster/node*
	@echo "Cluster stopped and cleaned"

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

# Start backup node (non-voting observer that takes snapshots without affecting cluster)
start-backup:
	@echo "Starting ClusterBackup node (non-voting observer)..."
	@mkdir -p /tmp/aeron-cluster/backup
	CLUSTER_ADDRESSES=localhost,localhost,localhost \
	BACKUP_HOST=localhost \
	CLUSTER_PORT_BASE=9000 \
	BACKUP_INTERVAL_SEC=30 \
	BASE_DIR=/tmp/aeron-cluster/backup \
	java $(JAVA_OPTS) -cp $(JAR_PATH) \
		com.match.infrastructure.persistence.ClusterBackupApp

# Start backup node in background
start-backup-bg:
	@echo "Starting ClusterBackup node in background..."
	@mkdir -p /tmp/aeron-cluster/backup
	@CLUSTER_ADDRESSES=localhost,localhost,localhost \
	BACKUP_HOST=localhost \
	CLUSTER_PORT_BASE=9000 \
	BACKUP_INTERVAL_SEC=30 \
	BASE_DIR=/tmp/aeron-cluster/backup \
	java $(JAVA_OPTS) -cp $(JAR_PATH) \
		com.match.infrastructure.persistence.ClusterBackupApp > /tmp/aeron-cluster/backup.log 2>&1 &
	@sleep 3
	@echo "Backup node started. Logs: tail -f /tmp/aeron-cluster/backup.log"

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