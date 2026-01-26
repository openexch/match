package main

import (
	"math"
	"math/rand"
)

// ParetoQuantity generates a power-law distributed quantity.
// This matches empirical observations that most orders are small,
// with occasional large "whale" orders.
// alpha=1.5 gives realistic heavy tail (Gopikrishnan et al. 2000)
func ParetoQuantity(minQty, alpha float64) float64 {
	u := rand.Float64()
	if u < 0.001 {
		u = 0.001 // Avoid division by zero
	}
	return minQty / math.Pow(u, 1/alpha)
}

// ExponentialLevel generates price level distance from mid-price.
// Most orders cluster near the spread (Bouchaud et al. 2002).
// lambda=0.3 concentrates ~70% of orders within first 5 levels.
func ExponentialLevel(lambda float64) int {
	u := rand.Float64()
	if u < 0.001 {
		u = 0.001
	}
	level := int(-math.Log(u)/lambda) + 1
	return level
}

// GeometricQuantity generates order sizes following geometric distribution.
// Used as alternative to Pareto for more controlled tails.
// p=0.3 gives mean of ~3.3 units
func GeometricQuantity(baseQty float64, p float64) float64 {
	// Geometric distribution: P(X=k) = (1-p)^(k-1) * p
	u := rand.Float64()
	k := int(math.Log(u) / math.Log(1-p))
	return baseQty * float64(k+1)
}

// LogNormalQuantity generates log-normally distributed quantities.
// Alternative power-law model (Maslov & Mills 2001).
func LogNormalQuantity(mu, sigma float64) float64 {
	// Box-Muller transform for normal distribution
	u1 := rand.Float64()
	u2 := rand.Float64()
	z := math.Sqrt(-2*math.Log(u1)) * math.Cos(2*math.Pi*u2)
	return math.Exp(mu + sigma*z)
}

// HawkesIntensity calculates self-exciting arrival intensity.
// Models order clustering observed in real markets.
// baseRate: background intensity
// excitation: per-event boost
// recentEvents: number of events in decay window
// decay: how fast excitation fades (per event)
func HawkesIntensity(baseRate, excitation float64, recentEvents int, decay float64) float64 {
	boost := 0.0
	for i := 0; i < recentEvents; i++ {
		boost += excitation * math.Exp(-decay*float64(i))
	}
	return baseRate + boost
}

// BurstProbability returns probability of generating extra orders
// based on recent activity level (simplified Hawkes).
func BurstProbability(baseProb float64, recentTradeRate float64, sensitivity float64) float64 {
	prob := baseProb + sensitivity*recentTradeRate
	if prob > 1.0 {
		prob = 1.0
	}
	return prob
}

// SpreadMultiplier calculates dynamic spread based on volatility/activity.
// Higher activity = tighter spreads (more competition).
func SpreadMultiplier(baseSpread float64, activityLevel float64) float64 {
	// Spread narrows with more activity (more market makers)
	multiplier := 1.0 / (1.0 + activityLevel*0.1)
	return baseSpread * multiplier
}

// ImbalanceSkew returns bid probability adjustment based on book imbalance.
// Implements Avellaneda-Stoikov style quote skewing.
// imbalance: -1 (all asks) to +1 (all bids)
// strength: how aggressively to counter imbalance (0.1-0.3 typical)
func ImbalanceSkew(imbalance, strength float64) float64 {
	// If bids dominate (positive imbalance), favor generating asks
	// Returns adjustment to bid probability (-0.3 to +0.3 typically)
	return -imbalance * strength
}

// PriceLevelWeight returns the expected volume at a given price level.
// Models the "inverted-V" or "M-shaped" depth profile.
// Closer to spread = more volume (exponential decay outward).
func PriceLevelWeight(level int, decayLambda float64) float64 {
	return math.Exp(-decayLambda * float64(level-1))
}

// RoundToTick rounds a price to the nearest tick size.
func RoundToTick(price, tickSize float64) float64 {
	return math.Round(price/tickSize) * tickSize
}

// ClampQuantity ensures quantity stays within reasonable bounds.
func ClampQuantity(qty, minQty, maxQty float64) float64 {
	if qty < minQty {
		return minQty
	}
	if qty > maxQty {
		return maxQty
	}
	return qty
}
