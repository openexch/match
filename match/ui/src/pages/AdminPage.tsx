import { useState, useEffect, useCallback, useRef } from 'react';
import { Link } from 'react-router-dom';
import './AdminPage.css';

type NodeStatusType = 'LEADER' | 'FOLLOWER' | 'OFFLINE' | 'STOPPING' | 'STARTING' | 'REJOINING' | 'ELECTION';

interface NodeStatus {
  id: number;
  running: boolean;
  pid?: number;
  role: NodeStatusType;
  status?: NodeStatusType;
  healthy?: boolean;
  // Per-node data
  logPosition?: number;      // From recording-log (stale, term boundaries only)
  commitPosition?: number;   // Real-time from Aeron counters
  snapshotPosition?: number;
  logDelta?: number;         // commitPosition - snapshotPosition
  snapshotCount?: number;
  archiveBytes?: number;
  archiveDiskBytes?: number;
}

interface GatewayStatus {
  running: boolean;
  port: number;
}

interface ClusterStatus {
  nodes: NodeStatus[];
  leader: number;
  backup: { running: boolean; pid?: number };
  gateway: { running: boolean; port: number };
  gateways: {
    market: GatewayStatus;
    order: GatewayStatus;
    admin: GatewayStatus;
  };
  // Archive is now per-node (in NodeStatus), these are deprecated
  archiveBytes?: number;
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

interface ProcessInfo {
  name: string;
  display: string;
  role: 'cluster' | 'gateway' | 'infra';
  port: number;
  running: boolean;
  pid: number;
  memoryBytes: number;
  cpuPercent: number;
  uptimeMs: number;
  startedAt: string;
  restartCount: number;
  enabled: boolean;
  status: string;
}

interface ProcessSummary {
  total: number;
  running: number;
  stopped: number;
  failed: number;
  totalMemoryMB: number;
  lastPollMs: number;
}

type LogSource =
  | { type: 'node'; id: number }
  | { type: 'service'; name: string };

type ConfirmAction = {
  type: 'stop-node' | 'restart-node' | 'start-node' |
        'process-action' | 'self-update' |
        'rolling-update' | 'rolling-cleanup' | 'stop-all-nodes' | 'start-all-nodes' | 'cleanup';
  nodeId?: number;
  service?: string;
  action?: 'start' | 'stop' | 'restart';
  title: string;
  message: string;
  confirmLabel: string;
  confirmStyle: 'danger' | 'warning' | 'primary';
};

// Icons
const Icons = {
  back: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M19 12H5M12 19l-7-7 7-7"/>
    </svg>
  ),
  server: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="2" y="2" width="20" height="8" rx="2"/>
      <rect x="2" y="14" width="20" height="8" rx="2"/>
      <circle cx="6" cy="6" r="1" fill="currentColor"/>
      <circle cx="6" cy="18" r="1" fill="currentColor"/>
    </svg>
  ),
  backup: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5"/>
    </svg>
  ),
  market: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polyline points="22,7 13.5,15.5 8.5,10.5 2,17"/>
      <polyline points="16,7 22,7 22,13"/>
    </svg>
  ),
  order: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="3" y="3" width="18" height="18" rx="2"/>
      <path d="M9 9h6M9 13h6M9 17h4"/>
    </svg>
  ),
  admin: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="3"/>
      <path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/>
    </svg>
  ),
  folder: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
    </svg>
  ),
  database: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <ellipse cx="12" cy="5" rx="9" ry="3"/>
      <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/>
      <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
    </svg>
  ),
  stop: (
    <svg viewBox="0 0 24 24" fill="currentColor">
      <rect x="6" y="6" width="12" height="12" rx="1"/>
    </svg>
  ),
  restart: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M1 4v6h6M23 20v-6h-6"/>
      <path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"/>
    </svg>
  ),
  play: (
    <svg viewBox="0 0 24 24" fill="currentColor">
      <polygon points="5,3 19,12 5,21"/>
    </svg>
  ),
  update: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M21 2v6h-6M3 12a9 9 0 0 1 15-6.7L21 8M3 22v-6h6M21 12a9 9 0 0 1-15 6.7L3 16"/>
    </svg>
  ),
  snapshot: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="3" y="3" width="18" height="18" rx="2"/>
      <circle cx="12" cy="12" r="3"/>
    </svg>
  ),
  logs: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
      <path d="M14 2v6h6M16 13H8M16 17H8M10 9H8"/>
    </svg>
  ),
  x: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
      <line x1="18" y1="6" x2="6" y2="18"/>
      <line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  ),
  archive: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="2" y="3" width="20" height="5" rx="1"/>
      <path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8"/>
      <path d="M10 12h4"/>
    </svg>
  ),
  info: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10"/>
      <line x1="12" y1="16" x2="12" y2="12"/>
      <line x1="12" y1="8" x2="12.01" y2="8"/>
    </svg>
  ),
  ui: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <rect x="2" y="3" width="20" height="14" rx="2"/>
      <path d="M8 21h8M12 17v4"/>
    </svg>
  ),
};

function getClusterStatus(progress: OperationProgress | null, nodes: NodeStatus[]): {
  status: 'healthy' | 'electing' | 'unstable' | 'updating';
  title: string;
  detail: string;
} {
  if (progress?.operation === 'rolling-update' && !progress.complete) {
    return {
      status: 'updating',
      title: 'Rolling Update',
      detail: progress.status || 'Updating cluster...'
    };
  }

  if (progress?.operation === 'rolling-cleanup' && !progress.complete) {
    return {
      status: 'updating',
      title: 'Rolling Cleanup',
      detail: progress.status || 'Cleaning archive...'
    };
  }

  const leader = nodes.find(n => n.role === 'LEADER');
  const electingNodes = nodes.filter(n => n.role === 'ELECTION');
  const isElecting = electingNodes.length > 0;

  if (!leader && !isElecting) {
    return {
      status: 'unstable',
      title: 'Cluster Unstable',
      detail: 'No leader elected'
    };
  }

  if (isElecting) {
    return {
      status: 'electing',
      title: 'Leader Election',
      detail: 'Selecting new leader...'
    };
  }

  return {
    status: 'healthy',
    title: 'Cluster Healthy',
    detail: `Node ${leader?.id} is leader`
  };
}

function getLogSourceLabel(source: LogSource | null): string {
  if (!source) return 'Select a service or node to view logs';
  if (source.type === 'node') return `Node ${source.id}`;
  switch (source.name) {
    case 'backup': return 'Backup Node';
    case 'market-gateway': return 'Market Gateway';
    case 'order-gateway': return 'Order Gateway';
    case 'admin-gateway': return 'Admin Gateway';
    case 'ui': return 'Trading UI';
    default: return source.name;
  }
}

function processToLogName(name: string): string {
  switch (name) {
    case 'market': return 'market-gateway';
    case 'order': return 'order-gateway';
    case 'admin': return 'admin-gateway';
    default: return name;
  }
}

function getProcessIcon(name: string) {
  switch (name) {
    case 'backup': return Icons.backup;
    case 'market': return Icons.market;
    case 'order': return Icons.order;
    case 'admin': return Icons.admin;
    case 'ui': return Icons.ui;
    default: return Icons.server;
  }
}

function formatUptime(ms: number): string {
  if (ms <= 0) return '--';
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (days > 0) return `${days}d ${hours % 24}h`;
  if (hours > 0) return `${hours}h ${minutes % 60}m`;
  if (minutes > 0) return `${minutes}m`;
  return `${seconds}s`;
}

export function AdminPage() {
  const [status, setStatus] = useState<ClusterStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<OperationProgress | null>(null);
  const [processes, setProcesses] = useState<ProcessInfo[]>([]);
  const [processSummary, setProcessSummary] = useState<ProcessSummary | null>(null);
  const [operatingServices, setOperatingServices] = useState<Set<string>>(new Set());
  const [snapshotOp, setSnapshotOp] = useState(false);
  const [logSource, setLogSource] = useState<LogSource | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [logFilters, setLogFilters] = useState({ error: true, warn: true, info: true, debug: true });
  const [pendingAction, setPendingAction] = useState<ConfirmAction | null>(null);
  const statusPollRef = useRef<number | null>(null);
  const logsRef = useRef<HTMLDivElement>(null);

  const fetchStatus = useCallback(async () => {
    try {
      const response = await fetch('/api/admin/status');
      if (response.ok) {
        const data = await response.json() as ClusterStatus;
        setStatus(data);
        setError(null);
      }
    } catch {
      setError('Failed to fetch cluster status');
    }
  }, []);

  const fetchProgress = useCallback(async () => {
    try {
      const response = await fetch('/api/admin/progress');
      if (response.ok) {
        const data = await response.json() as OperationProgress;
        if (data.operation || data.currentStep > 0) {
          setProgress(data);
          if (data.complete) {
            setTimeout(async () => {
              await fetch('/api/admin/progress?reset=true');
              setProgress(null);
            }, 3000);
          }
        }
      }
    } catch {
      // Ignore
    }
  }, []);

  const fetchLogs = useCallback(async () => {
    if (!logSource) return;
    try {
      let url = '/api/admin/logs?lines=200';
      if (logSource.type === 'node') {
        url += `&node=${logSource.id}`;
      } else {
        url += `&service=${logSource.name}`;
      }
      const response = await fetch(url);
      if (response.ok) {
        const data = await response.json();
        setLogs(data.logs || []);
      }
    } catch {
      // Ignore
    }
  }, [logSource]);

  const fetchProcesses = useCallback(async () => {
    try {
      const [listRes, summaryRes] = await Promise.all([
        fetch('/api/admin/processes'),
        fetch('/api/admin/processes/summary'),
      ]);
      if (listRes.ok) {
        setProcesses(await listRes.json());
      }
      if (summaryRes.ok) {
        setProcessSummary(await summaryRes.json());
      }
    } catch {
      // Ignore
    }
  }, []);

  useEffect(() => {
    if (progress?.operation && !progress.complete) {
      // Fast polling (50ms) during active operations for accurate progress display
      statusPollRef.current = window.setInterval(() => {
        fetchStatus();
        fetchProgress();
      }, 50);
    } else if (statusPollRef.current) {
      clearInterval(statusPollRef.current);
      statusPollRef.current = null;
    }
    return () => {
      if (statusPollRef.current) {
        clearInterval(statusPollRef.current);
      }
    };
  }, [progress?.operation, progress?.complete, fetchStatus, fetchProgress]);

  useEffect(() => {
    fetchStatus();
    fetchProgress();
    const interval = setInterval(() => {
      fetchStatus();
      fetchProgress();
    }, 3000);
    return () => clearInterval(interval);
  }, [fetchStatus, fetchProgress]);

  useEffect(() => {
    fetchProcesses();
    const interval = setInterval(fetchProcesses, 5000);
    return () => clearInterval(interval);
  }, [fetchProcesses]);

  useEffect(() => {
    if (logSource) {
      fetchLogs();
      const interval = setInterval(fetchLogs, 2000);
      return () => clearInterval(interval);
    }
  }, [logSource, fetchLogs]);

  // Auto-scroll logs
  useEffect(() => {
    if (logsRef.current) {
      logsRef.current.scrollTop = logsRef.current.scrollHeight;
    }
  }, [logs]);

  // ── Node action handlers (unchanged — still use /api/admin/status for transitional state) ──

  const requestStopNode = (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'stop-node',
      nodeId,
      title: `Stop Node ${nodeId}?`,
      message: 'This will stop the cluster node. The cluster will continue with remaining nodes.',
      confirmLabel: 'Stop Node',
      confirmStyle: 'danger',
    });
  };

  const requestRestartNode = (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'restart-node',
      nodeId,
      title: `Restart Node ${nodeId}?`,
      message: 'This will restart the cluster node. It will temporarily leave the cluster and rejoin.',
      confirmLabel: 'Restart Node',
      confirmStyle: 'warning',
    });
  };

  const requestStartNode = (nodeId: number) => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'start-node',
      nodeId,
      title: `Start Node ${nodeId}?`,
      message: 'This will start the cluster node and it will attempt to rejoin the cluster.',
      confirmLabel: 'Start Node',
      confirmStyle: 'primary',
    });
  };

  const requestStopAllNodes = () => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'stop-all-nodes',
      title: 'Stop All Nodes?',
      message: 'This will stop all cluster nodes. The cluster will become completely unavailable.',
      confirmLabel: 'Stop All',
      confirmStyle: 'danger',
    });
  };

  const requestStartAllNodes = () => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'start-all-nodes',
      title: 'Start All Nodes?',
      message: 'This will start all cluster nodes and form a new cluster.',
      confirmLabel: 'Start All',
      confirmStyle: 'primary',
    });
  };

  const requestCleanup = () => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'cleanup',
      title: 'Clean Aeron State?',
      message: 'This will remove stale Aeron files (shared memory, locks). All nodes must be stopped first.',
      confirmLabel: 'Clean State',
      confirmStyle: 'warning',
    });
  };

  const executeNodeAction = async (action: string, nodeId: number) => {
    try {
      await fetch(`/api/admin/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nodeId }),
      });
    } catch {
      setError(`Failed to ${action.replace('-', ' ')}`);
    }
  };

  // ── Generic process action handler (replaces all per-service handlers) ──

  const requestProcessAction = (service: string, action: 'start' | 'stop' | 'restart') => {
    if (operatingServices.has(service) || (progress?.operation && !progress.complete)) return;

    const displayName = processes.find(p => p.name === service)?.display || service;
    const actionLabel = action.charAt(0).toUpperCase() + action.slice(1);

    const descriptions: Record<string, Record<string, string>> = {
      stop: {
        backup: 'This will stop the backup node. Cluster snapshots will not be available until restarted.',
        market: 'This will stop the market data WebSocket. Clients will lose real-time market updates.',
        order: 'This will stop the order API. Order submission will be unavailable.',
        admin: 'This will stop the admin gateway. You will lose access to this dashboard.',
        ui: 'This will stop the trading UI. Users will not be able to access the web interface.',
      },
      start: {
        backup: 'This will start the backup node to enable cluster state backups.',
        market: 'This will start the market data WebSocket for real-time updates.',
        order: 'This will start the order API for order submission.',
        admin: 'This will start the admin gateway.',
        ui: 'This will start the trading UI web interface.',
      },
      restart: {
        backup: 'This will restart the backup node. Backup service will be temporarily unavailable.',
        market: 'This will restart the market gateway. Clients will be temporarily disconnected.',
        order: 'This will restart the order gateway. Order submission will be temporarily unavailable.',
        admin: 'This will restart the admin gateway. You will temporarily lose access to this dashboard.',
        ui: 'This will restart the trading UI. Users will experience a brief interruption.',
      },
    };

    const styles: Record<string, 'danger' | 'warning' | 'primary'> = {
      stop: 'danger', start: 'primary', restart: 'warning',
    };

    setPendingAction({
      type: 'process-action',
      service,
      action,
      title: `${actionLabel} ${displayName}?`,
      message: descriptions[action]?.[service] || `This will ${action} the ${displayName} service.`,
      confirmLabel: actionLabel,
      confirmStyle: styles[action],
    });
  };

  const executeProcessAction = async (service: string, action: string) => {
    setOperatingServices(prev => new Set(prev).add(service));
    try {
      await fetch(`/api/admin/processes/${service}/${action}`, { method: 'POST' });
      const timeout = action === 'restart' ? 8000 : 3000;
      setTimeout(() => {
        setOperatingServices(prev => {
          const next = new Set(prev);
          next.delete(service);
          return next;
        });
        fetchProcesses();
      }, timeout);
    } catch {
      setError(`Failed to ${action} ${service}`);
      setOperatingServices(prev => {
        const next = new Set(prev);
        next.delete(service);
        return next;
      });
    }
  };

  // ── Self-update (admin gateway rebuild) ──

  const requestSelfUpdate = () => {
    if (operatingServices.has('admin') || (progress?.operation && !progress.complete)) return;
    setPendingAction({
      type: 'self-update',
      title: 'Self-Update Admin Gateway?',
      message: 'This will rebuild the admin gateway from source and restart it. You will temporarily lose access to this dashboard.',
      confirmLabel: 'Self-Update',
      confirmStyle: 'warning',
    });
  };

  const executeSelfUpdate = async () => {
    setOperatingServices(prev => new Set(prev).add('admin'));
    try {
      await fetch('/api/admin/rebuild-admin', { method: 'POST' });
      // Admin will restart automatically — connection will drop
    } catch {
      setError('Failed to trigger self-update');
      setOperatingServices(prev => {
        const next = new Set(prev);
        next.delete('admin');
        return next;
      });
    }
  };

  // ── Snapshot ──

  const takeSnapshot = async () => {
    if (snapshotOp || (progress?.operation && !progress.complete)) return;
    setSnapshotOp(true);
    try {
      await fetch('/api/admin/snapshot', { method: 'POST' });
      setTimeout(() => { setSnapshotOp(false); }, 5000);
    } catch {
      setError('Failed to take snapshot');
      setSnapshotOp(false);
    }
  };

  // ── Rolling operations ──

  const requestRollingUpdate = () => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'rolling-update',
      title: 'Start Rolling Update?',
      message: 'This will rebuild the application and restart all cluster nodes one by one. The cluster will remain available during the update.',
      confirmLabel: 'Start Update',
      confirmStyle: 'warning',
    });
  };

  const executeRollingUpdate = async () => {
    try {
      const response = await fetch('/api/admin/rolling-update', { method: 'POST' });
      if (!response.ok) {
        const data = await response.json();
        setError(data.error || 'Rolling update failed');
      }
    } catch {
      setError('Failed to trigger rolling update');
    }
  };

  const requestRollingCleanup = () => {
    if (progress?.operation && !progress.complete) return;
    setPendingAction({
      type: 'rolling-cleanup',
      title: 'Start Rolling Cleanup?',
      message: 'This will clean archive segments on each node one by one to free disk space. The cluster will remain available during the cleanup.',
      confirmLabel: 'Start Cleanup',
      confirmStyle: 'warning',
    });
  };

  const executeRollingCleanup = async () => {
    try {
      const response = await fetch('/api/admin/rolling-cleanup', { method: 'POST' });
      if (!response.ok) {
        const data = await response.json();
        setError(data.error || 'Rolling cleanup failed');
      }
    } catch {
      setError('Failed to trigger rolling cleanup');
    }
  };

  const executeStopAllNodes = async () => {
    try {
      await fetch('/api/admin/stop-all-nodes', { method: 'POST' });
    } catch {
      setError('Failed to stop all nodes');
    }
  };

  const executeStartAllNodes = async () => {
    try {
      await fetch('/api/admin/start-all-nodes', { method: 'POST' });
    } catch {
      setError('Failed to start all nodes');
    }
  };

  const executeCleanup = async () => {
    try {
      const response = await fetch('/api/admin/cleanup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ force: true }),
      });
      const data = await response.json();
      if (!data.success) {
        setError(data.error || 'Cleanup failed');
      }
    } catch {
      setError('Failed to cleanup state');
    }
  };

  // ── Confirm action dispatch ──

  const confirmAction = async () => {
    if (!pendingAction) return;
    const action = pendingAction;
    setPendingAction(null);

    switch (action.type) {
      case 'stop-node':
      case 'restart-node':
      case 'start-node':
        if (action.nodeId !== undefined) {
          await executeNodeAction(action.type, action.nodeId);
        }
        break;
      case 'process-action':
        if (action.service && action.action) {
          await executeProcessAction(action.service, action.action);
        }
        break;
      case 'self-update':
        await executeSelfUpdate();
        break;
      case 'rolling-update':
        await executeRollingUpdate();
        break;
      case 'rolling-cleanup':
        await executeRollingCleanup();
        break;
      case 'stop-all-nodes':
        await executeStopAllNodes();
        break;
      case 'start-all-nodes':
        await executeStartAllNodes();
        break;
      case 'cleanup':
        await executeCleanup();
        break;
    }
  };

  // ── Formatters ──

  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
  };

  // Format log/snapshot positions with K, M, G suffixes
  const formatPosition = (pos: number | undefined): string => {
    if (pos === undefined || pos < 0) return '--';
    if (pos < 1000) return pos.toString();
    if (pos < 1000000) return `${(pos / 1000).toFixed(1)}K`;
    if (pos < 1000000000) return `${(pos / 1000000).toFixed(1)}M`;
    return `${(pos / 1000000000).toFixed(2)}G`;
  };

  const isLogSelected = (source: LogSource) => {
    if (!logSource) return false;
    if (source.type === 'node' && logSource.type === 'node') {
      return source.id === logSource.id;
    }
    if (source.type === 'service' && logSource.type === 'service') {
      return source.name === logSource.name;
    }
    return false;
  };

  const getLogLevel = (line: string): 'error' | 'warn' | 'info' | 'debug' => {
    const lower = line.toLowerCase();
    if (lower.includes('[error]') || lower.includes('exception') || lower.includes('severe') || lower.includes('failed')) {
      return 'error';
    }
    if (lower.includes('[warn]') || lower.includes('warning')) {
      return 'warn';
    }
    if (lower.includes('[info]') || lower.includes('[gateway]') || lower.includes('started') || lower.includes('connected')) {
      return 'info';
    }
    return 'debug';
  };

  const filteredLogs = logs.filter(line => {
    const level = getLogLevel(line);
    return logFilters[level];
  });

  const toggleFilter = (filter: 'error' | 'warn' | 'info' | 'debug') => {
    setLogFilters(prev => ({ ...prev, [filter]: !prev[filter] }));
  };

  // ── Derived state ──

  const clusterStatus = getClusterStatus(progress, status?.nodes || []);
  const isOperationRunning = !!(progress?.operation && !progress.complete);
  const operationProgress = isOperationRunning ? (progress?.progress || 0) : 0;
  const serviceProcesses = processes.filter(p => p.role !== 'cluster');

  return (
    <div className="admin-page">
      {/* Header */}
      <header className="admin-header">
        <Link to="/" className="back-link">
          {Icons.back}
          <span>Trading</span>
        </Link>
        <h1>Cluster Admin</h1>
      </header>

      {/* Error Banner */}
      {error && (
        <div className="error-banner">
          {Icons.x}
          <span>{error}</span>
          <button onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}

      {/* Cluster Status Bar */}
      {!status ? (
        <div className="skeleton skeleton-status-bar" />
      ) : (
        <div className={`status-bar ${clusterStatus.status}`}>
          <div className="status-bar-progress" style={{ width: `${operationProgress}%` }} />
          <div className="status-bar-content">
            <div className="status-info">
              <span className={`status-dot ${clusterStatus.status}`} />
              <div className="status-text">
                <span className="status-title">{clusterStatus.title}</span>
                <span className="status-detail">{clusterStatus.detail}</span>
              </div>
            </div>
            {isOperationRunning ? (
              <div className="update-progress">
                <span className="progress-text">{operationProgress}%</span>
              </div>
            ) : (
              <div className="status-bar-actions">
                <button
                  className="update-btn"
                  onClick={requestRollingUpdate}
                  disabled={isOperationRunning}
                >
                  {Icons.update}
                  <span>Rolling Update</span>
                </button>
                <button
                  className="cleanup-btn"
                  onClick={requestRollingCleanup}
                  disabled={isOperationRunning}
                >
                  {Icons.archive}
                  <span>Rolling Cleanup</span>
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      <main className="admin-main">
        {/* Cluster Nodes */}
        <section className="admin-section">
          <div className="section-header">
            {Icons.server}
            <h2>Cluster Nodes</h2>
            <div className="bulk-actions">
              <button
                className="btn-bulk stop"
                onClick={requestStopAllNodes}
                disabled={isOperationRunning}
                title="Stop All Nodes"
              >
                {Icons.stop}
                <span>Stop All</span>
              </button>
              <button
                className="btn-bulk start"
                onClick={requestStartAllNodes}
                disabled={isOperationRunning}
                title="Start All Nodes"
              >
                {Icons.play}
                <span>Start All</span>
              </button>
              <button
                className="btn-bulk cleanup"
                onClick={requestCleanup}
                disabled={isOperationRunning}
                title="Clean Aeron State"
              >
                {Icons.restart}
                <span>Cleanup</span>
              </button>
            </div>
          </div>
          <div className="nodes-grid">
            {!status ? (
              <>
                <div className="node-card skeleton skeleton-node" />
                <div className="node-card skeleton skeleton-node" />
                <div className="node-card skeleton skeleton-node" />
              </>
            ) : status.nodes.map((node) => {
              const nodeState = node.status || node.role;
              const isTransitioning = ['STOPPING', 'STARTING', 'REJOINING', 'ELECTION'].includes(nodeState);
              const stateClass = nodeState.toLowerCase();
              const logSelected = isLogSelected({ type: 'node', id: node.id });
              const nodeProc = processes.find(p => p.name === `node${node.id}`);

              return (
                <div key={node.id} className={`node-card ${stateClass}`}>
                  <div className="node-header">
                    <span className="node-id">Node {node.id}</span>
                    <span className={`node-role ${stateClass}`}>{nodeState}</span>
                  </div>
                  <div className="node-status">
                    <span className={`status-dot ${stateClass} ${isTransitioning ? 'pulsing' : ''}`} />
                    <span className="status-text">
                      {nodeState === 'OFFLINE' ? 'Stopped' :
                       isTransitioning ? nodeState.charAt(0) + nodeState.slice(1).toLowerCase() + '...' :
                       node.pid ? `PID ${node.pid}` : 'Running'}
                    </span>
                  </div>
                  <div className="node-data">
                    <div className="node-data-row">
                      <span className="data-label">Commit:</span>
                      <span className="data-value">{formatPosition(node.commitPosition)}</span>
                      <span className="data-label">Snap:</span>
                      <span className="data-value">{formatPosition(node.snapshotPosition)}</span>
                    </div>
                    <div className="node-data-row">
                      <span className="data-label">Delta:</span>
                      <span className="data-value delta">
                        {formatPosition(node.logDelta)}
                      </span>
                      <span className="data-label">Archive:</span>
                      <span className="data-value">{node.archiveBytes !== undefined ? formatBytes(node.archiveBytes) : '--'}</span>
                      <span className="info-trigger">
                        {Icons.info}
                        <div className="node-popover">
                          <div className="popover-title">Node Details</div>
                          <div className="popover-row">
                            <span>Commit Position:</span>
                            <span>{node.commitPosition !== undefined ? node.commitPosition.toLocaleString() : '--'}</span>
                          </div>
                          <div className="popover-row">
                            <span>Snapshot Position:</span>
                            <span>{node.snapshotPosition !== undefined ? node.snapshotPosition.toLocaleString() : '--'}</span>
                          </div>
                          <div className="popover-row">
                            <span>Delta (since snapshot):</span>
                            <span>{node.logDelta !== undefined ? node.logDelta.toLocaleString() : '--'}</span>
                          </div>
                          <div className="popover-row">
                            <span>Snapshot Count:</span>
                            <span>{node.snapshotCount !== undefined ? node.snapshotCount : '--'}</span>
                          </div>
                          <div className="popover-divider" />
                          <div className="popover-row">
                            <span>Archive Size:</span>
                            <span>{node.archiveBytes !== undefined ? formatBytes(node.archiveBytes) : '--'}</span>
                          </div>
                          <div className="popover-row">
                            <span>Disk Usage:</span>
                            <span>{node.archiveDiskBytes !== undefined ? formatBytes(node.archiveDiskBytes) : '--'}</span>
                          </div>
                        </div>
                      </span>
                    </div>
                    {nodeProc && nodeProc.running && (
                      <div className="node-data-row">
                        <span className="data-label">Mem:</span>
                        <span className="data-value">{formatBytes(nodeProc.memoryBytes)}</span>
                        <span className="data-label">CPU:</span>
                        <span className="data-value">{(nodeProc.cpuPercent ?? 0).toFixed(1)}%</span>
                        <span className="data-label">Up:</span>
                        <span className="data-value">{formatUptime(nodeProc.uptimeMs)}</span>
                      </div>
                    )}
                  </div>
                  <div className="node-actions">
                    {node.running && !isTransitioning ? (
                      <>
                        <button className="btn-icon stop" onClick={() => requestStopNode(node.id)} disabled={isOperationRunning} title="Stop">
                          {Icons.stop}
                        </button>
                        <button className="btn-icon restart" onClick={() => requestRestartNode(node.id)} disabled={isOperationRunning} title="Restart">
                          {Icons.restart}
                        </button>
                      </>
                    ) : !node.running && !isTransitioning ? (
                      <button className="btn-icon start" onClick={() => requestStartNode(node.id)} disabled={isOperationRunning} title="Start">
                        {Icons.play}
                      </button>
                    ) : null}
                    <button
                      className={`btn-icon logs ${logSelected ? 'active' : ''}`}
                      onClick={() => setLogSource({ type: 'node', id: node.id })}
                      title="View Logs"
                    >
                      {Icons.logs}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </section>

        {/* Services — powered by Process Manager */}
        <section className="admin-section">
          <div className="section-header">
            {Icons.server}
            <h2>Services</h2>
          </div>
          {processSummary && (
            <div className="process-summary-bar">
              <div className="summary-stat running">
                <span className="summary-count">{processSummary.running}</span>
                <span className="summary-label">Running</span>
              </div>
              <div className="summary-stat stopped">
                <span className="summary-count">{processSummary.stopped}</span>
                <span className="summary-label">Stopped</span>
              </div>
              {processSummary.failed > 0 && (
                <div className="summary-stat failed">
                  <span className="summary-count">{processSummary.failed}</span>
                  <span className="summary-label">Failed</span>
                </div>
              )}
              <div className="summary-stat memory">
                <span className="summary-count">
                  {processSummary.totalMemoryMB > 1024
                    ? `${(processSummary.totalMemoryMB / 1024).toFixed(1)} GB`
                    : `${Math.round(processSummary.totalMemoryMB)} MB`}
                </span>
                <span className="summary-label">Total Memory</span>
              </div>
            </div>
          )}
          <div className="services-grid">
            {processes.length === 0 ? (
              <>
                <div className="service-card skeleton skeleton-service" />
                <div className="service-card skeleton skeleton-service" />
                <div className="service-card skeleton skeleton-service" />
                <div className="service-card skeleton skeleton-service" />
              </>
            ) : (
              serviceProcesses.map((proc) => {
                const isOperating = operatingServices.has(proc.name);
                const logName = processToLogName(proc.name);
                const logSelected = isLogSelected({ type: 'service', name: logName });

                return (
                  <div key={proc.name} className={`service-card ${isOperating ? 'operating' : ''}`}>
                    <div className="service-main">
                      <div className="service-icon">{getProcessIcon(proc.name)}</div>
                      <div className="service-info">
                        <span className="service-name">
                          {proc.display}
                          {' '}
                          <span className={`role-badge ${proc.role}`}>{proc.role}</span>
                        </span>
                        <span className="service-status">
                          {isOperating
                            ? 'Processing...'
                            : `${proc.status}${proc.running && proc.port > 0 ? ` :${proc.port}` : ''}`}
                        </span>
                      </div>
                      <span className={`status-dot ${isOperating ? 'pulsing' : ''} ${proc.status}`} />
                    </div>
                    {proc.running && (
                      <div className="process-metrics">
                        <span className="metric">PID <span className="metric-value">{proc.pid}</span></span>
                        <span className="metric">Mem <span className="metric-value">{formatBytes(proc.memoryBytes)}</span></span>
                        <span className="metric">CPU <span className="metric-value">{(proc.cpuPercent ?? 0).toFixed(1)}%</span></span>
                        <span className="metric">Up <span className="metric-value">{formatUptime(proc.uptimeMs)}</span></span>
                      </div>
                    )}
                    <div className="service-actions">
                      {!isOperating && proc.running ? (
                        <>
                          <button className="btn-icon stop" onClick={() => requestProcessAction(proc.name, 'stop')} disabled={isOperationRunning || isOperating} title="Stop">{Icons.stop}</button>
                          <button className="btn-icon restart" onClick={() => requestProcessAction(proc.name, 'restart')} disabled={isOperationRunning || isOperating} title="Restart">{Icons.restart}</button>
                          {proc.name === 'backup' && (
                            <button
                              className={`btn-icon snapshot ${snapshotOp ? 'active' : ''}`}
                              onClick={takeSnapshot}
                              disabled={snapshotOp || isOperationRunning}
                              title="Take Snapshot"
                            >
                              {Icons.snapshot}
                            </button>
                          )}
                          {proc.name === 'admin' && (
                            <button
                              className="btn-icon self-update"
                              onClick={requestSelfUpdate}
                              disabled={isOperationRunning || isOperating}
                              title="Self-Update"
                            >
                              {Icons.update}
                            </button>
                          )}
                        </>
                      ) : !isOperating ? (
                        <button className="btn-icon start" onClick={() => requestProcessAction(proc.name, 'start')} disabled={isOperationRunning || isOperating} title="Start">{Icons.play}</button>
                      ) : null}
                      <button
                        className={`btn-icon logs ${logSelected ? 'active' : ''}`}
                        onClick={() => setLogSource({ type: 'service', name: logName })}
                        title="View Logs"
                      >
                        {Icons.logs}
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </section>

        {/* Log Viewer */}
        <section className="admin-section logs-section">
          <div className="section-header">
            {Icons.logs}
            <h2>{getLogSourceLabel(logSource)}</h2>
            {logSource && (
              <>
                <div className="log-filters">
                  <button
                    className={`log-filter-btn error ${logFilters.error ? 'active' : ''}`}
                    onClick={() => toggleFilter('error')}
                  >
                    Error
                  </button>
                  <button
                    className={`log-filter-btn warn ${logFilters.warn ? 'active' : ''}`}
                    onClick={() => toggleFilter('warn')}
                  >
                    Warn
                  </button>
                  <button
                    className={`log-filter-btn info ${logFilters.info ? 'active' : ''}`}
                    onClick={() => toggleFilter('info')}
                  >
                    Info
                  </button>
                  <button
                    className={`log-filter-btn debug ${logFilters.debug ? 'active' : ''}`}
                    onClick={() => toggleFilter('debug')}
                  >
                    Debug
                  </button>
                </div>
                <button className="clear-logs-btn" onClick={() => setLogSource(null)}>
                  Clear
                </button>
              </>
            )}
          </div>
          <div className="logs-container" ref={logsRef}>
            {logSource ? (
              filteredLogs.length > 0 ? (
                filteredLogs.map((line, i) => {
                  const level = getLogLevel(line);
                  return (
                    <div key={i} className={`log-line ${level}`}>
                      {line}
                    </div>
                  );
                })
              ) : (
                <div className="log-line placeholder">No logs match the current filters</div>
              )
            ) : (
              <div className="log-line placeholder">Click a log button on any node or service to view its logs</div>
            )}
          </div>
        </section>
      </main>

      {/* Confirmation Modal */}
      {pendingAction && (
        <div className="confirm-overlay" onClick={() => setPendingAction(null)}>
          <div className="confirm-modal" onClick={(e) => e.stopPropagation()}>
            <h3>{pendingAction.title}</h3>
            <p>{pendingAction.message}</p>
            <div className="confirm-actions">
              <button className="confirm-btn cancel" onClick={() => setPendingAction(null)}>
                Cancel
              </button>
              <button className={`confirm-btn ${pendingAction.confirmStyle}`} onClick={confirmAction}>
                {pendingAction.confirmLabel}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}