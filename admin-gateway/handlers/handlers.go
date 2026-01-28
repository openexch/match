package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/match/admin-gateway/services"
)

type Handlers struct {
	statusSvc    *services.StatusService
	opsSvc       *services.OperationsService
	systemd      *services.Systemd
	cluster      *services.Cluster
	progress     *services.Progress
	status       *services.ClusterStatus
	autoSnapshot *services.AutoSnapshot
	logSvc       *services.LogService
	procMgr      *services.ProcessManager
}

func New(
	statusSvc *services.StatusService,
	opsSvc *services.OperationsService,
	systemd *services.Systemd,
	cluster *services.Cluster,
	progress *services.Progress,
	status *services.ClusterStatus,
	autoSnapshot *services.AutoSnapshot,
	logSvc *services.LogService,
	procMgr *services.ProcessManager,
) *Handlers {
	return &Handlers{
		statusSvc:    statusSvc,
		opsSvc:       opsSvc,
		systemd:      systemd,
		cluster:      cluster,
		progress:     progress,
		status:       status,
		autoSnapshot: autoSnapshot,
		logSvc:       logSvc,
		procMgr:      procMgr,
	}
}

func (h *Handlers) RegisterRoutes(r chi.Router) {
	r.Use(corsMiddleware)

	// Status
	r.Get("/api/admin/status", h.handleStatus)
	r.Get("/api/admin/progress", h.handleProgress)

	// Node operations
	r.Post("/api/admin/restart-node", h.handleRestartNode)
	r.Post("/api/admin/stop-node", h.handleStopNode)
	r.Post("/api/admin/start-node", h.handleStartNode)
	r.Post("/api/admin/stop-all-nodes", h.handleStopAllNodes)
	r.Post("/api/admin/start-all-nodes", h.handleStartAllNodes)

	// Complex operations
	r.Post("/api/admin/rolling-update", h.handleRollingUpdate)
	r.Post("/api/admin/snapshot", h.handleSnapshot)

	// Build operations (multi-module safe)
	r.Post("/api/admin/rebuild-gateway", h.handleRebuildGateway)
	r.Post("/api/admin/rebuild-cluster", h.handleRebuildCluster)

	// Archive compaction operations
	r.Post("/api/admin/compact", h.handleCompact)
	r.Post("/api/admin/compact-archive", h.handleCompactArchive)
	r.Post("/api/admin/rolling-cleanup", h.handleRollingCleanup)

	// Auto-snapshot (GET/POST/DELETE)
	r.Get("/api/admin/auto-snapshot", h.handleAutoSnapshotGet)
	r.Post("/api/admin/auto-snapshot", h.handleAutoSnapshotPost)
	r.Delete("/api/admin/auto-snapshot", h.handleAutoSnapshotDelete)

	// Logs
	r.Get("/api/admin/logs", h.handleLogs)

	// Self-update (admin gateway)
	r.Post("/api/admin/rebuild-admin", h.handleRebuildAdmin)

	// Process manager
	r.Get("/api/admin/processes", h.handleProcessList)
	r.Get("/api/admin/processes/summary", h.handleProcessSummary)
	r.Get("/api/admin/processes/{name}", h.handleProcessGet)
	r.Post("/api/admin/processes/{name}/start", h.handleProcessStart)
	r.Post("/api/admin/processes/{name}/stop", h.handleProcessStop)
	r.Post("/api/admin/processes/{name}/restart", h.handleProcessRestart)
	r.Post("/api/admin/processes/{name}/force-stop", h.handleProcessForceStop)
	r.Post("/api/admin/processes/start-all", h.handleProcessStartAll)
	r.Post("/api/admin/processes/stop-all", h.handleProcessStopAll)
	r.Post("/api/admin/processes/restart-all", h.handleProcessRestartAll)

	// Cleanup
	r.Post("/api/admin/cleanup", h.handleCleanup)

	// Health check
	r.Get("/health", h.handleHealth)
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.Header().Set("Access-Control-Allow-Private-Network", "true")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}

func (h *Handlers) handleStatus(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, h.statusSvc.GetStatus())
}

func (h *Handlers) handleProgress(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("reset") == "true" && h.progress.ToMap()["complete"] == true {
		h.progress.Reset()
	}
	jsonResponse(w, http.StatusOK, h.progress.ToMap())
}

func (h *Handlers) handleHealth(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, map[string]string{"status": "healthy"})
}

// Node operations
func (h *Handlers) handleRestartNode(w http.ResponseWriter, r *http.Request) {
	nodeId, err := h.getNodeId(r)
	if err != nil {
		jsonResponse(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	go func() {
		h.status.SetNodeStatus(nodeId, "STOPPING", false)
		h.systemd.Stop("node" + strconv.Itoa(nodeId))
		h.status.SetNodeStatus(nodeId, "STARTING", false)
		h.systemd.Start("node" + strconv.Itoa(nodeId))
		h.status.SetNodeStatus(nodeId, "FOLLOWER", true)
	}()

	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Node " + strconv.Itoa(nodeId) + " restart initiated",
	})
}

func (h *Handlers) handleStopNode(w http.ResponseWriter, r *http.Request) {
	nodeId, err := h.getNodeId(r)
	if err != nil {
		jsonResponse(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	go func() {
		h.status.SetNodeStatus(nodeId, "STOPPING", false)
		h.systemd.Stop("node" + strconv.Itoa(nodeId))
		h.status.SetNodeStatus(nodeId, "OFFLINE", false)
	}()

	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Node " + strconv.Itoa(nodeId) + " stop initiated",
	})
}

func (h *Handlers) handleStartNode(w http.ResponseWriter, r *http.Request) {
	nodeId, err := h.getNodeId(r)
	if err != nil {
		jsonResponse(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}

	go func() {
		h.status.SetNodeStatus(nodeId, "STARTING", false)
		h.systemd.Start("node" + strconv.Itoa(nodeId))
		h.status.SetNodeStatus(nodeId, "FOLLOWER", true)
	}()

	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Node " + strconv.Itoa(nodeId) + " start initiated",
	})
}

func (h *Handlers) handleStopAllNodes(w http.ResponseWriter, r *http.Request) {
	go func() {
		for i := 0; i < 3; i++ {
			h.status.SetNodeStatus(i, "STOPPING", false)
			h.systemd.Stop("node" + strconv.Itoa(i))
			h.status.SetNodeStatus(i, "OFFLINE", false)
		}
	}()

	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "All nodes stop initiated",
	})
}

func (h *Handlers) handleStartAllNodes(w http.ResponseWriter, r *http.Request) {
	go func() {
		for i := 0; i < 3; i++ {
			h.status.SetNodeStatus(i, "STARTING", false)
			h.systemd.Start("node" + strconv.Itoa(i))
		}
		// Wait and detect leader
		leader := h.cluster.DetectLeader()
		for i := 0; i < 3; i++ {
			if i == leader {
				h.status.SetNodeStatus(i, "LEADER", true)
			} else {
				h.status.SetNodeStatus(i, "FOLLOWER", true)
			}
		}
	}()

	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "All nodes start initiated",
	})
}

// Complex operations
func (h *Handlers) handleRollingUpdate(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.RollingUpdate(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Rolling update initiated",
	})
}

func (h *Handlers) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.Snapshot(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Snapshot initiated",
	})
}

func (h *Handlers) handleRebuildGateway(w http.ResponseWriter, r *http.Request) {
	// Check if restart was requested
	var req struct {
		Restart bool `json:"restart"`
	}
	json.NewDecoder(r.Body).Decode(&req) // ignore error - defaults to false

	if err := h.opsSvc.RebuildGateway(req.Restart); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	msg := "Gateway rebuild initiated"
	if req.Restart {
		msg += " (will restart order & market gateways after build)"
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": msg,
	})
}

func (h *Handlers) handleRebuildCluster(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.RebuildCluster(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Cluster rebuild initiated (builds to staging, use rolling-update to deploy)",
	})
}

// Compact operations
func (h *Handlers) handleCompact(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.Compact(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Archive compaction initiated",
	})
}

func (h *Handlers) handleCompactArchive(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.CompactArchive(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]interface{}{
		"message": "Full archive compaction initiated",
		"warning": "This operation requires brief cluster downtime",
	})
}

func (h *Handlers) handleRollingCleanup(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.RollingArchiveCleanup(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]interface{}{
		"message": "Rolling archive cleanup initiated",
		"info":    "Cluster remains operational throughout",
	})
}

// Auto-snapshot handlers
func (h *Handlers) handleAutoSnapshotGet(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, h.autoSnapshot.ToMap())
}

func (h *Handlers) handleAutoSnapshotPost(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IntervalMinutes int64 `json:"intervalMinutes"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.IntervalMinutes <= 0 {
		jsonResponse(w, http.StatusBadRequest, map[string]string{
			"error": "intervalMinutes must be a positive number",
		})
		return
	}

	h.autoSnapshot.Start(req.IntervalMinutes)
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"status":          "started",
		"intervalMinutes": req.IntervalMinutes,
		"message":         "Auto-snapshot enabled: every " + strconv.FormatInt(req.IntervalMinutes, 10) + " minutes",
	})
}

func (h *Handlers) handleAutoSnapshotDelete(w http.ResponseWriter, r *http.Request) {
	h.autoSnapshot.Stop()
	jsonResponse(w, http.StatusOK, map[string]interface{}{
		"status":  "stopped",
		"message": "Auto-snapshot disabled",
	})
}

// Logs handler
func (h *Handlers) handleLogs(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()

	lines := 50
	if l := query.Get("lines"); l != "" {
		if parsed, err := strconv.Atoi(l); err == nil {
			lines = parsed
			if lines > 500 {
				lines = 500
			}
		}
	}

	if service := query.Get("service"); service != "" {
		jsonResponse(w, http.StatusOK, h.logSvc.GetServiceLogs(service, lines))
		return
	}

	nodeId := 0
	if n := query.Get("node"); n != "" {
		if parsed, err := strconv.Atoi(n); err == nil {
			nodeId = parsed
		}
	}

	jsonResponse(w, http.StatusOK, h.logSvc.GetNodeLogs(nodeId, lines))
}

// Cleanup handler
func (h *Handlers) handleCleanup(w http.ResponseWriter, r *http.Request) {
	result := h.opsSvc.Cleanup()
	status := http.StatusOK
	if success, ok := result["success"].(bool); ok && !success {
		status = http.StatusBadRequest
	}
	jsonResponse(w, status, result)
}

// --- Process Manager Handlers ---

func (h *Handlers) handleProcessList(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, h.procMgr.List())
}

func (h *Handlers) handleProcessSummary(w http.ResponseWriter, r *http.Request) {
	jsonResponse(w, http.StatusOK, h.procMgr.Summary())
}

func (h *Handlers) handleProcessGet(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	info := h.procMgr.Get(name)
	if info == nil {
		jsonResponse(w, http.StatusNotFound, map[string]string{"error": "unknown service: " + name})
		return
	}
	jsonResponse(w, http.StatusOK, info)
}

func (h *Handlers) handleProcessStart(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if err := h.procMgr.Start(name); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": name + " start initiated",
	})
}

func (h *Handlers) handleProcessStop(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if err := h.procMgr.Stop(name); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": name + " stop initiated",
	})
}

func (h *Handlers) handleProcessRestart(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if err := h.procMgr.Restart(name); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": name + " restart initiated",
	})
}

func (h *Handlers) handleProcessForceStop(w http.ResponseWriter, r *http.Request) {
	name := chi.URLParam(r, "name")
	if err := h.procMgr.ForceStop(name); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": name + " force-stop initiated",
	})
}

func (h *Handlers) handleProcessStartAll(w http.ResponseWriter, r *http.Request) {
	go func() {
		// Runs in background — dependency-ordered start takes time
		h.procMgr.StartAll()
	}()
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Start-all initiated (dependency-ordered)",
	})
}

func (h *Handlers) handleProcessStopAll(w http.ResponseWriter, r *http.Request) {
	go func() {
		h.procMgr.StopAll()
	}()
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Stop-all initiated (reverse dependency order)",
	})
}

func (h *Handlers) handleProcessRestartAll(w http.ResponseWriter, r *http.Request) {
	go func() {
		h.procMgr.RestartAll()
	}()
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Restart-all initiated (stop reverse → start forward)",
	})
}

// Self-update: rebuild admin gateway binary and restart via systemd
func (h *Handlers) handleRebuildAdmin(w http.ResponseWriter, r *http.Request) {
	if err := h.opsSvc.RebuildAdmin(); err != nil {
		jsonResponse(w, http.StatusConflict, map[string]string{"error": err.Error()})
		return
	}
	jsonResponse(w, http.StatusAccepted, map[string]string{
		"message": "Admin gateway self-update initiated. Service will restart momentarily.",
	})
}

// Helpers
func (h *Handlers) getNodeId(r *http.Request) (int, error) {
	var req struct {
		NodeId int `json:"nodeId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		return 0, err
	}
	if req.NodeId < 0 || req.NodeId > 2 {
		return 0, &InvalidNodeError{}
	}
	return req.NodeId, nil
}

type InvalidNodeError struct{}

func (e *InvalidNodeError) Error() string {
	return "Invalid nodeId. Must be 0, 1, or 2"
}

func jsonResponse(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}
