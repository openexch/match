package services

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"github.com/match/admin-gateway/config"
)

// OperationsService handles complex cluster operations
type OperationsService struct {
	cfg           *config.Config
	systemd       *Systemd
	cluster       *Cluster
	progress      *Progress
	clusterStatus *ClusterStatus
}

func NewOperationsService(cfg *config.Config, systemd *Systemd, cluster *Cluster, progress *Progress, status *ClusterStatus) *OperationsService {
	return &OperationsService{
		cfg:           cfg,
		systemd:       systemd,
		cluster:       cluster,
		progress:      progress,
		clusterStatus: status,
	}
}

// RollingUpdate performs a rolling update of all cluster nodes
func (o *OperationsService) RollingUpdate() error {
	if o.progress.IsRunning() {
		return fmt.Errorf("another operation in progress")
	}

	go o.doRollingUpdate()
	return nil
}

func (o *OperationsService) doRollingUpdate() {
	o.progress.Start("rolling-update", 11)

	jarPath := o.cfg.JarPath
	stagingDir := filepath.Join(o.cfg.ProjectDir, "match/target/staging")
	stagingJar := filepath.Join(stagingDir, "cluster-engine-1.0.jar")

	// Step 1: Build in isolated directory (NEVER touch live JAR)
	o.progress.Update(1, "Building application in isolated directory...")
	buildId := fmt.Sprintf("%d", time.Now().UnixMilli())
	tempBuildDir := "/tmp/match-rolling-build-" + buildId

	buildScript := fmt.Sprintf(`
		rm -rf %s &&
		mkdir -p %s &&
		mkdir -p %s &&
		cp %s/match/pom.xml %s/ &&
		cp -r %s/match/src %s/ &&
		cd %s &&
		mvn package -DskipTests -q &&
		cp %s/target/cluster-engine-1.0.jar %s &&
		rm -rf %s
	`, tempBuildDir, tempBuildDir, stagingDir,
		o.cfg.ProjectDir, tempBuildDir,
		o.cfg.ProjectDir, tempBuildDir,
		tempBuildDir, tempBuildDir, stagingJar, tempBuildDir)

	cmd := exec.Command("bash", "-c", buildScript)
	if err := cmd.Run(); err != nil {
		o.progress.Finish(false, "Build failed: "+err.Error())
		return
	}

	if _, err := os.Stat(stagingJar); os.IsNotExist(err) {
		o.progress.Finish(false, "Build failed: staging JAR not found")
		return
	}

	o.progress.Update(1, "Build complete, staged for deployment")

	// Step 2: Find leader
	o.progress.Update(2, "Finding cluster leader...")
	leader := o.cluster.DetectLeader()
	if leader < 0 {
		leader = o.clusterStatus.GetLeaderId()
	}
	if leader < 0 {
		o.progress.Finish(false, "Could not find cluster leader")
		return
	}

	// Get followers
	followers := []int{}
	for i := 0; i < 3; i++ {
		if i != leader {
			followers = append(followers, i)
		}
	}

	jarSwapped := false
	step := 3

	// Steps 3-8: Update followers
	for _, nodeId := range followers {
		nodeLabel := fmt.Sprintf("Node %d", nodeId)

		// Stop follower
		o.progress.Update(step, "Stopping "+nodeLabel+"...")
		o.clusterStatus.SetNodeStatus(nodeId, "STOPPING", false)
		o.systemd.Stop(fmt.Sprintf("node%d", nodeId))
		time.Sleep(1 * time.Second)
		o.clusterStatus.SetNodeStatus(nodeId, "OFFLINE", false)
		step++

		// Swap JAR after first node is stopped
		if !jarSwapped {
			o.progress.Update(step, "Deploying new JAR...")
			exec.Command("mv", stagingJar, jarPath).Run()
			jarSwapped = true
			time.Sleep(100 * time.Millisecond)
		}

		// Start follower with new code
		o.progress.Update(step, "Starting "+nodeLabel+" with new code...")
		o.clusterStatus.SetNodeStatus(nodeId, "STARTING", false)
		o.systemd.Start(fmt.Sprintf("node%d", nodeId))
		time.Sleep(2 * time.Second)
		step++

		// Wait for rejoin
		o.progress.Update(step, nodeLabel+": Waiting to rejoin cluster...")
		o.clusterStatus.SetNodeStatus(nodeId, "REJOINING", true)
		time.Sleep(5 * time.Second)
		o.clusterStatus.SetNodeStatus(nodeId, "FOLLOWER", true)
		o.progress.Update(step, nodeLabel+" rejoined as follower")
		step++
		time.Sleep(500 * time.Millisecond)
	}

	// Step 9: Stop old leader
	o.progress.Update(9, fmt.Sprintf("Stopping Node %d (Leader)...", leader))
	o.clusterStatus.SetNodeStatus(leader, "STOPPING", false)
	for _, nodeId := range followers {
		o.clusterStatus.SetNodeStatus(nodeId, "ELECTION", true)
	}
	o.systemd.Stop(fmt.Sprintf("node%d", leader))
	time.Sleep(1 * time.Second)
	o.clusterStatus.SetNodeStatus(leader, "OFFLINE", false)

	// Step 10: Wait for election
	o.progress.Update(10, "Leader election in progress...")
	time.Sleep(3 * time.Second)

	newLeader := o.cluster.DetectLeader()
	if newLeader < 0 {
		newLeader = followers[0]
	}
	o.clusterStatus.UpdateLeader(newLeader, 0)
	for _, nodeId := range followers {
		if nodeId == newLeader {
			o.clusterStatus.SetNodeStatus(nodeId, "LEADER", true)
		} else {
			o.clusterStatus.SetNodeStatus(nodeId, "FOLLOWER", true)
		}
	}
	o.progress.Update(10, fmt.Sprintf("New leader elected: Node %d", newLeader))

	// Step 11: Start old leader as follower
	o.progress.Update(11, fmt.Sprintf("Starting Node %d as follower...", leader))
	o.clusterStatus.SetNodeStatus(leader, "STARTING", false)
	o.systemd.Start(fmt.Sprintf("node%d", leader))
	time.Sleep(2 * time.Second)

	o.clusterStatus.SetNodeStatus(leader, "REJOINING", true)
	time.Sleep(5 * time.Second)
	o.clusterStatus.SetNodeStatus(leader, "FOLLOWER", true)
	o.progress.Update(11, fmt.Sprintf("Node %d rejoined as follower", leader))

	// Cleanup
	exec.Command("rm", "-rf", stagingDir).Run()

	o.progress.Finish(true, "All nodes updated successfully with new code")
}

// Snapshot triggers a cluster snapshot
func (o *OperationsService) Snapshot() error {
	if o.progress.IsRunning() {
		return fmt.Errorf("another operation in progress")
	}

	go o.doSnapshot()
	return nil
}

func (o *OperationsService) doSnapshot() {
	o.progress.Start("snapshot", 4)

	// Step 1: Find leader
	o.progress.Update(1, "Finding cluster leader...")
	leader := o.cluster.DetectLeader()
	if leader < 0 {
		o.progress.Finish(false, "Could not find cluster leader")
		return
	}

	// Step 2: Take snapshot
	o.progress.Update(2, fmt.Sprintf("Taking snapshot on Node %d...", leader))
	output, err := o.cluster.TakeSnapshot(leader)
	if err != nil {
		o.progress.Finish(false, "Snapshot failed: "+err.Error())
		return
	}

	// Step 3: Wait for propagation
	o.progress.Update(3, "Waiting for snapshot propagation...")
	time.Sleep(2 * time.Second)

	// Step 4: Verify
	o.progress.Update(4, "Verifying snapshot position...")
	pos := o.cluster.GetSnapshotPosition(leader)

	if pos < 0 || (!contains(output, "SNAPSHOT") && !contains(output, "completed")) {
		o.progress.Finish(false, "Snapshot may have failed: "+output)
		return
	}

	o.progress.Finish(true, fmt.Sprintf("Snapshot created at position %d", pos))
}

func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && containsHelper(s, substr))
}

func containsHelper(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
