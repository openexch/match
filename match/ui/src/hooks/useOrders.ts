import { useState, useCallback } from 'react';
import type { UserOrder, OrderStatusMessage, OrderStatusBatchMessage, OrderStatus } from '../types/market';

const MAX_ORDERS = 100;

export function useOrders() {
  const [orders, setOrders] = useState<UserOrder[]>([]);

  // Process a single order status entry
  const processOrderStatus = useCallback((message: OrderStatusMessage, prev: UserOrder[], marketId?: number, market?: string): UserOrder[] => {
    const existingIndex = prev.findIndex(o => o.orderId === message.orderId);

    // Terminal states - remove from open orders
    if (message.status === 'FILLED' || message.status === 'CANCELLED' || message.status === 'REJECTED') {
      if (existingIndex >= 0) {
        return prev.filter(o => o.orderId !== message.orderId);
      }
      return prev;
    }

    const updatedOrder: UserOrder = {
      orderId: message.orderId,
      marketId: message.marketId ?? marketId ?? 1,
      market: message.market ?? market ?? 'BTC-USD',
      userId: message.userId,
      side: message.side,
      type: 'LIMIT', // Default, could be enhanced
      price: message.price,
      originalQuantity: message.remainingQuantity + message.filledQuantity,
      remainingQuantity: message.remainingQuantity,
      filledQuantity: message.filledQuantity,
      status: message.status as OrderStatus,
      timestamp: message.timestamp,
    };

    if (existingIndex >= 0) {
      // Update existing order
      const newOrders = [...prev];
      newOrders[existingIndex] = updatedOrder;
      return newOrders;
    } else {
      // Add new order at the beginning
      const newOrders = [updatedOrder, ...prev];
      return newOrders.slice(0, MAX_ORDERS);
    }
  }, []);

  const handleOrderStatus = useCallback((message: OrderStatusMessage) => {
    setOrders(prev => processOrderStatus(message, prev));
  }, [processOrderStatus]);

  // Handle batched order status updates
  const handleOrderStatusBatch = useCallback((message: OrderStatusBatchMessage) => {
    if (!message.orders || message.orders.length === 0) return;

    setOrders(prev => {
      let result = prev;
      for (const order of message.orders) {
        result = processOrderStatus(order, result, message.marketId, message.market);
      }
      return result;
    });
  }, [processOrderStatus]);

  const resetOrders = useCallback(() => {
    setOrders([]);
  }, []);

  const removeOrder = useCallback((orderId: number) => {
    setOrders(prev => prev.filter(o => o.orderId !== orderId));
  }, []);

  // Get only open orders (NEW or PARTIALLY_FILLED)
  const openOrders = orders.filter(
    o => o.status === 'NEW' || o.status === 'PARTIALLY_FILLED'
  );

  return {
    orders,
    openOrders,
    handleOrderStatus,
    handleOrderStatusBatch,
    resetOrders,
    removeOrder,
  };
}
