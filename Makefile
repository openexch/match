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

.PHONY: tune tune-report audit-arm install install-deps optimize-os help build build-java build-cluster build-gateway build-loadtest sbe os-check determinism update-goldens durability p1-gate p1-gate-smoke

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
	@echo ""
	@$(MAKE) --no-print-directory audit-arm

# Arm a boot-persistent audit watch on /dev/shm directory removals (ag#68): a
# driver dir (/dev/shm/aeron-<user>-N-driver) has repeatedly been deleted out
# from under a live node by an unattributed process, bypassing the PM cleanup
# logs. This records the deleting pid/comm/auid so the culprit can be identified.
# Scoped to DIRECTORY-removal syscalls (rmdir + renames), NOT file unlinks: the
# prime suspect is the admin-gateway's own Go os.RemoveAll, which removes a dir
# via rmdir(2) on amd64 (unlinkat flag 0 -> EISDIR -> rmdir fallback) — so this
# catches it while staying near-silent (no per-file /dev/shm chatter). Trade-off:
# a manual coreutils `rm -rf` uses unlinkat(AT_REMOVEDIR) and would slip past;
# add unlink,unlinkat to the -S list if you ever need to catch that too.
# Idempotent; never fails the install (|| true). Read hits with:
#   sudo ausearch -k aeron-shm -i | grep -iE 'rmdir|rename' | tail
audit-arm:
	@echo "→ Arming /dev/shm dir-delete-watch (ag#68)..."
	@if ! command -v auditctl >/dev/null 2>&1; then \
		sudo apt install -y auditd 2>/dev/null || true; \
	fi
	@if command -v augenrules >/dev/null 2>&1; then \
		printf '%s\n' \
			'## Open Exchange: catch whoever deletes a live Aeron driver dir (ag#68).' \
			'## Dir-removal syscalls only (rmdir + renames) — near-silent; catches Go os.RemoveAll.' \
			'-a always,exit -F arch=b64 -F dir=/dev/shm -S rename,renameat,rmdir -k aeron-shm' \
			'-a always,exit -F arch=b32 -F dir=/dev/shm -S rename,renameat,rmdir -k aeron-shm' \
			| sudo tee /etc/audit/rules.d/aeron-shm.rules >/dev/null && \
		sudo systemctl enable --now auditd 2>/dev/null || true; \
		sudo augenrules --load 2>/dev/null || true; \
		if sudo auditctl -l 2>/dev/null | grep -q aeron-shm; then \
			echo "  ✓ Dir-delete-watch armed (key aeron-shm, persists across reboot)"; \
		else \
			echo "  ⚠ Rule file written but not loaded — check 'sudo systemctl status auditd'"; \
		fi; \
	else \
		echo "  ⚠ auditd unavailable; skipping (install it to arm the ag#68 watch)"; \
	fi

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

# Transport-critical tuning (idempotent, before/after report, safe without sudo).
# ISOLATED_CORES/NIC env-overridable; see deploy/tuning/system-tuning.sh header.
# optimize-os below keeps the broader system knobs (TCP, swappiness, scheduler).
tune:
	@./deploy/tuning/system-tuning.sh

tune-report:
	@./deploy/tuning/system-tuning.sh --report

tune-persist:
	@./deploy/tuning/system-tuning.sh --persist

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
	@echo "→ Persisting across reboots (sysctl.d + boot tuning unit)..."
	@./deploy/tuning/system-tuning.sh --persist | sed -n '/7\. persistence/,/AFTER:/p' | sed '$$d' || true
	@echo "  ✓ OS optimization complete (boot-persistent; verify after kernel upgrades: make tune-report)"

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

# ==================== TESTING ====================

# Fast, in-process determinism + durability unit suite (runs in CI via mvn verify).
determinism:
	mvn -pl match-cluster -am test -Dtest='DeterminismCorpusTest,EngineSnapshotReplayTest,SnapshotCodecTest,MatchingInvariantsTest' -Dsurefire.failIfNoSpecifiedTests=false

# Regenerate determinism golden files after an INTENTIONAL output change (review the diff, then commit).
update-goldens:
	mvn -pl match-cluster -am test -Dtest=DeterminismCorpusTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine="-Dupdate.golden=true"

# Multi-node durability scenarios against a LIVE cluster (manual / nightly, NOT the per-push CI gate).
durability:
	python3 tools/durability/durability.py all

# P1 acceptance gate (match#32): load + 50 leader switchovers + divergence/overflow
# report. Needs a live cluster AND an admin-gateway with the #13 truthful-status fix.
p1-gate:
	python3 tools/durability/storm.py --switchovers 50 --pg-reconcile
	python3 tools/durability/stranded.py

# ~5 minute smoke of the same harness (3 switchovers).
p1-gate-smoke:
	python3 tools/durability/storm.py --switchovers 3

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
	@echo "Testing:"
	@echo "  make determinism        Fast determinism + snapshot/durability unit suite"
	@echo "  make update-goldens     Regenerate determinism goldens (after intentional changes)"
	@echo "  make durability         Multi-node durability scenarios (needs a live cluster)"
	@echo "  make p1-gate            P1 acceptance storm: 50 switchovers + divergence report"
	@echo "  make p1-gate-smoke      Same harness, 3 switchovers (~5 min)"
	@echo ""
	@echo "Runtime management: see ../admin-gateway"
