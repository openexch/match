package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"sync"
	"time"
)

// OrderSubmitter starts worker goroutines that submit orders via HTTP
func OrderSubmitter(ctx context.Context, orders <-chan Order, endpoint string, workers int, metrics *Metrics) {
	client := &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			MaxIdleConns:        workers * 2,
			MaxIdleConnsPerHost: workers * 2,
			IdleConnTimeout:     30 * time.Second,
		},
	}

	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			submitWorker(ctx, orders, endpoint, client, metrics)
		}(i)
	}

	// Wait for all workers when context is done
	<-ctx.Done()
	wg.Wait()
}

func submitWorker(ctx context.Context, orders <-chan Order, endpoint string, client *http.Client, metrics *Metrics) {
	for {
		select {
		case <-ctx.Done():
			return
		case order, ok := <-orders:
			if !ok {
				return
			}
			submitOrder(ctx, order, endpoint, client, metrics)
		}
	}
}

func submitOrder(ctx context.Context, order Order, endpoint string, client *http.Client, metrics *Metrics) {
	body, err := json.Marshal(order)
	if err != nil {
		metrics.IncrementErrors()
		return
	}

	req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(body))
	if err != nil {
		metrics.IncrementErrors()
		return
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		metrics.IncrementErrors()
		return
	}
	defer resp.Body.Close()

	// Drain body to allow connection reuse
	io.Copy(io.Discard, resp.Body)

	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		metrics.IncrementOrdersSubmitted()
	} else {
		metrics.IncrementErrors()
	}
}
