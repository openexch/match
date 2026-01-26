package config

import (
	"os"
	"path/filepath"
)

type Config struct {
	Port        string
	ProjectDir  string
	JarPath     string
	LogDir      string
	ClusterDir  string
}

func Load() *Config {
	projectDir := os.Getenv("MATCH_PROJECT_DIR")
	if projectDir == "" {
		// Default to parent of admin-gateway
		exe, _ := os.Executable()
		projectDir = filepath.Dir(filepath.Dir(exe))
	}

	homeDir, _ := os.UserHomeDir()

	return &Config{
		Port:        getEnvOrDefault("ADMIN_PORT", "8082"),
		ProjectDir:  projectDir,
		JarPath:     filepath.Join(projectDir, "match/target/cluster-engine-1.0.jar"),
		LogDir:      filepath.Join(homeDir, ".local/log/cluster"),
		ClusterDir:  "/dev/shm/aeron-cluster",
	}
}

func getEnvOrDefault(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}
