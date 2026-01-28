package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/match/admin-gateway/config"
	"github.com/match/admin-gateway/handlers"
	"github.com/match/admin-gateway/services"
)

func main() {
	// Load configuration
	cfg := config.Load()

	// Initialize services
	systemd := services.NewSystemd()
	cluster := services.NewCluster(cfg)
	progress := services.NewProgress()
	clusterStatus := services.NewClusterStatus()
	procMgr := services.NewProcessManager(cfg, systemd)

	statusSvc := services.NewStatusService(cfg, systemd, cluster, clusterStatus)
	opsSvc := services.NewOperationsService(cfg, systemd, cluster, progress, clusterStatus)
	autoSnapshot := services.NewAutoSnapshot(opsSvc)
	logSvc := services.NewLogService(cfg)

	// Initialize handlers
	h := handlers.New(statusSvc, opsSvc, systemd, cluster, progress, clusterStatus, autoSnapshot, logSvc, procMgr)

	// Setup router
	r := chi.NewRouter()
	r.Use(middleware.Logger)
	r.Use(middleware.Recoverer)
	r.Use(middleware.RealIP)

	h.RegisterRoutes(r)

	// Start server
	addr := ":" + cfg.Port
	log.Printf("🚀 Admin Gateway starting on %s", addr)
	log.Printf("   Project: %s", cfg.ProjectDir)
	log.Printf("   JAR: %s", cfg.JarPath)

	// Graceful shutdown
	server := &http.Server{
		Addr:    addr,
		Handler: r,
	}

	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		<-sigChan
		log.Println("Shutting down...")
		procMgr.Shutdown()
		statusSvc.Stop()
		server.Close()
	}()

	if err := server.ListenAndServe(); err != http.ErrServerClosed {
		fmt.Fprintf(os.Stderr, "Server error: %v\n", err)
		os.Exit(1)
	}
}
