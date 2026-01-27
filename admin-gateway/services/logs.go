package services

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"

	"github.com/match/admin-gateway/config"
)

// LogService reads log files from the cluster log directory
type LogService struct {
	logDir string
}

func NewLogService(cfg *config.Config) *LogService {
	return &LogService{logDir: cfg.LogDir}
}

// GetNodeLogs returns the last N lines from a node's log file
func (l *LogService) GetNodeLogs(nodeId, lines int) map[string]interface{} {
	return l.getLogFile(fmt.Sprintf("node%d", nodeId), lines, map[string]interface{}{"node": nodeId})
}

// GetServiceLogs returns the last N lines from a service's log file
func (l *LogService) GetServiceLogs(service string, lines int) map[string]interface{} {
	// Map service names to log file names
	logName := service
	switch service {
	case "market-gateway":
		logName = "market"
	case "order-gateway":
		logName = "order"
	case "admin-gateway":
		logName = "admin"
	}

	return l.getLogFile(logName, lines, map[string]interface{}{"service": service})
}

func (l *LogService) getLogFile(name string, lines int, extra map[string]interface{}) map[string]interface{} {
	result := map[string]interface{}{}
	for k, v := range extra {
		result[k] = v
	}

	logPath := filepath.Join(l.logDir, name+".log")
	file, err := os.Open(logPath)
	if err != nil {
		result["logs"] = []string{}
		result["error"] = "Log file not found: " + logPath
		return result
	}
	defer file.Close()

	// Read all lines (efficient enough for log files)
	var allLines []string
	scanner := bufio.NewScanner(file)
	// Increase buffer for long lines
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		allLines = append(allLines, scanner.Text())
	}

	// Cap lines at 500
	if lines > 500 {
		lines = 500
	}

	start := len(allLines) - lines
	if start < 0 {
		start = 0
	}

	result["logs"] = allLines[start:]
	result["totalLines"] = len(allLines)
	return result
}
