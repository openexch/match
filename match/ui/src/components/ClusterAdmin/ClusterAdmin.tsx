import { useState, useEffect, useCallback, useRef } from 'react';
import './ClusterAdmin.css';

// Admin WebSocket port (different from market data WebSocket)
const ADMIN_WS_URL = `ws://${window.location.hostname}:8082/ws`;

// Extended node status types for real-time state tracking
type NodeStatusType = 'LEADER' | 'FOLLOWER' | 'OFFLINE' | 'STOPPING' | 'STARTING' | 'REJOINING' | 'ELECTION';

interface NodeStatus {
  id: number;
  running: boolean;
  pid?: number;
  role: NodeStatusType;
  status?: NodeStatusType; // From WebSocket CLUSTER_STATUS
  healthy?: boolean;
}

interface ClusterStatus {
  nodes: NodeStatus[];
  leader: number;
  backup: { running: boolean; pid?: number };
  gateway: { running: boolean; port: number };
  archiveBytes: number;
  archiveDiskBytes?: number;
}

interface OperationProgress {
  operation: string | null;
  status: string | null;
  progress: number;
  currentStep: number;
  totalSteps: number;
  complete: boolean;
  error: boolean;
  errorMessage: string | null;
  elapsedMs: number;
}

interface ClusterAdminProps {
  isOpen: boolean;
  onClose: () => void;
}

interface ActionButtonProps {
  operation: string;
  label: string;
  description: string;
  progress: OperationProgress | null;
  onClick: () => void;
}

function ActionButton({ operation, label, description, progress, onClick }: ActionButtonProps) {
  const isActive = progress?.operation === operation;
  const isComplete = isActive && progress?.complete;
  const isError = isActive && progress?.error;
  const isInProgress = isActive && !progress?.complete;
  const isDisabled = progress?.operation != null && !progress?.complete;

  const progressPercent = isActive ? progress?.progress || 0 : 0;
  const statusText = isActive ? progress?.status : null;

  return (
    <button
      className={`action-btn ${isInProgress ? 'in-progress' : ''} ${isComplete ? 'complete' : ''} ${isError ? 'error' : ''}`}
      onClick={onClick}
      disabled={isDisabled}
    >
      <div className="action-progress-bar" style={{ width: `${progressPercent}%` }} />
      <div className="action-content">
        <span className="action-label">
          {isInProgress && <span className="action-spinner" />}
          {isComplete && !isError && <span className="action-check">&#10003;</span>}
          {isError && <span className="action-error-icon">&#10007;</span>}
          {isInProgress ? statusText : label}
        </span>
        <span className="action-desc">
          {isInProgress
            ? `Step ${progress?.currentStep}/${progress?.totalSteps} (${progressPercent}%)`
            : isComplete
            ? (isError ? progress?.errorMessage : 'Completed')
            : description}
        </span>
      </div>
    </button>
  );
}

// Helper to get cluster status - always returns a status for the banner
function getClusterBannerStatus(progress: OperationProgress | null, nodes: NodeStatus[]): {
  isOperation: boolean;
  isElecting: boolean;
  status: string;
  detail: string;
} {
  const onlineNodes = nodes.filter(n => n.running || n.role !== 'OFFLINE').length;
  const leader = nodes.find(n => n.role === 'LEADER');
  const followers = nodes.filter(n => n.role === 'FOLLOWER');
  const stoppingNodes = nodes.filter(n => n.role === 'STOPPING');
  const startingNodes = nodes.filter(n => n.role === 'STARTING');
  const electingNodes = nodes.filter(n => n.role === 'ELECTION');
  const rejoiningNodes = nodes.filter(n => n.role === 'REJOINING');
  const offlineNodes = nodes.filter(n => n.role === 'OFFLINE' || !n.running);

  // Build node state summary with professional formatting
  const buildNodeSummary = () => {
    const parts: string[] = [];
    if (leader) parts.push(`Leader: Node ${leader.id}`);
    if (followers.length > 0) parts.push(`Followers: ${followers.map(n => n.id).join(', ')}`);
    if (stoppingNodes.length > 0) parts.push(`Stopping: ${stoppingNodes.map(n => n.id).join(', ')}`);
    if (startingNodes.length > 0) parts.push(`Starting: ${startingNodes.map(n => n.id).join(', ')}`);
    if (rejoiningNodes.length > 0) parts.push(`Rejoining: ${rejoiningNodes.map(n => n.id).join(', ')}`);
    if (electingNodes.length > 0) parts.push(`In Election: ${electingNodes.map(n => n.id).join(', ')}`);
    if (offlineNodes.length > 0) parts.push(`Offline: ${offlineNodes.map(n => n.id).join(', ')}`);
    return parts.join(' • ');
  };

  // During active operation
  if (progress?.operation && !progress.complete) {
    const statusText = progress.status || '';
    const nodeSummary = buildNodeSummary();

    // Election in progress
    if (statusText.includes('election') || electingNodes.length > 0) {
      return {
        isOperation: true,
        isElecting: true,
        status: 'Leader Election Active',
        detail: nodeSummary || 'Consensus algorithm selecting new leader'
      };
    }

    // Stopping leader (election about to start)
    if (statusText.includes('Stopping') && statusText.includes('Leader')) {
      return {
        isOperation: true,
        isElecting: true,
        status: 'Leader Transition Initiated',
        detail: nodeSummary || 'Current leader stepping down for election'
      };
    }

    // Stopping a follower
    if (stoppingNodes.length > 0 || statusText.includes('Stopping')) {
      const stoppingNode = stoppingNodes[0];
      return {
        isOperation: true,
        isElecting: false,
        status: `Node ${stoppingNode?.id ?? '?'} Shutting Down`,
        detail: nodeSummary
      };
    }

    // Starting a node
    if (startingNodes.length > 0 || statusText.includes('Starting')) {
      const startingNode = startingNodes[0];
      return {
        isOperation: true,
        isElecting: false,
        status: `Node ${startingNode?.id ?? '?'} Initializing`,
        detail: nodeSummary
      };
    }

    // Rejoining
    if (rejoiningNodes.length > 0 || statusText.includes('rejoin')) {
      const rejoiningNode = rejoiningNodes[0];
      return {
        isOperation: true,
        isElecting: false,
        status: `Node ${rejoiningNode?.id ?? '?'} Synchronizing`,
        detail: nodeSummary
      };
    }

    // Mark file timeout or cleaning - still show node states
    if (statusText.includes('mark file')) {
      return {
        isOperation: true,
        isElecting: false,
        status: 'Awaiting Mark File Expiry',
        detail: nodeSummary
      };
    }

    if (statusText.includes('Cleaning')) {
      return {
        isOperation: true,
        isElecting: false,
        status: 'Clearing Shared Memory',
        detail: nodeSummary
      };
    }

    // Generic operation status
    return {
      isOperation: true,
      isElecting: false,
      status: `Operation Step ${progress.currentStep}/${progress.totalSteps}`,
      detail: nodeSummary || statusText
    };
  }

  // No active operation - show cluster health with full state
  const nodeSummary = buildNodeSummary();

  if (leader) {
    return {
      isOperation: false,
      isElecting: false,
      status: 'Cluster Healthy',
      detail: nodeSummary
    };
  }

  // No leader found - election needed
  return {
    isOperation: false,
    isElecting: true,
    status: 'No Quorum Established',
    detail: nodeSummary || `${onlineNodes} of 3 nodes available`
  };
}

export function ClusterAdmin({ isOpen, onClose }: ClusterAdminProps) {
  const [status, setStatus] = useState<ClusterStatus | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [selectedNode, setSelectedNode] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<OperationProgress | null>(null);
  const [wsConnected, setWsConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const statusPollRef = useRef<number | null>(null);

  // Fetch status via HTTP - this is the source of truth for node states
  const fetchStatus = useCallback(async () => {
    try {
      const response = await fetch('/api/admin/status');
      if (response.ok) {
        const data = await response.json();
        setStatus(data);
        setError(null);
      }
    } catch (e) {
      setError('Failed to fetch cluster status');
    }
  }, []);

  const fetchLogs = useCallback(async () => {
    try {
      const response = await fetch(`/api/admin/logs?node=${selectedNode}&lines=100`);
      if (response.ok) {
        const data = await response.json();
        setLogs(data.logs || []);
      }
    } catch (e) {
      // Ignore log fetch errors
    }
  }, [selectedNode]);

  // Connect to admin WebSocket for progress updates only
  useEffect(() => {
    if (!isOpen) return;

    const connectWebSocket = () => {
      const ws = new WebSocket(ADMIN_WS_URL);

      ws.onopen = () => {
        console.log('Admin WebSocket connected');
        setWsConnected(true);
        ws.send(JSON.stringify({ action: 'subscribe-admin' }));
      };

      ws.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);

          if (message.type === 'ADMIN_PROGRESS') {
            const data = message.data as OperationProgress;
            setProgress(data);

            // Reset progress after completion
            if (data.complete) {
              setTimeout(async () => {
                await fetch('/api/admin/progress?reset=true');
                setProgress(null);
              }, 3000);
            }
          }
        } catch (e) {
          console.error('Failed to parse WebSocket message:', e);
        }
      };

      ws.onclose = () => {
        console.log('Admin WebSocket disconnected');
        setWsConnected(false);
        setTimeout(() => {
          if (isOpen && wsRef.current === ws) {
            wsRef.current = null;
            connectWebSocket();
          }
        }, 2000);
      };

      ws.onerror = (err) => {
        console.error('Admin WebSocket error:', err);
      };

      wsRef.current = ws;
    };

    connectWebSocket();

    return () => {
      if (wsRef.current) {
        wsRef.current.close();
        wsRef.current = null;
      }
    };
  }, [isOpen]);

  // Poll status during active operations - this gives us real-time node states
  useEffect(() => {
    if (progress?.operation && !progress.complete) {
      // Poll every 200ms during operations for responsive UI
      statusPollRef.current = window.setInterval(fetchStatus, 200);
    } else if (statusPollRef.current) {
      clearInterval(statusPollRef.current);
      statusPollRef.current = null;
    }
    return () => {
      if (statusPollRef.current) {
        clearInterval(statusPollRef.current);
      }
    };
  }, [progress?.operation, progress?.complete, fetchStatus]);

  // Fetch status and logs on open, poll status every 3s normally
  useEffect(() => {
    if (isOpen) {
      fetchStatus();
      fetchLogs();
      const statusInterval = setInterval(fetchStatus, 3000);
      const logsInterval = setInterval(fetchLogs, 2000);
      return () => {
        clearInterval(statusInterval);
        clearInterval(logsInterval);
      };
    }
  }, [isOpen, fetchStatus, fetchLogs]);

  const restartNode = async (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    try {
      await fetch('/api/admin/restart-node', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodeId }),
      });
      // Status update will come via WebSocket
    } catch (e) {
      setError('Failed to restart node');
    }
  };

  const triggerOperation = async (endpoint: string) => {
    if (progress?.operation && !progress.complete) return;
    try {
      const response = await fetch(`/api/admin/${endpoint}`, { method: 'POST' });
      if (!response.ok) {
        const data = await response.json();
        setError(data.error || 'Operation failed');
      }
      // Progress updates will come via WebSocket
    } catch (e) {
      setError(`Failed to trigger ${endpoint}`);
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  };

  if (!isOpen) return null;

  const bannerStatus = getClusterBannerStatus(progress, status?.nodes || []);

  return (
    <div className="cluster-admin-overlay" onClick={onClose}>
      <div className="cluster-admin-panel" onClick={(e) => e.stopPropagation()}>
        <div className="admin-header">
          <h2>Cluster Administration</h2>
          <div className="header-right">
            <span className={`ws-indicator ${wsConnected ? 'connected' : ''}`} title={wsConnected ? 'Real-time updates active' : 'Connecting...'}>
              {wsConnected ? 'Live' : 'Connecting'}
            </span>
            <button className="close-btn" onClick={onClose}>&times;</button>
          </div>
        </div>

        {error && <div className="admin-error">{error}</div>}

        <div className="admin-content">
          {/* Cluster Status Banner - Always visible */}
          <div className={`election-banner ${bannerStatus.isElecting ? 'electing' : ''} ${bannerStatus.isOperation ? 'operation' : ''}`}>
            <span className={`status-indicator ${bannerStatus.isElecting ? 'warning' : bannerStatus.isOperation ? 'active' : 'healthy'}`}></span>
            <div className="election-info">
              <div className="election-title">{bannerStatus.status}</div>
              <div className="election-detail">{bannerStatus.detail}</div>
            </div>
          </div>

          {/* Cluster Nodes */}
          <section className="admin-section">
            <h3>Cluster Nodes</h3>
            <div className="nodes-grid">
              {status?.nodes.map((node) => {
                // Use real-time status from WebSocket, fallback to role
                const nodeState = node.status || node.role;
                const isTransitioning = ['STOPPING', 'STARTING', 'REJOINING', 'ELECTION'].includes(nodeState);
                const stateClass = nodeState.toLowerCase();

                // Determine status label
                const getStatusLabel = () => {
                  switch (nodeState) {
                    case 'STOPPING': return 'Stopping...';
                    case 'STARTING': return 'Starting...';
                    case 'REJOINING': return 'Rejoining...';
                    case 'ELECTION': return 'Election...';
                    case 'OFFLINE': return 'Offline';
                    case 'LEADER': return node.pid ? `PID: ${node.pid}` : 'Leader';
                    case 'FOLLOWER': return node.pid ? `PID: ${node.pid}` : 'Follower';
                    default: return node.running ? `PID: ${node.pid}` : 'Offline';
                  }
                };

                return (
                  <div
                    key={node.id}
                    className={`node-card ${stateClass} ${isTransitioning ? 'transitioning' : ''}`}
                  >
                    <div className="node-header">
                      <span className="node-id">Node {node.id}</span>
                      <span className={`node-role ${stateClass}`}>
                        {nodeState}
                      </span>
                    </div>
                    <div className="node-details">
                      <span className={`status-dot ${stateClass} ${isTransitioning ? 'pulsing' : ''}`}></span>
                      {getStatusLabel()}
                    </div>
                    <button
                      className="node-action-btn"
                      onClick={() => restartNode(node.id)}
                      disabled={!!(progress?.operation && !progress.complete)}
                    >
                      Restart
                    </button>
                  </div>
                );
              })}
            </div>
          </section>

          {/* Services */}
          <section className="admin-section">
            <h3>Services</h3>
            <div className="services-grid">
              <div className="service-card">
                <span className="service-name">Backup Node</span>
                <span className={`status-dot ${status?.backup.running ? 'online' : 'offline'}`}></span>
                {status?.backup.running ? `PID: ${status.backup.pid}` : 'Offline'}
              </div>
              <div className="service-card">
                <span className="service-name">HTTP Gateway</span>
                <span className="status-dot online"></span>
                Port {status?.gateway.port}
              </div>
              <div className="service-card">
                <span className="service-name">Archive (Apparent)</span>
                <span className="archive-size">{formatBytes(status?.archiveBytes || 0)}</span>
              </div>
              <div className="service-card">
                <span className="service-name">Archive (Disk)</span>
                <span className="archive-size disk">{formatBytes(status?.archiveDiskBytes || 0)}</span>
              </div>
            </div>
          </section>

          {/* Actions */}
          <section className="admin-section">
            <h3>Actions</h3>
            <div className="actions-grid">
              <ActionButton
                operation="rolling-update"
                label="Rolling Update"
                description="Update all nodes one-by-one"
                progress={progress}
                onClick={() => triggerOperation('rolling-update')}
              />
              <ActionButton
                operation="snapshot"
                label="Take Snapshot"
                description="Save state for recovery"
                progress={progress}
                onClick={() => triggerOperation('snapshot')}
              />
              <ActionButton
                operation="compact"
                label="Compact Archives"
                description="Clean up old log segments"
                progress={progress}
                onClick={() => triggerOperation('compact')}
              />
            </div>
          </section>

          {/* Logs */}
          <section className="admin-section logs-section">
            <div className="logs-header">
              <h3>Logs</h3>
              <select
                value={selectedNode}
                onChange={(e) => setSelectedNode(Number(e.target.value))}
              >
                <option value={0}>Node 0</option>
                <option value={1}>Node 1</option>
                <option value={2}>Node 2</option>
              </select>
            </div>
            <div className="logs-container">
              {logs
                .filter(line => line.includes('[ERROR]') || line.includes('Exception') || line.includes('SEVERE'))
                .map((line, i) => (
                  <div key={i} className="log-line error">
                    {line}
                  </div>
                ))}
              {logs.filter(line => line.includes('[ERROR]') || line.includes('Exception') || line.includes('SEVERE')).length === 0 && (
                <div className="log-line info">No errors in recent logs</div>
              )}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}

