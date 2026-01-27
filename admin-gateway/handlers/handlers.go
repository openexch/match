package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"
	"github.com/match/admin-gateway/services"
)

type Handlers struct {
	statusSvc *services.StatusService
	opsSvc    *services.OperationsService
	systemd   *services.Systemd
	cluster   *services.Cluster
	progress  *services.Progress
	status    *services.ClusterStatus
}

func New(
	statusSvc *services.StatusService,
	opsSvc *services.OperationsService,
	systemd *services.Systemd,
	cluster *services.Cluster,
	progress *services.Progress,
	status *services.ClusterStatus,
) *Handlers {
	return &Handlers{
		statusSvc: statusSvc,
		opsSvc:    opsSvc,
		systemd:   systemd,
		cluster:   cluster,
		progress:  progress,
		status:    status,
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

	// Backup operations
	r.Post("/api/admin/stop-backup", h.handleServiceAction("backup", "stop"))
	r.Post("/api/admin/start-backup", h.handleServiceAction("backup", "start"))
	r.Post("/api/admin/restart-backup", h.handleServiceAction("backup", "restart"))

	// Gateway operations (all gateways)
	r.Post("/api/admin/stop-gateway", h.handleAllGateways("stop"))
	r.Post("/api/admin/start-gateway", h.handleAllGateways("start"))
	r.Post("/api/admin/restart-gateway", h.handleAllGateways("restart"))

	// Individual gateway operations
	r.Post("/api/admin/stop-market-gateway", h.handleServiceAction("market", "stop"))
	r.Post("/api/admin/start-market-gateway", h.handleServiceAction("market", "start"))
	r.Post("/api/admin/restart-market-gateway", h.handleServiceAction("market", "restart"))
	r.Post("/api/admin/stop-order-gateway", h.handleServiceAction("order", "stop"))
	r.Post("/api/admin/start-order-gateway", h.handleServiceAction("order", "start"))
	r.Post("/api/admin/restart-order-gateway", h.handleServiceAction("order", "restart"))

	// Complex operations
	r.Post("/api/admin/rolling-update", h.handleRollingUpdate)
	r.Post("/api/admin/snapshot", h.handleSnapshot)

	// Build operations (multi-module safe)
	r.Post("/api/admin/rebuild-gateway", h.handleRebuildGateway)
	r.Post("/api/admin/rebuild-cluster", h.handleRebuildCluster)

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

// Service action handler factory
func (h *Handlers) handleServiceAction(service, action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		go func() {
			switch action {
			case "stop":
				h.systemd.Stop(service)
			case "start":
				h.systemd.Start(service)
			case "restart":
				h.systemd.Restart(service)
			}
		}()

		jsonResponse(w, http.StatusAccepted, map[string]string{
			"message": service + " " + action + " initiated",
		})
	}
}

// All gateways handler factory
func (h *Handlers) handleAllGateways(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		go func() {
			for _, svc := range []string{"market", "order"} {
				switch action {
				case "stop":
					h.systemd.Stop(svc)
				case "start":
					h.systemd.Start(svc)
				case "restart":
					h.systemd.Restart(svc)
				}
			}
		}()

		jsonResponse(w, http.StatusAccepted, map[string]string{
			"message": "All gateways " + action + " initiated",
		})
	}
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
