package services

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/match/admin-gateway/config"
)

// ServiceRole defines the category of a managed service
type ServiceRole string

const (
	RoleClusterNode ServiceRole = "cluster"
	RoleGateway     ServiceRole = "gateway"
	RoleInfra       ServiceRole = "infra"
)

// ServiceDef defines how to start and manage a process
type ServiceDef struct {
	Name        string            `json:"name"`
	Display     string            `json:"display"`
	Role        ServiceRole       `json:"role"`
	Port        int               `json:"port,omitempty"`
	ExtraPorts  []int             `json:"-"` // additional ports to check (e.g. Aeron UDP egress)
	Command     []string          `json:"-"` // command + args
	Env         map[string]string `json:"-"` // extra environment variables
	WorkDir     string            `json:"-"` // working directory
	PreStart    [][]string        `json:"-"` // pre-start commands (run sequentially)
	DependsOn   []string          `json:"dependsOn,omitempty"`
	StartOrder  int               `json:"-"`
	AutoRestart bool              `json:"-"` // restart on crash
	RestartSec  int               `json:"-"` // seconds between restart attempts
	StopTimeout int               `json:"-"` // seconds to wait for graceful stop before SIGKILL
}

// ProcessInfo is the live state of a managed service
type ProcessInfo struct {
	Name         string      `json:"name"`
	Display      string      `json:"display"`
	Role         ServiceRole `json:"role"`
	Port         int         `json:"port,omitempty"`
	Running      bool        `json:"running"`
	PID          int         `json:"pid,omitempty"`
	MemoryBytes  int64       `json:"memoryBytes,omitempty"`
	CPUPercent   float64     `json:"cpuPercent,omitempty"`
	UptimeMs     int64       `json:"uptimeMs,omitempty"`
	StartedAt    string      `json:"startedAt,omitempty"`
	RestartCount int         `json:"restartCount"`
	Enabled      bool        `json:"enabled"`
	Status       string      `json:"status"` // "running", "stopped", "starting", "stopping", "failed", "crashed"
}

// managedProcess tracks a running process
type managedProcess struct {
	mu           sync.Mutex
	cmd          *exec.Cmd
	pid          int
	running      bool
	starting     bool // true while a start is in progress (prevents auto-restart race)
	startedAt    time.Time
	restartCount int
	status       string // "running", "stopped", "starting", "stopping", "failed", "crashed"
	stopChan     chan struct{}
	logFile      *os.File
}

// ProcessManager directly manages processes (no systemd for managed services)
type ProcessManager struct {
	cfg      *config.Config
	services []ServiceDef
	procs    map[string]*managedProcess // name → process state

	mu       sync.RWMutex
	pidDir   string
	logDir   string
	stopChan chan struct{}
}

func NewProcessManager(cfg *config.Config) *ProcessManager {
	homeDir, _ := os.UserHomeDir()
	logDir := filepath.Join(homeDir, ".local/log/cluster")
	pidDir := filepath.Join(homeDir, ".local/run/match")

	os.MkdirAll(logDir, 0755)
	os.MkdirAll(pidDir, 0755)

	javaBase := []string{
		"/usr/bin/java",
		"-XX:+UseZGC", "-XX:+ZGenerational",
		"-XX:+UnlockDiagnosticVMOptions", "-XX:GuaranteedSafepointInterval=0",
		"-XX:+AlwaysPreTouch", "-XX:+UseNUMA",
		"-XX:+PerfDisableSharedMem",
		"-XX:+TieredCompilation", "-XX:TieredStopAtLevel=4",
		"--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
		"--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
		"-Xmx2g", "-Xms2g",
	}

	// Node command with taskset for CPU pinning
	nodeCmd := func(cpuSet string) []string {
		cmd := []string{"/usr/bin/taskset", "-c", cpuSet}
		cmd = append(cmd, javaBase...)
		cmd = append(cmd, "-jar", "match-cluster/target/match-cluster.jar")
		return cmd
	}

	nodeEnv := func(nodeId int) map[string]string {
		return map[string]string{
			"CLUSTER_ADDRESSES":  "127.0.0.1,127.0.0.1,127.0.0.1",
			"CLUSTER_NODE":       strconv.Itoa(nodeId),
			"CLUSTER_PORT_BASE":  "9000",
			"BASE_DIR":           fmt.Sprintf("/dev/shm/aeron-cluster/node%d", nodeId),
		}
	}

	nodePreStart := func(nodeId int) [][]string {
		return [][]string{
			{"mkdir", "-p", fmt.Sprintf("/dev/shm/aeron-cluster/node%d", nodeId)},
		}
	}

	gatewayCmd := func() []string {
		cmd := make([]string, len(javaBase))
		copy(cmd, javaBase)
		return cmd
	}

	pm := &ProcessManager{
		cfg:    cfg,
		logDir: logDir,
		pidDir: pidDir,
		procs:  make(map[string]*managedProcess),
		services: []ServiceDef{
			{
				Name: "node0", Display: "Cluster Node 0", Role: RoleClusterNode,
				Command: nodeCmd("0-3"), Env: nodeEnv(0), WorkDir: cfg.ProjectDir,
				PreStart: nodePreStart(0), StartOrder: 1,
				AutoRestart: true, RestartSec: 10, StopTimeout: 5,
			},
			{
				Name: "node1", Display: "Cluster Node 1", Role: RoleClusterNode,
				Command: nodeCmd("4-7"), Env: nodeEnv(1), WorkDir: cfg.ProjectDir,
				PreStart: nodePreStart(1), StartOrder: 2,
				AutoRestart: true, RestartSec: 10, StopTimeout: 5,
			},
			{
				Name: "node2", Display: "Cluster Node 2", Role: RoleClusterNode,
				Command: nodeCmd("8-11"), Env: nodeEnv(2), WorkDir: cfg.ProjectDir,
				PreStart: nodePreStart(2), StartOrder: 3,
				AutoRestart: true, RestartSec: 10, StopTimeout: 5,
			},
			{
				Name: "backup", Display: "Backup Node", Role: RoleClusterNode,
				Command: append(gatewayCmd(), "-cp", "match-cluster/target/match-cluster.jar",
					"com.match.infrastructure.persistence.ClusterBackupApp"),
				WorkDir: cfg.ProjectDir,
				PreStart: [][]string{{"mkdir", "-p", "/dev/shm/aeron-cluster/backup"}},
				DependsOn: []string{"node0", "node1", "node2"}, StartOrder: 4,
				AutoRestart: true, RestartSec: 10, StopTimeout: 5,
			},
			{
				Name: "order", Display: "Order Gateway", Role: RoleGateway, Port: 8080,
				ExtraPorts: []int{9092}, // Aeron UDP egress port
				Command: append(gatewayCmd(), "-cp", "match-gateway/target/match-gateway.jar",
					"com.match.infrastructure.gateway.OrderGatewayMain"),
				Env: map[string]string{
					"MATCH_PROJECT_DIR": cfg.ProjectDir,
					"EGRESS_PORT":       "9092",
					"GATEWAY_TYPE":      "order",
				},
				WorkDir: cfg.ProjectDir,
				DependsOn: []string{"node0", "node1", "node2"}, StartOrder: 5,
				AutoRestart: true, RestartSec: 5, StopTimeout: 5,
			},
			{
				Name: "market", Display: "Market Gateway", Role: RoleGateway, Port: 8081,
				ExtraPorts: []int{9091}, // Aeron UDP egress port
				Command: append(gatewayCmd(), "-cp", "match-gateway/target/match-gateway.jar",
					"com.match.infrastructure.gateway.MarketGatewayMain"),
				Env: map[string]string{
					"MATCH_PROJECT_DIR": cfg.ProjectDir,
					"EGRESS_PORT":       "9091",
					"GATEWAY_TYPE":      "market",
				},
				WorkDir: cfg.ProjectDir,
				DependsOn: []string{"node0", "node1", "node2"}, StartOrder: 6,
				AutoRestart: true, RestartSec: 5, StopTimeout: 5,
			},
			{
				Name: "admin", Display: "Admin Gateway", Role: RoleGateway, Port: 8082,
				// Admin is self — we don't manage ourselves, just report status
				StartOrder: 7,
			},
			// Trading UI lives in separate repo (trading-ui)
		},
		stopChan: make(chan struct{}),
	}

	// Initialize process state for each service
	for _, def := range pm.services {
		pm.procs[def.Name] = &managedProcess{
			status: "stopped",
		}
	}

	// Adopt any already-running processes (from PID files)
	pm.adoptExisting()

	// Start background metrics poller
	go pm.backgroundPoller()

	return pm
}

func (pm *ProcessManager) Shutdown() {
	// Signal all monitor goroutines to stop auto-restarting
	close(pm.stopChan)

	// Also close every per-process stopChan to prevent any in-flight auto-restarts
	for _, def := range pm.services {
		if def.Name == "admin" {
			continue
		}
		proc := pm.procs[def.Name]
		proc.mu.Lock()
		if proc.stopChan != nil {
			select {
			case <-proc.stopChan:
			default:
				close(proc.stopChan)
			}
		}
		proc.mu.Unlock()
	}

	// Brief pause to let any in-flight restarts see the closed channels
	time.Sleep(500 * time.Millisecond)
}

// --- Public API ---

// List returns live info for all managed services
func (pm *ProcessManager) List() []ProcessInfo {
	result := make([]ProcessInfo, len(pm.services))
	for i, def := range pm.services {
		result[i] = pm.getInfo(def)
	}
	return result
}

// Get returns live info for a single service
func (pm *ProcessManager) Get(name string) *ProcessInfo {
	def := pm.findDef(name)
	if def == nil {
		return nil
	}
	info := pm.getInfo(*def)
	return &info
}

// Start a service (with dependency check)
func (pm *ProcessManager) Start(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}

	if name == "admin" {
		return fmt.Errorf("admin gateway manages itself via rebuild-admin endpoint")
	}

	if len(def.Command) == 0 {
		return fmt.Errorf("service %s has no command configured", name)
	}

	// Check if already running
	proc := pm.procs[name]
	proc.mu.Lock()
	if proc.running {
		proc.mu.Unlock()
		return fmt.Errorf("%s is already running (PID %d)", name, proc.pid)
	}
	proc.mu.Unlock()

	// Check dependencies
	for _, dep := range def.DependsOn {
		depProc := pm.procs[dep]
		depProc.mu.Lock()
		isRunning := depProc.running
		depProc.mu.Unlock()
		if !isRunning {
			return fmt.Errorf("dependency %q is not running (required by %s)", dep, name)
		}
	}

	return pm.startProcess(*def)
}

// Stop a service (with reverse dependency check)
func (pm *ProcessManager) Stop(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}

	if name == "admin" {
		return fmt.Errorf("admin gateway manages itself via rebuild-admin endpoint")
	}

	// Check if anything depends on us and is still running
	dependents := pm.findDependents(name)
	runningDeps := []string{}
	for _, d := range dependents {
		dProc := pm.procs[d]
		dProc.mu.Lock()
		if dProc.running {
			runningDeps = append(runningDeps, d)
		}
		dProc.mu.Unlock()
	}
	if len(runningDeps) > 0 {
		return fmt.Errorf("cannot stop %s: services still depend on it: %s (stop them first or use force-stop)",
			name, strings.Join(runningDeps, ", "))
	}

	return pm.stopProcess(name, false)
}

// ForceStop bypasses dependency checks
func (pm *ProcessManager) ForceStop(name string) error {
	if pm.findDef(name) == nil {
		return fmt.Errorf("unknown service: %s", name)
	}
	if name == "admin" {
		return fmt.Errorf("admin gateway manages itself via rebuild-admin endpoint")
	}
	return pm.stopProcess(name, true)
}

// Restart a service
func (pm *ProcessManager) Restart(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}
	if name == "admin" {
		return fmt.Errorf("admin gateway manages itself via rebuild-admin endpoint")
	}

	proc := pm.procs[name]
	proc.mu.Lock()
	wasRunning := proc.running
	proc.mu.Unlock()

	if wasRunning {
		if err := pm.stopProcess(name, true); err != nil {
			return fmt.Errorf("failed to stop %s for restart: %w", name, err)
		}
		// Wait for port release if the service binds a port
		if def.Port > 0 {
			if err := pm.waitForPortFree(def.Port, 20*time.Second); err != nil {
				fmt.Printf("[PM] Warning: port %d not free after stop, proceeding anyway: %v\n", def.Port, err)
			}
		} else {
			// Brief pause for non-port services (Aeron cleanup)
			time.Sleep(2 * time.Second)
		}
	}

	return pm.startProcess(*def)
}

// StartAll starts services in dependency order
func (pm *ProcessManager) StartAll() []ActionResult {
	results := []ActionResult{}
	ordered := pm.bootOrder()

	for _, def := range ordered {
		if def.Name == "admin" || len(def.Command) == 0 {
			continue
		}

		proc := pm.procs[def.Name]
		proc.mu.Lock()
		isRunning := proc.running
		proc.mu.Unlock()

		if isRunning {
			results = append(results, ActionResult{Service: def.Name, Action: "start", Success: true, Error: "already running"})
			continue
		}

		err := pm.startProcess(def)
		result := ActionResult{Service: def.Name, Action: "start", Success: err == nil}
		if err != nil {
			result.Error = err.Error()
		}
		results = append(results, result)
		time.Sleep(2 * time.Second) // stagger starts
	}

	return results
}

// StopAll stops services in reverse dependency order
func (pm *ProcessManager) StopAll() []ActionResult {
	results := []ActionResult{}
	ordered := pm.shutdownOrder()

	for _, def := range ordered {
		if def.Name == "admin" || len(def.Command) == 0 {
			continue
		}

		proc := pm.procs[def.Name]
		proc.mu.Lock()
		isRunning := proc.running
		proc.mu.Unlock()

		if !isRunning {
			results = append(results, ActionResult{Service: def.Name, Action: "stop", Success: true, Error: "already stopped"})
			continue
		}

		err := pm.stopProcess(def.Name, true) // force during bulk stop
		result := ActionResult{Service: def.Name, Action: "stop", Success: err == nil}
		if err != nil {
			result.Error = err.Error()
		}
		results = append(results, result)
	}

	return results
}

// RestartAll stops everything then starts in order
func (pm *ProcessManager) RestartAll() []ActionResult {
	stopResults := pm.StopAll()
	time.Sleep(2 * time.Second)
	startResults := pm.StartAll()

	results := make([]ActionResult, 0, len(stopResults)+len(startResults))
	results = append(results, stopResults...)
	results = append(results, startResults...)
	return results
}

// Summary returns an overview
func (pm *ProcessManager) Summary() map[string]interface{} {
	total := len(pm.services)
	running := 0
	stopped := 0
	failed := 0
	var totalMem int64

	for _, def := range pm.services {
		proc := pm.procs[def.Name]
		proc.mu.Lock()
		switch {
		case proc.running:
			running++
			if proc.pid > 0 {
				totalMem += getProcessMemory(proc.pid)
			}
		case proc.status == "failed" || proc.status == "crashed":
			failed++
		default:
			stopped++
		}
		proc.mu.Unlock()
	}

	// Count admin as running (we're always running)
	// Already counted above if adopted

	return map[string]interface{}{
		"total":         total,
		"running":       running,
		"stopped":       stopped,
		"failed":        failed,
		"totalMemoryMB": totalMem / (1024 * 1024),
	}
}

type ActionResult struct {
	Service string `json:"service"`
	Action  string `json:"action,omitempty"`
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

// --- Process Lifecycle ---

// startByName is a convenience for internal callers (e.g. operations service)
func (pm *ProcessManager) startByName(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}
	if len(def.Command) == 0 {
		return fmt.Errorf("service %s has no command configured", name)
	}
	return pm.startProcess(*def)
}

func (pm *ProcessManager) startProcess(def ServiceDef) error {
	return pm.startProcessInner(def, true)
}

func (pm *ProcessManager) startProcessNoRotate(def ServiceDef) error {
	return pm.startProcessInner(def, false)
}

func (pm *ProcessManager) startProcessInner(def ServiceDef, rotateLogs bool) error {
	proc := pm.procs[def.Name]

	// Phase 1: Check state and claim the start (hold lock briefly)
	proc.mu.Lock()
	if proc.running {
		proc.mu.Unlock()
		return fmt.Errorf("%s is already running", def.Name)
	}
	if proc.starting {
		proc.mu.Unlock()
		return fmt.Errorf("%s is already starting", def.Name)
	}
	proc.starting = true
	proc.status = "starting"
	proc.mu.Unlock()

	// Phase 2: Pre-start work WITHOUT holding the lock (port wait, cleanup, etc.)
	// This allows status queries and stop commands to proceed during preparation.
	var startErr error

	// Clean stale Aeron state for cluster nodes
	if def.Role == RoleClusterNode {
		pm.cleanStaleAeronState(def.Name)
	}

	// Kill orphaned processes on ALL ports (main + extra) BEFORE waiting for ports.
	// An orphan may hold both the HTTP port and Aeron egress port — must kill first.
	if def.Port > 0 {
		pm.killOrphanedPortHolder(def.Port, def.Name)
	}
	for _, extraPort := range def.ExtraPorts {
		pm.killOrphanedPortHolder(extraPort, def.Name)
	}

	// Clean stale Aeron MediaDriver dirs for gateways
	if def.Role == RoleGateway {
		pm.cleanStaleGatewayAeron(def.Name)
	}

	// Wait for port to be free (gateways bind HTTP ports)
	if def.Port > 0 {
		if err := pm.waitForPortFree(def.Port, 15*time.Second); err != nil {
			startErr = fmt.Errorf("port %d not free for %s: %w", def.Port, def.Name, err)
		}
	}
	// Also wait for extra ports (Aeron egress)
	if startErr == nil {
		for _, extraPort := range def.ExtraPorts {
			if err := pm.waitForPortFree(extraPort, 10*time.Second); err != nil {
				startErr = fmt.Errorf("extra port %d not free for %s: %w", extraPort, def.Name, err)
				break
			}
		}
	}

	// Run pre-start commands
	if startErr == nil {
		for _, preCmd := range def.PreStart {
			if len(preCmd) == 0 {
				continue
			}
			cmd := exec.Command(preCmd[0], preCmd[1:]...)
			cmd.Dir = def.WorkDir
			if out, err := cmd.CombinedOutput(); err != nil {
				startErr = fmt.Errorf("pre-start command %v failed: %s: %w", preCmd, string(out), err)
				break
			}
		}
	}

	// If pre-start failed, mark as failed and return
	if startErr != nil {
		proc.mu.Lock()
		proc.status = "failed"
		proc.starting = false
		proc.mu.Unlock()
		return startErr
	}

	// Phase 3: Actually start the process (hold lock for state mutation)
	proc.mu.Lock()
	defer func() {
		proc.starting = false
		proc.mu.Unlock()
	}()

	// Re-check: someone might have started it while we were doing pre-start work
	if proc.running {
		return fmt.Errorf("%s is already running (started while preparing)", def.Name)
	}

	// Rotate log file (skip on auto-restart to preserve crash context)
	logPath := filepath.Join(pm.logDir, def.Name+".log")
	if rotateLogs {
		pm.rotateLog(logPath)
	}

	// Open log file
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		proc.status = "failed"
		return fmt.Errorf("failed to open log file %s: %w", logPath, err)
	}

	// Build command
	cmd := exec.Command(def.Command[0], def.Command[1:]...)
	cmd.Dir = def.WorkDir
	cmd.Stdout = logFile
	cmd.Stderr = logFile

	// Set process group so we can kill the whole tree
	cmd.SysProcAttr = &syscall.SysProcAttr{
		Setpgid: true,
	}

	// Environment: inherit current env + add overrides
	env := os.Environ()
	for k, v := range def.Env {
		env = append(env, k+"="+v)
	}
	cmd.Env = env

	// Start the process
	if err := cmd.Start(); err != nil {
		logFile.Close()
		proc.status = "failed"
		return fmt.Errorf("failed to start %s: %w", def.Name, err)
	}

	proc.cmd = cmd
	proc.pid = cmd.Process.Pid
	proc.running = true
	proc.startedAt = time.Now()
	proc.status = "running"
	proc.logFile = logFile
	proc.stopChan = make(chan struct{})

	// Write PID file
	pm.writePID(def.Name, proc.pid)

	fmt.Printf("[PM] Started %s (PID %d)\n", def.Name, proc.pid)

	// Monitor process in background (handles crash + auto-restart)
	go pm.monitor(def, proc)

	return nil
}

func (pm *ProcessManager) stopProcess(name string, force bool) error {
	proc := pm.procs[name]
	proc.mu.Lock()

	// Always cancel pending auto-restarts, even if process appears stopped
	if proc.stopChan != nil {
		select {
		case <-proc.stopChan:
		default:
			close(proc.stopChan)
		}
	}

	if !proc.running || proc.cmd == nil {
		// Maybe an adopted process — try killing by PID
		if proc.pid > 0 {
			proc.status = "stopping"
			pid := proc.pid
			proc.mu.Unlock()
			syscall.Kill(-pid, syscall.SIGTERM) // Kill process group
			time.Sleep(2 * time.Second)
			// Check if dead
			if isProcessAlive(pid) {
				syscall.Kill(-pid, syscall.SIGKILL)
				time.Sleep(500 * time.Millisecond)
			}
			proc.mu.Lock()
			proc.running = false
			proc.pid = 0
			proc.status = "stopped"
			proc.mu.Unlock()
			pm.removePID(name)
			fmt.Printf("[PM] Stopped %s (adopted process)\n", name)
			return nil
		}
		proc.status = "stopped"
		proc.mu.Unlock()
		return nil // Not an error — just confirms it's stopped
	}

	proc.status = "stopping"
	pid := proc.pid
	proc.mu.Unlock()

	// Graceful shutdown: SIGTERM to process group
	def := pm.findDef(name)
	timeout := 5
	if def != nil && def.StopTimeout > 0 {
		timeout = def.StopTimeout
	}

	syscall.Kill(-pid, syscall.SIGTERM)

	// Wait for graceful exit
	deadline := time.Now().Add(time.Duration(timeout) * time.Second)
	for time.Now().Before(deadline) {
		if !isProcessAlive(pid) {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}

	// Force kill if still alive
	if isProcessAlive(pid) {
		fmt.Printf("[PM] Force killing %s (PID %d)\n", name, pid)
		syscall.Kill(-pid, syscall.SIGKILL)
		// Wait for the process to actually die (up to 5s after SIGKILL)
		killDeadline := time.Now().Add(5 * time.Second)
		for time.Now().Before(killDeadline) {
			if !isProcessAlive(pid) {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		if isProcessAlive(pid) {
			fmt.Printf("[PM] WARNING: %s (PID %d) still alive after SIGKILL + 5s\n", name, pid)
		}
	}

	proc.mu.Lock()
	proc.running = false
	proc.pid = 0
	proc.status = "stopped"
	if proc.logFile != nil {
		proc.logFile.Close()
		proc.logFile = nil
	}
	proc.mu.Unlock()

	pm.removePID(name)
	fmt.Printf("[PM] Stopped %s\n", name)

	return nil
}

func (pm *ProcessManager) monitor(def ServiceDef, proc *managedProcess) {
	if proc.cmd == nil {
		return
	}

	// Wait for process to exit
	err := proc.cmd.Wait()

	proc.mu.Lock()
	wasRunning := proc.running
	stopChan := proc.stopChan
	proc.running = false
	oldPid := proc.pid
	proc.pid = 0

	if proc.logFile != nil {
		proc.logFile.Close()
		proc.logFile = nil
	}
	proc.mu.Unlock()

	pm.removePID(def.Name)

	// Check if this was an intentional stop
	select {
	case <-stopChan:
		// Intentional stop — don't restart
		proc.mu.Lock()
		proc.status = "stopped"
		proc.mu.Unlock()
		fmt.Printf("[PM] %s stopped intentionally (was PID %d)\n", def.Name, oldPid)
		return
	default:
	}

	// Also check if the whole PM is shutting down
	select {
	case <-pm.stopChan:
		proc.mu.Lock()
		proc.status = "stopped"
		proc.mu.Unlock()
		return
	default:
	}

	if wasRunning {
		exitMsg := "unknown"
		if err != nil {
			exitMsg = err.Error()
		}
		fmt.Printf("[PM] %s crashed (PID %d, exit: %s)\n", def.Name, oldPid, exitMsg)
	}

	// Auto-restart if enabled (with crash-loop detection)
	if def.AutoRestart {
		restartSec := def.RestartSec
		if restartSec <= 0 {
			restartSec = 5
		}

		// Crash-loop detection: if restarted 5+ times, apply exponential backoff
		proc.mu.Lock()
		rapidRestarts := proc.restartCount
		proc.mu.Unlock()

		const maxRapidRestarts = 5
		if rapidRestarts >= maxRapidRestarts {
			// Exponential backoff: 30s, 60s, 120s, 240s... up to 5min
			backoffSec := 30 * (1 << (rapidRestarts - maxRapidRestarts))
			if backoffSec > 300 {
				backoffSec = 300
			}
			restartSec = backoffSec
			fmt.Printf("[PM] %s in crash loop (%d restarts), backing off to %ds\n", def.Name, rapidRestarts, restartSec)
		}

		proc.mu.Lock()
		proc.status = "restarting"
		proc.mu.Unlock()

		fmt.Printf("[PM] Will restart %s in %ds\n", def.Name, restartSec)

		select {
		case <-time.After(time.Duration(restartSec) * time.Second):
		case <-pm.stopChan:
			return
		case <-stopChan:
			return
		}

		// Check if someone else is already starting this service
		proc.mu.Lock()
		if proc.starting || proc.running {
			proc.mu.Unlock()
			fmt.Printf("[PM] Skipping auto-restart of %s — already %s\n",
				def.Name, func() string {
					if proc.running {
						return "running"
					}
					return "starting"
				}())
			return
		}
		proc.restartCount++
		proc.mu.Unlock()

		fmt.Printf("[PM] Auto-restarting %s (attempt %d)\n", def.Name, proc.restartCount)
		// Use startProcessNoRotate to preserve crash logs
		if err := pm.startProcessNoRotate(def); err != nil {
			fmt.Printf("[PM] Failed to restart %s: %v\n", def.Name, err)
			proc.mu.Lock()
			proc.status = "failed"
			proc.mu.Unlock()
		}
	} else {
		proc.mu.Lock()
		proc.status = "crashed"
		proc.mu.Unlock()
	}
}

// --- Process Adoption (re-discover after admin restart) ---

func (pm *ProcessManager) adoptExisting() {
	for _, def := range pm.services {
		// Special case: admin is always "us"
		if def.Name == "admin" {
			proc := pm.procs[def.Name]
			proc.mu.Lock()
			proc.pid = os.Getpid()
			proc.running = true
			proc.startedAt = time.Now() // approximate
			proc.status = "running"
			proc.mu.Unlock()
			continue
		}

		pid := pm.readPID(def.Name)
		if pid <= 0 {
			continue
		}

		if isProcessAlive(pid) {
			proc := pm.procs[def.Name]
			proc.mu.Lock()
			proc.pid = pid
			proc.running = true
			proc.status = "running"
			proc.startedAt = getProcessStartTime(pid)
			proc.mu.Unlock()
			fmt.Printf("[PM] Adopted %s (PID %d)\n", def.Name, pid)
		} else {
			// Stale PID file
			pm.removePID(def.Name)
		}
	}

	// After adoption, kill orphaned processes holding ports that belong to adopted services.
	// This handles the race where auto-restart spawns a duplicate during admin restart.
	pm.cleanupOrphansAfterAdoption()
}

// cleanupOrphansAfterAdoption kills processes holding service ports that aren't the adopted PID.
// Runs once at startup to catch orphans from the previous admin instance's auto-restart race.
func (pm *ProcessManager) cleanupOrphansAfterAdoption() {
	for _, def := range pm.services {
		if def.Name == "admin" || !pm.procs[def.Name].running {
			continue
		}

		// Check main port
		if def.Port > 0 {
			pm.killOrphanedPortHolder(def.Port, def.Name)
		}
		// Check extra ports (Aeron egress)
		for _, extraPort := range def.ExtraPorts {
			pm.killOrphanedPortHolder(extraPort, def.Name)
		}
	}
}

// --- PID File Management ---

func (pm *ProcessManager) writePID(name string, pid int) {
	path := filepath.Join(pm.pidDir, name+".pid")
	os.WriteFile(path, []byte(strconv.Itoa(pid)), 0644)
}

func (pm *ProcessManager) readPID(name string) int {
	path := filepath.Join(pm.pidDir, name+".pid")
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil {
		return 0
	}
	return pid
}

func (pm *ProcessManager) removePID(name string) {
	path := filepath.Join(pm.pidDir, name+".pid")
	os.Remove(path)
}

// --- Aeron Cleanup ---

// cleanStaleAeronState removes stale mark files and media driver directories
// that prevent a node from restarting after a crash
func (pm *ProcessManager) cleanStaleAeronState(name string) {
	// Map service name to node ID for media driver cleanup
	nodeIds := map[string]int{"node0": 0, "node1": 1, "node2": 2}
	if nodeId, ok := nodeIds[name]; ok {
		// Clean stale MediaDriver directory
		driverDir := fmt.Sprintf("/dev/shm/aeron-emre-%d-driver", nodeId)
		if _, err := os.Stat(driverDir); err == nil {
			os.RemoveAll(driverDir)
			fmt.Printf("[PM] Cleaned stale MediaDriver: %s\n", driverDir)
		}

		// Clean stale mark files and locks
		nodeDir := fmt.Sprintf("/dev/shm/aeron-cluster/%s", name)
		patterns := []string{
			nodeDir + "/cluster/cluster-mark*.dat",
			nodeDir + "/cluster/*.lck",
			nodeDir + "/archive/archive-mark.dat",
		}
		for _, pattern := range patterns {
			matches, _ := filepath.Glob(pattern)
			for _, m := range matches {
				os.Remove(m)
				fmt.Printf("[PM] Cleaned stale file: %s\n", m)
			}
		}
	}

	// Backup node
	if name == "backup" {
		backupDir := "/dev/shm/aeron-cluster/backup"
		patterns := []string{
			backupDir + "/cluster/cluster-mark*.dat",
			backupDir + "/cluster/*.lck",
			backupDir + "/archive/archive-mark.dat",
		}
		for _, pattern := range patterns {
			matches, _ := filepath.Glob(pattern)
			for _, m := range matches {
				os.Remove(m)
			}
		}
	}
}

// isPortInUse checks both TCP and UDP for a port being in use.
func isPortInUse(port int) bool {
	for _, proto := range []string{"-t", "-u"} {
		cmd := exec.Command("ss", proto+"lnH", fmt.Sprintf("sport = :%d", port))
		out, err := cmd.Output()
		if err == nil && len(strings.TrimSpace(string(out))) > 0 {
			return true
		}
	}
	return false
}

// waitForPortFree polls until a port (TCP or UDP) is no longer in use.
// Logs what PID is holding the port for easier debugging.
func (pm *ProcessManager) waitForPortFree(port int, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	logged := false
	for time.Now().Before(deadline) {
		if !isPortInUse(port) {
			return nil
		}
		if !logged {
			holder := pm.findPortHolder(port)
			if holder != "" {
				fmt.Printf("[PM] Port %d held by %s, waiting up to %s...\n", port, holder, timeout)
			} else {
				fmt.Printf("[PM] Port %d still in use, waiting up to %s...\n", port, timeout)
			}
			logged = true
		}
		time.Sleep(500 * time.Millisecond)
	}
	holder := pm.findPortHolder(port)
	return fmt.Errorf("port %d still in use after %s (holder: %s)", port, timeout, holder)
}

// findPortHolder returns a description of the process holding a port (TCP or UDP).
func (pm *ProcessManager) findPortHolder(port int) string {
	for _, proto := range []string{"-t", "-u"} {
		cmd := exec.Command("ss", proto+"lnpH", fmt.Sprintf("sport = :%d", port))
		out, err := cmd.Output()
		if err != nil {
			continue
		}
		line := strings.TrimSpace(string(out))
		if line == "" {
			continue
		}
		// ss -p output includes something like: users:(("java",pid=12345,fd=6))
		if idx := strings.Index(line, "users:"); idx >= 0 {
			return strings.TrimSpace(line[idx:])
		}
		return line
	}
	return "none"
}

// killOrphanedPortHolder finds and kills any process holding a port that isn't tracked by the PM.
// This handles orphaned gateway processes left behind from failed restarts.
func (pm *ProcessManager) killOrphanedPortHolder(port int, serviceName string) {
	cmd := exec.Command("ss", "-tlnpH", fmt.Sprintf("sport = :%d", port))
	out, _ := cmd.Output()
	if len(strings.TrimSpace(string(out))) == 0 {
		// Also check UDP (Aeron egress uses UDP)
		cmd = exec.Command("ss", "-ulnpH", fmt.Sprintf("sport = :%d", port))
		out, _ = cmd.Output()
	}
	if len(strings.TrimSpace(string(out))) == 0 {
		return
	}

	// Extract PID from ss output like: users:(("java",pid=12345,fd=6))
	line := string(out)
	pidIdx := strings.Index(line, "pid=")
	if pidIdx < 0 {
		return
	}
	pidStr := line[pidIdx+4:]
	if commaIdx := strings.IndexAny(pidStr, ",)"); commaIdx > 0 {
		pidStr = pidStr[:commaIdx]
	}
	pid, err := strconv.Atoi(strings.TrimSpace(pidStr))
	if err != nil || pid <= 0 {
		return
	}

	// Check if this PID is the one we're tracking
	proc := pm.procs[serviceName]
	proc.mu.Lock()
	trackedPID := proc.pid
	proc.mu.Unlock()

	if pid == trackedPID {
		return // It's our tracked process, not an orphan
	}

	// It's an orphan — kill it
	fmt.Printf("[PM] Killing orphaned process PID %d holding port %d (expected by %s)\n", pid, port, serviceName)
	syscall.Kill(-pid, syscall.SIGTERM)
	time.Sleep(2 * time.Second)
	if isProcessAlive(pid) {
		syscall.Kill(-pid, syscall.SIGKILL)
		time.Sleep(500 * time.Millisecond)
	}

	// Clean its Aeron directory
	prefix := fmt.Sprintf("aeron-%s-%d", serviceName, pid)
	dirPath := filepath.Join("/dev/shm", prefix)
	if _, err := os.Stat(dirPath); err == nil {
		os.RemoveAll(dirPath)
		fmt.Printf("[PM] Cleaned orphan Aeron dir: %s\n", dirPath)
	}
}

// cleanStaleGatewayAeron removes stale MediaDriver directories for gateway processes
func (pm *ProcessManager) cleanStaleGatewayAeron(name string) {
	// Gateway MediaDriver dirs are named aeron-{name}-{pid}
	// Clean any that don't belong to a running process
	entries, err := os.ReadDir("/dev/shm")
	if err != nil {
		return
	}
	prefix := fmt.Sprintf("aeron-%s-", name)
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), prefix) {
			// Extract PID from directory name
			pidStr := strings.TrimPrefix(entry.Name(), prefix)
			pid, err := strconv.Atoi(pidStr)
			if err != nil {
				continue
			}
			// Check if this PID is still alive
			if err := syscall.Kill(pid, 0); err != nil {
				// Process is dead, clean up
				dirPath := filepath.Join("/dev/shm", entry.Name())
				os.RemoveAll(dirPath)
				fmt.Printf("[PM] Cleaned stale gateway Aeron dir: %s\n", dirPath)
			}
		}
	}
}

// --- Log Management ---

func (pm *ProcessManager) rotateLog(logPath string) {
	if _, err := os.Stat(logPath); err == nil {
		ts := time.Now().Format("20060102-150405")
		os.Rename(logPath, logPath+"."+ts)
	}
}

// --- Metrics Polling ---

func (pm *ProcessManager) backgroundPoller() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			pm.refreshAdoptedProcesses()
		case <-pm.stopChan:
			return
		}
	}
}

// refreshAdoptedProcesses checks if adopted processes are still alive
func (pm *ProcessManager) refreshAdoptedProcesses() {
	for _, def := range pm.services {
		if def.Name == "admin" {
			continue
		}

		proc := pm.procs[def.Name]
		proc.mu.Lock()
		if proc.running && proc.pid > 0 && proc.cmd == nil {
			// Adopted process — check if still alive
			if !isProcessAlive(proc.pid) {
				proc.running = false
				proc.pid = 0
				proc.status = "stopped"
				pm.removePID(def.Name)
				fmt.Printf("[PM] Adopted process %s is no longer running\n", def.Name)
			}
		}
		proc.mu.Unlock()
	}
}

// --- Info Helpers ---

func (pm *ProcessManager) getInfo(def ServiceDef) ProcessInfo {
	proc := pm.procs[def.Name]
	proc.mu.Lock()
	defer proc.mu.Unlock()

	info := ProcessInfo{
		Name:         def.Name,
		Display:      def.Display,
		Role:         def.Role,
		Port:         def.Port,
		Running:      proc.running,
		PID:          proc.pid,
		RestartCount: proc.restartCount,
		Enabled:      true,
		Status:       proc.status,
	}

	if proc.running && proc.pid > 0 {
		info.MemoryBytes = getProcessMemory(proc.pid)
		info.CPUPercent = getProcessCPU(proc.pid)

		if !proc.startedAt.IsZero() {
			info.UptimeMs = time.Since(proc.startedAt).Milliseconds()
			info.StartedAt = proc.startedAt.Format("Mon 2006-01-02 15:04:05 -07")
		}
	}

	return info
}

func (pm *ProcessManager) findDef(name string) *ServiceDef {
	for i := range pm.services {
		if pm.services[i].Name == name {
			return &pm.services[i]
		}
	}
	return nil
}

func (pm *ProcessManager) findDependents(name string) []string {
	var deps []string
	for _, def := range pm.services {
		for _, d := range def.DependsOn {
			if d == name {
				deps = append(deps, def.Name)
				break
			}
		}
	}
	return deps
}

func (pm *ProcessManager) bootOrder() []ServiceDef {
	ordered := make([]ServiceDef, len(pm.services))
	copy(ordered, pm.services)
	return ordered
}

func (pm *ProcessManager) shutdownOrder() []ServiceDef {
	forward := pm.bootOrder()
	reversed := make([]ServiceDef, len(forward))
	for i, def := range forward {
		reversed[len(forward)-1-i] = def
	}
	return reversed
}

// --- System Helpers ---

func isProcessAlive(pid int) bool {
	if pid <= 0 {
		return false
	}
	// Signal 0 checks existence without actually sending a signal
	err := syscall.Kill(pid, 0)
	return err == nil
}

func getProcessMemory(pid int) int64 {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/statm", pid))
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(data))
	if len(fields) < 2 {
		return 0
	}
	rss, err := strconv.ParseInt(fields[1], 10, 64)
	if err != nil {
		return 0
	}
	return rss * 4096
}

func getProcessCPU(pid int) float64 {
	cmd := exec.Command("ps", "-p", strconv.Itoa(pid), "-o", "%cpu", "--no-headers")
	out, err := cmd.Output()
	if err != nil {
		return 0
	}
	val, err := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if err != nil {
		return 0
	}
	return val
}

func getProcessStartTime(pid int) time.Time {
	// Read /proc/[pid]/stat for start time
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return time.Time{}
	}

	// Field 22 is starttime (in clock ticks since boot)
	// Find the closing paren of the comm field first
	s := string(data)
	idx := strings.LastIndex(s, ")")
	if idx < 0 {
		return time.Time{}
	}
	fields := strings.Fields(s[idx+2:]) // skip ") "
	if len(fields) < 20 {
		return time.Time{}
	}

	startTicks, err := strconv.ParseInt(fields[19], 10, 64) // field 22 - 3 = index 19
	if err != nil {
		return time.Time{}
	}

	// Get system boot time
	bootTime := getBootTime()
	if bootTime.IsZero() {
		return time.Time{}
	}

	clkTck := int64(100) // sysconf(_SC_CLK_TCK) = 100 on Linux
	startSec := startTicks / clkTck
	startNsec := (startTicks % clkTck) * (1e9 / clkTck)

	return bootTime.Add(time.Duration(startSec)*time.Second + time.Duration(startNsec)*time.Nanosecond)
}

var cachedBootTime time.Time

func getBootTime() time.Time {
	if !cachedBootTime.IsZero() {
		return cachedBootTime
	}

	f, err := os.Open("/proc/stat")
	if err != nil {
		return time.Time{}
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "btime ") {
			ts, err := strconv.ParseInt(strings.TrimPrefix(line, "btime "), 10, 64)
			if err != nil {
				return time.Time{}
			}
			cachedBootTime = time.Unix(ts, 0)
			return cachedBootTime
		}
	}
	return time.Time{}
}

// Ensure unused imports are consumed
var _ = bufio.NewReader
var _ io.Reader
