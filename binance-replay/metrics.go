package main

import (
	"context"
	"fmt"
	"log"
	"sync"
	"sync/atomic"
	"time"
)

// MarketMetrics holds per-market statistics
type MarketMetrics struct {
	binanceTrades   atomic.Int64
	ordersSubmitted atomic.Int64
	depthOrders     atomic.Int64
	errors          atomic.Int64
	dropped         atomic.Int64
	midPrice        atomic.Value // float64
	connected       atomic.Bool

	// For rate calculation
	lastBinanceTrades   int64
	lastOrdersSubmitted int64
}

// Metrics tracks statistics for the load test (all markets)
type Metrics struct {
	mu      sync.RWMutex
	markets map[string]*MarketMetrics

	// Global counters
	totalOrders atomic.Int64
	totalErrors atomic.Int64
}

func NewMetrics() *Metrics {
	return &Metrics{
		markets: make(map[string]*MarketMetrics),
	}
}

func (m *Metrics) getMarket(market string) *MarketMetrics {
	m.mu.RLock()
	mm := m.markets[market]
	m.mu.RUnlock()
	if mm != nil {
		return mm
	}

	// Create new market metrics
	m.mu.Lock()
	defer m.mu.Unlock()
	// Double-check after acquiring write lock
	if mm = m.markets[market]; mm != nil {
		return mm
	}
	mm = &MarketMetrics{}
	mm.midPrice.Store(0.0)
	m.markets[market] = mm
	return mm
}

func (m *Metrics) IncrementBinanceTrades(market string) {
	m.getMarket(market).binanceTrades.Add(1)
}

func (m *Metrics) IncrementOrdersSubmitted() {
	m.totalOrders.Add(1)
}

func (m *Metrics) IncrementDepthOrders(market string) {
	m.getMarket(market).depthOrders.Add(1)
}

func (m *Metrics) IncrementErrors() {
	m.totalErrors.Add(1)
}

func (m *Metrics) IncrementDropped(market string) {
	m.getMarket(market).dropped.Add(1)
}

func (m *Metrics) SetMidPrice(market string, price float64) {
	m.getMarket(market).midPrice.Store(price)
}

func (m *Metrics) GetMidPrice(market string) float64 {
	mm := m.getMarket(market)
	if v := mm.midPrice.Load(); v != nil {
		return v.(float64)
	}
	return 0.0
}

func (m *Metrics) SetConnected(market string, connected bool) {
	m.getMarket(market).connected.Store(connected)
}

func (m *Metrics) IsConnected(market string) bool {
	return m.getMarket(market).connected.Load()
}

// GetMarketTotals returns totals for a specific market
func (m *Metrics) GetMarketTotals(market string) (binance, depth, dropped int64, midPrice float64, connected bool) {
	mm := m.getMarket(market)
	return mm.binanceTrades.Load(),
		mm.depthOrders.Load(),
		mm.dropped.Load(),
		mm.GetMidPrice(),
		mm.connected.Load()
}

func (mm *MarketMetrics) GetMidPrice() float64 {
	if v := mm.midPrice.Load(); v != nil {
		return v.(float64)
	}
	return 0.0
}

// ReportMetrics periodically prints metrics to the console
func ReportMetrics(ctx context.Context, metrics *Metrics, cfg *Config, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	// Track last values for rate calculation
	lastOrders := int64(0)
	lastBinance := make(map[string]int64)

	for {
		select {
		case <-ctx.Done():
			// Print final stats
			printFinalStats(metrics, cfg)
			return
		case <-ticker.C:
			// Calculate global order rate
			totalOrders := metrics.totalOrders.Load()
			ordersRate := totalOrders - lastOrders
			lastOrders = totalOrders

			// Print header
			log.Printf("Orders: %d/s (%d total) | Errors: %d",
				ordersRate/int64(interval.Seconds()),
				totalOrders,
				metrics.totalErrors.Load(),
			)

			// Print per-market stats
			for _, m := range cfg.Markets {
				market := m.MatchMarket
				binance, depth, dropped, midPrice, connected := metrics.GetMarketTotals(market)

				// Calculate rate
				lastB := lastBinance[market]
				binanceRate := binance - lastB
				lastBinance[market] = binance

				status := "OK"
				if !connected {
					status = "DISC"
				}

				log.Printf("  [%s] %s: trades=%d/s, depth=%d, dropped=%d, mid=$%.2f",
					status, market,
					binanceRate/int64(interval.Seconds()),
					depth, dropped, midPrice)
			}
		}
	}
}

func printFinalStats(metrics *Metrics, cfg *Config) {
	fmt.Println()
	fmt.Println("=== Final Statistics ===")
	fmt.Printf("Total orders submitted: %d\n", metrics.totalOrders.Load())
	fmt.Printf("Total errors:           %d\n", metrics.totalErrors.Load())
	fmt.Println()

	fmt.Println("Per-market statistics:")
	for _, m := range cfg.Markets {
		market := m.MatchMarket
		binance, depth, dropped, midPrice, _ := metrics.GetMarketTotals(market)
		fmt.Printf("  %s:\n", market)
		fmt.Printf("    Binance trades:    %d\n", binance)
		fmt.Printf("    Depth orders:      %d\n", depth)
		fmt.Printf("    Dropped:           %d\n", dropped)
		fmt.Printf("    Last mid price:    $%.2f\n", midPrice)
	}
}
