package services

import (
	"fmt"
	"sync"
	"time"
)

// AutoSnapshot manages periodic snapshot scheduling
type AutoSnapshot struct {
	mu                sync.RWMutex
	enabled           bool
	intervalMinutes   int64
	lastSnapshotPos   int64
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
				if !a.opsSvc.progress.IsRunning() {
					fmt.Println("Auto-snapshot: triggering snapshot...")
					a.opsSvc.Snapshot()
				} else {
					fmt.Println("Auto-snapshot skipped: another operation in progress")
				}
			case <-a.stopChan:
				return
			}
		}
	}()

	fmt.Printf("Auto-snapshot enabled: every %d minutes\n", intervalMinutes)
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
