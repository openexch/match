import { useState, useEffect, useCallback } from 'react';
import './ClusterStatus.css';
import { ClusterNode, ClusterStatusMessage, ClusterEventMessage } from '../../types/market';

interface ClusterEvent {
  id: number;
  event: string;
  message: string;
  timestamp: number;
  nodeId?: number;
}

interface ClusterState {
  leaderId: number;
  leadershipTermId: number;
  nodes: ClusterNode[];
  gatewayConnected: boolean;
  lastUpdate: number;
}

export function ClusterStatus() {
  const [clusterState, setClusterState] = useState<ClusterState>({
    leaderId: -1,
    leadershipTermId: -1,
    nodes: [
      { id: 0, status: 'OFFLINE', healthy: false },
      { id: 1, status: 'OFFLINE', healthy: false },
      { id: 2, status: 'OFFLINE', healthy: false },
    ],
    gatewayConnected: false,
    lastUpdate: 0,
  });

  const [events, setEvents] = useState<ClusterEvent[]>([]);
  const [wsConnected, setWsConnected] = useState(false);
  const eventIdRef = { current: 0 };

  const addEvent = useCallback((event: string, message: string, nodeId?: number) => {
    eventIdRef.current += 1;
    const newEvent: ClusterEvent = {
      id: eventIdRef.current,
      event,
      message,
      timestamp: Date.now(),
      nodeId,
    };
    setEvents(prevEvents => [newEvent, ...prevEvents].slice(0, 20));
  }, []);

  useEffect(() => {
    let ws: WebSocket | null = null;
    let reconnectTimeout: ReturnType<typeof setTimeout>;

    const connect = () => {
      const wsUrl = `ws://${window.location.hostname}:8081/ws`;
      ws = new WebSocket(wsUrl);

      ws.onopen = () => {
        setWsConnected(true);
        addEvent('CONNECTION_RESTORED', 'Connected to cluster WebSocket');
        // Subscribe to market 1 to receive updates
        ws?.send(JSON.stringify({ action: 'subscribe', marketId: 1 }));
      };

      ws.onclose = () => {
        setWsConnected(false);
        addEvent('CONNECTION_LOST', 'Lost connection to cluster WebSocket');
        // Reconnect after 2 seconds
        reconnectTimeout = setTimeout(connect, 2000);
      };

      ws.onerror = () => {
        setWsConnected(false);
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);

          if (data.type === 'CLUSTER_STATUS') {
            const status = data as ClusterStatusMessage;
            setClusterState({
              leaderId: status.leaderId,
              leadershipTermId: status.leadershipTermId,
              nodes: status.nodes,
              gatewayConnected: status.gatewayConnected,
              lastUpdate: status.timestamp,
            });
          } else if (data.type === 'CLUSTER_EVENT') {
            const evt = data as ClusterEventMessage;
            addEvent(evt.event, evt.message, evt.nodeId);
          }
        } catch (e) {
          // Ignore parse errors
        }
      };
    };

    connect();

    return () => {
      if (ws) {
        ws.close();
      }
      if (reconnectTimeout) {
        clearTimeout(reconnectTimeout);
      }
    };
  }, [addEvent]);

  const getNodeStatusClass = (node: ClusterNode) => {
    if (!node.healthy) return 'node-offline';
    if (node.status === 'LEADER') return 'node-leader';
    if (node.status === 'FOLLOWER') return 'node-follower';
    return 'node-unknown';
  };

  const getEventClass = (event: string) => {
    if (event.includes('LEADER')) return 'event-leader';
    if (event.includes('UP') || event.includes('RESTORED')) return 'event-up';
    if (event.includes('DOWN') || event.includes('LOST')) return 'event-down';
    return 'event-info';
  };

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    const timeStr = date.toLocaleTimeString('en-US', {
      hour12: false,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    const ms = String(date.getMilliseconds()).padStart(3, '0');
    return `${timeStr}.${ms}`;
  };

  return (
    <div className="cluster-status">
      <div className="cluster-header">
        <h3>Cluster Status</h3>
        <div className={`gateway-status ${clusterState.gatewayConnected ? 'connected' : 'disconnected'}`}>
          Gateway: {clusterState.gatewayConnected ? 'Connected' : 'Disconnected'}
        </div>
      </div>

      <div className="cluster-nodes">
        {clusterState.nodes.map((node) => (
          <div key={node.id} className={`cluster-node ${getNodeStatusClass(node)}`}>
            <div className="node-id">Node {node.id}</div>
            <div className="node-status">{node.status}</div>
            {node.id === clusterState.leaderId && (
              <div className="leader-badge">LEADER</div>
            )}
          </div>
        ))}
      </div>

      <div className="cluster-info">
        <span>Leader: Node {clusterState.leaderId >= 0 ? clusterState.leaderId : '?'}</span>
        <span>Term: {clusterState.leadershipTermId >= 0 ? clusterState.leadershipTermId : '?'}</span>
        <span className={`ws-status ${wsConnected ? 'connected' : 'disconnected'}`}>
          WS: {wsConnected ? 'OK' : 'Reconnecting...'}
        </span>
      </div>

      <div className="cluster-events">
        <h4>Cluster Events</h4>
        <div className="events-list">
          {events.length === 0 ? (
            <div className="no-events">Waiting for events...</div>
          ) : (
            events.map((evt) => (
              <div key={evt.id} className={`event-item ${getEventClass(evt.event)}`}>
                <span className="event-time">{formatTime(evt.timestamp)}</span>
                <span className="event-type">{evt.event}</span>
                <span className="event-message">{evt.message}</span>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
