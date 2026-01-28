package services

import (
	"sync"
	"time"

	"github.com/match/admin-gateway/config"
)

// ClusterStatus tracks the state of cluster nodes
type ClusterStatus struct {
	mu               sync.RWMutex
	nodeStatus       [3]string
	nodeRunning      [3]bool
	leaderId         int
	leadershipTermId int64
}

func NewClusterStatus() *ClusterStatus {
	return &ClusterStatus{
		leaderId: -1,
	}
}

func (cs *ClusterStatus) SetNodeStatus(nodeId int, status string, running bool) {
	cs.mu.Lock()
	defer cs.mu.Unlock()
	if nodeId >= 0 && nodeId < 3 {
		cs.nodeStatus[nodeId] = status
		cs.nodeRunning[nodeId] = running
	}
}

func (cs *ClusterStatus) GetNodeStatus(nodeId int) string {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	if nodeId >= 0 && nodeId < 3 {
		return cs.nodeStatus[nodeId]
	}
	return ""
}

func (cs *ClusterStatus) UpdateLeader(leaderId int, termId int64) {
	cs.mu.Lock()
	defer cs.mu.Unlock()
	cs.leaderId = leaderId
	cs.leadershipTermId = termId
}

func (cs *ClusterStatus) GetLeaderId() int {
	cs.mu.RLock()
	defer cs.mu.RUnlock()
	return cs.leaderId
}

// StatusService provides cluster status information with background caching
type StatusService struct {
	cfg           *config.Config
	systemd       *Systemd
	cluster       *Cluster
	clusterStatus *ClusterStatus

	// Cached status
	cacheMu     sync.RWMutex
	cachedStatus map[string]interface{}
	lastUpdate   time.Time
	
	// Background poller
	pollInterval time.Duration
	stopChan     chan struct{}
}

func NewStatusService(cfg *config.Config, systemd *Systemd, cluster *Cluster, status *ClusterStatus) *StatusService {
	s := &StatusService{
		cfg:           cfg,
		systemd:       systemd,
		cluster:       cluster,
		clusterStatus: status,
		pollInterval:  2 * time.Second, // Poll every 2 seconds
		stopChan:      make(chan struct{}),
	}
	
	// Initial fetch
	s.refreshStatus()
	
	// Start background poller
	go s.backgroundPoller()
	
	return s
}

func (s *StatusService) Stop() {
	close(s.stopChan)
}

func (s *StatusService) backgroundPoller() {
	ticker := time.NewTicker(s.pollInterval)
	defer ticker.Stop()
	
	for {
		select {
		case <-ticker.C:
			s.refreshStatus()
		case <-s.stopChan:
			return
		}
	}
}

func (s *StatusService) refreshStatus() {
	status := s.fetchStatus()
	
	s.cacheMu.Lock()
	s.cachedStatus = status
	s.lastUpdate = time.Now()
	s.cacheMu.Unlock()
}

// GetStatus returns cached status (instant response)
func (s *StatusService) GetStatus() map[string]interface{} {
	s.cacheMu.RLock()
	defer s.cacheMu.RUnlock()
	
	if s.cachedStatus == nil {
		// Fallback if cache not ready
		return s.fetchStatus()
	}
	
	// Add cache age info
	result := make(map[string]interface{})
	for k, v := range s.cachedStatus {
		result[k] = v
	}
	result["cacheAgeMs"] = time.Since(s.lastUpdate).Milliseconds()
	
	return result
}

// fetchStatus does the actual work (expensive - calls ClusterTool)
func (s *StatusService) fetchStatus() map[string]interface{} {
	// Detect leader (expensive - spawns JVM)
	leader := s.cluster.DetectLeader()
	if leader >= 0 {
		s.clusterStatus.UpdateLeader(leader, 0)
	} else {
		leader = s.clusterStatus.GetLeaderId()
	}

	// Build nodes status
	nodes := make([]map[string]interface{}, 3)
	for i := 0; i < 3; i++ {
		node := map[string]interface{}{
			"id": i,
		}

		serviceName := "node" + string(rune('0'+i))
		isActive := s.systemd.IsActive(serviceName)

		if isActive {
			node["running"] = true
			node["pid"] = s.systemd.GetPID(serviceName)
			
			// Check tracked status for transitional states
			tracked := s.clusterStatus.GetNodeStatus(i)
			if tracked == "STOPPING" || tracked == "STARTING" || 
			   tracked == "REJOINING" || tracked == "ELECTION" {
				node["role"] = tracked
			} else if i == leader {
				node["role"] = "LEADER"
			} else {
				node["role"] = "FOLLOWER"
			}
		} else {
			node["running"] = false
			node["role"] = "OFFLINE"
		}

		// Archive and log info
		if archiveSize := s.cluster.GetArchiveSize(i); archiveSize >= 0 {
			node["archiveBytes"] = archiveSize
		}
		if diskSize := s.cluster.GetArchiveDiskUsage(i); diskSize >= 0 {
			node["archiveDiskBytes"] = diskSize
		}
		// Get log and snapshot positions in one call (avoids double JVM spawn)
		logPos, snapPos := s.cluster.GetLogAndSnapshotPositions(i)
		if logPos >= 0 {
			node["logPosition"] = logPos
		}
		if snapPos >= 0 {
			node["snapshotPosition"] = snapPos
		}

		nodes[i] = node
	}

	// Build gateways status (cheap - just systemctl)
	gateways := map[string]interface{}{
		"market": map[string]interface{}{
			"running": s.systemd.IsActive("market"),
			"port":    8081,
		},
		"admin": map[string]interface{}{
			"running": true, // We're always running
			"port":    8082,
		},
		"order": map[string]interface{}{
			"running": s.systemd.IsActive("order"),
			"port":    8080,
		},
	}

	// Check backup
	backup := map[string]interface{}{
		"running": s.systemd.IsActive("backup"),
	}

	return map[string]interface{}{
		"leader":   leader,
		"nodes":    nodes,
		"gateways": gateways,
		"backup":   backup,
		"gateway": map[string]interface{}{ // Legacy field
			"running": s.systemd.IsActive("order"),
			"port":    8080,
		},
		"autoSnapshot": map[string]interface{}{
			"enabled":         false,
			"intervalMinutes": 0,
		},
	}
}
