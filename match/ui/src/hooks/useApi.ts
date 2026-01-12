import { useState, useCallback } from 'react';
import type { OrderRequest } from '../types/market';

const API_BASE = import.meta.env.VITE_ORDER_API_URL || 'http://localhost:8080';

interface ApiState {
  loading: boolean;
  error: string | null;
}

interface ApiResponse {
  success: boolean;
  message: string;
}

export function useApi() {
  const [state, setState] = useState<ApiState>({ loading: false, error: null });

  const submitOrder = useCallback(async (order: OrderRequest): Promise<ApiResponse> => {
    setState({ loading: true, error: null });

    try {
      const response = await fetch(`${API_BASE}/order`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(order),
      });

      const message = await response.text();

      if (response.ok || response.status === 202) {
        setState({ loading: false, error: null });
        return { success: true, message };
      } else {
        const error = message || `Error: ${response.status}`;
        setState({ loading: false, error });
        return { success: false, message: error };
      }
    } catch (err) {
      const error = err instanceof Error ? err.message : 'Network error';
      setState({ loading: false, error });
      return { success: false, message: error };
    }
  }, []);

  const cancelOrder = useCallback(async (orderId: number, userId: string, market: string): Promise<ApiResponse> => {
    setState({ loading: true, error: null });

    try {
      const response = await fetch(`${API_BASE}/order`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          userId,
          market,
          orderType: 'CANCEL',
          orderId,
          timestamp: Date.now(),
        }),
      });

      const message = await response.text();

      if (response.ok || response.status === 202) {
        setState({ loading: false, error: null });
        return { success: true, message };
      } else {
        const error = message || `Error: ${response.status}`;
        setState({ loading: false, error });
        return { success: false, message: error };
      }
    } catch (err) {
      const error = err instanceof Error ? err.message : 'Network error';
      setState({ loading: false, error });
      return { success: false, message: error };
    }
  }, []);

  return {
    ...state,
    submitOrder,
    cancelOrder,
  };
}
