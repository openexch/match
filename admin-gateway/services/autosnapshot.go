package services

import (
	"log"
	"sync"
	"time"
)

// AutoSnapshot manages periodic snapshot scheduling
type AutoSnapshot struct {
	mu                sync.RWMutex
	enabled           bool
	intervalMinutes   int64
	lastSnapshotPos   int64
	snapshotCount     int
	stopChan          chan struct{}
	opsSvc            *OperationsService
}

func NewAutoSnapshot(opsSvc *OperationsService) *AutoSnapshot {
	return &AutoSnapshot{
		lastSnapshotPos: -1,
		opsSvc:          opsSvc,
	}
}

// Start begins periodic snapshots at the given interval
func (a *AutoSnapshot) Start(intervalMinutes int64) {
	a.Stop() // Stop existing scheduler

	a.mu.Lock()
	a.enabled = true
	a.intervalMinutes = intervalMinutes
	a.stopChan = make(chan struct{})
	a.mu.Unlock()

	go func() {
		ticker := time.NewTicker(time.Duration(intervalMinutes) * time.Minute)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				a.runSnapshotCycle()
			case <-a.stopChan:
				return
			}
		}
	}()

	log.Printf("Auto-snapshot enabled: every %d minutes", intervalMinutes)
}

// Stop disables periodic snapshots
func (a *AutoSnapshot) Stop() {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.stopChan != nil {
		close(a.stopChan)
		a.stopChan = nil
	}
	a.enabled = false
	a.intervalMinutes = 0
}

// IsEnabled returns whether auto-snapshot is active
func (a *AutoSnapshot) IsEnabled() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.enabled
}

// GetIntervalMinutes returns the current interval
func (a *AutoSnapshot) GetIntervalMinutes() int64 {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.intervalMinutes
}

// SetLastPosition records the last snapshot position
func (a *AutoSnapshot) SetLastPosition(pos int64) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.lastSnapshotPos = pos
}

// GetLastPosition returns the last snapshot position
func (a *AutoSnapshot) GetLastPosition() int64 {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.lastSnapshotPos
}

// runSnapshotCycle takes a snapshot and then runs compaction.
// Every 6th cycle (~30 min at 5-min interval), runs RollingArchiveCleanup instead of Compact.
func (a *AutoSnapshot) runSnapshotCycle() {
	if a.opsSvc.progress.IsRunning() {
		log.Println("Auto-snapshot skipped: another operation in progress")
		return
	}

	log.Println("Auto-snapshot: triggering snapshot...")
	if err := a.opsSvc.Snapshot(); err != nil {
		log.Printf("Auto-snapshot: failed to start snapshot: %v", err)
		return
	}

	if !a.waitForOperation(5 * time.Minute) {
		log.Println("Auto-snapshot: snapshot did not complete in time, skipping compaction")
		return
	}

	a.mu.Lock()
	a.snapshotCount++
	count := a.snapshotCount
	a.mu.Unlock()

	// Every 6th snapshot (~30 min): full rolling archive cleanup (truncates consensus log)
	// Otherwise: lightweight compact (cleans orphaned segments, no downtime)
	if count%6 == 0 {
		log.Println("Auto-snapshot: triggering rolling archive cleanup (every 6th cycle)...")
		if err := a.opsSvc.RollingArchiveCleanup(); err != nil {
			log.Printf("Auto-snapshot: failed to start rolling cleanup: %v", err)
		}
	} else {
		log.Println("Auto-snapshot: triggering compact...")
		if err := a.opsSvc.Compact(); err != nil {
			log.Printf("Auto-snapshot: failed to start compact: %v", err)
		}
	}
}

// waitForOperation polls until the current operation finishes or timeout is reached.
func (a *AutoSnapshot) waitForOperation(timeout time.Duration) bool {
	deadline := time.After(timeout)
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()

	for {
		select {
		case <-deadline:
			return false
		case <-tick.C:
			if !a.opsSvc.progress.IsRunning() {
				return true
			}
		case <-a.stopChan:
			return false
		}
	}
}

// ToMap returns status as a map
func (a *AutoSnapshot) ToMap() map[string]interface{} {
	a.mu.RLock()
	defer a.mu.RUnlock()

	result := map[string]interface{}{
		"enabled":         a.enabled,
		"intervalMinutes": a.intervalMinutes,
	}
	if a.lastSnapshotPos >= 0 {
		result["lastPosition"] = a.lastSnapshotPos
	}
	return result
}
