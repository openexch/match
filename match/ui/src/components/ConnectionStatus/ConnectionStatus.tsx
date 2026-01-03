import type { ExtendedConnectionStatus, ClusterState } from '../../types/market';
import './ConnectionStatus.css';

interface ConnectionStatusProps {
  status: ExtendedConnectionStatus;
  clusterState?: ClusterState;
  onReconnect: () => void;
}

export function ConnectionStatus({ status, clusterState, onReconnect }: ConnectionStatusProps) {
  const statusText: Record<ExtendedConnectionStatus, string> = {
    connecting: 'Connecting...',
    connected: 'Connected',
    disconnected: 'Disconnected',
    error: 'Connection Error',
    'cluster-updating': 'Updating...',
    'cluster-electing': 'Election...',
    'cluster-reconnecting': 'Reconnecting...',
  };

  // Determine CSS class based on status
  const getStatusClass = () => {
    if (status === 'cluster-updating' || status === 'cluster-electing' || status === 'cluster-reconnecting') {
      return 'cluster-transition';
    }
    return status;
  };

  // Show reconnect button for disconnected/error states
  const showReconnect = status === 'disconnected' || status === 'error';

  // Show leader info when connected and cluster state is available
  const showLeader = status === 'connected' && clusterState && clusterState.leaderId >= 0;

  return (
    <div className={`connection-status ${getStatusClass()}`}>
      <span className="status-indicator" />
      <span className="status-text">{statusText[status]}</span>
      {showLeader && (
        <span className="leader-badge" title="Current cluster leader">
          L{clusterState.leaderId}
        </span>
      )}
      {showReconnect && (
        <button onClick={onReconnect} className="reconnect-btn">
          Reconnect
        </button>
      )}
    </div>
  );
}
