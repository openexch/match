package main

import (
	"flag"
	"fmt"
	"strings"
)

// MarketConfig maps a Binance symbol to Match Engine market
type MarketConfig struct {
	BinanceSymbol string  // e.g., "btcusdt"
	MatchMarket   string  // e.g., "BTC-USD"
	BasePrice     float64 // Initial price for seeding orderbook depth
	TickSize      float64 // Price increment (BTC: 0.01, DOGE: 0.00001)
	MinQty        float64 // Minimum order quantity
	TypicalSpread float64 // Typical spread in price units
}

// DefaultMarkets is the default set of markets to replay
// Parameters based on real Binance market data
var DefaultMarkets = []MarketConfig{
	{BinanceSymbol: "btcusdt", MatchMarket: "BTC-USD", BasePrice: 100000,
		TickSize: 0.01, MinQty: 0.0001, TypicalSpread: 0.10},
	{BinanceSymbol: "ethusdt", MatchMarket: "ETH-USD", BasePrice: 3500,
		TickSize: 0.01, MinQty: 0.001, TypicalSpread: 0.05},
	{BinanceSymbol: "solusdt", MatchMarket: "SOL-USD", BasePrice: 200,
		TickSize: 0.001, MinQty: 0.01, TypicalSpread: 0.01},
	{BinanceSymbol: "xrpusdt", MatchMarket: "XRP-USD", BasePrice: 2.5,
		TickSize: 0.0001, MinQty: 1.0, TypicalSpread: 0.0005},
	{BinanceSymbol: "dogeusdt", MatchMarket: "DOGE-USD", BasePrice: 0.35,
		TickSize: 0.00001, MinQty: 10.0, TypicalSpread: 0.00005},
}

// Config holds all configuration for the binance replay test
type Config struct {
	// Multi-market support
	Markets []MarketConfig

	// Match engine settings
	OrderEndpoint string

	// Simulation parameters
	OrderMultiplier    int     // Orders per Binance trade
	DepthOrdersPerSec  int     // Background depth orders per second (per market)
	DepthLevels        int     // Number of price levels for depth
	DepthSpreadPercent float64 // Spread percentage for depth orders

	// HTTP settings
	HTTPWorkers int // Number of concurrent HTTP workers

	// Realism parameters (based on academic research)
	OrderSizeAlpha    float64 // Pareto alpha for power-law order sizes (1.5 = realistic)
	DepthDecayLambda  float64 // Exponential decay rate (0.3 = 70% near spread)
	CancelProbability float64 // Per-tick cancel probability (0.02 = 2%)
	ImbalanceStrength float64 // How much imbalance affects order flow (0.2 typical)
	SeedOrders        int     // Initial orders to seed per market
}

// DefaultConfig returns a Config with sensible defaults
func DefaultConfig() *Config {
	return &Config{
		Markets:            DefaultMarkets,
		OrderEndpoint:      "http://localhost:8080/order",
		OrderMultiplier:    3,
		DepthOrdersPerSec:  200, // High rate for continuous flow
		DepthLevels:        50,  // More levels for realistic depth
		DepthSpreadPercent: 1.0, // 1% max spread
		HTTPWorkers:        32,  // High throughput

		// Realism parameters (research-based defaults)
		OrderSizeAlpha:    1.5,  // Power-law exponent (Gopikrishnan et al.)
		DepthDecayLambda:  0.3,  // ~70% of orders within 5 levels
		CancelProbability: 0.02, // 2% cancel rate per tick
		ImbalanceStrength: 0.2,  // Moderate imbalance response
		SeedOrders:        200,  // Initial orders per market
	}
}

// GetBasePrice returns the base price for a market, or 0 if not found
func (c *Config) GetBasePrice(market string) float64 {
	for _, m := range c.Markets {
		if m.MatchMarket == market {
			return m.BasePrice
		}
	}
	return 0
}

// GetMarketConfig returns the full MarketConfig for a market name
func (c *Config) GetMarketConfig(market string) *MarketConfig {
	for i := range c.Markets {
		if c.Markets[i].MatchMarket == market {
			return &c.Markets[i]
		}
	}
	return nil
}

// ParseFlags parses command-line flags and returns a Config
func ParseFlags() *Config {
	cfg := DefaultConfig()

	// Single-market mode flags (for backward compatibility)
	var singleSymbol, singleMarket string
	flag.StringVar(&singleSymbol, "symbol", "", "Single Binance symbol (e.g., btcusdt) - runs single market mode")
	flag.StringVar(&singleMarket, "market", "", "Single Match engine market (e.g., BTC-USD) - requires -symbol")

	// Multi-market mode flag
	var marketsStr string
	flag.StringVar(&marketsStr, "markets", "", "Comma-separated market mappings (e.g., btcusdt:BTC-USD,ethusdt:ETH-USD)")

	// Common settings
	flag.StringVar(&cfg.OrderEndpoint, "endpoint", cfg.OrderEndpoint, "Match engine order endpoint URL")
	flag.IntVar(&cfg.OrderMultiplier, "multiplier", cfg.OrderMultiplier, "Number of orders to generate per Binance trade")
	flag.IntVar(&cfg.DepthOrdersPerSec, "depth-rate", cfg.DepthOrdersPerSec, "Background depth orders per second (per market)")
	flag.IntVar(&cfg.DepthLevels, "depth-levels", cfg.DepthLevels, "Number of price levels for depth orders")
	flag.Float64Var(&cfg.DepthSpreadPercent, "depth-spread", cfg.DepthSpreadPercent, "Spread percentage for depth orders")
	flag.IntVar(&cfg.HTTPWorkers, "workers", cfg.HTTPWorkers, "Number of concurrent HTTP workers")

	flag.Parse()

	// Determine market configuration
	if singleSymbol != "" {
		// Single market mode (backward compatible)
		market := singleMarket
		if market == "" {
			// Auto-generate market name from symbol
			market = symbolToMarket(singleSymbol)
		}
		cfg.Markets = []MarketConfig{{BinanceSymbol: singleSymbol, MatchMarket: market}}
	} else if marketsStr != "" {
		// Custom market list
		cfg.Markets = parseMarkets(marketsStr)
	}
	// Otherwise use DefaultMarkets

	return cfg
}

// symbolToMarket converts a Binance symbol to a market name (e.g., btcusdt -> BTC-USD)
func symbolToMarket(symbol string) string {
	symbol = strings.ToUpper(symbol)
	// Remove USDT suffix and add -USD
	if strings.HasSuffix(symbol, "USDT") {
		base := strings.TrimSuffix(symbol, "USDT")
		return base + "-USD"
	}
	return symbol + "-USD"
}

// parseMarkets parses a comma-separated market mapping string
func parseMarkets(str string) []MarketConfig {
	var markets []MarketConfig
	for _, part := range strings.Split(str, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		// Parse "symbol:market" or just "symbol"
		if idx := strings.Index(part, ":"); idx > 0 {
			markets = append(markets, MarketConfig{
				BinanceSymbol: part[:idx],
				MatchMarket:   part[idx+1:],
			})
		} else {
			markets = append(markets, MarketConfig{
				BinanceSymbol: part,
				MatchMarket:   symbolToMarket(part),
			})
		}
	}
	return markets
}

// GetTradeStreamURL returns the Binance trade stream URL for a symbol
func GetTradeStreamURL(symbol string) string {
	return fmt.Sprintf("wss://stream.binance.com:9443/ws/%s@trade", symbol)
}

// GetBookTickerURL returns the Binance book ticker URL for a symbol
func GetBookTickerURL(symbol string) string {
	return fmt.Sprintf("wss://stream.binance.com:9443/ws/%s@bookTicker", symbol)
}
