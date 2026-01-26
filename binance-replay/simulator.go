package main

import (
	"context"
	"fmt"
	"math"
	"math/rand"
	"sync/atomic"
	"time"
)

var userIDCounter atomic.Int64

func init() {
	userIDCounter.Store(1000)
}

func nextUserID() string {
	return fmt.Sprintf("%d", userIDCounter.Add(1))
}

// SimulateTrades processes trades from Binance and generates orders
// Tickers are handled separately by the ticker processor goroutine
func SimulateTrades(ctx context.Context, trades <-chan BinanceTrade,
	orders chan<- Order, market string, ob *LocalOrderBook, cfg *Config, metrics *Metrics) {

	marketCfg := cfg.GetMarketConfig(market)
	if marketCfg == nil {
		return
	}

	// Process trades - generates realistic order flow
	for {
		select {
		case <-ctx.Done():
			return
		case trade := <-trades:
			generateOrdersFromTrade(trade, orders, market, ob, cfg, marketCfg, metrics)
		}
	}
}

// Simulate processes trades from Binance and generates orders (legacy, includes ticker handling)
func Simulate(ctx context.Context, trades <-chan BinanceTrade, tickers <-chan BinanceBookTicker,
	orders chan<- Order, market string, ob *LocalOrderBook, cfg *Config, metrics *Metrics) {

	marketCfg := cfg.GetMarketConfig(market)
	if marketCfg == nil {
		return
	}

	// Update mid price from book ticker
	go func() {
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
	}()

	// Process trades - generates realistic order flow
	for {
		select {
		case <-ctx.Done():
			return
		case trade := <-trades:
			generateOrdersFromTrade(trade, orders, market, ob, cfg, marketCfg, metrics)
		}
	}
}

// generateOrdersFromTrade converts a Binance trade into matching engine orders
// Uses power-law sizes and exponential price placement
func generateOrdersFromTrade(trade BinanceTrade, orders chan<- Order, market string,
	ob *LocalOrderBook, cfg *Config, marketCfg *MarketConfig, metrics *Metrics) {

	price := trade.GetPrice()
	qty := trade.GetQuantity()

	if price <= 0 || qty <= 0 {
		return
	}

	// Update mid-price
	metrics.SetMidPrice(market, price)
	ob.SetMidPrice(price)

	// Trade consumes liquidity from one side
	if trade.BuyerMaker {
		ob.RemoveQuantity("BID", price, qty*0.3)
	} else {
		ob.RemoveQuantity("ASK", price, qty*0.3)
	}

	// Get current imbalance for Avellaneda-Stoikov style quoting
	imbalance := ob.GetImbalanceNearSpread(5)

	// Variable number of replacement orders based on trade size
	// Larger trades trigger more market maker response
	sizeMultiplier := math.Log10(qty/marketCfg.MinQty + 1)
	baseOrders := 2 + rand.Intn(3)
	numOrders := int(float64(baseOrders) * (1 + sizeMultiplier*0.3))
	if numOrders > 8 {
		numOrders = 8
	}
	for i := 0; i < numOrders; i++ {
		// Decide side based on imbalance (counter-balance)
		bidProb := 0.5 + ImbalanceSkew(imbalance, cfg.ImbalanceStrength)
		side := "ASK"
		if rand.Float64() < bidProb {
			side = "BID"
		}

		// Exponential level placement (clustered near trade price)
		level := ExponentialLevel(0.5) // Tighter clustering for trade-driven
		if level > 20 {
			level = 20
		}

		// Calculate price with tick size
		spreadTicks := float64(level) * marketCfg.TickSize * (1 + rand.Float64()*0.5)
		var orderPrice float64
		if side == "BID" {
			orderPrice = price - spreadTicks
		} else {
			orderPrice = price + spreadTicks
		}
		orderPrice = RoundToTick(orderPrice, marketCfg.TickSize)

		// Power-law order size
		orderQty := ParetoQuantity(marketCfg.MinQty, cfg.OrderSizeAlpha)
		orderQty = ClampQuantity(orderQty, marketCfg.MinQty, marketCfg.MinQty*500)

		order := Order{
			UserID:     nextUserID(),
			Market:     market,
			OrderType:  "LIMIT",
			OrderSide:  side,
			Price:      orderPrice,
			Quantity:   orderQty,
			TotalPrice: orderPrice * orderQty,
		}

		select {
		case orders <- order:
			ob.AddOrder(side, orderPrice, orderQty)
		default:
			// Channel full
		}
	}

	// Occasionally generate a market order (taker simulation)
	if rand.Float32() < 0.3 {
		var order Order
		if trade.BuyerMaker {
			// Sell market order
			order = Order{
				UserID:     nextUserID(),
				Market:     market,
				OrderType:  "MARKET",
				OrderSide:  "ASK",
				Price:      0,
				Quantity:   ParetoQuantity(marketCfg.MinQty, cfg.OrderSizeAlpha) * 0.5,
				TotalPrice: 0,
			}
		} else {
			// Buy market order
			budget := price * ParetoQuantity(marketCfg.MinQty, cfg.OrderSizeAlpha) * 0.5
			order = Order{
				UserID:     nextUserID(),
				Market:     market,
				OrderType:  "MARKET",
				OrderSide:  "BID",
				Price:      0,
				Quantity:   0,
				TotalPrice: budget,
			}
		}
		select {
		case orders <- order:
		default:
		}
	}
}

// DepthBuilder generates continuous background limit orders
// Uses variable timing with Hawkes-style bursts for realistic flow
func DepthBuilder(ctx context.Context, orders chan<- Order, market string,
	ob *LocalOrderBook, cfg *Config, metrics *Metrics) {

	if cfg.DepthOrdersPerSec <= 0 {
		return
	}

	marketCfg := cfg.GetMarketConfig(market)
	if marketCfg == nil {
		return
	}

	// Base interval with randomization for continuous feel
	baseInterval := time.Second / time.Duration(cfg.DepthOrdersPerSec)
	recentOrders := 0
	lastBurst := time.Now()

	for {
		select {
		case <-ctx.Done():
			return
		default:
			// Variable delay: exponential distribution around base interval
			// This creates natural clustering without fixed cycles
			delay := time.Duration(float64(baseInterval) * (0.2 + rand.ExpFloat64()*0.8))
			if delay < time.Millisecond {
				delay = time.Millisecond
			}
			if delay > baseInterval*3 {
				delay = baseInterval * 3
			}

			// Hawkes-style burst: increased activity after recent orders
			if time.Since(lastBurst) < 500*time.Millisecond && recentOrders > 3 {
				delay = delay / 2 // Faster during bursts
			}

			time.Sleep(delay)

			// Generate 1-3 orders per tick for variability
			numOrders := 1
			if rand.Float32() < 0.2 {
				numOrders = 2 + rand.Intn(2) // Occasional burst of 2-3
				recentOrders += numOrders
				lastBurst = time.Now()
			}

			for i := 0; i < numOrders; i++ {
				generateRealisticOrder(orders, market, ob, cfg, marketCfg, metrics)
			}

			// Decay burst counter
			if rand.Float32() < 0.1 {
				recentOrders = recentOrders / 2
			}
		}
	}
}

// generateRealisticOrder creates a single realistic limit order
// Implements exponential depth decay and power-law sizing
func generateRealisticOrder(orders chan<- Order, market string,
	ob *LocalOrderBook, cfg *Config, marketCfg *MarketConfig, metrics *Metrics) {

	midPrice := metrics.GetMidPrice(market)
	if midPrice <= 0 {
		midPrice = marketCfg.BasePrice
		if midPrice <= 0 {
			return
		}
	}

	// Get current imbalance for quote skewing
	imbalance := ob.GetImbalanceNearSpread(5)

	// Avellaneda-Stoikov: skew quotes to counter imbalance
	bidProb := 0.5 + ImbalanceSkew(imbalance, cfg.ImbalanceStrength)

	side := "ASK"
	if rand.Float64() < bidProb {
		side = "BID"
	}

	// Exponential distribution for level (most orders near spread)
	level := ExponentialLevel(cfg.DepthDecayLambda)
	if level > cfg.DepthLevels {
		level = cfg.DepthLevels
	}

	// Calculate price: level determines distance from mid
	spreadTicks := float64(level) * marketCfg.TickSize * (1 + rand.Float64()*0.3)
	var price float64
	if side == "BID" {
		price = midPrice - spreadTicks
	} else {
		price = midPrice + spreadTicks
	}
	price = RoundToTick(price, marketCfg.TickSize)

	// Power-law order size (most orders small, few large)
	qty := ParetoQuantity(marketCfg.MinQty, cfg.OrderSizeAlpha)
	// Cap extreme outliers but allow some large orders
	maxQty := marketCfg.MinQty * 200
	qty = ClampQuantity(qty, marketCfg.MinQty, maxQty)

	// Add some variation based on level (deeper = slightly larger on average)
	qty = qty * (1 + float64(level)*0.02)

	order := Order{
		UserID:     nextUserID(),
		Market:     market,
		OrderType:  "LIMIT",
		OrderSide:  side,
		Price:      price,
		Quantity:   qty,
		TotalPrice: price * qty,
	}

	select {
	case orders <- order:
		ob.AddOrder(side, price, qty)
		metrics.IncrementDepthOrders(market)
	default:
		// Channel full
	}
}

// OrderDecay simulates continuous order cancellations
// Uses variable timing for natural feel
func OrderDecay(ctx context.Context, ob *LocalOrderBook, market string, cfg *Config) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
			// Variable delay: 50-150ms with exponential distribution
			delay := time.Duration(50+rand.ExpFloat64()*50) * time.Millisecond
			time.Sleep(delay)

			// Decay orders (simulate cancellations)
			ob.Decay(cfg.CancelProbability)

			// Prune distant orders
			ob.PruneDistant(0.01)
		}
	}
}

// SeedRealisticDepth generates initial orderbook depth with realistic distributions
// Creates an order book that looks like a real exchange from the start
func SeedRealisticDepth(orders chan<- Order, market string, ob *LocalOrderBook,
	cfg *Config, marketCfg *MarketConfig) int {

	basePrice := marketCfg.BasePrice
	if basePrice <= 0 {
		return 0
	}

	count := 0

	// Generate orders with exponential level distribution
	for i := 0; i < cfg.SeedOrders; i++ {
		// Exponential level (most near spread)
		level := ExponentialLevel(cfg.DepthDecayLambda)
		if level > cfg.DepthLevels {
			level = cfg.DepthLevels
		}

		// Power-law quantity
		qty := ParetoQuantity(marketCfg.MinQty, cfg.OrderSizeAlpha)
		qty = ClampQuantity(qty, marketCfg.MinQty, marketCfg.MinQty*100)

		// Add level-based variation
		qty = qty * (1 + float64(level)*0.02)

		// Calculate price distance
		spreadTicks := float64(level) * marketCfg.TickSize * (1 + rand.Float64()*0.3)

		// Slightly favor bids (markets tend to trend up)
		side := "BID"
		price := RoundToTick(basePrice-spreadTicks, marketCfg.TickSize)
		if rand.Float32() > 0.48 {
			side = "ASK"
			price = RoundToTick(basePrice+spreadTicks, marketCfg.TickSize)
		}

		order := Order{
			UserID:     nextUserID(),
			Market:     market,
			OrderType:  "LIMIT",
			OrderSide:  side,
			Price:      price,
			Quantity:   qty,
			TotalPrice: price * qty,
		}

		select {
		case orders <- order:
			ob.AddOrder(side, price, qty)
			count++
		default:
			// Channel full, skip
		}
	}

	return count
}

// Legacy function for compatibility - now wraps SeedRealisticDepth
func SeedInitialDepth(orders chan<- Order, market string, basePrice float64, levels int) int {
	if basePrice <= 0 || levels <= 0 {
		return 0
	}

	count := 0
	for level := 1; level <= levels; level++ {
		spreadPct := 0.005 * float64(level) / float64(levels)
		quantity := 0.05 * (1 + float64(level)*0.2)

		orders <- Order{
			UserID:     nextUserID(),
			Market:     market,
			OrderType:  "LIMIT",
			OrderSide:  "BID",
			Price:      basePrice * (1 - spreadPct),
			Quantity:   quantity,
			TotalPrice: basePrice * (1 - spreadPct) * quantity,
		}
		count++

		orders <- Order{
			UserID:     nextUserID(),
			Market:     market,
			OrderType:  "LIMIT",
			OrderSide:  "ASK",
			Price:      basePrice * (1 + spreadPct),
			Quantity:   quantity,
			TotalPrice: basePrice * (1 + spreadPct) * quantity,
		}
		count++
	}

	return count
}

// DepthProfile calculates the expected volume at each level for visualization
func DepthProfile(levels int, decayLambda float64) []float64 {
	profile := make([]float64, levels)
	totalWeight := 0.0

	for i := 0; i < levels; i++ {
		weight := math.Exp(-decayLambda * float64(i))
		profile[i] = weight
		totalWeight += weight
	}

	// Normalize
	for i := range profile {
		profile[i] /= totalWeight
	}

	return profile
}
