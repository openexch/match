package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	cfg := ParseFlags()
	metrics := NewMetrics()

	fmt.Println("=== Binance Replay - Realistic Market Simulation ===")
	fmt.Println()
	fmt.Printf("Markets:          %d\n", len(cfg.Markets))
	for _, m := range cfg.Markets {
		fmt.Printf("  - %s -> %s (base: $%.2f, tick: %.5f)\n",
			m.BinanceSymbol, m.MatchMarket, m.BasePrice, m.TickSize)
	}
	fmt.Println()
	fmt.Printf("Order Endpoint:   %s\n", cfg.OrderEndpoint)
	fmt.Printf("HTTP Workers:     %d\n", cfg.HTTPWorkers)
	fmt.Printf("Depth Rate:       %d orders/sec (per market)\n", cfg.DepthOrdersPerSec)
	fmt.Printf("Depth Levels:     %d\n", cfg.DepthLevels)
	fmt.Println()
	fmt.Println("Realism Parameters:")
	fmt.Printf("  Order Size α:   %.2f (power-law exponent)\n", cfg.OrderSizeAlpha)
	fmt.Printf("  Depth Decay λ:  %.2f (exponential rate)\n", cfg.DepthDecayLambda)
	fmt.Printf("  Cancel Rate:    %.1f%% per tick\n", cfg.CancelProbability*100)
	fmt.Printf("  Imbalance:      %.1f%% strength\n", cfg.ImbalanceStrength*100)
	fmt.Printf("  Seed Orders:    %d per market\n", cfg.SeedOrders)
	fmt.Println()

	// Shared order channel for all markets
	orders := make(chan Order, 100000)

	// Create local order books for each market (for realistic behavior)
	orderBooks := make(map[string]*LocalOrderBook)
	for _, market := range cfg.Markets {
		orderBooks[market.MatchMarket] = NewLocalOrderBook()
	}

	// Create context that cancels on interrupt
	ctx, cancel := context.WithCancel(context.Background())

	// Handle signals
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigChan
		log.Println("\nShutting down...")
		cancel()
	}()

	// Start HTTP workers first
	log.Printf("Starting %d HTTP workers...", cfg.HTTPWorkers)
	go OrderSubmitter(ctx, orders, cfg.OrderEndpoint, cfg.HTTPWorkers, metrics)

	// Connect to Binance and start ticker processing
	log.Println("Connecting to Binance for real-time prices...")
	marketTrades := make(map[string]chan BinanceTrade)

	for _, market := range cfg.Markets {
		m := market
		ob := orderBooks[m.MatchMarket]
		trades := make(chan BinanceTrade, 5000)
		tickers := make(chan BinanceBookTicker, 500)
		marketTrades[m.MatchMarket] = trades

		go ConnectTradeStream(ctx, GetTradeStreamURL(m.BinanceSymbol), trades, metrics, m.MatchMarket)
		go ConnectBookTicker(ctx, GetBookTickerURL(m.BinanceSymbol), tickers, metrics, m.MatchMarket)

		// Ticker processor: updates metrics and orderbook mid-price continuously
		go func(market string, ob *LocalOrderBook, tickers chan BinanceBookTicker) {
			for {
				select {
				case <-ctx.Done():
					return
				case ticker := <-tickers:
					mid := ticker.GetMidPrice()
					if mid > 0 {
						metrics.SetMidPrice(market, mid)
						ob.SetMidPrice(mid)
					}
				}
			}
		}(m.MatchMarket, ob, tickers)
	}

	// Wait for Binance connections and first prices
	log.Println("Waiting for real-time prices...")
	time.Sleep(2 * time.Second)

	// Seed orderbook depth using real Binance prices
	log.Println("Seeding realistic orderbook depth...")
	log.Println("  (power-law sizes, exponential levels, real prices)")
	totalSeeded := 0
	for _, market := range cfg.Markets {
		ob := orderBooks[market.MatchMarket]

		// Use real Binance price if available, otherwise fall back to base
		realPrice := metrics.GetMidPrice(market.MatchMarket)
		seedPrice := realPrice
		if seedPrice <= 0 {
			seedPrice = market.BasePrice
			log.Printf("  %s: using base price $%.2f (no Binance data yet)", market.MatchMarket, seedPrice)
		}

		// Update market config with real price for seeding
		marketCfg := market
		marketCfg.BasePrice = seedPrice

		count := SeedRealisticDepth(orders, market.MatchMarket, ob, cfg, &marketCfg)
		totalSeeded += count
		stats := ob.Stats()
		log.Printf("  %s: %d orders at $%.2f | bid/ask: %d/%d | imbalance: %.1f%%",
			market.MatchMarket, count, seedPrice, stats.BidLevels, stats.AskLevels, stats.Imbalance*100)
	}
	log.Printf("Seeding complete: %d total orders", totalSeeded)
	log.Println()

	// Start per-market processing goroutines
	for _, market := range cfg.Markets {
		m := market
		ob := orderBooks[m.MatchMarket]
		trades := marketTrades[m.MatchMarket]

		log.Printf("Starting market: %s", m.MatchMarket)

		// Start simulator (processes Binance trades only - tickers handled separately)
		go SimulateTrades(ctx, trades, orders, m.MatchMarket, ob, cfg, metrics)

		// Start depth builder (background market making)
		if cfg.DepthOrdersPerSec > 0 {
			go DepthBuilder(ctx, orders, m.MatchMarket, ob, cfg, metrics)
		}

		// Start order decay (simulates cancellations)
		go OrderDecay(ctx, ob, m.MatchMarket, cfg)
	}

	// Start metrics reporter (every 2 seconds)
	go ReportMetrics(ctx, metrics, cfg, 2*time.Second)

	// Periodically log order book stats
	go func() {
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				log.Println("Order book stats:")
				for _, market := range cfg.Markets {
					ob := orderBooks[market.MatchMarket]
					stats := ob.Stats()
					log.Printf("  %s: spread=%.4f | bids=%d asks=%d | imbalance=%.1f%%",
						market.MatchMarket, stats.Spread, stats.BidLevels, stats.AskLevels, stats.Imbalance*100)
				}
			}
		}
	}()

	// Wait for shutdown
	<-ctx.Done()

	// Print final statistics
	fmt.Println()
	fmt.Println("=== Final Order Book State ===")
	for _, market := range cfg.Markets {
		ob := orderBooks[market.MatchMarket]
		stats := ob.Stats()
		fmt.Printf("%s:\n", market.MatchMarket)
		fmt.Printf("  Best Bid: %.4f | Best Ask: %.4f | Spread: %.4f\n",
			stats.BestBid, stats.BestAsk, stats.Spread)
		fmt.Printf("  Bid Levels: %d | Ask Levels: %d\n", stats.BidLevels, stats.AskLevels)
		fmt.Printf("  Total Bid Qty: %.4f | Total Ask Qty: %.4f\n",
			stats.TotalBidQty, stats.TotalAskQty)
		fmt.Printf("  Imbalance: %.1f%%\n", stats.Imbalance*100)
	}

	// Give a moment for final metrics
	time.Sleep(100 * time.Millisecond)
}
