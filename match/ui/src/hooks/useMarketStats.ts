import { useState, useCallback, useRef } from 'react';
import type { MarketStats, AggregatedTrade, BookLevel } from '../types/market';

export function useMarketStats() {
  const [stats, setStats] = useState<MarketStats>({
    lastPrice: 0,
    priceChange: 0,
    priceChangePercent: 0,
    high24h: 0,
    low24h: 0,
    volume24h: 0,
  });

  const initialPriceRef = useRef<number | null>(null);
  const volumeRef = useRef<number>(0);
  const highRef = useRef<number>(0);
  const lowRef = useRef<number>(Infinity);

  const handleTrades = useCallback((trades: AggregatedTrade[]) => {
    if (trades.length === 0) return;

    let lastPrice = stats.lastPrice;
    let volume = volumeRef.current;
    let high = highRef.current;
    let low = lowRef.current;

    for (const trade of trades) {
      lastPrice = trade.price;
      volume += trade.quantity * trade.price;
      if (trade.price > high) high = trade.price;
      if (trade.price < low) low = trade.price;
    }

    // Set initial price for change calculation
    if (initialPriceRef.current === null && lastPrice > 0) {
      initialPriceRef.current = lastPrice;
    }

    volumeRef.current = volume;
    highRef.current = high;
    lowRef.current = low;

    const priceChange = initialPriceRef.current ? lastPrice - initialPriceRef.current : 0;
    const priceChangePercent = initialPriceRef.current ? (priceChange / initialPriceRef.current) * 100 : 0;

    setStats({
      lastPrice,
      priceChange,
      priceChangePercent,
      high24h: high,
      low24h: low === Infinity ? 0 : low,
      volume24h: volume,
    });
  }, [stats.lastPrice]);

  const handleBookUpdate = useCallback((bids: BookLevel[], asks: BookLevel[]) => {
    // Update last price from mid-market if no trades yet
    if (stats.lastPrice === 0 && bids.length > 0 && asks.length > 0) {
      const midPrice = (bids[0].price + asks[0].price) / 2;
      setStats(prev => ({
        ...prev,
        lastPrice: midPrice,
      }));
    }
  }, [stats.lastPrice]);

  const resetStats = useCallback(() => {
    initialPriceRef.current = null;
    volumeRef.current = 0;
    highRef.current = 0;
    lowRef.current = Infinity;
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
    handleTrades,
    handleBookUpdate,
    resetStats,
  };
}
