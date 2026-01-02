import { useEffect, useRef, useState, useCallback } from 'react';
import type { ConnectionStatus, WebSocketMessage } from '../types/market';

interface UseWebSocketOptions {
  marketId: number;
  onMessage: (message: WebSocketMessage) => void;
}

const MAX_RECONNECT_ATTEMPTS = 10;
const INITIAL_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;
const PING_INTERVAL = 30000;

// Message counter for diagnostics
let messageCount = 0;
let lastMessageLogTime = 0;

export function useWebSocket({ marketId, onMessage }: UseWebSocketOptions) {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const pingIntervalRef = useRef<number | null>(null);

  const getWebSocketUrl = useCallback(() => {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    // WebSocket runs on port 8081
    const host = window.location.hostname;
    return `${protocol}//${host}:8081/ws`;
  }, []);

  const clearTimers = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
    if (pingIntervalRef.current) {
      clearInterval(pingIntervalRef.current);
      pingIntervalRef.current = null;
    }
  }, []);

  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      return;
    }

    clearTimers();
    setStatus('connecting');

    try {
      const ws = new WebSocket(getWebSocketUrl());
      wsRef.current = ws;

      ws.onopen = () => {
        console.log('[WS] Connected to', getWebSocketUrl());
        setStatus('connected');
        reconnectAttemptRef.current = 0;

        // Subscribe to market
        console.log('[WS] Subscribing to market', marketId);
        ws.send(JSON.stringify({ action: 'subscribe', marketId }));

        // Start ping interval
        pingIntervalRef.current = window.setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ action: 'ping' }));
          }
        }, PING_INTERVAL);
      };

      ws.onmessage = (event) => {
        try {
          messageCount++;
          const now = Date.now();
          // Log message rate every 5 seconds
          if (now - lastMessageLogTime >= 5000) {
            console.log('[WS] Messages received:', messageCount, '(+' + messageCount + ' in last 5s)');
            lastMessageLogTime = now;
            messageCount = 0;
          }
          const message = JSON.parse(event.data) as WebSocketMessage;
          onMessage(message);
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e);
        }
      };

      ws.onclose = (event) => {
        console.log('[WS] Connection closed:', event.code, event.reason || '(no reason)');
        setStatus('disconnected');
        clearTimers();

        // Attempt reconnect with exponential backoff
        if (reconnectAttemptRef.current < MAX_RECONNECT_ATTEMPTS) {
          const delay = Math.min(
            INITIAL_RECONNECT_DELAY * Math.pow(1.5, reconnectAttemptRef.current),
            MAX_RECONNECT_DELAY
          );
          reconnectAttemptRef.current++;
          console.log(`[WS] Reconnecting in ${delay}ms (attempt ${reconnectAttemptRef.current})`);
          reconnectTimeoutRef.current = window.setTimeout(connect, delay);
        } else {
          console.error('[WS] Max reconnection attempts reached');
          setStatus('error');
        }
      };

      ws.onerror = (event) => {
        console.error('[WS] WebSocket error:', event);
        setStatus('error');
      };
    } catch (e) {
      console.error('Failed to create WebSocket:', e);
      setStatus('error');
    }
  }, [getWebSocketUrl, marketId, onMessage, clearTimers]);

  const disconnect = useCallback(() => {
    clearTimers();
    reconnectAttemptRef.current = MAX_RECONNECT_ATTEMPTS; // Prevent reconnect
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setStatus('disconnected');
  }, [clearTimers]);

  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, [connect, disconnect]);

  return { status, reconnect: connect, disconnect };
}
