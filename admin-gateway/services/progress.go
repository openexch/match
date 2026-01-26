package services

import (
	"sync"
	"time"
)

// Progress tracks long-running operation status
type Progress struct {
	mu            sync.RWMutex
	Operation     string    `json:"operation,omitempty"`
	CurrentStep   int       `json:"currentStep"`
	TotalSteps    int       `json:"totalSteps"`
	Status        string    `json:"status,omitempty"`
	Complete      bool      `json:"complete"`
	Error         bool      `json:"error"`
	StartTime     time.Time `json:"-"`
	ElapsedMs     int64     `json:"elapsedMs,omitempty"`
}

func NewProgress() *Progress {
	return &Progress{}
}

func (p *Progress) Start(operation string, totalSteps int) {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	p.Operation = operation
	p.TotalSteps = totalSteps
	p.CurrentStep = 0
	p.Status = ""
	p.Complete = false
	p.Error = false
	p.StartTime = time.Now()
}

func (p *Progress) Update(step int, status string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	p.CurrentStep = step
	p.Status = status
}

func (p *Progress) Finish(success bool, status string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	p.Complete = true
	p.Error = !success
	p.Status = status
	p.CurrentStep = p.TotalSteps
}

func (p *Progress) Reset() {
	p.mu.Lock()
	defer p.mu.Unlock()
	
	p.Operation = ""
	p.CurrentStep = 0
	p.TotalSteps = 0
	p.Status = ""
	p.Complete = false
	p.Error = false
}

func (p *Progress) IsRunning() bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.Operation != "" && !p.Complete
}

func (p *Progress) ToMap() map[string]interface{} {
	p.mu.RLock()
	defer p.mu.RUnlock()
	
	result := map[string]interface{}{
		"currentStep": p.CurrentStep,
		"totalSteps":  p.TotalSteps,
		"complete":    p.Complete,
		"error":       p.Error,
	}
	
	if p.Operation != "" {
		result["operation"] = p.Operation
	}
	if p.Status != "" {
		result["status"] = p.Status
	}
	if !p.StartTime.IsZero() {
		result["elapsedMs"] = time.Since(p.StartTime).Milliseconds()
	}
	if p.TotalSteps > 0 {
		result["progress"] = int(float64(p.CurrentStep) / float64(p.TotalSteps) * 100)
	}
	
	return result
}
