package main

import (
	"math"
	"math/rand"
	"sort"
	"sync"
)

// LocalOrderBook tracks simulated order book state locally.
// This enables realistic behaviors like imbalance-driven quoting,
// cancellation simulation, and depth profile tracking.
type LocalOrderBook struct {
	mu   sync.RWMutex
	bids map[float64]float64 // price -> total quantity
	asks map[float64]float64 // price -> total quantity

	// Cached values
	bestBid  float64
	bestAsk  float64
	midPrice float64

	// Statistics
	totalBidQty float64
	totalAskQty float64
}

// NewLocalOrderBook creates an empty order book.
func NewLocalOrderBook() *LocalOrderBook {
	return &LocalOrderBook{
		bids: make(map[float64]float64),
		asks: make(map[float64]float64),
	}
}

// AddOrder adds quantity at a price level.
func (ob *LocalOrderBook) AddOrder(side string, price, qty float64) {
	ob.mu.Lock()
	defer ob.mu.Unlock()

	if side == "BID" {
		ob.bids[price] += qty
		ob.totalBidQty += qty
		if price > ob.bestBid {
			ob.bestBid = price
		}
	} else {
		ob.asks[price] += qty
		ob.totalAskQty += qty
		if ob.bestAsk == 0 || price < ob.bestAsk {
			ob.bestAsk = price
		}
	}

	ob.updateMidPrice()
}

// RemoveQuantity removes quantity from a price level (simulates fills/cancels).
func (ob *LocalOrderBook) RemoveQuantity(side string, price, qty float64) {
	ob.mu.Lock()
	defer ob.mu.Unlock()

	if side == "BID" {
		if current, ok := ob.bids[price]; ok {
			remaining := current - qty
			if remaining <= 0 {
				delete(ob.bids, price)
				ob.totalBidQty -= current
				ob.recalcBestBid()
			} else {
				ob.bids[price] = remaining
				ob.totalBidQty -= qty
			}
		}
	} else {
		if current, ok := ob.asks[price]; ok {
			remaining := current - qty
			if remaining <= 0 {
				delete(ob.asks, price)
				ob.totalAskQty -= current
				ob.recalcBestAsk()
			} else {
				ob.asks[price] = remaining
				ob.totalAskQty -= qty
			}
		}
	}
}

// Decay removes a percentage of orders at each level (simulates cancellations).
// decayFactor: 0.02 = remove 2% of each level's quantity.
func (ob *LocalOrderBook) Decay(decayFactor float64) {
	ob.mu.Lock()
	defer ob.mu.Unlock()

	// Decay bids
	for price, qty := range ob.bids {
		// Random chance to decay this level
		if rand.Float64() < decayFactor*3 { // 3x factor for sporadic removal
			removal := qty * (decayFactor + rand.Float64()*decayFactor)
			remaining := qty - removal
			if remaining < qty*0.1 { // If less than 10% left, remove entirely
				delete(ob.bids, price)
				ob.totalBidQty -= qty
			} else {
				ob.bids[price] = remaining
				ob.totalBidQty -= removal
			}
		}
	}

	// Decay asks
	for price, qty := range ob.asks {
		if rand.Float64() < decayFactor*3 {
			removal := qty * (decayFactor + rand.Float64()*decayFactor)
			remaining := qty - removal
			if remaining < qty*0.1 {
				delete(ob.asks, price)
				ob.totalAskQty -= removal
			} else {
				ob.asks[price] = remaining
				ob.totalAskQty -= removal
			}
		}
	}

	ob.recalcBestBid()
	ob.recalcBestAsk()
}

// GetImbalance returns order book imbalance from -1 (ask heavy) to +1 (bid heavy).
// Used for Avellaneda-Stoikov style quote skewing.
func (ob *LocalOrderBook) GetImbalance() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()

	total := ob.totalBidQty + ob.totalAskQty
	if total == 0 {
		return 0
	}

	// Normalized imbalance: (bids - asks) / (bids + asks)
	return (ob.totalBidQty - ob.totalAskQty) / total
}

// GetImbalanceNearSpread returns imbalance using only levels near the spread.
// This is more sensitive to immediate supply/demand.
func (ob *LocalOrderBook) GetImbalanceNearSpread(levels int) float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()

	if ob.bestBid == 0 || ob.bestAsk == 0 {
		return 0
	}

	// Get top N bid levels
	bidPrices := make([]float64, 0, len(ob.bids))
	for p := range ob.bids {
		bidPrices = append(bidPrices, p)
	}
	sort.Sort(sort.Reverse(sort.Float64Slice(bidPrices)))

	bidQty := 0.0
	for i := 0; i < levels && i < len(bidPrices); i++ {
		bidQty += ob.bids[bidPrices[i]]
	}

	// Get top N ask levels
	askPrices := make([]float64, 0, len(ob.asks))
	for p := range ob.asks {
		askPrices = append(askPrices, p)
	}
	sort.Float64Slice(askPrices).Sort()

	askQty := 0.0
	for i := 0; i < levels && i < len(askPrices); i++ {
		askQty += ob.asks[askPrices[i]]
	}

	total := bidQty + askQty
	if total == 0 {
		return 0
	}

	return (bidQty - askQty) / total
}

// GetBestBid returns the highest bid price.
func (ob *LocalOrderBook) GetBestBid() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	return ob.bestBid
}

// GetBestAsk returns the lowest ask price.
func (ob *LocalOrderBook) GetBestAsk() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	return ob.bestAsk
}

// GetMidPrice returns the mid-price between best bid and ask.
func (ob *LocalOrderBook) GetMidPrice() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	return ob.midPrice
}

// GetSpread returns the current bid-ask spread.
func (ob *LocalOrderBook) GetSpread() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	if ob.bestBid == 0 || ob.bestAsk == 0 {
		return 0
	}
	return ob.bestAsk - ob.bestBid
}

// GetSpreadBps returns spread in basis points.
func (ob *LocalOrderBook) GetSpreadBps() float64 {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	if ob.midPrice == 0 {
		return 0
	}
	return (ob.bestAsk - ob.bestBid) / ob.midPrice * 10000
}

// GetDepthAtLevel returns total quantity within N ticks of the mid-price.
func (ob *LocalOrderBook) GetDepthAtLevel(tickSize float64, levels int) (bidDepth, askDepth float64) {
	ob.mu.RLock()
	defer ob.mu.RUnlock()

	maxBidPrice := ob.midPrice
	minBidPrice := ob.midPrice - tickSize*float64(levels)
	for price, qty := range ob.bids {
		if price >= minBidPrice && price <= maxBidPrice {
			bidDepth += qty
		}
	}

	minAskPrice := ob.midPrice
	maxAskPrice := ob.midPrice + tickSize*float64(levels)
	for price, qty := range ob.asks {
		if price >= minAskPrice && price <= maxAskPrice {
			askDepth += qty
		}
	}

	return
}

// GetTotalDepth returns total bid and ask quantities.
func (ob *LocalOrderBook) GetTotalDepth() (bidDepth, askDepth float64) {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	return ob.totalBidQty, ob.totalAskQty
}

// GetLevelCount returns number of price levels on each side.
func (ob *LocalOrderBook) GetLevelCount() (bidLevels, askLevels int) {
	ob.mu.RLock()
	defer ob.mu.RUnlock()
	return len(ob.bids), len(ob.asks)
}

// SetMidPrice updates the mid-price (called when receiving Binance data).
func (ob *LocalOrderBook) SetMidPrice(price float64) {
	ob.mu.Lock()
	defer ob.mu.Unlock()
	ob.midPrice = price
}

// Internal helpers

func (ob *LocalOrderBook) updateMidPrice() {
	if ob.bestBid > 0 && ob.bestAsk > 0 {
		ob.midPrice = (ob.bestBid + ob.bestAsk) / 2
	}
}

func (ob *LocalOrderBook) recalcBestBid() {
	ob.bestBid = 0
	for price := range ob.bids {
		if price > ob.bestBid {
			ob.bestBid = price
		}
	}
	ob.updateMidPrice()
}

func (ob *LocalOrderBook) recalcBestAsk() {
	ob.bestAsk = 0
	for price := range ob.asks {
		if ob.bestAsk == 0 || price < ob.bestAsk {
			ob.bestAsk = price
		}
	}
	ob.updateMidPrice()
}

// PruneDistant removes orders too far from mid-price (cleanup).
func (ob *LocalOrderBook) PruneDistant(maxDistancePct float64) {
	ob.mu.Lock()
	defer ob.mu.Unlock()

	if ob.midPrice == 0 {
		return
	}

	maxDistance := ob.midPrice * maxDistancePct

	// Prune distant bids
	for price, qty := range ob.bids {
		if ob.midPrice-price > maxDistance {
			delete(ob.bids, price)
			ob.totalBidQty -= qty
		}
	}

	// Prune distant asks
	for price, qty := range ob.asks {
		if price-ob.midPrice > maxDistance {
			delete(ob.asks, price)
			ob.totalAskQty -= qty
		}
	}

	ob.recalcBestBid()
	ob.recalcBestAsk()
}

// Stats returns a snapshot of order book statistics.
func (ob *LocalOrderBook) Stats() OrderBookStats {
	ob.mu.RLock()
	defer ob.mu.RUnlock()

	return OrderBookStats{
		BestBid:     ob.bestBid,
		BestAsk:     ob.bestAsk,
		MidPrice:    ob.midPrice,
		Spread:      ob.bestAsk - ob.bestBid,
		TotalBidQty: ob.totalBidQty,
		TotalAskQty: ob.totalAskQty,
		BidLevels:   len(ob.bids),
		AskLevels:   len(ob.asks),
		Imbalance:   (ob.totalBidQty - ob.totalAskQty) / math.Max(ob.totalBidQty+ob.totalAskQty, 1),
	}
}

// OrderBookStats holds snapshot statistics.
type OrderBookStats struct {
	BestBid     float64
	BestAsk     float64
	MidPrice    float64
	Spread      float64
	TotalBidQty float64
	TotalAskQty float64
	BidLevels   int
	AskLevels   int
	Imbalance   float64
}
