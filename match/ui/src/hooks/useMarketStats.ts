import { useState, useCallback } from 'react';
import type { MarketStats, AggregatedTrade, BookLevel } from '../types/market';

/**
 * Hook for managing market ticker stats.
 * Stats are primarily received from the server via TICKER_STATS messages.
 * handleTrades and handleBookUpdate provide fallback updates for last price.
 */
export function useMarketStats() {
  const [stats, setStats] = useState<MarketStats>({
    lastPrice: 0,
    priceChange: 0,
    priceChangePercent: 0,
    high24h: 0,
    low24h: 0,
    volume24h: 0,
  });

  // Fallback: update lastPrice from trades if TICKER_STATS hasn't arrived yet
  const handleTrades = useCallback((trades: AggregatedTrade[]) => {
    if (trades.length === 0) return;
    const lastTrade = trades[trades.length - 1];
    setStats(prev => ({
      ...prev,
      lastPrice: lastTrade.price,
    }));
  }, []);

  // Fallback: update lastPrice from mid-market if no trades yet
  const handleBookUpdate = useCallback((bids: BookLevel[], asks: BookLevel[]) => {
    if (bids.length > 0 && asks.length > 0) {
      setStats(prev => {
        // Only update if we don't have a price yet
        if (prev.lastPrice === 0) {
          const midPrice = (bids[0].price + asks[0].price) / 2;
          return { ...prev, lastPrice: midPrice };
        }
        return prev;
      });
    }
  }, []);

  const resetStats = useCallback(() => {
    setStats({
      lastPrice: 0,
      priceChange: 0,
      priceChangePercent: 0,
      high24h: 0,
      low24h: 0,
      volume24h: 0,
    });
  }, []);

  return {
    stats,
    setStats,  // Exposed for direct updates from TICKER_STATS messages
    handleTrades,
    handleBookUpdate,
    resetStats,
  };
}
