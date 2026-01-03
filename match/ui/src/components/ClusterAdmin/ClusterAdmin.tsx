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
          {label}
        </span>
        <span className="action-desc">
          {isInProgress
            ? `${progressPercent}%`
            : isComplete
            ? (isError ? 'Failed' : 'Complete')
            : description}
        </span>
      </div>
    </button>
  );
}

// Helper to get cluster status - simplified to 4 states
function getClusterBannerStatus(progress: OperationProgress | null, nodes: NodeStatus[]): {
  status: 'healthy' | 'electing' | 'unstable' | 'building';
  title: string;
  detail: string;
} {
  // Check if building is in progress
  const isBuilding = progress?.operation === 'rolling-update' &&
    !progress.complete &&
    (progress.status?.toLowerCase().includes('building') ?? false);

  if (isBuilding) {
    return {
      status: 'building',
      title: 'Building Application',
      detail: progress?.status || 'Compiling...'
    };
  }

  const leader = nodes.find(n => n.role === 'LEADER');
  const electingNodes = nodes.filter(n => n.role === 'ELECTION');
  const isElecting = electingNodes.length > 0 ||
    (progress?.status?.includes('election') ?? false);

  // No leader and not electing = unstable
  if (!leader && !isElecting) {
    return {
      status: 'unstable',
      title: 'Cluster Unstable',
      detail: 'No leader - Loss of quorum'
    };
  }

  // Election in progress
  if (isElecting) {
    return {
      status: 'electing',
      title: 'Election in Progress',
      detail: 'Selecting new leader...'
    };
  }

  // Leader exists = healthy
  return {
    status: 'healthy',
    title: 'Cluster Healthy',
    detail: 'Leader elected'
  };
}

export function ClusterAdmin({ isOpen, onClose }: ClusterAdminProps) {
  const [status, setStatus] = useState<ClusterStatus | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [selectedNode, setSelectedNode] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<OperationProgress | null>(null);
  const [wsConnected, setWsConnected] = useState(false);
  const [serviceOps, setServiceOps] = useState<{backup: boolean; gateway: boolean}>({backup: false, gateway: false});
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
    } catch (e) {
      setError('Failed to restart node');
    }
  };

  const stopNode = async (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    try {
      await fetch('/api/admin/stop-node', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodeId }),
      });
    } catch (e) {
      setError('Failed to stop node');
    }
  };

  const startNode = async (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    try {
      await fetch('/api/admin/start-node', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodeId }),
      });
    } catch (e) {
      setError('Failed to start node');
    }
  };

  const stopBackup = async () => {
    if (serviceOps.backup || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, backup: true}));
    try {
      await fetch('/api/admin/stop-backup', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, backup: false})); fetchStatus(); }, 3000);
    } catch (e) {
      setError('Failed to stop backup');
      setServiceOps(prev => ({...prev, backup: false}));
    }
  };

  const startBackup = async () => {
    if (serviceOps.backup || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, backup: true}));
    try {
      await fetch('/api/admin/start-backup', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, backup: false})); fetchStatus(); }, 3000);
    } catch (e) {
      setError('Failed to start backup');
      setServiceOps(prev => ({...prev, backup: false}));
    }
  };

  const restartBackup = async () => {
    if (serviceOps.backup || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, backup: true}));
    try {
      await fetch('/api/admin/restart-backup', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, backup: false})); fetchStatus(); }, 5000);
    } catch (e) {
      setError('Failed to restart backup');
      setServiceOps(prev => ({...prev, backup: false}));
    }
  };

  const stopGateway = async () => {
    if (serviceOps.gateway || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, gateway: true}));
    try {
      await fetch('/api/admin/stop-gateway', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, gateway: false})); fetchStatus(); }, 3000);
    } catch (e) {
      setError('Failed to stop gateway');
      setServiceOps(prev => ({...prev, gateway: false}));
    }
  };

  const startGateway = async () => {
    if (serviceOps.gateway || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, gateway: true}));
    try {
      await fetch('/api/admin/start-gateway', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, gateway: false})); fetchStatus(); }, 3000);
    } catch (e) {
      setError('Failed to start gateway');
      setServiceOps(prev => ({...prev, gateway: false}));
    }
  };

  const restartGateway = async () => {
    if (serviceOps.gateway || (progress?.operation && !progress.complete)) return;
    setServiceOps(prev => ({...prev, gateway: true}));
    try {
      await fetch('/api/admin/restart-gateway', { method: 'POST' });
      setTimeout(() => { setServiceOps(prev => ({...prev, gateway: false})); fetchStatus(); }, 5000);
    } catch (e) {
      setError('Failed to restart gateway');
      setServiceOps(prev => ({...prev, gateway: false}));
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
          <div className={`election-banner ${bannerStatus.status}`}>
            <span className={`status-indicator ${bannerStatus.status}`}></span>
            <div className="election-info">
              <div className="election-title">{bannerStatus.title}</div>
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
                    <div className="node-actions">
                      {node.running && !isTransitioning ? (
                        <>
                          <button
                            className="icon-btn stop"
                            onClick={() => stopNode(node.id)}
                            disabled={isTransitioning || !!(progress?.operation && !progress.complete)}
                            title="Stop"
                          >
                            &#9632;
                          </button>
                          <button
                            className="icon-btn restart"
                            onClick={() => restartNode(node.id)}
                            disabled={isTransitioning || !!(progress?.operation && !progress.complete)}
                            title="Restart"
                          >
                            &#8635;
                          </button>
                        </>
                      ) : !node.running && !isTransitioning ? (
                        <button
                          className="icon-btn start"
                          onClick={() => startNode(node.id)}
                          disabled={isTransitioning || !!(progress?.operation && !progress.complete)}
                          title="Start"
                        >
                          &#9654;
                        </button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          </section>

          {/* Services */}
          <section className="admin-section">
            <h3>Services</h3>
            <div className="services-grid">
              <div className={`service-card ${serviceOps.backup ? 'operating' : ''}`}>
                <div className="service-header">
                  <span className="service-name">Backup Node</span>
                  <span className={`status-dot ${serviceOps.backup ? 'pulsing' : ''} ${status?.backup.running ? 'online' : 'offline'}`}></span>
                </div>
                <span className="service-status">
                  {serviceOps.backup ? 'Processing...' : status?.backup.running ? `PID: ${status.backup.pid}` : 'Offline'}
                </span>
                <div className="service-actions">
                  {!serviceOps.backup && status?.backup.running ? (
                    <>
                      <button
                        className="icon-btn stop"
                        onClick={stopBackup}
                        disabled={serviceOps.backup || !!(progress?.operation && !progress.complete)}
                        title="Stop"
                      >
                        &#9632;
                      </button>
                      <button
                        className="icon-btn restart"
                        onClick={restartBackup}
                        disabled={serviceOps.backup || !!(progress?.operation && !progress.complete)}
                        title="Restart"
                      >
                        &#8635;
                      </button>
                    </>
                  ) : !serviceOps.backup ? (
                    <button
                      className="icon-btn start"
                      onClick={startBackup}
                      disabled={serviceOps.backup || !!(progress?.operation && !progress.complete)}
                      title="Start"
                    >
                      &#9654;
                    </button>
                  ) : null}
                </div>
              </div>
              <div className={`service-card ${serviceOps.gateway ? 'operating' : ''}`}>
                <div className="service-header">
                  <span className="service-name">HTTP Gateway</span>
                  <span className={`status-dot ${serviceOps.gateway ? 'pulsing' : ''} ${status?.gateway.running ? 'online' : 'offline'}`}></span>
                </div>
                <span className="service-status">
                  {serviceOps.gateway ? 'Processing...' : `Port ${status?.gateway.port}`}
                </span>
                <div className="service-actions">
                  {!serviceOps.gateway && status?.gateway.running ? (
                    <>
                      <button
                        className="icon-btn stop"
                        onClick={stopGateway}
                        disabled={serviceOps.gateway || !!(progress?.operation && !progress.complete)}
                        title="Stop"
                      >
                        &#9632;
                      </button>
                      <button
                        className="icon-btn restart"
                        onClick={restartGateway}
                        disabled={serviceOps.gateway || !!(progress?.operation && !progress.complete)}
                        title="Restart"
                      >
                        &#8635;
                      </button>
                    </>
                  ) : !serviceOps.gateway ? (
                    <button
                      className="icon-btn start"
                      onClick={startGateway}
                      disabled={serviceOps.gateway || !!(progress?.operation && !progress.complete)}
                      title="Start"
                    >
                      &#9654;
                    </button>
                  ) : null}
                </div>
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

