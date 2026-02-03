package services

import (
	"os/exec"
	"strconv"
	"strings"
)

// Systemd wraps systemctl commands for admin gateway self-management ONLY.
// Cluster nodes and gateways are managed by ProcessManager, NOT systemd.
// This struct is ONLY used by RebuildAdmin() for admin gateway self-restart.
// DO NOT use this for cluster node operations.
type Systemd struct{}

func NewSystemd() *Systemd {
	return &Systemd{}
}

func (s *Systemd) IsActive(service string) bool {
	cmd := exec.Command("systemctl", "--user", "is-active", service)
	output, err := cmd.Output()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(output)) == "active"
}

func (s *Systemd) GetPID(service string) int {
	cmd := exec.Command("systemctl", "--user", "show", "-p", "MainPID", "--value", service)
	output, err := cmd.Output()
	if err != nil {
		return 0
	}
	pid, _ := strconv.Atoi(strings.TrimSpace(string(output)))
	return pid
}

func (s *Systemd) Start(service string) error {
	return exec.Command("systemctl", "--user", "start", service).Run()
}

func (s *Systemd) Stop(service string) error {
	return exec.Command("systemctl", "--user", "stop", service).Run()
}

func (s *Systemd) Restart(service string) error {
	return exec.Command("systemctl", "--user", "restart", service).Run()
}
