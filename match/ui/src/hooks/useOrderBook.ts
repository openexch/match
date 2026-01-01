import { useState, useCallback } from 'react';
import type { OrderBook, BookSnapshotMessage } from '../types/market';

const INITIAL_ORDER_BOOK: OrderBook = {
  bids: [],
  asks: [],
  lastUpdate: 0,
};

export function useOrderBook() {
  const [orderBook, setOrderBook] = useState<OrderBook>(INITIAL_ORDER_BOOK);

  const handleBookSnapshot = useCallback((message: BookSnapshotMessage) => {
    setOrderBook({
      bids: message.bids,
      asks: message.asks,
      lastUpdate: message.timestamp,
    });
  }, []);

  const resetOrderBook = useCallback(() => {
    setOrderBook(INITIAL_ORDER_BOOK);
  }, []);

  return { orderBook, handleBookSnapshot, resetOrderBook };
}
