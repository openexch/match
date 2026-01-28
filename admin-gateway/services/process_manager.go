package services

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
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

// ServiceDef defines a managed service (static config)
type ServiceDef struct {
	Name        string      `json:"name"`        // systemd unit name (e.g. "node0")
	Display     string      `json:"display"`      // human-readable (e.g. "Cluster Node 0")
	Role        ServiceRole `json:"role"`         // cluster | gateway | infra
	Port        int         `json:"port,omitempty"`
	HealthURL   string      `json:"-"`            // optional HTTP health check URL
	DependsOn   []string    `json:"dependsOn,omitempty"` // services that must be running first
	StartOrder  int         `json:"-"`            // boot sequence order (lower = first)
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
	Healthy      *bool       `json:"healthy,omitempty"`      // nil = no health check, true/false
	RestartCount int         `json:"restartCount,omitempty"` // from systemd NRestarts
	Enabled      bool        `json:"enabled"`
	Status       string      `json:"status"`  // "running", "stopped", "starting", "failed", "activating"
}

// ProcessManager is the central process orchestrator
type ProcessManager struct {
	cfg      *config.Config
	systemd  *Systemd
	services []ServiceDef

	mu       sync.RWMutex
	cache    []ProcessInfo
	lastPoll time.Time

	stopChan chan struct{}
}

func NewProcessManager(cfg *config.Config, systemd *Systemd) *ProcessManager {
	pm := &ProcessManager{
		cfg:     cfg,
		systemd: systemd,
		services: []ServiceDef{
			{Name: "node0", Display: "Cluster Node 0", Role: RoleClusterNode, StartOrder: 1},
			{Name: "node1", Display: "Cluster Node 1", Role: RoleClusterNode, StartOrder: 2},
			{Name: "node2", Display: "Cluster Node 2", Role: RoleClusterNode, StartOrder: 3},
			{Name: "backup", Display: "Backup Node", Role: RoleClusterNode, StartOrder: 4, DependsOn: []string{"node0", "node1", "node2"}},
			{Name: "order", Display: "Order Gateway", Role: RoleGateway, Port: 8080, DependsOn: []string{"node0", "node1", "node2"}},
			{Name: "market", Display: "Market Gateway", Role: RoleGateway, Port: 8081, DependsOn: []string{"node0", "node1", "node2"}},
			{Name: "admin", Display: "Admin Gateway", Role: RoleGateway, Port: 8082, HealthURL: "http://localhost:8082/health"},
			{Name: "ui", Display: "Trading UI", Role: RoleInfra, Port: 3000, DependsOn: []string{"order", "market"}},
		},
		stopChan: make(chan struct{}),
	}

	// Initial poll
	pm.refreshAll()

	// Background poller every 5s (lightweight — just systemctl + /proc reads)
	go pm.backgroundPoller()

	return pm
}

func (pm *ProcessManager) Shutdown() {
	close(pm.stopChan)
}

// --- Public API ---

// List returns live info for all managed services
func (pm *ProcessManager) List() []ProcessInfo {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	// Return a copy
	result := make([]ProcessInfo, len(pm.cache))
	copy(result, pm.cache)
	return result
}

// Get returns live info for a single service
func (pm *ProcessManager) Get(name string) *ProcessInfo {
	pm.mu.RLock()
	defer pm.mu.RUnlock()
	for i := range pm.cache {
		if pm.cache[i].Name == name {
			info := pm.cache[i]
			return &info
		}
	}
	return nil
}

// Start a service (with dependency check)
func (pm *ProcessManager) Start(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}

	// Check dependencies
	for _, dep := range def.DependsOn {
		if !pm.systemd.IsActive(dep) {
			return fmt.Errorf("dependency %q is not running (required by %s)", dep, name)
		}
	}

	return pm.systemd.Start(name)
}

// Stop a service (with reverse dependency check)
func (pm *ProcessManager) Stop(name string) error {
	def := pm.findDef(name)
	if def == nil {
		return fmt.Errorf("unknown service: %s", name)
	}

	// Check if anything depends on us and is still running
	dependents := pm.findDependents(name)
	runningDeps := []string{}
	for _, d := range dependents {
		if pm.systemd.IsActive(d) {
			runningDeps = append(runningDeps, d)
		}
	}
	if len(runningDeps) > 0 {
		return fmt.Errorf("cannot stop %s: services still depend on it: %s (stop them first or use force)",
			name, strings.Join(runningDeps, ", "))
	}

	return pm.systemd.Stop(name)
}

// ForceStop bypasses dependency checks
func (pm *ProcessManager) ForceStop(name string) error {
	if pm.findDef(name) == nil {
		return fmt.Errorf("unknown service: %s", name)
	}
	return pm.systemd.Stop(name)
}

// Restart a service
func (pm *ProcessManager) Restart(name string) error {
	if pm.findDef(name) == nil {
		return fmt.Errorf("unknown service: %s", name)
	}
	return pm.systemd.Restart(name)
}

// StartAll starts services in dependency order
func (pm *ProcessManager) StartAll() []ActionResult {
	results := []ActionResult{}
	ordered := pm.bootOrder()

	for _, def := range ordered {
		err := pm.systemd.Start(def.Name)
		result := ActionResult{Service: def.Name, Success: err == nil}
		if err != nil {
			result.Error = err.Error()
		}
		results = append(results, result)
		// Brief pause between starts to let services bind ports
		time.Sleep(1 * time.Second)
	}

	return results
}

// StopAll stops services in reverse dependency order
func (pm *ProcessManager) StopAll() []ActionResult {
	results := []ActionResult{}
	ordered := pm.shutdownOrder()

	for _, def := range ordered {
		err := pm.systemd.Stop(def.Name)
		result := ActionResult{Service: def.Name, Success: err == nil}
		if err != nil {
			result.Error = err.Error()
		}
		results = append(results, result)
	}

	return results
}

// RestartAll restarts everything in proper order (stop reverse, start forward)
func (pm *ProcessManager) RestartAll() []ActionResult {
	stopResults := pm.StopAll()
	time.Sleep(2 * time.Second)
	startResults := pm.StartAll()

	results := make([]ActionResult, 0, len(stopResults)+len(startResults))
	for _, r := range stopResults {
		r.Action = "stop"
		results = append(results, r)
	}
	for _, r := range startResults {
		r.Action = "start"
		results = append(results, r)
	}
	return results
}

// Summary returns an overview
func (pm *ProcessManager) Summary() map[string]interface{} {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	total := len(pm.cache)
	running := 0
	stopped := 0
	failed := 0
	var totalMem int64

	for _, p := range pm.cache {
		switch {
		case p.Running:
			running++
			totalMem += p.MemoryBytes
		case p.Status == "failed":
			failed++
		default:
			stopped++
		}
	}

	return map[string]interface{}{
		"total":          total,
		"running":        running,
		"stopped":        stopped,
		"failed":         failed,
		"totalMemoryMB":  totalMem / (1024 * 1024),
		"lastPollMs":     time.Since(pm.lastPoll).Milliseconds(),
	}
}

type ActionResult struct {
	Service string `json:"service"`
	Action  string `json:"action,omitempty"`
	Success bool   `json:"success"`
	Error   string `json:"error,omitempty"`
}

// --- Internal ---

func (pm *ProcessManager) backgroundPoller() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			pm.refreshAll()
		case <-pm.stopChan:
			return
		}
	}
}

func (pm *ProcessManager) refreshAll() {
	infos := make([]ProcessInfo, len(pm.services))

	for i, def := range pm.services {
		infos[i] = pm.probe(def)
	}

	pm.mu.Lock()
	pm.cache = infos
	pm.lastPoll = time.Now()
	pm.mu.Unlock()
}

func (pm *ProcessManager) probe(def ServiceDef) ProcessInfo {
	info := ProcessInfo{
		Name:    def.Name,
		Display: def.Display,
		Role:    def.Role,
		Port:    def.Port,
		Enabled: pm.isEnabled(def.Name),
	}

	// Get systemd active state
	activeState := pm.getActiveState(def.Name)
	info.Running = activeState == "active"

	switch activeState {
	case "active":
		info.Status = "running"
	case "activating":
		info.Status = "starting"
	case "deactivating":
		info.Status = "stopping"
	case "failed":
		info.Status = "failed"
	default:
		info.Status = "stopped"
	}

	if !info.Running {
		return info
	}

	// PID
	info.PID = pm.systemd.GetPID(def.Name)

	// Uptime from systemd ActiveEnterTimestamp
	if startedAt := pm.getProperty(def.Name, "ActiveEnterTimestamp"); startedAt != "" {
		info.StartedAt = startedAt
		if t, err := parseSystemdTimestamp(startedAt); err == nil {
			info.UptimeMs = time.Since(t).Milliseconds()
		}
	}

	// Restart count
	if n := pm.getProperty(def.Name, "NRestarts"); n != "" {
		if v, err := strconv.Atoi(n); err == nil {
			info.RestartCount = v
		}
	}

	// Memory + CPU from /proc (fast, no subprocess)
	if info.PID > 0 {
		info.MemoryBytes = pm.getProcessMemory(info.PID)
		info.CPUPercent = pm.getProcessCPU(info.PID)
	}

	return info
}

// getActiveState returns the systemd ActiveState for a unit
func (pm *ProcessManager) getActiveState(service string) string {
	return pm.getProperty(service, "ActiveState")
}

// getProperty reads a single systemd unit property
func (pm *ProcessManager) getProperty(service, prop string) string {
	cmd := exec.Command("systemctl", "--user", "show", "-p", prop, "--value", service)
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// isEnabled checks if systemd unit is enabled
func (pm *ProcessManager) isEnabled(service string) bool {
	cmd := exec.Command("systemctl", "--user", "is-enabled", service)
	out, _ := cmd.Output()
	return strings.TrimSpace(string(out)) == "enabled"
}

// getProcessMemory reads RSS from /proc/[pid]/statm (pages → bytes)
func (pm *ProcessManager) getProcessMemory(pid int) int64 {
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/statm", pid))
	if err != nil {
		return 0
	}
	fields := strings.Fields(string(data))
	if len(fields) < 2 {
		return 0
	}
	// field[1] = resident set size in pages
	rss, err := strconv.ParseInt(fields[1], 10, 64)
	if err != nil {
		return 0
	}
	return rss * 4096 // page size = 4KB on x86_64
}

// getProcessCPU estimates CPU% from /proc/[pid]/stat (quick snapshot via ps)
func (pm *ProcessManager) getProcessCPU(pid int) float64 {
	// Use ps for a quick CPU% — avoids complex jiffies math
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

func (pm *ProcessManager) findDef(name string) *ServiceDef {
	for i := range pm.services {
		if pm.services[i].Name == name {
			return &pm.services[i]
		}
	}
	return nil
}

// findDependents returns service names that depend on the given service
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

// bootOrder returns services sorted by start order
func (pm *ProcessManager) bootOrder() []ServiceDef {
	ordered := make([]ServiceDef, len(pm.services))
	copy(ordered, pm.services)
	// Already defined in order — services slice is intentionally ordered
	return ordered
}

// shutdownOrder returns services in reverse boot order
func (pm *ProcessManager) shutdownOrder() []ServiceDef {
	forward := pm.bootOrder()
	reversed := make([]ServiceDef, len(forward))
	for i, def := range forward {
		reversed[len(forward)-1-i] = def
	}
	return reversed
}

// parseSystemdTimestamp parses "Day YYYY-MM-DD HH:MM:SS TZ" format
func parseSystemdTimestamp(s string) (time.Time, error) {
	// systemd format: "Thu 2026-01-29 01:17:41 +03"
	// Try common formats
	formats := []string{
		"Mon 2006-01-02 15:04:05 MST",
		"Mon 2006-01-02 15:04:05 -07",
		"Mon 2006-01-02 15:04:05 Z07",
		time.RFC3339,
	}
	for _, f := range formats {
		if t, err := time.Parse(f, s); err == nil {
			return t, nil
		}
	}
	return time.Time{}, fmt.Errorf("unparseable timestamp: %s", s)
}
