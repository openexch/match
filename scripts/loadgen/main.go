package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"os"
	"os/signal"
	"sort"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type Stats struct {
	sent      atomic.Int64
	success   atomic.Int64
	errors    atomic.Int64
	errors503 atomic.Int64 // Service unavailable (transient)
	errors500 atomic.Int64 // Server error (permanent)
	errorsNet atomic.Int64 // Network/connection errors

	mu        sync.Mutex
	latencies []float64
}

func (s *Stats) recordSuccess(latMs float64) {
	s.sent.Add(1)
	s.success.Add(1)
	s.mu.Lock()
	s.latencies = append(s.latencies, latMs)
	s.mu.Unlock()
}

func (s *Stats) recordError() {
	s.sent.Add(1)
	s.errors.Add(1)
}

func (s *Stats) getLatencies() []float64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	cp := make([]float64, len(s.latencies))
	copy(cp, s.latencies)
	return cp
}

type Order struct {
	UserID    string  `json:"userId"`
	Market    string  `json:"market"`
	OrderSide string  `json:"orderSide"`
	OrderType string  `json:"orderType"`
	Price     float64 `json:"price"`
	Quantity  float64 `json:"quantity"`
}

var (
	sides     = []string{"BUY", "SELL"}
	basePrice = 95000.0
)

func randomOrder() []byte {
	side := sides[rand.Intn(2)]
	price := basePrice + rand.NormFloat64()*500
	qty := 0.001 + rand.Float64()*0.099

	o := Order{
		UserID:    fmt.Sprintf("%d", rand.Intn(10000)+1),
		Market:    "BTC-USD",
		OrderSide: side,
		OrderType: "LIMIT",
		Price:     float64(int(price*100)) / 100,
		Quantity:  float64(int(qty*1e6)) / 1e6,
	}
	data, _ := json.Marshal(o)
	return data
}

func percentile(sorted []float64, p float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(float64(len(sorted)) * p)
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func main() {
	rate := flag.Int("rate", 5000, "Target orders per second")
	duration := flag.Int("duration", 600, "Test duration in seconds")
	url := flag.String("url", "http://127.0.0.1:8080/order", "Order endpoint URL")
	workers := flag.Int("workers", 200, "Number of concurrent workers")
	flag.Parse()

	fmt.Printf("Load generator: %d/s for %ds with %d workers\n", *rate, *duration, *workers)
	fmt.Printf("Target: %s\n", *url)
	fmt.Println()

	// HTTP client with aggressive connection reuse
	client := &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			MaxIdleConns:        *workers * 2,
			MaxIdleConnsPerHost: *workers * 2,
			MaxConnsPerHost:     *workers,
			IdleConnTimeout:     90 * time.Second,
			DisableKeepAlives:   false,
			ForceAttemptHTTP2:   false,
		},
	}

	stats := &Stats{}
	stop := make(chan struct{})

	// Graceful shutdown
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-sigCh
		close(stop)
	}()

	// Work channel — buffered to smooth out bursts
	work := make(chan struct{}, *workers*2)

	// Worker pool
	var wg sync.WaitGroup
	for i := 0; i < *workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range work {
				data := randomOrder()
				start := time.Now()
				resp, err := client.Post(*url, "application/json", bytes.NewReader(data))
				latMs := float64(time.Since(start).Microseconds()) / 1000.0

				if err != nil {
					stats.recordError()
					stats.errorsNet.Add(1)
					if stats.errorsNet.Load() <= 3 {
						fmt.Printf("[CONN-ERR] %v\n", err)
					}
					continue
				}

				// Drain and close body for connection reuse
				io.Copy(io.Discard, resp.Body)
				resp.Body.Close()

				if resp.StatusCode >= 200 && resp.StatusCode < 300 {
					stats.recordSuccess(latMs)
				} else {
					stats.recordError()
					if resp.StatusCode == 503 {
						stats.errors503.Add(1)
					} else if resp.StatusCode >= 500 {
						stats.errors500.Add(1)
					}
					if stats.errors.Load() <= 5 {
						fmt.Printf("[HTTP-ERR] %d\n", resp.StatusCode)
					}
				}
			}
		}()
	}

	// Rate limiter — batch approach for higher throughput
	startTime := time.Now()
	endTime := startTime.Add(time.Duration(*duration) * time.Second)

	// Report ticker
	reportTicker := time.NewTicker(5 * time.Second)
	defer reportTicker.Stop()
	lastReportSent := int64(0)
	lastReportTime := startTime

	// Batch parameters: send `batchSize` orders every `batchInterval`
	batchesPerSec := 50
	batchSize := *rate / batchesPerSec
	if batchSize < 1 {
		batchSize = 1
	}
	batchInterval := time.Second / time.Duration(batchesPerSec)
	batchTicker := time.NewTicker(batchInterval)
	defer batchTicker.Stop()

	fmt.Printf("[%s] Starting load... (batch=%d every %v)\n", time.Now().Format("15:04:05"), batchSize, batchInterval)

loop:
	for {
		select {
		case <-stop:
			break loop
		case now := <-batchTicker.C:
			if now.After(endTime) {
				break loop
			}
			for i := 0; i < batchSize; i++ {
				select {
				case work <- struct{}{}:
				default:
					stats.recordError()
				}
			}
		case now := <-reportTicker.C:
			sent := stats.sent.Load()
			success := stats.success.Load()
			errors := stats.errors.Load()
			elapsed := now.Sub(lastReportTime).Seconds()
			intervalSent := sent - lastReportSent
			currentRate := float64(intervalSent) / elapsed

			// Get recent latencies
			lats := stats.getLatencies()
			var p50, p99, avg float64
			if len(lats) > 0 {
				recent := lats
				if len(recent) > 5000 {
					recent = recent[len(recent)-5000:]
				}
				sort.Float64s(recent)
				p50 = percentile(recent, 0.50)
				p99 = percentile(recent, 0.99)
				sum := 0.0
				for _, v := range recent {
					sum += v
				}
				avg = sum / float64(len(recent))
			}

			e503 := stats.errors503.Load()
			e500 := stats.errors500.Load()
			eNet := stats.errorsNet.Load()
			fmt.Printf("[%s] rate=%5.0f/s total=%-8d ok=%-8d err=%-6d (503=%d,500=%d,net=%d) | p50=%.1fms p99=%.1fms avg=%.1fms\n",
				now.Format("15:04:05"), currentRate, sent, success, errors, e503, e500, eNet, p50, p99, avg)

			lastReportSent = sent
			lastReportTime = now
		}
	}

	// Drain remaining work
	close(work)
	wg.Wait()

	// Final report
	totalTime := time.Since(startTime).Seconds()
	totalSent := stats.sent.Load()
	totalSuccess := stats.success.Load()
	totalErrors := stats.errors.Load()
	avgRate := float64(totalSent) / totalTime
	successPct := float64(0)
	if totalSent > 0 {
		successPct = float64(totalSuccess) / float64(totalSent) * 100
	}

	lats := stats.getLatencies()
	sort.Float64s(lats)

	fmt.Println()
	fmt.Println("======================================================================")
	fmt.Println("FINAL RESULTS")
	fmt.Println("======================================================================")
	fmt.Printf("Duration:     %.1fs\n", totalTime)
	fmt.Printf("Total sent:   %d\n", totalSent)
	fmt.Printf("Success:      %d (%.1f%%)\n", totalSuccess, successPct)
	fmt.Printf("Errors:       %d (503=%d, 500=%d, net=%d)\n",
		totalErrors, stats.errors503.Load(), stats.errors500.Load(), stats.errorsNet.Load())
	fmt.Printf("Avg rate:     %.0f/s\n", avgRate)

	if len(lats) > 0 {
		sum := 0.0
		for _, v := range lats {
			sum += v
		}
		fmt.Printf("Latency p50:  %.1fms\n", percentile(lats, 0.50))
		fmt.Printf("Latency p95:  %.1fms\n", percentile(lats, 0.95))
		fmt.Printf("Latency p99:  %.1fms\n", percentile(lats, 0.99))
		fmt.Printf("Latency max:  %.1fms\n", lats[len(lats)-1])
		fmt.Printf("Latency avg:  %.1fms\n", sum/float64(len(lats)))
	}

	fmt.Println("======================================================================")

	// JSON output for machine parsing
	result := map[string]interface{}{
		"duration_s":  fmt.Sprintf("%.1f", totalTime),
		"total_sent":  totalSent,
		"success":     totalSuccess,
		"errors":      totalErrors,
		"avg_rate":    fmt.Sprintf("%.0f", avgRate),
		"success_pct": fmt.Sprintf("%.2f", successPct),
	}
	if len(lats) > 0 {
		result["latency_p50_ms"] = fmt.Sprintf("%.2f", percentile(lats, 0.50))
		result["latency_p95_ms"] = fmt.Sprintf("%.2f", percentile(lats, 0.95))
		result["latency_p99_ms"] = fmt.Sprintf("%.2f", percentile(lats, 0.99))
		result["latency_max_ms"] = fmt.Sprintf("%.2f", lats[len(lats)-1])
	}
	jsonOut, _ := json.Marshal(result)
	fmt.Println(string(jsonOut))
}
