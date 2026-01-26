import { useEffect, useRef, useState, useCallback } from 'react';
import type { ConnectionStatus, WebSocketMessage } from '../types/market';

interface UseWebSocketOptions {
  marketId: number;
  onMessage: (message: WebSocketMessage) => void;
  onReconnecting?: () => void;  // Called when starting reconnect - use to reset state
  onReconnected?: () => void;   // Called after successful reconnect
}

const MAX_RECONNECT_ATTEMPTS = 10;
const INITIAL_RECONNECT_DELAY = 1000;
const MAX_RECONNECT_DELAY = 30000;
const PING_INTERVAL = 30000;

// Message counter for diagnostics
let messageCount = 0;
let lastMessageLogTime = 0;

export function useWebSocket({ marketId, onMessage, onReconnecting, onReconnected }: UseWebSocketOptions) {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimeoutRef = useRef<number | null>(null);
  const pingIntervalRef = useRef<number | null>(null);
  const isReconnectRef = useRef(false);  // Track if this is a reconnection

  // Use ref for onMessage to avoid reconnecting when handler changes
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  // Use refs for reconnection callbacks
  const onReconnectingRef = useRef(onReconnecting);
  const onReconnectedRef = useRef(onReconnected);
  onReconnectingRef.current = onReconnecting;
  onReconnectedRef.current = onReconnected;

  // Use ref for marketId to send subscribe without reconnecting
  const marketIdRef = useRef(marketId);

  const getWebSocketUrl = useCallback(() => {
    // Use environment variable if set (for cloudflared deployment)
    if (import.meta.env.VITE_MARKET_WS_URL) {
      return `${import.meta.env.VITE_MARKET_WS_URL}/ws`;
    }
    // Fallback to localhost for development
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
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

    // Notify that reconnection is starting (if this is a reconnect)
    if (isReconnectRef.current && onReconnectingRef.current) {
      console.log('[WS] Reconnecting - resetting state');
      onReconnectingRef.current();
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

        // Subscribe to market (use ref for current marketId)
        console.log('[WS] Subscribing to market', marketIdRef.current);
        ws.send(JSON.stringify({ action: 'subscribe', marketId: marketIdRef.current }));

        // Request state refresh after reconnect
        if (isReconnectRef.current) {
          console.log('[WS] Requesting state refresh after reconnect for market', marketIdRef.current);
          ws.send(JSON.stringify({ action: 'refresh', marketId: marketIdRef.current }));
          if (onReconnectedRef.current) {
            onReconnectedRef.current();
          }
        }

        // Mark future connects as reconnects
        isReconnectRef.current = true;

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
          // Use ref to always call latest handler without causing reconnects
          onMessageRef.current(message);
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
  }, [getWebSocketUrl, clearTimers]);

  const disconnect = useCallback(() => {
    clearTimers();
    reconnectAttemptRef.current = MAX_RECONNECT_ATTEMPTS; // Prevent reconnect
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setStatus('disconnected');
  }, [clearTimers]);

  // Connect on mount, disconnect on unmount
  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, [connect, disconnect]);

  // Handle market changes by re-subscribing (without reconnecting)
  useEffect(() => {
    marketIdRef.current = marketId;
    // If already connected, send new subscription
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      console.log('[WS] Re-subscribing to market', marketId);
      wsRef.current.send(JSON.stringify({ action: 'subscribe', marketId }));
    }
  }, [marketId]);

  // Force reconnect - explicitly triggers onReconnecting callback
  const forceReconnect = useCallback(() => {
    isReconnectRef.current = true;
    reconnectAttemptRef.current = 0;
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    // connect() will be called by onclose handler, but we can also call directly for immediate effect
    connect();
  }, [connect]);

  return { status, reconnect: connect, disconnect, forceReconnect };
}
