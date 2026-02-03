import { useState, useCallback, useRef, useEffect } from 'react';
import type { OrderBook, BookSnapshotMessage, BookDeltaMessage, BookLevel } from '../types/market';

export type ChangeType = 'new' | 'update';

export interface LevelChange {
  type: ChangeType;
  side: 'bid' | 'ask';
  timestamp: number;
}

// Convert price to string key for consistent Map lookups (avoids floating-point precision issues)
export const priceKey = (price: number): string => (price ?? 0).toFixed(8);

const INITIAL_ORDER_BOOK: OrderBook = {
  bids: [],
  asks: [],
  lastUpdate: 0,
};

const ANIMATION_DURATION = 500; // ms

export function useOrderBook() {
  const [orderBook, setOrderBook] = useState<OrderBook>(INITIAL_ORDER_BOOK);
  const [levelChanges, setLevelChanges] = useState<Map<string, LevelChange>>(new Map());
  const pendingClearsRef = useRef<Set<string>>(new Set());

  // Clear changes after animation duration
  useEffect(() => {
    if (levelChanges.size === 0) return;

    const now = Date.now();
    const timeouts: ReturnType<typeof setTimeout>[] = [];

    levelChanges.forEach((change, key) => {
      if (pendingClearsRef.current.has(key)) return;
      pendingClearsRef.current.add(key);

      const elapsed = now - change.timestamp;
      const remaining = Math.max(0, ANIMATION_DURATION - elapsed);

      const timeout = setTimeout(() => {
        setLevelChanges(prev => {
          const next = new Map(prev);
          next.delete(key);
          return next;
        });
        pendingClearsRef.current.delete(key);
      }, remaining);

      timeouts.push(timeout);
    });

    return () => {
      timeouts.forEach(t => clearTimeout(t));
    };
  }, [levelChanges]);

  const handleBookSnapshot = useCallback((message: BookSnapshotMessage) => {
    setOrderBook({
      bids: message.bids,
      asks: message.asks,
      lastUpdate: message.timestamp,
    });
    // Clear changes on full snapshot
    setLevelChanges(new Map());
    pendingClearsRef.current.clear();
  }, []);

  const handleBookDelta = useCallback((message: BookDeltaMessage) => {
    const newChanges = new Map<string, LevelChange>();
    const now = Date.now();

    setOrderBook((prev) => {
      const newBids = [...prev.bids];
      const newAsks = [...prev.asks];

      for (const change of message.changes) {
        const levels = change.side === 'BID' ? newBids : newAsks;
        const isBid = change.side === 'BID';
        const key = priceKey(change.price);

        if (change.updateType === 'DELETE_LEVEL') {
          // Remove the level
          const idx = levels.findIndex(l => Math.abs(l.price - change.price) < 0.0000001);
          if (idx >= 0) {
            levels.splice(idx, 1);
          }
        } else if (change.updateType === 'UPDATE_LEVEL') {
          // Update existing level
          const idx = levels.findIndex(l => Math.abs(l.price - change.price) < 0.0000001);
          if (idx >= 0) {
            levels[idx] = {
              price: change.price,
              quantity: change.quantity,
              orderCount: change.orderCount,
            };
            // Track update
            newChanges.set(key, {
              type: 'update',
              side: isBid ? 'bid' : 'ask',
              timestamp: now,
            });
          }
        } else {
          // NEW_LEVEL - insert at correct position
          const newLevel: BookLevel = {
            price: change.price,
            quantity: change.quantity,
            orderCount: change.orderCount,
          };

          // Find insertion point
          // Bids: descending (higher prices first)
          // Asks: ascending (lower prices first)
          let insertIdx = levels.length;
          for (let i = 0; i < levels.length; i++) {
            const shouldInsertBefore = isBid
              ? change.price > levels[i].price
              : change.price < levels[i].price;
            if (shouldInsertBefore) {
              insertIdx = i;
              break;
            }
          }
          levels.splice(insertIdx, 0, newLevel);

          // Track new level
          newChanges.set(key, {
            type: 'new',
            side: isBid ? 'bid' : 'ask',
            timestamp: now,
          });

          // Keep max 20 levels
          if (levels.length > 20) {
            levels.pop();
          }
        }
      }

      return {
        bids: newBids,
        asks: newAsks,
        lastUpdate: message.timestamp,
      };
    });

    // Merge new changes
    if (newChanges.size > 0) {
      setLevelChanges(prev => {
        const merged = new Map(prev);
        newChanges.forEach((change, key) => {
          merged.set(key, change);
          pendingClearsRef.current.delete(key); // Reset clear timer
        });
        return merged;
      });
    }
  }, []);

  const resetOrderBook = useCallback(() => {
    setOrderBook(INITIAL_ORDER_BOOK);
    setLevelChanges(new Map());
    pendingClearsRef.current.clear();
  }, []);

  return { orderBook, levelChanges, handleBookSnapshot, handleBookDelta, resetOrderBook };
}
