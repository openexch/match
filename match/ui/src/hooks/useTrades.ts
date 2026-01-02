import { useState, useCallback } from 'react';
import type { AggregatedTrade, TradesBatchMessage } from '../types/market';

const MAX_TRADES = 50;

export function useTrades() {
  const [trades, setTrades] = useState<AggregatedTrade[]>([]);

  const handleTradesBatch = useCallback((message: TradesBatchMessage) => {
    setTrades((prev) => {
      // Prepend new trades and keep only the latest MAX_TRADES
      const newTrades = [...message.trades.reverse(), ...prev];
      return newTrades.slice(0, MAX_TRADES);
    });
  }, []);

  const resetTrades = useCallback(() => {
    setTrades([]);
  }, []);

  return { trades, handleTradesBatch, resetTrades };
}
